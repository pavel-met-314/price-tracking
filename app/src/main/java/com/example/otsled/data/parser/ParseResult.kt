package com.example.otsled.data.parser

sealed class ParseResult {
    data class Success(
        val title: String,
        val variants: List<ParsedProductVariant>,
    ) : ParseResult() {
        val price: Double get() = variants.minOf { it.price }
    }

    data class Error(
        val message: String,
    ) : ParseResult()
}
