# Documento de alcance y dimensionamiento — Suite MCP MX

**Proyecto:** `mcp-suite-mx` — plataforma MCP (Model Context Protocol) con 11 módulos de herramientas (incluye multi-empresa, facturación CFDI 4.0, el taller de análisis de código, centinela de seguridad y el pack dev: parche, orquesta y rúter).
**Estado:** v0.1.0 · **Fase 1 entregada** (herramientas + doble transporte) · **Fase 2 entregada** (auth, rate limiting, auditoría, métricas, SQLite) · **Fase 3 entregada** (CFDI 4.0, HTTPS, respaldos).
**Fecha:** 11 de octubre de 2026.

---

## 1. Perfil del proyecto

| Pregunta | Respuesta | Implicación |
|---|---|---|
| ¿Qué problema resuelve? | Herramientas de dominio propio (no un simple wrapper de API externa) | Lógica de negocio propia que mantener y evolucionar |
| ¿Stack? | Sin preferencia → recomendación: **TypeScript / Node.js** | SDK oficial de MCP más maduro, un solo lenguaje para stdio y HTTP |
| ¿Magnitud esperada? | **Producto completo** | Auth, despliegue remoto, CI/CD, pruebas y documentación |
| ¿Dónde se usa? | **Local (stdio) + remoto (HTTP)** | Doble transporte obligatorio desde el día 1 |
| ¿Dominio? | Los 11 dominios: empresas, negocio, facturación, documentos, análisis, DevOps, taller, centinela, parche, orquesta, rúter | 11 módulos independientes |
| ¿Cantidad de herramientas? | "Más de 12" | **68 herramientas implementadas** |
| ¿Autenticación? | Fase 1 sin auth → **Fase 2 con API keys** | Implementada: permisos por módulo + scopes de solo lectura |

## 2. Veredicto de magnitud

**Proyecto mediano-grande** (categoría "producto"), comparable a un micro-SaaS interno:

| Indicador | Valor |
|---|---|
| Código fuente | ~5,240 líneas TS (33 archivos) + 260 líneas de panel HTML |
| Pruebas | 184 pruebas (unitarias + integración MCP + HTTP con auth + HTTPS + CFDI + respaldos + taller/gemelo vivo + centinela/gate + parche + orquesta + rúter + multi-empresa + paquete instalado) |
| Herramientas MCP | 66 en 11 módulos + 2 de núcleo |
| Recursos y prompts MCP | 2 recursos + 2 prompts |
| Superficie de despliegue | local (stdio), Docker + compose, CI/CD con humos de seguridad |
| Dependencias de producción | 3 (SDK oficial de MCP, Zod, better-sqlite3) |

**Esfuerzo de referencia** (desarrollador senior, jornadas de 6 h efectivas):

| Fase | Alcance | Estimación | Estado |
|---|---|---|---|
| **Fase 1** | Arquitectura, 29 herramientas, datos semilla, panel, pruebas, Docker, CI, docs | 8–12 días | ✅ Entregada |
| **Fase 2** | API keys + scopes, rate limiting, auditoría inmutable, métricas Prometheus, SQLite con migración, CLI de administración | 10–15 días | ✅ Entregada |
| **Fase 3** | CFDI 4.0 (borrador, XML sin sellar, validaciones, retenciones), HTTPS nativo, respaldos en caliente con rotación | 8–12 días | ✅ Entregada (subconjunto) |
| **Fase 4A** | Taller: GEMELO (mapa vivo + impacto) y RESCATE (diagnóstico + plan) sobre cualquier proyecto dentro de rutas autorizadas | 10–15 días | ✅ Entregada |
| **Fase 4B** | Centinela: escaneo de seguridad local (secretos, configs, rutas, dependencias), plan de corrección comprobable, verificación contra línea base y gate de aprobación humana auditado | 6–9 días | ✅ Entregada |
| **Fase 4C** | Parche: diagnóstico con evidencia, parche revisable, pruebas antes/después en copia aislada, aplicación con aprobación humana + respaldo y reversión en un comando; Centinela completo (permisos reales por ruta + npm audit con caché) | 12–18 días | ✅ Entregada |
| **Fase 4D** | Orquesta (5 flujos de dev + flujos propios con actas y aprobaciones) y Rúter (cascada de modelos con coste por resultado útil) | 10–15 días | ✅ Entregada |
| **Fase 5A** | Multi-empresa: espacios aislados (inventario, pedidos, plantillas), importación de catálogo por CSV, claves de API atadas a una empresa, datos fiscales y series por empresa para CFDI, resumen y comparativa | 8–12 días | ✅ Entregada |
| **Fase 5B** | Timbrado real vía PAC, OAuth 2.1, integración ERP/CRM, webhooks | 15–20 días | Pendiente |

