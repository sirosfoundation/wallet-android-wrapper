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
import org.siros.sdk.credentials.CircuitZkCircuitFilter
import org.siros.sdk.credentials.CircuitZkCircuitSource
import org.siros.sdk.credentials.CircuitZkFunction
import org.siros.sdk.credentials.CircuitZkPlan
import org.siros.sdk.credentials.CircuitZkRequestValidator
import org.siros.sdk.credentials.CircuitZkVerdict
import org.siros.sdk.credentials.CredentialUtils
import org.siros.sdk.credentials.NymPseudonym
import org.siros.sdk.credentials.NymSeedKind
import org.siros.sdk.credentials.StoredCredential
import org.siros.sdk.credentials.ZkWitnessSigner
import org.siros.sdk.keystore.CircuitZkError
import org.siros.sdk.keystore.CircuitZkFunctionWitness
import org.siros.sdk.keystore.CircuitZkPresenter
import org.siros.sdk.keystore.CircuitZkProver
import org.siros.sdk.keystore.CircuitZkWitnessKind
import org.siros.sdk.keystore.CircuitZkWitnessProvider
import org.siros.sdk.keystore.MdocDeviceResponseBuilder
import org.siros.sdk.keystore.ZkProverResidency
import org.siros.wwwallet.bridging.JsCallHost
import timber.log.Timber
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class ZkArtifact(
    val url: String,
    val hash: String,
    val compression: String,
)

class ZkCircuit(
    val circuit: CircuitZkCircuit,
    val configuration: String,
    val proofSystemVersion: String,
    val artifact: ZkArtifact?,
)

class ZkConfig(
    val prover: CircuitZkProver,
    val catalogue: List<ZkCircuit>,
    val validator: CircuitZkRequestValidator,
    val residency: ZkProverResidency?,
) {
    val circuits: CircuitZkCircuitSource = CircuitZkCircuitSource { catalogue.map { it.circuit } }

    fun systemIds(): List<String> = catalogue.map { it.circuit.system }.distinct()
}

