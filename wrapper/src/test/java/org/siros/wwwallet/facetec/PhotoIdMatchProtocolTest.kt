package org.siros.wwwallet.facetec

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siros.wwwallet.R
import java.io.File

class PhotoIdMatchProtocolTest {
    // internal/idverrors/errors.go of facetec-api v0.16.0: the codes a client can be refused with.
    private val facetecApiCodes =
        listOf(
            "liveness_failed",
            "match_failed",
            "document_unreadable",
            "policy_rejected",
            "session_expired",
            "nfc_skipped",
            "chip_untrusted",
            "nfc_not_requested",
            "nfc_device_not_capable",
            "nfc_chip_read_failed",
            "nfc_not_authenticated",
            "document_expired",
            "issuance_failed",
            "internal_error",
        )

    @Test
    fun `every code facetec-api returns has a message of its own except the generic ones`() {
        val generic = R.string.photo_id_match_refused_other

        for (code in facetecApiCodes) {
            assertNotEquals(code, generic, PhotoIdMatchProtocol.refusalMessage(code))
        }
    }

    @Test
    fun `no two codes share a message`() {
        val messages = facetecApiCodes.map { PhotoIdMatchProtocol.refusalMessage(it) }

        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun `an unknown code gets the generic message`() {
        assertEquals(R.string.photo_id_match_refused_other, PhotoIdMatchProtocol.refusalMessage("something_new"))
        assertEquals(R.string.photo_id_match_refused_other, PhotoIdMatchProtocol.refusalMessage(""))
    }

    @Test
    fun `session_expired and chip_untrusted are explained`() {
        assertEquals(R.string.photo_id_match_refused_session_expired, PhotoIdMatchProtocol.refusalMessage("session_expired"))
        assertEquals(R.string.photo_id_match_refused_chip_untrusted, PhotoIdMatchProtocol.refusalMessage("chip_untrusted"))
    }

    @Test
    fun `the refusal code is read from credentialIssueErrorCode`() {
        val response = JSONObject("""{"responseBlob":"x","credentialIssueError":"no","credentialIssueErrorCode":"chip_untrusted"}""")

        assertEquals("chip_untrusted", PhotoIdMatchProtocol.credentialIssueErrorCode(response))
        assertNull(PhotoIdMatchProtocol.credentialOfferURI(response))
    }

    @Test
    fun `the legacy error_code key is not a refusal code`() {
        assertNull(PhotoIdMatchProtocol.credentialIssueErrorCode(JSONObject("""{"error_code":"nfc_skipped"}""")))
    }

    @Test
    fun `blank offer and code are absent`() {
        val response = JSONObject("""{"credentialOfferURI":"  ","credentialIssueErrorCode":""}""")

        assertNull(PhotoIdMatchProtocol.credentialOfferURI(response))
        assertNull(PhotoIdMatchProtocol.credentialIssueErrorCode(response))
    }

    @Test
    fun `an issued credential offer is read`() {
        val response = JSONObject("""{"credentialOfferURI":"openid-credential-offer://?x=1","transactionId":"t"}""")

        assertEquals("openid-credential-offer://?x=1", PhotoIdMatchProtocol.credentialOfferURI(response))
        assertNull(PhotoIdMatchProtocol.credentialIssueErrorCode(response))
    }

    @Test
    fun `the request carries the blob and the session's externalDatabaseRefID`() {
        val ref = PhotoIdMatchProtocol.newExternalDatabaseRefID()
        val payload = PhotoIdMatchProtocol.requestPayload("blob", ref)

        assertEquals("blob", payload.getString("requestBlob"))
        assertEquals(ref, payload.getString("externalDatabaseRefID"))
    }

    @Test
    fun `one identifier stays the same across a session's requests and differs between sessions`() {
        val first = PhotoIdMatchProtocol.newExternalDatabaseRefID()
        val second = PhotoIdMatchProtocol.newExternalDatabaseRefID()

        val session = listOf("liveness", "idscan", "nfc").map { PhotoIdMatchProtocol.requestPayload(it, first).getString("externalDatabaseRefID") }

        assertEquals(1, session.toSet().size)
        assertNotEquals(first, second)
        assertTrue(first.startsWith(PhotoIdMatchProtocol.EXTERNAL_DB_REF_PREFIX))
    }

    @Test
    fun `every refusal message is translated in each language the app ships it in`() {
        val resDir = File("src/main/res")
        val names = refusalStringNames(File(resDir, "values/strings.xml"))
        assertTrue(names.contains("photo_id_match_refused_session_expired"))

        for (locale in listOf("el", "fi", "pt", "sv")) {
            val translated = refusalStringNames(File(resDir, "values-$locale/strings.xml"))

            assertEquals("values-$locale", names, translated)
        }
    }

    private fun refusalStringNames(file: File): Set<String> =
        Regex("""name="(photo_id_match_refused_[a-z_]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
}
