package org.siros.wwwallet.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.Locale

@Serializable
data class DcApiCredential(
    val id: String,
    val format: String,
    val display: DcApiDisplay,
    val docType: String? = null,
    val fields: List<DcApiField>? = null,
    val verifiableCredentialType: String? = null,
    val claims: List<DcApiClaim>? = null,
) {
    companion object {
        const val FORMAT_MDOC = "mdoc"
        const val FORMAT_SD_JWT = "sd-jwt"
    }
}

@Serializable
data class DcApiDisplay(
    val title: String,
    val subtitle: String?,
)

@Serializable
data class DcApiField(
    val namespace: String,
    val identifier: String,
    val value: JsonElement?,
    val display: Map<String, String>?,
) {
    companion object {
        fun resolveDisplayName(
            display: Map<String, String>?,
            fallback: String,
        ): String {
            val locale = Locale.getDefault().toLanguageTag()
            return display?.get(locale)
                ?: display?.get("en-US")
                ?: display?.values?.firstOrNull()
                ?: fallback
        }
    }
}

@Serializable
data class DcApiClaim(
    val path: String,
    val value: JsonElement,
    val display: Map<String, String>,
)
