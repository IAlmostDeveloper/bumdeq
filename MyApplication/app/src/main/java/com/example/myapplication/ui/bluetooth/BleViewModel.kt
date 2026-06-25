package com.example.myapplication.ui.bluetooth

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Связывает [BleManager] с UI: собирает все его потоки в один [BleScreenState] и
 * проксирует действия экрана. Хранит «сохранённый» MAC (последний выбранный).
 *
 * Сейчас сохранение держится в памяти ViewModel; точка интеграции с DataStore
 * помечена в [saveSelected].
 */
class BleViewModel(private val manager: BleManager) : ViewModel() {

    private val savedAddress = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Единый источник правды для [BluetoothScreen]. */
    val state: StateFlow<BleScreenState> = combine(
        manager.bondedDevices,
        manager.scanResults,
        manager.connectionStates,
        manager.isScanning,
        savedAddress,
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun loadBondedDevices() = manager.refreshBondedDevices()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() = manager.startScan()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() = manager.stopScan()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BleDeviceUi) = manager.connect(device.address)

    /** Запомнить выбранное устройство. TODO: вынести в DataStore для переживания перезапуска. */
    fun saveSelected(device: BleDeviceUi) {
        savedAddress.value = device.address
    }

    override fun onCleared() {
        super.onCleared()
        // disconnect() сам проверяет наличие BLUETOOTH_CONNECT и тихо выходит, если его нет.
        @Suppress("MissingPermission")
        manager.disconnect()
        manager.release() // снять broadcast-подписку на состояние адаптера
    }

    companion object {
        /** Фабрика: BleManager нужен applicationContext, а не Activity. */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val manager = BleManager(context.applicationContext)
                    return BleViewModel(manager) as T
                }
            }
    }
}
