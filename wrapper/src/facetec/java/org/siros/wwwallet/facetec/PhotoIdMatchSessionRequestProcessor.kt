package org.siros.wwwallet.facetec

import com.facetec.sdk.FaceTecSessionRequestProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.siros.wwwallet.BuildConfig
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Forwards FaceTec SDK Session Request/Response Blobs to facetec-api's `/v1/process-request`,
 * which proxies them to the FaceTec Server and applies local policy to decide whether a
 * successful Photo ID Match is accepted.
 *
 * Per FaceTec's integration contract, [onSessionRequest] performs only the network call and
 * the minimum bookkeeping needed to relay its result back into the SDK — no other app logic
 * or UI changes are allowed here. It is already invoked off the main thread by the SDK.
 *
 * Every request also carries an [externalDatabaseRefID]: the key FaceTec Server files the
 * Enrollment Record under during the liveness step of a session and looks it up again during
 * the ID match step. See that property for why the app has to be the one to mint it.
 *
 * Request/response bodies are opaque encrypted blobs to FaceTec Server and are never logged,
 * to keep this client's exposure to biometric data as small as possible. The logged response is
 * passed through [redactLongValues], which replaces any string value long enough to plausibly be
 * an image or encrypted blob (`responseBlob`, `scanResultBlob`, face crops, …) with its length, so
 * short diagnostic/status fields remain visible without ever logging biometric data.
 */
class PhotoIdMatchSessionRequestProcessor(
    private val onCredentialOfferReceived: (String) -> Unit,
) : FaceTecSessionRequestProcessor {
    companion object {
        private const val REDACT_THRESHOLD = 200

        // Prefixes the per-session identifier below, so a record in FaceTec Server can be
        // traced back to the client that created it.
        private const val EXTERNAL_DB_REF_PREFIX = "wwwallet-android-"
    }

    /**
     * Identifies this scan's Enrollment Record inside FaceTec Server. It must stay stable
     * across the several `/process-request` calls one FaceTec session makes — the server
     * files the record under this key during the liveness step and retrieves it again during
     * the ID match step — and it must differ between sessions, since a record can only be
     * enrolled once per key.
     *
     * One value per processor instance is exactly that: [PhotoIdMatchActivity] constructs one
     * processor per session.
     *
     * The app has to be the one to generate this. facetec-api sees each `/process-request`
     * call in isolation — the calls of one session carry no correlation identifier, only the
     * tenant-wide bearer token — so it cannot tell which of them belong together and cannot
     * synthesize a stable key on our behalf. Sending none (issue #27) left every session
     * sharing one empty key, and the ID match step then failed with "A Record could not be
     * found for the Enrollment".
     */
    private val externalDatabaseRefID = EXTERNAL_DB_REF_PREFIX + UUID.randomUUID()

    override fun onSessionRequest(
        sessionRequestBlob: String,
        sessionRequestCallback: FaceTecSessionRequestProcessor.Callback,
    ) {
        Timber.i(
            "onSessionRequest() on thread '${Thread.currentThread().name}', blob length ${sessionRequestBlob.length}, " +
                "externalDatabaseRefID=$externalDatabaseRefID.",
        )

        try {
            val response = postProcessRequest(sessionRequestBlob)

            Timber.i("process-request response: ${redactLongValues(response)}")

            val credentialOfferURI = response.optString("credentialOfferURI").takeIf { it.isNotBlank() }
            credentialOfferURI?.let(onCredentialOfferReceived)

            sessionRequestCallback.processResponse(response.getString("responseBlob"))
        } catch (t: Throwable) {
            // Throwable rather than Exception: whatever goes wrong here, the SDK is waiting
            // on this callback and has to be told, or the session hangs.
            Timber.e(t, "facetec-api process-request call failed with ${t.javaClass.name}.")
            sessionRequestCallback.abortOnCatastrophicError()
        }
    }

    private fun redactLongValues(value: Any?): Any? =
        when (value) {
            is JSONObject ->
                JSONObject().apply {
                    value.keys().forEach { key -> put(key, redactLongValues(value.get(key))) }
                }
            is JSONArray ->
                JSONArray().apply {
                    for (i in 0 until value.length()) {
                        put(redactLongValues(value.get(i)))
                    }
                }
            is String -> if (value.length > REDACT_THRESHOLD) "<redacted, len=${value.length}>" else value
            else -> value
        }

    private fun postProcessRequest(sessionRequestBlob: String): JSONObject {
        val connection = URL(BuildConfig.FACETEC_API_BASE_URL).openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.FACETEC_API_BEARER_TOKEN}")

        val payload =
            JSONObject()
                .put("requestBlob", sessionRequestBlob)
                .put("externalDatabaseRefID", externalDatabaseRefID)

        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = responseStream.bufferedReader().use { it.readText() }

        Timber.i("process-request HTTP status: $responseCode")

        if (responseCode !in 200..299) {
            Timber.e("process-request error body: $body")
            throw IOException("process-request failed with HTTP $responseCode.")
        }

        return JSONObject(body)
    }
}
