# Diseño definitivo de la suite

**Fecha:** 12 de octubre de 2026 · **Versión:** 0.2.0.

Este documento es la norma visual del proyecto. Todo lo que tenga interfaz
—el panel, el mapa del Taller, el catálogo de herramientas, la landing y la
documentación— usa estos valores. La imagen de referencia está en
`paginas/hero-fucsia.png`: un núcleo con sus satélites alrededor, unidos por
una red de luz. La red es el producto: herramientas sueltas que trabajan
juntas, con la bitácora de lo que hicieron.

## Paleta

| Uso | Valor | Dónde |
| --- | --- | --- |
| Fondo | `#07070a` | cuerpo de todas las páginas |
| Fondo de escena | `#05050a` | escena 3D del Taller, banda del héroe |
| Panel | `#0d0d12` | tarjetas, tablas, contenedores |
| Panel alterno | `#12121a` | encabezados, filas alternas, chips |
| Borde | `#2a2a3a` | separadores y contornos |
| Texto | `#eceaf2` | texto principal |
| Texto suave | `#a9a4bb` | etiquetas, descripciones, cifras secundarias |
| **Acento (fucsia)** | `#ff3df0` | marca, enlaces, botón principal, luz de la red |
| Acento profundo | `#c026d3` | degradados del acento |
| Aviso | `#ffb648` | advertencias |
| Peligro | `#ff5c8a` | errores, acciones destructivas |
| Correcto | `#7ff0cd` | todo bien, servidor en línea |
| Informativo | `#4ec3e0` | cifras informativas, nunca decorativo |

La regla del color: el fucsia es la única luz decorativa de la marca. Los
demás colores son semánticos y no se usan para adornar. Si algo es verde es
porque está bien; si es rojo, porque hay peligro.

## Tipografía

- Texto: la del sistema (`ui-sans-serif`, `system-ui`, Segoe UI, Roboto).
- Cifras y código: monoespaciada (`ui-monospace`, SFMono-Regular, Menlo).
- Jerarquía: título de página 25 px, título de sección 17 px con un punto
  fucsia antes, título de tarjeta 15.5 px, cuerpo 15 px, secundario 12.5 px.

## Piezas

- **Banda del héroe:** la imagen de referencia arriba de todo, con el
  degradado oscuro a la izquierda y el título encima. El texto no compite con
  la imagen: contrasta.
- **Tarjeta:** fondo `#0d0d12`, borde de 1 px, esquinas de 14 px. Sin sombras
  grandes: la luz es del acento, no de las cajas.
- **Píldora de estado:** borde y texto del color semántico, fondo del panel.
- **Marca de lectura:** `lee` en azul informativo, `escribe` en fucsia. Es la
  distinción que importa: lo que escribe deja rastro en la auditoría.
- **Punto de sección:** el pequeño luminoso antes de cada título de sección,
  con brillo fucsia.

## Dónde está aplicado

| Página | Ruta | Archivo |
| --- | --- | --- |
| Panel y landing | `/` y `/panel` | `paginas/landing.html` |
| Catálogo: 68 herramientas y el proceso | `/herramientas` | `paginas/orquesta-visual.html` |
| Mapa vivo 3D del Taller | `/taller` | `paginas/taller.html` |
| Imagen de referencia | — | `paginas/hero-fucsia.png` |

## Lo que no se hace

- No se usan recursos externos: ni tipografías, ni scripts, ni imágenes por
  internet. Todo va dentro del archivo, para que la interfaz funcione igual en
  una computadora sin salida a la red.
- No se cambia el comportamiento por el estilo: los botones, los identificadores
  y los datos que espera el servidor siguen siendo los mismos.
- No se pinta de fucsia lo que comunica estado: los semáforos conservan su
  color.

## Cómo se comprueba

Las pruebas ya cubren el panel y el taller (`tests/http.test.ts`,
`tests/tls.test.ts`, `tests/taller.test.ts`). Al tocar el estilo se corre
`npm run typecheck`, `npm run build` y `npm test`: 186 pruebas en verde.
Después se levanta el servidor y se revisan las rutas `/panel`, `/herramientas`
y `/taller`.
