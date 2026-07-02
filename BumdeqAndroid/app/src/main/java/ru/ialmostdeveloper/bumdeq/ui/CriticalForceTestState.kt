package ru.ialmostdeveloper.bumdeq.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceCalculator
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceProtocol
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import ru.ialmostdeveloper.bumdeq.criticalforce.ForceSample
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Фаза раунда. */
enum class TestPhase { WORK, REST }

/**
 * Состояние и метроном теста Critical Force (24 раунда «7 c тяга / 3 c отдых»).
 *
 * Время теста отсчитывается «активным» прошедшим временем [elapsedMs] — оно НЕ растёт во
 * время паузы. Отсчёты силы метятся этим же [elapsedMs], а итог считается с
 * `startTimestampMs = 0`, поэтому метроном калькулятора совпадает с записью при любых паузах.
 *
 * Состояние держится на Compose-полях; таймер крутится в [run] (вызывать из LaunchedEffect).
 * Экземпляр одноразовый — на каждый запуск теста создаётся новый (remember).
 */
class CriticalForceTestState(
    val protocol: CriticalForceProtocol = CriticalForceProtocol(),
) {
    private val cycleMs = (protocol.cycleSeconds * 1000.0).toLong()
    private val workMs = (protocol.workSeconds * 1000.0).toLong()
    private val totalMs = (protocol.totalSeconds * 1000.0).toLong()

    var elapsedMs by mutableLongStateOf(0L)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var isFinished by mutableStateOf(false)
        private set

    /** Последнее показание датчика (кг) — для крупного вывода «текущее». */
    var currentForce by mutableDoubleStateOf(0.0)
        private set

    /** Итог теста; не null после прохождения всех раундов. */
    var result by mutableStateOf<CriticalForceResult?>(null)
        private set

    // Полные отсчёты для расчёта (метка = активный elapsedMs).
    private val samples = ArrayList<ForceSample>()

    // Точки для графика (значения силы); рисуются с прореживанием.
    private val _graph = mutableStateListOf<Float>()
    val graph: List<Float> get() = _graph

    /** Текущий раунд, 1-based (1..rounds). */
    val currentRound: Int
        get() = (elapsedMs / cycleMs).toInt().coerceIn(0, protocol.rounds - 1) + 1

    private val inCycleMs: Long get() = elapsedMs % cycleMs

    val phase: TestPhase get() = if (inCycleMs < workMs) TestPhase.WORK else TestPhase.REST

    /** Оставшиеся секунды текущей фазы (округление вверх, не меньше 0). */
    val phaseRemainingSeconds: Int
        get() {
            val remMs = if (phase == TestPhase.WORK) workMs - inCycleMs else cycleMs - inCycleMs
            return ceil(remMs / 1000.0).toInt().coerceAtLeast(0)
        }

    /** Прогресс текущей фазы 0..1 — для индикатора. */
    val phaseProgress: Float
        get() {
            val span = if (phase == TestPhase.WORK) workMs else cycleMs - workMs
            val done = if (phase == TestPhase.WORK) inCycleMs else inCycleMs - workMs
            return if (span <= 0L) 0f else (done.toFloat() / span).coerceIn(0f, 1f)
        }

    fun togglePause() {
        if (!isFinished) isPaused = !isPaused
    }

    /** Принять новое показание датчика. Запись в тест идёт только во время активной фазы. */
    fun onForce(force: Double) {
        currentForce = force
        if (isFinished || isPaused || phase == TestPhase.REST) return
        samples.add(ForceSample(elapsedMs, force))
        _graph.add(force.toFloat())
    }

    /** Метроном: продвигает активное время, пока не пройдут все раунды; затем считает итог. */
    suspend fun run() {
        var last = SystemClock.elapsedRealtime()
        while (elapsedMs < totalMs) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            val delta = now - last
            last = now
            // last обновляем всегда, чтобы после снятия паузы время не «прыгнуло» на её длину.
            if (!isPaused) {
                elapsedMs = (elapsedMs + delta).coerceAtMost(totalMs)
            }
        }
        finish()
    }

    private fun finish() {
        if (isFinished) return
        isFinished = true
        result = CriticalForceCalculator(protocol).compute(samples, startTimestampMs = 0L)
    }

    companion object {
        private const val TICK_MS = 33L // ~30 Гц обновление UI/таймера
    }
}
