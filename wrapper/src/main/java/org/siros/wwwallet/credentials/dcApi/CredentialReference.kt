package org.siros.wwwallet.credentials.dcApi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class CredentialReference(
    val callbackUrl: String,
    val id: String,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(this).encodeToByteArray())

    companion object {
        fun decode(value: String): CredentialReference = Json.decodeFromString(Base64.getUrlDecoder().decode(value).decodeToString())
    }
}
