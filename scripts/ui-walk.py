#!/usr/bin/env python3
"""Recorre la aplicación instalada en un emulador y **verifica lo que afirma** sobre la pantalla.

Por qué existe: hay fallos que solo se ven mirando (un título dibujado letra a letra, botones sin
texto, un tema que no cambia). Esta máquina no tiene pantalla ni dispositivo, así que el recorrido
corre en CI sobre el **APK de release**, guarda capturas y además mide: si se elige «Claro», la
pantalla tiene que iluminarse de verdad; si no cambia, el recorrido falla.

Uso:  python3 scripts/ui-walk.py <carpeta-de-salida>
Requiere: adb en el PATH y la aplicación instalada (el workflow la instala antes).
"""

import hashlib
import os
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.hifiplayer"
ACTIVITY = f"{PACKAGE}/com.hifiplayer.ui.MainActivity"
SCREEN_W, SCREEN_H = 320, 640  # emulador de CI; solo se usa para gestos y muestreo

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


def nodes(root: ET.Element):
    return list(root.iter("node"))


def find_all(root: ET.Element, wanted: str, exact: bool = False):
    """Nodos cuyo texto coincide, del más corto al más largo.

    El orden importa: uiautomator pega a veces los textos de un contenedor, así que buscando
    «Claro» el primer resultado puede ser un párrafo entero. El nodo más corto que contiene el texto
    es el chip, que es lo que se quiere pulsar.
    """
    result = []
    for node in nodes(root):
        text = (node.get("text") or "").strip()
        if (text == wanted) if exact else (wanted.lower() in text.lower()):
            result.append(node)
    return sorted(result, key=lambda n: len((n.get("text") or "")))


def scroll_up(times: int = 1) -> None:
    for _ in range(times):
        sh(f"adb shell input swipe {SCREEN_W // 2} 200 {SCREEN_W // 2} 520 250")
        time.sleep(1.0)


def scroll_until(wanted: str, max_swipes: int = 12, exact: bool = False) -> bool:
    """Busca el texto bajando y, si no está, subiendo.

    Hay secciones por encima de otras (Crossfeed está antes que Apariencia en Ajustes), así que
    buscar solo hacia abajo dejaba pantallas sin visitar.
    """
    for direction in (scroll_down, scroll_up):
        for _ in range(max_swipes):
            if find_all(dump(), wanted, exact=exact):
                return True
            direction(1)
    return False


