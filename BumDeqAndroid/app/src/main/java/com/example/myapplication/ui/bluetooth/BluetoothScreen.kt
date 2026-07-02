package com.example.Bumdeq.ui.bluetooth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.Bumdeq.ui.theme.BumdeqTheme

/**
 * Содержимое экрана поиска / выбора / сохранения Bluetooth-устройств.
 *
 * Чистый UI: рисуется как функция от [state], все действия пробрасываются наверх через
 * колбэки. Без собственного Scaffold — рисуется внутри переданного [contentPadding]
 * общего Scaffold (см. MainScreen), чтобы избежать вложенных Scaffold.
 *
 * @param state            состояние экрана (списки, флаг сканирования, сохранённый MAC)
 * @param onScan           начать поиск новых устройств
 * @param onStopScan       остановить поиск
 * @param onConnect        подключиться к устройству
 * @param onSaveSelected   запомнить устройство как выбранное (DataStore)
 * @param onEnableBluetooth запрос на включение адаптера (показывается, когда BT выключен)
 */
@Composable
fun BluetoothContent(
    state: BleScreenState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceUi) -> Unit,
    onSaveSelected: (BleDeviceUi) -> Unit,
    onEnableBluetooth: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Баннер: Bluetooth выключен ───────────────────────────────────
        if (!state.bluetoothEnabled) {
            item { BluetoothDisabledBanner(onEnableBluetooth = onEnableBluetooth) }
        }

        // ── Секция 1: сопряжённые устройства ─────────────────────────────
        item {
            SectionHeader("Сопряжённые устройства")
        }
        if (state.bondedDevices.isEmpty()) {
            item { EmptyHint("Нет сопряжённых устройств") }
        } else {
            items(state.bondedDevices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    isSaved = device.address == state.savedAddress,
                    onConnect = { onConnect(device) },
                    onSave = { onSaveSelected(device) },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }

        // ── Секция 2: поиск новых устройств ──────────────────────────────
        item {
            ScanSectionHeader(
                isScanning = state.isScanning,
                scanEnabled = state.bluetoothEnabled,
                onScan = onScan,
                onStopScan = onStopScan,
            )
        }
        if (state.scannedDevices.isEmpty()) {
            item {
                EmptyHint(
                    if (state.isScanning) "Идёт поиск…" else "Нажмите «Поиск», чтобы найти устройства"
                )
            }
        } else {
            items(state.scannedDevices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    isSaved = device.address == state.savedAddress,
                    showRssi = true,
                    onConnect = { onConnect(device) },
                    onSave = { onSaveSelected(device) },
                )
            }
        }
    }
    }

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Заголовок секции поиска с кнопкой «Поиск» / «Стоп» и спиннером во время сканирования. */
@Composable
private fun ScanSectionHeader(
    isScanning: Boolean,
    scanEnabled: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Новые устройства",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (isScanning) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        if (isScanning) {
            OutlinedButton(onClick = onStopScan) { Text("Стоп") }
        } else {
            // При выключенном Bluetooth поиск заблокирован — иначе тихий no-op без обратной связи.
            Button(onClick = onScan, enabled = scanEnabled) { Text("Поиск") }
        }
    }
}

/** Баннер о выключенном Bluetooth с кнопкой включения. */
@Composable
private fun BluetoothDisabledBanner(onEnableBluetooth: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Bluetooth выключен",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onEnableBluetooth) { Text("Включить") }
        }
    }
}

/**
 * Строка устройства: имя, MAC, опционально RSSI, отметка «сохранено»,
 * и кнопка действия, чей вид зависит от [BleDeviceUi.state].
 */
@Composable
private fun DeviceRow(
    device: BleDeviceUi,
    isSaved: Boolean,
    onConnect: () -> Unit,
    onSave: () -> Unit,
    showRssi: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSaved) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isSaved) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "★ сохранено",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showRssi && device.rssi != null) {
                    Text(
                        text = "Сигнал: ${device.rssi} dBm  ${rssiBars(device.rssi)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            ConnectButton(state = device.state, onConnect = onConnect, onSave = onSave)
        }
    }
}

/** Кнопка действия зависит от состояния подключения устройства. */
@Composable
private fun ConnectButton(
    state: ConnectionState,
    onConnect: () -> Unit,
    onSave: () -> Unit,
) {
    when (state) {
        ConnectionState.DISCONNECTED ->
            Button(onClick = onConnect) { Text("Подключить") }

        ConnectionState.CONNECTING ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Подключение…", style = MaterialTheme.typography.labelMedium)
            }

        ConnectionState.CONNECTED ->
            OutlinedButton(onClick = onSave) { Text("Сохранить") }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Простая текстовая «шкала» уровня сигнала, чтобы не тянуть иконки. */
private fun rssiBars(rssi: Int): String = when {
    rssi >= -60 -> "▂▄▆█"
    rssi >= -70 -> "▂▄▆_"
    rssi >= -80 -> "▂▄__"
    else -> "▂___"
}

// ── Preview на mock-данных ───────────────────────────────────────────────────

private val previewState = BleScreenState(
    bondedDevices = listOf(
        BleDeviceUi("Galaxy Watch", "AA:BB:CC:DD:EE:01", state = ConnectionState.CONNECTED),
        BleDeviceUi("Heart Sensor", "AA:BB:CC:DD:EE:02", state = ConnectionState.CONNECTING),
        BleDeviceUi(null, "AA:BB:CC:DD:EE:03"),
    ),
    scannedDevices = listOf(
        BleDeviceUi("BLE Beacon", "11:22:33:44:55:66", rssi = -58),
        BleDeviceUi("Unknown", "77:88:99:AA:BB:CC", rssi = -74),
        BleDeviceUi(null, "DD:EE:FF:00:11:22", rssi = -89),
    ),
    isScanning = true,
    savedAddress = "AA:BB:CC:DD:EE:01",
)

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun BluetoothContentPreview() {
    BumdeqTheme {
        // Одноразовый Scaffold только для превью — в проде Scaffold даёт MainScreen.
        Scaffold(
            topBar = { TopAppBar(title = { Text("Bluetooth устройства") }) },
        ) { innerPadding ->
            BluetoothContent(
                state = previewState,
                onScan = {},
                onStopScan = {},
                onConnect = {},
                onSaveSelected = {},
                onEnableBluetooth = {},
                contentPadding = innerPadding,
            )
        }
    }
}
