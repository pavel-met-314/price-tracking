package com.example.otsled.data.parser

sealed class ParseResult {
    data class Success(
        val title: String,
        val price: Double,
    ) : ParseResult()

    data class Error(
        val message: String,
    ) : ParseResult()
}