def tap_node(node) -> None:
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds")))
    sh(f"adb shell input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(1.6)


def tap_text(wanted: str, index: int = 0, exact: bool = False) -> bool:
    found = find_all(dump(), wanted, exact=exact)
    if len(found) <= index:
        failures.append(f"no encontré «{wanted}» (posición {index}) en la pantalla")
        return False
    tap_node(found[index])
    return True


def scroll_down(times: int = 1) -> None:
    for _ in range(times):
        sh(f"adb shell input swipe {SCREEN_W // 2} 520 {SCREEN_W // 2} 200 250")
        time.sleep(1.0)


def screenshot(name: str) -> str:
    """Captura la pantalla, esperando a que el fotograma esté asentado.

    Se toma dos veces: con la GPU por software, la primera captura cae a veces a mitad de una
    transición y salen dos pantallas superpuestas (se veía contenido desplazado encima del título,
    y en la jerarquía de la interfaz ese contenido no estaba). La segunda siempre está limpia.
    """
    path = f"{out_dir}/{name}.png"
    sh(f"adb exec-out screencap -p > {path}")
    time.sleep(1.2)
    sh(f"adb exec-out screencap -p > {path}")
    notes.append(f"captura {name}.png")
    return path


def save_dump(name: str) -> list[str]:
    root = dump()
    all_texts = [t for t in ((n.get("text") or "").strip() for n in root.iter("node")) if t]
    with open(f"{out_dir}/{name}.txt", "w") as handle:
        handle.write("\n".join(all_texts))
    return all_texts


def mean_brightness() -> float:
    """Brillo medio de la franja central de la pantalla, leído del fotograma en bruto.

    Se usa el fotograma en bruto (no el PNG) porque no hace falta ninguna biblioteca: `screencap`
    devuelve ancho, alto, formato y los píxeles. La franja central evita la barra de estado (donde
    está el reloj, que cambia solo) y la barra de navegación.
    """
    raw = subprocess.run("adb exec-out screencap", shell=True, capture_output=True).stdout
    width, height, _format = struct.unpack("<III", raw[:12])
    pixels = raw[12:]
    start_row, end_row = height // 3, (height * 2) // 3
    total = 0
    count = 0
    for y in range(start_row, end_row):
        row_start = y * width * 4
        for x in range(0, width, 4):  # una de cada cuatro columnas basta para la media
            offset = row_start + x * 4
            total += pixels[offset + 1]  # canal verde
            count += 1
    return total / max(count, 1)


def file_digest(path: str) -> str:
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()[:12]


# ------------------------------------------------------------------ recorrido
sh(f"adb shell am start -W -n {ACTIVITY} > /dev/null")
time.sleep(6)
screenshot("01-inicio")

dark_brightness = None
light_brightness = None

# --- Ajustes (la pestaña de abajo, texto exacto) ---
if tap_text("Ajustes", exact=True):
    time.sleep(1)
    screenshot("02-ajustes")
    save_dump("02-ajustes")

    # --- Apariencia: hay que bajar hasta ella ---
    if not scroll_until("Apariencia", max_swipes=8):
        failures.append("no llegué a la sección «Apariencia»")
    else:
        screenshot("03-apariencia")
        visible = save_dump("03-apariencia")

        # El fallo reportado: botones de tema dibujados pero SIN TEXTO.
        theme_options = ["Negro puro", "Oscuro", "Claro", "Seguir al sistema"]
        missing = [o for o in theme_options if not any(o.lower() in t.lower() for t in visible)]
        if missing:
            failures.append(f"las opciones de tema no tienen texto en pantalla: {missing}")
        else:
            notes.append("las cuatro opciones de tema tienen texto")

        # --- El tema tiene que cambiar DE VERDAD, medido en píxeles ---
        if tap_text("Oscuro", index=0):
            time.sleep(1.5)
            dark_brightness = mean_brightness()
            screenshot("04-tema-oscuro")

        if tap_text("Claro", index=0):
            time.sleep(1.5)
            light_brightness = mean_brightness()
            screenshot("05-tema-claro")

        if dark_brightness is not None and light_brightness is not None:
            notes.append(f"brillo medio: oscuro {dark_brightness:.1f} · claro {light_brightness:.1f}")
            if light_brightness - dark_brightness < 30:
                failures.append(
                    "elegir «Claro» NO ilumina la pantalla "
                    f"(oscuridad {dark_brightness:.1f} vs claridad {light_brightness:.1f}): el tema no cambia",
                )
            else:
                notes.append("«Claro» cambia el tema de verdad")

        # --- Negro puro tiene que ser distinto de Oscuro ---
        if tap_text("Negro puro", index=0) and dark_brightness is not None:
            time.sleep(1.5)
            pure = mean_brightness()
            screenshot("06-tema-negro-puro")
            notes.append(f"brillo medio con negro puro: {pure:.1f}")
            if abs(pure - dark_brightness) < 1.0:
                failures.append("«Negro puro (OLED)» no cambia nada respecto a «Oscuro»")
            else:
                notes.append("«Negro puro» es distinto de «Oscuro»")
            tap_text("Oscuro", index=0)  # se deja el tema del producto

# --- Crossfeed: ahí viven Balance y los avisos que salían en rojo ---
if scroll_until("Crossfeed", max_swipes=12) and tap_text("Crossfeed", index=0):
    time.sleep(2)
    screenshot("07-crossfeed")
    seen = save_dump("07-crossfeed")
    for expected in ["Balance", "Nivel", "Ganancia de la app"]:
        if any(expected.lower() in t.lower() for t in seen):
            notes.append(f"Crossfeed: «{expected}» está en pantalla")
        else:
            notes.append(f"Crossfeed: no vi «{expected}» sin desplazar (puede estar más abajo)")
    sh("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1)

# --- Ecualizador ---
if scroll_until("Ecualizador paramétrico") and tap_text("Ecualizador paramétrico", index=0):
    time.sleep(2)
    screenshot("08-ecualizador")
    save_dump("08-ecualizador")
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
print("\nSin fallos: lo comprobado en pantalla coincide con lo que la app dice.")
