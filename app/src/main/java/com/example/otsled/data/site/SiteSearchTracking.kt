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
 * слэш в конце и разный регистрhost'а должны узнаваться как один и тот же товар.
 */
object SiteSearchTracking {

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

    /** id отслеживаемого товара для строки выдачи, или null — если такой товар ещё не добавлен. */
    fun trackedId(hit: SiteSearchHit, tracked: Map<String, Long>): Long? = tracked[canonical(hit.url)]

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
