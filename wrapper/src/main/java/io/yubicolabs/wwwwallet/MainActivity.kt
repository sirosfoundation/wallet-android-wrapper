package io.yubicolabs.wwwwallet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
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
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import ch.qos.logback.classic.android.BasicLogcatConfigurator
import io.yubicolabs.wwwwallet.MainViewModel.UpdateReason.DeeplinkRequest
import io.yubicolabs.wwwwallet.MainViewModel.UpdateReason.UserRequest
import io.yubicolabs.wwwwallet.MainViewModel.UpdateReason.WebpageError
import io.yubicolabs.wwwwallet.bluetooth.BleClientHandler
import io.yubicolabs.wwwwallet.bluetooth.BleServerHandler
import io.yubicolabs.wwwwallet.bridging.DebugMenuHandler
import io.yubicolabs.wwwwallet.bridging.WalletJsBridge
import io.yubicolabs.wwwwallet.bridging.WalletJsBridge.Companion.JAVASCRIPT_BRIDGE_NAME
import io.yubicolabs.wwwwallet.credentials.AndroidContainer
import io.yubicolabs.wwwwallet.credentials.YubicoContainer
import io.yubicolabs.wwwwallet.logging.YOLOLogger
import io.yubicolabs.wwwwallet.webkit.WalletWebChromeClient
import io.yubicolabs.wwwwallet.webkit.WalletWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ui.EnterBaseUrlDialog

class MainActivity : ComponentActivity() {
    init {
        BasicLogcatConfigurator.configureDefaultContext()
    }

    val vm: MainViewModel by viewModels<MainViewModel>()

    private val webViewClient: WebViewClient =
        WalletWebViewClient(this) { description ->
            vm.errorReceived(
                description,
            )
        }

    private val webChromeClient: WebChromeClient = WalletWebChromeClient(this)

    private val javascriptInterfaceCreator: (WebView) -> WalletJsBridge = { webView ->
        WalletJsBridge(
            webView,
            Dispatchers.Main,
            YubicoContainer(activity = this),
            AndroidContainer(context = this),
            BleClientHandler(activity = this),
            BleServerHandler(activity = this),
            if (BuildConfig.DEBUG) {
                DebugMenuHandler(
                    context = this,
                    browseTo = {
                        lifecycleScope.launch {
                            vm.setBaseUrl(it)
                            vm.browseToUrl(it)
                        }
                    },
                    updateBaseUrl = { vm.updateBaseUrl() },
                    copyToClipboard = { vm.copyToClipboard(it) },
                )
            } else {
                null
            },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        vm.activity = this // 👀 (NFC)

        onBackPressedDispatcher.addCallback(
            owner = this,
        ) { vm.onBackPressed() }

        when (intent.scheme) {
            "https", "openid4vp", "haip", "wwwallet" -> vm.parseIntent(intent)
            null -> Unit
            else -> YOLOLogger.e(tagForLog, "Cannot handle ${intent.scheme}.")
        }

        vm.openedFromShortcut(intent.identifier)

        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                enableEdgeToEdge()

                val url by vm.url.collectAsState()
                val hosts by vm.hosts.collectAsState()
                val updateBaseUrl by vm.updateBaseUrl.collectAsState()

                MainView(url, hosts, listOf(vm.topBgColor, vm.buttonBgColor, vm.bottomBgColor),{
                    lifecycleScope.launch {
                        val url = vm.setBaseUrl(it)
                        vm.browseToUrl(url)
                    }
                }) {
                    WebView(
                        activity = this@MainActivity,
                        webViewClient = webViewClient,
                        webChromeClient = webChromeClient,
                        javascriptInterfaceCreator = javascriptInterfaceCreator,
                        javascriptInterfaceName = JAVASCRIPT_BRIDGE_NAME,
                        url,
                    ) { url ->
                        lifecycleScope.launch {
                            vm.browseToUrl(url)
                        }
                    }

                    updateBaseUrl?.let { reason ->
                        EnterBaseUrlDialog(
                            title = stringResource(R.string.shortcut_open_custom),
                            hint =
                                when (reason) {
                                    is WebpageError -> stringResource(R.string.shortcut_open_custom_by_error, reason.errorMessage)
                                    is DeeplinkRequest -> stringResource(R.string.shortcut_open_custom_from_deeplink)
                                    is UserRequest -> stringResource(R.string.shortcut_open_custom_by_user)
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
}

@Composable
fun MainView(url: String, hosts: List<String>,
             colors: List<Color>,
             onHostChange: (String) -> Unit,
             content: @Composable () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .background(Color.Black)
                    .fillMaxHeight()
        ) {
            Row(Modifier
                .background(colors.first(), AbsoluteRoundedCornerShape(
                    CornerSize(0), CornerSize(0),
                    CornerSize(20), CornerSize(20)))
                .padding(bottom = 8.dp, end = 8.dp, top = paddingValues.calculateTopPadding())
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    null,
                    Modifier.size(56.dp)
                )

                Column(Modifier.padding(top = 8.dp)) {
                    Text("Current Wallet", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(url.toUri().host ?: url, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(Modifier.weight(1f))

                HostsDropdownMenu(hosts, onHostChange, colors[1])
            }

            Box(Modifier.weight(1f)) {
                content()
            }

            Box(Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(colors.last()))
        }
    }
}

@Preview
@Composable
fun MainViewPreview() {
    MaterialTheme(
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        MainView(
            "https://demo.wwwallet.org/",
            listOf("demo.wwwallet.org", "foo.example.org", "bar.example.org"),
            listOf(Color(red = 0, green = 52, blue = 149),
                Color(red = 0, green = 52, blue = 118),
                Color(red = 17, green = 24, blue = 39)),
            {}) {
            Box(Modifier
                .fillMaxSize()
                .background(Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                Text("This is the web view.")
            }
        }
    }
}

@Composable
fun HostsDropdownMenu(hosts: List<String>, onChange: (String) -> Unit, color: Color) {
    var expanded by remember {
        mutableStateOf(false)
    }

    Box(Modifier.padding(top = 8.dp)) {
        Button(
            { expanded = !expanded },
            colors = ButtonColors(
                color,
                Color.White,
                ButtonDefaults.buttonColors().disabledContainerColor,
                ButtonDefaults.buttonColors().disabledContentColor
            )
        ) {
            Icon(
                painterResource(R.drawable.baseline_refresh_24), null)
            Text("Switch Wallet")
        }

        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (host in hosts) {
                DropdownMenuItem({ Text(host) }, onClick = {
                    onChange(host)
                    expanded = false
                })
            }
        }
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

    webView.webViewClient = webViewClient

    webView.webChromeClient = webChromeClient

    ServiceWorkerController
        .getInstance().apply {
            serviceWorkerWebSettings.allowContentAccess = true
            setServiceWorkerClient(
                object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
                        return super.shouldInterceptRequest(request)
                    }
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

                YOLOLogger.i(webView.tagForLog, "Reached $newUrl after back.")
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
