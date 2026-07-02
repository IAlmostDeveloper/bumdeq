package ru.ialmostdeveloper.bumdeq.ui

/**
 * Типы замеров, доступных для запуска со вкладки «Замеры».
 * Пока поддержан один — [CRITICAL_FORCE]; список расширяется добавлением констант.
 *
 * @param title человекочитаемое название для окна выбора и заголовка страницы
 */
enum class MeasurementType(val title: String) {
    CRITICAL_FORCE("Critical Force"),
}
