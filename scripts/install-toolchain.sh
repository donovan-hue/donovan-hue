#!/usr/bin/env bash
# Instala el toolchain de compilación en el sandbox (JDK 17 + Android SDK).
#
# Vive en /home/user/.cache/tools, que NO se conserva entre sesiones del workspace: si `./gradlew`
# dice que JAVA_HOME no existe o que no encuentra el SDK, esto es lo que hay que ejecutar.
set -eu

ROOT="${HIFI_TOOLS:-/home/user/.cache/tools}"
mkdir -p "$ROOT"
cd "$ROOT"

if [ ! -x "$ROOT/jdk/bin/java" ]; then
  echo "== JDK 17 =="
  curl -sL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar xzf jdk.tar.gz -C jdk --strip-components=1 && rm jdk.tar.gz
  "$ROOT/jdk/bin/java" -version
fi

if [ ! -x "$ROOT/android-sdk/platforms/android-36/android.jar" ]; then
  echo "== Android SDK =="
  if [ ! -x "$ROOT/android-sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -sL -o cmdline.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
    mkdir -p android-sdk/cmdline-tools
    unzip -q cmdline.zip -d android-sdk/cmdline-tools
    mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
    rm cmdline.zip
  fi
  export JAVA_HOME="$ROOT/jdk"
  SDKMANAGER="$ROOT/android-sdk/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKMANAGER" --sdk_root="$ROOT/android-sdk" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$ROOT/android-sdk" "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null
fi

echo "OK: $ROOT/jdk y $ROOT/android-sdk listos"