## 3. Arquitectura

```
                    ┌──────────────────────────────────────────────────────┐
                    │                    mcp-suite-mx                      │
                    │                                                      │
 Claude Desktop ────┤ stdio ──► buildServer(ctx) ──► 68 herramientas       │
 Cursor / Claude    │         · empresas (6) · negocio (10) · documentos (6) │
 Code (local)       │            · análisis (7) · devops (6) · núcleo (2)  │
                    │            · recursos + prompts MCP                  │
 Clientes remotos ──┤ HTTP  ──► CAPAS TRANSVERSALES (fase 2)               │
 (equipo/clientes)  │  POST /mcp  ├─ 1. Autenticación Bearer (scopes)      │
                    │             ├─ 2. Filtrado del catálogo por permisos │
                    │             ├─ 3. Límite de tasa (token bucket)      │
                    │             ├─ 4. Métricas Prometheus                │
                    │             └─ 5. Auditoría append-only              │
                    │                        │                             │
                    │                        ▼                             │
                    │  SQLite (WAL): inventario · pedidos · plantillas     │
                    │                api_keys · meta (transaccional)       │
                    │  NDJSON: runtime/auditoria.ndjson (inmutable)        │
                    │  Archivos: datasets CSV · logs · XML de facturación  │
                    │  Respaldos: VACUUM INTO + manifiesto sha256 (rotación)│
                    └──────────────────────────────────────────────────────┘
```

Decisiones clave (fase 1 y 2):

- **SQLite WAL como almacén transaccional**: pedidos y descuentos de stock ocurren en una sola transacción (antes eran dos archivos JSON). Migración automática desde los JSON de la fase 1 al primer arranque, sin borrar los originales.
- **Claves hasheadas (sha256) + comparación en tiempo constante**: el secreto solo se muestra al crearlo; en disco nunca queda en claro. Los scopes viven en la base.
- **Permisos como filtro del catálogo**: una clave con `negocio:ro` no ve `inventario_actualizar`; la herramienta responde "not found" (no revela qué existe).
- **Auditoría inmutable por diseño**: NDJSON append-only, línea por evento; las líneas corruptas se ignoran al leer pero nunca se reescriben.
- **Métricas sin dependencias**: registry propio con formato de exposición Prometheus (contadores + histogramas).
- **Errores como texto útil en español**: mensajes accionables para que el LLM corrija el rumbo (stock insuficiente, transición inválida, límite excedido con segundos de reintento).

## 4. Catálogo de herramientas (35)

### Núcleo (2)
| Herramienta | Para qué |
|---|---|
| `mcp_suite_info` | Autodescubrimiento: versión, módulos, configuración, **tus permisos** |
| `auditoria_consultar` | Quién cambió qué y cuándo (filtros por tipo, herramienta, cliente, fecha) |

### Facturación — CFDI 4.0 (5)
| Herramienta | Para qué |
|---|---|
| `cfdi_generar` | Borrador + XML sin sellar con validaciones del Anexo 20; opcionalmente guarda el XML |
| `cfdi_desde_pedido` | Factura un pedido existente sin recapturar conceptos |
| `cfdi_validar_datos_fiscales` | Revisa RFC, régimen, uso del CFDI, forma/método de pago y reglas de público en general |
| `catalogo_sat_consultar` | Regímenes, usos, formas/métodos de pago, monedas y unidades más usados |
| `cfdi_retenciones_calcular` | ISR 1.25% + IVA 10.6667% sobre la base (honorarios): total real a pagar |

### Negocio (9)
`inventario_consultar` · `inventario_actualizar` · `pedido_crear` (transaccional) · `pedido_consultar` · `pedido_actualizar_estado` (ciclo de vida validado) · `cotizacion_generar` (IVA 16% + retenciones ISR/IVA) · `calculadora_impuestos` · `margen_utilidad` · `punto_equilibrio`

### Documentos (6)
`documento_leer` · `documento_resumir` (legibilidad Fernández-Huerta) · `documento_extraer_datos` (email, teléfono, RFC, CURP, montos, fechas, C.P.) · `plantilla_guardar` · `plantilla_renderizar` · `plantilla_listar`

### Análisis (7)
`dataset_perfilar` · `dataset_analizar_columna` (cuartiles, outliers, tendencia) · `dataset_agrupar` (tabla dinámica) · `dataset_exportar_json` · `datos_estadisticas` · `datos_correlacion` · `datos_serie_tiempo` (media móvil, pronóstico, CAGR)

