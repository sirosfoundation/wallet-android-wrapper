@file:OptIn(ExperimentalStdlibApi::class)

package io.yubicolabs.wwwwallet.credentials

import android.content.Context
import io.yubicolabs.wwwwallet.BuildConfig
import io.yubicolabs.wwwwallet.json.getNested
import io.yubicolabs.wwwwallet.json.toList
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class LocalContainer(
    val context: Context,
) : Container {
    private val origin: String = BuildConfig.APPLICATION_ID

    private val storage: MutableMap<String, KeyPair> = mutableMapOf()

    override fun create(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) = try {
        // todo check rp id and options

        val credentialId = ByteArray(32)
        SecureRandom().nextBytes(credentialId)

        // Generate a credential key pair
        // TODO Update algorithm name to match requested
        val spec = ECGenParameterSpec("secp256r1")
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(spec)
        val keyPair = keyPairGen.genKeyPair()

        // TODO
        // TODO Save passkey securely
        // TODO
        storage[credentialId.toHexString()] = keyPair

        val response =
            JSONObject(
                mapOf(
                    "requestOptions" to options,
                    "credentialId" to credentialId,
                    "credentialPublicKey" to keyPair.public,
                    "origin" to origin,
                    "up" to true,
                    "uv" to true,
                    "be" to true,
                    "bs" to true,
                ),
            )

        val credential =
            JSONObject(
                mapOf(
                    "rawId" to credentialId,
                    "response" to response,
                    "authenticatorAttachment" to "",
                    // TODO: Add our authenticator attachment, signature, extensions, etc...
                ),
            )

        successCallback(credential)
    } catch (th: Throwable) {
        failureCallback(th)
    }

    override fun get(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) = try {
        val allowedCredentials = (options.getNested("publicKey.allowCredentials") as? JSONArray)?.toList() ?: listOf()

        val credentials: Map<String, KeyPair> =
            if (allowedCredentials.isNotEmpty()) {
                allowedCredentials.mapNotNull { allowed ->
                    val allowedId = (allowed as? JSONObject)?.get("id") as? String
                    if (allowedId != null && allowedId in storage.keys) {
                        allowedId to storage[allowedId]!!
                    } else {
                        null
                    }
                }.toMap()
            } else {
                storage
            }

        if (credentials.isNotEmpty()) {
            // TODO MULTIPLE MATCHES??
            successCallback(
                JSONObject(credentials.values.first().public.encoded.toHexString()),
            )
        } else {
            val ids =
                allowedCredentials
                    .joinToString(separator = ",") {
                        (it as? JSONObject)?.get("id") as? String ?: "null"
                    }

            failureCallback(
                NoSuchElementException("No credential with id in [$ids] found in credential storage."),
            )
        }
    } catch (th: Throwable) {
        failureCallback(th)
    }
}
