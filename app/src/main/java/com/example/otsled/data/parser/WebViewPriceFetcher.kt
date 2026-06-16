package com.example.otsled.data.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

data class WebPageContent(
    val html: String?,
    val variants: List<ParsedProductVariant>,
)

class WebViewPriceFetcher(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchContent(rawUrl: String): WebPageContent? {
        val url = ProductUrlNormalizer.normalize(rawUrl) ?: return null

        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context.applicationContext)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = AllureParfumPriceParser.USER_AGENT

                var finished = false
                val handler = Handler(Looper.getMainLooper())
                var bestHtml: String? = null
                var bestVariants: List<ParsedProductVariant> = emptyList()
                var attempts = 0

                fun complete() {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    webView.destroy()
                    if (continuation.isActive) {
                        continuation.resume(
                            WebPageContent(html = bestHtml, variants = bestVariants),
                        )
                    }
                }

                fun tryExtract() {
                    if (finished) return
                    attempts++

                    webView.evaluateJavascript(VARIANTS_EXTRACT_JS) { variantsJson ->
                        val variants = parseVariantsJson(variantsJson)
                        if (variants.size > bestVariants.size) {
                            bestVariants = variants
                        }
                        if (bestVariants.size >= 2) {
                            complete()
                        }
                    }

                    webView.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})();",
                    ) { htmlResult ->
                        val html = decodeJsString(htmlResult)
                        if (!html.isNullOrBlank() && !isChallengePage(html)) {
                            bestHtml = html
                            val parsed = AllureParfumPriceParser().parseHtml(html, url)
                            if (parsed is ParseResult.Success && parsed.variants.size > bestVariants.size) {
                                bestVariants = parsed.variants
                            }
                            if (bestVariants.size >= 2) {
                                complete()
                            }
                        }
                    }

                    if (attempts >= MAX_ATTEMPTS) {
                        complete()
                    } else {
                        handler.postDelayed({ tryExtract() }, RETRY_DELAY_MS)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl == null || !loadedUrl.contains("allureparfum.ru")) return
                        handler.postDelayed({ tryExtract() }, 500)
                    }
                }

                continuation.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    webView.destroy()
                }

                handler.postDelayed({ if (!finished) complete() }, TIMEOUT_MS)
                webView.loadUrl(url)
            }
        }
    }

    suspend fun fetchHtml(rawUrl: String): String? = fetchContent(rawUrl)?.html

    private fun parseVariantsJson(json: String?): List<ParsedProductVariant> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        return runCatching {
            val array = JSONArray(decodeJsString(json) ?: return emptyList())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val volume = item.optString("volume")
                    val price = item.optDouble("price", Double.NaN)
                    if (volume.isBlank() || price.isNaN()) continue
                    add(
                        ParsedProductVariant(
                            volume = volume,
                            label = item.optString("label", ""),
                            article = item.optString("article").takeIf { it.isNotBlank() },
                            price = price,
                            oldPrice = item.optDouble("oldPrice", Double.NaN).takeUnless { it.isNaN() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun isChallengePage(html: String): Boolean {
        return html.contains("js-challenge-script") ||
            html.contains("jsch._jsChallenge") ||
            html.contains("Ваш браузер не смог пройти")
    }

    private fun decodeJsString(result: String?): String? {
        if (result.isNullOrBlank() || result == "null") return null
        return result
            .trim('"')
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\/", "/")
    }

    companion object {
        private const val TIMEOUT_MS = 35_000L
        private const val RETRY_DELAY_MS = 1_500L
        private const val MAX_ATTEMPTS = 8

        private val VARIANTS_EXTRACT_JS = """
            (function() {
              function normalizeVolume(text) {
                var match = text.match(/(\d+)\s*мл/i);
                return match ? match[1] + ' мл' : null;
              }
              function stripNoise(text) {
                return text
                  .replace(/Артикул\s*\d+/gi, ' ')
                  .replace(/\d+\s*мл\.?/gi, ' ');
              }
              function parseRubPrice(text) {
                var match = text.match(/(\d[\d\s\u00a0]{0,10})\s*руб/i);
                if (!match) return null;
                var value = parseFloat(match[1].replace(/[\s\u00a0]/g, ''));
                if (!value || value > 500000) return null;
                return value;
              }
              function extractPrices(node, text) {
                var selectors = '.product-item-detail-price-current, .price_value, [class*="price-current"], [data-entity="price"]';
                var fromNodes = Array.prototype.map.call(node.querySelectorAll(selectors), function(el) {
                  return parseRubPrice(el.innerText || '');
                }).filter(Boolean);
                if (fromNodes.length) return fromNodes;
                var cleaned = stripNoise(text);
                var matches = cleaned.match(/\d[\d\s\u00a0]{0,10}\s*руб\.?/gi) || [];
                return matches.map(parseRubPrice).filter(Boolean);
              }
              function extractLabel(text) {
                if (/уценка/i.test(text)) return 'уценка';
                return '';
              }
              var results = [];
              var seen = {};
              var selectors = 'tbody > tr, tr, [class*="offer"], [class*="trade"], [class*="sku"]';
              document.querySelectorAll(selectors).forEach(function(node) {
                var text = (node.innerText || '').replace(/\s+/g, ' ').trim();
                if (text.length < 8 || text.length > 900) return;
                if (!/\d+\s*мл/i.test(text)) return;
                if (!/руб/i.test(text)) return;
                var volumeMatch = text.match(/\d+\s*мл\.?/i);
                if (!volumeMatch) return;
                var volume = normalizeVolume(volumeMatch[0]);
                if (!volume) return;
                var prices = extractPrices(node, text);
                if (!prices.length) return;
                var price = prices[0];
                var oldPrice = prices.length > 1 ? prices[1] : null;
                var articleMatch = text.match(/Артикул\s*(\d+)/i);
                var article = articleMatch ? articleMatch[1] : null;
                var label = extractLabel(text);
                var key = article || (volume + '|' + label);
                if (seen[key]) return;
                seen[key] = true;
                results.push({
                  volume: volume,
                  label: label,
                  article: article || '',
                  price: price,
                  oldPrice: oldPrice || null
                });
              });
              results.sort(function(a, b) {
                return parseInt(a.volume, 10) - parseInt(b.volume, 10);
              });
              return JSON.stringify(results);
            })();
        """.trimIndent()
    }
}
