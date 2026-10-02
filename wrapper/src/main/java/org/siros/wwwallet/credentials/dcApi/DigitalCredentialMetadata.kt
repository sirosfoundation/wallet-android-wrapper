package org.siros.wwwallet.credentials.dcApi

import kotlinx.io.bytestring.ByteString

/**
 * A searchable claim, not an issued credential or proof. [path] has two components for mdoc
 * (namespace and element name), and one or more components for SD-JWT.
 * [matchValue] is the unquoted string value used by the matcher, e.g. "true" for a boolean.
 */
data class DigitalCredentialClaimMetadata(
    val path: List<String>,
    val displayName: String,
    val displayValue: String,
    val matchValue: String,
)

/**
 * Metadata for a credential presented by an external wallet, e.g. a web application.
 * Exactly one of [mdocDocType] and [sdJwtVct] must be set. [id] is an opaque, space-free
 * selection identifier returned as the third component of the matcher's picker entry ID.
 * No keys or issuer-signed credentials are stored by this API.
 *
 * Issuer identifiers are certificate AuthorityKeyIdentifiers, not issuer display names.
 * If absent, requests requiring an issuer identifier will not match.
 */
data class DigitalCredentialMetadata(
    val id: String,
    val title: String,
    val subtitle: String,
    val bitmap: ByteString,
    val claims: List<DigitalCredentialClaimMetadata>,
    val mdocDocType: String? = null,
    val sdJwtVct: String? = null,
    val issuerIdentifiers: List<ByteString> = emptyList(),
)
