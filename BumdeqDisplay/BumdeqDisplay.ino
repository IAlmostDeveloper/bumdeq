#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <SPI.h>
#include <MD_Parola.h>
#include <MD_MAX72XX.h>

extern "C" {
  #include <espnow.h>
}

// Текущее значение для вывода и флаг необходимости перерисовки.
// Заполняется в MQTT-колбэке, отрисовывается в loop() — чтобы не делать
// SPI-обмен внутри обработчика прерывания/колбэка.
char displayText[16] = "----";
bool needsRedraw     = true;

void onReceive(uint8_t *mac, uint8_t *incomingData, uint8_t len) {
  if (len != sizeof(float)) {
    Serial.println("Wrong packet size");
    return;
  }

  float value;
  memcpy(&value, incomingData, sizeof(value));

  Serial.print("Received float: ");
  needsRedraw = true;
  snprintf(displayText, sizeof(displayText), "%.2f", value);
  Serial.println(value);
}

// ─────────────────────────── Настройки Wi-Fi ───────────────────────────
const char* WIFI_SSID     = "ProNote986";
const char* WIFI_PASSWORD = "123456788";

// ─────────────────────────── Настройки MQTT ────────────────────────────
const char*    MQTT_HOST      = "10.209.116.128";   // адрес брокера
const uint16_t MQTT_PORT      = 1883;
const char*    MQTT_USER      = "";                // пусто, если брокер без авторизации
const char*    MQTT_PASSWORD  = "";
const char*    MQTT_TOPIC     = "matrix/number";   // топик, откуда приходит число
const char*    MQTT_CLIENT_ID = "esp8266-matrix";

// ───────────────────────── Настройки матриц MAX7219 ─────────────────────
#define HARDWARE_TYPE MD_MAX72XX::FC16_HW
#define MAX_DEVICES 16

#define CLK_PIN   14
#define DATA_PIN  13
#define CS_PIN    12
#define BRIGHTNESS    5                     // яркость 0..15

// ───────────────────────────── Глобальные объекты ──────────────────────
MD_Parola    display = MD_Parola(HARDWARE_TYPE, CS_PIN, MAX_DEVICES);
WiFiClient   wifiClient;
PubSubClient mqtt(wifiClient);



// ───────────────────────────── Вспомогательные функции ─────────────────

// Вывод короткой строки на матрицы (по центру).
void showText(const char* text) {
    display.displayClear();
    display.setTextAlignment(PA_CENTER);
    display.print(text);
}

// Подключение к Wi-Fi (блокирующее, с индикацией на матрицах).
void connectWiFi() {
    Serial.printf("Подключение к Wi-Fi: %s\n", WIFI_SSID);
    showText("WiFi");

    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }

    Serial.printf("\nWi-Fi подключён, IP: %s\n", WiFi.localIP().toString().c_str());
}

// Колбэк приёма сообщения из MQTT.
void onMqttMessage(char* topic, byte* payload, unsigned int length) {
    char incoming[sizeof(displayText)];
    unsigned int n = (length < sizeof(incoming) - 1) ? length : sizeof(incoming) - 1;
    memcpy(incoming, payload, n);
    incoming[n] = '\0';

    Serial.printf("MQTT [%s] = %s\n", topic, incoming);

    // Перерисовываем табло только если значение действительно изменилось:
    // при частом поступлении одинаковых чисел это убирает лишние SPI-обновления
    // и мерцание экрана.
    if (strcmp(incoming, displayText) != 0) {
        strcpy(displayText, incoming);
        needsRedraw = true;
    }
}

// Одна попытка подключения к брокеру MQTT и подписка на топик.
void connectMqtt() {
    Serial.printf("Подключение к MQTT %s:%u ...\n", MQTT_HOST, MQTT_PORT);
    showText("MQTT");

    bool connected = (strlen(MQTT_USER) > 0)
        ? mqtt.connect(MQTT_CLIENT_ID, MQTT_USER, MQTT_PASSWORD)
        : mqtt.connect(MQTT_CLIENT_ID);

    if (connected) {
        Serial.println("MQTT подключён");
        mqtt.subscribe(MQTT_TOPIC);
        Serial.printf("Подписка на топик: %s\n", MQTT_TOPIC);
        needsRedraw = true;   // вернуть последнее значение на экран
    } else {
        Serial.printf("Ошибка MQTT, rc=%d. Повтор через 5 с\n", mqtt.state());
        delay(5000);
    }
}

// ───────────────────────────────── setup ───────────────────────────────
void setup() {
    Serial.begin(115200);
    Serial.println("\nStarting MQTT_Matrix_Display");
Serial.print("MAC address: ");
  Serial.println(WiFi.macAddress());
    // Инициализация матриц
    display.begin();
    display.setIntensity(BRIGHTNESS);
    display.displayClear();
    showText(displayText);

  WiFi.mode(WIFI_STA);

  if (esp_now_init() != 0) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_set_self_role(ESP_NOW_ROLE_SLAVE);
  esp_now_register_recv_cb(onReceive);
    // connectWiFi();

    // mqtt.setServer(MQTT_HOST, MQTT_PORT);
    // mqtt.setCallback(onMqttMessage);
}

// ───────────────────────────────── loop ────────────────────────────────
void loop() {
    // if (WiFi.status() != WL_CONNECTED) {
    //     connectWiFi();
    // }

    // if (!mqtt.connected()) {
    //     connectMqtt();
    // } else {
    //     mqtt.loop();
    // }

    if (needsRedraw) {
        showText(displayText);
        needsRedraw = false;
    }
}
