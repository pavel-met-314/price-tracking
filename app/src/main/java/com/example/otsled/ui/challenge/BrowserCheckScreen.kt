package com.example.otsled.ui.challenge

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.otsled.R
import com.example.otsled.data.parser.AllureParfumPriceParser
import com.example.otsled.ui.AppViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Экран «пройти проверку браузера руками». Единственное место, где анти-бот видит обычный
 * интерактивный WebView: автотест проходит сам, капчу — только человек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserCheckScreen(
    viewModelFactory: AppViewModelFactory,
    onDone: () -> Unit,
) {
    val viewModel: BrowserCheckViewModel = viewModel(factory = viewModelFactory)
    val passed by viewModel.passed.collectAsStateWithLifecycle()
    val pageSummary by viewModel.pageSummary.collectAsStateWithLifecycle()

    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        // 40 секунд наблюдения: проверка сайта разворачивается сама, и обычно хватает 3–5 секунд.
        repeat(POLL_TIMES) {
            val probe = view.probe()
            viewModel.onPageText(probe.summary)
            if (probe.passed) {
                viewModel.markPassed()
                return@repeat
            }
            delay(POLL_DELAY_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_check_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browser_check_done))
                    }
                    OutlinedButton(
                        onClick = viewModel::resetSession,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.browser_check_reset))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_check_instruction),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(
                    if (passed) R.string.browser_check_status_passed else R.string.browser_check_status_waiting,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (passed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 6.dp),
            )
            pageSummary?.let { summary ->
                // Тот самый текст, который просят прислать: чем страница отличается от каталога.
                SelectionContainer {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            BrowserWebView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 8.dp),
                onReady = { view -> webView = view },
            )
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.let { view ->
                // Без flush куки могут остаться в памяти WebView и не достаться OkHttp-пути.
                runCatching { CookieManager.getInstance().flush() }
                runCatching { view.destroy() }
            }
            webView = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(modifier: Modifier = Modifier, onReady: (WebView) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = AllureParfumPriceParser.USER_AGENT
                // Отладка через chrome://inspect: иначе «что сайт отдал на самом деле» можно
                // только угадывать по логам.
                if (isDebuggable(context)) {
                    runCatching { WebView.setWebContentsDebuggingEnabled(true) }
                }
                runCatching { CookieManager.getInstance().setAcceptCookie(true) }
                // Пустой WebViewClient обязателен: без него WebView отдаёт переходы системному
                // браузеру, и редирект после проверки улетел бы из приложения.
                webViewClient = WebViewClient()
                loadUrl(BrowserCheckViewModel.START_URL)
                onReady(this)
            }
        },
    )
}

private class PageProbe(val passed: Boolean, val summary: String?)

/**
 * Один опрос состояния страницы. Возвращается плоская строка, а не JSON: её формат задаёт не
 * сайт, и возиться с экранированием кавычек из чужого текста незачем — небезопасные символы
 * вырезаются ещё в JavaScript.
 */
private suspend fun WebView.probe(): PageProbe = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(PROBE_JS) { raw ->
        val parts = raw.orEmpty().trim('"').split('|')
        if (parts.size < 5) {
            continuation.resume(PageProbe(passed = false, summary = null))
            return@evaluateJavascript
        }
        val state = parts[0]
        val textLength = parts[1].toIntOrNull() ?: 0
        val links = parts[2].toIntOrNull() ?: 0
        val challenged = parts[3] == "1"
        val head = parts[4]
        // Каталог открыт: страница дополнена, проверка браузера не мешает, и либо есть ссылки
        // на товары, либо текста достаточно много, чтобы это была настоящая страница.
        val passed = state == "complete" && !challenged && (links > 0 || textLength > 6_000)
        val summary = "состояние: $state, текста: $textLength, ссылок на товары: $links" +
            if (head.isNotBlank()) ", начало страницы: «$head»" else ""
        continuation.resume(PageProbe(passed = passed, summary = summary))
    }
    continuation.invokeOnCancellation { }
}

private fun isDebuggable(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private const val POLL_TIMES = 40
private const val POLL_DELAY_MS = 1_000L

private val PROBE_JS = """
    (function () {
      var body = document.body;
      var t = body && body.innerText ? body.innerText : '';
      var head = t.slice(0, 300).replace(/\s+/g, ' ').replace(/[^A-Za-zА-Яа-яЁё0-9 .,:%()\-]/g, '');
      var challenged = /проверк|верификац|just a moment|checking your browser|captcha|доступ ограничен|access denied/i.test(head);
      var links = document.querySelectorAll('a[href*="/katalog/"]').length;
      return document.readyState + '|' + t.length + '|' + links + '|' + (challenged ? 1 : 0) + '|' + head;
    })();
""".trimIndent()
