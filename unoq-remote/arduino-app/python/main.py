# UNO Q Remote - Linux side.
# Serves a small HTTP API (port 7000) for the Android app and forwards
# every request to the MCU sketch through the Bridge.

import functools
import threading
from arduino.app_utils import App, Bridge
from arduino.app_bricks.web_ui import WebUI

DIGITAL = [f"D{i}" for i in range(14)]
ANALOG = [f"A{i}" for i in range(6)]
PWM = ["D3", "D5", "D6", "D9", "D10", "D11"]
MODES = {"input": 0, "output": 1, "input_pullup": 2}

_lock = threading.Lock()
state = {
    "modes": {p: "input" for p in DIGITAL},
    "out": {p: 0 for p in DIGITAL},
    "pwm": {p: 0 for p in PWM},
    "led3": [0, 0, 0],
    "led4": [0, 0, 0],
}


def _call(name, *args):
    with _lock:
        return Bridge.call(name, *args)


def _pin(pin: str, allowed) -> str:
    p = pin.strip().upper()
    if p not in allowed:
        raise ValueError(f"unknown pin {pin!r}")
    return p


def _safe(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            return {"ok": True, **(fn(*args, **kwargs) or {})}
        except Exception as e:  # report errors to the app instead of HTTP 500
            return {"ok": False, "error": str(e)}
    return wrapper


@_safe
def info():
    return {"app": "unoq-remote", "version": 1, "digital": DIGITAL,
            "analog": ANALOG, "pwm": PWM}


@_safe
def status():
    return {"state": state}


@_safe
def set_mode(pin: str, mode: str):
    p = _pin(pin, DIGITAL + ANALOG)
    m = mode.strip().lower()
    if m not in MODES:
        raise ValueError(f"mode must be one of {list(MODES)}")
    _call("set_mode", p, MODES[m])
    if p in DIGITAL:
        state["modes"][p] = m
    return {"pin": p, "mode": m}


@_safe
def digital_write(pin: str, value: int):
    p = _pin(pin, DIGITAL)
    v = 1 if value else 0
    if state["modes"][p] != "output":
        _call("set_mode", p, 1)
        state["modes"][p] = "output"
    _call("dwrite", p, v)
    state["out"][p] = v
    return {"pin": p, "value": v}


@_safe
def digital_read(pin: str):
    p = _pin(pin, DIGITAL)
    return {"pin": p, "value": _call("dread", p)}


@_safe
def digital_read_all():
    return {"values": {p: _call("dread", p) for p in DIGITAL}}


@_safe
def pwm_write(pin: str, value: int):
    p = _pin(pin, PWM)
    v = max(0, min(255, int(value)))
    if state["modes"][p] != "output":
        _call("set_mode", p, 1)
        state["modes"][p] = "output"
    _call("pwm", p, v)
    state["pwm"][p] = v
    return {"pin": p, "value": v}


@_safe
def analog_read(pin: str):
    p = _pin(pin, ANALOG)
    return {"pin": p, "value": _call("aread", p)}


@_safe
def analog_read_all():
    return {"values": {p: _call("aread", p) for p in ANALOG}}


@_safe
def led(led: int, r: int = 0, g: int = 0, b: int = 0):
    if led not in (3, 4):
        raise ValueError("led must be 3 or 4")
    rgb = [1 if r else 0, 1 if g else 0, 1 if b else 0]
    _call("rgb", led, *rgb)
    state[f"led{led}"] = rgb
    return {"led": led, "rgb": rgb}


ui = WebUI()
for path, fn in [
    ("/api/info", info),
    ("/api/status", status),
    ("/api/mode", set_mode),
    ("/api/digital/write", digital_write),
    ("/api/digital/read", digital_read),
    ("/api/digital/all", digital_read_all),
    ("/api/pwm", pwm_write),
    ("/api/analog/read", analog_read),
    ("/api/analog/all", analog_read_all),
    ("/api/led", led),
]:
    ui.expose_api("GET", path, fn)

App.run()
