package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.ui.MainScreen
import com.example.myapplication.ui.bluetooth.BleDeviceUi
import com.example.myapplication.ui.bluetooth.BleViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * Тонкая Activity: запрашивает BLE-разрешения и рисует [com.example.myapplication.ui.bluetooth.BluetoothScreen],
 * привязанный к [BleViewModel]. Вся работа с BLE — в BleViewModel/BleManager.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BleViewModel

    @SuppressLint("MissingPermission")
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val scan = result[Manifest.permission.BLUETOOTH_SCAN] == true
            val connect = result[Manifest.permission.BLUETOOTH_CONNECT] == true
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Эта штука ругается в линте на отсутствие проверки разрешений
                // Добавил пока suppress ошибки, странно, что с ней тоже билдится без проблем
                onPermissionsResult(scanGranted = scan, connectGranted = connect)
            }
        }

    @SuppressLint("MissingPermission")
    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED && viewModel.isBluetoothEnabled
            ) {
                viewModel.loadBondedDevices()
                startScanChecked()
            }
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
                    onScan = { startScanChecked() },
                    onStopScan = { stopScanChecked() },
                    onConnect = { device -> connectChecked(device) },
                    onSaveSelected = { device -> viewModel.saveSelected(device) },
                    onEnableBluetooth = { enableBluetoothChecked() },
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onPermissionsResult(scanGranted: Boolean, connectGranted: Boolean) {
        if (!connectGranted) {
            Log.w("BLE", "BLUETOOTH_CONNECT denied")
        }
        if (!viewModel.isBluetoothEnabled) {
            Log.d("BLE", "Bluetooth disabled")
            return
        }
        if (connectGranted) viewModel.loadBondedDevices()
        if (scanGranted) startScanChecked() else Log.w("BLE", "BLUETOOTH_SCAN denied")
    }

    private fun startScanChecked() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startScan()
        } else {
            requestBtPermissions()
        }
    }

    private fun stopScanChecked() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            viewModel.stopScan()
        }
    }

    private fun connectChecked(device: BleDeviceUi) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            viewModel.connect(device)
        } else {
            requestBtPermissions()
        }
    }

    /** Показать системный диалог включения Bluetooth (требует BLUETOOTH_CONNECT на API 31+). */
    private fun enableBluetoothChecked() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            requestBtPermissions()
        }
    }
}
