package org.siros.wwwallet.credentials.dcApi

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.siros.wwwallet.json.DcApiCredential
import org.siros.wwwallet.json.DcApiRequest
import org.siros.wwwallet.json.DcApiRequestData

class DcApiMetadataTest {
    private val reference =
        CredentialReference("https://wallet.example/id/tenant/cb", "1789566381829")
    private val request =
        DcApiRequest(DcApiRequestData(nonce = "selected"), "openid4vp-v1-unsigned")

    @Test
    fun referencePreservesTenantAndOpaqueId() {
        Assert.assertEquals(reference, CredentialReference.decode(reference.encode()))
        Assert.assertFalse(reference.encode().contains(" "))
        Assert.assertNotEquals(
            reference.encode(),
            reference.copy(callbackUrl = "https://wallet.example/id/other/cb").encode(),
        )
        Assert.assertEquals(
            reference.copy(id = "space / unicode \u00e9"),
            CredentialReference.decode(reference.copy(id = "space / unicode \u00e9").encode()),
        )
    }

    @Test
    fun selectionUsesMatchedProtocolNotFirstRequest() {
        val first = DcApiRequest(DcApiRequestData(nonce = "wrong"), "openid4vp-v1-signed")
        val selected = DcApiSelection.parse(listOf("0 ${request.protocol} ${reference.encode()}"), listOf(first, request))
        Assert.assertEquals(reference, selected.reference)
        Assert.assertEquals("selected", selected.request.data.nonce)
    }

    @Test
    fun unsupportedSelectionsFailExplicitly() {
        val id = "0 ${request.protocol} ${reference.encode()}"
        Assert.assertThrows(IllegalArgumentException::class.java) {
            DcApiSelection.parse(
                listOf(
                    id,
                    id,
                ),
                listOf(request),
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            DcApiSelection.parse(
                listOf(id),
                emptyList(),
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            DcApiSelection.parse(
                listOf(id),
                listOf(request, request),
            )
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            DcApiSelection.parse(
                listOf("invalid"),
                listOf(request),
            )
        }
    }

    @Test
    fun mdocPreservesValuesAndOmitsSeedBytes() {
        val credential =
            Json.decodeFromString<DcApiCredential>(
                """
                {
                  "format":"mdoc","id":"1789566381829","docType":"eu.europa.ec.eudi.pid.1",
                  "fields":[
                    {"namespace":"eu.europa.ec.eudi.pid.1","identifier":"family_name","value":"Oldman","display":{"en":"Family Name"}},
                    {"namespace":"eu.europa.ec.eudi.pid.1","identifier":"age_over_18","value":true,"display":null},
                    {"namespace":"eu.europa.ec.eudi.pid.1","identifier":"pseudonym_seed","value":{"0":106,"1":147},"display":null}
                  ],
                  "display":{"title":"PID (mDoc)","subtitle":"Issued by siros-id"}
                }
                """.trimIndent(),
            )
        val metadata = credential.toMatcherMetadata(reference.encode(), ByteString())
        Assert.assertEquals("eu.europa.ec.eudi.pid.1", metadata.mdocDocType)
        Assert.assertEquals("Oldman", metadata.claims[0].matchValue)
        Assert.assertEquals("true", metadata.claims[1].matchValue)
        Assert.assertEquals("pseudonym_seed", metadata.claims[2].path[1])
        Assert.assertEquals("", metadata.claims[2].matchValue)
        Assert.assertEquals("", metadata.claims[2].displayValue)
        Assert.assertFalse(metadata.toString().contains("147"))
    }

    @Test
    fun legacyMdocAndSdJwtClaimsAreIndexed() {
        val credential =
            Json.decodeFromString<DcApiCredential>(
                """
                {
                  "format":"mdoc","id":"legacy","docType":"pid",
                  "claims":[{"path":"pid.namespace.name","value":"Gary","display":{}}],
                  "display":{"title":"PID","subtitle":null}
                }
                """.trimIndent(),
            )
        val mdocMetadata = credential.toMatcherMetadata("id", kotlinx.io.bytestring.ByteString())
        Assert.assertEquals(listOf("pid.namespace", "name"), mdocMetadata.claims.single().path)
        val sdjwt = credential.copy(format = DcApiCredential.FORMAT_SD_JWT, docType = null, verifiableCredentialType = "urn:pid")
        val sdjwtMetadata = sdjwt.toMatcherMetadata("id", kotlinx.io.bytestring.ByteString())
        Assert.assertEquals(listOf("pid", "namespace", "name"), sdjwtMetadata.claims.single().path)
        Assert.assertEquals("urn:pid", sdjwtMetadata.sdJwtVct)
    }
}
