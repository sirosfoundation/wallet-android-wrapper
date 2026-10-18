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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.siros.sdk.credentials.COSE_ALG_ES256
import org.siros.sdk.credentials.CircuitZkCircuit
import org.siros.sdk.credentials.CircuitZkCircuitSource
import org.siros.sdk.credentials.CircuitZkFunction
import org.siros.sdk.credentials.CircuitZkPlan
import org.siros.sdk.credentials.CircuitZkRequestValidator
import org.siros.sdk.credentials.CircuitZkVerdict
import org.siros.sdk.credentials.CredentialUtils
import org.siros.sdk.credentials.StoredCredential
import org.siros.sdk.credentials.ZkWitnessSigner
import org.siros.sdk.keystore.CircuitZkError
import org.siros.sdk.keystore.CircuitZkPresenter
import org.siros.sdk.keystore.CircuitZkProver
import org.siros.sdk.keystore.CircuitZkFunctionWitness
import org.siros.sdk.keystore.CircuitZkWitnessKind
import org.siros.sdk.keystore.CircuitZkWitnessProvider
import org.siros.sdk.keystore.ZkProverResidency
import org.siros.sdk.keystore.MdocDeviceResponseBuilder
import org.siros.wwwallet.bridging.JsCallHost
import timber.log.Timber
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Everything [ZkpBridge] needs to prove. Absent (null) means this build cannot
 * present with zero knowledge, and the page is told so through `capabilities()`.
 */
/** A circuit artifact as the catalogue publishes it. [url] is absolute. */
class ZkArtifact(
    val url: String,
    /** `sha256:<hex>` of the compressed artifact. */
    val hash: String,
    /** `zstd` or `none`. */
    val compression: String,
)

/**
 * One circuit the wallet can prove with, as the catalogue describes it.
 * [circuit] is what the SDK presenter matches on; the rest is what the page's registry needs to select it.
 */
class ZkCircuit(
    val circuit: CircuitZkCircuit,
    /** The nym_auth configuration the circuit was built for, e.g. `known-offender-matching-ssc`. */
    val configuration: String,
    /** The catalogue's `systemVersion`. */
    val proofSystemVersion: String,
    val artifact: ZkArtifact?,
)

class ZkConfig(
    /** The `circuit_zk` prover. The SDK ships none that produces real proofs. */
    val prover: CircuitZkProver,
    /** The circuits this build can prove with; also what [ZkpBridge.selectCircuit] chooses from. */
    val catalogue: List<ZkCircuit>,
    val validator: CircuitZkRequestValidator,
    val residency: ZkProverResidency?,
) {
    /** What the SDK presenter matches circuits against. */
    val circuits: CircuitZkCircuitSource = CircuitZkCircuitSource { catalogue.map { it.circuit } }

    /** System ids the page may offer (what the prover and circuits support). */
    fun systemIds(): List<String> = catalogue.map { it.circuit.system }.distinct()
}

