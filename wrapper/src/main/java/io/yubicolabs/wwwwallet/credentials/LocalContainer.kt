@file:OptIn(ExperimentalStdlibApi::class, ExperimentalUuidApi::class)

package io.yubicolabs.wwwwallet.credentials

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64.NO_PADDING
import android.util.Base64.NO_WRAP
import android.util.Base64.URL_SAFE
import android.util.Base64.encodeToString
import com.upokecenter.cbor.CBORObject
import io.yubicolabs.wwwwallet.BuildConfig
import io.yubicolabs.wwwwallet.json.getNested
import io.yubicolabs.wwwwallet.json.toList
import io.yubicolabs.wwwwallet.json.toMap
import io.yubicolabs.wwwwallet.logging.YOLOLogger
import io.yubicolabs.wwwwallet.tagForLog
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStore.Entry
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val DEFAULT_KEY_STORE = "AndroidKeyStore"

class LocalContainer(
    val context: Context,
    // TODO REPLACE ME WITH AN ACTUAL REGISTERED AAGUID
    val aaguid: Uuid = Uuid.random(),
) : Container {
    private val origin: String = BuildConfig.APPLICATION_ID
    private val storage: KeyStore = KeyStore.getInstance(DEFAULT_KEY_STORE).apply { load(null) }
    val isStrongBoxed: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun create(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) = try {
        // todo check rp id and options
        val credentialId = ByteArray(32)
        SecureRandom().nextBytes(credentialId)

        val challenge = (
            (options.getNested("publicKey.challenge") as? String)?.decodeBase64()?.toByteArray()
                ?: byteArrayOf()
        )
        val specBuilder =
            KeyGenParameterSpec
                .Builder(
                    // keystoreAlias =
                    "$origin+${credentialId.toHexString()}",
                    // purposes =
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                .setAlgorithmParameterSpec(
                    // TODO Update algorithm name to match requested
                    ECGenParameterSpec("secp256r1"),
                )
                .setIsStrongBoxBacked(
                    isStrongBoxed,
                ).setDigests(
                    // TODO: CHECK AND REMOVE NOT SPECIFIED DIGEST ALGOS
                    KeyProperties.DIGEST_SHA256,
                ).setAttestationChallenge(
                    challenge,
                ).setUserPresenceRequired(true)

        val spec = specBuilder.build()
        val keyPairGen =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                DEFAULT_KEY_STORE,
            )
        keyPairGen.initialize(spec)

        val keyPair = keyPairGen.genKeyPair()

        val clientDataJson =
            encodeToString(
                getClientOptions(
                    type = "webauthn.create",
                    challenge = String(challenge),
                    origin = origin,
                ),
                NO_PADDING or NO_WRAP or URL_SAFE,
            )

        val attestationObject =
            createAttestationObject(
                rpId = origin,
                credentialId = credentialId,
                publicKey = keyPair.public.encoded,
                // TODO: CHECK WITH OPTIONS
                requireUserVerification = true,
                // TODO: STORE THIS?
                signatureCount = 0,
            )

        val authenticatorData =
            createAuthenticatorData(
                rpId = origin,
                // TODO: VERIFIY WITH OPTIONS,
                userPresence = true,
                // TODO: STORE
                signatureCounter = 0,
                // TODO?
                userVerification = true,
                attestedCredentialData = createAttestedCredentialData(credentialId, keyPair.public.encoded),
                // TODO add extensions
                extensions = null,
            )

        val response =
            JSONObject(
                mapOf(
                    "clientDataJSON" to clientDataJson,
                    "attestationObject" to attestationObject,
                    "transports" to JSONArray(arrayOf("internal", "hybrid")),
                    "authenticatorData" to authenticatorData,
                    "publicKeyAlgorithm" to -7,
                    "publicKey" to
                        encodeToString(
                            keyPair.public.encoded,
                            NO_PADDING or NO_WRAP or URL_SAFE,
                        ),
                ),
            )

        val credential =
            JSONObject(
                mapOf(
                    "rawId" to
                        encodeToString(
                            credentialId,
                            NO_PADDING or NO_WRAP or URL_SAFE,
                        ),
                    "id" to
                        encodeToString(
                            credentialId,
                            NO_PADDING or NO_WRAP or URL_SAFE,
                        ),
                    "type" to "public-key",
                    "authenticatorAttachment" to "platform",
                    "response" to response,
                    "clientExtensionResults" to null,
                    // TODO: Add extensions, etc...
                ),
            )

        YOLOLogger.i(tagForLog, "DEBUG ME")

        successCallback(credential)
    } catch (th: Throwable) {
        YOLOLogger.e(tagForLog, "Cannot create credential.", th)
        failureCallback(th)
    }

    private fun createAttestationObject(
        rpId: String,
        credentialId: ByteArray,
        publicKey: ByteArray,
        requireUserVerification: Boolean,
        signatureCount: Int,
    ): String? {
        val attestedCredentialData = createAttestedCredentialData(credentialId, publicKey)

        val authenticatorData =
            createAuthenticatorData(
                rpId,
                true,
                requireUserVerification,
                signatureCount,
                attestedCredentialData,
                null,
            )

        return encodeToString(
            CBORObject.NewMap()
                .Add("fmt", "none")
                .Add("attStmt", CBORObject.NewMap())
                .Add("authData", authenticatorData)
                .EncodeToBytes(),
            NO_PADDING or NO_WRAP or URL_SAFE,
        )
    }

    private fun createAttestedCredentialData(
        credentialId: ByteArray,
        cosePublicKey: ByteArray,
    ): ByteArray {
        val attestedCredentialDataLength = 16 + 2 + credentialId.size + cosePublicKey.size
        return ByteBuffer.allocate(attestedCredentialDataLength)
            .order(ByteOrder.BIG_ENDIAN)
            .put(aaguid.toByteArray(), 0, 16)
            .putShort(credentialId.size.toShort())
            .put(credentialId)
            .put(cosePublicKey)
            .array()
    }

    private fun createAuthenticatorData(
        rpId: String,
        userPresence: Boolean,
        userVerification: Boolean,
        signatureCounter: Int,
        attestedCredentialData: ByteArray,
        extensions: ByteArray?,
    ): ByteArray {
        val sha256 =
            try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException("SHA-256 is not available", e)
            }

        val rpIdHash = sha256.digest(rpId.toByteArray())
        val ed = extensions != null
        val at = attestedCredentialData != null

        val flags = generateAuthenticatorDataFlags(ed, at, userVerification, userPresence)

        val authenticatorData =
            ByteBuffer.allocate(32 + 1 + 4 + (if (at) attestedCredentialData.size else 0) + (if (ed) extensions.size else 0))
                .order(ByteOrder.BIG_ENDIAN)
                .put(rpIdHash, 0, 32)
                .put(flags)
                .putInt(signatureCounter)
        if (at) {
            authenticatorData.put(attestedCredentialData)
        }
        if (ed) {
            authenticatorData.put(extensions)
        }
        return authenticatorData.array()
    }

    private fun generateAuthenticatorDataFlags(
        ed: Boolean,
        at: Boolean,
        uv: Boolean,
        up: Boolean,
    ): Byte {
        var flags = 0
        if (ed) {
            flags++
        }
        flags = flags shl 1
        if (at) {
            flags++
        }
        flags = flags shl 4
        if (uv) {
            flags++
        }
        flags = flags shl 2
        if (up) {
            flags++
        }
        return flags.toByte()
    }

    override fun get(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) = try {
        val allowedCredentials: List<Map<String, Any?>> =
            (
                (options.getNested("publicKey.allowCredentials") as? JSONArray)?.toList()
                    ?: listOf()
            ).mapNotNull {
                (it as? JSONObject)?.toMap()
            }

        // retrieve all credentials
        val selectedCredentials =
            storage.aliases().toList().associate { alias ->
                alias to storage.getEntry(alias, null)
            }.toMutableMap()

        for (allowed in allowedCredentials) {
            val allowedId = allowed.getOrDefault("id", null) as? String ?: ""
            val alias = "$origin+$allowedId"

            if (origin !in alias || !storage.containsAlias(alias)) {
                selectedCredentials[alias] = null
            }
        }

        val finalSelection = selectedCredentials.filter { it.value != null }
        if (finalSelection.isNotEmpty()) {
            // TODO MULTIPLE MATCHES??

            val firstKey = finalSelection.keys.first()
            val first = finalSelection[firstKey]!!
            val credentialResponse = first.toResponse(firstKey)

            successCallback(
                credentialResponse,
            )
        } else {
            val ids =
                allowedCredentials
                    .joinToString(separator = ",") {
                        (it as? JSONObject)?.get("id") as? String ?: "null"
                    }

            failureCallback(
                NoSuchElementException("No credential with id in [$ids] found in credential storage."),
            )
        }
    } catch (th: Throwable) {
        failureCallback(th)
    }

    private fun Entry.toResponse(id: String): JSONObject {
        // TODO: Transform into correct response
        val json = JSONObject()

        val credentialId = id.replace("$origin+", "")
        json.put("id", credentialId)
        json.put("type", "public-key")
        json.put("raw_id", credentialId.hexToByteArray())
        json.put("authenticator_attachment", "")
        json.put("response", "")
        json.put("client_extension_results", null)

        return json
    }
}
