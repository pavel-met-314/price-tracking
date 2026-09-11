package com.example.otsled.domain

import com.example.otsled.data.parser.PriceNormalizer
import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.domain.model.TrackedProduct

/**
 * Ручная правка отслеживаемого товара. Правила вынесены из UI и из БД, потому что именно здесь
 * легче всего соврать: «пользователь поправил ссылку» означает, что сохранённые цена и история
 * относятся уже к другой странице, и держать их как ни в чём не бывало — тот же класс вранья,
 * что и разные объёмы на одном графике.
 */
data class ProductEditDraft(
    val title: String,
    val url: String,
    val targetPrice: String,
    val notifyOnAnyChange: Boolean,
    val notifyOnTargetReached: Boolean,
)

/** Готовые значения для записи. [pageChanged] — ключевое: по нему решается, что обнулять. */
data class ProductEdit(
    val url: String,
    val titleOverride: String?,
    val targetPrice: Double?,
    val notifyOnAnyChange: Boolean,
    val notifyOnTargetReached: Boolean,
    val pageChanged: Boolean,
)

enum class EditField { URL, TARGET_PRICE }

enum class EditReason { EMPTY, NOT_A_PRODUCT_PAGE, NOT_POSITIVE }

/** Результат проверки формы: проблемы списком, а не исключением — экран должен показать, что именно не так. */
data class ProductEditResult(
    val problems: Map<EditField, EditReason>,
    val edit: ProductEdit?,
) {
    val isValid: Boolean get() = problems.isEmpty()
}

object ProductEditing {

    /**
     * Цена цели вводится по-человечески: «1 200», «1200,50 ₽», «1 200 руб» — то же правило, что и
     * в парсере страницы. Простой `toDoubleOrNull()` терял пробел и запятую, и цель молча
     * не сохранялась.
     */
    fun parseTarget(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Минус разбирается парсером страницы как диапазон «300-400» и превратился бы в «400»:
        // для цели это не опечатка, а бессмыслица, поэтому отказываем явно.
        if (trimmed.startsWith("-") || trimmed.startsWith("+")) return null
        return PriceNormalizer.normalize(trimmed)?.takeIf { it > 0.0 }
    }

    fun validate(draft: ProductEditDraft, current: TrackedProduct): ProductEditResult {
        val problems = mutableMapOf<EditField, EditReason>()

        val trimmedUrl = draft.url.trim()
        val normalized = if (trimmedUrl.isEmpty()) null else ProductUrlNormalizer.normalize(trimmedUrl)
        when {
            trimmedUrl.isEmpty() -> problems[EditField.URL] = EditReason.EMPTY
            // normalize() достраивает адрес до allureparfum.ru из чего угодно, поэтому «чужая
            // ссылка» проверяется отдельно: иначе вставка из другого магазина превратилась бы в
            // несуществующую страницу на этом.
            !ProductUrlNormalizer.isSupportedUrl(trimmedUrl) -> problems[EditField.URL] = EditReason.NOT_A_PRODUCT_PAGE
        }

        val targetText = draft.targetPrice.trim()
        val parsedTarget = parseTarget(targetText)
        if (targetText.isNotEmpty() && parsedTarget == null) {
            // «abc» и «12ф» молча превращались бы в «цели нет»: пользователь думал бы, что цель
            // на месте, а уведомление о ней не приходило бы.
            problems[EditField.TARGET_PRICE] = EditReason.NOT_POSITIVE
        }

        if (problems.isNotEmpty()) return ProductEditResult(problems, null)

        // Ссылки сравниваются уже нормализованными: «/a/b.html?» и «/a/b.html» — одна страница,
        // и история ради такого обнулялась бы неправильно. Нормализация query не вырезает, поэтому
        // ссылка с utm-параметрами считается другой страницей: молча слить её с текущей опаснее,
        // чем попросить человека почистить адрес.
        val pageChanged = normalized != ProductUrlNormalizer.normalize(current.url)

        return ProductEditResult(
            problems = emptyMap(),
            edit = ProductEdit(
                url = normalized.orEmpty(),
                // Ручное название имеет смысл, только если оно отличается от прочитанного со
                // страницы: иначе «сохранить как есть» навсегда заморозило бы заголовок.
                titleOverride = draft.title.trim().takeIf { it.isNotEmpty() && it != current.title },
                targetPrice = parsedTarget,
                notifyOnAnyChange = draft.notifyOnAnyChange,
                notifyOnTargetReached = draft.notifyOnTargetReached,
                pageChanged = pageChanged,
            ),
        )
    }
}
