package com.example.otsled.domain

import com.example.otsled.domain.model.TrackedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила ручной правки товара. Самое дорогое здесь — `pageChanged`: по нему решается, стирать ли
 * историю. Ошибка в одну сторону молча съедает данные, ошибка в другую оставляет в карточке цены
 * чужой страницы.
 */
class ProductEditingTest {

    private fun product(
        url: String = "https://allureparfum.ru/katalog/creed/aventus.html",
        title: String = "Creed - Aventus",
    ) = TrackedProduct(
        id = 1L,
        url = url,
        title = title,
        targetPrice = null,
        lastPrice = 100.0,
        lastCheckedAt = 1L,
    )

    private fun draft(
        title: String = "",
        url: String,
        target: String = "",
        anyChange: Boolean = true,
        targetReached: Boolean = true,
    ) = ProductEditDraft(
        title = title,
        url = url,
        targetPrice = target,
        notifyOnAnyChange = anyChange,
        notifyOnTargetReached = targetReached,
    )

    @Test
    fun samePageWithDifferentNoiseIsNotANewPage() {
        // Вставка из мессенджера тащит за собой «, цена 3000»: ссылка та же, и обнулять историю
        // из-за запятой — значит потерять данные у человека, который просто поправил опечатку.
        val result = ProductEditing.validate(
            draft(url = "https://allureparfum.ru/katalog/creed/aventus.html, цена 3000"),
            product(),
        )

        assertTrue(result.isValid)
        assertFalse(result.edit!!.pageChanged)
        assertEquals("https://allureparfum.ru/katalog/creed/aventus.html", result.edit.url)
    }

    @Test
    fun anotherProductPageMarksReset() {
        val result = ProductEditing.validate(draft(url = "https://allureparfum.ru/katalog/creed/silver.htm"), product())

        assertTrue(result.isValid)
        assertTrue(result.edit!!.pageChanged)
    }

    @Test
    fun blankAndForeignUrlsAreRejected() {
        val blank = ProductEditing.validate(draft(url = "   "), product())
        assertEquals(mapOf(EditField.URL to EditReason.EMPTY), blank.problems)
        assertNull(blank.edit)

        val foreign = ProductEditing.validate(draft(url = "https://other-shop.ru/katalog/x.html"), product())
        assertEquals(mapOf(EditField.URL to EditReason.NOT_A_PRODUCT_PAGE), foreign.problems)
        assertNull(foreign.edit)
    }

    @Test
    fun targetAcceptsHumanNumbers() {
        listOf("1 200" to 1_200.0, "1200,50 ₽" to 1_200.5, "1\u00A0200 руб" to 1_200.0).forEach { (raw, expected) ->
            val edit = ProductEditing.validate(draft(url = product().url, target = raw), product()).edit
            assertEquals("поле «$raw»", expected, edit!!.targetPrice!!, 0.01)
        }
    }

    @Test
    fun emptyTargetMeansNoTargetAndIsNotAnError() {
        val result = ProductEditing.validate(draft(url = product().url, target = "  "), product())

        assertTrue(result.isValid)
        assertNull(result.edit!!.targetPrice)
    }

    @Test
    fun nonsenseAndNonPositiveTargetAreProblemsNotSilence() {
        // Молча проигнорированная цель — это «цели нет», а человек думает, что она стоит.
        listOf("abc", "0", "-5").forEach { raw ->
            val result = ProductEditing.validate(draft(url = product().url, target = raw), product())

            assertEquals("поле «$raw»", mapOf(EditField.TARGET_PRICE to EditReason.NOT_POSITIVE), result.problems)
            assertNull(result.edit)
        }
    }

    @Test
    fun titleOverrideOnlyWhenItDiffersFromTheSiteTitle() {
        val same = ProductEditing.validate(draft(title = " Creed - Aventus ", url = product().url), product())
        assertNull("совпадающее название не должно замораживать разбор", same.edit!!.titleOverride)

        val custom = ProductEditing.validate(draft(title = "Анюта", url = product().url), product())
        assertEquals("Анюта", custom.edit!!.titleOverride)

        val blank = ProductEditing.validate(draft(title = "", url = product().url), product())
        assertNull(blank.edit!!.titleOverride)
    }

    @Test
    fun switchesAreStoredAsTyped() {
        val edit = ProductEditing.validate(
            draft(url = product().url, anyChange = false, targetReached = false),
            product(),
        ).edit!!

        assertFalse(edit.notifyOnAnyChange)
        assertFalse(edit.notifyOnTargetReached)
    }

    @Test
    fun parseTargetFollowsTheSameRulesAsTheEditForm() {
        assertEquals(1_200.0, ProductEditing.parseTarget("1 200")!!, 0.01)
        assertEquals(999.99, ProductEditing.parseTarget("999,99 ₽")!!, 0.01)
        assertNull(ProductEditing.parseTarget(""))
        assertNull(ProductEditing.parseTarget("ноль"))
        assertNull(ProductEditing.parseTarget("0"))
    }
}
