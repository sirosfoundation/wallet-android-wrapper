package org.siros.wwwallet.webkit

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import org.siros.wwwallet.MainActivity
import org.siros.wwwallet.bridging.WalletJsBridge
import java.net.URI

class WalletWebViewClient(
    private val activity: MainActivity,
    private val onErrorReceived: (description: String) -> Unit,
) : WebViewClientCompat() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val baseUrl = URI(activity.vm.url.value)

        // Open all foreign web pages and app schemes like "eid" for the AusweisApp
        // externally. Only wwWallet code is allowed inside the app.
        if (request.url.scheme != baseUrl.scheme || request.url.host != baseUrl.host) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, request.url))
            return true
        }

        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageCommitVisible(
        view: WebView,
        url: String,
    ) {
        super.onPageCommitVisible(view, url)

        view.evaluateJavascript("${WalletJsBridge.JAVASCRIPT_BRIDGE_NAME}.inject()") {}
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        super.onReceivedError(view, request, error)

        // This is called for every failed sub-resource request (icons, fonts, analytics
        // pings, …), not just the main document. Only a main-frame failure means the
        // wallet itself is actually unreachable — anything else is noise that shouldn't
        // kick the user back to the "enter base URL" dialog while the page is fine.
        if (!request.isForMainFrame) {
            return
        }

        val description =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
                error.description.toString()
            } else {
                "Web Page Error"
            }

        onErrorReceived(description)
    }
}
