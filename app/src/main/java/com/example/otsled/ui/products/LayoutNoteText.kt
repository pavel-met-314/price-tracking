package com.example.otsled.ui.products

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.otsled.R
import com.example.otsled.domain.LayoutChangeDetection
import com.example.otsled.domain.partRes

/**
 * Подпись подозрения на смену вёрстки — общая для списка и карточки, чтобы в двух местах не
 * расходились формулировки.
 *
 * Список частей собирается циклом, а не `joinToString { stringResource(...) }`: composable-вызов
 * внутри лямбды с nullable-параметром компилятор запрещает, и молча «не туда» он бы не повёл —
 * просто не собралось бы.
 */
@Composable
fun layoutSuspicionText(note: String): String {
    val parts = mutableListOf<String>()
    for (regression in LayoutChangeDetection.parseNote(note)) {
        parts += stringResource(regression.partRes())
    }
    return stringResource(R.string.problem_layout_suspicion, parts.joinToString(", "))
}
