// app/src/debug/kotlin/org/siros/wwwallet/zkp/DebugZk.kt  -- DEBUG BUILDS ONLY
package org.siros.wwwallet.zkp

import org.siros.sdk.credentials.CircuitZkCircuit
import org.siros.sdk.keystore.FakeCircuitZkProver

/**
 * Fake prover plus one test circuit for `known-offender-matching-psc`, the config the wallet's own
 * seed can satisfy. The catalogue has only `-ssc` circuits, so without this entry a `-psc` request
 * finds no circuit. Its proofs are not valid; it must never exist in a release build.
 */
fun debugZkConfig(): ZkConfig? =
    NymAuthZk.config(
        prover = FakeCircuitZkProver.optIn(),
        catalogue =
            NymAuthZk.catalogue +
                    ZkCircuit(
                        circuit =
                            CircuitZkCircuit(
                                system = "debug-fake",
                                circuitId = "debug-fake-psc-v1",
                                docTypes = setOf(NymAuthZk.DOC_TYPE),
                            ),
                        configuration = "known-offender-matching-psc",
                        proofSystemVersion = "debug",
                        artifact = null,
                    ),
    )