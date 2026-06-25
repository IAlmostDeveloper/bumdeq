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
 * BLE-устройства. Показывает и декодированный UTF-8 текст (крупно), и сырые байты в HEX.
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

        // Текущее значение крупно
        Text(
            text = measurement.asText.ifBlank { "—" },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp),
        )

        // Сырые байты в HEX
        Text(
            text = "HEX: ${measurement.asHex.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Время получения
        Text(
            text = "Обновлено: ${formatTime(measurement.timestampMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
