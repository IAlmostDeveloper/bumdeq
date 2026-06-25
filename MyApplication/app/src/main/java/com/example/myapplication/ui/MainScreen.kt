package com.example.myapplication.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.myapplication.ui.bluetooth.BleDeviceUi
import com.example.myapplication.ui.bluetooth.BleScreenState
import com.example.myapplication.ui.bluetooth.BluetoothContent
import com.example.myapplication.ui.bluetooth.Measurement

/** Вкладки нижнего меню. BLE живёт во вкладке [DEVICES], данные с него — в [MEASUREMENTS]. */
private enum class MainTab(val title: String, val icon: ImageVector) {
    DEVICES("Устройства", Icons.Filled.Search),
    MEASUREMENTS("Замеры", Icons.AutoMirrored.Filled.List),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

/**
 * Корневой экран с нижней навигацией ([NavigationBar]). Держит один общий [Scaffold]
 * с topBar и bottomBar; контент вкладок рисуется внутри его paddings, без вложенных
 * Scaffold. Подключение к BLE-устройствам вынесено во вкладку «Устройства».
 *
 * Колбэки BLE пробрасываются во вкладку [BluetoothContent] как есть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    bleState: BleScreenState,
    measurement: Measurement?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceUi) -> Unit,
    onSaveSelected: (BleDeviceUi) -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.DEVICES) }

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

            MainTab.SETTINGS -> PlaceholderTab(
                text = "Раздел в разработке",
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun PlaceholderTab(text: String, contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}
