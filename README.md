# HiFi Player

Reproductor de música Hi-Fi para Android. **Solo archivos locales**: sin streaming, sin
publicidad, sin cuentas. Prioridad absoluta: **no degradar la señal** y **no afirmar nada
que no se haya verificado en el dispositivo**.

## Requisitos

| Herramienta | Versión |
|---|---|
| JDK | 17 |
| Android SDK | platform 36 + build-tools 36.0.0 |
| Gradle | wrapper incluido (8.14.5) |

```bash
export JAVA_HOME=/ruta/al/jdk-17
export ANDROID_HOME=/ruta/al/android-sdk
./gradlew :app:assembleDebug
```

La ruta del SDK también puede fijarse en `local.properties` (`sdk.dir=...`), archivo que **no se
versiona**.

## Arquitectura

Flujo de dependencias: `UI → ViewModel → UseCase → Repository → DataSource → (AudioEngine / Room / Storage)`.
La UI nunca toca Room, Media3, USB ni el sistema de archivos.

```
app/                     aplicación, navegación raíz, tema, firma, ProGuard
domain/model/            entidades y modelos de audio (sin dependencias de Android)
domain/repository/       contratos de repositorio
domain/usecase/          casos de uso
core/common/             Outcome, Logger, errores tipados, AppConfig, dispatchers (Kotlin puro)
core/database/           Room: 12 entidades, 8 DAOs
core/storage/            MediaStore + SAF + validación de archivos
core/metadata/           sondeo de formato (FLAC/WAV/MP3/AAC/ALAC/Ogg) y lectura de tags
core/permissions/        permisos mínimos con justificación
core/usb/                descriptores UAC, detección de DAC, permiso USB
core/audio/              capacidades reales del dispositivo + controlador Bit-Perfect + monitor de salidas
core/designsystem/       tema "Dark Hi-Fi" y componentes base
native/dsp/              EQ paramétrico, ReplayGain, crossfeed, ganancia, pipeline (Kotlin puro, con tests)
native/audio_engine/     motor de reproducción
data/*                   implementación de repositorios, DataStore, escáner, puente al motor
presentation/*           Compose: navigation, library, playback, settings
```

## Estado real del proyecto

Verificado con compilador y pruebas, no por inspección visual. **97 pruebas, 0 fallos** (31 de DSP,
21 de biblioteca, 14 de reproductor, 12 de ajustes, 10 del modelo de dominio, 6 de audio, 3 de
navegación), y CI las ejecuta en cada push.

- ✅ **Fases 1-17 implementadas**: arquitectura y proyecto, biblioteca y escáner, motor de audio,
  Now Playing, cola y Up Next, listas, metadatos y portadas, información de audio, salidas y USB DAC,
  bit-perfect, ReplayGain, ecualizador paramétrico de 10 bandas, crossfeed, sesión de biblioteca
  (notificación, bloqueo, Bluetooth y Android Auto), optimización, pruebas y release.
- ⚠️ **Lo que falta para decir 1.0.0**: probarlo en dispositivos reales. Aquí no hay móvil, ni DAC
  USB, ni coche: todo lo que depende del hardware (que el bit-perfect se confirme, qué formatos
  acepta un DAC concreto, el comportamiento en Android Auto) está implementado y se presenta con lo
  que el dispositivo responda, pero **no está verificado en hardware**.

Comandos del día a día (el toolchain del sandbox no se conserva entre sesiones):

```bash
sh scripts/install-toolchain.sh   # JDK 17 + Android SDK (solo si hace falta)
sh scripts/build.sh --all         # compila los módulos y corre las pruebas
sh scripts/build.sh :presentation:playback:testDebugUnitTest --max-workers=1
```

## Principios de honestidad técnica

- Bit-Perfect solo se muestra cuando el sistema **confirma** la ruta sin mezcla para el formato
  exacto que se está reproduciendo; si no, se explica el motivo concreto.
- Ninguna cifra de audio (frecuencia, profundidad, bitrate) se muestra si no proviene de una
  lectura real del archivo o del hardware.
- Las funciones que aún no existen se marcan como pendientes; nunca se simulan.

## Despliegue

El canal de entrega es GitHub, sin pasos manuales:

| Disparador | Qué ocurre |
|---|---|
| push a `main` | compila los 22 módulos, ejecuta las 97 pruebas y sube los APK (debug y release) como artefactos |
| etiqueta `v*` | además publica el APK de release en el *release* de GitHub |
| push a `main` (rama del panel) | republica el panel de `docs/` en GitHub Pages |

```sh
git tag v0.17.0-alpha && git push origin v0.17.0-alpha
```

El panel de estado del proyecto se publica con GitHub Pages desde `docs/`.

Al etiquetar, el *release* toma su texto de `docs/RELEASE-NOTES.md`, que es el archivo de notas de la
versión en curso. Vive en el repositorio a propósito: el texto que ve quien descarga el APK no puede
contradecir lo que dice el código. Procedimiento de una versión:

```sh
# 1. subir la versión en app/build.gradle.kts (versionCode/versionName)
# 2. copiar las notas de la versión a docs/RELEASE-NOTES.md
# 3. push a main y esperar a que CI esté en verde
# 4. etiquetar
git tag v0.17.0-alpha && git push origin v0.17.0-alpha
```

**Versión actual: 0.17.0-alpha** — las 17 fases del pliego, implementadas y verificadas con CI.
La numeración sigue a las fases a propósito: **1.0.0 no es «todo hecho», es «todo hecho y probado en
hardware real»**, y eso no se puede afirmar desde una máquina sin dispositivo.

**Firma:** el APK se firma con la clave de depuración mientras no exista un keystore de producción
(`keystore.properties` o las variables `HIFI_KEYSTORE_FILE`, `HIFI_KEYSTORE_PASSWORD`, `HIFI_KEY_ALIAS`,
`HIFI_KEY_PASSWORD`). Es instalable; para una tienda hay que firmarlo con la clave definitiva.
