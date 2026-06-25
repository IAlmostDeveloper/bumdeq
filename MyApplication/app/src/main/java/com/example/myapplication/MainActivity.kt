package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        requestBtPermissions()

        if (!bluetoothAdapter.isEnabled) {
            Log.d("BLE", "Bluetooth disabled")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        startScan()


        val bondedDevices = bluetoothAdapter.bondedDevices
        for (device in bondedDevices) {
            Log.d("BT", "${device.name} : ${device.address}")
        }

        enableEdgeToEdge()
        setContent {
            DeviceDropdown(
                devices = bondedDevices.toList()
            )
        }
    }

    @Composable
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun DeviceDropdown(devices: List<BluetoothDevice>) {
        var expanded by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf("") }
        var context = this;
        Column {
            Button(onClick = { expanded = true }) {
                Text(selected.ifEmpty { "Select device" })
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Text("${device.name ?: "Unknown"} (${device.address})")
                        },
                        onClick = {
                            selected = device.name
                            expanded = false
                            gatt = device.connectGatt(context, false, gattCallback)
                        }
                    )
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d("BLE", "status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                for (service in gatt.services) {
                    Log.d("BLE", "Service UUID: ${service.uuid}")

                    for (characteristic in service.characteristics) {
                        Log.d("BLE", "  Characteristic UUID: ${characteristic.uuid}")
                    }
                }
            }
            val service = gatt.getService(
                UUID.fromString("0000baad-0000-1000-8000-00805f9b34fb")
            )

            val characteristic = service.getCharacteristic(
                UUID.fromString("0000f00d-0000-1000-8000-00805f9b34fb")
            )

            gatt.setCharacteristicNotification(characteristic, true)

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                // Дескриптор BLE определен значением из госта Bluetooth SIG
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )

                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }, 200)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("BLE", "Descriptor write status = $status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value
            val text = String(bytes, Charsets.UTF_8)

            Log.d("BLE", "Received: $text")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bleScanner.stopScan(scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(null, scanSettings, scanCallback)

        Log.d("BLE", "Scanning started")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            val name = try {
                device.name ?: "Unknown"
            } catch (e: SecurityException) {
                "Permission denied"
            }

            Log.d(
                "BLE",
                "Device: $name, MAC: ${device.address}, RSSI: ${result.rssi}"
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed: $errorCode")
        }
    }

    private fun requestBtPermissions() {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            1
        )
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}