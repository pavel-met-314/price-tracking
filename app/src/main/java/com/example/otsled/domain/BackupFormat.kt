package com.example.otsled.domain

import com.example.otsled.domain.model.PriceHistoryEntry
import com.example.otsled.domain.model.ProductVariant
import com.example.otsled.domain.model.TrackedProduct

/**
 * Резервная копия в CSV с секциями.
 *
 * CSV, а не JSON, по двум причинам: файл открывается в таблице и правится руками, а свой
 * JSON-парсер — это ещё один способ разойтись с самим собой. Вложенности нет: таблицы связаны
 * ссылками на товар по URL, поэтому копия остаётся плоской и читается человеком.
 *
 * Пустая строка и «значения нет» — разные вещи: `null` пишется как `\N`, пустая строка — как `""`.
 * Иначе товар с пустым названием и товар без названия стали бы одним и тем же.
 */
object BackupFormat {

    const val VERSION = 1
    const val MAGIC = "otsled-backup"
    const val TABLE_PRODUCTS = "products"
    const val TABLE_VARIANTS = "variants"
    const val TABLE_HISTORY = "history"

    private const val NULL_SENTINEL = "\\N"

    val PRODUCT_HEADER = listOf(
        "url", "title", "titleOverride", "targetPrice", "notifyOnAnyChange", "notifyOnTargetReached",
        "isActive", "archivedAt", "lastPrice", "lastCheckedAt", "lastSuccessAt",
    )
    val VARIANT_HEADER = listOf(
        "productUrl", "variantKey", "volume", "label", "article", "lastPrice", "oldPrice", "isTracked",
    )
    val HISTORY_HEADER = listOf("productUrl", "variantKey", "price", "checkedAt")

    fun encode(backup: Backup): String = buildString {
        append(MAGIC).append(',').append(VERSION).append('\n')

        append(section(TABLE_PRODUCTS, PRODUCT_HEADER))
        backup.products.forEach { row ->
            append(
                cells(
                    row.url, row.title, row.titleOverride, number(row.targetPrice),
                    bool(row.notifyOnAnyChange), bool(row.notifyOnTargetReached), bool(row.isActive),
                    number(row.archivedAt), number(row.lastPrice), number(row.lastCheckedAt),
                    number(row.lastSuccessAt),
                ),
            )
        }

        append(section(TABLE_VARIANTS, VARIANT_HEADER))
        backup.variants.forEach { row ->
            append(
                cells(
                    row.productUrl, row.variantKey, row.volume, row.label, row.article,
                    number(row.lastPrice), number(row.oldPrice), bool(row.isTracked),
                ),
            )
        }

        append(section(TABLE_HISTORY, HISTORY_HEADER))
        backup.history.forEach { row ->
            append(cells(row.productUrl, row.variantKey, number(row.price), number(row.checkedAt)))
        }
    }

    fun decode(text: String): BackupDecodeResult {
        val records = parseRecords(text)
        if (records.isEmpty()) return BackupDecodeResult.failure(BackupFailure.EMPTY, null)

        val header = records.first().cells.firstOrNull()?.text?.trim().orEmpty()
        if (header != MAGIC) return BackupDecodeResult.failure(BackupFailure.NOT_A_BACKUP, null)

        val version = records.first().cells.getOrNull(1)?.text?.trim()?.toIntOrNull()
            ?: return BackupDecodeResult.failure(BackupFailure.NOT_A_BACKUP, "не читается версия файла")
        if (version > VERSION) {
            return BackupDecodeResult.failure(
                BackupFailure.VERSION_TOO_NEW,
                "в файле версия $version, а приложение умеет $VERSION",
            )
        }

        val products = mutableListOf<BackupProductRow>()
        val variants = mutableListOf<BackupVariantRow>()
        val history = mutableListOf<BackupHistoryRow>()
        val warnings = mutableListOf<String>()
        var table: String? = null
        var index = 1

        while (index < records.size) {
            val record = records[index]
            val first = record.cells.firstOrNull()?.text?.trim().orEmpty()

            if (first.startsWith("#table=")) {
                table = first.removePrefix("#table=").trim()
                index++
                val expected = expectedHeader(table)
                if (expected == null) {
                    warnings += "таблица «$table» пропущена: приложение такой не знает"
                    while (index < records.size && !isTableLine(records[index])) index++
                    continue
                }
                // Первая строка после #table= — имена колонок. Сверяем, но не верим им слепо:
                // строки читаются по позициям, и расхождение надо показать, а не молча съесть.
                records.getOrNull(index)?.let { header ->
                    requireHeader(header.cells.map { cell -> cell.text.trim() }, expected, record.startLine, warnings)
                    index++
                }
                continue
            }

            when (table) {
                TABLE_PRODUCTS -> productRow(record.cells, record.startLine, products, warnings)
                TABLE_VARIANTS -> variantRow(record.cells, record.startLine, variants, warnings)
                TABLE_HISTORY -> historyRow(record.cells, record.startLine, history, warnings)
                else -> warnings += "строка ${record.startLine} пропущена: данные вне таблицы"
            }
            index++
        }

        if (products.isEmpty()) {
            return BackupDecodeResult.failure(BackupFailure.NO_PRODUCTS, warnings.joinToString("; "))
        }
        return BackupDecodeResult(
            backup = Backup(products = products, variants = variants, history = history),
            failure = null,
            details = null,
            warnings = warnings,
        )
    }

