package org.siros.wwwallet.bridging

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.credentials.registry.digitalcredentials.openid4vp.OpenId4VpRegistry
import androidx.credentials.registry.provider.RegistryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.siros.wwwallet.BuildConfig
import org.siros.wwwallet.bluetooth.BleClientHandler
import org.siros.wwwallet.bluetooth.BleServerHandler
import org.siros.wwwallet.bluetooth.ServiceCharacteristic
import org.siros.wwwallet.credentials.Container
import org.siros.wwwallet.json.toList
import timber.log.Timber
import kotlin.coroutines.EmptyCoroutineContext

class WalletJsBridge(
    private val webView: WebView,
    private val dispatcher: CoroutineDispatcher,
    private val securityKeyCredentialsContainer: Container,
    private val clientDeviceCredentialsContainer: Container,
    private val bleClientHandler: BleClientHandler,
    private val bleServerHandler: BleServerHandler,
    private val debugMenuHandler: DebugMenuHandler?,
    private val startPhotoIdMatch: () -> Unit,
) {
    companion object {
        const val JAVASCRIPT_BRIDGE_NAME = "nativeWrapper"
    }

    private fun credentialsContainerByOption(mappedOptions: JSONObject): Container =
        try {
            val publicKey = mappedOptions.getJSONObject("publicKey")
            // throws JSONException if not present
            val jsonHints = publicKey.getJSONArray("hints")
            val hints = jsonHints.toList().mapNotNull { it as? String }

            if (hints.contains("security-key")) {
                securityKeyCredentialsContainer
            } else {
                clientDeviceCredentialsContainer
            }
        } catch (jsonException: JSONException) {
            Timber.i(
                jsonException,
                "'hints' field in credential options not found, defaulting back to 'client-device'.",
            )
            clientDeviceCredentialsContainer
        }

    /**
     * Call this to overwrite the `navigator.credentials.[get|create]` methods.
     */
    @JavascriptInterface
    @Suppress("unused")
    fun inject() {
        Timber.i("Adding `${javaClass.simpleName}` as `$JAVASCRIPT_BRIDGE_NAME` to JS.")

        dispatcher.dispatch(EmptyCoroutineContext) {
            val injectionSnippet =
                JSCodeSnippet.fromRawResource(
                    context = webView.context,
                    resource = "injectjs.js",
                    replacements =
                        listOf(
                            "JAVASCRIPT_BRIDGE" to JAVASCRIPT_BRIDGE_NAME,
                            "JAVASCRIPT_VISUALIZE_INJECTION" to "${BuildConfig.VISUALIZE_INJECTION}",
                        ),
                )

            webView.evaluateJavascript(injectionSnippet.code) {
                Timber.i(it)
            }
        }
    }

    /**
     * Entry point for the web app's "Scan Physical ID" flow. Its presence on
     * the bridge is what makes the web app show/enable that flow at all
     * (see `isNativeScanAvailable` in wallet-frontend).
     *
     * Launches the native FaceTec Photo ID Match Activity (see
     * [org.siros.wwwallet.facetec.PhotoIdMatchActivity]). Its result is handled by
     * `MainActivity`/`MainViewModel`, which navigate the WebView directly to the
     * resulting credential offer when facetec-api accepts the scan.
     *
     * Captures the WebView's current URL before departing to FaceTec. wallet-frontend
     * may be deployed multi-tenant (URLs prefixed with e.g. "/id/<tenant>/") — the app
     * has no business knowing that routing structure, but the page the user is already
     * on is guaranteed to be correctly tenant-scoped, since they got there by using the
     * wallet normally. Returning to that same URL (with the credential offer appended
     * as a query param) lets wallet-frontend's own UriHandlerProvider pick it up and
     * route it correctly, tenant and all — see MainViewModel#photoIdMatchCompleted.
     */
    @JavascriptInterface
    @Suppress("unused")
    fun startScanPhysicalId() {
        Timber.i("$JAVASCRIPT_BRIDGE_NAME.startScanPhysicalId() called.")

        // @JavascriptInterface methods run on the "JavaBridge" thread, not the main
        // thread — webView.url (like all WebView methods) must only be touched on
        // the thread the WebView was created on, so read it inside the dispatch to
        // Dispatchers.Main below, not before it.
        Dispatchers.Main.dispatch(EmptyCoroutineContext) {
            startPhotoIdMatch()
        }
    }

    @JavascriptInterface
    @Suppress("unused")
    fun openDebugMenu() {
        Dispatchers.Main.dispatch(EmptyCoroutineContext) {
            debugMenuHandler?.onMenuOpened { code, callback ->
                dispatcher.dispatch(EmptyCoroutineContext) {
                    webView.evaluateJavascript(
                        code,
                        callback,
                    )
                }
            }
        }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @JavascriptInterface
    @Suppress("unused")
    fun updateAllCredentials(list: String) {
        val credentials: List<DcApiCredential>

        try {
            credentials = json.decodeFromString(list)
        } catch (e: Exception) {
            Timber.e(e.stackTraceToString())
            return
        }

        Timber.i("Received ${credentials.size} credentials.")

        CoroutineScope(dispatcher).launch {
            val bitmap = getAppIconBitmap()

            credentials.forEach { it.bitmap = bitmap }
            val sdJwts = credentials.mapNotNull { it.sdJwt }
            val mDocs = credentials.mapNotNull { it.mDoc }

            val rm = RegistryManager.create(webView.context)
            val request = OpenId4VpRegistry(sdJwts + mDocs, webView.context.packageName)

            try {
                val response = rm.registerCredentials(request)
                Timber.i("Registration succeeded: $response")
            } catch (e: Exception) {
                Timber.e(e, "Registration failed")
            }
        }
    }

    @JavascriptInterface
    fun sendDcApiResponse(
        response: String?,
        error: String?,
    ) {
        Timber.i("Received GET_CREDENTIALS response: $response, error: $error")

        // TODO
    }

    @JavascriptInterface
    @SuppressLint("unused")
    fun create(
        promiseUuid: String,
        options: String,
    ) {
        val mappedOptions = JSONObject(options)
        Timber.i("$JAVASCRIPT_BRIDGE_NAME.create($promiseUuid, ${mappedOptions.toString(2)}) called.")

        credentialsContainerByOption(mappedOptions).create(
            options = mappedOptions,
            failureCallback = { th ->
                Timber.e(th, "Creation failed.")

                dispatcher.dispatch(EmptyCoroutineContext) {
                    webView.evaluateJavascript(
                        """
                        console.log('credential creation failed', JSON.stringify("$th"))
                        alert('Credential creation failed: ' + JSON.stringify("${th.localizedMessage}"))
                        $JAVASCRIPT_BRIDGE_NAME.__reject__("$promiseUuid", JSON.stringify("$th"));
                        """.trimIndent(),
                    ) {}
                }
            },
            successCallback = { response ->
                Timber.i("Creation succeeded with $response.")

                dispatcher.dispatch(EmptyCoroutineContext) {
                    webView.evaluateJavascript(
                        """
                        var response = JSON.parse('$response')
                        console.log('credential created', response)
                        $JAVASCRIPT_BRIDGE_NAME.__resolve__("$promiseUuid", response);
                        """.trimIndent(),
                    ) {}
                }
            },
        )
    }

    @JavascriptInterface
    @SuppressLint("unused")
    fun get(
        promiseUuid: String,
        options: String,
    ) {
        Timber.i("$JAVASCRIPT_BRIDGE_NAME.get($promiseUuid, $options) called.")

        val mappedOptions = JSONObject(options)
        val container = credentialsContainerByOption(mappedOptions)
        container.get(
            options = mappedOptions,
            failureCallback = { th ->
                Timber.e(th, "Get failed.")

                dispatcher.dispatch(EmptyCoroutineContext) {
                    webView.evaluateJavascript(
                        """
                        console.log('credential getting failed', JSON.stringify("$th"))
                        alert('Credential getting failed: ' + JSON.stringify("${th.localizedMessage}"))
                        $JAVASCRIPT_BRIDGE_NAME.__reject__("$promiseUuid", JSON.stringify("$th"));
                        """.trimIndent(),
                    ) {}
                }
            },
            successCallback = { response ->
                Timber.i("Get succeeded with $response.")

                dispatcher.dispatch(EmptyCoroutineContext) {
                    webView.evaluateJavascript(
                        """
                        var response = JSON.parse('$response')
                        console.log('credential getted', response)
                        $JAVASCRIPT_BRIDGE_NAME.__resolve__("$promiseUuid", response);
                        """.trimIndent(),
                    ) {}
                }
            },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothStatusWrapped(
        promiseUuid: String,
        unusedParameter: String,
    ) {
        resolvePromise(
            promiseUuid,
            // @formatter:off
            "Mode:   ${ServiceCharacteristic.mode.name}\\n\\n" +
                "Server: ${bleServerHandler.status()}\\n\\n" +
                "Client: ${bleClientHandler.status()}",
            // @formatter:on
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothTerminateWrapped(
        promiseUuid: String,
        unusedParameter: String,
    ) {
        bleServerHandler.disconnect()
        bleClientHandler.disconnect()

        resolvePromise(promiseUuid, "true")
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothCreateServerWrapped(
        promiseUuid: String,
        serviceUuid: String,
    ) {
        bleServerHandler.createServer(
            serviceUuid = serviceUuid,
            success = { resolvePromise(promiseUuid, "true") },
            failure = { rejectPromise(promiseUuid, "false") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothCreateClientWrapped(
        promiseUuid: String,
        serviceUuid: String,
    ) {
        bleClientHandler.createClient(
            serviceUuid = serviceUuid,
            success = { resolvePromise(promiseUuid, "true") },
            failure = { rejectPromise(promiseUuid, "false") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothSendToServerWrapped(
        promiseUuid: String,
        rawParameter: String,
    ) {
        val parameter = JSONArray(rawParameter).toByteArray()

        bleClientHandler.sendToServer(
            parameter,
            success = { resolvePromise(promiseUuid, "true") },
            failure = { rejectPromise(promiseUuid, "false") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothSendToClientWrapped(
        promiseUuid: String,
        rawParameter: String,
    ) {
        val parameter = JSONArray(rawParameter).toByteArray()

        bleServerHandler.sendToClient(
            parameter,
            success = { resolvePromise(promiseUuid, "true") },
            failure = { rejectPromise(promiseUuid, "false") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothReceiveFromClientWrapped(
        promiseUuid: String,
        unusedParameter: String,
    ) {
        bleServerHandler.receiveFromClient(
            success = { resolvePromise(promiseUuid, JSONArray(it).toString()) },
            failure = { rejectPromise(promiseUuid, "null") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothReceiveFromServerWrapped(
        promiseUuid: String,
        unusedParameter: String,
    ) {
        bleClientHandler.receiveFromServer(
            success = { resolvePromise(promiseUuid, JSONArray(it).toString()) },
            failure = { rejectPromise(promiseUuid, "false") },
        )
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothSetMode(mode: String) {
        if (mode in ServiceCharacteristic.Mode.entries.map { it.name }) {
            ServiceCharacteristic.mode = ServiceCharacteristic.Mode.valueOf(mode)
        }
    }

    @JavascriptInterface
    @Suppress("unused")
    fun bluetoothGetMode(): String = ServiceCharacteristic.mode.name

    private fun resolvePromise(
        promiseUuid: String,
        result: String,
    ) {
        dispatcher.dispatch(EmptyCoroutineContext) {
            val wrapped = JSONObject.wrap(result)
            webView.evaluateJavascript(
                "${JAVASCRIPT_BRIDGE_NAME}.__resolve__('$promiseUuid', '$wrapped')",
            ) {}
        }
    }

    private fun rejectPromise(
        promiseUuid: String,
        result: String,
    ) {
        dispatcher.dispatch(EmptyCoroutineContext) {
            val wrapped = JSONObject.wrap(result)
            webView.evaluateJavascript(
                "${JAVASCRIPT_BRIDGE_NAME}.__reject__('$promiseUuid', '$wrapped')",
            ) {}
        }
    }

    private fun getAppIconBitmap(): Bitmap {
        val drawable = webView.context.packageManager.getApplicationIcon(webView.context.packageName)
        val bitmap = createBitmap(32, 32)
        val canvas = Canvas(bitmap)

        drawable.setBounds(0, 0, 32, 32)
        drawable.draw(canvas)

        return bitmap
    }
}

private fun JSONArray.toByteArray(): ByteArray =
    (0 until length())
        .mapNotNull { index ->
            val value = get(index)
            if (value is Int) {
                value.toByte()
            } else {
                null
            }
        }.toByteArray()
