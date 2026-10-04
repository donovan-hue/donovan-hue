# Cómo instalar HiFi Player en tu móvil

Versión: **0.17.2-alpha** · Android 8.0 o superior · archivo: `hifi-player.apk` (3,0 MB)

Esta versión **corrige el cierre al abrir** de la 0.17.1: el reproductor se consultaba desde un hilo de
fondo y Android mataba el proceso por ello. La causa se encontró abriendo el APK de release en un
emulador, no adivinando; desde ahora CI hace esa comprobación en cada cambio.

## 1. Descarga

Desde el móvil, abre este enlace y toca **Download**/descargar:

**https://donovan-hue.github.io/donovan-hue/hifi-player.apk**

Es la última compilación de la rama `main`: no hace falta cuenta de GitHub ni descomprimir nada.
(La misma pantalla del panel de estado tiene este enlace en *Cómo probarlo hoy*.)

> No instales ninguna versión anterior a la **0.17.2**: la 0.17.0 y la 0.17.1 no arrancaban. Si tienes
> una de esas instalada, desinstálala antes de continuar. Desde la 0.17.1 la firma es la misma, así que
> esta se instala encima y conserva tus ajustes.

## 2. Permite la instalación

Android te dirá que el navegador no puede instalar apps. Es normal para un APK que no viene de la
Play Store:

1. Toca **Ajustes** en el aviso.
2. Activa **Permitir desde esta fuente** para el navegador (o el gestor de archivos) que estés usando.
3. Vuelve atrás y toca **Instalar**.

Si ya tenías otra versión instalada, esta se instala **encima**: desde la 0.17.1 todas las
compilaciones llevan la misma firma, así que no hay que desinstalar y no se pierden ajustes, ni
biblioteca, ni listas.

## 3. Primer arranque

1. Abre **HiFi Player**.
2. Acepta el permiso de audio cuando lo pida (es para leer tus archivos; la app no pide red para la
   biblioteca).
3. Añade una carpeta con música: por ejemplo `Música` o la carpeta de tus descargas. Android pedirá
   confirmar el acceso a esa carpeta: **Permitir**.
4. El escáner empieza solo y muestra el progreso («Analizando… 42/381»). En cuanto termina, ya hay
   canciones.

## 4. Si la aplicación se cierra al abrir

Eso es un fallo y hay que arreglarlo, pero desde la máquina donde se compila no se puede ver: hace
falta el informe que la propia app deja en el teléfono. La 0.17.2 lo escribe **en dos sitios**, para
que no se pierda aunque la aplicación no llegue a abrirse:

1. **En la carpeta Descargas.** Busca un archivo llamado `hifi-player-fallo-FECHA.txt` (abre
   *Archivos* → *Descargas*, o el gestor de archivos del teléfono). Este es el camino que funciona
   aunque la app se cierre en cada intento.
2. **En un aviso dentro de la app.** Si al volver a abrirla aparece un cuadro que dice «La aplicación
   falló la última vez», toca **Copiar informe**.

Manda el contenido: pégalo en el chat, o comparte el archivo de Descargas por donde te sea más
fácil. El informe lleva la versión, el modelo de teléfono, el error exacto y su rastro; **no** lleva
tus archivos ni tus datos.

## 5. Si algo más no funciona

| Síntoma | Qué pasa |
|---|---|
| "Aplicación no instalada" | Solo puede pasar con versiones anteriores a la 0.17.1 (firmadas con claves distintas). Desinstala esa y ya no volverá a ocurrir. |
| No aparecen canciones | Comprueba que la carpeta elegida tiene archivos `.flac`, `.wav`, `.m4a`, `.mp3` o `.aac`. En Ajustes → Biblioteca puedes lanzar un escaneo manual. |
| "Permiso denegado" al añadir carpeta | Vuelve a añadirla: el permiso SAF se pide por carpeta y Android lo revoca si se reinstala la app. |
| Sale "sin datos de capacidad todavía" | Es correcto: esa salida no ha respondido al sondeo. La app nunca rellena ese dato a ojo. |
| Bit-perfect "no disponible" | Normal en Android 13 o inferior, y en salidas Bluetooth. Solo las salidas USB o con cable, en Android 14+, pueden entregar la señal sin mezcla. |

## 6. Desinstalar

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
