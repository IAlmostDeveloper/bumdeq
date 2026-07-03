package ru.ialmostdeveloper.bumdeq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.ialmostdeveloper.bumdeq.data.AllOutSettings
import java.util.Locale

/**
 * Содержимое вкладки «Настройки». Пока — единственная настройка: коэффициент all-out
 * ([AllOutSettings.maxCfToPeakPercent]), порог CF/PF·100% для вердикта «это был all-out».
 * Значение персистится в SharedPreferences и подхватывается экраном результата Critical Force.
 *
 * Рисуется без собственного Scaffold — внутри [contentPadding] общего Scaffold MainScreen.
 */
@Composable
fun SettingsContent(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { AllOutSettings(context) }

    // Черновик ввода и последнее сохранённое значение держим отдельно: поле можно править,
    // не трогая сохранённое, а по «Сохранить» синхронизируем и показываем подтверждение.
    var saved by rememberSaveable { mutableStateOf(settings.maxCfToPeakPercent) }
    var text by rememberSaveable { mutableStateOf(formatCoefficient(settings.maxCfToPeakPercent)) }
    var showSavedHint by rememberSaveable { mutableStateOf(false) }

    val parsed = text.trim().replace(',', '.').toFloatOrNull()
    val isValid = parsed != null && parsed in 1f..100f

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
    ) {
        Text(
            text = "Коэффициент all-out",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Порог CF/PF·100%. Если у замера CF/PF выше этого значения, " +
                "сила не упала к плато и тест помечается как «не all-out».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                showSavedHint = false
            },
            label = { Text("CF/PF, %") },
            singleLine = true,
            isError = text.isNotBlank() && !isValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (text.isNotBlank() && !isValid) {
                    Text("Введите число от 1 до 100")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val value = parsed ?: return@Button
                settings.maxCfToPeakPercent = value
                saved = value
                showSavedHint = true
            },
            enabled = isValid && parsed != saved,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить")
        }

        Text(
            text = if (showSavedHint) {
                "Сохранено: ${formatCoefficient(saved)} %"
            } else {
                "Текущее значение: ${formatCoefficient(saved)} %"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Без хвостовых нулей у целых значений: 80.0 → «80», 82.5 → «82.5». */
private fun formatCoefficient(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.US, "%.1f", value)
