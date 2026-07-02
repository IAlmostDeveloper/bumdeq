package ru.ialmostdeveloper.bumdeq.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import ru.ialmostdeveloper.bumdeq.data.AllOutSettings
import ru.ialmostdeveloper.bumdeq.ui.bluetooth.Measurement
import java.util.Locale
import kotlin.math.max

/**
 * Страница замера Critical Force: метроном 24×(7 c тяга / 3 c отдых), сбор показаний датчика,
 * график и расчёт CF по завершении. Тест стартует при открытии страницы.
 *
 * @param measurement последнее показание датчика (источник отсчётов силы)
 * @param onBack      отмена замера / возврат в главное меню (нижняя навигация здесь скрыта)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalForceScreen(
    measurement: Measurement?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { CriticalForceTestState() }

    // Метроном теста; отменяется автоматически при уходе со страницы.
    LaunchedEffect(Unit) { state.run() }

    // На время замера блокируем поворот экрана (фиксируем текущую ориентацию), чтобы
    // пересоздание Activity не сбрасывало тест. При выходе со страницы — восстанавливаем.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Запись каждого нового показания датчика в тест. Датчик шлёт ~раз в 200 мс (5 Гц),
    // ключ — timestampMs, поэтому пишется ровно один отсчёт на каждое новое показание.
    // Таймер ниже тикает чаще (≈30 Гц), так что обратный отсчёт остаётся плавным.
    LaunchedEffect(measurement?.timestampMs) {
        measurement?.numericValue?.let { state.onForce(it.toDouble()) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(MeasurementType.CRITICAL_FORCE.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val result = state.result
            if (state.isFinished && result != null) {
                ResultContent(result = result, graph = state.graph, onBack = onBack)
            } else {
                RunningContent(state = state, onCancel = onBack)
            }
        }
    }
}

@Composable
private fun ColumnScope.RunningContent(
    state: CriticalForceTestState,
    onCancel: () -> Unit,
) {
    val isWork = state.phase == TestPhase.WORK
    val phaseColor = if (isWork) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Text(
        text = "Раунд ${state.currentRound} / ${state.protocol.rounds}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )

    Text(
        text = if (isWork) "ТЯГА" else "ОТДЫХ",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = phaseColor,
        modifier = Modifier.padding(top = 4.dp),
    )

    Text(
        text = "${state.phaseRemainingSeconds} c",
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        color = phaseColor,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    if (state.isPaused) {
        Text(
            text = "на паузе",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    LinearProgressIndicator(
        progress = { state.phaseProgress },
        color = phaseColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )

    Text(
        text = "Текущее: ${formatKg(state.currentForce)} кг",
        style = MaterialTheme.typography.titleMedium,
    )

    ForceGraph(
        points = state.graph,
        lineColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(vertical = 12.dp),
    )

    Button(
        onClick = { state.togglePause() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.isPaused) "Продолжить" else "Пауза")
    }
    Spacer(Modifier.height(8.dp))
    // Отмена замера
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Отменить замер")
    }
}

@Composable
private fun ColumnScope.ResultContent(
    result: CriticalForceResult,
    graph: List<Float>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AllOutSettings(context) }

    Text(
        text = "Critical Force",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "${formatKg(result.criticalForceKg)} кг",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )

    MetricRow("Пиковая сила (PF)", "${formatKg(result.peakForceKg)} кг")
    MetricRow("W′ (импульс над CF)", "${formatKg(result.wPrimeKgS)} кг·с")
    MetricRow("CF / PF", "${formatKg(result.cfToPeakPercent)} %")
    Text(
        text = if (result.looksLikeValidAllOut(settings.maxCfToPeakPercent)) {
            "Похоже на корректный all-out"
        } else {
            "CF близок к пику — возможно, тест не был all-out"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )

    ForceGraph(
        points = graph,
        lineColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(vertical = 16.dp),
    )

    Button(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("В меню")
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Линейный график силы по времени. Прореживает точки до [MAX_DRAW_POINTS] для плавности. */
@Composable
private fun ForceGraph(
    points: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier.border(1.dp, outline),
    ) {
        if (points.size < 2) return@Canvas
        val maxForce = max(points.max(), 1f)
        val stride = max(1, points.size / MAX_DRAW_POINTS)
        val drawn = points.filterIndexed { i, _ -> i % stride == 0 }
        if (drawn.size < 2) return@Canvas

        val stepX = size.width / (drawn.size - 1)
        val path = Path()
        drawn.forEachIndexed { i, f ->
            val x = i * stepX
            val y = size.height - (f / maxForce) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
    }
}

private fun formatKg(value: Double): String = String.format(Locale.US, "%.1f", value)

/** Развернуть Compose-контекст до Activity (он может быть обёрнут ContextWrapper'ом). */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val MAX_DRAW_POINTS = 400
