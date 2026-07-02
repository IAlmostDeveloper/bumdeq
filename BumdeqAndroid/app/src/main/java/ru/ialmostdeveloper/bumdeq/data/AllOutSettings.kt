package ru.ialmostdeveloper.bumdeq.data

import android.content.Context
import androidx.core.content.edit

class AllOutSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Коэффициент падения усилия, используется для валидности теста.
     * Если процент крит. силы к макс. силе превышает его,
     * тест считаем невалидным - скорее всего, тестируемый не прилагал все усилия на протяжении теста.
     */
    var maxCfToPeakPercent: Float
        get() = prefs.getFloat(KEY_MAX_CF_TO_PEAK, DEFAULT_MAX_CF_TO_PEAK)
        set(value) = prefs.edit { putFloat(KEY_MAX_CF_TO_PEAK, value) }

    companion object {
        const val PREFS_NAME = "preferences"
        const val KEY_MAX_CF_TO_PEAK = "maxCfToPeakPercent"
        const val DEFAULT_MAX_CF_TO_PEAK = 80.0f
    }
}