/**
 * Zero-knowledge presentation (`circuit_zk`: nym_auth pseudonym + age predicate), hosted natively.
 * Replaces the former `mso_mdoc_zk` / DCQL implementation; the page-facing contract keeps its
 * names (`capabilities`, `generate`, `cancel`, `zkp.sign`, `zkp.step`, `zkp.complete`).
 *
 * The page owns the credential and the device key. Native owns the holder private seed
 * (in the page's keystore; handed over per proof through `zkp.seed`) and runs the presenter.
 *
 * ### Page -> native
 * - `capabilities()` -> `{ zkSystems: string[], busy: boolean, fakeProver: boolean }`.
 * - `generate(params)` -> `{ proofId }`, then `zkp.step` / `zkp.complete`. A new proof cancels a running one.
 * - `cancel()`.
 *
 * `params`:
 * ```
 * {
 *   credential: StoredCredential,          // its device key is what the page signs with
 *   plan: {
 *     functions: [ { id, handle, params } ],   // e.g. pseudonym-v1-sha256, age_check
 *     meta: { doctype_value: string }          // required; also the docType asked of the page for the seed
 *   },
 *   userValue?: string,                    // base64, only for configs whose function takes one
 *   verifierId: string,                    // what the pseudonym is derived for
 *   transcript: { kind: "openid4vp"|"dc_api"|"raw", ... }
 * }
 * ```
 *
 * ### Native -> page
 * - `zkp.sign` `{ proofId, credentialId, algorithm: "ES256", data(base64) }` -> `{ signature }`
 *   (base64 of raw 64-byte r||s, not DER, message not digest). Unchanged.
 * - `zkp.seed` `{ proofId, credentialId, docType }` -> `{ seed }` (base64, 16 bytes): the holder private
 *   seed for `docType`, derived by the page from its unlocked keystore. Held by native for this proof only.
 * - `zkp.step` `{ proofId, step }`.
 * - `zkp.complete` `{ proofId, success: true, token }` (`token` = CircuitZkPresentationCodec,
 *   base64url) or `{ proofId, success: false, error: { code, message } }`.
 *   Codes: bad_request, refused, sign_failed, unsupported_algorithm, cancelled, failed.
 *   `CircuitZkError` carries no description by design, so it is always `refused`.
 *
 * Notes:
 * - The presenter is built per proof around the seed the page supplies. Its circuits must match the catalogue (`longfellow-nymauth`, `vega-nymauth`;
 *   doctype `eu.europa.ec.eudi.nym_auth.1`).
 * - The plan's `config` must be the one the chosen circuit was built for
 *   (the catalogue lists `known-offender-matching-ssc`).
 */