class ZkpBridge(
    context: Context,
    private val calls: JsCallHost,
    private val scope: CoroutineScope,
    private val config: ZkConfig,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    @Volatile private var lastPresenter: CircuitZkPresenter? = null

    private fun releaseProvers() {
        lastPresenter?.releaseProvers()
    }

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
                yield()
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
                                // The SDK's refusal carries no description by design; the presenter logs its step.
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
                raw =
                    JsonObject(
                        mapOf(
                            "meta" to
                                buildJsonObject {
                                    put("doctype_value", doctype)
                                    put("source_format", "mso_mdoc")
                                },
                        ),
                    ),
            )
        if (config.validator.validate(plan) !is CircuitZkVerdict.Ok) return JsonNull

        val configs = pseudonymConfigsOf(functions)

        val chosen =
            config.catalogue.firstOrNull { e ->
                doctype in e.circuit.docTypes &&
                        (ids == null || e.circuit.circuitId in ids || e.circuit.aliases.any { it in ids }) &&
                        (systems == null || e.circuit.system in systems || e.circuit.proofSystem in systems) &&
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
        val plan =
            planOf(
                params["plan"] as? JsonObject ?: throw Failure(BAD_REQUEST, "plan must be an object"),
                circuitFilterOf(params),
            )
        val sessionTranscript =
            sessionTranscriptOf(params["transcript"] as? JsonObject ?: throw Failure(BAD_REQUEST, "transcript must be an object"))

        val docType =
            ((params["plan"] as JsonObject)["meta"] as? JsonObject)?.str("doctype_value")
                ?: throw Failure(BAD_REQUEST, "plan.meta.doctype_value is required")
        val userValue = params.str("userValue")?.let { decodeB64(it) }

        val circuits =
            params.str("circuitId")?.let { wanted ->
                val e =
                    config.catalogue.firstOrNull { it.circuit.circuitId == wanted }
                        ?: throw Failure(BAD_REQUEST, "unknown circuitId $wanted")
                if (docType !in e.circuit.docTypes) throw Failure(BAD_REQUEST, "circuit $wanted is not for $docType")
                CircuitZkCircuitSource { listOf(e.circuit) }
            } ?: config.circuits

        val needsPrivateSeed =
            pseudonymConfigsOf(plan.functions).any { NymPseudonym.describe(it)?.seedKind == NymSeedKind.PRIVATE }
        val secretSeed =
            params.str("secretSeed")?.let { encoded ->
                decodeB64(encoded).also {
                    if (it.size != SEED_BYTES) {
                        it.fill(0)
                        throw Failure(BAD_REQUEST, "secretSeed must be $SEED_BYTES bytes")
                    }
                }
            }
        val privateSeed = if (needsPrivateSeed) seedFromPage(proofId, credential.id, docType) else null
        try {
            val provider = PageSeedWitnessProvider(docType, privateSeed, userValue)
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
                        secretSeed = secretSeed,
                    ),
                    ZkWitnessSigner { alg, data -> sign(proofId, credential.id, alg, data) },
                )
            val token = result.presentation.toBase64Url()
            Timber.i("circuit_zk $proofId produced a ${result.systemId} presentation (${token.length} chars)")
            return Result(token)
        } finally {
            privateSeed?.fill(0)
            secretSeed?.fill(0)
        }
    }

    /**
     * The request's offer (rulebook v0.4): the `system_id_values` and `proof_system_values` of its client_metadata. The SDK
     * answers only with a system the request offered, so a plan without one is refused.
     */
    private fun circuitFilterOf(params: JsonObject): CircuitZkCircuitFilter? {
        val systemIds = strings(params["systemIdValues"])
        val proofSystems = strings(params["proofSystemValues"])
        if (systemIds == null && proofSystems == null) {
            Timber.w("circuit_zk: the request states no system_id_values; the SDK will refuse to answer it")
            return null
        }
        return CircuitZkCircuitFilter(proofSystems = proofSystems, circuitIds = systemIds)
    }

    private fun pseudonymConfigsOf(functions: List<CircuitZkFunction>): Set<String> =
        functions
            .filter { it.id == PSEUDONYM_FUNCTION }
            .mapNotNull { (it.params["config"] as? JsonPrimitive)?.contentOrNull }
            .toSet()

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
        if (seed.size != SEED_BYTES) {
            seed.fill(0)
            throw Failure("seed_failed", "$SEED must return a $SEED_BYTES-byte seed, got ${seed.size}")
        }
        return seed
    }

    private fun planFunctionsOf(req: JsonArray): List<CircuitZkFunction> =
        req.map { f ->
            val o = f as? JsonObject ?: throw Failure(BAD_REQUEST, "functions must be objects")
            CircuitZkFunction(
                id = o.str("fn_id") ?: throw Failure(BAD_REQUEST, "fn_id is required"),
                handle = o.str("id") ?: throw Failure(BAD_REQUEST, "function id is required"),
                params = (o["params"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }

    private fun planOf(
        p: JsonObject,
        circuitFilter: CircuitZkCircuitFilter?,
    ): CircuitZkPlan {
        val functions =
            (p["functions"] as? JsonArray)?.map { f ->
                val o = f as? JsonObject ?: throw Failure(BAD_REQUEST, "plan.functions entries must be objects")
                CircuitZkFunction(
                    id = o.str("id") ?: throw Failure(BAD_REQUEST, "function.id is required"),
                    handle = o.str("handle") ?: throw Failure(BAD_REQUEST, "function.handle is required"),
                    params = (o["params"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
            } ?: throw Failure(BAD_REQUEST, "plan.functions must be an array")
        // Rulebook v0.4 requires meta.source_format (rule source_format_unsupported). This bridge proves mdoc only, so a
        // page that leaves it out is answering an mso_mdoc query.
        val meta =
            (p["meta"] as? JsonObject)?.let { m ->
                if (m.containsKey("source_format")) m else JsonObject(m + ("source_format" to JsonPrimitive("mso_mdoc")))
            }
        return CircuitZkPlan(
            functions = functions,
            circuitFilter = circuitFilter,
            raw = JsonObject(if (meta != null) mapOf("meta" to meta) else emptyMap()),
        )
    }

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

    private class PageSeedWitnessProvider(
        private val docType: String,
        private val seed: ByteArray?,
        private val userValue: ByteArray?,
    ) : CircuitZkWitnessProvider {
        override fun witness(
            function: CircuitZkFunction,
            needs: Set<CircuitZkWitnessKind>,
            credentialType: String,
            issuer: String?,
        ): CircuitZkFunctionWitness? {
            if (CircuitZkWitnessKind.NOT_HELD_BY_WALLET in needs) return null
            if (credentialType != docType) return null
            if (CircuitZkWitnessKind.USER_VALUE in needs && userValue == null) return null
            if (CircuitZkWitnessKind.PRIVATE_SEED in needs && seed == null) return null
            return CircuitZkFunctionWitness(
                privateSeed = if (CircuitZkWitnessKind.PRIVATE_SEED in needs) seed?.copyOf() else null,
                userValue = if (CircuitZkWitnessKind.USER_VALUE in needs) userValue?.copyOf() else null,
            )
        }

        override fun privateSeedMatches(
            credentialType: String,
            issuer: String?,
            psc: ByteArray,
        ): Boolean {
            val s = seed ?: return false
            return credentialType == docType &&
                    MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(s), psc)
        }
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
        const val SIGN = "zkp.sign"
        const val SEED = "zkp.seed"
        const val STEP = "zkp.step"
        const val COMPLETE = "zkp.complete"

        private const val BAD_REQUEST = "bad_request"
        /** The SDK's pseudonym function id (v0.21.0, rulebook v0.4: PseudonymFunctionHandler.functionId). */
        private const val PSEUDONYM_FUNCTION = "pseudonym"
        private const val SEED_BYTES = 16
    }
}