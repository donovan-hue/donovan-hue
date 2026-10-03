#!/usr/bin/env bash
# Compila y prueba el proyecto. Compatible con bash y con sh (dash):
#   ./scripts/build.sh                       # núcleo + datos + tests de DSP
#   ./scripts/build.sh --all                 # lo mismo (explícito)
#   ./scripts/build.sh :app:assembleDebug    # cualquier tarea de Gradle
set -eu
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/home/user/.cache/tools/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-/home/user/.cache/tools/android-sdk}"
PATH="$JAVA_HOME/bin:$PATH"
export PATH

TASKS=":native:dsp:test :core:database:compileDebugKotlin :core:storage:compileDebugKotlin :core:metadata:compileDebugKotlin :core:permissions:compileDebugKotlin :core:usb:compileDebugKotlin :core:audio:compileDebugKotlin :data:local:compileDebugKotlin :data:metadata:compileDebugKotlin :data:repository:compileDebugKotlin :data:audio:compileDebugKotlin :native:audio_engine:compileDebugKotlin"

if [ "$#" -eq 0 ] || [ "${1:-}" = "--all" ]; then
  # shellcheck disable=SC2086
  set -- $TASKS
fi

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Falta el JDK en $JAVA_HOME; ejecuta primero: sh scripts/install-toolchain.sh" >&2
  exit 1
fi

exec sh ./gradlew "$@" --console=plain