class ZkpBridge(
    context: Context,
    private val calls: JsCallHost,
    private val scope: CoroutineScope,
    private val config: ZkConfig,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    // One presenter per proof: its witness provider carries that proof's seed. Kept only so memory
    // pressure can drop the resident prover.
    @Volatile private var lastPresenter: CircuitZkPresenter? = null

    private fun releaseProvers() {
        lastPresenter?.releaseProvers()
    }

    // A resident prover is large; drop it when the UI is hidden or memory is low.
    private val memoryCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseProvers()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = releaseProvers()
        }.also { appContext.registerComponentCallbacks(it) }

    fun capabilities(): JsonObject =
        buildJsonObject {
            put("zkSystems", JsonArray(config.systemIds().map { JsonPrimitive(it) }))
            put("busy", job?.isActive == true)
            // A fake prover produces no real proof; the page must not offer it to a real verifier.
            put("fakeProver", config.prover.isFake)
        }

    fun dispose() {
        cancel()
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        releaseProvers()
    }

    fun generate(paramsJson: String): JsonObject {
        cancel()
        val proofId = UUID.randomUUID().toString()
        job =
            scope.launch {
                yield() // let the page receive proofId before any report for it
                val report =
                    try {
                        success(proofId, prove(proofId, paramsJson))
                    } catch (e: CancellationException) {
                        calls.notify(COMPLETE, failure(proofId, "cancelled", "The proof was cancelled"))
                        throw e
                    } catch (e: Exception) {
                        val known = failureIn(e)
                        when {
                            known != null -> failure(proofId, known.code, known.message ?: known.code)
                            e is CircuitZkError || e.cause is CircuitZkError -> {
                                // No description by design; do not try to extract one.
                                Timber.w("circuit_zk $proofId refused")
                                failure(proofId, "refused", "The wallet refused this request")
                            }
                            else -> {
                                Timber.e(e, "circuit_zk $proofId failed")
                                failure(proofId, "failed", e.message ?: e::class.simpleName ?: "failed")
                            }
                        }
                    }
                calls.notify(COMPLETE, report)
            }
        return buildJsonObject { put("proofId", proofId) }
    }

    /**
     * Chooses the circuit this build will prove with, for the page's registry host.
     *
     * `criteria`: `{ doctype, req: [{ fn_id, id, params }], acceptedCircuitIds?, acceptedSystems? }`.
     * Resolves with `{ circuitId, proofSystem, proofSystemVersion, artifact? }`, or JSON null when no
     * circuit fits. A circuit fits when it is for the doctype, is in the accepted ids and systems (when
     * given), was built for the `config` of every pseudonym function in `req`, and the rulebook
     * validator accepts the requested functions.
     */
    fun selectCircuit(criteriaJson: String): JsonElement {
        val c =
            runCatching { json.parseToJsonElement(criteriaJson).jsonObject }.getOrNull()
                ?: throw Failure(BAD_REQUEST, "criteria must be a JSON object")
        val doctype = c.str("doctype") ?: throw Failure(BAD_REQUEST, "doctype is required")
        val req = c["req"] as? JsonArray ?: throw Failure(BAD_REQUEST, "req must be an array")
        val ids = strings(c["acceptedCircuitIds"])
        val systems = strings(c["acceptedSystems"])

        val functions = planFunctionsOf(req)
        val plan =
            CircuitZkPlan(
                functions = functions,
                raw = JsonObject(mapOf("meta" to buildJsonObject { put("doctype_value", doctype) })),
            )
        if (config.validator.validate(plan) !is CircuitZkVerdict.Ok) return JsonNull

        val configs =
            functions
                .filter { it.id == PSEUDONYM_FUNCTION }
                .mapNotNull { (it.params["config"] as? JsonPrimitive)?.contentOrNull }
                .toSet()

        val chosen =
            config.catalogue.firstOrNull { e ->
                doctype in e.circuit.docTypes &&
                        (ids == null || e.circuit.circuitId in ids) &&
                        (systems == null || e.circuit.system in systems) &&
                        configs.all { it == e.configuration }
            } ?: return JsonNull

        return buildJsonObject {
            put("circuitId", chosen.circuit.circuitId)
            put("proofSystem", chosen.circuit.system)
            put("proofSystemVersion", chosen.proofSystemVersion)
            chosen.artifact?.let { a ->
                putJsonObject("artifact") {
                    put("url", a.url)
                    put("hash", a.hash)
                    put("compression", a.compression)
                }
            }
        }
    }

    private fun strings(e: JsonElement?): List<String>? =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun prove(
        proofId: String,
        paramsJson: String,
    ): Result {
        val params =
            runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull()
                ?: throw Failure(BAD_REQUEST, "params must be a JSON object")
        val credential =
            try {
                json.decodeFromJsonElement(
                    StoredCredential.serializer(),
                    params["credential"] ?: throw Failure(BAD_REQUEST, "credential is missing"),
                )
            } catch (e: Failure) {
                throw e
            } catch (e: Exception) {
                throw Failure(BAD_REQUEST, "credential is not a StoredCredential: ${e.message}")
            }
        val credentialBytes =
            runCatching { CredentialUtils.decodeMdocRawBytes(credential) }.getOrNull()
                ?: throw Failure(BAD_REQUEST, "credential.raw is not a decodable mdoc")

        val verifierId = params.str("verifierId") ?: throw Failure(BAD_REQUEST, "verifierId is required")
        val plan = planOf(params["plan"] as? JsonObject ?: throw Failure(BAD_REQUEST, "plan must be an object"))
        val sessionTranscript =
            sessionTranscriptOf(params["transcript"] as? JsonObject ?: throw Failure(BAD_REQUEST, "transcript must be an object"))

        val docType =
            ((params["plan"] as JsonObject)["meta"] as? JsonObject)?.str("doctype_value")
                ?: throw Failure(BAD_REQUEST, "plan.meta.doctype_value is required")
        val userValue = params.str("userValue")?.let { decodeB64(it) }

        // The page's registry already chose a circuit; prove with that one, not whichever the presenter prefers.
        val circuits =
            params.str("circuitId")?.let { wanted ->
                val e =
                    config.catalogue.firstOrNull { it.circuit.circuitId == wanted }
                        ?: throw Failure(BAD_REQUEST, "unknown circuitId $wanted")
                if (docType !in e.circuit.docTypes) throw Failure(BAD_REQUEST, "circuit $wanted is not for $docType")
                CircuitZkCircuitSource { listOf(e.circuit) }
            } ?: config.circuits

        // The page's keystore owns the secret; fetch it before proving because the SDK's witness
        // provider is not suspending. It lives in this ByteArray for this proof only.
        val seed = seedFromPage(proofId, credential.id, docType)
        try {
            val provider = PageSeedWitnessProvider(docType, seed, userValue)
            val presenter =
                CircuitZkPresenter(
                    prover = config.prover,
                    circuits = circuits,
                    validator = config.validator,
                    witnessProvider = provider,
                    residency = config.residency,
                )
            lastPresenter = presenter

            calls.notify(STEP, step(proofId, "computing_proof"))
            val result =
                presenter.present(
                    CircuitZkPresenter.Request(
                        credentialBytes = credentialBytes,
                        plan = plan,
                        verifierId = verifierId,
                        sessionTranscript = sessionTranscript,
                    ),
                    ZkWitnessSigner { alg, data -> sign(proofId, credential.id, alg, data) },
                )
            Timber.i("circuit_zk $proofId produced a presentation (${result.base64Url.length} chars)")
            return Result(result.base64Url)
        } finally {
            seed.fill(0)
        }
    }

    /** Asks the page for the holder private seed of [docType]; it derives it from its unlocked keystore. */
    private suspend fun seedFromPage(
        proofId: String,
        credentialId: Long,
        docType: String,
    ): ByteArray {
        calls.notify(STEP, step(proofId, "awaiting_seed"))
        val reply =
            try {
                calls.call(
                    SEED,
                    buildJsonObject {
                        put("proofId", proofId)
                        put("credentialId", credentialId)
                        put("docType", docType)
                    },
                )
            } catch (e: JsCallHost.JsCallException) {
                throw Failure("seed_failed", "The page did not provide the seed: ${e.code}")
            }
        val seed = decodeB64((reply as? JsonObject)?.str("seed") ?: throw Failure("seed_failed", "$SEED returned no seed"))
        if (seed.size != PRIVATE_SEED_BYTES) {
            seed.fill(0)
            throw Failure("seed_failed", "$SEED must return a $PRIVATE_SEED_BYTES-byte seed, got ${seed.size}")
        }
        return seed
    }

    // A plan function is `{ id, handle, params }`; a registry criteria function is `{ fn_id, id, params }`.
    private fun planFunctionsOf(req: JsonArray): List<CircuitZkFunction> =
        req.map { f ->
            val o = f as? JsonObject ?: throw Failure(BAD_REQUEST, "functions must be objects")
            CircuitZkFunction(
                id = o.str("fn_id") ?: throw Failure(BAD_REQUEST, "fn_id is required"),
                handle = o.str("id") ?: throw Failure(BAD_REQUEST, "function id is required"),
                params = (o["params"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }

    private fun planOf(p: JsonObject): CircuitZkPlan {
        val functions =
            (p["functions"] as? JsonArray)?.map { f ->
                val o = f as? JsonObject ?: throw Failure(BAD_REQUEST, "plan.functions entries must be objects")
                CircuitZkFunction(
                    id = o.str("id") ?: throw Failure(BAD_REQUEST, "function.id is required"),
                    handle = o.str("handle") ?: throw Failure(BAD_REQUEST, "function.handle is required"),
                    params = (o["params"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
            } ?: throw Failure(BAD_REQUEST, "plan.functions must be an array")
        val meta = p["meta"] as? JsonObject
        return CircuitZkPlan(
            functions = functions,
            raw = JsonObject(if (meta != null) mapOf("meta" to meta) else emptyMap()),
        )
    }

    // Same transcript kinds as the previous bridge.
    private fun sessionTranscriptOf(t: JsonObject): ByteArray =
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
            "raw" -> decodeB64(t.required("sessionTranscript"))
            else -> throw Failure(BAD_REQUEST, "transcript.kind must be openid4vp, dc_api or raw")
        }

    private suspend fun sign(
        proofId: String,
        credentialId: Long,
        algorithm: Long,
        data: ByteArray,
    ): ByteArray {
        if (algorithm != COSE_ALG_ES256) throw Failure("unsupported_algorithm", "only ES256 is supported, got COSE alg $algorithm")
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
                throw Failure("sign_failed", "The page did not sign: ${e.code}")
            }
        val signature = decodeB64((reply as? JsonObject)?.str("signature") ?: throw Failure("sign_failed", "no signature returned"))
        if (signature.size != 64) throw Failure("sign_failed", "expected raw 64-byte r||s, got ${signature.size} bytes (DER?)")
        calls.notify(STEP, step(proofId, "computing_proof"))
        return signature
    }

    /**
     * Serves the private seed the page supplied for this proof. The commitment is `SHA256(private_seed)`
     * (as the SDK's HolderWitnessSource documents it), compared in constant time.
     */
    private class PageSeedWitnessProvider(
        private val docType: String,
        private val seed: ByteArray,
        private val userValue: ByteArray?,
    ) : CircuitZkWitnessProvider {
        override fun witness(
            function: CircuitZkFunction,
            needs: Set<CircuitZkWitnessKind>,
            credentialType: String,
        ): CircuitZkFunctionWitness? {
            if (CircuitZkWitnessKind.NOT_HELD_BY_WALLET in needs) return null
            if (credentialType != docType) return null
            if (CircuitZkWitnessKind.USER_VALUE in needs && userValue == null) return null
            return CircuitZkFunctionWitness(
                privateSeed = if (CircuitZkWitnessKind.PRIVATE_SEED in needs) seed.copyOf() else null,
                userValue = if (CircuitZkWitnessKind.USER_VALUE in needs) userValue?.copyOf() else null,
            )
        }

        override fun privateSeedMatches(
            credentialType: String,
            psc: ByteArray,
        ): Boolean =
            credentialType == docType &&
                    MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(seed), psc)
    }

    private class Failure(val code: String, message: String) : Exception(message)

    private class Result(val token: String)

    private fun failureIn(e: Throwable): Failure? {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c is Failure) return c
            c = c.cause
        }
        return null
    }

    private fun success(proofId: String, r: Result) =
        buildJsonObject {
            put("proofId", proofId)
            put("success", true)
            put("token", r.token)
        }

    private fun failure(proofId: String, code: String, message: String) =
        buildJsonObject {
            put("proofId", proofId)
            put("success", false)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

    private fun step(proofId: String, step: String) =
        buildJsonObject {
            put("proofId", proofId)
            put("step", step)
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.required(key: String): String =
        str(key)?.takeIf { it.isNotBlank() } ?: throw Failure(BAD_REQUEST, "transcript.$key is required")

    private fun decodeB64(value: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').trimEnd('=')) }.getOrNull()
            ?: throw Failure(BAD_REQUEST, "not valid base64")

    companion object {
        /** Handler names the page registers. Unchanged from the mso_mdoc_zk bridge. */
        const val SIGN = "zkp.sign"
        const val SEED = "zkp.seed"
        const val STEP = "zkp.step"
        const val COMPLETE = "zkp.complete"

        private const val BAD_REQUEST = "bad_request"
        private const val PSEUDONYM_FUNCTION = "pseudonym-v1-sha256"
        private const val PRIVATE_SEED_BYTES = 16
    }
}