package org.siros.wwwallet.bridging

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.credentials.registry.digitalcredentials.mdoc.MdocEntry
import androidx.credentials.registry.digitalcredentials.mdoc.MdocField
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtClaim
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtEntry
import androidx.credentials.registry.provider.digitalcredentials.VerificationEntryDisplayProperties
import androidx.credentials.registry.provider.digitalcredentials.VerificationFieldDisplayProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
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

    @Transient
    var bitmap: Bitmap = createBitmap(1, 1),
) {

    val sdJwt: SdJwtEntry?
        get() {
            if (format != "sd-jwt") return null

            val verifiableCredentialType = verifiableCredentialType ?: return null
            val claims = claims?.map {
                SdJwtClaim(it.path.split("."), it.value.toNativeValue(), setOf(it.displayProperties))
            } ?: return null

            return SdJwtEntry(verifiableCredentialType, claims, setOf(displayProperties), id)
        }

    val mDoc: MdocEntry?
        get() {
            if (format != "mdoc") return null

            val docType = docType ?: return null
            val fields = fields?.map { MdocField(it.namespace, it.element, null, emptySet()) } ?: return null

            return MdocEntry(docType, fields, setOf(displayProperties), id)
        }

    val displayProperties: VerificationEntryDisplayProperties
        get() = VerificationEntryDisplayProperties(display.title, display.subtitle, bitmap)
}

@Serializable
data class DcApiDisplay(
    val title: String,
    val subtitle: String?
)

@Serializable
data class DcApiField(
    val namespace: String,
    val element: String,
)

@Serializable
data class DcApiClaim(
    val path: String,
    val value: JsonElement,
    val display: Map<String, String>
) {

    val displayProperties: VerificationFieldDisplayProperties
        get() {
            val name: String
            val locale = Locale.getDefault().displayName

            if (display.containsKey(locale)) {
                name = display[locale]!!
            }
            else if (display.containsKey("en-US")) {
                name = display["en-US"]!!
            }
            else {
                val key = display.keys.firstOrNull()

                if (key == null || display[key] == null) {
                    name = path
                }
                else {
                    name = display[key]!!
                }
            }

            val valueString = if (value is JsonPrimitive) value.contentOrNull else value.toString()
            return VerificationFieldDisplayProperties(name, valueString)
        }
}

fun JsonElement.toNativeValue(): Any? {
    return when (this) {
        is JsonNull -> null
        is JsonPrimitive -> {
            if (isString) content
            else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        }
        is JsonArray -> map { it.toNativeValue() }
        is JsonObject -> mapValues { it.value.toNativeValue() }
    }
}
