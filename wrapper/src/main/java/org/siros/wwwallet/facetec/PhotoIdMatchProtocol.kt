package org.siros.wwwallet.facetec

import androidx.annotation.StringRes
import org.json.JSONObject
import org.siros.wwwallet.R
import java.util.UUID

/**
 * The parts of the facetec-api `/v1/process-request` contract (v0.16.0) that the FaceTec-only
 * [PhotoIdMatchSessionRequestProcessor] and [PhotoIdMatchActivity] rely on, kept free of FaceTec
 * types so they are compiled, and unit tested, in every build.
 */
object PhotoIdMatchProtocol {
    /** Prefixes the per-session identifier, so a record in FaceTec Server can be traced to this app. */
    const val EXTERNAL_DB_REF_PREFIX = "wwwallet-android-"

    /** JSON key of the request's per-session identifier. */
    const val KEY_EXTERNAL_DATABASE_REF_ID = "externalDatabaseRefID"

    /** JSON key of the credential offer in the response, set once facetec-api issued. */
    const val KEY_CREDENTIAL_OFFER_URI = "credentialOfferURI"

    /** JSON key of the refusal code in the response (not `error_code`, which only the legacy `/v1` endpoints use). */
    const val KEY_CREDENTIAL_ISSUE_ERROR_CODE = "credentialIssueErrorCode"

    /**
     * A new identifier for one FaceTec session. facetec-api v0.16.0 ties FaceTec Server's liveness
     * verdict to it and refuses the final result with `liveness_failed` unless every request of
     * the session carried the same one, so it is generated once per session and never reused:
     * the liveness proof is single-use and expires after 15 minutes.
     */
    fun newExternalDatabaseRefID(): String = EXTERNAL_DB_REF_PREFIX + UUID.randomUUID()

    /** The request body for one `/v1/process-request` call. */
    fun requestPayload(
        requestBlob: String,
        externalDatabaseRefID: String,
    ): JSONObject =
        JSONObject()
            .put("requestBlob", requestBlob)
            .put(KEY_EXTERNAL_DATABASE_REF_ID, externalDatabaseRefID)

    /** The credential offer in a response, if facetec-api issued a credential. */
    fun credentialOfferURI(response: JSONObject): String? = response.optString(KEY_CREDENTIAL_OFFER_URI).takeIf { it.isNotBlank() }

    /** The refusal code in a response, if the scan completed but facetec-api refused to issue. */
    fun credentialIssueErrorCode(response: JSONObject): String? = response.optString(KEY_CREDENTIAL_ISSUE_ERROR_CODE).takeIf { it.isNotBlank() }

    /**
     * The message explaining a refusal code to the user. A code this app does not know, or that
     * facetec-api adds later, gets the generic message.
     */
    @StringRes
    fun refusalMessage(credentialIssueErrorCode: String): Int =
        when (credentialIssueErrorCode) {
            "nfc_not_requested" -> R.string.photo_id_match_refused_nfc_not_requested
            "nfc_device_not_capable" -> R.string.photo_id_match_refused_nfc_device_not_capable
            "nfc_skipped" -> R.string.photo_id_match_refused_nfc_skipped
            "nfc_chip_read_failed" -> R.string.photo_id_match_refused_nfc_chip_read_failed
            "nfc_not_authenticated" -> R.string.photo_id_match_refused_nfc_not_authenticated
            "chip_untrusted" -> R.string.photo_id_match_refused_chip_untrusted
            "policy_rejected" -> R.string.photo_id_match_refused_policy_rejected
            "match_failed" -> R.string.photo_id_match_refused_match_failed
            "issuance_failed" -> R.string.photo_id_match_refused_issuance_failed
            "internal_error" -> R.string.photo_id_match_refused_internal_error
            "liveness_failed" -> R.string.photo_id_match_refused_liveness_failed
            "document_expired" -> R.string.photo_id_match_refused_document_expired
            "document_unreadable" -> R.string.photo_id_match_refused_document_unreadable
            "session_expired" -> R.string.photo_id_match_refused_session_expired
            else -> R.string.photo_id_match_refused_other
        }
}
