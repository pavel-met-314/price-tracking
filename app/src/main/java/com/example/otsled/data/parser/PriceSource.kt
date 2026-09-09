package com.example.otsled.data.parser

/** Откуда пришли цены. Нужен для журнала диагностики: WebView путь медленный и «сложный»,
 * поэтому важно видеть, не начал ли обычный HTTP-запрос стабильно проваливаться. */
enum class PriceSource {
    HTTP,
    WEBVIEW,
    UNKNOWN,
}
