package com.example.otsled.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ритм «обновить всё». Считаем не для красоты: по этим числам человек решает, ждать ли ему, а
 * приложение — сколько товаров оставить на плановую проверку. Молча проверить половину списка и
 * сказать, что проверило всё, — самый простой способ потерять доверие.
 */
class ManualCheckPacingTest {

    private fun plan(
        total: Int,
        done: Int = 0,
        elapsed: Long = 0,
        avg: Long? = null,
        delay: Long = 1_000,
        budget: Long = 10_000,
    ) = ManualCheckPacing.plan(
        total = total,
        alreadyDone = done,
        elapsedMillis = elapsed,
        avgCheckMillis = avg,
        delayMillis = delay,
        budgetMillis = budget,
    )

    @Test
    fun shortListFitsEntirely() {
        val result = plan(total = 3, avg = 1_000, budget = 60_000)

        assertEquals(3, result.fits)
        assertEquals(0, result.deferred)
        assertTrue(result.everythingFits)
    }

    @Test
    fun budgetIsSpentOnChecksAndOnPauses() {
        // Товар = 2 секунды проверки + секунда паузы. На 10 секунд бюджета влезает ровно трое.
        val result = plan(total = 10, avg = 2_000, delay = 1_000, budget = 10_000)

        assertEquals(3, result.fits)
        assertEquals(7, result.deferred)
    }

    @Test
    fun exhaustedBudgetDefersEverythingLeft() {
        val result = plan(total = 10, done = 4, elapsed = 60_000, avg = 2_000, budget = 60_000)

        assertEquals(0, result.fits)
        assertEquals(6, result.deferred)
        assertFalse(result.everythingFits)
    }

    @Test
    fun nothingLeftMeansNothingToPlan() {
        val result = plan(total = 5, done = 5)

        assertEquals(0, result.fits)
        assertEquals(0, result.deferred)
        assertEquals(0, result.estimatedMillis)
    }

    @Test
    fun estimateUsesFirstGuessBeforeTheFirstCheck() {
        val result = plan(total = 4, budget = 10_000_000)

        // До первой проверки среднего нет — берём консервативную оценку, чтобы не пообещать «секунду».
        assertEquals((ManualCheckPacing.FIRST_CHECK_ESTIMATE_MILLIS + 1_000) * 4, result.estimatedMillis)
    }

    @Test
    fun zeroDelayDoesNotDivideByZero() {
        val result = plan(total = 3, avg = 0, delay = 0, budget = 1_000)

        assertEquals(3, result.fits)
    }

    @Test
    fun delayBetweenChecksIsPublic() {
        // Пауза между запросами — суть троттлинга; если её кто-то обнулит, тест должен зашуметь.
        assertTrue(ManualCheckPacing.DELAY_BETWEEN_CHECKS_MILLIS > 0)
    }
}

class ManualCheckStateTest {

    @Test
    fun beginStartsOnceAndFinishFreesIt() {
        ManualCheckState.finish()

        assertTrue(ManualCheckState.begin(5))
        assertTrue(ManualCheckState.isRunning)
        assertFalse("повторный тап не должен начинать второй прогон", ManualCheckState.begin(5))
        assertEquals(5, ManualCheckState.progress.value?.total)

        ManualCheckState.advance(ManualCheckProgress(done = 2, total = 5, deferred = 1))
        assertEquals(2, ManualCheckState.progress.value?.done)

        ManualCheckState.finish()
        assertFalse(ManualCheckState.isRunning)
    }

    @Test
    fun advanceIsIgnoredWhileNothingRuns() {
        ManualCheckState.finish()

        ManualCheckState.advance(ManualCheckProgress(done = 1, total = 3, deferred = 0))

        assertEquals(null, ManualCheckState.progress.value)
    }

    @Test
    fun progressFractionStaysSane() {
        assertEquals(0f, ManualCheckProgress(done = 0, total = 0, deferred = 0).fraction, 0.0001f)
        assertEquals(0.5f, ManualCheckProgress(done = 1, total = 2, deferred = 0).fraction, 0.0001f)
        assertTrue(ManualCheckProgress(done = 2, total = 2, deferred = 0).everythingChecked)
        assertFalse(ManualCheckProgress(done = 2, total = 5, deferred = 3).everythingChecked)
    }
}
