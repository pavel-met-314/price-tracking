package com.example.otsled.ui.challenge

import androidx.lifecycle.ViewModel
import com.example.otsled.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние «проверку браузера нужно пройти руками».
 *
 * Фоновый WebView создаётся без окна и без пользователя: автотест сайта он проходит, а капчу или
 * «подтвердите, что вы не робот» — нет. Единственный честный способ это починить — показать
 * страницу человеку. Куки при этом общие ([android.webkit.CookieManager]), поэтому пройденная тут
 * проверка сразу возвращает к жизни и поиск, и фоновые проверки цен.
 */
class BrowserCheckViewModel(
    container: AppContainer,
) : ViewModel() {
    private val sessionStore = container.parseSessionStore

    private val _passed = MutableStateFlow(false)
    val passed = _passed.asStateFlow()

    /** Что сайт пишет на странице — это стоит показать и скопировать: по одной строке понятно,
     * автотест это, капча или блок по IP. */
    private val _pageSummary = MutableStateFlow<String?>(null)
    val pageSummary = _pageSummary.asStateFlow()

    fun onPageText(text: String?) {
        _pageSummary.value = text
    }

    /** Сайт пустил на настоящую страницу: кулдаун снимается, быстрый HTTP снова основной. */
    fun markPassed() {
        sessionStore.markSuccess()
        _passed.value = true
    }

    /** Ручной сброс: например, когда защита сменилась и старые договорённости уже недействительны. */
    fun resetSession() {
        sessionStore.clear()
        _passed.value = false
    }

    companion object {
        /** Стартуем с главной: проверка ставит cookie на домен, а не на конкретный товар. */
        const val START_URL = "https://allureparfum.ru/"
    }
}
