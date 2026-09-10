package org.siros.wwwallet.bridging

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.credentials.registry.digitalcredentials.mdoc.MdocEntry
import androidx.credentials.registry.digitalcredentials.openid4vp.OpenId4VpRegistry
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtEntry
import androidx.credentials.registry.provider.RegistryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONException
import org.json.JSONObject
import org.siros.wwwallet.BuildConfig
import org.siros.wwwallet.MainViewModel
import org.siros.wwwallet.credentials.Container
import org.siros.wwwallet.json.DcApiCredential
import org.siros.wwwallet.json.toList
import org.siros.wwwallet.proximity.ProximityBridge
import org.siros.wwwallet.storage.Settings
import timber.log.Timber
import java.util.Base64
import kotlin.coroutines.EmptyCoroutineContext

class WalletJsBridge(
    private val webView: WebView,
    private val dispatcher: CoroutineDispatcher,
    private val securityKeyCredentialsContainer: Container,
    private val clientDeviceCredentialsContainer: Container,
    private val debugMenuHandler: DebugMenuHandler?,
    private val startPhotoIdMatch: () -> Unit,
    private val finishDcApiRequest: (response: String?, error: String?) -> Unit,
) {
    companion object {
        const val JAVASCRIPT_BRIDGE_NAME = "nativeWrapper"
    }

    /**
     * Calls into the page. Constructed here rather than injected because it is
     * bound to this WebView's lifetime and has no configuration.
     */
    private val scope = CoroutineScope(dispatcher)

    private val calls = JsCallHost(webView, scope)

    /**
     * ISO 18013-5 proximity, hosted by the SDK. Replaces the eight
     * `bluetooth*` methods this class used to expose, which handed raw GATT
     * to the page and left it to run the protocol.
     */
    private val proximity = ProximityBridge(webView.context, calls, scope)

    private fun credentialsContainerByOption(mappedOptions: JSONObject): Container =
        try {
            val publicKey = mappedOptions.getJSONObject("publicKey")
            // throws JSONException if not present
            val jsonHints = publicKey.getJSONArray("hints")
            val hints = jsonHints.toList().filterIsInstance<String>()

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

        // A page commit replaced the page and its handler registry, so anything
        // we were waiting on can never be answered.
        calls.invalidateAll("the page was replaced")

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
        updateAllCredentials(list, null)
    }

    @JavascriptInterface
    @Suppress("unused")
    fun updateAllCredentials(
        list: String,
        callbackUrl: String?,
    ) {
        val credentials: List<DcApiCredential>

        try {
            credentials = json.decodeFromString(list)
        } catch (t: Throwable) {
            Timber.e(t)
            return
        }

        Timber.i("Received ${credentials.size} credentials for $callbackUrl.")

        CoroutineScope(dispatcher).launch {
            val allCredentials = Settings.getDcApiCredentials().toMutableMap()

            val fbCredentials = allCredentials[MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL]?.toMutableList() ?: mutableListOf()

            // Graceful frontend upgrade: When a frontend starts to support tenancy-aware credentials
            // we need to remove these credentials from the fallback list.
            // When it doesn't support it, yet, we need to remove old versions so we can add the updated
            // ones later.
            fbCredentials.removeAll { fbc -> credentials.firstOrNull { it.id == fbc.id } != null }

            if (!callbackUrl.isNullOrBlank()) {
                allCredentials[callbackUrl] = credentials
            } else {
                // Fallback to support frontends, which aren't aware of the second argument, yet.
                // We mix all of them together here, in the hopes, that a user will only select a
                // credential later, which belongs to the currently set tenant.
                fbCredentials.addAll(credentials)
            }

            if (fbCredentials.isEmpty()) {
                allCredentials.remove(MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL)
            } else {
                allCredentials[MainViewModel.DCAPI_CREDENTIALS_FALLBACK_URL] = fbCredentials
            }

            Settings.setDcApiCredentials(allCredentials)

            val bitmap = getAppIconBitmap()

            val sdJwts = mutableListOf<SdJwtEntry>()
            val mDocs = mutableListOf<MdocEntry>()

            allCredentials.forEach { (_, credentials) ->
                credentials.forEach { it.bitmap = bitmap }
                sdJwts.addAll(credentials.mapNotNull { it.sdJwt })
                mDocs.addAll(credentials.mapNotNull { it.mDoc })
            }

            val rm = RegistryManager.create(webView.context)
            val request = OpenId4VpRegistry(sdJwts + mDocs, webView.context.packageName)

            try {
                // As per experiments, this call will automatically drop all credentials which were
                // registered before, but aren't in the list anymore.
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
        if (response.isNullOrBlank()) {
            Timber.e("Error instead of GET_CREDENTIALS response: %s", error ?: "Unknown error")

            finishDcApiRequest(null, error)

            return
        }

        Timber.i("Received GET_CREDENTIALS response: $response")

        finishDcApiRequest(response, null)
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

    // ── proximity ───────────────────────────────────────────────────────────

    /**
     * Starts an ISO 18013-5 proximity session and resolves with
     * `{ mdocUri, mode }` so the page can render its QR code. The session then
     * runs natively and reports through `proximity.step` / `proximity.complete`.
     */
    @JavascriptInterface
    @Suppress("unused")
    fun proximityStartWrapped(
        promiseUuid: String,
        params: String,
    ) {
        scope.launch {
            try {
                val engagement = proximity.start(params)
                resolvePromise(promiseUuid, base64Json(engagement))
            } catch (e: Exception) {
                Timber.e(e, "Could not start a proximity session.")
                rejectPromise(promiseUuid, base64Json(errorPayload(e)))
            }
        }
    }

    /** Tears the session down. Idempotent. */
    @JavascriptInterface
    @Suppress("unused")
    fun proximityStopWrapped(
        promiseUuid: String,
        unusedParameter: String,
    ) {
        proximity.stop()
        resolvePromise(promiseUuid, base64Json(JsonPrimitive(true)))
    }

    // ── replies to calls we made into the page ──────────────────────────────

    /** The page's answer to a `__invoke__`. See [JsCallHost]. */
    @JavascriptInterface
    @Suppress("unused", "ktlint:standard:function-naming")
    fun __reply__(
        callId: String,
        payloadB64: String,
    ) = calls.reply(callId, payloadB64)

    /** The page's refusal of a `__invoke__`. See [JsCallHost]. */
    @JavascriptInterface
    @Suppress("unused", "ktlint:standard:function-naming")
    fun __replyError__(
        callId: String,
        code: String,
        message: String,
    ) = calls.replyError(callId, code, message)

    private fun base64Json(payload: JsonElement): String =
        Base64.getEncoder().encodeToString(
            Json.encodeToString(JsonElement.serializer(), payload).toByteArray(Charsets.UTF_8),
        )

    private fun errorPayload(e: Exception): JsonElement =
        buildJsonObject {
            put("code", JsonPrimitive(e::class.simpleName ?: "error"))
            put("message", JsonPrimitive(e.message ?: "no message"))
        }

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
