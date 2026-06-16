package com.example.otsled.data.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebViewPriceFetcher(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(url: String): String? = suspendCancellableCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context.applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = AllureParfumPriceParser.USER_AGENT

            var finished = false
            fun complete(html: String?) {
                if (finished) return
                finished = true
                webView.destroy()
                if (continuation.isActive) {
                    continuation.resume(html)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (loadedUrl == null || !loadedUrl.contains("allureparfum.ru")) return
                    view?.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})();",
                    ) { result ->
                        val html = result
                            ?.trim('"')
                            ?.replace("\\n", "\n")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\u003C", "<")
                        if (html.isNullOrBlank() || html.contains("js-challenge-script")) {
                            complete(null)
                        } else {
                            complete(html)
                        }
                    }
                }
            }

            continuation.invokeOnCancellation {
                webView.destroy()
            }

            webView.loadUrl(url)
        }
    }
}