### Taller — gemelo digital (4)
| Herramienta | Para qué |
|---|---|
| `mapa_proyecto` | El mapa del proyecto: módulos, dependencias, rutas, servicios, entradas, huérfanos y duplicados (y la página interactiva `/taller` con el gemelo vivo) |
| `impacto_calcular` | Qué se rompe al cambiar un archivo o símbolo, con evidencia y pruebas a correr |
| `rescate_diagnostico` | Reconstruye arquitectura + huecos + plan ordenado para retomar un proyecto abandonado |
| `pendientes_listar` | TODO/FIXME/sin implementar ordenados por impacto real |

### Centinela — seguridad (5)
| Herramienta | Para qué |
|---|---|
| `seguridad_escanear` | Hallazgos priorizados con puntaje y semáforo (secretos redactados); opcionalmente guarda la línea base |
| `seguridad_plan` | Plan priorizado donde cada paso dice qué hacer, en qué archivos y cómo comprobarlo; solicita/valida la aprobación humana para aplicarlo |
| `seguridad_verificar` | Re-escanea y compara contra la línea base: resueltos, permanecen y nuevos, con el cambio de puntaje |
| `dependencias_revisar` | Avisos conocidos (tabla local), versiones sin fijar, scripts de instalación peligrosos y falta de lockfile |
| `dependencias_vulnerables` | Avisos reales vía `npm audit` con caché por contenido (24 h) y respaldo a tabla local |
| `permisos_auditar` | Cómo queda protegida CADA ruta: guard en la línea, guard global previo, indicio en el archivo o descubierta |
| `aprobaciones_pendientes` | Cola del gate: qué espera autorización humana y cómo resolverla |

### Parche — reparar con red de seguridad (6)
| Herramienta | Para qué |
|---|---|
| `falla_diagnosticar` | Causa probable de un error/log/prueba rota, con evidencia archivo:línea (import roto, ENOENT, undefined, expectativas, red, sintaxis) |
| `parche_proponer` | Parche revisable (diff + huella); valida que el bloque «antes» exista tal cual y liga la aprobación al contenido |
| `parche_probar` | Pruebas antes/después en copia aislada; veredicto arregla/mejora/igual/empeora; límite de intentos |
| `parche_aplicar` | Aplica al repo real solo con aprobación humana vigente (huella idéntica) y respaldo por archivo |
| `reparacion_revertir` | Restaura desde el respaldo en un comando (deshacer no requiere permiso) |
| `reparaciones_listar` | Historial con estados, veredictos y guardarraíles activos |

### Empresas — multi-empresa (6)
| Herramienta | Para qué |
|---|---|
| `empresas_listar` | Espacios de trabajo con datos fiscales y cuál está activo |
| `empresa_crear` | Espacio nuevo (RFC, régimen, CP, serie de folios) totalmente aislado |
| `empresa_actual` | Ver o cambiar la empresa activa de la sesión (las claves atadas no cambian) |
| `empresa_editar` | Corregir datos o archivar (oculta sin borrar; no permite archivar la activa) |
| `empresa_resumen` | Inventario, ventas por estado, ticket promedio y riesgos de stock de una empresa |
| `empresas_comparar` | Todas las empresas lado a lado, sin mezclar cifras |

### Orquesta — flujos con bitácora (5)
| Herramienta | Para qué |
|---|---|
| `flujo_listar` | 5 flujos de batalla probada + los propios, con sus pasos |
| `flujo_ejecutar` | Corre un flujo (dry-run para el plan) y deja acta JSON en data/orquesta/runs/ |
| `flujo_estado` | Últimas corridas, pasos fallidos y aprobaciones pendientes |
| `flujo_definir` | Crea flujos propios con los tipos de paso permitidos |
| `aprobacion_solicitar` | Pide permiso humano para cualquier operación sensible (con huella opcional) |

### Rúter — modelos con coste por resultado útil (4)
| Herramienta | Para qué |
|---|---|
| `modelo_elegir` | Cascada de modelos para la tarea (barato primero; en criticidad alta, el fuerte) |
| `modelo_resultado` | Registra si la tarea sirvió, con evidencia obligatoria |
| `coste_comparar` | Coste por tarea exitosa medido con tus datos (no por token) |
| `politica_modelos` | Presupuesto, preferencias y «solo local» por tipo de tarea |

### Centinela — seguridad (7)
`git_status` · `git_log` · `git_diff` · `proyecto_resumen` · `http_health_check` · `log_analizar`

## 5. Seguridad implementada

