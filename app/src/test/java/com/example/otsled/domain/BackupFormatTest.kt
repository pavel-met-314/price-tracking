package com.example.otsled.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат резервной копии. Копия — единственная вещь в приложении, которая обязана пережить всё
 * остальное: битый импорт опаснее, чем отсутствие импорта, поэтому здесь проверяются и «не
 * потерять», и «не выдумать» (пустая строка не равна «значения нет»), и «не съесть чужой файл».
 */
class BackupFormatTest {

    private val url = "https://allureparfum.ru/katalog/creed/aventus.html"

    private fun productRow(
        title: String = "Creed - Aventus",
        titleOverride: String? = null,
        target: Double? = 12_000.0,
    ) = BackupProductRow(
        url = url,
        title = title,
        titleOverride = titleOverride,
        targetPrice = target,
        notifyOnAnyChange = true,
        notifyOnTargetReached = false,
        isActive = true,
        archivedAt = null,
        lastPrice = 12_345.5,
        lastCheckedAt = 1_700_000_000_000,
        lastSuccessAt = 1_700_000_000_001,
    )

    private fun backup() = Backup(
        products = listOf(productRow()),
        variants = listOf(
            BackupVariantRow(
                productUrl = url,
                variantKey = "100 мл",
                volume = "100 мл",
                label = "уценка",
                article = "123, 456",
                lastPrice = 12_345.5,
                oldPrice = 15_000.0,
                isTracked = true,
            ),
        ),
        history = listOf(BackupHistoryRow(productUrl = url, variantKey = "100 мл", price = 12_345.5, checkedAt = 1_700_000_000_000)),
    )

    @Test
    fun roundTripKeepsEveryField() {
        val decoded = BackupFormat.decode(BackupFormat.encode(backup()))

        assertTrue(decoded.warnings.toString(), decoded.isValid)
        val source = backup()
        assertEquals(source.products, decoded.backup!!.products)
        assertEquals(source.variants, decoded.backup!!.variants)
        assertEquals(source.history, decoded.backup!!.history)
    }

    @Test
    fun commasQuotesAndSpacesSurvive() {
        val tricky = Backup(
            products = listOf(
                // Перенос строк внутри значения — то, на чём обычно и ломаются самописные CSV.
                productRow(title = "Creed «Aventus», 100 мл; \"новая\" версия\nвторая строка"),
                productRow(titleOverride = "\\N", title = "буквальный слэш-N как название"),
            ),
        )

        val decoded = BackupFormat.decode(BackupFormat.encode(tricky))

        assertTrue(decoded.warnings.toString(), decoded.isValid)
        assertEquals(tricky.products, decoded.backup!!.products)
        // Отличаем «название задан вручную и он равен \N» от «названия нет»: иначе кавычки в
        // файле молча превратились бы в null.
        assertEquals("\\N", decoded.backup!!.products[1].titleOverride)
    }

    @Test
    fun nullAndEmptyStringAreDifferentThings() {
        val encoded = BackupFormat.encode(
            Backup(products = listOf(productRow(title = "", titleOverride = null))),
        )

        val decoded = BackupFormat.decode(encoded).backup!!

        assertEquals("", decoded.products.single().title)
        assertNull(decoded.products.single().titleOverride)
        assertTrue(encoded.contains("\"\""))
        assertTrue(encoded.contains("\\N"))
    }

    @Test
    fun unknownTableIsSkippedNotFatal() {
        val text = BackupFormat.encode(backup()) + "#table=notes\nauthor,text\nme,\"а это поле приложение не знает\"\n"

        val decoded = BackupFormat.decode(text)

        assertTrue(decoded.isValid)
        assertEquals(1, decoded.backup!!.products.size)
        assertTrue(decoded.warnings.any { it.contains("notes") })
    }

    @Test
    fun brokenLinesAreReportedAndSkipped() {
        val text = BackupFormat.encode(backup()) +
            ",,,\n" +   // строка без ссылки: товар неопознаваем
            "#table=variants\nproductUrl,variantKey\n"  // таблица без обязательных полей

        val decoded = BackupFormat.decode(text)

        assertTrue("валидная часть обязана импортироваться", decoded.isValid)
        assertEquals(1, decoded.backup!!.products.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun filesThatAreNotBackupsAreRefused() {
        assertEquals(BackupFailure.EMPTY, BackupFormat.decode("").failure)
        assertEquals(BackupFailure.NOT_A_BACKUP, BackupFormat.decode("a,b,c\n1,2,3").failure)
        assertEquals(BackupFailure.NOT_A_BACKUP, BackupFormat.decode("otsled-backup,?\n").failure)
    }

    @Test
    fun newerFormatIsNotGuessedAt() {
        val text = "otsled-backup,${BackupFormat.VERSION + 1}\n#table=products\n"

        val decoded = BackupFormat.decode(text)

        assertEquals(BackupFailure.VERSION_TOO_NEW, decoded.failure)
        assertNotNull(decoded.details)
    }

    @Test
    fun validButEmptyFileIsReportedAsNoProducts() {
        val decoded = BackupFormat.decode("otsled-backup,${BackupFormat.VERSION}\n#table=history\n${BackupFormat.HISTORY_HEADER.joinToString(",")}\n")

        assertEquals(BackupFailure.NO_PRODUCTS, decoded.failure)
    }

    @Test
    fun crlfAndMissingFinalNewlineStillParse() {
        val unix = BackupFormat.encode(backup())

        val decoded = BackupFormat.decode(unix.replace("\n", "\r\n").trimEnd())

        assertTrue(decoded.warnings.toString(), decoded.isValid)
        assertEquals(backup().products, decoded.backup!!.products)
    }

    @Test
    fun buildLinksVariantsAndHistoryToTheProductUrl() {
        val products = listOf(
            com.example.otsled.domain.model.TrackedProduct(
                id = 7L,
                url = url,
                title = "Creed - Aventus",
                targetPrice = null,
                lastPrice = 1_000.0,
                lastCheckedAt = null,
            ),
        )
        val variants = listOf(
            com.example.otsled.domain.model.ProductVariant(
                id = 11L,
                productId = 7L,
                variantKey = "100 мл",
                volume = "100 мл",
                lastPrice = 1_000.0,
            ),
            // Вариант товара, которого в списке нет: в копию он попасть не должен.
            com.example.otsled.domain.model.ProductVariant(
                id = 12L,
                productId = 99L,
                variantKey = "50 мл",
                volume = "50 мл",
                lastPrice = 500.0,
            ),
        )
        val history = listOf(
            com.example.otsled.domain.model.PriceHistoryEntry(productId = 7L, variantId = 11L, price = 900.0, checkedAt = 5L),
            // История без варианта всё равно сохраняется: ключ остаётся пустым.
            com.example.otsled.domain.model.PriceHistoryEntry(productId = 7L, variantId = null, price = 950.0, checkedAt = 6L),
        )

        val backup = BackupFormat.build(products, variants, history)

        assertEquals(1, backup.products.size)
        assertEquals(url, backup.products.single().url)
        assertEquals(listOf("100 мл"), backup.variants.map { it.variantKey })
        assertEquals(2, backup.history.size)
        assertEquals("100 мл", backup.history.first { it.checkedAt == 5L }.variantKey)
        assertEquals("", backup.history.first { it.checkedAt == 6L }.variantKey)
        assertEquals(listOf("100 мл"), backup.variantsFor(url).map { it.variantKey })
        assertEquals(2, backup.historyFor(url).size)
    }
}
