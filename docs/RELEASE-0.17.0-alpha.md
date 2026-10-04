# HiFi Player · notas de la versión 0.17.0-alpha

Reproductor de música para Android **solo con archivos locales**: sin streaming, sin publicidad y sin
cuentas. Las 17 fases del pliego están implementadas; lo que sigue sin poder afirmarse es lo que solo
se puede comprobar con hardware delante, y este documento lo dice en cada caso.

## Qué se puede usar hoy

### Biblioteca
- Carpetas por SAF, escáner no bloqueante con progreso real («Scanning music… 42/381»), detección de
  formatos, portadas con el orden incrustada → `cover.jpg` → `folder.jpg` → `artwork.jpg`, y caché
  con presupuesto en bytes.
- Canciones, Álbumes, Artistas, Géneros, Carpetas, Listas, Recientes, Favoritos; orden por título,
  artista, álbum, año, duración y calidad, y filtros de sin pérdida y favoritos resueltos en SQL.
- Búsqueda global con 300 ms de espera real medida en pruebas: escribir diez letras produce **una**
  consulta.

### Reproducción
- FLAC, WAV, ALAC, MP3 y AAC como mínimo, con lectura real de códec, frecuencia, profundidad, canales,
  bitrate y duración: ninguna cifra se muestra si no se ha leído del archivo o del hardware.
- Reproducción sin cortes (gapless), cola con añadir, reproducir a continuación, quitar, reordenar por
  arrastre, guardar y restaurar, y estado que sobrevive a la rotación, al cierre y al cambio de salida.
- Servicio en primer plano con `MediaSession`: notificación y pantalla de bloqueo con carátula,
  anterior, reproducir/pausar y siguiente, mandos de auriculares y de Bluetooth, y **Android Auto** con
  un árbol navegable real (Recientes, Álbumes, Artistas, Canciones, Favoritos, Listas, Géneros) con
  paginación y búsqueda.

### Audio
- **Información de audio**: tarjeta SOURCE → OUTPUT, cadena de procesamiento aplicada de verdad,
  bloqueos del bit-perfect con su explicación, evidencia y verificación bajo demanda del formato que
  suena.
- **Salidas y USB DAC**: lista de rutas con la activa marcada, permiso USB cuando hace falta y ficha de
  capacidades con **solo** lo que el equipo respondió al sondearlo.
- **Bit-Perfect**: interruptor con estado medido (solicitado / activo / soporte), bloqueos y evidencia.
  Si no se puede, dice «bit-perfect no disponible en esta configuración de audio»; nunca lo finge.
- **ReplayGain** OFF / TRACK / ALBUM aplicado al reproducir (nunca modifica archivos), con las
  ganancias reales de la pista que suena.
- **Ecualizador paramétrico de 10 bandas** con frecuencia, ganancia, Q, tipo y activación por banda,
  filtros Peaking, Low/High Shelf y Low/High Pass, y presets Flat, Bass Boost, Vocal, Treble y Custom
  con guardar y borrar. Elegir un preset **no** lo enciende.
- **Crossfeed** OFF / BAJO / MEDIO / ALTO con la advertencia de que altera la señal, ganancia de
  aplicación con aviso por encima de 0 dB y sin doble amplificación.

### Honestidad técnica
- Cada efecto se puede desactivar por separado y la cadena se describe con lo que está encendido.
- Los errores son tipados (`PlaybackError`, `StorageError`, `MetadataError`, `AudioDeviceError`) y se
  dicen al usuario; no hay `catch (Exception) {}`.
- Ninguna función decorativa: lo que aparece escribe de verdad o está marcado como pendiente.

## Verificación

- **97 pruebas automatizadas, 0 fallos**: 31 de DSP (EQ, ReplayGain, crossfeed, ganancia, pipeline),
  21 de biblioteca (listas, búsqueda, filas de pista), 14 de reproductor (ruta de audio, estado y
  redibujado), 12 de ajustes (DSP y capacidades), 10 del modelo de dominio, 6 de audio (árbol de
  navegación del coche), 3 de navegación.
- CI compila los 22 módulos, ejecuta las pruebas y empaqueta los APK de depuración y release en cada
  push; con una etiqueta publica el APK de release aquí.
- El APK se firma con la clave de depuración mientras no exista un keystore de producción: es
  instalable, pero para una tienda hay que firmarlo con la clave definitiva.

## Lo que **no** está verificado (y por eso la versión no es 1.0.0)

Todo esto está implementado y se presenta con lo que el dispositivo responda, pero no se ha probado
en hardware real porque en la máquina de desarrollo no hay móvil, ni DAC USB, ni coche:

- Si el bit-perfect se **confirma** en un dispositivo concreto y qué formatos acepta cada DAC.
- El comportamiento real al conectar y desconectar un DAC USB, y la reanudación en Bluetooth.
- La integración con Android Auto en un vehículo o con la app de teléfono.
- El consumo de batería y el rendimiento con una biblioteca de miles de pistas en un móvil real.

Nada de esto se declara como «probado» en ninguna pantalla de la aplicación: lo que aparece en
Bit-Perfect, en la ficha de capacidades y en la tarjeta SOURCE/OUTPUT sale de sondear el equipo, y si
el equipo no responde, la app lo dice.
