package org.siros.wwwallet.proximity

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siros.sdk.credentials.CredentialFamily
import org.siros.sdk.credentials.StoredCredential
import org.siros.sdk.keystore.mdoc.ActiveEngagement
import org.siros.sdk.keystore.mdoc.BleCentralClient
import org.siros.sdk.keystore.mdoc.BlePeripheralServer
import org.siros.sdk.keystore.mdoc.DeviceEngagement
import org.siros.sdk.keystore.mdoc.NfcHandoverSelect
import org.siros.sdk.keystore.mdoc.ProximityConsentResult
import org.siros.sdk.keystore.mdoc.ReaderTrustResult
import org.siros.wwwallet.bridging.JsCallHost
import timber.log.Timber
import java.util.Base64

/**
 * ISO 18013-5 proximity presentation, hosted natively.
 *
 * The wrapper used to expose GATT as eight bridge methods and let the page
 * run the protocol over them. This replaces that with a session: the page
 * starts one and gets an engagement URI back for its QR code, the SDK runs
 * device engagement, both BLE roles, session establishment, reader
 * authentication and device-response assembly, and it calls back into the
 * page for the three things the page still owns.
 *
 * Everything protocol-shaped here comes from the SDK. This class is wiring:
 * it turns seven injected lambdas into bridge calls and back.
 */
