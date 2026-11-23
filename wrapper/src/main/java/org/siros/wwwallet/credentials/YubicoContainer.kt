package org.siros.wwwallet.credentials

import android.util.Base64.NO_PADDING
import android.util.Base64.NO_WRAP
import android.util.Base64.URL_SAFE
import android.util.Base64.encodeToString
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.client.Ctap2Client
import com.yubico.yubikit.fido.client.MultipleAssertionsAvailable
import com.yubico.yubikit.fido.client.WebAuthnClient
import com.yubico.yubikit.fido.client.clientdata.ClientDataProvider
import com.yubico.yubikit.fido.client.extensions.CredBlobExtension
import com.yubico.yubikit.fido.client.extensions.CredPropsExtension
import com.yubico.yubikit.fido.client.extensions.CredProtectExtension
import com.yubico.yubikit.fido.client.extensions.HmacSecretExtension
import com.yubico.yubikit.fido.client.extensions.LargeBlobExtension
import com.yubico.yubikit.fido.client.extensions.MinPinLengthExtension
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRequestOptions
import com.yubico.yubikit.fido.webauthn.SerializationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.siros.wwwallet.MainActivity
import org.siros.wwwallet.json.getOrNull
import org.siros.wwwallet.json.toMap
import org.siros.wwwallet.ui.HandlingHintType
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class UserCancellationException : Exception()

