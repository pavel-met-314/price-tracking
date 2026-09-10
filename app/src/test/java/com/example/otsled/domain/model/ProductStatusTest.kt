package com.example.otsled.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Статусы товара и связанные с ними решения.
 *
 * Главное, что здесь проверяется, — честность: статус «товара нет на сайте» может появиться только
 * из ответа сайта. Статусы «мы не смогли прочитать» обязаны говорить о нас, иначе пользователь
 * решит, что его аромат сняли с продажи только потому, что у нас не выдержала сеть.
 */
class ProductStatusTest {

    private fun product(
        errorCode: String = "",
        failures: Int = 0,
        lastPrice: Double? = 1000.0,
        lastCheckedAt: Long? = 1L,
        isActive: Boolean = true,
        archivedAt: Long? = null,
    ) = TrackedProduct(
        id = 1,
        url = "https://allureparfum.ru/katalog/x.html",
        title = "Kirke",
        targetPrice = null,
        lastPrice = lastPrice,
        lastCheckedAt = lastCheckedAt,
        isActive = isActive,
        lastErrorCode = errorCode,
        lastErrorMessage = if (errorCode.isEmpty()) null else "msg",
        consecutiveFailures = failures,
        archivedAt = archivedAt,
    )

    @Test
    fun pageGoneIsAboutTheProduct() {
        val subject = product(errorCode = ParseResultKind.NOT_FOUND, failures = 1)

        assertEquals(ProductStatus.NOT_FOUND, subject.status)
        assertTrue(subject.status.concernsProduct)
        assertTrue(subject.status.isProblem)
    }

    @Test
    fun missingOffersIsAboutTheProduct() {
        val subject = product(errorCode = ParseResultKind.OUT_OF_STOCK, failures = 1)

        assertEquals(ProductStatus.OUT_OF_STOCK, subject.status)
        assertTrue(subject.status.concernsProduct)
        assertTrue(subject.status.isProblem)
    }

    @Test
    fun botChallengeIsOurProblemNotProducts() {
        val subject = product(errorCode = ParseResultKind.BOT_CHALLENGE, failures = 2)

        assertEquals(ProductStatus.ACCESS_FAILED, subject.status)
        assertFalse(subject.status.concernsProduct)
        assertTrue(subject.status.isProblem)
        assertTrue(subject.isBotBlocked)
    }

    @Test
    fun networkFailureDoesNotBlameTheProduct() {
        val subject = product(errorCode = ParseResultKind.NETWORK, failures = 4)

        assertEquals(ProductStatus.ACCESS_FAILED, subject.status)
        assertFalse(subject.status.concernsProduct)
    }

    @Test
    fun parseFailureMeansLayoutChange() {
        val subject = product(errorCode = ParseResultKind.PARSE, failures = 3)

        assertEquals(ProductStatus.PARSE_FAILED, subject.status)
        assertFalse(subject.status.concernsProduct)
    }

    @Test
    fun successAfterFailuresIsOk() {
        val subject = product(errorCode = "", failures = 0)

        assertEquals(ProductStatus.OK, subject.status)
        assertFalse(subject.hasCheckProblem)
    }

    @Test
    fun neverCheckedProductHasNoData() {
        val subject = product(lastPrice = null, lastCheckedAt = null)

        assertEquals(ProductStatus.NO_DATA, subject.status)
        assertFalse(subject.status.isProblem)
    }

    @Test
    fun archiveIsNotPause() {
        val archived = product(archivedAt = 5L)
        val paused = product(isActive = false)

        assertTrue(archived.isArchived)
        assertFalse(archived.isPaused)
        assertTrue(paused.isPaused)
        assertFalse(paused.isArchived)
    }

    @Test
    fun onlyPageGoneCanAutoPause() {
        assertTrue(shouldAutoPauseOnNotFound(ParseResultKind.NOT_FOUND, 3, 3))
        // Двух раз мало: часовой 404 во время выгрузки каталога — не приговор товару.
        assertFalse(shouldAutoPauseOnNotFound(ParseResultKind.NOT_FOUND, 2, 3))
        assertFalse(shouldAutoPauseOnNotFound(ParseResultKind.OUT_OF_STOCK, 9, 3))
        assertFalse(shouldAutoPauseOnNotFound(ParseResultKind.PARSE, 9, 3))
        assertFalse(shouldAutoPauseOnNotFound(null, 9, 3))
    }
}
