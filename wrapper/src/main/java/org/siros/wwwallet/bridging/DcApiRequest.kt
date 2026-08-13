package org.siros.wwwallet.bridging

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DcApiRequests(
    val requests: List<DcApiRequest>,
)

@Serializable
data class DcApiRequest(
    val data: DcApiRequestData,
    val protocol: String,
)

@Serializable
data class DcApiRequestData(
    @SerialName("client_id")
    val clientId: String? = null,
    @SerialName("response_type")
    val responseType: String? = null,
    @SerialName("response_mode")
    val responseMode: String? = null,
    val nonce: String? = null,
    @SerialName("client_metadata")
    val clientMetadata: JsonElement? = null,
    val request: String? = null,
    @SerialName("transaction_data")
    val transactionData: JsonElement? = null,
    @SerialName("dcql_query")
    val dcqlQuery: JsonElement? = null,
    @SerialName("verifier_info")
    val verifierInfo: JsonElement? = null,
) {
    fun addAsQuery(uri: Uri.Builder) {
        if (request != null) {
            uri
                .appendQueryParameter("clientId", clientId)
                .appendQueryParameter("request", request)
        } else {
            uri
                .appendQueryParameter("response_type", responseType)
                .appendQueryParameter("response_mode", responseMode)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("client_metadata", clientMetadata?.toString() ?: "{}")
                .appendQueryParameter("transaction_data", transactionData?.toString())
                .appendQueryParameter("dcql_query", dcqlQuery?.toString() ?: "{}")
                .appendQueryParameter("verifier_info", verifierInfo?.toString())
        }
    }
}
