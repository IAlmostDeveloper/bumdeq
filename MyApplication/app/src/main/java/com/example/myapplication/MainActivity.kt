package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.MainScreen
import com.example.myapplication.ui.bluetooth.BleDeviceUi
import com.example.myapplication.ui.bluetooth.BleViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * Тонкая Activity: запрашивает BLE-разрешения и рисует [MainScreen], привязанный к
 * [BleViewModel]. Проверка разрешений на стороне Android-API — в BleManager;
 * Activity лишь решает, когда (пере)запросить их у пользователя.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BleViewModel

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Разбираем результат БЕЗ внешнего гейта по CONNECT: сканирование требует только
            // SCAN, поэтому при выдаче лишь SCAN поиск всё равно должен стартовать.
            onPermissionsResult(
                scanGranted = result[Manifest.permission.BLUETOOTH_SCAN] == true,
                connectGranted = result[Manifest.permission.BLUETOOTH_CONNECT] == true,
            )
        }

    // Только показывает системный диалог. На реальное включение реагирует BleManager по
    // переходу адаптера в STATE_ON (в момент возврата сюда адаптер обычно ещё TURNING_ON).
    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("BLE", "Enable-BT dialog result=${result.resultCode}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Создаём до запроса разрешений, чтобы колбэк onPermissionsResult имел инстанс.
        viewModel = ViewModelProvider(
            this,
            BleViewModel.factory(applicationContext),
        )[BleViewModel::class.java]

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val measurement by viewModel.lastMeasurement.collectAsStateWithLifecycle()

            MyApplicationTheme {
                MainScreen(
                    bleState = state,
                    measurement = measurement,
                    onScan = { withPermission(Manifest.permission.BLUETOOTH_SCAN) { viewModel.startScan() } },
                    onStopScan = { withPermission(Manifest.permission.BLUETOOTH_SCAN) { viewModel.stopScan() } },
                    onConnect = { device ->
                        withPermission(Manifest.permission.BLUETOOTH_CONNECT) { viewModel.connect(device) }
                    },
                    onSaveSelected = { device -> viewModel.saveSelected(device) },
                    onEnableBluetooth = {
                        withPermission(Manifest.permission.BLUETOOTH_CONNECT) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    },
                )
            }
        }

        requestBtPermissions()
    }

    private fun requestBtPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )
    }

    private fun onPermissionsResult(scanGranted: Boolean, connectGranted: Boolean) {
        if (!connectGranted) Log.w("BLE", "BLUETOOTH_CONNECT denied")
        if (!viewModel.isBluetoothEnabled) {
            Log.d("BLE", "Bluetooth disabled")
            return
        }
        // Сопряжённые устройства требуют CONNECT; сканирование — только SCAN. Гейтим раздельно.
        if (connectGranted) viewModel.loadBondedDevices()
        if (scanGranted) viewModel.startScan() else Log.w("BLE", "BLUETOOTH_SCAN denied")
    }

    /**
     * Единая обёртка вместо четырёх копий: выполнить [action] при наличии [permission],
     * иначе перезапросить разрешения. Проверка разрешений на уровне Android-API живёт
     * в BleManager; здесь — только UX-решение «запросить, если не выдано».
     */
    private inline fun withPermission(permission: String, action: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            requestBtPermissions()
        }
    }
}