class ProximityBridge(
    private val context: Context,
    private val calls: JsCallHost,
    private val scope: CoroutineScope,
) {
    /** Which BLE role the wallet plays. The engagement always offers both; this picks the one to actually start. */
    enum class Mode {
        /** mdoc peripheral server: the wallet is the GATT server and advertises. */
        PERIPHERAL,

        /** mdoc central client: the wallet is the GATT client and scans for the reader. */
        CENTRAL,
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private var peripheral: BlePeripheralServer? = null
    private var central: BleCentralClient? = null

    /**
     * Starts a session and returns the engagement for the page to render.
     *
     * Returns as soon as the transport is up: the session itself continues in
     * the background and reports through `proximity.step` and
     * `proximity.complete`. Callers get `{ mdocUri, mode }`.
     */
    fun start(paramsJson: String): JsonObject {
        stop()

        val mode = parseMode(paramsJson)
        val engagement =
            DeviceEngagement.create(
                supportsCentralClientMode = true,
                supportsPeripheralServerMode = true,
            )

        // The SDK's HostApduService is OS-instantiated, so it reads the active
        // engagement from this singleton rather than taking it by constructor.
        ActiveEngagement.handoverSelectBytes = NfcHandoverSelect.build(engagement)
        ActiveEngagement.onHandoverServed = { calls.notify(STEP, stepPayload("nfc_handover_served")) }

        when (mode) {
            Mode.PERIPHERAL ->
                peripheral =
                    BlePeripheralServer(
                        context = context,
                        engagement = engagement,
                        getHandoverSelectBytes = { ActiveEngagement.handoverSelectBytes },
                        getCredentials = ::getCredentials,
                        signPresentation = ::signPresentation,
                        requestConsent = ::requestConsent,
                        filterEligible = ::filterEligible,
                        evaluateReaderTrust = ::evaluateReaderTrust,
                        onStep = ::onStep,
                        onComplete = ::onComplete,
                    ).also { it.start() }

            Mode.CENTRAL ->
                central =
                    BleCentralClient(
                        context = context,
                        engagement = engagement,
                        getHandoverSelectBytes = { ActiveEngagement.handoverSelectBytes },
                        getCredentials = ::getCredentials,
                        signPresentation = ::signPresentation,
                        requestConsent = ::requestConsent,
                        filterEligible = ::filterEligible,
                        evaluateReaderTrust = ::evaluateReaderTrust,
                        onStep = ::onStep,
                        onComplete = ::onComplete,
                    ).also { it.start() }
        }

        Timber.i("Proximity session started in $mode mode.")
        return buildJsonObject {
            put("mdocUri", JsonPrimitive(engagement.mdocUri))
            put("mode", JsonPrimitive(mode.name.lowercase()))
        }
    }

    /** Tears down whichever transport is running. Safe to call when none is. */
    fun stop() {
        peripheral?.let {
            runCatching { it.stop() }.onFailure { e -> Timber.w(e, "Stopping the peripheral server failed.") }
        }
        central?.let {
            runCatching { it.stop() }.onFailure { e -> Timber.w(e, "Stopping the central client failed.") }
        }
        peripheral = null
        central = null
        ActiveEngagement.handoverSelectBytes = null
        ActiveEngagement.onHandoverServed = null
    }

    // ── The page's side of the session ──────────────────────────────────────

    /**
     * The page returns the credentials it is willing to present. It filters for
     * eligibility itself, which is why [filterEligible] below is the identity:
     * consumption policy and presentation history live in the page's own
     * wallet state, so it is the only side that can apply them.
     */
    private suspend fun getCredentials(): List<StoredCredential> {
        val result = calls.call(CREDENTIALS, JsonNull)
        return result.jsonArray.map { json.decodeFromJsonElement(StoredCredential.serializer(), it) }
    }

    /** See [getCredentials]: the page has already filtered. */
    private suspend fun filterEligible(candidates: List<StoredCredential>): List<StoredCredential> = candidates

    /**
     * The page signs, because the device key never leaves it. This is the
     * callback that makes the bridge bidirectional; everything else here
     * could have been one-way.
     */
    private suspend fun signPresentation(
        credentialId: Long,
        disclosedClaims: List<String>?,
        sessionTranscriptBytes: ByteArray,
    ): ByteArray {
        val payload =
            buildJsonObject {
                put("credentialId", JsonPrimitive(credentialId))
                put(
                    "disclosedClaims",
                    disclosedClaims?.let { claims -> buildJsonArray { claims.forEach { add(JsonPrimitive(it)) } } } ?: JsonNull,
                )
                put("sessionTranscript", JsonPrimitive(b64(sessionTranscriptBytes)))
            }
        val result = calls.call(SIGN, payload)
        val encoded =
            result.jsonObject["deviceResponse"]?.jsonPrimitive?.content
                ?: throw JsCallHost.JsCallException("bad_reply", "$SIGN returned no deviceResponse")
        return unb64(encoded)
    }

    /**
     * The page shows the prompt and returns a choice. Families are identified
     * by their representative's credential id, which the page already knows
     * from the list it supplied, so it can match its own display metadata
     * without this bridge having to carry any.
     */
    private suspend fun requestConsent(
        docType: String,
        requestedClaims: List<String>,
        matchingFamilies: List<CredentialFamily>,
        readerTrust: ReaderTrustResult?,
    ): ProximityConsentResult {
        val payload =
            buildJsonObject {
                put("docType", JsonPrimitive(docType))
                put("requestedClaims", buildJsonArray { requestedClaims.forEach { add(JsonPrimitive(it)) } })
                put(
                    "families",
                    buildJsonArray {
                        matchingFamilies.forEach { family ->
                            add(
                                buildJsonObject {
                                    put("credentialId", JsonPrimitive(family.representative.id))
                                    put("batchId", JsonPrimitive(family.representative.batchId))
                                    put("instances", JsonPrimitive(family.instances.size))
                                },
                            )
                        }
                    },
                )
                put(
                    "readerTrust",
                    readerTrust?.let {
                        buildJsonObject {
                            put("trusted", JsonPrimitive(it.trusted))
                            put("reason", it.reason?.let(::JsonPrimitive) ?: JsonNull)
                            put("entityName", it.entityName?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    } ?: JsonNull,
                )
            }

        val result = calls.call(CONSENT, payload).jsonObject
        val approved = result["approved"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (!approved) return ProximityConsentResult.Denied

        val chosenId = result["credentialId"]?.jsonPrimitive?.content?.toLongOrNull()
        val family =
            matchingFamilies.firstOrNull { it.representative.id == chosenId }
                ?: matchingFamilies.firstOrNull()
                ?: return ProximityConsentResult.Denied
        return ProximityConsentResult.Approved(family)
    }

    /**
     * REVIEW POINT. Reader trust belongs on this side of the bridge, not the
     * page's: both clients ask go-trust for the same decision, but only a
     * native client has a fallback when go-trust is unreachable, and a
     * checkpoint is exactly where it is unreachable.
     *
     * It is a callback here because the SDK's own reader-trust evaluation -
     * AuthZEN against go-trust with local certificate-path validation as the
     * fallback, including the distinction between a reader the backend
     * refused and a backend that could not be reached - is only reachable
     * through the wallet facade, which a web-view host deliberately does not
     * construct. Until that evaluation is reachable without it, this defers
     * to the page, which means an offline session cannot make a trust
     * decision at all.
     */
    private suspend fun evaluateReaderTrust(x5chain: List<ByteArray>): ReaderTrustResult {
        val payload =
            buildJsonObject {
                put("x5chain", buildJsonArray { x5chain.forEach { add(JsonPrimitive(b64(it))) } })
            }
        return try {
            val result = calls.call(READER_TRUST, payload).jsonObject
            ReaderTrustResult(
                trusted = result["trusted"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                reason = result["reason"]?.jsonPrimitive?.contentOrNullSafe(),
                entityName = result["entityName"]?.jsonPrimitive?.contentOrNullSafe(),
            )
        } catch (e: JsCallHost.JsCallException) {
            // Fail closed: an unanswered trust question is not a trusted reader.
            Timber.w(e, "Reader trust evaluation failed; treating the reader as untrusted.")
            ReaderTrustResult(trusted = false, reason = "trust evaluation unavailable: ${e.code}")
        }
    }

    private fun onStep(step: String) = calls.notify(STEP, stepPayload(step))

    private fun onComplete(success: Boolean) {
        calls.notify(
            COMPLETE,
            buildJsonObject { put("success", JsonPrimitive(success)) },
        )
        stop()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun stepPayload(step: String): JsonObject = buildJsonObject { put("step", JsonPrimitive(step)) }

    private fun parseMode(paramsJson: String): Mode {
        val requested =
            runCatching {
                json
                    .parseToJsonElement(paramsJson)
                    .jsonObject["mode"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        return when (requested?.lowercase()) {
            "central" -> Mode.CENTRAL
            "peripheral", null, "" -> Mode.PERIPHERAL
            else -> {
                Timber.w("Unknown proximity mode '$requested'; using peripheral server mode.")
                Mode.PERIPHERAL
            }
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    companion object {
        /** Handler names the page registers. Kept together so the contract is readable in one place. */
        const val CREDENTIALS = "proximity.credentials"
        const val SIGN = "proximity.sign"
        const val CONSENT = "proximity.consent"
        const val READER_TRUST = "proximity.readerTrust"
        const val STEP = "proximity.step"
        const val COMPLETE = "proximity.complete"
    }
}