class YubicoContainer(
    val activity: MainActivity,
) : Container {
    companion object {
        // In seconds.
        private const val PIN_CACHE_TIMEOUT = 60
    }

    private val manager: YubiKitManager = YubiKitManager(activity)

    private var pinResetJob: Job? = null

    private var pin: String? = null
        set(value) {
            field = value
            pinResetJob?.cancel()

            if (value != null) {
                Timber.i("Start PIN timeout.")

                pinResetJob =
                    (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                        delay(PIN_CACHE_TIMEOUT.seconds)
                        pin = null
                        Timber.i("PIN cleared after timeout.")
                    }
            }
        }

    private suspend fun discoverDevice(): YubiKeyDevice? {
        val deferred = CompletableDeferred<YubiKeyDevice?>()

        activity.vm.showStartDialog {
            manager.stopUsbDiscovery()
            if (!activity.isDestroyed) manager.stopNfcDiscovery(activity)

            deferred.complete(null)
        }

        var usbWon = false
        var nfcWon = false

        manager.startUsbDiscovery(UsbConfiguration().handlePermissions(true)) { device ->
            if (nfcWon) return@startUsbDiscovery

            usbWon = true

            if (!activity.isDestroyed) manager.stopNfcDiscovery(activity)

            activity.vm.dismissStartDialog(false)

            deferred.complete(device)
        }

        // Above callback could have been called synchronously, when a USB key was already
        // attached and activated.
        if (!usbWon) {
            try {
                manager.startNfcDiscovery(
                    NfcConfiguration().timeout(10_000), // .disableNfcDiscoverySound(true),
                    activity,
                ) { device ->
                    if (usbWon) return@startNfcDiscovery

                    nfcWon = true

                    manager.stopUsbDiscovery()

                    activity.vm.dismissStartDialog(false)

                    deferred.complete(device)
                }
            } catch (e: NfcNotAvailable) {
                Timber.i(e, "No NFC, ignoring.")
            }
        }

        return deferred.await()
    }

    private var newPin: String? = null

    override fun create(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) {
        Timber.i("yubico create implementation called.")

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val device = discoverDevice()

            if (device == null) {
                // User might have chickened out of setting a PIN on a new security key.
                // Remove stored new PIN again, so no surprises happen on a second attempt and UI
                // is shown again.
                this@YubicoContainer.newPin = null

                failureCallback(UserCancellationException())
                return@launch
            }

            val client: WebAuthnClient

            try {
                client = createClient(device)
            } catch (t: Throwable) {
                stop()
                failureCallback(t)
                return@launch
            }

            if ((client as? Ctap2Client)?.isPinConfigured == false) {
                // Second run: We now have a PIN to set.
                newPin?.let { newPin ->
                    if (newPin.isNotEmpty()) {
                        try {
                            client.setPin(newPin.toCharArray())
                        } catch (t: Throwable) {
                            Timber.e(t, "Error while setting PIN.")

                            this@YubicoContainer.newPin = null

                            stop(client)

                            failureCallback(t)
                            return@launch
                        }

                        // Always unset. Another attempt to set a PIN on an unconfigured security
                        // key (be it the same after a failed attempt or a second one after a
                        // successful attempt) should always show UI again, and not
                        // automatically set an earlier defined PIN, which would be highly
                        // confusing to the user.
                        this@YubicoContainer.newPin = null

                        // NOTE: We *could* set this@YubicoContainer.pin = newPin, however,
                        // we want the user to remember their freshly set PIN, so we deliberately
                        // *don't* do that, so the user has a chance to repeat it again and
                        // remember it.

                        stop(client)

                        create(options, successCallback, failureCallback)
                        return@launch
                    }
                }

                stop(client)

                // First run: Get a new PIN from the user.
                newPin =
                    suspendCancellableCoroutine { continuation ->
                        activity.vm.requestPinSet { providedPin ->
                            continuation.resume(providedPin) { _, _, _ -> }
                        }
                    }

                if (newPin == null) {
                    failureCallback(UserCancellationException())
                    return@launch
                }

                create(options, successCallback, failureCallback)
                return@launch
            }

            if (pin.isNullOrEmpty()) {
                stop(client)

                pin =
                    suspendCancellableCoroutine { continuation ->
                        activity.vm.requestPin { providedPin ->
                            continuation.resume(providedPin) { _, _, _ -> }
                        }
                    }

                if (pin == null) {
                    failureCallback(UserCancellationException())
                    return@launch
                }

                create(options, successCallback, failureCallback)
                return@launch
            } else {
                pin = pin // Refresh timer
            }

            activity.vm.showHandlingHint(if (device is UsbYubiKeyDevice) HandlingHintType.USB else HandlingHintType.NFC)

            val publicKey = options.publicKey!!
            val kitOptions = PublicKeyCredentialCreationOptions.fromMap(publicKey.toMap())
            val domain = publicKey.rp?.id ?: ""
            val provider =
                ClientDataProvider.fromClientDataJson(
                    getClientOptions(
                        type = "webauthn.create",
                        origin = domain,
                        challenge =
                            encodeToString(
                                kitOptions.challenge,
                                NO_PADDING or NO_WRAP or URL_SAFE,
                            ),
                    ),
                )
            val enterprise = null
            val state = null

            try {
                val result: PublicKeyCredential =
                    client.makeCredential(
                        provider,
                        kitOptions,
                        domain,
                        pin?.toCharArray(),
                        enterprise,
                        state,
                    )

                Timber.i("Done, created $result.")
                successCallback(JSONObject(result.toMap()))
            } catch (ctap: CtapException) {
                Timber.e(
                    ctap,
                    "Protocol exception: '${ctap.ctapError.toHumanReadable()}'.",
                )
                failureCallback(ctap)
            } catch (th: Throwable) {
                pin = null // Reset. This error might be due to an invalid PIN.
                Timber.e(th, "Unexpected error: '${th.message}'.")
                failureCallback(th)
            } finally {
                stop(client)

                activity.vm.dismissHandlingHint()
            }
        }
    }

    override fun get(
        options: JSONObject,
        successCallback: (JSONObject) -> Unit,
        failureCallback: (Throwable) -> Unit,
    ) {
        Timber.i("yubico get implementation called.")

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val device = discoverDevice()

            if (device == null) {
                failureCallback(UserCancellationException())
                return@launch
            }

            if (pin.isNullOrEmpty()) {
                pin =
                    suspendCancellableCoroutine { continuation ->
                        activity.vm.requestPin { providedPin ->
                            continuation.resume(providedPin) { _, _, _ -> }
                        }
                    }

                if (pin == null) {
                    stop()
                    failureCallback(UserCancellationException())
                    return@launch
                }

                get(options, successCallback, failureCallback)
                return@launch
            } else {
                pin = pin // Refresh timer
            }

            activity.vm.showHandlingHint(if (device is UsbYubiKeyDevice) HandlingHintType.USB else HandlingHintType.NFC)

            val publicKey = options.publicKey!!
            val kitOptions = PublicKeyCredentialRequestOptions.fromMap(publicKey.toMap())
            val domain = publicKey.rpId ?: ""
            val provider =
                ClientDataProvider.fromClientDataJson(
                    getClientOptions(
                        type = "webauthn.get",
                        origin = domain,
                        challenge =
                            encodeToString(
                                kitOptions.challenge,
                                NO_PADDING or NO_WRAP or URL_SAFE,
                            ),
                    ),
                )
            val enterprise = null
            var client: WebAuthnClient? = null

            var newOptions: JSONObject? = null

            try {
                client = createClient(device)

                val result =
                    client.getAssertion(
                        provider,
                        kitOptions,
                        domain,
                        pin?.toCharArray(),
                        enterprise,
                    )

                Timber.i("Done, got $result.")
                successCallback(JSONObject(result.toMap()))
            } catch (ctap: CtapException) {
                Timber.e(
                    ctap,
                    "Protocol exception: '${ctap.ctapError.toHumanReadable()}'.",
                )
                failureCallback(ctap)
            } catch (multiple: MultipleAssertionsAvailable) {
                Timber.i("Found several assertions. User selection needed.")

                activity.vm.dismissHandlingHint()

                newOptions = requestSelection(multiple, kitOptions)

                if (newOptions == null) {
                    failureCallback(UserCancellationException())
                }
            } catch (th: Throwable) {
                pin = null // Reset. This error might be due to an invalid PIN.
                Timber.e(th, "Unexpected error: '${th.message}'.")
                failureCallback(th)
            } finally {
                stop(client)

                activity.vm.dismissHandlingHint()

                newOptions?.let {
                    get(it, successCallback, failureCallback)
                }
            }
        }
    }

    private fun createClient(device: YubiKeyDevice) =
        WebAuthnClient.create(
            device,
            listOf(
                CredPropsExtension(),
                CredBlobExtension(),
                CredProtectExtension(),
                HmacSecretExtension(),
                MinPinLengthExtension(),
                LargeBlobExtension(),
            ),
        )

    private fun stop(client: WebAuthnClient? = null) {
        try {
            client?.close()
        } catch (_: Throwable) {
            // Ignored. If we cannot close it anymore, because it's out of date, we're fine.
        }

        manager.stopUsbDiscovery()
        if (!activity.isDestroyed) manager.stopNfcDiscovery(activity)
    }

    private suspend fun requestSelection(
        multiple: MultipleAssertionsAvailable,
        kitOptions: PublicKeyCredentialRequestOptions,
    ): JSONObject? {
        val credential =
            suspendCancellableCoroutine { continuation ->
                activity.vm.requestCredential(multiple) { credential ->
                    continuation.resume(credential) { _, _, _ -> }
                }
            }

        if (credential == null) {
            return null
        }

        kitOptions.allowCredentials.clear()
        kitOptions.allowCredentials.add(
            PublicKeyCredentialDescriptor(
                "public-key",
                credential.rawId,
                null,
            ),
        )

        return JSONObject(mapOf("publicKey" to kitOptions.toMap(SerializationType.JSON)))
    }
}

