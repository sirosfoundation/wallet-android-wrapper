package org.siros.wwwallet.zkp

import org.siros.sdk.credentials.CircuitZkCircuit
import org.siros.sdk.credentials.RulebookCircuitZkValidator
import org.siros.sdk.keystore.CircuitZkProver
import org.siros.sdk.keystore.ZkProverResidency

/**
 * Builds the [ZkConfig] for nym_auth `circuit_zk` presentations.
 *
 * [catalogue] mirrors the mdoc entries of the SIROS circuit catalogue
 * (https://api.circuits.siros.org/v1/manifest.json, manifest generated 2026-10-13):
 * `nymauth-longfellow-mdoc-v1` (system `longfellow-nymauth`, systemVersion `61a8a73`) and
 * `nymauth-vega-mdoc-v1` (system `vega-nymauth`, systemVersion `c0ee259`), both for doctype
 * `eu.europa.ec.eudi.nym_auth.1` and configuration `known-offender-matching-ssc`.
 * Only mdoc is listed because [ZkpBridge] decodes mdoc credentials; add the `sdjwt` entries only
 * together with SD-JWT support. Re-copy these values when the catalogue is republished.
 *
 * The prover must be a real [CircuitZkProver] for these circuits. The SDK ships only a fake one,
 * so the wallet cannot present until one is supplied.
 */
object NymAuthZk {
    const val DOC_TYPE = "eu.europa.ec.eudi.nym_auth.1"
    const val CATALOGUE_BASE = "https://api.circuits.siros.org"

    val catalogue: List<ZkCircuit> =
        listOf(
            ZkCircuit(
                circuit =
                    CircuitZkCircuit(
                        system = "longfellow-nymauth",
                        circuitId = "nymauth-longfellow-mdoc-v1",
                        docTypes = setOf(DOC_TYPE),
                    ),
                configuration = "known-offender-matching-ssc",
                proofSystemVersion = "61a8a73",
                artifact =
                    ZkArtifact(
                        url = "$CATALOGUE_BASE/v1/artifacts/sha256/6c4b96ed431ee547a3a2e5b62621943a9a0a44646267653bf2db0fcc269b5c3b",
                        hash = "sha256:6c4b96ed431ee547a3a2e5b62621943a9a0a44646267653bf2db0fcc269b5c3b",
                        compression = "zstd",
                    ),
            ),
            ZkCircuit(
                circuit =
                    CircuitZkCircuit(
                        system = "vega-nymauth",
                        circuitId = "nymauth-vega-mdoc-v1",
                        docTypes = setOf(DOC_TYPE),
                    ),
                configuration = "known-offender-matching-ssc",
                proofSystemVersion = "c0ee259",
                artifact =
                    ZkArtifact(
                        url = "$CATALOGUE_BASE/v1/artifacts/sha256/b457ba5382eb7604ef035e790f2cb24f0cd5fdcd065f383ff1c2c49fab1bb4df",
                        hash = "sha256:b457ba5382eb7604ef035e790f2cb24f0cd5fdcd065f383ff1c2c49fab1bb4df",
                        compression = "zstd",
                    ),
            ),
        )

    fun config(
        prover: CircuitZkProver,
        catalogue: List<ZkCircuit> = NymAuthZk.catalogue,
        residency: ZkProverResidency? = null,
    ): ZkConfig {
        val source = org.siros.sdk.credentials.CircuitZkCircuitSource { catalogue.map { it.circuit } }
        return ZkConfig(
            prover = prover,
            catalogue = catalogue,
            validator = RulebookCircuitZkValidator(circuits = source),
            residency = residency,
        )
    }
}