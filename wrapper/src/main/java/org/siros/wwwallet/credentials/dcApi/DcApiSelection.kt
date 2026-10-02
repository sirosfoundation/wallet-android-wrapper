package org.siros.wwwallet.credentials.dcApi

import org.siros.wwwallet.json.DcApiRequest

data class DcApiSelection(
    val reference: CredentialReference,
    val request: DcApiRequest,
) {
    companion object {
        fun parse(
            entryIds: List<String>,
            requests: List<DcApiRequest>,
        ): DcApiSelection {
            require(entryIds.size == 1) { "The web wallet supports selecting only one credential per request." }
            val parts = entryIds.single().split(" ")
            require(parts.size == 3 && parts[0].toIntOrNull()?.let { it >= 0 } == true) { "Invalid digital credential selection ID." }
            require(parts[1] in DigitalCredentials.exchangeProtocols) { "Unsupported digital credential protocol." }
            val matchingRequests = requests.filter { it.protocol == parts[1] }
            require(matchingRequests.size == 1) { "Missing or ambiguous request for the selected protocol." }
            return DcApiSelection(CredentialReference.decode(parts[2]), matchingRequests.single())
        }
    }
}
