package org.siros.wwwallet.credentials.dcApi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.RegistrationRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.siros.wwwallet.MainViewModel
import org.siros.wwwallet.json.DcApiCredential
import org.siros.wwwallet.json.DcApiField
import org.siros.wwwallet.storage.Settings
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DigitalCredentials {
    val exchangeProtocols = setOf("openid4vp", "openid4vp-v1-signed", "openid4vp-v1-unsigned")

    /** Exchange protocols understood by the metadata registrar's bundled matcher. */
    val metadataExchangeProtocols: Set<String> =
        setOf(
            "openid4vp",
            "openid4vp-v1-signed",
            "openid4vp-v1-unsigned",
            "openid4vp-v1-multisigned",
            "org-iso-mdoc",
        )

    private val lock = Mutex()
    private val metadataRegistrationLock = Mutex()

    suspend fun registerStoredCredentials(context: Context) {
        lock.withLock {
            register(context, Settings.getDcApiCredentials())
        }
    }

    suspend fun updateCredentials(
        context: Context,
        credentials: List<DcApiCredential>,
        callbackUrl: String?,
    ) {
        lock.withLock {
            val allCredentials = Settings.getDcApiCredentials().toMutableMap()
            val fallback =
                allCredentials[MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL]?.toMutableList()
                    ?: mutableListOf()
            fallback.removeAll { old -> credentials.any { it.id == old.id } }

            if (!callbackUrl.isNullOrBlank()) {
                allCredentials[callbackUrl] = credentials
            } else {
                fallback.addAll(credentials)
            }

            if (fallback.isEmpty()) {
                allCredentials.remove(MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL)
            } else {
                allCredentials[MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL] = fallback
            }

            Settings.setDcApiCredentials(allCredentials)

            val metadata = createMetadata(context, allCredentials)
            registerCredentialMetadata(context, metadata, exchangeProtocols)

            Timber.i("Registered %d digital credential metadata entries", metadata.size)
        }
    }

    private suspend fun register(
        context: Context,
        credentials: Map<String, List<DcApiCredential>>,
    ) {
        val metadata = createMetadata(context, credentials)
        registerCredentialMetadata(context, metadata, exchangeProtocols)

        Timber.i("Registered %d stored digital credential metadata entries", metadata.size)
    }

    private fun createMetadata(
        context: Context,
        credentials: Map<String, List<DcApiCredential>>,
    ): List<DigitalCredentialMetadata> {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = createBitmap(48, 48)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        val icon =
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode credential icon" }
                ByteString(it.toByteArray())
            }

        return credentials.flatMap { (callbackUrl, entries) ->
            entries.mapNotNull { credential ->
                try {
                    credential.toMatcherMetadata(
                        CredentialReference(
                            callbackUrl,
                            credential.id,
                        ).encode(),
                        icon,
                    )
                } catch (_: Throwable) {
                    Timber.w("Failed to index credential %s for callback %s", credential.id, callbackUrl)
                    null
                }
            }
        }
    }

    /**
     * Replaces the application's Android registration with a metadata-only index.
     * The application remains responsible for validating requests and producing presentations.
     * Both platform registration tasks are awaited; failures propagate to the caller.
     * An empty list removes the previously indexed credentials.
     */
    suspend fun registerCredentialMetadata(
        context: Context,
        credentials: List<DigitalCredentialMetadata>,
        selectedProtocols: Set<String>,
        fulfillmentActionName: String = "androidx.credentials.registry.provider.action.GET_CREDENTIAL",
    ) {
        require(fulfillmentActionName.isNotBlank()) { "Missing fulfillment action" }

        val database = Cbor.encode(calculateMetadataDatabase(credentials, selectedProtocols))

        metadataRegistrationLock.withLock {
            val matcher = context.assets.open("identitycredentialmatcher.wasm").use { it.readBytes() }
            val client = IdentityCredentialManager.getClient(context.applicationContext)

            for (type in listOf("com.credman.IdentityCredential", "androidx.credentials.TYPE_DIGITAL_CREDENTIAL")) {
                suspendCancellableCoroutine { continuation ->
                    client
                        .registerCredentials(
                            RegistrationRequest(
                                credentials = database,
                                matcher = matcher,
                                type = type,
                                requestType = "",
                                protocolTypes = emptyList(),
                                id = context.packageName,
                                fulfillmentActionName = fulfillmentActionName,
                            ),
                        ).addOnSuccessListener {
                            continuation.resume(Unit)
                        }.addOnFailureListener {
                            continuation.resumeWithException(it)
                        }.addOnCanceledListener {
                            continuation.cancel()
                        }
                }
            }
        }
    }

    internal fun calculateMetadataDatabase(
        credentials: List<DigitalCredentialMetadata>,
        selectedProtocols: Set<String>,
    ): DataItem {
        require(metadataExchangeProtocols.containsAll(selectedProtocols)) { "Unsupported exchange protocol" }
        require(credentials.map { it.id }.distinct().size == credentials.size) { "Duplicate credential IDs" }

        for (credential in credentials) {
            require(credential.id.isNotBlank() && credential.id.none { it.isWhitespace() }) {
                "Credential ID must be non-empty and contain no whitespace"
            }

            require((credential.mdocDocType != null) != (credential.sdJwtVct != null)) {
                "Exactly one credential format must be specified"
            }

            require(requireNotNull(credential.mdocDocType ?: credential.sdJwtVct).isNotBlank()) { "Empty credential type" }

            require(
                credential.claims.all { claim ->
                    claim.path.isNotEmpty() &&
                        claim.path.all { it.isNotBlank() } &&
                        (credential.mdocDocType == null || claim.path.size == 2)
                },
            ) { "Invalid claim path" }

            require(
                credential.claims
                    .map { it.path.joinToString(".") }
                    .distinct()
                    .size == credential.claims.size,
            ) {
                "Duplicate claim paths"
            }
        }

        return buildCborMap {
            putCborArray("protocols") { selectedProtocols.sorted().forEach { add(it) } }
            putCborArray("credentials") {
                for (credential in credentials.sortedBy { it.id }) {
                    add(
                        buildCborMap {
                            put("title", credential.title)
                            put("subtitle", credential.subtitle)
                            put("bitmap", credential.bitmap.toByteArray())
                            putCborMap(if (credential.mdocDocType != null) "mdoc" else "sdjwt") {
                                put("documentId", credential.id)
                                if (credential.mdocDocType != null) {
                                    put("docType", credential.mdocDocType)
                                } else {
                                    put("vct", requireNotNull(credential.sdJwtVct))
                                }
                                if (credential.issuerIdentifiers.isNotEmpty()) {
                                    putCborArray("issuerIdentifiers") {
                                        credential.issuerIdentifiers.forEach { add(it.toByteArray()) }
                                    }
                                }
                                if (credential.mdocDocType != null) {
                                    putCborMap("namespaces") {
                                        for ((namespace, claims) in credential.claims.groupBy { it.path[0] }) {
                                            putCborMap(namespace) {
                                                for (claim in claims) {
                                                    putCborArray(claim.path[1]) {
                                                        add(claim.displayName)
                                                        add(claim.displayValue)
                                                        add(claim.matchValue)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    putCborMap("claims") {
                                        for (claim in credential.claims) {
                                            putCborArray(claim.path.joinToString(".")) {
                                                add(claim.displayName)
                                                add(claim.displayValue)
                                                add(claim.matchValue)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun DcApiCredential.toMatcherMetadata(
    selectionId: String,
    bitmap: ByteString,
): DigitalCredentialMetadata {
    val indexedClaims =
        when (format) {
            DcApiCredential.FORMAT_MDOC -> {
                require(!docType.isNullOrBlank()) { "Missing mdoc document type for credential $id" }
                val legacyFields =
                    claims.orEmpty().map {
                        DcApiField(it.path.substringBeforeLast("."), it.path.substringAfterLast("."), it.value, it.display)
                    }
                (fields.orEmpty() + legacyFields)
                    .distinctBy { it.namespace to it.identifier }
                    .map {
                        val isSeed = it.namespace == "eu.europa.ec.eudi.pid.1" && it.identifier == "pseudonym_seed"
                        require(!isSeed || (it.value != null && it.value != JsonNull)) { "Missing pseudonym seed metadata for credential $id" }
                        val value = if (isSeed) "" else it.value.matchValue()
                        DigitalCredentialClaimMetadata(
                            listOf(it.namespace, it.identifier),
                            DcApiField.resolveDisplayName(it.display, it.identifier),
                            value,
                            value,
                        )
                    }
            }
            DcApiCredential.FORMAT_SD_JWT -> {
                require(!verifiableCredentialType.isNullOrBlank()) { "Missing SD-JWT type for credential $id" }
                claims.orEmpty().map {
                    val value = it.value.matchValue()
                    DigitalCredentialClaimMetadata(
                        it.path.split("."),
                        DcApiField.resolveDisplayName(it.display, it.path.substringAfterLast(".")),
                        value,
                        value,
                    )
                }
            }
            else -> throw IllegalArgumentException("Unsupported credential format: $format")
        }

    return DigitalCredentialMetadata(
        id = selectionId,
        title = display.title,
        subtitle = display.subtitle.orEmpty(),
        bitmap = bitmap,
        claims = indexedClaims,
        mdocDocType = if (format == DcApiCredential.FORMAT_MDOC) docType else null,
        sdJwtVct = if (format == DcApiCredential.FORMAT_SD_JWT) verifiableCredentialType else null,
    )
}

private fun JsonElement?.matchValue(): String =
    when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }
