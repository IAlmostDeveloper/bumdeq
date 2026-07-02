package com.example.Bumdeq.criticalforce

import kotlin.math.max

/**
 * Один отсчёт силы хвата.
 *
 * @param timestampMs абсолютная метка времени прибора, мс (System.currentTimeMillis или device-clock)
 * @param forceKg     сила в килограммах — как отдаёт Tindeq Progressor. Подойдёт любая единица
 *                    силы (Н, кг), лишь бы во всех отсчётах одного теста она была одинаковой;
 *                    тогда импульс W′/полный импульс получится в «[единица]·с».
 */
data class ForceSample(
    val timestampMs: Long,
    val forceKg: Double,
)

/** Способ свёртки отсчётов внутри одного 7-сек. сокращения в одно число. */
enum class RoundAggregation {
    /** Среднее — каноническое определение Giles et al. 2021. */
    MEAN,

    /** Медиана — устойчивее к рывкам/спайкам контакта с зацепом (так делают некоторые реализации). */
    MEDIAN,
}

/**
 * Параметры протокола Critical Force. По умолчанию — «4-min all-out test»
 * (Giles et al. 2021), который реализует Tindeq Progressor:
 * 24 раунда «7 с тяга / 3 с отдых», CF = плато по последним 6 раундам, планка 20 мм.
 *
 * @param rounds              число раундов (по умолчанию 24)
 * @param workSeconds         длительность тяги в раунде, с (7)
 * @param restSeconds         длительность отдыха в раунде, с (3)
 * @param plateauRounds       сколько последних раундов усредняется в CF — «плато» (6)
 * @param edgeMillimeters     размер зацепа, мм — только для отчёта, в расчёт не входит (18 или 20)
 * @param aggregation         чем сворачивать отсчёты внутри сокращения
 * @param workForceThresholdKg порог силы (кг), ниже которого отсчёт в окне считается шумом
 *                            и не идёт в свёртку раунда; 0.0 — фильтр выключен
 */
data class CriticalForceProtocol(
    val rounds: Int = 24,
    val workSeconds: Double = 7.0,
    val restSeconds: Double = 3.0,
    val plateauRounds: Int = 6,
    val edgeMillimeters: Int = 20,
    val aggregation: RoundAggregation = RoundAggregation.MEAN,
    val workForceThresholdKg: Double = 0.0,
) {
    /** Полный период одного раунда, с (тяга + отдых). */
    val cycleSeconds: Double get() = workSeconds + restSeconds

    /** Полная длительность теста, с. */
    val totalSeconds: Double get() = rounds * cycleSeconds

    init {
        require(rounds > 0) { "rounds must be > 0, got $rounds" }
        require(workSeconds > 0.0) { "workSeconds must be > 0, got $workSeconds" }
        require(restSeconds >= 0.0) { "restSeconds must be >= 0, got $restSeconds" }
        require(plateauRounds in 1..rounds) { "plateauRounds must be in 1..$rounds, got $plateauRounds" }
    }
}

/**
 * Свёрнутое усилие одного раунда — для графика и контроля выхода на плато.
 *
 * @param index       номер раунда, 1-based
 * @param forceKg     усилие раунда после свёртки, кг; [Double.NaN], если в окне не было отсчётов
 * @param sampleCount сколько отсчётов попало в рабочее окно (после фильтра порога)
 */
data class RoundForce(
    val index: Int,
    val forceKg: Double,
    val sampleCount: Int,
) {
    val hasData: Boolean get() = sampleCount > 0 && !forceKg.isNaN()
}

/**
 * Результат расчёта Critical Force.
 *
 * @param criticalForceKg        CF — плато по последним [CriticalForceProtocol.plateauRounds] раундам, кг
 * @param peakForceKg            PF — макс. мгновенная сила за тест, кг
 * @param wPrimeKgS              W′ — импульс над CF (площадь силы выше CF по времени), кг·с
 * @param totalImpulseKgS        суммарный импульс силы за тест, кг·с
 * @param roundForces            усилие по каждому раунду (длина = [CriticalForceProtocol.rounds])
 * @param cfToPeakPercent        CF/PF·100% — мера «настоящести» all-out: чем ниже, тем сильнее было падение
 * @param cfToMvcPercent         CF/MVC·100% — индекс утомляемости предплечья; null, если MVC не задан
 * @param cfToBodyWeightPercent  CF/вес·100%; null, если вес не задан
 * @param peakToBodyWeightPercent PF/вес·100%; null, если вес не задан
 */
data class CriticalForceResult(
    val criticalForceKg: Double,
    val peakForceKg: Double,
    val wPrimeKgS: Double,
    val totalImpulseKgS: Double,
    val roundForces: List<RoundForce>,
    val cfToPeakPercent: Double,
    val cfToMvcPercent: Double?,
    val cfToBodyWeightPercent: Double?,
    val peakToBodyWeightPercent: Double?,
) {
    /**
     * Похоже ли на корректный all-out: усилие реально упало к плато (CF заметно ниже пика).
     * Если спортсмен «раскладывался по силам», CF окажется близок к PF — тест невалиден.
     *
     * @param maxCfToPeakPercent порог CF/PF·100%, выше которого тест считаем подозрительным
     */
    fun looksLikeValidAllOut(maxCfToPeakPercent: Double = 90.0): Boolean =
        cfToPeakPercent in 0.0..maxCfToPeakPercent
}

