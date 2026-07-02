/**
 *  BumDeqDevice
 *
 *  Чтение значений тензодатчика через 24-битный АЦП HX711,
 *  перевод в килограммы и отправка измерений по Bluetooth и EspNow
 *
 *  Подключение:
 *    HX711 VCC -> 5V
 *    HX711 GND -> GND
 *    HX711 DT  -> ADC_DOUT_PIN
 *    HX711 SCK -> ADC_SCK_PIN
 */

#include "HX711.h"
#include "nimble.h"
#include <WiFi.h>
#include <esp_now.h>

uint8_t receiverMAC[] = {0xA8, 0x48, 0xFA, 0xDC, 0xC1, 0x3C};

esp_now_peer_info_t peerInfo;

void onSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  Serial.print("Send status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "OK" : "FAIL");
}

const uint8_t ADC_DT_PIN = 9;
const uint8_t ADC_SCK_PIN  = 10;

// Сколько выборок усреднять за одно измерение: больше — стабильнее, но медленнее.
const uint8_t SAMPLES_PER_READING = 10;
// Сколько выборок усреднять при тарировании (поиск нуля) на старте.
const uint8_t TARE_SAMPLES        = 20;

// COUNTS_PER_KG — количество отсчётов АЦП приходится на 1 кг нагрузки.
//   1. На старте скетч сам находит "ноль" (tare) без нагрузки
//   2. Вешаем (или ставим) на датчик известную нам массу (гантелю, гирю и т.д.)
//   3. Вычисляем COUNTS_PER_KG как (сигнал с датчика на весу - сигнал датчика без веса) / известная нам масса груза
// Пример: гиря 24кг, АЦП показывает без веса значение 429,495, а с весом 2,491,071, COUNTS_PER_KG=(2,491,071-429,495)/24
const float COUNTS_PER_KG = 85899.0f;

HX711 adc;
long  zeroOffset = 0;   // сырое значение при нулевой нагрузке (находится при тарировании)

float rawToKilograms(long raw) {
    return (float)(raw - zeroOffset) / COUNTS_PER_KG;
}

void setup() {
    Serial.begin(115200);
    Serial.println("Starting BumDeq Device, version 0.1");
    Serial.println("Starting BLE module");
    initNimBLEServer();

    Serial.println("Starting WIFI ESP-NOW module");
    WiFi.mode(WIFI_STA);

    if (esp_now_init() != ESP_OK) {
        Serial.println("ESP-NOW init failed");
        return;
    }

    esp_now_register_send_cb(onSent);

    memcpy(peerInfo.peer_addr, receiverMAC, 6);
    peerInfo.channel = 0;
    peerInfo.encrypt = false;

    if (esp_now_add_peer(&peerInfo) != ESP_OK) {
        Serial.println("Peer add failed");
        return;
    }

    Serial.println("Statring HX711");

    adc.begin(ADC_DT_PIN, ADC_SCK_PIN);

    // Тарирование: запоминаем сырое значение без нагрузки как "ноль".
    // wait_ready_timeout не даёт зависнуть, если датчик не отвечает.
    if (adc.wait_ready_timeout(1000)) {
        zeroOffset = adc.read_average(TARE_SAMPLES);
        Serial.print("Taring ended, ADC value on zero weight is ");
        Serial.println(zeroOffset);
    } else {
        Serial.println("HX711 not found - check your connection");
    }
}

void loop() {
    // is_ready() возвращает true, когда у HX711 готовы новые данные.
    // Без этой проверки read() блокирующе ждёт готовности датчика.
    if (adc.is_ready()) {
        long  reading = adc.read_average(SAMPLES_PER_READING);
        float kg      = rawToKilograms(reading);

        Serial.print(reading);
        Serial.print('\t');
        Serial.print(kg, 3);   // 3 знака после запятой
        Serial.println(" кг");

        ble_notify(kg);
        esp_now_send(
            receiverMAC,
            (uint8_t*)&kg,
            sizeof(kg)
        );
    } else {
        delay(300);
    }
}
