package com.example.otsled.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    fun format(timestamp: Long?): String {
        if (timestamp == null) return "—"
        return format.format(Date(timestamp))
    }
}
