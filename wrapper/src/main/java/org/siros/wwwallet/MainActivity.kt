package org.siros.wwwallet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings.LOAD_NO_CACHE
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.registry.provider.selectedCredentialSet
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.siros.wwwallet.bluetooth.BleClientHandler
import org.siros.wwwallet.bluetooth.BleServerHandler
import org.siros.wwwallet.bridging.DebugMenuHandler
import org.siros.wwwallet.bridging.WalletJsBridge
import org.siros.wwwallet.credentials.AndroidContainer
import org.siros.wwwallet.credentials.YubicoContainer
import org.siros.wwwallet.facetec.FaceTecManager
import org.siros.wwwallet.facetec.FaceTecProvider
import org.siros.wwwallet.json.DcApiRequests
import org.siros.wwwallet.util.ShakeDetector
import org.siros.wwwallet.webkit.WalletWebChromeClient
import org.siros.wwwallet.webkit.WalletWebViewClient
import timber.log.Timber
import ui.EnterBaseUrlDialog
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlin.Any
import kotlin.Exception
import kotlin.OptIn
import kotlin.String
import kotlin.Unit
import kotlin.apply
import kotlin.let
import kotlin.stackTraceToString

class MainActivity : ComponentActivity() {
    val vm: MainViewModel by viewModels<MainViewModel>()

