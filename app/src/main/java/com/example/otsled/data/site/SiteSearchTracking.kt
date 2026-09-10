package com.example.otsled.data.site

import com.example.otsled.data.parser.ProductUrlNormalizer
import com.example.otsled.domain.model.TrackedProduct

/**
 * Помечает строки выдачи поиска тем, что уже отслеживается.
 *
 * Без этой пометки поиск выглядит как «пустой» список: тот же товар находится второй раз,
 * добавляется дублем (у Room ключ — url, и REPLACE тихо перезаписал бы настройки цели и
 * уведомлений), а пользователь не понимает, что товар уже на месте. Логика вынесена из UI,
 * потому что сравнение URL — то место, где обычно и ошибаются: `?PAGEN_1=2`, `#reviews`,
 * слэш в конце и разный регистр хоста должны узнаваться как один и тот же товар.
 */
object SiteSearchTracking {
    private const val MAX_URL_IN_REPORT = 96

    /** Ключ карты — канонический URL товара, значение — его id в базе. */
    fun trackedIdsByUrl(products: List<TrackedProduct>): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        products.forEach { product ->
            val key = canonical(product.url)
            if (key.isNotBlank() && !result.containsKey(key)) {
                result[key] = product.id
            }
        }
        return result
    }

    /**
     * id архивных товаров из списка: выдача должна отличать «уже отслеживается» от «уже отслеживается,
     * но убран в архив». Второе — не отказ добавлять, а подсказка «верни, если передумал».
     */
    fun archivedIds(products: List<TrackedProduct>): Set<Long> =
        products.filter { it.isArchived }.map { it.id }.toSet()

    /** id отслеживаемого товара для строки выдачи, или null — если такой товар ещё не добавлен. */
    fun trackedId(hit: SiteSearchHit, tracked: Map<String, Long>): Long? = tracked[canonical(hit.url)]

    /**
     * Ключи — URL строк выдачи (те же строки, что пришли в `SiteSearchHit`), значения — id
     * отслеживаемого товара. Отдаём готовую карту, чтобы экран не пересчитывал нормализацию URL
     * на каждой перерисовке списка.
     */
    fun resolve(hits: List<SiteSearchHit>, tracked: Map<String, Long>): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        hits.forEach { hit ->
            trackedId(hit, tracked)?.let { id -> result[hit.url] = id }
        }
        return result
    }

    /**
     * Диагностическая строка о сопоставлении: её видно под выдачей и она попадает в «Журнал
     * проверок». Без неё «пометка не появилась» неотличимо от «такого товара нет в списке», а
     * разница между ними — только в том, сошлись URL или нет; вот эти URL и показываем.
     */
    fun report(hits: List<SiteSearchHit>, tracked: Map<String, Long>, archivedCount: Int = 0): String {
        if (hits.isEmpty()) return ""
        val matched = hits.count { trackedId(it, tracked) != null }
        val archiveNote = if (archivedCount > 0) ", в архиве $archivedCount" else ""
        val head = "совпало со списком $matched из ${hits.size} (в списке ${tracked.size}$archiveNote)"
        if (matched > 0 || tracked.isEmpty()) return head
        return "$head; в выдаче ${cut(canonical(hits.first().url))}, в базе ${cut(tracked.keys.first())}"
    }

    private fun cut(value: String): String =
        if (value.length <= MAX_URL_IN_REPORT) value else value.take(MAX_URL_IN_REPORT) + "…"

    /**
     * Вид URL, по которому одна и та же страница товара выглядит одинаково: `normalize` делает
     * адрес абсолютным, а `?PAGEN_1=2`, `#reviews` и хвостовой слэш убираются здесь — строка
     * выдачи приходит без них, а введённая пользователем ссылка может быть и с ними.
     */
    private fun canonical(url: String): String =
        (ProductUrlNormalizer.normalize(url.trim()) ?: url.trim())
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
}
