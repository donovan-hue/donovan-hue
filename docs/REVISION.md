# Guía de revisión — estado del proyecto

Documento para revisar lo que ya está escrito. Nada aquí se declara terminado si no pasó por el
compilador o por los tests. Fecha de esta revisión: 2026-10-03.

Repositorio: https://github.com/donovan-hue/donovan-hue (rama `main`)

---

## 1. Qué está hecho y cómo comprobarlo

| Módulo | Contenido | Comprobación |
|---|---|---|
| `domain/model` | 15 archivos: formatos de audio, entidades de biblioteca, reproducción, ajustes, errores tipados, dispositivos | `./gradlew :domain:model:compileKotlin` |
| `domain/repository` | 10 contratos (Music, Playback, AudioDevice, Settings, Playlist, Favorite, Queue, Metadata, Artwork, Search) | compila |
| `domain/usecase` | ~100 casos de uso en 8 grupos | compila |
| `core/common` | `Outcome` (sin excepciones crudas), `Logger`, errores tipados, `AppConfig`, dispatchers, formateadores — Kotlin puro, sin Android | compila |
| `core/database` | Room: 12 entidades, 8 DAOs, mappers | `./gradlew :core:database:compileDebugKotlin` (verde, KSP genera `HiFiDatabase_Impl`) |
| `core/storage` | MediaStore, carpetas SAF, validación de archivos | `./gradlew :core:storage:compileDebugKotlin` |
| `core/metadata` | Sondeo de cabeceras (FLAC/WAV/MPEG/ISO-BMFF/Ogg) + tags (Vorbis, ID3v2.2/2.3/2.4, MP4 `ilst`) | `./gradlew :core:metadata:compileDebugKotlin` |
| `core/permissions` | Permisos mínimos por versión de Android + justificación | verde |
| `core/usb` | Parser de descriptores UAC1/UAC2, detección de DAC, solicitud de permiso | verde |
| `core/audio` | Capacidades medidas + Bit-Perfect con lectura de comprobación + monitor de salidas | verde |
| `native/dsp` | EQ paramétrico, ReplayGain (decisión + analizador BS.1770), crossfeed, ganancia, pipeline | `./gradlew :native:dsp:test` → **31 tests en verde** |
| `app` | Manifiesto, servicio declarado, receiver USB, ProGuard, logging | falta `res/` (strings, tema, iconos) |

Comando único para reproducir todo lo verificado:

```bash
export JAVA_HOME=/ruta/jdk-17
export ANDROID_HOME=/ruta/android-sdk
./gradlew :core:database:compileDebugKotlin :core:storage:compileDebugKotlin \
          :core:metadata:compileDebugKotlin :core:permissions:compileDebugKotlin \
          :core:usb:compileDebugKotlin :core:audio:compileDebugKotlin :native:dsp:test
```

---

## 2. Los 6 puntos que vale la pena revisar a fondo

Si solo tienes tiempo para mirar algunas cosas, que sean estas: son donde se define si el producto
es honesto o es humo.

### 2.1 Detección real de formato
`core/metadata/src/main/kotlin/com/hifiplayer/core/metadata/AudioHeaderProbe.kt`

Lee el propio archivo: `STREAMINFO` de FLAC, chunk `fmt`/`data` de WAV (incluido `0xFFFE` extensible
e IEEE float), tablas MPEG-1/2 Layer III con conteo de frames Xing/Info y VBRI, cajas `mvhd`/`mdhd`
+ `stsd` de ISO-BMFF (`mp4a`/`alac` con su magic cookie) y Ogg (`Opus`/`Vorbis`).
**Qué revisar:** que nunca devuelva un valor por defecto silencioso; si no puede medir algo, va `null`.

### 2.2 Honestidad del Bit-Perfect
`core/audio/src/main/kotlin/com/hifiplayer/core/audio/BitPerfectController.kt`

Flujo: comprueba que exista la API (Android 14+), que la salida sea USB/cableada, que el DAC anuncie
ese formato exacto, pide `AudioMixerAttributes` con `MIXER_BEHAVIOR_BIT_PERFECT`, y **lee de vuelta**
con `getPreferredMixerAttributes` antes de decir "activo". Cada negativa añade un `BitPerfectBlocker`
con explicación en español.
**Qué revisar:** que no exista ninguna ruta de código donde `isActive` sea `true` sin la lectura de
comprobación. En `domain/model/device/BitPerfect.kt` están los textos del banner.

### 2.3 Capacidades del dispositivo medidas, no supuestas
`core/audio/src/main/kotlin/com/hifiplayer/core/audio/AudioCapabilitiesManager.kt`

