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

Verificado con compilador y tests (no por inspección visual):

- ✅ `native:dsp` — 31 tests JUnit/Truth en verde (EQ, ReplayGain, crossfeed, ganancia, pipeline).
- ✅ `core:database` (KSP + Room), `core:storage`, `core:metadata`, `core:permissions`,
  `core:usb`, `core:audio`, `domain:*` — compilan.
- 🚧 Pendiente: escáner/repositorios (`data:*`), motor de reproducción y `PlaybackService`
  (fase 3), toda la UI Compose, `res/` de la app, tests instrumentados, release.

Este README no declara nada como terminado si no está compilado o probado.

## Principios de honestidad técnica

- Bit-Perfect solo se muestra cuando el sistema **confirma** la ruta sin mezcla para el formato
  exacto que se está reproduciendo; si no, se explica el motivo concreto.
- Ninguna cifra de audio (frecuencia, profundidad, bitrate) se muestra si no proviene de una
  lectura real del archivo o del hardware.
- Las funciones que aún no existen se marcan como pendientes; nunca se simulan.
