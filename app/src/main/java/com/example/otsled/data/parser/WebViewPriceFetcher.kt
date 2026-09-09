package com.example.otsled.data.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

data class WebPageContent(
    val html: String?,
    val variants: List<ParsedProductVariant>,
    val title: String? = null,
    /** Что именно написал сайт на заглушке: «автотест», капча или «доступ ограничен». */
    val stubText: String? = null,
    /** Страница осталась заглушкой анти-бота даже после ожидания. */
    val challenge: Boolean = false,
    /** WebView не смог загрузить страницу (нет сети, DNS, таймаут соединения). */
    val loadFailed: Boolean = false,
)

class WebViewPriceFetcher(private val context: Context) {
    /**
     * [readyWhen] меняет условие готовности страницы. По умолчанию ждём варианты цен; для
     * страницы поиска цен нет и ждать их бессмысленно — там достаточно увидеть ссылки на товары.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchContent(
        rawUrl: String,
        readyWhen: ((String?) -> Boolean)? = null,
    ): WebPageContent? {
        val url = ProductUrlNormalizer.normalize(rawUrl) ?: return null

        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context.applicationContext)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = AllureParfumPriceParser.USER_AGENT

                // Куки живут в общем CookieManager: их забирает OkHttp (WebViewCookieJar),
                // поэтому пройденная один раз проверка браузера ускоряет все последующие проверки.
                // AcceptThirdPartyCookies не трогаем: защита сайта ставит свою cookie на тот же
                // домен, а приём сторонних куки в WebView менялся в разных версиях AndroidX.
                val cookieManager = CookieManager.getInstance()
                runCatching { cookieManager.setAcceptCookie(true) }

                var finished = false
                val handler = Handler(Looper.getMainLooper())
                var bestHtml: String? = null
                var bestTitle: String? = null
                var bestVariants: List<ParsedProductVariant> = emptyList()
                var attempts = 0
                var sawChallenge = false
                var lastStubHtml: String? = null
                var loadFailed = false

                fun complete() {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    // Без flush куки остаются в памяти WebView и не достаются OkHttp-пути.
                    runCatching { cookieManager.flush() }
                    webView.destroy()
                    if (continuation.isActive) {
                        continuation.resume(
                            WebPageContent(
                                html = bestHtml,
                                variants = bestVariants,
                                title = bestTitle,
                                // «Заглушка» означает, что за всё время так и не увидели настоящую
                                // страницу. Раньше условие строилось на «нет вариантов», и страница
                                // поиска (где вариантов нет по определению) ложно помечалась
                                // блокировкой, а настоящий диагноз терялся.
                                challenge = sawChallenge && bestHtml == null,
                                loadFailed = loadFailed,
                                stubText = if (bestHtml == null) BotProtection.stubSummary(lastStubHtml) else null,
                            ),
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
                    }

                    webView.evaluateJavascript(TITLE_EXTRACT_JS) { titleJson ->
                        decodeJsString(titleJson)?.takeIf { it.isNotBlank() }?.let { title ->
                            bestTitle = title
                        }
                    }

                    webView.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})();",
                    ) { htmlResult ->
                        val html = decodeJsString(htmlResult)
                        if (!html.isNullOrBlank() && isChallengePage(html)) {
                            sawChallenge = true
                            lastStubHtml = html
                        }
                        if (!html.isNullOrBlank() && !isChallengePage(html)) {
                            bestHtml = html
                            parser.parseHtmlOrNull(html, url)?.let { parsed ->
                                if (parsed.variants.size > bestVariants.size) {
                                    bestVariants = parsed.variants
                                }
                                if (bestTitle.isNullOrBlank()) {
                                    bestTitle = parsed.title
                                }
                            }
                        }
                    }

                    val ready = if (readyWhen != null) {
                        readyWhen(bestHtml)
                    } else {
                        bestVariants.size >= 2 && !bestTitle.isNullOrBlank()
                    }

                    if (attempts >= MAX_ATTEMPTS || ready) {
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

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // Реагируем только на основную страницу: ошибки картинок/шрифтов не повод
                        // считать, что цену получить не удалось.
                        if (request?.isForMainFrame == true) {
                            loadFailed = true
                            complete()
                        }
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

    private val parser = AllureParfumPriceParser()

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

    private fun isChallengePage(html: String): Boolean = BotProtection.isChallengeHtml(html)

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

        private val TITLE_EXTRACT_JS = """
            (function() {
              var og = document.querySelector('meta[property="og:title"]');
              if (og && og.content) return og.content.trim();
              var h1 = document.querySelector('h1');
              if (h1 && h1.innerText) return h1.innerText.trim();
              var name = document.querySelector('[itemprop="name"]');
              if (name && name.innerText) return name.innerText.trim();
              if (document.title) return document.title.split(' - ')[0].trim();
              return '';
            })();
        """.trimIndent()

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
              function stripPerMl(text) {
                return text
                  .replace(/за\s*1?\s*мл[^0-9]{0,12}\d[\d\s\u00a0]{0,10}\s*(руб|₽)/gi, ' ')
                  .replace(/\d[\d\s.,\u00a0]{0,10}\s*(₽|руб\.?)\s*\/\s*мл/gi, ' ');
              }
              function isNoisePrice(cleaned, index) {
                var before = cleaned.slice(Math.max(0, index - 40), index).toLowerCase();
                return /(доставк|самовывоз|оплат|бонус|балл|подарок|промоко|купон|рассрочк|отзыв|похож|рекоменду)/.test(before);
              }
              function isInsidePromoBlock(node) {
                try {
                  return !!node.closest('[class*="similar"], [class*="recommend"], [class*="related"], [class*="review"], [class*="comment"], [class*="footer"], [id*="similar"], [id*="recommend"]');
                } catch (e) {
                  return false;
                }
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
                var prices = [];
                var re = /\d[\d\s\u00a0]{0,10}\s*руб\.?/gi;
                var m;
                while ((m = re.exec(cleaned)) !== null) {
                  // Цена доставки в той же строке не должна становиться ценой товара.
                  if (isNoisePrice(cleaned, m.index)) continue;
                  var value = parseRubPrice(m[0]);
                  if (value) prices.push(value);
                }
                return prices;
              }
              function extractLabel(text) {
                if (/уценка/i.test(text)) return 'уценка';
                return '';
              }
              var results = [];
              var seen = {};
              var selectors = 'tbody > tr, tr, [class*="offer"], [class*="trade"], [class*="sku"]';
              document.querySelectorAll(selectors).forEach(function(node) {
                if (isInsidePromoBlock(node)) return;
                var text = stripPerMl((node.innerText || '').replace(/\s+/g, ' ').trim());
                if (text.length < 8 || text.length > 900) return;
                if (!/\d+\s*мл/i.test(text)) return;
                if (!/руб/i.test(text)) return;
                var volumeMatch = text.match(/\d+\s*мл\.?/i);
                if (!volumeMatch) return;
                var volume = normalizeVolume(volumeMatch[0]);
                if (!volume) return;
                var prices = extractPrices(node, text);
                if (!prices.length) return;
                // Актуальная цена — наименьшая: зачёркнутая старая всегда больше, а порядок
                // цифр в разметке предсказывать нельзя.
                var price = Math.min.apply(null, prices);
                var biggest = Math.max.apply(null, prices);
                var oldPrice = biggest > price ? biggest : null;
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
