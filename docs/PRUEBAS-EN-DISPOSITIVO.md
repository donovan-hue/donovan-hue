# Pruebas en un móvil real (lo que falta para la 1.0.0)

Todo lo que sigue está implementado, pero **no se ha probado en hardware** porque la máquina donde se
construye no tiene móvil, ni DAC USB, ni coche. Aquí está la lista concreta de qué mirar y qué debería
pasar. Si algo no coincide, con el número de la fila basta para localizarlo.

Marca lo que vayas probando y anota lo que falle. Lo que diga «no aplica» porque tu equipo no lo
soporta también es información: la app está diseñada para decir «no disponible» en vez de fingir.

## A. Lo básico (sin hardware especial)

| # | Qué probar | Qué debería pasar | Resultado |
|---|---|---|---|
| A1 | Instalar y abrir | Abre sin cerrarse y muestra la pantalla de bienvenida vacía | ☐ |
| A2 | Añadir una carpeta con música | El escáner avanza con un contador real («42/381») y al acabar aparecen las canciones | ☐ |
| A3 | Reproducir un FLAC | Suena; en Now Playing aparecen códec, frecuencia y profundidad **reales** del archivo | ☐ |
| A4 | Reproducir un MP3 | Suena; la etiqueta dice 16-bit / 44.1 kHz y su bitrate (nunca «no detectado» en un archivo bueno) | ☐ |
| A5 | Rotar el móvil mientras suena | Sigue sonando y la pantalla no pierde el estado (ni la posición) | ☐ |
| A6 | Cerrar la app (deslizar fuera) | La música sigue; la notificación permanece; al volver está todo igual | ☐ |
| A7 | Bloquear la pantalla | Salen carátula, título y artista en el bloqueo, y los botones responden | ☐ |
| A8 | Buscar | Escribir rápido produce **una** consulta (no una por letra) y los resultados son correctos | ☐ |
| A9 | Cola: reproducir a continuación y reordenar | Respeta el orden que dejas con el dedo | ☐ |
| A10 | Lista de reproducción: crear, añadir, reordenar y reproducir | Se guarda al cerrar y abrir la app | ☐ |
| A11 | Escuchar un disco con gapless | Las pistas encadenan sin silencio apreciable | ☐ |
| A12 | Extraer el audio por Bluetooth | Suena; si el bit-perfect estaba activo, la app dice que ya no lo está | ☐ |
| A13 | Desconectar el Bluetooth en medio de una pista | La reproducción se detiene y lo dice (no se queda muda fingiendo que suena) | ☐ |

## B. Bit-perfect y salidas (necesita Android 14+)

| # | Qué probar | Qué debería pasar | Resultado |
|---|---|---|---|
| B1 | Ajustes → Audio → Bit-Perfect | Dice el estado **medido**: activo, o el motivo concreto por el que no lo está | ☐ |
| B2 | Reproducir 24/96 con bit-perfect y altavoz interno | No se anuncia bit-perfect: el altavoz pasa por el mezclador del sistema | ☐ |
| B3 | Con auriculares con cable | Si el sistema acepta el mezclador sin mezcla, el banner pasa a **BIT-PERFECT** con el formato entregado | ☐ |
| B4 | Encender el EQ con bit-perfect puesto | El banner cambia a **DSP ACTIVE**: no puede haber las dos cosas a la vez | ☐ |

## C. DAC USB (lo que más falta por comprobar)

| # | Qué probar | Qué debería pasar | Resultado |
|---|---|---|---|
| C1 | Enchufar un DAC USB | Android pide permiso; al aceptarlo, la salida activa pasa a ser el DAC | ☐ |
| C2 | Ajustes → Audio → Salidas | Aparece el DAC con su nombre; su ficha muestra **solo** lo que respondió al sondeo | ☐ |
| C3 | Reproducir 24/192 en el DAC | Suena sin cortes; la tarjeta SOURCE/OUTPUT dice si se convirtió o no | ☐ |
| C4 | Desenchufar el DAC mientras suena | La reproducción se detiene o cambia de salida **según el ajuste**, y lo informa | ☐ |
| C5 | Volver a enchufarlo | La app lo detecta y ofrece usarlo de nuevo | ☐ |
| C6 | Reproducir en el DAC con bit-perfect activado | Es el caso donde de verdad se puede confirmar: mira evidencia y formato entregado | ☐ |

## D. Android Auto / coche

| # | Qué probar | Qué debería pasar | Resultado |
|---|---|---|---|
| D1 | Conectar el móvil al coche | HiFi Player aparece como app de música con su icono | ☐ |
| D2 | Navegar por Álbumes → un álbum | Lista las pistas del álbum, con su carátula | ☐ |
| D3 | Buscar desde la pantalla del coche | Devuelve resultados de tu biblioteca | ☐ |
| D4 | Reproducir desde el coche | Suena y el mando del coche controla la reproducción | ☐ |

## E. Rendimiento (lo que no se puede medir sin un móvil)

| # | Qué probar | Qué debería pasar | Resultado |
|---|---|---|---|
| E1 | Desplazarse por una lista de miles de canciones | Sin tirones; las carátulas no se re-descargan al subir y bajar | ☐ |
| E2 | Abrir Now Playing desde una lista | La carátula aparece sin congelar la pantalla | ☐ |
| E3 | Dejar sonando 1 hora con la pantalla apagada | Consumo razonable (Ajustes de Android → Batería → HiFi Player) | ☐ |
| E4 | Abrir la app en frío con la biblioteca ya escaneada | Aparece rápido, con la última pista y la cola donde las dejaste | ☐ |

---

## Cómo reportar

Con el número de fila basta. Por ejemplo: *«C4: al desenchufar el DAC la música se paró y no salió
ningún aviso»*. Lo que salga de aquí se convierte en un fallo concreto con su prueba, no en una
impresión general.

**Recordatorio:** ninguna de estas casillas se puede marcar desde la máquina de construcción, y por eso
la versión publicada se llama `0.17.1-alpha` y no `1.0.0`. La numeración no es un adorno: 0.x significa
«está construido y verificado por el compilador», 1.0 significará «y funciona en equipos reales».
