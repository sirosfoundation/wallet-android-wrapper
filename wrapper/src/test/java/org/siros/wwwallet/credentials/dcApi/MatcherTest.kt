package org.siros.wwwallet.credentials.dcApi

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.siros.wwwallet.credentials.dcApi.DigitalCredentials.calculateMetadataDatabase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the existing C++ matcher harness against metadata-only registrations. */
class MatcherTest {
    init {
        System.loadLibrary("MatcherTest")
    }

    external fun runMatcher(
        request: ByteArray,
        credentialDatabase: ByteArray,
    ): String

    private fun match(
        format: String,
        claim: String,
        seed: Boolean = true,
        values: String = "",
    ): String {
        val protocol = "openid4vp-v1-unsigned"
        val credential =
            DigitalCredentialMetadata(
                id = "web-pid",
                title = "PID",
                subtitle = "Issuer",
                bitmap = ByteString(),
                mdocDocType = "eu.europa.ec.eudi.pid.1",
                claims =
                    buildList {
                        add(DigitalCredentialClaimMetadata(listOf("eu.europa.ec.eudi.pid.1", "age_over_18"), "Age", "true", "true"))
                        if (seed) {
                            add(DigitalCredentialClaimMetadata(listOf("eu.europa.ec.eudi.pid.1", "pseudonym_seed"), "Seed", "", ""))
                        }
                    },
            )
        val request =
            """
            {"requests":[{"protocol":"$protocol","data":{"dcql_query":{"credentials":[{
                "id":"pid","format":"$format","meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},
                "claims":[{"path":["eu.europa.ec.eudi.pid.1","$claim"]$values}]
            }]}}}]}
            """.trimIndent()
        return runMatcher(
            request.encodeToByteArray(),
            Cbor.encode(calculateMetadataDatabase(listOf(credential), setOf(protocol))),
        )
    }

    @Test
    fun pseudonymMatchesOnlyZkpWithSeed() {
        val result = match("mso_mdoc_zk", "pairwise_pseudonym")
        assertTrue(result.contains("web-pid"))
        assertTrue(result.contains("Pairwise pseudonym"))
        assertFalse(result.contains("Seed:"))
        assertFalse(match("mso_mdoc", "pairwise_pseudonym").contains("web-pid"))
        assertFalse(match("mso_mdoc_zk", "pairwise_pseudonym", seed = false).contains("web-pid"))
        assertFalse(match("mso_mdoc_zk", "pairwise_pseudonym", values = ""","values":["unknown"]""").contains("web-pid"))
    }

    @Test
    fun booleanValueMatchingWorksForBothMdocFormats() {
        for (format in listOf("mso_mdoc", "mso_mdoc_zk")) {
            assertTrue(match(format, "age_over_18", values = ""","values":[true]""").contains("web-pid"))
            assertFalse(match(format, "age_over_18", values = ""","values":[false]""").contains("web-pid"))
        }
    }
}
