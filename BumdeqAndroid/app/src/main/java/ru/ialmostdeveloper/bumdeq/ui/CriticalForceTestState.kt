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
import ru.ialmostdeveloper.bumdeq.criticalforce.RoundAggregation
import ru.ialmostdeveloper.bumdeq.criticalforce.RoundForce
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
    private val cycleMs = (protocol.roundSeconds * 1000.0).toLong()
    private val workMs = (protocol.workSeconds * 1000.0).toLong()
    private val totalMs = (protocol.totalSeconds * 1000.0).toLong()

    /** Описание замера, введённое перед стартом; попадает в [result] по завершении. */
    var description: String = ""

    /** Вес тела, кг, введённый перед стартом; null — не задан. Идёт в расчёт CF/вес и в [result]. */
    var bodyWeightKg: Double? = null

    var elapsedMs by mutableLongStateOf(0L)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var isFinished by mutableStateOf(false)
        private set

    /** Последнее показание датчика (кг) — для крупного вывода «текущее». */
    var currentForce by mutableDoubleStateOf(0.0)
        private set

    /** Итог теста (он же сохраняемая запись замера); не null после прохождения всех раундов. */
    var result by mutableStateOf<CriticalForceResult?>(null)
        private set

    /**
     * Пораундовая разбивка, заполняемая по ходу теста: раунд появляется сразу по завершении
     * своего рабочего окна. В [finish] заменяется канонической разбивкой из калькулятора.
     */
    private val _rounds = mutableStateListOf<RoundForce>()
    val rounds: List<RoundForce> get() = _rounds

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
                fillCompletedRounds()
            }
        }
        finish()
    }

    /** Достраивает [_rounds] раундами, чьё рабочее окно уже закончилось. */
    private fun fillCompletedRounds() {
        val completed =
            if (elapsedMs < workMs) 0
            else ((elapsedMs - workMs) / cycleMs + 1).toInt().coerceAtMost(protocol.rounds)
        while (_rounds.size < completed) {
            val k = _rounds.size // 0-based индекс закрываемого раунда
            val forces = windowForces(k)
            _rounds.add(
                RoundForce(
                    index = k + 1,
                    forceKg = if (forces.isEmpty()) Double.NaN else aggregate(forces),
                    peakForceKg = forces.maxOrNull() ?: Double.NaN,
                    sampleCount = forces.size,
                )
            )
        }
    }

    /** Отсчёты силы рабочего окна раунда [k] (0-based) по накопленным данным. */
    private fun windowForces(k: Int): List<Double> {
        val fromMs = k * cycleMs
        val toMs = k * cycleMs + workMs
        return samples.asSequence()
            .filter { it.timestampMs in fromMs..toMs }
            .map { it.forceKg }
            .toList()
    }

    /** Свёртка отсчётов раунда — та же, что в калькуляторе (среднее/медиана по протоколу). */
    private fun aggregate(values: List<Double>): Double = when (protocol.aggregation) {
        RoundAggregation.AVERAGE -> values.average()
        RoundAggregation.MEDIAN -> values.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
    }

    private fun finish() {
        if (isFinished) return
        isFinished = true
        fillCompletedRounds() // закрыть последний раунд (его окно завершается ровно в totalMs)
        val computed = CriticalForceCalculator(protocol)
            .compute(samples, startTimestampMs = 0L, bodyWeightKg = bodyWeightKg)
        result = computed.copy(description = description)
        // Заменяем «живые» раунды каноническими из калькулятора (с учётом фильтра порога).
        _rounds.clear()
        _rounds.addAll(computed.roundForces)
    }

    companion object {
        private const val TICK_MS = 33L // ~30 Гц обновление UI/таймера
    }
}
