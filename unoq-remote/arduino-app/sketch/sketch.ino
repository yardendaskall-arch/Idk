// UNO Q Remote - MCU side (STM32U585).
// Exposes simple pin functions to the Linux side through the Arduino Bridge.

#include <Arduino_RouterBridge.h>

struct PinEntry { const char* name; uint8_t pin; };

static const PinEntry kPins[] = {
  {"D0", D0}, {"D1", D1}, {"D2", D2}, {"D3", D3}, {"D4", D4}, {"D5", D5},
  {"D6", D6}, {"D7", D7}, {"D8", D8}, {"D9", D9}, {"D10", D10}, {"D11", D11},
  {"D12", D12}, {"D13", D13},
  {"A0", A0}, {"A1", A1}, {"A2", A2}, {"A3", A3}, {"A4", A4}, {"A5", A5},
};
static const size_t kPinCount = sizeof(kPins) / sizeof(kPins[0]);

static int findPin(const String& name) {
  for (size_t i = 0; i < kPinCount; ++i) {
    if (name.equalsIgnoreCase(kPins[i].name)) return kPins[i].pin;
  }
  return -1;
}

// mode: 0 = INPUT, 1 = OUTPUT, 2 = INPUT_PULLUP
bool set_mode(String name, int mode) {
  int p = findPin(name);
  if (p < 0) return false;
  pinMode(p, mode == 1 ? OUTPUT : (mode == 2 ? INPUT_PULLUP : INPUT));
  return true;
}

bool dwrite(String name, int value) {
  int p = findPin(name);
  if (p < 0) return false;
  digitalWrite(p, value ? HIGH : LOW);
  return true;
}

int dread(String name) {
  int p = findPin(name);
  if (p < 0) return -1;
  return digitalRead(p);
}

bool pwm(String name, int value) {
  int p = findPin(name);
  if (p < 0) return false;
  analogWrite(p, constrain(value, 0, 255));
  return true;
}

int aread(String name) {
  int p = findPin(name);
  if (p < 0) return -1;
  return analogRead(p);
}

// Built-in RGB LEDs on the MCU side: LED3 and LED4 (active LOW).
bool rgb(int led, int r, int g, int b) {
  int base;
  if (led == 3) base = LED_BUILTIN;
  else if (led == 4) base = LED_BUILTIN + 3;
  else return false;
  digitalWrite(base,     r ? LOW : HIGH);
  digitalWrite(base + 1, g ? LOW : HIGH);
  digitalWrite(base + 2, b ? LOW : HIGH);
  return true;
}

void setup() {
  for (int i = 0; i < 6; ++i) {
    pinMode(LED_BUILTIN + i, OUTPUT);
    digitalWrite(LED_BUILTIN + i, HIGH);  // off
  }

  Bridge.begin();
  Bridge.provide("set_mode", set_mode);
  Bridge.provide("dwrite", dwrite);
  Bridge.provide("dread", dread);
  Bridge.provide("pwm", pwm);
  Bridge.provide("aread", aread);
  Bridge.provide("rgb", rgb);
}

void loop() {}
