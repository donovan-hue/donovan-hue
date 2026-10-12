# Prueba en vivo sobre Kronos-space.com

**Fecha:** 12 de octubre de 2026 · **Proyecto revisado:** `donovan-hue/Kronos-space.com` (clonado en `/home/user/demos/kronos-space`) · **Suite:** mcp-suite-mx 0.2.0.

Esta es la primera prueba de la suite contra un proyecto ajeno y real. No se modificó nada del repositorio de Kronos: todo lo que se probó corrió en copias aisladas.

---

## 1. Lo que se revisó

| Herramienta | Resultado |
|---|---|
| `mapa_proyecto` | 418 archivos de código · 74,234 líneas · 278 rutas · 127 pruebas · 19 entradas · 0 huérfanos · 6 desconectados · 10 funciones duplicadas |
| `pendientes_listar` | 0 pendientes (ni TODO, ni FIXME, ni código sin implementar) — el proyecto está muy cuidado en ese sentido |
| `git_status` | Limpio, al día con `origin/main` |
| `seguridad_escanear` | 439 archivos · puntaje 0 · 52 altos + 2 medios (detalle abajo) |
| CI de GitHub | **1 corrida en rojo en `main`** (11 de octubre, 20:04) → diagnosticada y resuelta |

## 2. El error real del CI

Corrida `38170632997`, trabajo `validate`, prueba 287 de 290:

```
not ok 287 - ruta desconocida /uploads/no-existe.jpg responde 404 JSON
  location: server/test/http-errors.contract.test.js:147:3
  error: 'The "string" argument must be of type string. Received type object (null)'
  code: ERR_ASSERTION · operator: match
```

Se reprodujo local: `node --test server/test/http-errors.contract.test.js` → 13 pasan, 1 falla (la misma).

**Por qué falla.** La prueba exige que toda ruta desconocida responda el contrato JSON `{error, code, path}`. Para `/uploads/no-existe.jpg` el filtro `mediaAcl` corta antes y responde `res.status(404).end()`: sin cuerpo y sin `content-type`. De ahí el `null`. Las otras tres rutas del mismo bucle (`/`, `/cualquier-cosa`, `/api/nope`) sí llegan al 404 JSON de `server.js`, y por eso pasan.

## 3. El diagnóstico con la suite

**Primera pasada:** falló. Dijo "tipo desconocido, confianza baja" porque no reconocía el formato del runner de pruebas de Node ni las aserciones que reciben `null`.

**Se mejoró la herramienta** (commit `6a1a9eb` de la suite, con 2 pruebas nuevas y 186 en total):

- Lee el formato TAP del runner de Node (`not ok 287 - nombre`).
- Reconoce la aserción que recibió `null` (`ERR_ASSERTION` / `must be of type string`) y propone mirar quién produce ese valor.
- Resuelve la ruta por terminación: el log trae `test/x.test.js` y el archivo está en `server/test/x.test.js` (monorepo).
- Si la línea citada ya no existe, muestra las últimas y lo dice.

**Segunda pasada (ya mejorada):**

```
tipo: prueba · confianza: alta
causa: Una comparación recibió null donde esperaba texto (aserción señalada en test/http-errors.contract.test.js:147)
evidencia: → server/test/http-errors.contract.test.js:147: test(`ruta desconocida ${path} responde 404 JSON`, async () => {
sugerencia: revisa el handler de esa ruta (¿responde sin cuerpo o sin content-type?)
```

## 4. La solución propuesta y probada

Parche `PR-b2c31d` (huella `05e7f6bd476a3bc4…`), un solo archivo, `server/src/middleware/mediaAcl.js`:

```diff
-function deny(res) {
+function deny(req, res) {
   cache(res, false);
-  return res.status(404).end();
+  return res.status(404).json({
+    error: "Recurso no encontrado",
+    code: "NOT_FOUND",
+    path: (req.originalUrl || req.url || "").split("?")[0]
+  });
 }
```

Más los dos sitios que llaman a `deny(...)`.

**Prueba en copia aislada** (`parche_probar`, comando `node --test server/test/http-errors.contract.test.js`):

- Antes: código 1 (rojo), 10.9 s.
- Después: código 0 (verde), 10.9 s.
- **Veredicto: "arregla"** — de rojo a verde, sin tocar el repositorio.

Estado: **propuesto y probado**, sin aplicar. Para aplicarlo hace falta la aprobación humana (CLI o panel); la suite se niega a aplicar sola.

## 5. Los demás hallazgos

**Secretos (52, altos, regla SEC008).** Todos en archivos de prueba: `client/test-ui/auth-session-persistence.spec.jsx`, `auth-storage-blocked.spec.jsx`, `forms-validation.spec.jsx`, `session-end.spec.jsx`. Son credenciales de juguete (`token-de-prueba`, `12345678`), no secretos reales. Dos salidas: agregar un `.centinela-ignore` en Kronos con esos archivos, o dejar que la regla marque los archivos de prueba como informativos.

**Rutas (2, medias, regla RUT001).**