### Fase 1
- Rutas de archivo confinadas (anti path-traversal) con listas permitidas.
- Git limitado a `ALLOWED_GIT_ROOTS`; ninguna herramienta escribe en repos.
- `http_health_check` bloquea direcciones privadas salvo activación explícita.
- Límites de tamaño en lecturas; logs solo a **stderr** (stdout reservado al protocolo MCP).

### Fase 2
| Control | Detalle |
|---|---|
| **Autenticación** | `AUTH_MODE=off/optional/required`. En `required`, `POST /mcp` exige `Authorization: Bearer mcp_…`. En producción, `off` con bind público se rechaza al arrancar. |
| **Permisos por scope** | `*`, `modulo`, `modulo:ro` (solo lectura). El catálogo se filtra por clave; las herramientas no autorizadas no existen para ese cliente. |
| **Gestión de claves** | CLI (`keys create/list/revoke`), expiración opcional, revocación inmediata, conteo de usos y último uso. Hasheadas con sha256. |
| **Límite de tasa** | Token bucket por cliente (`RATE_LIMIT_RPM`, `RATE_LIMIT_BURST`), headers `X-RateLimit-*`, mensaje con segundos de reintento. Nota: en memoria — multi-instancia requiere Redis. |
| **Auditoría** | Eventos: `tool_destructiva` (ok/fallo, args truncados), `auth_fallo`, `rate_limit`, `clave_creada`, `clave_revocada`. Consultable por herramienta MCP, API y CLI. |
| **Observabilidad** | `/metrics` Prometheus: llamadas por herramienta/módulo/resultado, duración (histograma), peticiones HTTP por ruta y código, fallos de auth, bloqueos de tasa, uptime. |
| **Integridad de datos** | Transacciones SQLite para pedidos y stock; WAL + busy_timeout; `foreign_keys=ON`. |
| **Transporte cifrado (fase 3)** | HTTPS nativo con `TLS_CERT`/`TLS_KEY` + HSTS; certificado autofirmado para pruebas vía script. |
| **Respaldos (fase 3)** | `VACUUM INTO` en caliente + auditoría + datasets + XML, con manifiesto de hashes sha256 y rotación configurable. |
| **Trazabilidad fiscal (fase 3)** | Toda generación de CFDI queda en la bitácora de auditoría (cliente, args truncados, resultado). |

## 6. Operación

| Aspecto | Cómo |
|---|---|
| Local (stdio) | `npm run dev:stdio` / `node dist/index.js --transport stdio` |
| Remoto (HTTP) | `AUTH_MODE=required npm run start:http` → `http://host:8787/mcp` |
| Crear clave | `node dist/cli.js keys create --nombre staff --scopes negocio,analisis` |
| Ver bitácora | `node dist/cli.js audit --limite 20` o `GET /api/auditoria` |
| Diagnóstico | `node dist/cli.js check` (config efectiva, claves activas, rutas) |
| Métricas | `GET /metrics` (Prometheus) |
| Panel | `GET /panel` (público, sin datos de negocio) |
| Docker | `docker compose up -d --build` (volumen `./data` persiste suite.db y auditoría) |
| CI | GitHub Actions: tipos → 184 pruebas → build → humo stdio → humo de auth (401/200) → humos CFDI/respaldo/HTTPS → imagen Docker con auth |

## 7. Lo que falta para producción plena (fase 4)

1. **Timbrado real vía PAC**: hoy se genera el XML sin sellar (el flujo correcto: tú o tu PAC lo sellan con el CSD). Falta el conector con un PAC (Facturama, SW, Finkok…) y el manejo del complemento de pago (PPD).
2. **Multi-tenant / multi-organización**: las claves comparten el mismo conjunto de datos; para venderlo a varias empresas se necesita aislamiento por organización.
3. **OAuth 2.1 / SSO** si se publica como servicio para terceros; las API keys ya cubren equipos internos.
4. **Límite de tasa distribuido** (Redis) si se levantan varias instancias detrás de balanceador.
5. **Integraciones de negocio**: webhooks de pedidos, ERP/CRM.
6. **Respaldos externos**: hoy se guardan en `data/backups/`; conviene replicarlos a S3/Backblaze y probar restauración periódicamente.

*Nota sobre retenciones:* el cálculo de retención de IVA se corrigió en fase 3 para aplicar el porcentaje (10.6667%) **sobre la base** —equivalente a 2/3 del IVA trasladado— y no sobre el IVA, que era el error de la fase 1.