    private val photoIdMatchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val credentialOfferURI = result.data?.getStringExtra(FaceTecManager.EXTRA_CREDENTIAL_OFFER_URI)
            Timber.i("PhotoIdMatchActivity returned resultCode=${result.resultCode}, credentialOfferURI=$credentialOfferURI")
            vm.photoIdMatchCompleted(credentialOfferURI)
        }

    private val webViewClient: WebViewClient =
        WalletWebViewClient(this) { description ->
            vm.errorReceived(
                description,
            )
        }

    private val webChromeClient: WebChromeClient = WalletWebChromeClient(this)

    private lateinit var shakeDetector: ShakeDetector

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private val javascriptInterfaceCreator: (WebView) -> WalletJsBridge = { webView ->
        val bridge =
            WalletJsBridge(
                webView,
                Dispatchers.Main,
                YubicoContainer(activity = this),
                AndroidContainer(context = this),
                BleClientHandler(activity = this),
                BleServerHandler(activity = this),
                DebugMenuHandler(
                    this,
                    {
                        lifecycleScope.launch {
                            vm.setBaseUrl(it)
                            vm.browseToUrl(it)
                        }
                    },
                    { vm.updateBaseUrl() },
                    { vm.copyToClipboard(it) },
                ),
                {
                    FaceTecProvider.getManager().startPhotoIdMatch(this, photoIdMatchLauncher)
                },
                { response, error ->
                    if (response == null) {
                        finishWithException(error ?: "Unknown error")
                        return@WalletJsBridge
                    }

                    val responseJson = response.response.toString()
                    val response = GetCredentialResponse(DigitalCredential(responseJson))
                    val resultData = Intent()
                    PendingIntentHandler.setGetCredentialResponse(resultData, response)
                    setResult(RESULT_OK, resultData)

                    finish()
                },
            )

        // Avoid circular reference.
        val weakBridge = WeakReference(bridge)

        shakeDetector =
            ShakeDetector(this, {
                weakBridge.get()?.openDebugMenu()
            }, 50f)
        shakeDetector.start()

        bridge
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        vm.activity = this // 👀 (NFC)

        onBackPressedDispatcher.addCallback(
            owner = this,
        ) { vm.onBackPressed() }

        handleIntent(intent)

        vm.openedFromShortcut(intent.identifier)

        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                enableEdgeToEdge()

                val url by vm.url.collectAsState()
                val updateBaseUrl by vm.updateBaseUrl.collectAsState()

                Scaffold { paddingValues ->
                    Column(
                        modifier =
                            Modifier
                                .padding(paddingValues)
                                .fillMaxHeight(),
                    ) {
                        WebView(
                            activity = this@MainActivity,
                            webViewClient = webViewClient,
                            webChromeClient = webChromeClient,
                            javascriptInterfaceCreator = javascriptInterfaceCreator,
                            javascriptInterfaceName = WalletJsBridge.JAVASCRIPT_BRIDGE_NAME,
                            url,
                        ) { url ->
                            lifecycleScope.launch {
                                vm.browseToUrl(url)
                            }
                        }
                    }

                    updateBaseUrl?.let { reason ->
                        EnterBaseUrlDialog(
                            title = stringResource(R.string.shortcut_open_custom),
                            hint =
                                when (reason) {
                                    is MainViewModel.UpdateReason.WebpageError ->
                                        stringResource(
                                            R.string.shortcut_open_custom_by_error,
                                            reason.errorMessage,
                                        )

                                    is MainViewModel.UpdateReason.DeeplinkRequest ->
                                        stringResource(R.string.shortcut_open_custom_from_deeplink)

                                    is MainViewModel.UpdateReason.UserRequest -> stringResource(R.string.shortcut_open_custom_by_user)
                                },
                            currentBaseUrl = runBlocking { vm.getBaseUrl() },
                            onCanceled = { vm.updateBaseUrlCanceled() },
                            onUrlEntered = {
                                lifecycleScope.launch {
                                    val url = vm.setBaseUrl(it)
                                    vm.browseToUrl(url)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::shakeDetector.isInitialized) {
            shakeDetector.stop()
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == "androidx.credentials.registry.provider.action.GET_CREDENTIAL") {
            handleGetCredential(intent)
            return
        }

        when (intent.scheme) {
            // Also handle `http` links: e.g. manually entered URLs automatically
            // use the `http` scheme and didn't have a chance to upgrade, yet.
            // Upgrade will happen in MainViewModel#browseToUrl()
            "http", "https", "openid4vp", "haip", "wwwallet" -> vm.parseIntent(intent)
            null -> Unit
            else -> Timber.e("Cannot handle ${intent.scheme}.")
        }
    }

    // https://developer.android.com/identity/digital-credentials/credential-holder/credential-holder#handle-selected-credential
    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun handleGetCredential(intent: Intent) {
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)

        val selectedId =
            request
                ?.selectedCredentialSet
                ?.credentials
                ?.firstOrNull()
                ?.credentialId

        if (selectedId == null) {
            Timber.e("Could not handle DC-API GET_CREDENTIAL: No credential ID given!")
            finishWithException("No credential ID given.")
            return
        }

        val option = request.credentialOptions.first { it is GetDigitalCredentialOption } as? GetDigitalCredentialOption

        if (option == null) {
            Timber.e("Could not handle DC-API GET_CREDENTIAL: No credential options given!")
            finishWithException("No credential options given.")
            return
        }

        val json = Json { ignoreUnknownKeys = true }
        val requests: DcApiRequests

        try {
            requests = json.decodeFromString(option.requestJson)
        } catch (e: Exception) {
            Timber.e("Could not handle DC-API GET_CREDENTIAL: ${e.stackTraceToString()}")
            finishWithException(e.localizedMessage)
            return
        }

        val requestData = requests.requests.firstOrNull()?.data
        if (requestData == null) {
            Timber.e("Could not handle DC-API GET_CREDENTIAL: No credential request given!")
            finishWithException("No credential request given.")
            return
        }

        vm.enqueueDcApiRequest(selectedId, getOrigin(request), requestData)
    }

    private fun getOrigin(request: ProviderGetCredentialRequest?): String {
        val origin =
            request?.callingAppInfo?.getOrigin(
                assets.open("privileged-apps.json").bufferedReader().use { it.readText() },
            )

        if (!origin.isNullOrBlank()) return origin

        val appSigningInfo =
            request
                ?.callingAppInfo
                ?.signingInfoCompat
                ?.signingCertificateHistory
                ?.firstOrNull()
                ?.toByteArray() ?: return ""

        val md = MessageDigest.getInstance("SHA-256")

        val certHash = Base64.encodeToString(md.digest(appSigningInfo), Base64.NO_WRAP or Base64.NO_PADDING)

        return "android:apk-key-hash:$certHash"
    }

    private fun finishWithException(message: String? = null) {
        val intent = Intent()
        PendingIntentHandler.setGetCredentialException(intent, GetCredentialUnknownException(message))

        setResult(RESULT_OK, intent)
        finish()
    }
}

@Composable
fun WebView(
    activity: Activity,
    webViewClient: WebViewClient,
    webChromeClient: WebChromeClient,
    javascriptInterfaceCreator: (WebView) -> Any,
    javascriptInterfaceName: String,
    url: String,
    setUrl: (String) -> Unit,
) {
    AndroidView(
        modifier =
            Modifier.wrapContentHeight(
                align = Alignment.Top,
            ),
        factory =
            createWebViewFactory(
                activity = activity,
                webViewClient = webViewClient,
                webChromeClient = webChromeClient,
                javascriptInterfaceCreator = javascriptInterfaceCreator,
                javascriptInterfaceName = javascriptInterfaceName,
            ),
        update = { webView: WebView ->
            updateWebView(
                webView = webView,
                url = url,
                newUrlCallback = setUrl,
            )
        },
    )
}

@Composable
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "JavascriptInterface")
private fun createWebViewFactory(
    activity: Activity,
    webViewClient: WebViewClient,
    webChromeClient: WebChromeClient,
    javascriptInterfaceCreator: (WebView) -> Any,
    javascriptInterfaceName: String,
) = { _: Context ->
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    val webView =
        WebView(activity).apply {
            setNetworkAvailable(true)
        }

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        textZoom = 100
        cacheMode = LOAD_NO_CACHE
        useWideViewPort = true
        loadWithOverviewMode = true
    }

    // This is needed in order to make WebView support navigator.credentials.get/create
    // on its own. This way, we only need to intercept the calls with the `security-key` hint, not
    // any others.
    // See https://developer.android.com/identity/sign-in/credential-manager-webview
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
        WebSettingsCompat.setWebAuthenticationSupport(
            webView.settings,
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
        )

        Timber.i(
            "Web authentication support enabled: ${
                WebSettingsCompat.getWebAuthenticationSupport(webView.settings)
            }",
        )
    } else {
        Timber.e("WebView does not support passkeys.")
    }

    webView.webViewClient = webViewClient

    webView.webChromeClient = webChromeClient

    ServiceWorkerController
        .getInstance()
        .apply {
            serviceWorkerWebSettings.allowContentAccess = true
            setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? = super.shouldInterceptRequest(request)
                },
            )
        }

    webView.addJavascriptInterface(
        javascriptInterfaceCreator(webView),
        javascriptInterfaceName,
    )

    webView
}

private fun updateWebView(
    webView: WebView,
    url: String?,
    newUrlCallback: (String) -> Unit,
) {
    if (url?.isNotBlank() == true) {
        if (url == "webview://back") {
            webView.evaluateJavascript(
                """
                window.history.back()
                document.location.href
                """.trimIndent(),
            ) {
                val newUrl =
                    if (it.contains("\"")) {
                        it.split("\"")[1]
                    } else {
                        it
                    }

                Timber.i("Reached $newUrl after back.")
                newUrlCallback("")
            }
        } else {
            webView.loadUrl(url)
        }
        webView.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
    }
}
