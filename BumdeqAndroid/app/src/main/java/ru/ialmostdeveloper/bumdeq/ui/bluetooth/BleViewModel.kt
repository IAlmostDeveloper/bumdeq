package ru.ialmostdeveloper.bumdeq.ui.bluetooth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ru.ialmostdeveloper.bumdeq.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Связывает [BleManager] с UI: собирает все его потоки в один [BleScreenState] и
 * проксирует действия экрана.
 *
 * Проверка разрешений — целиком на [BleManager] (он встроенно гейтит каждый системный
 * вызов), поэтому методы здесь не объявляют @RequiresPermission. «Сохранённый» MAC
 * персистится через [SettingsRepository] (DataStore) и переживает перезапуск процесса.
 */
class BleViewModel(
    private val manager: BleManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Единый источник правды для экрана устройств. */
    val state: StateFlow<BleScreenState> = combine(
        manager.bondedDevices,
        manager.scanResults,
        manager.connectionStates,
        manager.isScanning,
        settings.savedAddress,
    ) { bonded, scanned, states, scanning, saved ->
        BleScreenState(
            // Подмешиваем актуальный статус подключения в обе секции.
            bondedDevices = bonded.map { it.copy(state = states[it.address] ?: it.state) },
            scannedDevices = scanned.values
                .map { it.copy(state = states[it.address] ?: it.state) }
                .sortedByDescending { it.rssi },
            isScanning = scanning,
            savedAddress = saved,
        )
    }
        // 6-й поток (combine типизирован до 5) — подмешиваем состояние адаптера.
        .combine(manager.bluetoothEnabled) { st, btOn -> st.copy(bluetoothEnabled = btOn) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BleScreenState(),
        )

    /** Последний замер с подключённого устройства — для вкладки «Замеры». */
    val lastMeasurement: StateFlow<Measurement?> = manager.lastMeasurement

    val isBluetoothEnabled: Boolean get() = manager.isBluetoothEnabled

    /** Загрузить сопряжённые устройства (после выдачи разрешений). */
    fun loadBondedDevices() = manager.refreshBondedDevices()

    fun startScan() = manager.startScan()

    fun stopScan() = manager.stopScan()

    fun connect(device: BleDeviceUi) = manager.connect(device.address)

    /** Запомнить выбранное устройство в DataStore (переживает перезапуск). */
    fun saveSelected(device: BleDeviceUi) {
        viewModelScope.launch { settings.setSavedAddress(device.address) }
    }

    override fun onCleared() {
        super.onCleared()
        manager.disconnect()
        manager.release() // снять broadcast-подписку на состояние адаптера
    }

    companion object {
        /** Фабрика: BleManager и SettingsRepository нужен applicationContext, а не Activity. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    return BleViewModel(
                        manager = BleManager(appContext),
                        settings = SettingsRepository(appContext),
                    ) as T
                }
            }
    }
}
