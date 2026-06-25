package com.example.myapplication.ui.bluetooth

/**
 * Состояние подключения отдельного устройства — управляет видом кнопки/иконки в списке.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * UI-представление BLE-устройства. Намеренно отвязано от android.bluetooth.BluetoothDevice,
 * чтобы экран можно было рисовать на mock-данных и в @Preview без реального адаптера.
 *
 * @param name      человекочитаемое имя (может быть null у эфирных находок)
 * @param address   MAC-адрес, уникальный ключ устройства
 * @param rssi      уровень сигнала в dBm для найденных устройств; null для сопряжённых
 * @param state     текущее состояние подключения
 */
data class BleDeviceUi(
    val name: String?,
    val address: String,
    val rssi: Int? = null,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Без имени"
}

/**
 * Полное состояние экрана Bluetooth. Один источник правды для UI —
 * экран рисуется как чистая функция от этого объекта.
 *
 * @param bondedDevices  системно сопряжённые устройства (верхняя секция)
 * @param scannedDevices устройства, найденные сканированием (нижняя секция)
 * @param isScanning       идёт ли сейчас поиск (индикатор + кнопка «Стоп»)
 * @param savedAddress     MAC последнего выбранного устройства (DataStore); подсвечивается в списке
 * @param bluetoothEnabled включён ли адаптер Bluetooth; при false показываем баннер и блокируем поиск
 */
data class BleScreenState(
    val bondedDevices: List<BleDeviceUi> = emptyList(),
    val scannedDevices: List<BleDeviceUi> = emptyList(),
    val isScanning: Boolean = false,
    val savedAddress: String? = null,
    val bluetoothEnabled: Boolean = true,
)

/**
 * Один замер, полученный уведомлением от подписанной характеристики.
 *
 * @param raw           сырые байты как пришли от устройства
 * @param deviceAddress MAC устройства-источника
 * @param deviceName    имя источника (может быть null)
 * @param timestampMs   время получения (System.currentTimeMillis)
 */
data class Measurement(
    val raw: ByteArray,
    val deviceAddress: String,
    val deviceName: String?,
    val timestampMs: Long,
) {
    /** Декодированный UTF-8 текст. */
    val asText: String get() = raw.toString(Charsets.UTF_8)

    /** Сырые байты в HEX, через пробел: "A1 0F 23". */
    val asHex: String get() = raw.joinToString(" ") { "%02X".format(it) }

    // ByteArray в data class требует ручных equals/hashCode для корректного сравнения.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Measurement) return false
        return timestampMs == other.timestampMs &&
            deviceAddress == other.deviceAddress &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + deviceAddress.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
