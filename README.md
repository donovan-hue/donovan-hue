# Suite MCP MX

Plataforma **[MCP](https://modelcontextprotocol.io)** (Model Context Protocol) con **76 herramientas** en 12 módulos: **Empresas (multi-empresa), negocio, facturación (CFDI 4.0), documentos, análisis de datos, DevOps, Taller (gemelo digital), Centinela (seguridad), Parche (reparar con red de seguridad), Orquesta (flujos), Rúter (modelos) y Diseño (color, contraste, tokens y componentes)**, adaptada a la operación de empresas mexicanas (IVA 16%, retenciones ISR/IVA, RFC/CURP, CFDI 4.0).

- **Local** vía `stdio`: Claude Desktop, Claude Code, Cursor, VS Code.
- **Remoto** vía **Streamable HTTP** con **API keys, permisos por módulo, límite de tasa, auditoría y métricas Prometheus**.
- **Persistencia transaccional** en SQLite (WAL) con migración automática desde los JSON de la v1.
- **Facturación CFDI 4.0**: borradores y XML sin sellar listos para tu PAC, validaciones del Anexo 20 y retenciones de honorarios.
- **Operación**: HTTPS con certificado propio (+HSTS), respaldos en caliente con manifiesto sha256 y rotación, y panel web en `GET /panel`.

> Alcance, arquitectura, seguridad y estimaciones: [`docs/ALCANCE.md`](docs/ALCANCE.md).

> Diseño: la norma visual del proyecto (paleta fucsia, tipografía y piezas) está en [`docs/DISENO.md`](docs/DISENO.md). El panel vive en `/panel`, el catálogo de las 76 herramientas y su proceso en `/herramientas`, y el mapa vivo 3D en `/taller`.

## Inicio rápido

### 0. Desde npm (sin clonar nada)

```bash
npx mcp-suite-mx --version        # servidor MCP (stdio por defecto; --transport http para el servidor web)
npx mcp-suite-mx-cli keys create --nombre equipo --scopes negocio,facturacion
npx mcp-suite-mx-cli empresas --comparar
```

Los dos comandos los instala el paquete: `mcp-suite-mx` (servidor) y `mcp-suite-mx-cli` (administración). Con `--help` sale el resumen de uso y variables.

### 1. Local (stdio, 2 minutos)

```bash
npm install
npm run build

# Probar el servidor por stdio (handshake + catálogo):
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cli","version":"1"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
| node dist/index.js --transport stdio
```

### 2. Remoto con autenticación (recomendado)

```bash
cp .env.example .env      # AUTH_MODE=required por defecto
npm run build

# Crea una clave con permisos concretos (el secreto se muestra UNA vez):
node dist/cli.js keys create --nombre equipo-ventas --scopes negocio,analisis:ro

# Arranca el servidor
AUTH_MODE=required npm run start:http

# Llama herramientas con la clave
curl -s http://localhost:8787/mcp \
  -H 'Authorization: Bearer mcp_XXXXXXXX_...' \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cotizacion_generar","arguments":{"cliente":"ACME","items":[{"descripcion":"Servicio","cantidad":1,"precio_unitario":10000}]}}}'
```

Sin clave, `POST /mcp` responde `401` con instrucciones. En clientes MCP remotos la URL es `http://tu-servidor:8787/mcp`.

### 3. Docker

```bash
docker compose up -d --build
docker compose exec mcp-suite node dist/cli.js keys create --nombre staff --scopes negocio,analisis
curl http://localhost:8787/health
# Panel: http://localhost:8787/panel
```

## Seguridad (fase 2)

| Capacidad | Cómo se usa |
|---|---|
| **API keys** | `AUTH_MODE=required` exige `Authorization: Bearer mcp_…`. Hasheadas (sha256), con expiración opcional y revocación inmediata. |
| **Scopes** | `*` (todo) · `negocio` · `negocio:ro` (solo lectura) · `facturacion` · `facturacion:ro` · combinables: `--scopes negocio,facturacion,analisis:ro`. El catálogo se filtra por clave: lo no autorizado simplemente no existe para ese cliente. |
| **CLI** | `keys create/list/revoke`, `audit`, `check` |
| **Límite de tasa** | `RATE_LIMIT_RPM` (default 120) y `RATE_LIMIT_BURST` (30) por cliente; headers `X-RateLimit-*` y mensaje con segundos de reintento. |
| **Auditoría** | `data/runtime/auditoria.ndjson` (append-only): operaciones destructivas, fallos de auth, bloqueos de tasa, altas/bajas de claves. Consultable con `auditoria_consultar`, `GET /api/auditoria` o `cli audit`. |
| **Métricas** | `GET /metrics` en formato Prometheus: llamadas por herramienta, duración (histograma), HTTP por ruta/código, fallos de auth, bloqueos y uptime. |

> En HTTP sin `AUTH_MODE=required` no expongas el puerto a internet. En producción, `AUTH_MODE=off` con bind público se **rechaza al arrancar**.

## Facturación (CFDI 4.0)

```bash
# 1. Configura tu emisor una sola vez
export CFDI_EMISOR_RFC=DJA240118H72 CFDI_EMISOR_NOMBRE="Distribuidora Jalisco SA de CV" \
       CFDI_EMISOR_REGIMEN=601 CFDI_EMISOR_CP=44100

# 2. Factura un pedido existente (o usa cfdi_generar para conceptos libres)
#    → el modelo puede pedirlo directamente: "factura el pedido P-0002 a Coworking Chapultepec, RFC CCH190903K31..."
```

Qué hace y qué no:

- ✅ Calcula IVA (16% general, 8% frontera, 0% o exento), retenciones de ISR/IVA sobre la base, descuentos y totales.
- ✅ Valida RFC, regímenes fiscales, uso del CFDI, reglas de público en general (`XAXX010101000`), forma/método de pago (PUE/PPD) y moneda con tipo de cambio.
- ✅ Genera el **XML CFDI 4.0 sin sellar** y lo guarda en `data/facturacion/` si lo pides.
- ❌ No sella ni timbra: eso lo hace un **PAC autorizado** con tu CSD. El flujo es: borrador → XML → PAC → UUID/fecha de timbrado → complemento de pago si es PPD.

## Operación en producción

### HTTPS

```bash
node scripts/certificado-autofirmado.mjs          # solo para pruebas locales
TLS_CERT=./data/tls/cert.pem TLS_KEY=./data/tls/key.pem npm run start:http
```

Con `TLS_CERT`/`TLS_KEY` el servidor arranca en HTTPS y agrega HSTS. En producción usa certificados reales (Let's Encrypt o los de tu empresa) y, si hay reverse proxy, `TRUST_PROXY=1`.

### Respaldos

```bash
node dist/cli.js backup                     # copia consistente en data/backups/
node dist/cli.js backup --conservar 14      # política de retención
node dist/cli.js backup list                # respaldos existentes
```

Cada respaldo usa `VACUUM INTO` (consistente incluso con el servidor en marcha), copia la auditoría, los datasets y los XML de facturación, y escribe un manifiesto con hashes **sha256** (manifest.json dentro de cada respaldo) para verificar integridad. Restaurar = detener el servidor y copiar `suite.db` del respaldo sobre `data/`.

Programa el respaldo diario con cron:

```
15 2 * * * cd /opt/mcp-suite-mx && node dist/cli.js backup --conservar 14 >> data/runtime/backup.log 2>&1
```

## Taller — gemelo digital del proyecto (fase 4A)

Módulo para trabajar **sobre código** (propio o de cualquier proyecto dentro de `ALLOWED_GIT_ROOTS`). Todo es local: no sube código a ningún servicio.

```bash
# El mapa vivo: módulos, dependencias, rutas, servicios, entradas y huérfanos
# → "¿qué es este proyecto?" respondido en segundos (resumen | markdown | svg | json)
# impacto_calcular: cambiar src/lib/cfdi.ts → qué se rompe + qué pruebas correr
# rescate_diagnostico: reconstruye arquitectura + huecos + PLAN ordenado por impacto
# pendientes_listar: TODO/FIXME/sin implementar, ordenados por lo que más desbloquea
```

- **GEMELO**: mapa del proyecto con dependencias entre módulos, rutas HTTP, servicios externos, pruebas, archivos huérfanos y funciones duplicadas. Informes en Markdown (con diagrama Mermaid), SVG o JSON; opcionalmente guardados en `data/taller/`.
- **GEMELO VIVO** (página interactiva): abre **`/taller`** en el navegador. El mapa se re-analiza solo cada 4 segundos (solo si algo cambió, mediante huella de fecha+tamaño) y lleva una **bitácora de cambios en vivo**: archivos nuevos, rutas nuevas, huérfanos que nacen o se resuelven, pendientes que aparecen o se cierran. Con `TALLER_PUBLICO=0` el mapa exige API key cuando `AUTH_MODE=required`.
- **VISTA 3D EN VIVO**: la página trae un **motor 3D propio** (cero librerías, cero CDN, cero conexiones: funciona hasta sin internet). Cada archivo es una esfera con sombreado, punto especular y niebla por profundidad; cada import, un enlace. Arrastra para orbitar, rueda para acercar, click para seleccionar, doble click para auto-girar. Los cambios laten: la esfera modificada emite un anillo cuando guardas código. Las posiciones **persisten** entre refrescos (solo los nodos nuevos se colocan), así que la escena no brinca cada 4 s.
- **RESCATE**: para retomar proyectos a medias sin recordar su historia: detecta stubs (`no implementado`), TODOs, archivos vacíos, módulos desconectados, duplicados y desajustes entre el README y la realidad; devuelve un plan de terminación ordenado por impacto.
- Ejemplo incluido: analiza el proyecto de prueba `data/fixtures/proyecto-abandonado/` y verás el diagnóstico completo con su plan.

## Centinela — seguridad con verificación (fase 4B)

Encuentra los riesgos que de verdad se cuelan a producción y **demuestra** cuándo quedaron resueltos. Todo local: no sube tu código a ningún servicio.

```bash
# Qué tiene mal este proyecto: secretos, configs peligrosas, rutas sin protección, dependencias
# seguridad_escanear   → hallazgos priorizados + puntaje 0-100 + semáforo (secretos siempre redactados)
# seguridad_plan       → cada hallazgo convertido en pasos: qué hacer, dónde y CÓMO COMPROBARLO
# seguridad_verificar  → compara contra la línea base: resueltos, permanecen y NUEVOS (¿de verdad sirvió el fix?)
# dependencias_revisar → avisos conocidos, versiones sin fijar y scripts de instalación peligrosos
```

- **Qué detecta** (segunda línea de defensa local, sin red): llaves de AWS/Google/Stripe/OpenAI y tokens de GitHub/Slack, llaves privadas, contraseñas en código y cadenas de alta entropía (`SEC0xx`); `eval`, SQL por concatenación, TLS sin verificar, `chmod 777`, credenciales de admin por defecto y descargas ejecutadas al shell (`CFG0xx`); archivos con rutas que nunca mencionan autenticación (`RUT001`); dependencias con avisos conocidos y rangos flotantes (`DEP0xx`); higiene del repositorio —`.env` presente o sin cubrir por `.gitignore` (`HIG0xx`).
- **Exclusiones con motivo**: agrega `.centinela-ignore` con líneas `REGLA:ruta/glob # razón` (o `:ruta # razón` para todas las reglas). El motivo queda citado en el informe: excluir sin explicar no es una opción.
- **Plan comprobable**: cada paso trae prioridad (ahora / esta semana / cuando puedas), esfuerzo (S/M/L) y su verificación concreta —p. ej. «escanear: CFG007 en cero; pruebas de base de datos en verde (incluye caso con comillas en el dato)»—. Nada de "revisa la seguridad": pasos que se pueden cerrar.
- **Demostración, no suposición**: `seguridad_escanear` con `guardar_linea_base=true` fija la foto inicial; `seguridad_verificar` re-escanea y responde *resueltos / permanecen / nuevos*, con el cambio de puntaje. Un arreglo de seguridad sin verificación es una promesa, no un hecho.
- **Gate de aprobación humana**: toda operación destructiva (aplicar un plan, desplegar) pasa por una **aprobación registrada**. El modelo puede *solicitarla* (`seguridad_plan` con `solicitar_aplicacion=true`) y *consultar* si sigue válida, pero **solo una persona la resuelve** por CLI (`aprobar AP-xxxx` / `rechazar AP-xxxx --motivo "…"`) o panel: un agente no puede autorizarse a sí mismo. Cada aprobación lleva una **huella** del contenido: si el plan cambia, la autorización deja de valer. Las solicitudes vencen solas (`APROBACION_VIGENCIA_MIN`, 120 min por defecto) y todo queda en la auditoría inmutable.
- **Permisos de las rutas, sin adivinar**: `permisos_auditar` lee cómo se declaran tus rutas (Express, Fastify, Koa, Hono, NestJS) y dice cuál está protegida por *middleware en la línea*, cuál por un *guard global* y cuál quedó al aire; además separa las que «probablemente son públicas» (webhooks, health, login) para que no ensucies el informe con falsos positivos.
- **Auditoría de la propia suite**: `npm audit` en este repositorio da **0 vulnerabilidades** (el aviso de `tinypool`/`@vitest/mocker` de la cadena de pruebas se cerró subiendo a vitest 4.1.11, y `dependencias_vulnerables` lo comprueba cuando quieras).
- **Dependencias reales**: `dependencias_vulnerables` consulta `npm audit --json` (con caché de 24 h por hash de `package.json` + lockfile en `data/centinela/audit-cache.json`) y, si no hay red o no hay lockfile, cae a la tabla local de avisos frecuentes **diciendo con franqueza de dónde salió cada dato** — nunca presenta un «0 vulnerabilidades» falso.
- **Vía CLI y CI**: `node dist/cli.js seguridad --directorio <proyecto> [--minimo 80] [--plan]` imprime el escaneo en JSON (o el plan en Markdown) y **sale con código 1 si el puntaje baja del mínimo** — listo para un pipeline. `node dist/cli.js aprobaciones` muestra la cola; `parches`, `flujos` y `modelos` cubren el resto del pack.

## Parche — reparar con red de seguridad (fase 4C)

El pipeline completo de reparación, con la regla de oro: **nada se aplica sin pruebas y sin tu aprobación; todo se deshace con un comando**.

```bash
# falla_diagnosticar   → error/log/prueba rota → causa probable CON EVIDENCIA archivo:línea
# parche_proponer      → cambio revisable (diff + huella); valida que el «antes» exista tal cual
# parche_probar        → pruebas ANTES y DESPUÉS en copia aislada (tu repo no se toca)
# parche_aplicar       → SOLO con aprobación humana (AP-xxxx) con la huella del parche + respaldo
# reparacion_revertir  → deshacer en un comando (deshacer nunca requiere permiso)
# reparaciones_listar  → historial: qué se arregló, qué falta aprobar, qué se deshizo
```

- **Diagnóstico con evidencia**: reconoce y explica los casos que de verdad rompen el día — import relativo que no resuelve (y te dice la ruta buena si existe), dependencia declarada pero no instalada, `ENOENT`, acceso a propiedad de `undefined/null`, expectativas de prueba incumplidas, errores de sintaxis y servicios caídos. Cada causa cita archivo:línea leído del proyecto; si no alcanza el contexto, lo dice en vez de inventar.
- **Prueba en copia aislada**: cada parche corre primero en `data/parche/pruebas/PR-xxxx/`, con las pruebas antes y después. Veredicto claro: **arregla** (rojo → verde), **mejora**, **igual** o **empeora**. El veredicto «empeora» no se puede aplicar ni con aprobación.
- **Guardarraíles**: límite de intentos por parche (`PARCHE_MAX_INTENTOS`, 3), timeout de pruebas configurable, respaldo por archivo antes de tocar el repositorio real y huella del contenido (si el parche cambia después de aprobarlo, la aprobación caduca).
- **Revertir siempre primero**: el respaldo se conserva y `reparacion_revertir` restaura los archivos sin pedir permiso — igual que en los respaldos de la base.

## Orquesta — flujos de dev con bitácora (fase 4D)

```bash
# flujo_listar        → 5 flujos de batalla probada + los tuyos
# flujo_ejecutar      → corre paso a paso (dry_run para ver el plan) y deja acta JSON
# flujo_estado        → últimas corridas, pasos fallidos y aprobaciones pendientes
# flujo_definir       → tu flujo propio combinando pasos permitidos
# aprobacion_solicitar→ pedir permiso humano para CUALQUIER operación sensible
```

Los 5 predefinidos: **verificar despliegue** (health checks + estado del repo), **respaldo con rotación**, **auditoría de dependencias y seguridad**, **preparar release** (propone versión y notas; no publica nada) y **post-mortem automático** (informe Markdown con la auditoría, los errores y el diff). Cada corrida queda en `data/orquesta/runs/`; los flujos propios, en `data/orquesta/flujos.json`.

## Rúter — coste por resultado útil (fase 4D)

```bash
# modelo_elegir      → qué modelo para esta tarea + cascada de escalado (barato primero)
# modelo_resultado   → registra si SIRVIÓ (pruebas verdes, parche vivo) — con evidencia
# coste_comparar     → coste por tarea EXITOSA, medido con tus datos
# politica_modelos   → presupuesto, preferencias y «solo local» por tipo de tarea
```

La diferencia con los enrutadores del mercado: ellos estiman calidad por benchmark; aquí se **mide si la tarea sirvió** con los hechos del propio taller (¿las pruebas quedaron verdes?, ¿el parche sobrevivió?) y se calcula **coste por resultado útil**. El catálogo de modelos es local y editable (`data/ruter/modelos.json`), con costes en unidades relativas para que pongas tus precios reales. Nunca degrada una tarea crítica por ahorrar: en criticidad alta arranca el modelo fuerte.

## Empresas — un servidor, varios negocios (fase 5A)

El caso que pidió el canal: **un despacho contable con varios clientes, o un dueño con dos giros**. Cada empresa tiene su inventario, sus pedidos y sus plantillas; nada se mezcla. La misma clave de código puede existir en dos empresas (dos `TOR-001` distintos) y cada pedido descuenta stock en el negocio correcto.

```bash
# empresas_listar    → espacios (con datos fiscales) y cuál está activo
# empresa_crear      → espacio nuevo, con su RFC/régimen/CP/serie de folios
# empresa_actual     → ver o cambiar la empresa activa de esta sesión
# empresa_editar     → corregir datos o archivar (archivar oculta, no borra)
# empresa_resumen    → foto ejecutiva del negocio: inventario, ventas, riesgos de stock
# empresas_comparar  → todos los clientes lado a lado, sin mezclar cifras
# inventario_importar→ cargar el catálogo de cada empresa desde CSV (con dry_run)
```

- **Aislamiento de verdad**: `inventario`, `pedidos` y `plantillas` llevan `empresa_id`; los repositorios filtran por la empresa activa en cada consulta. Una base anterior se migra sola (todo lo que ya existía pasa a la empresa por defecto, sin perder nada).
- **Claves atadas a una empresa**: `node dist/cli.js keys create --nombre cliente-x --scopes negocio,facturacion --empresa EMP-xxxxxx` — esa clave solo ve los datos de su empresa y no puede cambiar de espacio ni comparar.
- **CFDI por empresa**: el borrador toma el emisor (RFC, razón social, régimen, CP) y la serie de folios de la empresa activa; ya no hay que configurar `CFDI_EMISOR_*` si cada empresa tiene sus datos.
- **CLI**: `node dist/cli.js empresas [--todas] [--comparar]` y `empresas crear --nombre "…" [--rfc …] [--regimen …] [--cp …]`.

## Datos

```
data/
├── suite.db                 # SQLite (WAL): inventario, pedidos, plantillas, api_keys, meta
├── runtime/auditoria.ndjson # bitácora inmutable (append-only)
├── datasets/                # ventas_mensuales.csv (48 filas), clientes.csv
├── logs/api_produccion.log  # log de ejemplo para log_analizar
├── facturacion/             # XML CFDI sin sellar que generes
├── backups/                 # respaldos con manifiesto sha256 (cli backup)
├── tls/                     # certificados de desarrollo (autofirmados)
├── taller/                  # informes del gemelo digital (mapa y rescate)
├── fixtures/                # proyecto de prueba "abandonado" para el diagnóstico
├── inventario.json …        # JSON de la v1: migrados automáticamente a SQLite
└── plantillas.json          #   en el primer arranque (no se borran; quedan de respaldo)
```

- Pedidos y descuentos de stock se ejecutan en **una sola transacción** (o todo, o nada).
- Regenerar los datasets de ejemplo: `node scripts/generar-datasets.mjs`.

Variables de entorno principales (ver `.env.example`):

| Variable | Descripción | Default |
|---|---|---|
| `MCP_TRANSPORT` | `stdio` o `http` | `stdio` |
| `PORT` / `HOST` | Puerto y bind del modo HTTP | `8787` / `0.0.0.0` |
| `DATA_DIR` | Directorio de datos (SQLite, datasets, auditoría) | `./data` |
| `AUTH_MODE` | `off` / `optional` / `required` | `off` |
| `MCP_API_KEYS` | Claves bootstrap separadas por coma (CI/despliegues desechables) | — |
| `RATE_LIMIT_RPM` / `RATE_LIMIT_BURST` | Límite por cliente (0 desactiva) | `120` / `30` |
| `TRUST_PROXY` | Confiar en `X-Forwarded-For` (solo detrás de proxy propio) | `0` |
| `TLS_CERT` / `TLS_KEY` | Certificado y llave PEM (ambos activan HTTPS + HSTS) | — |
| `CFDI_EMISOR_RFC` / `_NOMBRE` / `_REGIMEN` / `_CP` | Datos fiscales del emisor para no repetirlos en cada factura | — |
| `ALLOWED_GIT_ROOTS` | Rutas autorizadas para git y para el módulo taller (separadas por `:`) | raíz del proyecto |
| `TALLER_PUBLICO` | `1` = el mapa vivo `/taller` es público · `0` = exige API key con `AUTH_MODE=required` | `1` |
| `IVA_PCT` | Tasa de IVA por defecto | `16` |
| `ALLOWED_GIT_ROOTS` | Rutas donde las herramientas git pueden operar | raíz del proyecto |
| `HTTP_CHECK_ALLOW_PRIVATE` | `1` permite health checks a IPs privadas | `0` |
| `CORS_ORIGIN` | Origen permitido para CORS | `*` |

## Herramientas (76)

| Módulo | Herramientas |
|---|---|
| **negocio** (9) | `inventario_consultar` · `inventario_actualizar` · `pedido_crear` · `pedido_consultar` · `pedido_actualizar_estado` · `cotizacion_generar` · `calculadora_impuestos` · `margen_utilidad` · `punto_equilibrio` |
| **facturación** (5) | `cfdi_generar` · `cfdi_desde_pedido` · `cfdi_validar_datos_fiscales` · `catalogo_sat_consultar` · `cfdi_retenciones_calcular` |
| **documentos** (6) | `documento_leer` · `documento_resumir` · `documento_extraer_datos` · `plantilla_guardar` · `plantilla_renderizar` · `plantilla_listar` |
| **análisis** (7) | `dataset_perfilar` · `dataset_analizar_columna` · `dataset_agrupar` · `dataset_exportar_json` · `datos_estadisticas` · `datos_correlacion` · `datos_serie_tiempo` |
| **devops** (6) | `git_status` · `git_log` · `git_diff` · `proyecto_resumen` · `http_health_check` · `log_analizar` |
| **taller** (4) | `mapa_proyecto` · `impacto_calcular` · `rescate_diagnostico` · `pendientes_listar` |
| **centinela** (7) | `seguridad_escanear` · `seguridad_plan` · `seguridad_verificar` · `dependencias_revisar` · `dependencias_vulnerables` · `permisos_auditar` · `aprobaciones_pendientes` |
| **parche** (6) | `falla_diagnosticar` · `parche_proponer` · `parche_probar` · `parche_aplicar` · `reparacion_revertir` · `reparaciones_listar` |
| **orquesta** (5) | `flujo_listar` · `flujo_ejecutar` · `flujo_estado` · `flujo_definir` · `aprobacion_solicitar` |
| **rúter** (4) | `modelo_elegir` · `modelo_resultado` · `coste_comparar` · `politica_modelos` |
| **diseño** (8) | `color_convertir` · `contraste_verificar` · `paleta_generar` · `tipografia_escala` · `tokens_generar` · `componente_generar` · `accesibilidad_auditar` · `estilo_revisar` |
| **núcleo** (2) | `mcp_suite_info` · `auditoria_consultar` |

Además: 2 **recursos MCP** (`mcp-suite-mx://config/actual`, `mcp-suite-mx://catalogo/tools`) y 2 **prompts** (`resumen_semanal_negocio`, `diagnostico_produccion`).

### Módulo diseño (color de última generación)

Ocho herramientas para vestir un producto sin salir del servidor: color en
**OKLCH** (el espacio que ya usan los navegadores, donde la luminosidad es la que
percibe el ojo), contraste obligatorio **WCAG 2.2** con segunda opinión **APCA**,
paletas con roles legibles, tipografía fluida, tokens en **CSS con capas** y en el
formato del grupo de diseño (**DTCG**), componentes en CSS moderno (anidado,
`color-mix()`, `@container`, `:focus-visible`, objetivo táctil de 44 px) y dos
auditorías: una de HTML y otra de CSS. Todo se calcula aquí; nada sale a internet.

```bash
# Marca → paleta, tokens y un componente, sin abrir un editor de diseño
paleta_generar      { "base": "#ff3df0", "modo": "oscuro" }
tokens_generar      { "base": "#ff3df0", "prefijo": "ks", "archivo": "tokens" }
componente_generar  { "tipo": "boton", "prefijo": "ks" }

# ¿Cumple el texto que ya tienes?
contraste_verificar { "pares": [{ "texto": "#a9a4bb", "fondo": "#07070a" }] }
accesibilidad_auditar { "ruta": "paginas/landing.html" }
estilo_revisar      { "ruta": "paginas/panel.css", "detalle": "solo_alto" }
```

El estilo con el que nace todo esto es el de [`docs/DISENO.md`](docs/DISENO.md).

## Desarrollo

```bash
npm run dev:stdio   # recarga en vivo, transporte stdio
npm run dev         # recarga en vivo, HTTP en :8787
npm run typecheck   # tsc sin emitir
npm test            # 215 pruebas (unitarias + integración MCP + HTTP con auth + TLS + CFDI + taller + centinela + parche + orquesta + rúter + multi-empresa + diseño)
npm run build       # compila a dist/
node dist/cli.js    # ayuda del CLI de administración
```

Estructura:

```
src/
├── index.ts        # arranque, contexto (BD, métricas, auditoría, limiter)
├── server.ts       # registro de módulos + filtrado por permisos + recursos/prompts
├── http.ts         # HTTP/panel: auth, rate headers, /metrics, /api/*
├── cli.ts          # keys create|list|revoke · audit · backup · aprobaciones|aprobar|rechazar · parches|revertir · flujos · modelos · empresas · seguridad · check
├── config.ts       # configuración (flags CLI + variables de entorno)
├── auth/           # keys.ts (SQLite, sha256, timing-safe) · politica.ts (scopes)
├── db/             # esquema, migración desde JSON, siembra, repositorios
├── lib/            # csv, stats, finance, cfdi, text, store, audit, metrics, rate-limit, backup, grafo, rescate,
│                   # taller-vivo, centinela (reglas + plan + verificación), aprobaciones (gate),
│                   # parche (diagnóstico/diff/copias), orquesta (flujos/actas), ruter (cascada/costes),
│                   # empresas (multi-empresa: espacios, resúmenes y comparativa)
└── tools/          # un archivo por módulo (empresas, negocio, facturacion, documentos, analisis, devops,
                    # taller, centinela, parche, orquesta, ruter)
```

## Endpoints HTTP

| Ruta | Descripción |
|---|---|
| `POST /mcp` | Endpoint MCP (JSON-RPC 2.0, Streamable HTTP). Requiere clave si `AUTH_MODE=required`. |
| `GET /health` | Estado, versión, uptime y modo de auth (siempre público). |
| `GET /metrics` | Métricas Prometheus. |
| `GET /api/catalogo` · `GET /api/herramientas` · `GET /api/config` | Catálogo y configuración efectiva. |
| `GET /api/claves` | Claves emitidas (sin hashes). Protegido si `AUTH_MODE=required`. |
| `GET /api/auditoria` | Bitácora con filtros (`tipo`, `herramienta`, `cliente`, `desde`, `limite`). |
| `GET /api/aprobaciones` | Cola de aprobaciones (pendientes por defecto, `?todas=1` para el histórico). Protegido. |
| `POST /api/aprobaciones/<id>` | Resolver desde el panel: `{"decision":"aprobar"\|"rechazar","motivo":"…"}`. Protegido y auditado. |
| `GET /api/parches` | Historial de parches con huella, veredicto de pruebas y respaldo. Protegido. |
| `GET /api/empresas` | Espacios multi-empresa; con `?comparar=1`, sus cifras lado a lado. Protegido. |
| `GET /panel` | Panel HTML autogenerado, con la cola de aprobaciones y el historial de parches en vivo (botones Aprobar/Rechazar). |
| `GET /herramientas` | Catálogo visual de las 76 herramientas y el proceso completo, de la orden a la respuesta. |