/**
 * Считает Critical Force, W′ и производные метрики из потока отсчётов силы
 * по протоколу [CriticalForceProtocol]. Без Android-зависимостей — чистая логика,
 * пригодная для юнит-тестов.
 *
 * Алгоритм (4-min all-out test, Giles et al. 2021 / Tindeq Progressor):
 *  1. Раунды нарезаются по метроному относительно `startTimestampMs`: раунд k (0-based) —
 *     окно тяги `[k·cycle, k·cycle + work]`. Предполагается, что прибор вёл спортсмена
 *     по ритму 7/3, а старт записи совпал со стартом первого раунда.
 *  2. Усилие раунда = свёртка отсчётов окна ([RoundAggregation.MEAN] по умолчанию).
 *  3. **CF** = среднее усилие последних [CriticalForceProtocol.plateauRounds] раундов с данными.
 *  4. **PF** = макс. мгновенная сила за весь тест.
 *  5. **W′** = импульс над CF = ∫ max(0, F − CF) dt (трапеции по соседним отсчётам).
 *  6. Нормировки CF/MVC, CF/вес, PF/вес — если переданы MVC и/или вес.
 *
 * Класс не хранит состояние между вызовами — один экземпляр можно переиспользовать.
 */
class CriticalForceCalculator(
    private val protocol: CriticalForceProtocol = CriticalForceProtocol(),
) {

    /**
     * @param samples         отсчёты силы за весь тест (порядок не важен — отсортируем сами)
     * @param startTimestampMs метка старта первого раунда; по умолчанию — время первого отсчёта
     * @param mvcKg           максимальная сила (из отдельного 5-сек. MVC-теста), кг — для CF/MVC
     * @param bodyWeightKg    вес тела, кг — для CF/вес и PF/вес
     */
    fun compute(
        samples: List<ForceSample>,
        startTimestampMs: Long = samples.minOfOrNull { it.timestampMs } ?: 0L,
        mvcKg: Double? = null,
        bodyWeightKg: Double? = null,
    ): CriticalForceResult {
        val sorted = samples.sortedBy { it.timestampMs }

        val rounds = aggregateRounds(sorted, startTimestampMs)
        val criticalForce = criticalForce(rounds)
        val peak = sorted.maxOfOrNull { it.forceKg } ?: 0.0

        return CriticalForceResult(
            criticalForceKg = criticalForce,
            peakForceKg = peak,
            wPrimeKgS = impulseAbove(sorted, baseline = criticalForce),
            totalImpulseKgS = impulseAbove(sorted, baseline = 0.0),
            roundForces = rounds,
            cfToPeakPercent = if (peak > 0.0) 100.0 * criticalForce / peak else 0.0,
            cfToMvcPercent = mvcKg?.takeIf { it > 0.0 }?.let { 100.0 * criticalForce / it },
            cfToBodyWeightPercent = bodyWeightKg?.takeIf { it > 0.0 }?.let { 100.0 * criticalForce / it },
            peakToBodyWeightPercent = bodyWeightKg?.takeIf { it > 0.0 }?.let { 100.0 * peak / it },
        )
    }

    /** Шаг 1–2: режем на раунды по метроному и сворачиваем каждое рабочее окно в одно усилие. */
    private fun aggregateRounds(sorted: List<ForceSample>, startMs: Long): List<RoundForce> =
        (0 until protocol.rounds).map { k ->
            val fromMs = startMs + (k * protocol.cycleSeconds * 1000.0).toLong()
            val toMs = startMs + ((k * protocol.cycleSeconds + protocol.workSeconds) * 1000.0).toLong()
            val inWindow = sorted.asSequence()
                .filter { it.timestampMs in fromMs..toMs && it.forceKg >= protocol.workForceThresholdKg }
                .map { it.forceKg }
                .toList()
            RoundForce(
                index = k + 1,
                forceKg = if (inWindow.isEmpty()) Double.NaN else aggregate(inWindow),
                sampleCount = inWindow.size,
            )
        }

    private fun aggregate(values: List<Double>): Double = when (protocol.aggregation) {
        RoundAggregation.MEAN -> values.average()
        RoundAggregation.MEDIAN -> median(values)
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Шаг 3: CF = среднее усилие последних [CriticalForceProtocol.plateauRounds] раундов с данными. */
    private fun criticalForce(rounds: List<RoundForce>): Double {
        val plateau = rounds.filter { it.hasData }.takeLast(protocol.plateauRounds)
        return if (plateau.isEmpty()) 0.0 else plateau.map { it.forceKg }.average()
    }

    /**
     * Шаг 5: импульс силы над уровнем [baseline] методом трапеций:
     * `∫ max(0, F − baseline) dt`. При baseline = CF это W′, при baseline = 0 — полный импульс.
     * Фазы отдыха (F ≈ 0 < CF) дают нулевой вклад, поэтому интегрируем по всей записи.
     *
     * Замечание: при пересечении линии baseline между двумя отсчётами трапеция по
     * усечённым концам слегка завышает площадь — для частоты ≥ 10–20 Гц погрешность мала.
     */
    private fun impulseAbove(sorted: List<ForceSample>, baseline: Double): Double {
        if (sorted.size < 2) return 0.0
        var impulse = 0.0
        for (i in 1 until sorted.size) {
            val dtSec = (sorted[i].timestampMs - sorted[i - 1].timestampMs) / 1000.0
            if (dtSec <= 0.0) continue
            val e0 = max(0.0, sorted[i - 1].forceKg - baseline)
            val e1 = max(0.0, sorted[i].forceKg - baseline)
            impulse += 0.5 * (e0 + e1) * dtSec
        }
        return impulse
    }
}
