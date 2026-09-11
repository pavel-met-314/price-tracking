package com.example.otsled.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор товаров для виджета «что подешевело». На домашнем экране нет ни подписей, ни возможности
 * открыть журнал, поэтому единственная защита от вранья — правила, вынесенные сюда: показывать
 * только реальные падения, сортировать по силе падения, не врать с обрезкой названия.
 */
class DroppedWidgetTest {

    private fun candidate(
        id: Long,
        title: String = "Товар $id",
        price: Double? = 1_000.0,
        drop: VariantDrop? = VariantDrop(variantId = 1L, label = "100 мл", percent = -10.0, delta = -100.0, peakPrice = 1_100.0),
    ) = DroppedWidget.Candidate(productId = id, title = title, price = price, drop = drop)

    @Test
    fun onlyRealFallsAreShownAndSortedByStrength() {
        val model = DroppedWidget.build(
            listOf(
                candidate(1, drop = null),
                candidate(2, drop = VariantDrop(1L, "100 мл", 5.0, 50.0, 1_000.0)),
                candidate(3, drop = VariantDrop(1L, "100 мл", -2.0, -20.0, 1_000.0)),
                candidate(4, drop = VariantDrop(1L, "100 мл", -30.0, -300.0, 1_000.0)),
                candidate(5, price = null),
            ),
        )

        assertEquals(listOf(4L, 3L), model.rows.map { it.productId })
        assertEquals(0, model.hiddenCount)
    }

    @Test
    fun moreThanFitsIsCountedNotHidden() {
        val model = DroppedWidget.build(List(6) { candidate((it + 1).toLong()) }, maxRows = 4)

        assertEquals(4, model.rows.size)
        assertEquals("остальные не должны исчезать из виду молча", 2, model.hiddenCount)
    }

    @Test
    fun emptyListShowsPlaceholder() {
        val model = DroppedWidget.build(emptyList())

        assertTrue(model.isEmpty)
        assertEquals(0, model.hiddenCount)
    }

    @Test
    fun zeroMaxRowsIsNotCrash() {
        val model = DroppedWidget.build(List(3) { candidate((it + 1).toLong()) }, maxRows = 0)

        assertTrue(model.rows.isEmpty())
        assertEquals(3, model.hiddenCount)
    }

    @Test
    fun longTitlesAreShortenedNotWrapped() {
        val long = "Marc-Antoine Barrois - Ganymede Extrait, тестер, 100 мл, уценка"

        val shortened = DroppedWidget.shorten(long)

        assertTrue(shortened.length <= DroppedWidget.MAX_TITLE_LENGTH)
        assertTrue(shortened, shortened.endsWith("…"))
        assertEquals("Короткое", DroppedWidget.shorten("  Короткое  "))
    }

    @Test
    fun rowKeepsThePeakItFellFrom() {
        val model = DroppedWidget.build(listOf(candidate(1L)))

        assertEquals(1_100.0, model.rows.single().peakPrice, 0.01)
        assertEquals("100 мл", model.rows.single().volumeLabel)
    }
}
