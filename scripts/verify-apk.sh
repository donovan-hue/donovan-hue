#!/usr/bin/env sh
# ============================================================================
# verify-apk.sh — el APK, ¿puede siquiera arrancar?
#
# Comprueba que cada componente que el manifest nombra (Application, actividades,
# servicios y receptores) existe de verdad como clase dentro del dex del APK.
#
# Por qué existe: en la versión 0.17.0-alpha el manifest apuntaba a
# com.hifiplayer.app.HiFiPlayerApp y com.hifiplayer.app.ui.MainActivity, mientras
# las clases eran com.hifiplayer.HiFiPlayerApp y com.hifiplayer.ui.MainActivity
# (el `namespace` del módulo no coincide con los paquetes del código). El APK se
# instalaba y se cerraba al abrir. Ningún compilador lo detecta: los android:name
# son cadenas de texto y solo se resuelven en tiempo de ejecución.
#
# Esto NO sustituye a probarlo en un móvil; cierra el fallo más tonto y más fatal.
#
# uso: sh scripts/verify-apk.sh <ruta.apk>
# ============================================================================
set -eu

APK="${1:?uso: sh scripts/verify-apk.sh <ruta.apk>}"
[ -f "$APK" ] || { echo "No existe el APK: $APK" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  # El toolchain del proyecto lo deja aquí; es el mismo camino que usa build.sh.
  SDK="$HOME/.cache/tools/android-sdk"
fi
AAPT2=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
if [ -z "$AAPT2" ]; then
  echo "No encuentro aapt2 en $SDK/build-tools; instala el toolchain (sh scripts/install-toolchain.sh)" >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "APK: $APK"

# ---------------------------------------------------------------- identidad
"$AAPT2" dump badging "$APK" > "$WORK/badging.txt"
grep -E "^package:" "$WORK/badging.txt" | sed 's/^/  /'
LAUNCHABLE=$(grep -E "^launchable-activity:" "$WORK/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" || true)
if [ -z "$LAUNCHABLE" ]; then
  echo "  ✘ el APK no declara ninguna actividad lanzable: no aparecería en el cajón de apps" >&2
  exit 1
fi
echo "  actividad de entrada: $LAUNCHABLE"

# ------------------------------------------------- componentes del manifest
# Solo los *componentes*: el android:name de <application>, <activity>, <service>,
# <receiver> y <provider> es el único que nombra una clase. (El de <uses-permission>
# o <uses-feature> es un identificador, no una clase: confundirlos fue el primer
# error de este propio script.)
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" \
  | awk '
      /^[[:space:]]*E: / { element = $2 }
      /android:name\(0x01010003\)=/ {
        if (element == "application" || element == "activity" || element == "activity-alias" \
            || element == "service" || element == "receiver" || element == "provider") {
          match($0, /"[^"]+"/)
          print substr($0, RSTART + 1, RLENGTH - 2)
        }
      }' \
  | grep -E '^com\.hifiplayer\.' \
  | sort -u > "$WORK/declared.txt"
echo "  componentes declarados: $(wc -l < "$WORK/declared.txt" | tr -d ' ')"

# ------------------------------------------------------------- clases del dex
mkdir -p "$WORK/dex"
unzip -o -q "$APK" 'classes*.dex' -d "$WORK/dex"
if [ -z "$(ls -A "$WORK/dex")" ]; then
  echo "  ✘ el APK no contiene ningún dex" >&2
  exit 1
fi
cat "$WORK"/dex/classes*.dex > "$WORK/all.dex"

# ------------------------------------------------------------------ veredicto
missing=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  descriptor="L$(printf '%s' "$name" | tr '.' '/');"
  if strings "$WORK/all.dex" | grep -qF "$descriptor"; then
    echo "  ✔ $name"
  else
    echo "  ✘ NO EXISTE EN EL APK: $name" >&2
    missing=$((missing + 1))
  fi
done < "$WORK/declared.txt"

# La actividad de entrada también tiene que existir, aunque no empiece por com.hifiplayer.
entry_descriptor="L$(printf '%s' "$LAUNCHABLE" | tr '.' '/');"
if ! strings "$WORK/all.dex" | grep -qF "$entry_descriptor"; then
  echo "  ✘ NO EXISTE EN EL APK la actividad de entrada: $LAUNCHABLE" >&2
  missing=$((missing + 1))
fi

if [ "$missing" -gt 0 ]; then
  echo "" >&2
  echo "El APK se instalaría y se cerraría al abrir: $missing componente(s) del manifest no existen." >&2
  echo "Revisa que los android:name sean los nombres completos reales de las clases" >&2
  echo "(el namespace del módulo y los paquetes del código no tienen por qué coincidir)." >&2
  exit 1
fi

echo "  ✔ todos los componentes del manifest existen en el APK"

# ------------------------------------------------------------------- firma
# ¿Lleva la clave de depuración versionada? Si cada compilación firma con una clave distinta, la
# versión nueva no se puede instalar encima de la anterior y hay que desinstalar (perdiendo ajustes y
# biblioteca). La comprobación se salta cuando hay credenciales de producción configuradas: en ese
# caso la firma correcta es la otra, y compararla con la de depuración sería un falso positivo.
if [ -f ci/hifi-debug.keystore ] && [ ! -f keystore.properties ] && [ -z "${HIFI_KEYSTORE_FILE:-}" ]; then
  KEYTOOL="${JAVA_HOME:-}/bin/keytool"
  [ -x "$KEYTOOL" ] || KEYTOOL=$(command -v keytool || true)
  APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
  if [ -n "$KEYTOOL" ] && [ -n "$APKSIGNER" ]; then
    # keytool: "  SHA256: C0:4F:…"  ·  apksigner: "Signer #1 certificate SHA-256 digest: c04f…"
    expected=$("$KEYTOOL" -list -v -keystore ci/hifi-debug.keystore -storepass hifiplayer 2>/dev/null \
      | sed -n 's/.*SHA256:[[:space:]]*//p' | head -1 | tr -d ':\r' | tr 'A-Z' 'a-z')
    actual=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
      | sed -n 's/.*SHA-256 digest:[[:space:]]*//p' | head -1 | tr -d ':\r' | tr 'A-Z' 'a-z')
    if [ -n "$expected" ] && [ -n "$actual" ]; then
      if [ "$expected" = "$actual" ]; then
        echo "  ✔ firmado con la clave de depuración versionada (las actualizaciones se instalan encima)"
      else
        echo "  ✘ la firma no es la clave versionada en ci/hifi-debug.keystore" >&2
        echo "    esperada: $expected" >&2
        echo "    actual:   $actual" >&2
        echo "    Con una clave distinta en cada compilación, actualizar exige desinstalar." >&2
        missignature=1
      fi
    fi
  fi
fi

if [ "${missignature:-0}" = "1" ]; then
  exit 1
fi
