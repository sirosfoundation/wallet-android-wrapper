package org.siros.wwwallet.credentials.dcApi

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.siros.wwwallet.credentials.dcApi.DigitalCredentials.calculateMetadataDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DigitalCredentialMetadataTest {
    private val credential =
        DigitalCredentialMetadata(
            id = "pid",
            title = "PID",
            subtitle = "Issuer",
            bitmap = ByteString(),
            mdocDocType = "eu.europa.ec.eudi.pid.1",
            claims =
                listOf(
                    DigitalCredentialClaimMetadata(
                        listOf("eu.europa.ec.eudi.pid.1", "age_over_18"),
                        "Age",
                        "true",
                        "true",
                    ),
                ),
        )
    private val protocols = setOf("openid4vp-v1-unsigned")

    @Test
    fun mdocIndex() {
        val database = Cbor.decode(Cbor.encode(calculateMetadataDatabase(listOf(credential), protocols)))
        val mdoc = database["credentials"].asArray[0]["mdoc"]
        assertEquals("pid", mdoc["documentId"].asTstr)
        assertEquals("true", mdoc["namespaces"]["eu.europa.ec.eudi.pid.1"]["age_over_18"].asArray[2].asTstr)
    }

    @Test
    fun sdJwtIndex() {
        val sdjwt =
            credential.copy(
                mdocDocType = null,
                sdJwtVct = "urn:eudi:pid:1",
                claims = listOf(DigitalCredentialClaimMetadata(listOf("address", "country"), "Country", "DE", "DE")),
            )
        val database = calculateMetadataDatabase(listOf(sdjwt), protocols)
        assertEquals("DE", database["credentials"].asArray[0]["sdjwt"]["claims"]["address.country"].asArray[2].asTstr)
    }

    @Test
    fun emptyIndexClearsCredentials() {
        assertEquals(0, calculateMetadataDatabase(emptyList(), protocols)["credentials"].asArray.size)
    }

    @Test
    fun invalidMetadataRejected() {
        assertFailsWith<IllegalArgumentException> { calculateMetadataDatabase(listOf(credential, credential), protocols) }
        assertFailsWith<IllegalArgumentException> { calculateMetadataDatabase(listOf(credential.copy(id = "bad id")), protocols) }
        assertFailsWith<IllegalArgumentException> { calculateMetadataDatabase(listOf(credential.copy(sdJwtVct = "vct")), protocols) }
        assertFailsWith<IllegalArgumentException> { calculateMetadataDatabase(listOf(credential), setOf("unsupported")) }
    }
}
