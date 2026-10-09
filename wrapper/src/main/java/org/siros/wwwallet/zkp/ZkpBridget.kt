package org.siros.wwwallet.zkp

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.siros.sdk.credentials.COSE_ALG_ES256
import org.siros.sdk.credentials.CredentialMatcher
import org.siros.sdk.credentials.CredentialUtils
import org.siros.sdk.credentials.StoredCredential
import org.siros.sdk.credentials.VerifierIdentity
import org.siros.sdk.credentials.ZkCircuitClient
import org.siros.sdk.credentials.ZkSystemSpec
import org.siros.sdk.keystore.LongfellowZkProofSystem
import org.siros.sdk.keystore.MdocDeviceResponseBuilder
import org.siros.sdk.keystore.ZkMdocPresentation
import org.siros.wwwallet.bridging.JsCallHost
import timber.log.Timber
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Zero-knowledge mdoc presentation (`"mso_mdoc_zk"`), hosted natively.
 *
 * Same split as [org.siros.wwwallet.proximity.ProximityBridge]: the page owns
 * the credential, the device key and the decision to present; the SDK owns
 * everything protocol-shaped. Here that is DCQL matching against the one
 * credential the page chose, picking a proof system, building the
 * SessionTranscript, loading circuits and running the prover - none of which
 * the page can do, since the provers are native.
 *
 * ### Page -> native
 *
 * - `capabilities()` -> `{ zkSystems: string[], busy: boolean }`. Use
 *   `zkSystems` to decide whether to offer a ZK presentation at all.
 * - `generate(params)` -> `{ proofId }`, immediately. The proof itself runs in
 *   the background and reports through `zkp.step` and `zkp.complete`.
 *   Starting a new proof cancels a running one, as starting a new proximity
 *   session stops the old one.
 * - `cancel()`.
 *
 * `params`:
 * ```
 * {
 *   credential:        StoredCredential,      // same JSON shape proximity.credentials returns
 *   dcqlQuery:         object,                // the verifier's full dcql_query, forwarded as is;
 *                                             // native picks the proof system from its zk_system_type
 *   credentialQueryId: string?,               // which query to answer; default: the first one the credential matches
 *   transcript:
 *       { kind: "openid4vp", clientId, nonce, responseUri, verifierJwkThumbprint? }
 *     | { kind: "dc_api",    origin,   nonce, encryptionJwkThumbprint? }
 *     | { kind: "raw",       sessionTranscript }          // base64, already CBOR-encoded
 *   verifier:          { clientId, sessionId? }?          // required only when pairwise_pseudonym is requested
 * }
 * ```
 *
 * ### Native -> page (handlers the page registers)
 *
 * - `zkp.sign` (call) `{ proofId, credentialId, algorithm: "ES256", data }`
 *   -> `{ signature }`. `data` is the message to sign (base64), not a digest:
 *   sign it with SHA-256/P-256 using the credential's device key and return
 *   the raw 64-byte `r || s` (what WebCrypto's ECDSA produces), not DER. Not
 *   every proof system calls this - Vega has no device binding - so the page
 *   must not assume it will be asked.
 * - `zkp.step` (notify) `{ proofId, step }`.
 * - `zkp.complete` (notify)
 *   `{ proofId, success: true, deviceResponse, system, queryId, disclosedClaims }`
 *   or `{ proofId, success: false, error: { code, message } }`.
 *   `deviceResponse` is base64url without padding, ready to go into
 *   `vp_token[queryId]` as the single element of its array.
 *
 * Error codes: `bad_request`, `no_zk_match`, `no_proof_system`,
 * `sign_failed`, `unsupported_algorithm`, `cancelled`, `failed`.
 *
 * ### Memory
 *
 * A resident prover is 100+ MB of native memory. It is dropped when the UI is
 * hidden or under memory pressure (as the SDK's own wallet does) and reloaded
 * from the on-device circuit cache on the next proof. Call [dispose] when the
 * hosting Activity is destroyed.
 *
 * @param circuitSources the go-zk-circuits catalog URL(s) - the value the SDK
 *   takes as `WalletConfig.zkCircuitUrls`.
 */
class ZkpBridge(
    context: Context,
    private val calls: JsCallHost,
    private val scope: CoroutineScope,
    circuitSources: List<String>,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val appContext = context.applicationContext

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val zkPresentation: ZkMdocPresentation =
        ZkMdocPresentation.standard(
            circuitClient =
                ZkCircuitClient(
                    sources = circuitSources,
                    httpClient = httpClient,
                    // Circuits are immutable, tens of MB, and verified on
                    // download: fetched once per device, then served from disk
                    // with or without network. cacheDir, not files: nothing
                    // here is user data and re-downloading is the only cost.
                    cacheDir = File(appContext.cacheDir, "siros-zk-circuits"),
                ),
            // No BBS here: its holder state lives in the SDK's keystore
            // container, which this host does not own.
            extra = emptyList(),
        )

    private val zkSystemIds: List<String> get() = zkPresentation.registry.systemIds

    private val memoryCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) zkPresentation.releaseProvers()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = zkPresentation.releaseProvers()
        }.also { appContext.registerComponentCallbacks(it) }

    private var job: Job? = null

    // ── Page -> native ──────────────────────────────────────────────────────

    fun capabilities(): JsonObject =
        buildJsonObject {
            put("zkSystems", JsonArray(zkSystemIds.map { JsonPrimitive(it) }))
            put("busy", job?.isActive == true)
        }

    fun generate(paramsJson: String): JsonObject {
        cancel()
        val proofId = UUID.randomUUID().toString()
        job =
            scope.launch {
                // A Main.immediate scope (lifecycleScope) would otherwise run
                // this body - and possibly report zkp.complete for a malformed
                // request - before the page has received the proofId it needs
                // to correlate that report with.
                yield()
                val report =
                    try {
                        success(proofId, prove(proofId, paramsJson))
                    } catch (e: CancellationException) {
                        calls.notify(COMPLETE, failure(proofId, "cancelled", "The proof was cancelled"))
                        throw e
                    } catch (e: Exception) {
                        val known = proofFailureIn(e)
                        if (known != null) {
                            Timber.w("ZK proof $proofId failed: ${known.code} - ${known.message}")
                            failure(proofId, known.code, known.message ?: known.code)
                        } else {
                            Timber.e(e, "ZK proof $proofId failed")
                            failure(proofId, "failed", e.message ?: e::class.simpleName ?: "failed")
                        }
                    }
                calls.notify(COMPLETE, report)
            }
        return buildJsonObject { put("proofId", proofId) }
    }

    /** Cancels a running proof, which then reports `cancelled`. Safe when none is running. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    fun dispose() {
        cancel()
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        zkPresentation.releaseProvers()
    }

    // ── The proof ───────────────────────────────────────────────────────────

    private suspend fun prove(
        proofId: String,
        paramsJson: String,
    ): ProofResult {
        val params =
            runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull()
                ?: throw ProofFailure(BAD_REQUEST, "params must be a JSON object")
        val credentialJson = params["credential"] ?: throw ProofFailure(BAD_REQUEST, "credential is missing")
        val credential =
            try {
                json.decodeFromJsonElement(StoredCredential.serializer(), credentialJson)
            } catch (e: Exception) {
                // Name what is wrong (usually a missing required field), not just that something is.
                throw ProofFailure(BAD_REQUEST, "credential is not a StoredCredential: ${e.message}")
            }
        // The verifier's query, forwarded unchanged by the wallet: its zk_system_type
        // lists the proof systems the verifier accepts, and the SDK picks one here.
        val dcqlQuery =
            params["dcqlQuery"] as? JsonObject
                ?: throw ProofFailure(BAD_REQUEST, "dcqlQuery must be an object")
        val transcript =
            params["transcript"] as? JsonObject
                ?: throw ProofFailure(BAD_REQUEST, "transcript must be an object")
        val wantedQueryId = params.str("credentialQueryId")

        // The query is matched natively rather than having the page pass
        // systems and claims in: the requested proof systems, the pseudonym
        // context and the claim paths come out of the matcher in exactly the
        // types the prover takes, and a page re-deriving them is how the two
        // ends would come to disagree about what was asked for.
        // matchDcql with the registered proof systems (SDK 0.20+): without that
        // list the shared DCQL engine treats every mso_mdoc_zk query as
        // unanswerable and declines the whole request. Same call as
        // SirosWallet.handleDCAPIRequest.
        Timber.d("ZK proof $proofId: registered proof systems $zkSystemIds; query $dcqlQuery")
        // pairwise_pseudonym is derived (from the credential's pseudonym_seed and
        // the verifier's identity), not an element the credential holds, and the
        // shared DCQL engine declines any request that names it. Match without it,
        // then add it back for the prover below.
        val pseudonymQueryIds = queryIdsRequestingPseudonym(dcqlQuery)
        val matchQuery = if (pseudonymQueryIds.isEmpty()) dcqlQuery else withoutPseudonymClaims(dcqlQuery)
        val results = CredentialMatcher.matchDcql(matchQuery, listOf(credential), zkSystemIds).queryResults
        val match =
            if (wantedQueryId != null) {
                results.firstOrNull { it.queryId == wantedQueryId && it.candidates.any { c -> c.id == credential.id } }
            } else {
                // First match governs, the same rule the SDK applies everywhere.
                results.firstOrNull { r -> r.candidates.any { it.id == credential.id } }
            }
        if (match == null || match.format?.equals("mso_mdoc_zk", ignoreCase = true) != true) {
            throw ProofFailure(
                "no_zk_match",
                "The credential does not answer an mso_mdoc_zk query" +
                    (wantedQueryId?.let { " with id '$it'" } ?: "") +
                    (match?.let { " (it matched '${it.queryId}' as ${it.format}, which is not a ZK request)" } ?: ""),
            )
        }

        // Each requestedClaims entry is a full claim path [namespace, element];
        // the prover takes element identifiers and rejects a namespace passed
        // as one.
        val pseudonymRequested = match.queryId in pseudonymQueryIds
        val claims =
            (match.requestedClaims.mapNotNull { it.lastOrNull() } +
                listOfNotNull(LongfellowZkProofSystem.PSEUDONYM_CLAIM.takeIf { pseudonymRequested })).distinct()

        // The proof systems as the verifier wrote them. With a pseudonym the matcher
        // saw a reduced num_attributes, so its specs cannot be used: the prover
        // selects a circuit by the verifier's original count (pseudonym included).
        val requestedSystems =
            if (pseudonymRequested) {
                zkSystemSpecsOf(dcqlQuery, match.queryId).ifEmpty { match.zkSystemTypes.orEmpty() }
            } else {
                match.zkSystemTypes.orEmpty()
            }

        // Only bind a pseudonym when it was asked for: a non-null identity makes
        // the prover add and disclose pairwise_pseudonym unconditionally.
        val verifierIdentity =
            if (LongfellowZkProofSystem.PSEUDONYM_CLAIM in claims) {
                val verifier = params["verifier"] as? JsonObject
                val clientId =
                    verifier?.str("clientId")
                        ?: throw ProofFailure(BAD_REQUEST, "pairwise_pseudonym is requested but verifier.clientId is missing")
                VerifierIdentity(
                    clientId = clientId,
                    ppidContext = match.ppidContext,
                    sessionId = verifier.str("sessionId"),
                )
            } else {
                null
            }

        val sessionTranscript = sessionTranscriptOf(transcript)
        val credentialBytes =
            runCatching { CredentialUtils.decodeMdocRawBytes(credential) }.getOrNull()
                ?: throw ProofFailure(BAD_REQUEST, "credential.raw is not a decodable mdoc")

        calls.notify(STEP, step(proofId, "computing_proof"))
        val presented =
            try {
                zkPresentation.present(
                    ZkMdocPresentation.Request(
                        credentialBytes = credentialBytes,
                        requestedSystems = requestedSystems,
                        sessionTranscript = sessionTranscript,
                        requestedClaims = claims,
                        verifierIdentity = verifierIdentity,
                    ),
                    signer = { algorithm, data -> sign(proofId, credential.id, algorithm, data) },
                )
            } catch (e: ZkMdocPresentation.NoMatchingProofSystem) {
                throw ProofFailure("no_proof_system", e.message ?: "No registered ZK proof system satisfies the request")
            }

        Timber.i("ZK proof $proofId generated with ${presented.system.systemId} (${presented.deviceResponse.size} bytes)")
        return ProofResult(
            deviceResponse = presented.deviceResponse,
            systemId = presented.system.systemId,
            queryId = match.queryId,
            disclosedClaims = claims,
        )
    }

    // ── pairwise_pseudonym handling ─────────────────────────────────────────

    private fun credentialQueries(query: JsonObject): List<JsonObject> =
        (query["credentials"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun isPseudonymClaim(claim: JsonElement): Boolean =
        ((claim as? JsonObject)?.get("path") as? JsonArray)
            ?.lastOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull } == LongfellowZkProofSystem.PSEUDONYM_CLAIM

    private fun queryIdsRequestingPseudonym(query: JsonObject): Set<String> =
        credentialQueries(query)
            .filter { q -> (q["claims"] as? JsonArray)?.any(::isPseudonymClaim) == true }
            .mapNotNull { it.str("id") }
            .toSet()

    /** The query with pseudonym claims removed and each `num_attributes` reduced to match. */
    private fun withoutPseudonymClaims(query: JsonObject): JsonObject {
        val credentials =
            credentialQueries(query).map { q ->
                val claims = (q["claims"] as? JsonArray) ?: return@map q
                val removed = claims.count(::isPseudonymClaim)
                if (removed == 0) return@map q
                val meta = q["meta"] as? JsonObject
                val specs = (meta?.get("zk_system_type") as? JsonArray)?.map { spec ->
                    val o = spec as? JsonObject ?: return@map spec
                    val n = (o["num_attributes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@map o
                    JsonObject(o + ("num_attributes" to JsonPrimitive(n - removed)))
                }
                JsonObject(
                    q +
                        ("claims" to JsonArray(claims.filterNot(::isPseudonymClaim))) +
                        listOfNotNull(
                            meta?.let { m -> "meta" to JsonObject(m + listOfNotNull(specs?.let { "zk_system_type" to JsonArray(it) })) },
                        ),
                )
            }
        return JsonObject(query + ("credentials" to JsonArray(credentials)))
    }

    /** The zk_system_type entries of one credential query, as the SDK's ZkSystemSpec (extra keys become params). */
    private fun zkSystemSpecsOf(
        query: JsonObject,
        queryId: String,
    ): List<ZkSystemSpec> {
        val entries =
            credentialQueries(query)
                .firstOrNull { it.str("id") == queryId }
                ?.let { (it["meta"] as? JsonObject)?.get("zk_system_type") as? JsonArray }
                .orEmpty()
        return entries.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val system = o.str("system") ?: return@mapNotNull null
            ZkSystemSpec(
                id = o.str("id") ?: system,
                system = system,
                params =
                    o
                        .filterKeys { it != "id" && it != "system" }
                        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                        .toMap(),
            )
        }
    }

    private fun sessionTranscriptOf(t: JsonObject) =
        when (t.str("kind")) {
            "openid4vp" ->
                MdocDeviceResponseBuilder.buildOpenID4VPSessionTranscript(
                    clientId = t.required("clientId"),
                    nonce = t.required("nonce"),
                    responseUri = t.str("responseUri") ?: "",
                    verifierJwkThumbprint = t.str("verifierJwkThumbprint"),
                )
            "dc_api" ->
                MdocDeviceResponseBuilder.buildDCAPISessionTranscript(
                    origin = t.required("origin"),
                    nonce = t.required("nonce"),
                    encryptionPublicJwkThumbprint = t.str("encryptionJwkThumbprint"),
                )
            "raw" -> decodeB64(t.required("sessionTranscript"), "transcript.sessionTranscript")
            else -> throw ProofFailure(BAD_REQUEST, "transcript.kind must be openid4vp, dc_api or raw")
        }

    /**
     * The page signs, because the device key never leaves it - the same
     * reason `proximity.sign` exists.
     */
    private suspend fun sign(
        proofId: String,
        credentialId: Long,
        algorithm: Long,
        data: ByteArray,
    ): ByteArray {
        if (algorithm != COSE_ALG_ES256) {
            throw ProofFailure("unsupported_algorithm", "The proof system asked for COSE alg $algorithm; only ES256 is supported")
        }
        calls.notify(STEP, step(proofId, "awaiting_signature"))
        val reply =
            try {
                calls.call(
                    SIGN,
                    buildJsonObject {
                        put("proofId", proofId)
                        put("credentialId", credentialId)
                        put("algorithm", "ES256")
                        put("data", Base64.getEncoder().encodeToString(data))
                    },
                )
            } catch (e: JsCallHost.JsCallException) {
                throw ProofFailure("sign_failed", "The page did not sign: ${e.code}")
            }
        val encoded =
            (reply as? JsonObject)?.str("signature")
                ?: throw ProofFailure("sign_failed", "$SIGN returned no signature")
        val signature = decodeB64(encoded, "signature")
        if (signature.size != ES256_RAW_SIGNATURE_BYTES) {
            throw ProofFailure(
                "sign_failed",
                "$SIGN must return a raw 64-byte r||s ES256 signature, got ${signature.size} bytes (DER?)",
            )
        }
        calls.notify(STEP, step(proofId, "computing_proof"))
        return signature
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private class ProofFailure(
        val code: String,
        message: String,
    ) : Exception(message)

    private class ProofResult(
        val deviceResponse: ByteArray,
        val systemId: String,
        val queryId: String,
        val disclosedClaims: List<String>,
    )

    /** The prover may wrap what the signer threw; keep the code the signer chose. */
    private fun proofFailureIn(e: Throwable): ProofFailure? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is ProofFailure) return current
            current = current.cause
        }
        return null
    }

    private fun success(
        proofId: String,
        r: ProofResult,
    ): JsonObject =
        buildJsonObject {
            put("proofId", proofId)
            put("success", true)
            put("deviceResponse", Base64.getUrlEncoder().withoutPadding().encodeToString(r.deviceResponse))
            put("system", r.systemId)
            put("queryId", r.queryId)
            put("disclosedClaims", buildJsonArray { r.disclosedClaims.forEach { add(JsonPrimitive(it)) } })
        }

    private fun failure(
        proofId: String,
        code: String,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("proofId", proofId)
            put("success", false)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

    private fun step(
        proofId: String,
        step: String,
    ): JsonObject =
        buildJsonObject {
            put("proofId", proofId)
            put("step", step)
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.required(key: String): String =
        str(key)?.takeIf { it.isNotBlank() } ?: throw ProofFailure(BAD_REQUEST, "transcript.$key is required")

    /** Accepts standard or URL-safe base64, padded or not. */
    private fun decodeB64(
        value: String,
        field: String,
    ): ByteArray =
        runCatching {
            Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').trimEnd('='))
        }.getOrNull() ?: throw ProofFailure(BAD_REQUEST, "$field is not valid base64")

    companion object {
        /** Handler names the page registers. Kept together so the contract is readable in one place. */
        const val SIGN = "zkp.sign"
        const val STEP = "zkp.step"
        const val COMPLETE = "zkp.complete"

        private const val BAD_REQUEST = "bad_request"
        private const val ES256_RAW_SIGNATURE_BYTES = 64
    }
}