    /**
     * Снимок того, что лежит в базе. Варианты и история привязываются к товару по URL, а не по id:
     * в новом телефоне id будут другие, и сопоставлять нужно по тому, что человек видит глазами.
     */
    fun build(
        products: List<TrackedProduct>,
        variants: List<ProductVariant>,
        history: List<PriceHistoryEntry>,
    ): Backup {
        val urlByProductId = products.associate { it.id to it.url }
        val variantKeyById = variants.associate { it.id to it.variantKey }

        return Backup(
            products = products.map { product ->
                BackupProductRow(
                    url = product.url,
                    title = product.title,
                    titleOverride = product.titleOverride,
                    targetPrice = product.targetPrice,
                    notifyOnAnyChange = product.notifyOnAnyChange,
                    notifyOnTargetReached = product.notifyOnTargetReached,
                    isActive = product.isActive,
                    archivedAt = product.archivedAt,
                    lastPrice = product.lastPrice,
                    lastCheckedAt = product.lastCheckedAt,
                    lastSuccessAt = product.lastSuccessAt,
                )
            },
            variants = variants.mapNotNull { variant ->
                val url = urlByProductId[variant.productId] ?: return@mapNotNull null
                BackupVariantRow(
                    productUrl = url,
                    variantKey = variant.variantKey,
                    volume = variant.volume,
                    label = variant.label,
                    article = variant.article,
                    lastPrice = variant.lastPrice,
                    oldPrice = variant.oldPrice,
                    isTracked = variant.isTracked,
                )
            },
            history = history.mapNotNull { entry ->
                val url = urlByProductId[entry.productId] ?: return@mapNotNull null
                BackupHistoryRow(
                    productUrl = url,
                    // Вариант мог исчезнуть раньше, чем успела уехать история: ключ оставляем пустым,
                    // строка всё равно найдёт своё место по товару и дате.
                    variantKey = entry.variantId?.let { variantKeyById[it] }.orEmpty(),
                    price = entry.price,
                    checkedAt = entry.checkedAt,
                )
            },
        )
    }

    // ---------- разбор и сборка CSV ----------

    private fun expectedHeader(table: String?): List<String>? = when (table) {
        TABLE_PRODUCTS -> PRODUCT_HEADER
        TABLE_VARIANTS -> VARIANT_HEADER
        TABLE_HISTORY -> HISTORY_HEADER
        else -> null
    }

    private fun isTableLine(record: Record): Boolean =
        record.cells.firstOrNull()?.text?.trim().orEmpty().startsWith("#table=")

    private fun parseRecords(text: String): List<Record> {
        val records = mutableListOf<Record>()
        var cells = mutableListOf<Cell>()
        val builder = StringBuilder()
        var quoted = false
        var wasQuoted = false
        var line = 1
        var startLine = 1
        var i = 0

        fun endCell() {
            cells += Cell(builder.toString(), wasQuoted)
            builder.setLength(0)
            wasQuoted = false
        }

        fun endRecord() {
            endCell()
            if (cells.size > 1 || cells.any { it.text.isNotEmpty() || it.quoted }) {
                records += Record(startLine, cells.toList())
            }
            cells = mutableListOf()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    builder.append('"')
                    i += 2
                }

                ch == '"' -> {
                    quoted = !quoted
                    if (quoted) wasQuoted = true
                    i++
                }

                !quoted && ch == ',' -> {
                    endCell()
                    i++
                }

                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRecord()
                    line++
                    startLine = line
                    i++
                }

