package com.example.otsled.domain

import com.example.otsled.data.parser.ParseResult
import com.example.otsled.data.parser.ProductUrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика повторов фонового прогона. Ошибка тут дорогая: лишние ретраи жгут батарею и трафик
 * пользователя, а отсутствие ретраев оставляет цены устаревшими на часы.
 */
class CheckOutcomeTest {
    @Test
    fun retry_whenNothingSucceededAndFailureIsTransient() {
        val outcome = CheckOutcome(total = 3, succeeded = 0, retryableFailures = 3)

        assertTrue(outcome.shouldRetry)
        assertEqualsFailed(outcome, 3)
    }

    @Test
    fun noRetry_whenPartiallySucceeded() {
        // Один снятый с продажи товар не повод дёргать всю пачку заново.
        val outcome = CheckOutcome(total = 3, succeeded = 2, retryableFailures = 1)

        assertFalse(outcome.shouldRetry)
    }

    @Test
    fun noRetry_whenNothingIsRetryable() {
        val outcome = CheckOutcome(total = 2, succeeded = 0, retryableFailures = 0)

        assertFalse(outcome.shouldRetry)
    }

    @Test
    fun noRetry_onEmptyList() {
        assertFalse(CheckOutcome(total = 0, succeeded = 0, retryableFailures = 0).shouldRetry)
    }

    @Test
    fun networkAndChallengeErrorsAreRetryable_othersAreNot() {
        assertTrue(ParseResult.Error("нет сети", ParseResult.Kind.NETWORK).isRetryable)
        assertTrue(ParseResult.Error("проверка браузера", ParseResult.Kind.BOT_CHALLENGE).isRetryable)
        assertFalse(ParseResult.Error("404", ParseResult.Kind.NOT_FOUND).isRetryable)
        assertFalse(ParseResult.Error("разметка изменилась", ParseResult.Kind.PARSE).isRetryable)
        assertFalse(ParseResult.Error("нет цен", ParseResult.Kind.OUT_OF_STOCK).isRetryable)
    }

    @Test
    fun retry_whenCycleRanOutOfTimeBudget() {
        val outcome = CheckOutcome(total = 5, succeeded = 0, retryableFailures = 0, skippedByBudget = 4)

        assertTrue(outcome.shouldRetry)
        assertEquals(1, outcome.failed)
    }

    @Test
    fun urlExtractedFromSharedText() {
        val text = "Посмотри https://allureparfum.ru/katalog/creed/aventus.html, цена 3000"

        assertEquals(
            "https://allureparfum.ru/katalog/creed/aventus.html",
            ProductUrlNormalizer.extractUrl(text),
        )
        assertEquals(
            "https://allureparfum.ru/katalog/creed/aventus.html",
            ProductUrlNormalizer.normalize(text),
        )
    }

    private fun assertEqualsFailed(outcome: CheckOutcome, expected: Int) {
        assertTrue("ошибок должно быть $expected", outcome.failed == expected)
    }
}
