package com.example.otsled.domain

import com.example.otsled.data.parser.ParsedProductVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Автодетект смены вёрстки. Ценность файла в обратных случаях: эвристика, которая пугает «магазин
 * всё сломал» при каждой снятой позиции, хуже, чем её отсутствие, — человек перестаёт верить
 * подписям. Поэтому подозрение объявляется только когда признак *перестал* находиться совсем.
 */
class LayoutFingerprintTest {

    private fun variant(
        volume: String = "100 мл",
        article: String? = "777",
        oldPrice: Double? = null,
    ) = ParsedProductVariant(volume = volume, article = article, price = 1_000.0, oldPrice = oldPrice)

    private fun fingerprint(
        variants: List<ParsedProductVariant>,
        title: String = "Creed - Aventus",
    ) = LayoutFingerprint.of(title = title, variants = variants)

    @Test
    fun encodeAndParseRoundTrip() {
        val source = fingerprint(listOf(variant(), variant(volume = "50 мл", oldPrice = 1_200.0)))
        val restored = LayoutFingerprint.parse(source.encode())

        assertEquals(source, restored)
    }

    @Test
    fun unparsableOrMissingSnapshotMeansNoSuspicion() {
        assertNull(LayoutFingerprint.parse(null))
        assertNull(LayoutFingerprint.parse(""))
        assertNull(LayoutFingerprint.parse("мусор вместо отпечатка"))
        assertNull(LayoutFingerprint.parse("w=3,o=1"))
        assertTrue(LayoutChangeDetection.regressions(null, fingerprint(listOf(variant()))).isEmpty())
    }

    @Test
    fun partialSnapshotStillParses() {
        val restored = LayoutFingerprint.parse("v=4,w=4")

        assertEquals(4, restored!!.variants)
        assertEquals(4, restored.withVolume)
        assertEquals(0, restored.withOldPrice)
        assertTrue(!restored.titleFound)
    }

    @Test
    fun volumesStopBeingFound() {
        val before = fingerprint(listOf(variant(), variant(volume = "50 мл")))
        val after = fingerprint(listOf(variant(volume = " "), variant(volume = " ")))

        assertEquals(listOf(LayoutRegression.VOLUMES_GONE), LayoutChangeDetection.regressions(before, after))
    }

    @Test
    fun fewerVariantsAloneIsNotALayoutProblem() {
        // Объёмы снимают с продажи, меняют линейку, убирают уценку — это про товар, а не про
        // вёрстку. Сказать человеку «магазин сломался» в такой момент значит соврать.
        val before = fingerprint(List(5) { variant(volume = "$it мл") })
        val after = fingerprint(listOf(variant()))

        assertTrue(LayoutChangeDetection.regressions(before, after).isEmpty())
    }

    @Test
    fun singleMissingOldPriceIsADiscountNotABreakage() {
        val before = fingerprint(listOf(variant(oldPrice = 1_200.0)))
        val after = fingerprint(listOf(variant()))

        assertTrue(LayoutChangeDetection.regressions(before, after).isEmpty())

        val manyBefore = fingerprint(List(3) { variant(oldPrice = 1_200.0) })
        assertEquals(
            listOf(LayoutRegression.OLD_PRICES_GONE),
            LayoutChangeDetection.regressions(manyBefore, after),
        )
    }

    @Test
    fun titleDisappearingIsReported() {
        val before = fingerprint(listOf(variant()))
        val after = fingerprint(listOf(variant()), title = "   ")

        assertEquals(listOf(LayoutRegression.TITLE_GONE), LayoutChangeDetection.regressions(before, after))
    }

    @Test
    fun noteKeyRoundTripAndCleaning() {
        val regressions = listOf(LayoutRegression.VOLUMES_GONE, LayoutRegression.TITLE_GONE)
        val key = LayoutChangeDetection.noteKey(regressions)

        assertEquals("VOLUMES_GONE,TITLE_GONE", key)
        assertEquals(regressions, LayoutChangeDetection.parseNote(key))
        // Чистый прогон обязан затирать подозрение: пустая строка — это «всё снова нормально».
        assertEquals("", LayoutChangeDetection.noteKey(emptyList()))
        assertTrue(LayoutChangeDetection.parseNote("").isEmpty())
        assertTrue(LayoutChangeDetection.parseNote("NOT_A_REAL_MARKER").isEmpty())
    }
}