                else -> {
                    builder.append(ch)
                    i++
                }
            }
        }
        if (builder.isNotEmpty() || cells.isNotEmpty()) endRecord()

        return records
    }

    private fun section(name: String, header: List<String>): String =
        "#table=$name\n" + header.joinToString(",") + "\n"

    private fun cells(vararg values: String?): String =
        values.joinToString(",", postfix = "\n") { escape(it) }

    private fun escape(value: String?): String {
        if (value == null) return NULL_SENTINEL
        val needsQuotes = value.isEmpty() ||
            value == NULL_SENTINEL ||
            value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.first().isWhitespace() ||
            value.last().isWhitespace()
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun bool(value: Boolean): String = if (value) "1" else "0"

    private fun number(value: Double?): String = value?.toString() ?: NULL_SENTINEL

    private fun number(value: Long?): String = value?.toString() ?: NULL_SENTINEL

    private fun List<Cell>.text(index: Int): String? = getOrNull(index)?.takeIf { !it.isNullSentinel }?.text

    private fun List<Cell>.raw(index: Int): String = getOrNull(index)?.text.orEmpty()

    private fun List<Cell>.d(index: Int): Double? = text(index)?.trim()?.toDoubleOrNull()

    private fun List<Cell>.l(index: Int): Long? = text(index)?.trim()?.toLongOrNull()

    private fun List<Cell>.b(index: Int, default: Boolean): Boolean = when (text(index)?.trim()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> default
    }

    private fun requireHeader(header: List<String>?, expected: List<String>, line: Int, warnings: MutableList<String>) {
        if (header == null) return
        if (header != expected) {
            warnings += "строка $line: порядок колонок не ожидаемый — строки читаются по позициям"
        }
    }

    private fun productRow(cells: List<Cell>, line: Int, into: MutableList<BackupProductRow>, warnings: MutableList<String>) {
        val url = cells.raw(0).trim()
        if (url.isEmpty()) {
            warnings += "строка $line пропущена: у товара нет ссылки"
            return
        }
        into += BackupProductRow(
            url = url,
            title = cells.raw(1),
            titleOverride = cells.text(2),
            targetPrice = cells.d(3),
            notifyOnAnyChange = cells.b(4, true),
            notifyOnTargetReached = cells.b(5, true),
            isActive = cells.b(6, true),
            archivedAt = cells.l(7),
            lastPrice = cells.d(8),
            lastCheckedAt = cells.l(9),
            lastSuccessAt = cells.l(10),
        )
    }

    private fun variantRow(cells: List<Cell>, line: Int, into: MutableList<BackupVariantRow>, warnings: MutableList<String>) {
        val url = cells.raw(0).trim()
        val key = cells.raw(1).trim()
        val price = cells.d(5)
        when {
            url.isEmpty() || key.isEmpty() -> warnings += "строка $line пропущена: у объёма нет ссылки или ключа"
            price == null -> warnings += "строка $line пропущена: у объёма нет цены"
            else -> into += BackupVariantRow(
                productUrl = url,
                variantKey = key,
                volume = cells.raw(2),
                label = cells.raw(3),
                article = cells.text(4),
                lastPrice = price,
                oldPrice = cells.d(6),
                isTracked = cells.b(7, true),
            )
        }
    }

    private fun historyRow(cells: List<Cell>, line: Int, into: MutableList<BackupHistoryRow>, warnings: MutableList<String>) {
        val url = cells.raw(0).trim()
        val price = cells.d(2)
        val checkedAt = cells.l(3)
        when {
            url.isEmpty() || price == null || checkedAt == null ->
                warnings += "строка $line пропущена: истории не на что опираться (нет ссылки, цены или даты)"

            else -> into += BackupHistoryRow(
                productUrl = url,
                variantKey = cells.raw(1).trim(),
                price = price,
                checkedAt = checkedAt,
            )
        }
    }

    private class Record(val startLine: Int, val cells: List<Cell>)

    /** Поле вместе с признаком «было в кавычках» — только так пустая строка отличается от null. */
    private class Cell(val text: String, val quoted: Boolean) {
        val isNullSentinel: Boolean get() = !quoted && text == NULL_SENTINEL
    }
}

data class BackupProductRow(
    val url: String,
    val title: String,
    val titleOverride: String?,
    val targetPrice: Double?,
    val notifyOnAnyChange: Boolean,
    val notifyOnTargetReached: Boolean,
    val isActive: Boolean,
    val archivedAt: Long?,
    val lastPrice: Double?,
    val lastCheckedAt: Long?,
    val lastSuccessAt: Long?,
)

data class BackupVariantRow(
    val productUrl: String,
    val variantKey: String,
    val volume: String,
    val label: String,
    val article: String?,
    val lastPrice: Double,
    val oldPrice: Double?,
    val isTracked: Boolean,
)

data class BackupHistoryRow(
    val productUrl: String,
    val variantKey: String,
    val price: Double,
    val checkedAt: Long,
)

data class Backup(
    val products: List<BackupProductRow> = emptyList(),
    val variants: List<BackupVariantRow> = emptyList(),
    val history: List<BackupHistoryRow> = emptyList(),
) {
    val isEmpty: Boolean get() = products.isEmpty()

    fun variantsFor(url: String): List<BackupVariantRow> = variants.filter { it.productUrl == url }

    fun historyFor(url: String): List<BackupHistoryRow> = history.filter { it.productUrl == url }
}

enum class BackupFailure { EMPTY, NOT_A_BACKUP, VERSION_TOO_NEW, NO_PRODUCTS }

data class BackupDecodeResult(
    val backup: Backup?,
    val failure: BackupFailure?,
    val details: String?,
    val warnings: List<String>,
) {
    val isValid: Boolean get() = failure == null && backup != null

    companion object {
        fun failure(kind: BackupFailure, details: String?) = BackupDecodeResult(null, kind, details, emptyList())
    }
}