## 8. Riesgos y mitigaciones

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| Fuga de una API key | Media | Alto | Scopes mínimos, expiración opcional, revocación inmediata y auditoría de usos |
| El modelo llama herramientas destructivas por error | Media | Alto | Anotaciones `destructiveHint`, confirmaciones implícitas en la descripción, validaciones estrictas y bitácora con args |
| Saturación por cliente abusivo | Baja | Medio | Límite de tasa por clave + bloqueos visibles en métricas |
| Corrupción de datos | Baja | Alto | Transacciones SQLite (WAL), escrituras atómicas, migración que no borra origen |
| Crecimiento del catálogo | Alta | Medio | Registro modular por archivo; cada módulo es autocontenido |

## 9. Definición de terminado ✅

**Fase 1**
- [x] 29 herramientas documentadas con esquemas Zod
- [x] Doble transporte (stdio + HTTP) y panel autogenerado
- [x] Docker, CI y documentación de conexión

**Fase 4A–4D**
- [x] Taller: mapa del proyecto (GEMELO) + impacto + rescate (RESCATE) con página interactiva 3D en vivo
- [x] Centinela: 18 reglas de detección (secretos, configs, rutas, dependencias, higiene) con secretos redactados
- [x] Plan de corrección con verificación concreta por paso, en JSON y Markdown
- [x] Verificación contra línea base (resueltos / permanecen / nuevos + puntaje) y comando CLI `seguridad` con código de salida para CI
- [x] Gate de aprobación humana con huella de contenido, vencimiento, resolución solo por CLI/panel y auditoría inmutable
- [x] Parche: diagnóstico con evidencia, parche revisable con huella, pruebas antes/después en copia aislada, aplicación con aprobación humana + respaldo y reversión en un comando (límite de intentos)
- [x] Orquesta: 5 flujos de dev + flujos propios, actas por corrida, dry-run y solicitud de aprobación genérica
- [x] Rúter: cascada de modelos por tarea, registro de resultados con evidencia y coste por resultado útil; política por tipo con «solo local»
- [x] Centinela completo: permisos reales por ruta (`permisos_auditar`) y avisos de dependencias con caché (`dependencias_vulnerables`)
- [x] 184 pruebas en verde (26 de centinela, 18 de taller, 17 del servidor MCP, 14 de HTTP con auth, 14 de CFDI, 14 de seguridad, 11 de multi-empresa, 11 de parche, 10 de orquesta, 7 de rúter y 3 del paquete tal como lo instala npm)

**Fase 5A**
- [x] Espacios multi-empresa con inventario, pedidos y plantillas aislados (clave compuesta empresa+SKU/nombre)
- [x] Migración automática de bases anteriores: todo lo existente pasa a la empresa por defecto sin perder datos
- [x] `inventario_importar` (CSV con dry-run) para arrancar el catálogo de un cliente nuevo
- [x] Claves de API atadas a una empresa (`keys create --empresa`) + `/api/empresas` con comparativa
- [x] CFDI: emisor y serie de folios tomados de la empresa activa (sin CFDI_EMISOR_*)
- [x] `empresa_resumen` y `empresas_comparar` (ventas, pedidos, inventario y riesgos por empresa)
- [x] 11 pruebas de multi-empresa: mismo SKU y mismo folio en dos empresas (P-0001 cada una), aislamiento por clave atada, CFDI con emisor y serie propios, migración de una base vieja y datos intactos

**Fase 3**
- [x] Módulo de facturación CFDI 4.0 (5 herramientas) con validaciones del Anexo 20 y XML sin sellar
- [x] Catálogos SAT de uso frecuente consultables por el modelo
- [x] Retenciones de honorarios correctas (ISR 1.25% e IVA 10.6667% sobre la base) y corrección del cálculo de fase 1
- [x] HTTPS con certificado propio + HSTS y script de certificado autofirmado para pruebas
- [x] Respaldos en caliente (`VACUUM INTO`) con manifiesto sha256, rotación y comandos CLI
- [x] 86 pruebas en verde (incluye CFDI, TLS con certificado real generado en la prueba y respaldos) y CI con humos de CFDI, respaldo y HTTPS

**Fase 2**
- [x] Autenticación por API keys con scopes por módulo y solo lectura
- [x] CLI de administración (crear/listar/revocar/auditar/check)
- [x] Límite de tasa por cliente con headers y mensajes de reintento
- [x] Auditoría inmutable (NDJSON) con consulta vía herramienta MCP, HTTP y CLI
- [x] Métricas Prometheus (llamadas, duración, HTTP, auth, tasa, uptime)
- [x] Persistencia SQLite transaccional + migración automática desde JSON
- [x] 104 pruebas en verde (incluye 401/200, scopes, límites, auditoría, métricas, CFDI, TLS, respaldos y taller)
- [x] Artefactos de despliegue actualizados (Dockerfile Debian slim, compose, CI con humos de seguridad)
