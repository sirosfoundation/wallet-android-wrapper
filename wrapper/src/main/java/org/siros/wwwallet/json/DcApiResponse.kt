package org.siros.wwwallet.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DcApiResponse(
    val protocol: String,
    val data: JsonElement,
)
