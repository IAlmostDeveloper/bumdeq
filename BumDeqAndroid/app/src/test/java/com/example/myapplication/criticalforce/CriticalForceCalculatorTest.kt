package com.example.Bumdeq.criticalforce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты алгоритма Critical Force на синтетических данных.
 *
 * Сигнал генерируется по метроному 7/3: внутри рабочего окна каждого раунда —
 * постоянное усилие [roundForce], в фазах отдыха — 0. Это позволяет точно знать
 * ожидаемые CF/PF и проверить расчёт.
 */
class CriticalForceCalculatorTest {

    private val protocol = CriticalForceProtocol() // 24×7/3, плато по 6, 20 мм

    /** Генератор сигнала: для 0-based раунда k задаётся постоянная сила тяги. */
    private fun buildSamples(
        protocol: CriticalForceProtocol,
        hz: Int = 50,
        roundForce: (Int) -> Double,
    ): List<ForceSample> {
        val dtMs = 1000L / hz
        val endMs = (protocol.totalSeconds * 1000.0).toLong()
        val samples = ArrayList<ForceSample>()
        var tMs = 0L
        while (tMs <= endMs) {
            val tSec = tMs / 1000.0
            val k = (tSec / protocol.cycleSeconds).toInt()
            val inWork = k < protocol.rounds &&
                tSec >= k * protocol.cycleSeconds &&
                tSec <= k * protocol.cycleSeconds + protocol.workSeconds
            samples.add(ForceSample(tMs, if (inWork) roundForce(k) else 0.0))
            tMs += dtMs
        }
        return samples
    }

    @Test
    fun decayingForce_plateauBecomesCriticalForce() {
        // Сила падает 50 → 20 кг и с раунда 16 держит плато 20 кг.
        val samples = buildSamples(protocol) { k -> maxOf(20.0, 50.0 - 2.0 * k) }

        val r = CriticalForceCalculator(protocol).compute(samples, startTimestampMs = 0L)

        assertEquals("раундов должно быть 24", 24, r.roundForces.size)
        assertEquals("CF = плато последних 6 раундов", 20.0, r.criticalForceKg, 0.3)
        assertEquals("PF = усилие первого раунда", 50.0, r.peakForceKg, 0.001)
        assertTrue("W′ > 0, раз были раунды выше CF", r.wPrimeKgS > 0.0)
        assertEquals("CF/PF = 20/50 = 40%", 40.0, r.cfToPeakPercent, 1.0)
        assertTrue("реальное падение усилия → валидный all-out", r.looksLikeValidAllOut())
    }

    @Test
    fun constantForce_isNotAValidAllOut() {
        // Постоянные 30 кг во всех тягах: CF == PF, реального падения нет.
        val samples = buildSamples(protocol) { 30.0 }

        val r = CriticalForceCalculator(protocol).compute(samples, startTimestampMs = 0L)

        assertEquals(30.0, r.criticalForceKg, 0.1)
        assertEquals(30.0, r.peakForceKg, 0.001)
        assertEquals("импульс над CF ≈ 0", 0.0, r.wPrimeKgS, 1.0)
        assertFalse("CF≈PF → тест не похож на all-out", r.looksLikeValidAllOut())
    }

    @Test
    fun normalizedMetrics_areComputedWhenInputsGiven() {
        val samples = buildSamples(protocol) { k -> maxOf(20.0, 50.0 - 2.0 * k) }

        val r = CriticalForceCalculator(protocol).compute(
            samples,
            startTimestampMs = 0L,
            mvcKg = 50.0,
            bodyWeightKg = 70.0,
        )

        assertEquals("CF/MVC = 20/50 = 40%", 40.0, r.cfToMvcPercent!!, 1.0)
        assertEquals("CF/вес = 20/70", 28.57, r.cfToBodyWeightPercent!!, 1.0)
        assertEquals("PF/вес = 50/70", 71.43, r.peakToBodyWeightPercent!!, 1.0)
    }

    @Test
    fun normalizedMetrics_areNullWhenInputsMissing() {
        val samples = buildSamples(protocol) { 30.0 }

        val r = CriticalForceCalculator(protocol).compute(samples, startTimestampMs = 0L)

        assertEquals(null, r.cfToMvcPercent)
        assertEquals(null, r.cfToBodyWeightPercent)
        assertEquals(null, r.peakToBodyWeightPercent)
    }
}
