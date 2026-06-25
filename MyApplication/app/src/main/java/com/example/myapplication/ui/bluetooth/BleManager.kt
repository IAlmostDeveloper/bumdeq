package com.example.myapplication.ui.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class BleManager(private val appContext: Context) {

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    /** Найденные сканированием устройства, ключ — MAC, значение — UI-модель с актуальным RSSI. */
    private val _scanResults = MutableStateFlow<Map<String, BleDeviceUi>>(emptyMap())
    val scanResults: StateFlow<Map<String, BleDeviceUi>> = _scanResults.asStateFlow()

    /** Системно сопряжённые устройства. */
    private val _bondedDevices = MutableStateFlow<List<BleDeviceUi>>(emptyList())
    val bondedDevices: StateFlow<List<BleDeviceUi>> = _bondedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Состояние подключения по MAC-адресу. */
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    /** Последний замер, полученный от подписанной характеристики. */
    private val _lastMeasurement = MutableStateFlow<Measurement?>(null)
    val lastMeasurement: StateFlow<Measurement?> = _lastMeasurement.asStateFlow()

    /** Включён ли адаптер Bluetooth (живо обновляется через broadcast). */
    private val _bluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private var gatt: BluetoothGatt? = null

    // MAC устройства, с которым сейчас идёт работа. Нужен, чтобы при closeGatt()
    // (тихий teardown без колбэка) корректно пометить устройство как DISCONNECTED.
    private var activeAddress: String? = null

    // Имя устройства, к которому подключаемся (для подписи замеров — в колбэке его не достать без разрешения).
    private var connectingName: String? = null

    // Авто-остановка скана по таймауту, чтобы спиннер не висел вечно при пустом эфире.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScanInternal() }
    private val connectTimeoutRunnable = Runnable { handleConnectTimeout() }

    // Следим за включением/выключением Bluetooth, чтобы UI узнавал об этом сразу.
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                _bluetoothEnabled.value = adapter?.isEnabled == true
            }
        }
    }

    init {
        appContext.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    /** Снять подписку на системные broadcast. Вызывать при уничтожении владельца (ViewModel.onCleared). */
    fun release() {
        runCatching { appContext.unregisterReceiver(btStateReceiver) }
    }

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private val hasScanPermission get() = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    private val hasConnectPermission get() = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun refreshBondedDevices() {
        if (!hasConnectPermission) return
        val adapter = adapter ?: return
        _bondedDevices.value = adapter.bondedDevices.orEmpty().map { device ->
            BleDeviceUi(
                name = device.name,
                address = device.address,
                state = currentState(device.address),
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!hasScanPermission || _isScanning.value) return

        _scanResults.value = emptyMap()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        _isScanning.value = true
        // Гарантируем остановку через SCAN_DURATION_MS, даже если ничего не найдено.
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_DURATION_MS)
        Log.d(TAG, "Scan started")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() = stopScanInternal()

    /**
     * Остановка скана со встроенной проверкой разрешения. Вызывается из публичного
     * stopScan(), из таймаута и из disconnect() — проверка checkSelfPermission стоит
     * прямо здесь, чтобы lint видел guard для @RequiresPermission-вызова stopScan().
     */
    private fun stopScanInternal() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!_isScanning.value) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        ) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        _isScanning.value = false
        Log.d(TAG, "Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            val ui = BleDeviceUi(
                name = name,
                address = device.address,
                rssi = result.rssi,
                state = currentState(device.address),
            )
            // Дедуплицируем по MAC и обновляем RSSI у уже найденных.
            _scanResults.update { it + (device.address to ui) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    // ── Подключение ────────────────────────────────────────────────────────────

    /** Подключиться к устройству по MAC. Закрывает предыдущее соединение, если было. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(address: String) {
        if (!hasConnectPermission) return
        val adapter = adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad MAC: $address", e)
            return
        }

        closeGatt() // тихо закрываем предыдущее соединение → оно помечается DISCONNECTED внутри
        // Запоминаем имя из известных устройств — в колбэке его не достать без лишних проверок.
        connectingName = deviceNameFor(address)
        activeAddress = address
        setState(address, ConnectionState.CONNECTING)

        val newGatt = device.connectGatt(appContext, false, gattCallback)
        if (newGatt == null) {
            // connectGatt() вернул null — колбэка не будет никогда, гасим CONNECTING сразу.
            Log.w(TAG, "connectGatt returned null for $address")
            setState(address, ConnectionState.DISCONNECTED)
            activeAddress = null
            return
        }
        gatt = newGatt
        // Таймаут подключения: если колбэк не придёт (null-gatt/OEM-баг), CONNECTING не залипнет.
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    /** Таймаут подключения: если за CONNECT_TIMEOUT_MS не дошли до CONNECTED — рвём и гасим спиннер. */
    private fun handleConnectTimeout() {
        val address = activeAddress ?: return
        if (currentState(address) != ConnectionState.CONNECTING) return
        Log.w(TAG, "Connect timeout for $address")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            gatt?.disconnect()
        }
        closeGatt()
    }

    /** Имя устройства по MAC из ранее найденных/сопряжённых. */
    private fun deviceNameFor(address: String): String? =
        _scanResults.value[address]?.name
            ?: _bondedDevices.value.firstOrNull { it.address == address }?.name

    /** Разорвать текущее соединение, остановить скан и освободить ресурсы. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        stopScanInternal()
        if (hasConnectPermission) gatt?.disconnect()
        closeGatt()
    }

    /**
     * Тихий teardown GATT. В отличие от disconnect(), BluetoothGatt.close() НЕ доставляет
     * onConnectionStateChange(DISCONNECTED) — поэтому состояние активного устройства
     * помечаем здесь вручную, иначе оно навсегда залипнет в CONNECTED/CONNECTING.
     * close() помечен @RequiresPermission, но освобождает ЛОКАЛЬНЫЕ ресурсы и должен
     * выполняться всегда (даже если разрешение отозвали) — иначе утечёт GATT-клиент.
     * Поэтому guard'ить нельзя, осознанно подавляем проверку.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        activeAddress?.let { setState(it, ConnectionState.DISCONNECTED) }
        activeAddress = null
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            Log.d(TAG, "onConnectionStateChange addr=$address status=$status newState=$newState")

            // Любой не-SUCCESS статус (частый случай — 133) трактуем как обрыв, независимо от newState.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT error status=$status for $address")
                setState(address, ConnectionState.DISCONNECTED)
                closeGatt()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable) // успели — таймаут не нужен
                    setState(address, ConnectionState.CONNECTED)
                    if (hasConnectPermission) gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    setState(address, ConnectionState.DISCONNECTED)
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                return
            }

            for (service in gatt.services) {
                Log.d(TAG, "Service: ${service.uuid}")
                for (ch in service.characteristics) {
                    Log.d(TAG, "  Characteristic: ${ch.uuid}")
                }
            }

            // Был баг: getService мог вернуть null → NPE на getCharacteristic.
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "Target service $SERVICE_UUID not found")
                return
            }
            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.w(TAG, "Target characteristic $CHARACTERISTIC_UUID not found")
                return
            }

            enableNotifications(gatt, characteristic)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "Descriptor write status=$status")
        }

        // API 33+ (TIRAMISU): значение приходит параметром.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleNotification(gatt, value)

        // API 31/32: 3-арг перегрузки ещё нет — стек зовёт эту, значение лежит в characteristic.value.
        // Без неё уведомления молча терялись бы на Android 12/12L (а это наш minSdk = 31).
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            handleNotification(gatt, value)
        }
    }

    /** Единый обработчик уведомления для обеих перегрузок onCharacteristicChanged. */
    private fun handleNotification(gatt: BluetoothGatt, value: ByteArray) {
        val address = gatt.device.address
        val measurement = Measurement(
            raw = value.copyOf(), // стек может переиспользовать буфер — копируем при сохранении
            deviceAddress = address,
            deviceName = connectingName,
            timestampMs = System.currentTimeMillis(),
        )
        Log.d(TAG, "Received from $address: ${measurement.asText} (${measurement.asHex})")
        _lastMeasurement.value = measurement
    }

    /**
     * Включить нотификации по характеристике: локально + запись в CCCD-дескриптор.
     * Раньше между этими шагами стоял хрупкий postDelayed(200ms) — он убран;
     * запись дескриптора идёт сразу после setCharacteristicNotification.
     */
    @SuppressLint("MissingPermission") // вызывается из колбэка после успешного connect
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "CCCD descriptor not found on ${characteristic.uuid}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun currentState(address: String): ConnectionState =
        _connectionStates.value[address] ?: ConnectionState.DISCONNECTED

    private fun setState(address: String, state: ConnectionState) {
        _connectionStates.update { it + (address to state) }
    }

    companion object {
        private const val TAG = "BLE"
        private const val SCAN_DURATION_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        val SERVICE_UUID: UUID = UUID.fromString("0000baad-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000f00d-0000-1000-8000-00805f9b34fb")


        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
