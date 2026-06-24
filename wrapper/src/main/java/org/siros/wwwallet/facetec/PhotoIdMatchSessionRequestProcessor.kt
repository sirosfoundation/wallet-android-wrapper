package org.siros.wwwallet.facetec

import com.facetec.sdk.FaceTecSessionRequestProcessor
import org.json.JSONObject
import org.siros.wwwallet.BuildConfig
import org.siros.wwwallet.logging.YOLOLogger
import org.siros.wwwallet.tagForLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forwards FaceTec SDK Session Request/Response Blobs to facetec-api's `/v1/process-request`,
 * which proxies them to the FaceTec Server and applies local policy to decide whether a
 * successful Photo ID Match is accepted.
 *
 * Per FaceTec's integration contract, [onSessionRequest] performs only the network call and
 * the minimum bookkeeping needed to relay its result back into the SDK — no other app logic
 * or UI changes are allowed here. It is already invoked off the main thread by the SDK.
 *
 * Request/response bodies are opaque encrypted blobs to FaceTec Server and are never logged,
 * to keep this client's exposure to biometric data as small as possible.
 */
class PhotoIdMatchSessionRequestProcessor(
    private val onCredentialOfferReceived: (String) -> Unit,
) : FaceTecSessionRequestProcessor {
    override fun onSessionRequest(
        sessionRequestBlob: String,
        sessionRequestCallback: FaceTecSessionRequestProcessor.Callback,
    ) {
        try {
            val response = postProcessRequest(sessionRequestBlob)

            response.optString("credentialOfferURI").takeIf { it.isNotBlank() }?.let(onCredentialOfferReceived)

            sessionRequestCallback.processResponse(response.getString("responseBlob"))
        } catch (e: Exception) {
            YOLOLogger.e(tagForLog, "facetec-api process-request call failed.", e)
            sessionRequestCallback.abortOnCatastrophicError()
        }
    }

    private fun postProcessRequest(sessionRequestBlob: String): JSONObject {
        val connection = URL(BuildConfig.FACETEC_API_BASE_URL).openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.FACETEC_API_BEARER_TOKEN}")

        val payload = JSONObject().put("requestBlob", sessionRequestBlob)

        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = responseStream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            throw IOException("process-request failed with HTTP $responseCode.")
        }

        return JSONObject(body)
    }
}
