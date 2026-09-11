package com.example.otsled.domain

import com.example.otsled.R
import com.example.otsled.data.parser.ParsedProductVariant

/**
 * Что именно перестало находиться на странице. Каждый пункт — про признак, который раньше
 * находился *всегда*: разовое «не нашли старую цену» говорит о том, что скидки больше нет, а не о
 * вёрстке, поэтому правила ниже намеренно консервативные.
 */
enum class LayoutRegression {
    VOLUMES_GONE,
    OLD_PRICES_GONE,
    ARTICLES_GONE,
    TITLE_GONE,
}

/**
 * Снимок того, *какие части* разметки мы сумели прочитать. Не хеш HTML: страница меняется в
 * мелочах (даты доставки, рекомендации), и хеш кричал бы каждый день. Здесь — только то, что
 * ломает смысл: объёмы, старая цена, артикул, название.
 */
data class LayoutFingerprint(
    val variants: Int,
    val withVolume: Int,
    val withOldPrice: Int,
    val withArticle: Int,
    val titleFound: Boolean,
) {
    fun encode(): String = buildString {
        append("v=").append(variants)
        append(",w=").append(withVolume)
        append(",o=").append(withOldPrice)
        append(",a=").append(withArticle)
        append(",t=").append(if (titleFound) 1 else 0)
    }

    companion object {
        fun of(title: String, variants: List<ParsedProductVariant>): LayoutFingerprint = LayoutFingerprint(
            variants = variants.size,
            withVolume = variants.count { it.volume.isNotBlank() },
            withOldPrice = variants.count { it.oldPrice != null },
            withArticle = variants.count { !it.article.isNullOrBlank() },
            titleFound = title.isNotBlank(),
        )

        /** null — сохранённого отпечатка нет или он испорчен: молчим, а не подозреваем вёрстку. */
        fun parse(raw: String?): LayoutFingerprint? {
            if (raw.isNullOrBlank()) return null
            val values = raw.split(',').mapNotNull { part ->
                val key = part.substringBefore('=').trim()
                part.substringAfter('=', "").trim().toIntOrNull()?.let { key to it }
            }.toMap()
            val variants = values["v"] ?: return null
            return LayoutFingerprint(
                variants = variants,
                withVolume = values["w"] ?: 0,
                withOldPrice = values["o"] ?: 0,
                withArticle = values["a"] ?: 0,
                titleFound = (values["t"] ?: 0) == 1,
            )
        }
    }
}

object LayoutChangeDetection {

    /**
     * Чем текущий снимок хуже сохранённого. Пустой список — значит вёрстка ведёт себя как обычно,
     * и заметку надо стереть (важно: иначе подозрение висело бы вечно после одного сбоя).
     */
    fun regressions(previous: LayoutFingerprint?, current: LayoutFingerprint): List<LayoutRegression> {
        if (previous == null) return emptyList()
        val found = mutableListOf<LayoutRegression>()
        // Цены находятся (иначе до сюда дело не дошло), а объёмов нет — значит их перестали
        // находить, а не «все сняли с продажи».
        if (previous.withVolume > 0 && current.withVolume == 0) found += LayoutRegression.VOLUMES_GONE
        if (previous.withOldPrice >= MIN_CONFIRMED_PARTS && current.withOldPrice == 0) {
            found += LayoutRegression.OLD_PRICES_GONE
        }
        if (previous.withArticle >= MIN_CONFIRMED_PARTS && current.withArticle == 0) {
            found += LayoutRegression.ARTICLES_GONE
        }
        if (previous.titleFound && !current.titleFound) found += LayoutRegression.TITLE_GONE
        return found
    }

    /** Ключи подозрений для БД: текст рисует UI, а не домен. */
    fun noteKey(regressions: List<LayoutRegression>): String = regressions.joinToString(",") { it.name }

    fun parseNote(raw: String?): List<LayoutRegression> = raw?.split(',')
        ?.mapNotNull { name -> LayoutRegression.entries.firstOrNull { it.name == name.trim() } }
        ?: emptyList()

    /**
     * Сколько совпадений нужно, чтобы заподозрить вёрстку по «старой цене»/артикулу. Один вариант
     * без старой цены — это отменённая скидка, а не поломка.
     */
    private const val MIN_CONFIRMED_PARTS = 2
}

/** Короткое имя пропавшей части — для подписи в списке и в карточке. */
fun LayoutRegression.partRes(): Int = when (this) {
    LayoutRegression.VOLUMES_GONE -> R.string.layout_part_volumes
    LayoutRegression.OLD_PRICES_GONE -> R.string.layout_part_old_price
    LayoutRegression.ARTICLES_GONE -> R.string.layout_part_articles
    LayoutRegression.TITLE_GONE -> R.string.layout_part_title
}

/** Формулировка для журнала проверок: с цифрами, чтобы по одному скрину было видно, что изменилось. */
fun LayoutRegression.messageRes(): Int = when (this) {
    LayoutRegression.VOLUMES_GONE -> R.string.layout_note_volumes
    LayoutRegression.OLD_PRICES_GONE -> R.string.layout_note_old_prices
    LayoutRegression.ARTICLES_GONE -> R.string.layout_note_articles
    LayoutRegression.TITLE_GONE -> R.string.layout_note_title
}