- `server/src/modules/federation/federation.routes.js:22` — 6 rutas, entre ellas `/.well-known/webfinger`, que por diseño de la federación es pública.
- `server/src/modules/mcp/mcp.routes.js:6` — 2 rutas, `router.get("/status")`. Vale confirmar que ese estado puede ser público.

**Desconectados (6).** Casi todos se explican solos: `client/src/main.jsx` lo carga el HTML de Vite, `client/test-ui/setup.js` lo carga la configuración de pruebas, `client/playwright.config.js` es configuración y `guardian/src/index.js` es la entrada del guardián. Quedan por revisar de verdad: `client/src/lib/utils.js` y `client/src/design-preview/DesignPreview.jsx`.

## 6. Lo que queda pendiente

- **Decisión tuya:** ¿se aplica el parche `PR-b2c31d` al repositorio de Kronos y se sube una rama (o un PR) con el arreglo? La suite ya dio el visto bueno con pruebas.
- Opcional: `.centinela-ignore` en Kronos para que el escaneo no se ensucie con las credenciales de prueba.

## 7. Segunda pasada: aplicación del parche y sistema visual

**El parche se aplicó.** Ya no está sólo propuesto: vive en
`server/src/middleware/mediaAcl.js` del clon, en el commit `add9715`.

- `deny(req, res)` respondía `res.status(404).end()`: sin cuerpo y sin
  tipo de contenido. Por eso las tres rutas del bucle de la prueba sí
  llegaban al 404 JSON de `server.js` y `/uploads` no.
- Ahora responde el mismo `{error, code, path}` que el resto del servicio.
- Verificación: `http-errors.contract.test.js` 14/14 (antes 13/14) y la
  suite completa del servidor en 492 pruebas, 0 fallas.

**El sistema visual de Kronos-space-react entró al cliente.** Lo que en
un principio eran aproximaciones sueltas dentro de
`kronos-html-theme.css` pasó a ser el sistema portado tal cual:

- `client/src/styles/kronos-system/`, con los once archivos del port y su
  orden de carga respetado (la cascada depende de ese orden).
- Dos adaptaciones nada más, señaladas en el archivo: `--ring` se llama
  `--ks-ring` porque Tailwind ya usa `--ring` para los anillos de foco, y
  el `height:100%` de `html`/`body` pasó a `min-height` para no convertir
  el body en contenedor de scroll.
- El puente `kronos-html-theme.css` dejó de pelear con el sistema: sus
  tokens son alias y sus clases propias usan los valores exactos. Bajó de
  851 a 700 líneas y de 213 a unos 150 `!important`.
- Marca: componente `KronosBrand`, el sello cromado de cuatro capas, en la
  navegación, la barra superior y el panel.

**Limpieza de código.**

- Fuera `client/src/styles/chrome-minimal.css`: 1512 líneas (35 KB) que no
  cargaba ningún archivo. Se comprobó comparando la compilación antes y
  después: idéntica byte por byte, o sea que era código muerto de verdad.
- Fuera las reglas del tema cuyas clases no usaba nadie.
- Trece cierres de bloque `}}` normalizados en `styles.css`,
  `design-system.css` y `fan-nav.css`.

**Pruebas nuevas y resultados.** Se agregó `test-ui/kronos-system-parity.spec.jsx`
(8 casos) que vigila el orden de carga del sistema, que los tokens del
puente sean alias y no copias, que las clases del sistema no vuelvan a
definirse en el puente y que la marca dibuje sus cuatro capas. La prueba
de `ux-audit` dejó de vigilar el archivo muerto y ahora revisa todo el CSS
que `main.jsx` carga de verdad.

- Cliente: lint limpio, 38 pruebas de `node --test` y 261 de vitest.
- Servidor: 492 pruebas, 0 fallas.

Un dato aparte que salió en esta pasada: en el proyecto demo
`/home/user/demos/tienda-api` hay un token de GitHub escrito en
`src/config.js`. Se probó y ya está revocado (401), así que no sirve, pero
conviene borrar esa línea.

## 8. Resultado en GitHub

**Kronos-space.com.** Los dos commits están en `main`: `add9715` (el 404) y
`9e2a8b0` (sistema visual y limpieza). La corrida de CI de `9e2a8b0`
terminó en verde: trabajo `validate` con 17 pasos correctos (el restante se
salta por condición del flujo). La corrida roja que originó todo esto
(`38170632997`) es del commit anterior, `cc00009`, donde el fallo era real.

**Suite.** La corrida de CI de `43bb84c` también en verde.

**Alertas de dependencias.** Dependabot marcaba 15 alertas abiertas
(1 crítica, 5 altas, 8 medias, 1 baja) sobre
`data/fixtures/proyecto-vulnerable/package.json`. Ese archivo es un
proyecto deliberadamente vulnerable que `tests/centinela.test.ts` usa para
comprobar que el escáner de seguridad de la suite detecta lo que debe
detectar: no se instala, no se ejecuta y no entra en el paquete publicado.
Se dictaminaron las 15 como riesgo tolerable con esa explicación y el
repositorio queda en 0 alertas abiertas.
