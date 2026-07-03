package ru.ialmostdeveloper.bumdeq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import ru.ialmostdeveloper.bumdeq.ui.bluetooth.BleDeviceUi
import ru.ialmostdeveloper.bumdeq.ui.bluetooth.BleScreenState
import ru.ialmostdeveloper.bumdeq.ui.bluetooth.BluetoothContent
import ru.ialmostdeveloper.bumdeq.ui.bluetooth.Measurement
import java.util.UUID
import kotlinx.coroutines.launch

/** Вкладки нижнего меню. BLE живёт во вкладке [DEVICES], данные с него — в [MEASUREMENTS]. */
private enum class MainTab(val title: String, val icon: ImageVector) {
    DEVICES("Устройства", Icons.Filled.Search),
    MEASUREMENTS("Замеры", Icons.AutoMirrored.Filled.List),
    RESULTS("Результаты", Icons.Filled.CheckCircle),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

/**
 * Корневой экран с нижней навигацией ([NavigationBar]). Держит один общий [Scaffold]
 * с topBar, bottomBar и FAB; контент вкладок рисуется внутри его paddings, без вложенных
 * Scaffold.
 *
 * Запуск замера со вкладки «Замеры» открывает отдельную полноэкранную страницу (без нижней
 * навигации) — её состояние [activeMeasurement] поднято над ветвлением, чтобы возврат
 * назад сохранял выбранную вкладку.
 *
 * Колбэки BLE пробрасываются во вкладку [BluetoothContent] как есть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    bleState: BleScreenState,
    measurement: Measurement?,
    savedResults: List<CriticalForceResult>,
    onSaveResult: (CriticalForceResult) -> Unit,
    onDeleteResult: (UUID) -> Unit,
    onExportJson: () -> String,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceUi) -> Unit,
    onSaveSelected: (BleDeviceUi) -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.DEVICES) }
    var activeMeasurement by rememberSaveable { mutableStateOf<MeasurementType?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Запущенный замер — отдельная страница поверх вкладок. Системная «Назад» возвращает к ним.
    val active = activeMeasurement
    if (active != null) {
        BackHandler { activeMeasurement = null }
        when (active) {
            MeasurementType.CRITICAL_FORCE -> CriticalForceScreen(
                measurement = measurement,
                onSaved = onSaveResult,
                onBack = { activeMeasurement = null },
            )
        }
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(selectedTab.title) }) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            // Запуск замеров доступен только на вкладке «Замеры».
            if (selectedTab == MainTab.MEASUREMENTS) {
                FloatingActionButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Запустить замер")
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            MainTab.DEVICES -> BluetoothContent(
                state = bleState,
                onScan = onScan,
                onStopScan = onStopScan,
                onConnect = onConnect,
                onSaveSelected = onSaveSelected,
                onEnableBluetooth = onEnableBluetooth,
                contentPadding = innerPadding,
            )

            MainTab.MEASUREMENTS -> MeasurementContent(
                measurement = measurement,
                contentPadding = innerPadding,
            )

            MainTab.RESULTS -> ResultsContent(
                results = savedResults,
                onDelete = onDeleteResult,
                onExportJson = onExportJson,
                contentPadding = innerPadding,
            )

            MainTab.SETTINGS -> SettingsContent(
                contentPadding = innerPadding,
            )
        }
    }

    // Окно выбора замера для запуска.
    if (showPicker) {
        MeasurementPickerSheet(
            onDismiss = { showPicker = false },
            onSelect = { type ->
                showPicker = false
                activeMeasurement = type
            },
        )
    }
}

/** Bottom sheet со списком доступных для запуска замеров. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (MeasurementType) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        // Свайп / тап по затемнению — Material3 проигрывает скрытие сам, затем зовёт onDismiss.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "Доступные замеры",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        MeasurementType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Программный выбор: сначала анимируем скрытие листа, и только по
                        // завершении навигируем — иначе sheetState не задействован и лист
                        // исчезает рывком (showPicker=false выдёргивает его из композиции).
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) onSelect(type)
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(type.title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(16.dp)) // отступ от системной навигации снизу
    }
}
