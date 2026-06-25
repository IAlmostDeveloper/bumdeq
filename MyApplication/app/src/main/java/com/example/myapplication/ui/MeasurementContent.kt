package com.example.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.bluetooth.Measurement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Содержимое вкладки «Замеры»: одно текущее значение, полученное от подключённого
 * BLE-устройства. Крупно — числовое значение (разобранное из текстовой нагрузки),
 * ниже — сырые байты в HEX как «истина».
 *
 * Рисуется без собственного Scaffold — внутри [contentPadding] общего Scaffold MainScreen.
 *
 * @param measurement последний замер или null, если данных ещё не приходило
 */
@Composable
fun MeasurementContent(
    measurement: Measurement?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (measurement == null) {
            Text(
                text = "Нет данных.\nПодключитесь к устройству на вкладке «Устройства».",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        // Источник
        Text(
            text = measurement.deviceName?.takeIf { it.isNotBlank() } ?: "Без имени",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = measurement.deviceAddress,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Текущее значение крупно: типизированное число, если нагрузка парсится; иначе
        // показываем текст как есть, иначе «—». Так значение не пропадает при любом формате.
        val display = measurement.numericValue
            ?.let { String.format(Locale.US, "%.2f", it) }
            ?: measurement.asText.ifBlank { "—" }
        Text(
            text = display,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp),
        )

        // Сырые байты в HEX — ground truth, показываем всегда.
        Text(
            text = "HEX: ${measurement.asHex.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Время получения
        Text(
            text = "Обновлено: ${TIME_FORMAT.format(Date(measurement.timestampMs))}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// Один форматтер на файл вместо создания нового на каждую рекомпозицию.
// Используется только на main-потоке, поэтому потокобезопасность SimpleDateFormat не нужна.
// Locale.US (а не getDefault) намеренно: "HH:mm:ss" локале-независим, зато это истинный
// синглтон без предупреждения ConstantLocale о смене локали в рантайме.
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
