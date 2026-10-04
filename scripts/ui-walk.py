#!/usr/bin/env python3
"""Recorre la aplicación instalada en un emulador y guarda capturas y volcados de la interfaz.

Por qué existe: hay fallos que solo se ven mirando la pantalla (botones sin texto, avisos pintados
como errores, un tema que no cambia). Esta máquina no tiene pantalla ni dispositivo, así que el
recorrido corre en CI sobre el **APK de release** y sube las capturas como artefacto: así se puede
comprobar con los ojos lo que se afirma, en vez de darlo por bueno porque compila.

Uso:  python3 scripts/ui-walk.py <carpeta-de-salida>
Requiere: adb en el PATH y la aplicación instalada (el workflow la instala antes).
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.hifiplayer"
ACTIVITY = f"{PACKAGE}/com.hifiplayer.ui.MainActivity"

out_dir = sys.argv[1] if len(sys.argv) > 1 else "ui-shots"
os.makedirs(out_dir, exist_ok=True)

failures: list[str] = []
notes: list[str] = []


def sh(cmd: str) -> str:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


def dump() -> ET.Element:
    """Vuelca la jerarquía de la interfaz y la devuelve como árbol."""
    sh("adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1")
    sh("adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1")
    return ET.parse("/tmp/ui.xml").getroot()


def texts(root: ET.Element) -> list[str]:
    return [n.get("text", "") for n in root.iter("node") if n.get("text")]


def find(root: ET.Element, wanted: str):
    """Nodo cuyo texto contiene el buscado (la interfaz usa etiquetas largas)."""
    for node in root.iter("node"):
        if wanted.lower() in (node.get("text") or "").lower():
            return node
    return None


def tap(node) -> None:
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds")))
    sh(f"adb shell input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(1.5)


def tap_text(wanted: str) -> bool:
    node = find(dump(), wanted)
    if node is None:
        failures.append(f"no encontré «{wanted}» en la pantalla")
        return False
    tap(node)
    return True


def scroll_down(times: int = 1) -> None:
    for _ in range(times):
        sh("adb shell input swipe 540 1600 540 600 250")
        time.sleep(1.0)


def screenshot(name: str) -> None:
    sh(f"adb exec-out screencap -p > {out_dir}/{name}.png")
    notes.append(f"captura {name}.png")


def save_dump(name: str) -> list[str]:
    root = dump()
    all_texts = texts(root)
    with open(f"{out_dir}/{name}.txt", "w") as handle:
        handle.write("\n".join(all_texts))
    return all_texts


# ------------------------------------------------------------------ recorrido
sh(f"adb shell am start -W -n {ACTIVITY} > /dev/null")
time.sleep(6)
screenshot("01-inicio")

# --- Ajustes ---
if tap_text("Ajustes"):
    time.sleep(1)
    screenshot("02-ajustes")
    save_dump("02-ajustes")

    # --- Apariencia: la fila del tema y sus botones ---
    scroll_down(2)
    time.sleep(1)
    screenshot("03-apariencia")
    visible = save_dump("03-apariencia")

    # El fallo reportado: botones de tema dibujados pero SIN TEXTO.
    theme_options = ["Negro puro", "Oscuro", "Claro", "Seguir al sistema"]
    missing = [option for option in theme_options if not any(option.lower() in t.lower() for t in visible)]
    if missing:
        failures.append(f"las opciones de tema no tienen texto en pantalla: {missing}")
    else:
        notes.append("las cuatro opciones de tema tienen texto: " + ", ".join(theme_options))

    # --- Cambiar a claro y comprobar que la pantalla cambia de verdad ---
    if tap_text("Claro"):
        time.sleep(2)
        screenshot("04-tema-claro")
        save_dump("04-tema-claro")
        notes.append("pulsé «Claro»: comparar 03 y 04 para ver si el fondo cambió")
    if tap_text("Negro puro"):
        time.sleep(2)
        screenshot("05-tema-negro-puro")
        notes.append("pulsé «Negro puro (OLED)»")

# --- Crossfeed (contiene Balance y era donde salían los avisos en rojo) ---
scroll_down(3)
if tap_text("Crossfeed"):
    time.sleep(1)
    screenshot("06-crossfeed")
    save_dump("06-crossfeed")
    sh("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1)

# --- Ecualizador ---
scroll_down(2)
if tap_text("Ecualizador"):
    time.sleep(1)
    screenshot("07-ecualizador")
    save_dump("07-ecualizador")
    sh("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1)

# ------------------------------------------------------------------ resumen
print("\n=== RECORRIDO DE LA INTERFAZ ===")
for note in notes:
    print("  ·", note)
if failures:
    print("\nFALLOS:")
    for failure in failures:
        print("  ✗", failure)
    sys.exit(1)
print("\nSin fallos de interfaz en los puntos comprobados.")
