package org.siros.wwwallet.zkp

import org.siros.sdk.credentials.CircuitZkCircuit
import org.siros.sdk.credentials.RulebookCircuitZkValidator
import org.siros.sdk.keystore.CircuitZkProver
import org.siros.sdk.keystore.ZkProverResidency

/**
 * Builds the [ZkConfig] for nym_auth `circuit_zk` / `zkp_req` presentations.
 *
 * [catalogue] mirrors the mdoc entries of the SIROS circuit catalogue
 * (https://api.circuits.siros.org/v1/manifest.json):
 * `nymauth-longfellow-mdoc-v1` (system `longfellow-nymauth`, systemVersion `61a8a73`) and
 * `nymauth-vega-mdoc-v1` (system `vega-nymauth`, systemVersion `c0ee259`), both for doctype
 * `eu.europa.ec.eudi.nym_auth.1` and configuration `known-offender-matching-ssc`.
 * Each declares the functions it proves: a circuit that declares none is offered for no plan.
 *
 * Rulebook v0.4 (`zkp_req`): a response states a `system_id` the request offered (`system_id_values`) and is proved by a
 * proof system the request accepts (`proof_system_values`). The SDK takes the `system_id` from the circuit's id or aliases
 * and compares [CircuitZkCircuit.proofSystem] with the accepted proof systems, so each circuit carries its v0.4 identity
 * as an alias and its proof system.
 *
 * Only mdoc is listed because [ZkpBridge] decodes mdoc credentials. Re-copy these values when the catalogue is republished.
 */
object NymAuthZk {
    const val DOC_TYPE = "eu.europa.ec.eudi.nym_auth.1"
    const val CATALOGUE_BASE = "https://api.circuits.siros.org"

    /** The rulebook v0.4 identity of the Longfellow nym_auth circuits, and its proof system. */
    const val LONGFELLOW_SYSTEM_ID = "urn:circuits:longfellow:nym_auth:v1"
    const val LONGFELLOW_PROOF_SYSTEM = "longfellow-libzk-v1"

    /** The same for Vega (catalogue manifest, nymauth-vega-mdoc-v1). */
    const val VEGA_SYSTEM_ID = "urn:circuits:vega:nym_auth:v1"
    const val VEGA_PROOF_SYSTEM = "vega-v1"

    /** The SDK's function handler ids (v0.21.0: PseudonymFunctionHandler.functionId is "pseudonym"). */
    private val FUNCTIONS = setOf("pseudonym", "age_check")

    val catalogue: List<ZkCircuit> =
        listOf(
            ZkCircuit(
                circuit =
                    CircuitZkCircuit(
                        system = "longfellow-nymauth",
                        circuitId = "nymauth-longfellow-mdoc-v1",
                        docTypes = setOf(DOC_TYPE),
                        functions = FUNCTIONS,
                        aliases = setOf(LONGFELLOW_SYSTEM_ID),
                        systemVersion = "61a8a73",
                        proofSystem = LONGFELLOW_PROOF_SYSTEM,
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
                        functions = FUNCTIONS,
                        aliases = setOf(VEGA_SYSTEM_ID),
                        systemVersion = "c0ee259",
                        proofSystem = VEGA_PROOF_SYSTEM,
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