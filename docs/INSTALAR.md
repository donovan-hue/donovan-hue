# Cómo instalar HiFi Player en tu móvil

Versión: **0.17.1-alpha** · Android 8.0 o superior · archivo: `app-release.apk` (3,0 MB)

## 1. Descarga

Desde el móvil, abre:

**https://github.com/donovan-hue/donovan-hue/releases/tag/v0.17.1-alpha**

Y toca `app-release.apk` en la sección *Assets*.

> No instales la **0.17.0-alpha**: su APK no arranca (está corregido en la 0.17.1). Si ya la
> instalaste, desinstálala antes de continuar.

## 2. Permite la instalación

Android te dirá que el navegador no puede instalar apps. Es normal para un APK que no viene de la
Play Store:

1. Toca **Ajustes** en el aviso.
2. Activa **Permitir desde esta fuente** para el navegador (o el gestor de archivos) que estés usando.
3. Vuelve atrás y toca **Instalar**.

Si ya tenías otra versión instalada, la 0.17.1 puede pedirte **desinstalar primero**. A partir de
ahora no: todas las compilaciones llevan la misma firma, así que las versiones nuevas se instalan
encima y conservan tus ajustes, tu biblioteca y tus listas.

## 3. Primer arranque

1. Abre **HiFi Player**.
2. Acepta el permiso de audio cuando lo pida (es para leer tus archivos; la app no pide red para la
   biblioteca).
3. Verás una pantalla vacía con un botón para **añadir una carpeta**. Eso es lo esperado: la app solo
   reproduce archivos locales, no trae música ni la busca en internet.
4. Elige la carpeta donde tengas tu música (por ejemplo `Música` o la carpeta de tus descargas).
   Android pedirá confirmar el acceso a esa carpeta: **Permitir**.
5. El escáner empieza solo y muestra el progreso («Analizando… 42/381»). En cuanto termina, ya hay
   canciones.

## 4. Si algo no funciona

| Síntoma | Qué pasa |
|---|---|
| "Aplicación no instalada" | Solo puede pasar con versiones anteriores a la 0.17.1 (firmadas con claves distintas). Desinstala esa y ya no volverá a ocurrir. |
| No aparecen canciones | Comprueba que la carpeta elegida tiene archivos `.flac`, `.wav`, `.m4a`, `.mp3` o `.aac`. En Ajustes → Biblioteca puedes lanzar un escaneo manual. |
| "Permiso denegado" al añadir carpeta | Vuelve a añadirla: el permiso SAF se pide por carpeta y Android lo revoca si se reinstala la app. |
| Sale "sin datos de capacidad todavía" | Es correcto: esa salida no ha respondido al sondeo. La app nunca rellena ese dato a ojo. |
| Bit-perfect "no disponible" | Normal en Android 13 o inferior, y en salidas Bluetooth. Solo las salidas USB o con cable, en Android 14+, pueden entregar la señal sin mezcla. |

## 5. Desinstalar

Ajustes de Android → Aplicaciones → HiFi Player → Desinstalar. La música no se toca: la app solo
guarda en su base de datos las rutas de los archivos que le diste.

---

**Firma:** el APK va firmado con una clave de **depuración** versionada en `ci/hifi-debug.keystore`.
No es un secreto (una clave de depuración no sirve para publicar) y está en el repositorio a
propósito: así **todas** las compilaciones firman igual y las actualizaciones entran encima de la
versión anterior, sin desinstalar y sin perder nada. CI lo comprueba en cada build: si un APK saliera
con otra firma, el pipeline falla antes de publicarlo.

Para una tienda hay que firmar con una clave de producción propia (`keystore.properties` o las
variables `HIFI_KEYSTORE_*`), que tiene prioridad sobre esta.
