package ru.ialmostdeveloper.bumdeq.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Содержимое вкладки «Результаты» — история сохранённых замеров Critical Force.
 * Новые замеры показываются сверху; каждый можно удалить, а весь список — выгрузить в JSON-файл.
 *
 * Рисуется без собственного Scaffold — внутри [contentPadding] общего Scaffold MainScreen.
 *
 * @param results     сохранённые замеры (в порядке добавления — переворачиваем для показа)
 * @param onDelete    удалить замер по идентификатору
 * @param onExportJson построить JSON-массив всех замеров для экспорта в файл
 */
@Composable
fun ResultsContent(
    results: List<CriticalForceResult>,
    onDelete: (UUID) -> Unit,
    onExportJson: () -> String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Сохранённых результатов пока нет.\nЗавершите замер на вкладке «Замеры».",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Диалог создания файла (Storage Access Framework): пользователь сам выбирает, куда сохранить.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // пользователь отменил выбор
        val json = onExportJson()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("no output stream")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (ok) "Экспортировано" else "Не удалось сохранить файл",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        OutlinedButton(
            onClick = { exportLauncher.launch("bumdeq-cf-results.json") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(
                text = "Экспорт в JSON (${results.size})",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = results.asReversed(), key = { it.id }) { result ->
                ResultCard(result = result, onDelete = { onDelete(result.id) })
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: CriticalForceResult,
    onDelete: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.description.ifBlank { "Замер без описания" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (result.createdAtMs > 0L) {
                    Text(
                        text = DATE_FORMAT.format(Date(result.createdAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "CF ${formatKg(result.criticalForceKg)} кг · " +
                        "PF ${formatKg(result.peakForceKg)} кг · " +
                        "W′ ${formatKg(result.wPrimeKgS)} кг·с",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Раундов: ${result.roundForces.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить замер")
            }
        }
    }
}

private fun formatKg(value: Double): String = String.format(Locale.US, "%.1f", value)

// Один форматтер на файл. Locale.getDefault() — дата показывается в локали пользователя.
private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