Cinco fuentes reales: `getAudioProfiles()` (API 34+), `getEncodings()/getSampleRates()`,
`AudioTrack.isDirectPlaybackSupported()`, `getSupportedMixerAttributes()` y los descriptores USB.
Cada dato guarda su `CapabilityEvidence` (de dónde salió) y cada ausencia su `warning`.
**Qué revisar:** que no haya valores por defecto tipo "asumimos 48 kHz/16 bits".

### 2.4 DSP con matemática verificable
`native/dsp/...` + sus 7 clases de test.

- `ParametricEq`: un `Biquad` por banda y canal, coeficientes recalculados al vuelo.
- `ReplayGainProcessor.decide()`: devuelve una `Decision` con dB aplicados, dB pedidos, si hubo
  recorte por clipping, y explicación textual.
- `ReplayGainAnalyzer`: ponderación K de BS.1770, bloques de 400 ms con 75 % de solape, gate −10 LU,
  referencia −18 LUFS.
- `GainStage`: rampa de 40 ms (sin clics), techo de +12 dB, medidores **post-limitador**.
**Qué revisar:** `GainStageTest` y `AudioProcessingPipelineTest`; ahí se ve por qué los medidores se
toman después del limitador.

### 2.5 Errores tipados, sin `catch (Exception) {}` mudos
`domain/model/error/AppError.kt` (PlaybackError, StorageError, MetadataError, AudioDeviceError)
y `core/common/error/ErrorMapper.kt`.
**Qué revisar:** busca `catch` en el proyecto; cada uno debe llamar al `Logger` o devolver un
`Outcome.Failure` con mensaje útil.

### 2.6 La arquitectura no se rompe
`settings.gradle.kts` define 21 módulos. La regla es que la UI nunca importe Room, Media3, USB ni
`java.io.File`; todo pasa por casos de uso y contratos de `domain/repository`.
**Qué revisar:** que las dependencias apunten hacia dentro (`presentation → domain ← data`).

---

## 3. Decisiones técnicas que conviene conocer

| Decisión | Motivo |
|---|---|
| `Outcome<T>` en vez de excepciones | un error no puede pasar desapercibido ni tumbar la app |
| Kotlin puro en `core/common` y `native/dsp` | se prueban con JUnit normal, sin emulador (por eso hay 31 tests corriendo hoy) |
| Room **y** DataStore | biblioteca/colecciones en Room; preferencias en DataStore |
| MediaStore + SAF | SAF no requiere permisos y funciona en carpetas que MediaStore no indexa |
| Sin `ffmpeg` ni decodificadores externos | ALAC se sondea en tiempo de ejecución con `MediaCodecList`, no se asume que exista |
| Sondeo de cabeceras propio | para no depender de que el sistema te diga el formato del archivo |

---

## 4. Lo que falta (sin adornos)

Fases 2 (parte de datos), 3 y 4–17 del plan original:

1. **`data/*`** — el escáner que une MediaStore + SAF + tags + Room, con progreso
   ("Analizando música… 42/381"), más los repositorios, DataStore y el puente al motor.
2. **`native/audio_engine` + servicio de reproducción** — motor, cadena DSP, `PlaybackService`,
   `MediaSession`, gapless, cola persistente.
3. **UI Compose completa** — `core/designsystem` + `presentation/{navigation,library,playback,settings}`:
   Home, Now Playing, Library, Playlists, Settings, AudioInfoCard, Mini Player.
4. **`app/res`** — textos en español, tema "Dark Hi-Fi", iconos.
5. **Tests instrumentados, release firmado, optimización.**

En volumen: falta algo más de la mitad de las líneas. En riesgo: la parte difícil ya está resuelta
y verificada (arquitectura, detección real, Room, DSP, ruta bit-perfect); lo que queda es sobre todo
volumen de UI.

---

## 5. Cómo seguir en la siguiente sesión

```bash
cd /home/user/HiFiPlayer
./scripts/git-setup.sh          # restaura identidad y remoto (no sobreviven al snapshot)
./scripts/git-setup.sh --check  # informe rápido

# el toolchain vive en /home/user/.cache/tools (tampoco sobrevive):
#   JDK 17 · Gradle 8.14.5 · Android SDK platform-36 + build-tools 36.0.0
```

Notas de entorno: la máquina tiene 2 vCPU y ~1 GB de RAM, así que Gradle va con `-Xmx720m` y las
compilaciones en frío tardan bastante; por eso los builds se agrupan por módulo en lugar de
ejecutar `assembleDebug` en cada iteración.
