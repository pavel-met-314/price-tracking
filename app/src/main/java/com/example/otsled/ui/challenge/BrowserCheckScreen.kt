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
 * Экран «пройти проверку браузера руками». Фоновый WebView создаётся без окна: автотест сайта
 * он проходит, капчу или кнопку «я не робот» — нет. Единственный честный способ пустить человека —
 * показать страницу ему. Куки при этом общие ([CookieManager]), поэтому пройденная здесь проверка
 * возвращает к жизни и поиск, и фоновые проверки цен.
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
        // 40 секунд наблюдения: проверка сайта разворачивается сама, обычно хватает 3–5 секунд.
        var ticks = 0
        while (ticks < POLL_TIMES) {
            val probe = view.probe()
            probe.summary?.let(viewModel::onPageText)
            if (probe.passed) {
                viewModel.markPassed()
                break
            }
            delay(POLL_DELAY_MS)
            ticks++
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
                color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Что реально отдаёт страница — тот самый текст, который просят прислать при
            // «на телефоне пусто». Выделяется долгим тапом, чтобы его можно было скопировать.
            SelectionContainer {
                Text(
                    text = pageSummary ?: stringResource(R.string.browser_check_waiting_page),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
        // Значение читаем здесь, а не в onDispose: эффект с ключом «null» вызывается и тогда,
        // когда WebView только что создали, — чтение состояния в onDispose уничтожало живой
        // WebView, и экран оставался белым.
        val view = webView
        onDispose {
            if (view != null) {
                // Без flush куки могут остаться в памяти WebView и не достаться OkHttp-пути.
                runCatching { CookieManager.getInstance().flush() }
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
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
                // Отладка через chrome://inspect: иначе «что сайт отдал на самом деле» можно только
                // угадывать по логам.
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
 * Один опрос состояния страницы. Возвращается плоская строка, а не JSON: её формат задаёт не сайт,
 * и возиться с экранированием чужого текста незачем — небезопасные символы вырезаются в JS.
 */
private suspend fun WebView.probe(): PageProbe = suspendCancellableCoroutine { continuation ->
    // Корутина снимается вместе с экраном, а колбэк WebView приходит позже: resume по снятому
    // продолжению уронил бы приложение, поэтому каждое возобновление под проверкой isActive.
    fun reply(probe: PageProbe) {
        if (continuation.isActive) continuation.resume(probe)
    }

    val callback: (String?) -> Unit = { raw ->
        val parts = raw.orEmpty().trim('"').split('|')
        if (parts.size < 5) {
            reply(PageProbe(passed = false, summary = null))
        } else {
            val state = parts[0]
            val textLength = parts[1].toIntOrNull() ?: 0
            val links = parts[2].toIntOrNull() ?: 0
            val challenged = parts[3] == "1"
            val head = parts[4]
            // Каталог открыт: документ загружен, проверка не мешает, и либо есть ссылки на товары,
            // либо текста достаточно много, чтобы это была настоящая страница.
            val done = state == "complete" && !challenged && (links > 0 || textLength > 6_000)
            val summary = "состояние: $state, текста: $textLength, ссылок на товары: $links" +
                if (head.isNotBlank()) ", начало страницы: «$head»" else ""
            reply(PageProbe(passed = done, summary = summary))
        }
    }
    // Вызов может не удаться, если WebView уже уничтожен: иначе корутина осталась бы висеть.
    runCatching { evaluateJavascript(PROBE_JS, callback) }
        .onFailure { reply(PageProbe(passed = false, summary = null)) }
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
