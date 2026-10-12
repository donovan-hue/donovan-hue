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
