package com.example.otsled.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    /** Короткая метка для оси графика: год там только мешал бы. */
    private val shortFormat = SimpleDateFormat("dd.MM", Locale("ru"))

    fun format(timestamp: Long?): String {
        if (timestamp == null) return "—"
        return format.format(Date(timestamp))
    }

    fun formatShort(timestamp: Long?): String {
        if (timestamp == null) return "—"
        return shortFormat.format(Date(timestamp))
    }
}