@Suppress("DEPRECATION")
private fun Byte.toHumanReadable(): String =
    when (this) {
        CtapException.ERR_SUCCESS -> "ERR_SUCCESS"
        CtapException.ERR_INVALID_COMMAND -> "ERR_INVALID_COMMAND"
        CtapException.ERR_INVALID_PARAMETER -> "ERR_INVALID_PARAMETER"
        CtapException.ERR_INVALID_LENGTH -> "ERR_INVALID_LENGTH"
        CtapException.ERR_INVALID_SEQ -> "ERR_INVALID_SEQ"
        CtapException.ERR_TIMEOUT -> "ERR_TIMEOUT"
        CtapException.ERR_CHANNEL_BUSY -> "ERR_CHANNEL_BUSY"
        CtapException.ERR_LOCK_REQUIRED -> "ERR_LOCK_REQUIRED"
        CtapException.ERR_INVALID_CHANNEL -> "ERR_INVALID_CHANNEL"
        CtapException.ERR_CBOR_UNEXPECTED_TYPE -> "ERR_CBOR_UNEXPECTED_TYPE"
        CtapException.ERR_INVALID_CBOR -> "ERR_INVALID_CBOR"
        CtapException.ERR_MISSING_PARAMETER -> "ERR_MISSING_PARAMETER"
        CtapException.ERR_LIMIT_EXCEEDED -> "ERR_LIMIT_EXCEEDED"
        CtapException.ERR_UNSUPPORTED_EXTENSION -> "ERR_UNSUPPORTED_EXTENSION"
        CtapException.ERR_FP_DATABASE_FULL -> "ERR_FP_DATABASE_FULL"
        CtapException.ERR_CREDENTIAL_EXCLUDED -> "ERR_CREDENTIAL_EXCLUDED"
        CtapException.ERR_PROCESSING -> "ERR_PROCESSING"
        CtapException.ERR_INVALID_CREDENTIAL -> "ERR_INVALID_CREDENTIAL"
        CtapException.ERR_USER_ACTION_PENDING -> "ERR_USER_ACTION_PENDING"
        CtapException.ERR_OPERATION_PENDING -> "ERR_OPERATION_PENDING"
        CtapException.ERR_NO_OPERATIONS -> "ERR_NO_OPERATIONS"
        CtapException.ERR_UNSUPPORTED_ALGORITHM -> "ERR_UNSUPPORTED_ALGORITHM"
        CtapException.ERR_OPERATION_DENIED -> "ERR_OPERATION_DENIED"
        CtapException.ERR_KEY_STORE_FULL -> "ERR_KEY_STORE_FULL"
        CtapException.ERR_NOT_BUSY -> "ERR_NOT_BUSY"
        CtapException.ERR_NO_OPERATION_PENDING -> "ERR_NO_OPERATION_PENDING"
        CtapException.ERR_UNSUPPORTED_OPTION -> "ERR_UNSUPPORTED_OPTION"
        CtapException.ERR_INVALID_OPTION -> "ERR_INVALID_OPTION"
        CtapException.ERR_KEEPALIVE_CANCEL -> "ERR_KEEPALIVE_CANCEL"
        CtapException.ERR_NO_CREDENTIALS -> "ERR_NO_CREDENTIALS"
        CtapException.ERR_USER_ACTION_TIMEOUT -> "ERR_USER_ACTION_TIMEOUT"
        CtapException.ERR_NOT_ALLOWED -> "ERR_NOT_ALLOWED"
        CtapException.ERR_PIN_INVALID -> "ERR_PIN_INVALID"
        CtapException.ERR_PIN_BLOCKED -> "ERR_PIN_BLOCKED"
        CtapException.ERR_PIN_AUTH_INVALID -> "ERR_PIN_AUTH_INVALID"
        CtapException.ERR_PIN_AUTH_BLOCKED -> "ERR_PIN_AUTH_BLOCKED"
        CtapException.ERR_PIN_NOT_SET -> "ERR_PIN_NOT_SET"
        CtapException.ERR_PIN_POLICY_VIOLATION -> "ERR_PIN_POLICY_VIOLATION"
        CtapException.ERR_PIN_TOKEN_EXPIRED -> "ERR_PIN_TOKEN_EXPIRED"
        CtapException.ERR_REQUEST_TOO_LARGE -> "ERR_REQUEST_TOO_LARGE"
        CtapException.ERR_ACTION_TIMEOUT -> "ERR_ACTION_TIMEOUT"
        CtapException.ERR_UP_REQUIRED -> "ERR_UP_REQUIRED"
        CtapException.ERR_UV_BLOCKED -> "ERR_UV_BLOCKED"
        CtapException.ERR_INTEGRITY_FAILURE -> "ERR_INTEGRITY_FAILURE"
        CtapException.ERR_INVALID_SUBCOMMAND -> "ERR_INVALID_SUBCOMMAND"
        CtapException.ERR_UV_INVALID -> "ERR_UV_INVALID"
        CtapException.ERR_UNAUTHORIZED_PERMISSION -> "ERR_UNAUTHORIZED_PERMISSION"
        CtapException.ERR_OTHER -> "ERR_OTHER"
        CtapException.ERR_SPEC_LAST -> "ERR_SPEC_LAST"
        CtapException.ERR_EXTENSION_FIRST -> "ERR_EXTENSION_FIRST"
        CtapException.ERR_EXTENSION_LAST -> "ERR_EXTENSION_LAST"
        CtapException.ERR_VENDOR_FIRST -> "ERR_VENDOR_FIRST"
        CtapException.ERR_VENDOR_LAST -> "ERR_VENDOR_LAST"
        else -> "Unknown CTAP ERROR"
    }

private val JSONObject.publicKey: JSONObject?
    get() = getOrNull("publicKey")

private val JSONObject.rpId: String?
    get() = getOrNull("rpId")

private val JSONObject.rp: JSONObject?
    get() = getOrNull("rp")

private val JSONObject.id: String?
    get() = getOrNull("id")
