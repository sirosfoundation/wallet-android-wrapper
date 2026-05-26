package org.siros.wwwallet.bridging

import androidx.credentials.registry.digitalcredentials.mdoc.MdocEntry
import androidx.credentials.registry.digitalcredentials.mdoc.MdocField
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtClaim
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtEntry
import kotlinx.serialization.Serializable

@Serializable
data class DcApiCredential(
    val id: String,
    val format: String,
    val display: DcApiDisplay,
    val docType: String? = null,
    val fields: List<DcApiField>? = null,
    val verifiableCredentialType: String? = null,
    val claims: List<String>? = null,
) {
    val sdJwt: SdJwtEntry?
        get() {
            if (format != "sd-jwt") return null

            val verifiableCredentialType = verifiableCredentialType ?: return null
            val claims = claims?.map { SdJwtClaim(it.split("."), null, emptySet()) } ?: return null

            return SdJwtEntry(verifiableCredentialType, claims, emptySet(), id)
        }

    val mDoc: MdocEntry?
        get() {
            if (format != "mdoc") return null

            val docType = docType ?: return null
            val fields = fields?.map { MdocField(it.namespace, it.element, null, emptySet()) } ?: return null

            return MdocEntry(docType, fields, emptySet(), id)
        }
}

@Serializable
data class DcApiDisplay(
    val title: String,
)

@Serializable
data class DcApiField(
    val namespace: String,
    val element: String,
)
