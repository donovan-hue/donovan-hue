# Lista de verificación de la entrega — Suite MCP MX 0.2.0

**Fecha:** 11 de octubre de 2026.
**Cómo leerla:** `[x]` = terminado y probado. `[ ]` = pendiente.

---

## 1. Grado del proyecto

| Parte | Grado | Detalle |
|---|---|---|
| Construcción (fases 1 a 5A) | 100% | Todo lo acordado está construido y probado |
| Pruebas | 186 de 186 en verde | 17 archivos de prueba |
| Seguridad de dependencias | 0 vulnerabilidades | `npm audit` limpio |
| Paquete listo para instalar | 100% | Probado instalando el archivo .tgz en una carpeta limpia |
| CI de GitHub | Listo | Los 9 bloques pasan, simulados en local |
| Publicación (npm + GitHub + registro) | Pendiente solo de tus 3 comandos | Unos 5 minutos de tu parte |

**Resumen:** el proyecto está terminado y funcionando. Lo único que falta es publicarlo, y eso requiere tu cuenta (yo no puedo entrar a ella).

---

## 2. Lo que pediste, punto por punto

- [x] **Todo en español** (manuales, mensajes, panel, ayuda del CLI).
- [x] **Un solo servidor para varias empresas.** Un despacho contable o un dueño con dos giros usa el mismo servidor; cada empresa con su inventario, pedidos y plantillas separados.
- [x] **Datos fiscales propios por empresa** para los borradores de CFDI (RFC, régimen, serie).
- [x] **Claves de API atables a una empresa:** la clave ve solo esa empresa y no puede cambiar de ni comparar con otras.
- [x] **Autenticación con claves por módulo** (reversaste la idea de "sin autenticación").
- [x] **Sin servicios externos** (nada de PAC, WhatsApp, bancos, SAT masivo).
- [x] **Cuestionarios fuera del código** (viven en `/home/user/privado`, nunca en el repositorio).
- [x] **Los 6 plugins dev del "Taller" son la especificación** y están construidos: taller (mapa), centinela (seguridad), parche, orquesta, rúter y el extra de multi-empresa.
- [x] **Mapa vivo, no una foto; realismo 3D en vivo** (el taller muestra el proyecto como gemelo digital).
- [x] **Ejecutado sin parar a pedir permiso** (urgencia de "para ayer").
- [x] **CFDI 4.0 en alcance acordado:** borrador sin sellar, validaciones, catálogos y XML.
- [x] **SQLite local** (better-sqlite3) y **TypeScript/Node**.
- [x] **Local y remoto:** stdio (para tu editor o agente) y HTTP (para red).
- [x] **Sin tema gobierno/SAT en las ideas nuevas** y con formato "la normal tiene X, la nuestra Y" en el documento 04.
- [x] **Publicación preparada:** repositorio remoto configurado, paquete verificado, registro listo.
- [ ] **Publicar** (tus credenciales): `git push`, `npm publish`, `mcp-publisher publish`.

---

## 3. Fases entregadas

- [x] **Fase 1 — Herramientas y doble transporte.** Catálogo de herramientas MCP y el servidor funcionando por stdio y por HTTP.
- [x] **Fase 2 — Seguridad y operación.** Claves de API, límite de peticiones (120 por minuto), auditoría, métricas y base SQLite.
- [x] **Fase 3 — Facturación y robustez.** CFDI 4.0 (borrador + validaciones + catálogos + XML), HTTPS con certificado y respaldos con rotación.
- [x] **Fase 4 C/D — Pack dev.** Taller (mapa vivo 3D), centinela (seguridad), parche (reparación con aprobación), orquesta (flujos de trabajo) y rúter (elección de modelo). Evidencia: `docs/ENTREGA-PACK-4CD.md` y `docs/evidencia-5A-pack-completo.txt`.
- [x] **Fase 5A — Multi-empresa.** 6 herramientas nuevas, folios por empresa, comparativa y claves atadas. Evidencia: `docs/ENTREGA-5A-MULTI-EMPRESA.md` y `docs/evidencia-5A-demo.txt`.

---

## 4. Temas principales y subtemas

### 4.1 Los 68 comandos del servidor, módulo por módulo

#### Núcleo (2)

| Función | Qué hace y qué la dispara |
|---|---|
| `mcp_suite_info` | Ficha del servidor: versión, herramientas que permite tu clave, configuración y permisos. Se dispara al empezar, para saber qué puedes hacer. |
| `auditoria_consultar` | Lee la bitácora: quién cambió qué y cuándo (incluye intentos fallidos y bloqueos por tasa). Se dispara cuando preguntas "¿quién hizo esto?". |

#### Negocio (10)

Tema: el día a día de un negocio, todo por empresa.

| Función | Qué hace y qué la dispara |
|---|---|
| `inventario_consultar` | Busca productos por SKU, texto, categoría o "bajo mínimo". Se dispara con "¿cuánto stock hay de TEC-001?" o "muéstrame lo que está bajo mínimo". |
| `inventario_actualizar` | Movimientos: entrada (suma), salida (resta, falla si no alcanza) y ajuste (fija el conteo). Se dispara con "llegaron 20 piezas" o "hice conteo físico". |
| `inventario_importar` | Carga el catálogo desde un CSV, con simulación previa. Se dispara al arrancar una empresa nueva: "carga este catálogo". |
| `pedido_crear` | Crea un pedido con líneas y totales. Se dispara con "hazme un pedido para el cliente X". |
| `pedido_consultar` | Busca pedidos por folio, cliente o estado. Se dispara con "¿cómo va el pedido P-0003?". |
| `pedido_actualizar_estado` | Avanza el ciclo: borrador, confirmado, en producción, enviado, entregado, cancelado. Se dispara con "marca el pedido como enviado". |
| `cotizacion_generar` | Cotización lista para enviar: líneas, descuento, IVA y total. Se dispara con "cótizale esto al cliente". |
| `calculadora_impuestos` | Agrega o desglosa IVA. Se dispara con "¿cuánto es con IVA?" o "desglosa el IVA de 10,000". |
| `margen_utilidad` | Utilidad, margen y precio con IVA a partir de costo y precio. Se dispara con "¿cuánto gano con esto?". |
| `punto_equilibrio` | Cuántas unidades necesitas vender para cubrir costos fijos. Se dispara con "¿cuánto tengo que vender para no perder?". |

#### Empresas (6)

Tema: varios negocios en un mismo servidor.

| Función | Qué hace y qué la dispara |
|---|---|
| `empresas_listar` | Lista los espacios con sus datos fiscales y cuál está activo. Se dispara con "¿qué empresas tengo?". |
| `empresa_crear` | Crea una empresa nueva, con todo aislado. Se dispara con "ábreme el espacio de la ferretería". |
| `empresa_actual` | Consulta o cambia la empresa activa. Se dispara con "cámbiate a la panadería". |
| `empresa_editar` | Corrige nombre, RFC, régimen o serie. Se dispara con "actualiza los datos fiscales". |
| `empresa_resumen` | Foto ejecutiva de una empresa: productos, valor del inventario, ventas, riesgos de stock. Se dispara con "dame el resumen del negocio". |
| `empresas_comparar` | Compara varias empresas lado a lado. Se dispara con "¿cómo va el despacho este mes?". |

#### Facturación (5)

Tema: CFDI 4.0 (borrador, sin timbrar, según el alcance acordado).

| Función | Qué hace y qué la dispara |
|---|---|
| `cfdi_generar` | Borrador de CFDI con validaciones y XML listo para timbrar. Se dispara con "hazme la factura de este cobro". |
| `cfdi_desde_pedido` | Toma un pedido existente y arma su factura. Se dispara con "factura el pedido P-0003". |
| `cfdi_validar_datos_fiscales` | Revisa RFC, régimen y uso del CFDI antes de facturar. Se dispara con "¿están bien los datos de este cliente?". |
| `catalogo_sat_consultar` | Consulta los catálogos incluidos: regímenes, usos de CFDI, formas y métodos de pago. Se dispara con "¿qué clave de uso corresponde?". |
| `cfdi_retenciones_calcular` | Desglose de honorarios con retenciones (ejemplo comprobado: 10,000 dan 10,408.33). Se dispara con "sácame la factura de honorarios con retención". |

#### Taller (4)

Tema: entender un proyecto de código, vivo y no como una foto.

| Función | Qué hace y qué la dispara |
|---|---|
| `mapa_proyecto` | Construye el gemelo digital: módulos, archivos, dependencias, rutas, pruebas. Se dispara con "enséñame el mapa del proyecto". |
| `impacto_calcular` | Si toco este archivo o esta función, qué más se rompe. Se dispara antes de un cambio: "¿qué pasa si cambio cfdi.ts?". |
| `rescate_diagnostico` | Reconstruye un proyecto abandonado o dañado sin recordar su historia. Se dispara con "retomo este proyecto, ¿por dónde empiezo?". |
| `pendientes_listar` | Lista lo que falta (pendientes, código sin implementar) ordenado por impacto. Se dispara con "¿qué me falta aquí?". |

#### Centinela (7)

Tema: seguridad real, con evidencia.

| Función | Qué hace y qué la dispara |
|---|---|
| `seguridad_escanear` | Escanea secretos expuestos, rutas sin proteger, configuraciones débiles. Se dispara con "revisa la seguridad". |
| `seguridad_plan` | Convierte los hallazgos en plan priorizado (ahora, esta semana, algún día). Se dispara con "¿qué arreglo primero?". |
| `seguridad_verificar` | Vuelve a escanear y compara contra la línea base. Se dispara con "¿ya quedó?" después de corregir. |
| `aprobaciones_pendientes` | Muestra la cola de aprobaciones humanas. Se dispara con "¿hay algo esperándome?". |
| `permisos_auditar` | Revisa cómo queda protegida CADA ruta (middlewares, roles), no avisos genéricos. Se dispara con "¿mis rutas están realmente protegidas?". |
| `dependencias_vulnerables` | Avisos reales con `npm audit`, con caché. Se dispara con "¿mis librerías tienen fallas?". |
| `dependencias_revisar` | Revisión sin conexión, contra tabla local, más revisión de scripts. Se dispara con "revisa dependencias aunque no haya internet". |

#### DevOps (6)

Tema: el estado del entorno y del repositorio.

| Función | Qué hace y qué la dispara |
|---|---|
| `proyecto_resumen` | Resume una carpeta: tipo de proyecto, estructura, scripts, estado. Se dispara con "¿qué es esta carpeta?". |
| `git_status` | Rama, adelante o atrás del remoto, cambios sin guardar. Se dispara con "¿cómo está mi repositorio?". |
| `git_log` | Últimos commits, con filtros. Se dispara con "¿qué se hizo últimamente?". |
| `git_diff` | Diferencias sin guardar, resumidas. Se dispara con "¿qué cambié?" antes de un commit. |
| `http_health_check` | Prueba un endpoint y reporta código, tiempo y respuesta. Se dispara con "¿está vivo mi servicio?". |
| `log_analizar` | Analiza un archivo de log: niveles, patrones, errores repetidos. Se dispara con "¿por qué falló, según el log?". |

#### Documentos (6)

Tema: leer, resumir y llenar documentos.

| Función | Qué hace y qué la dispara |
|---|---|
| `documento_leer` | Lee archivos de texto (md, txt, csv, json, log). Se dispara con "léeme este archivo". |
| `documento_resumir` | Métricas y estructura de un texto (palabras, oraciones, tiempo de lectura). Se dispara con "resúmeme esto". |
| `documento_extraer_datos` | Extrae correos, teléfonos, RFC y direcciones de un texto, y los valida. Se dispara con "saca los datos de contacto". |
| `plantilla_renderizar` | Rellena una plantilla guardada (marcadores tipo `{{cliente}}`). Se dispara con "llena la plantilla con estos datos". |
| `plantilla_guardar` | Crea o actualiza una plantilla. Se dispara con "guarda este formato como plantilla". |
| `plantilla_listar` | Lista las plantillas guardadas y qué variables piden. Se dispara con "¿qué plantillas tengo?". |

#### Análisis (7)

Tema: números y archivos CSV.

| Función | Qué hace y qué la dispara |
|---|---|
| `dataset_perfilar` | Estructura de un CSV: filas, columnas, tipos, nulos. Se dispara con "¿qué trae este archivo?". |
| `dataset_analizar_columna` | Estadística de una columna numérica: media, mediana, desviación. Se dispara con "analiza la columna ventas". |
| `dataset_agrupar` | Tabla dinámica: agrupa y suma o promedia. Se dispara con "ventas por mes" o "total por categoría". |
| `dataset_exportar_json` | Convierte CSV a JSON, Markdown o texto. Se dispara con "pásame esto a JSON". |
| `datos_estadisticas` | Estadística de una lista de números que tú das. Se dispara con "sácame el promedio de estos números". |
| `datos_correlacion` | Correlación de Pearson entre dos series, con interpretación. Se dispara con "¿esto sube cuando aquello sube?". |
| `datos_serie_tiempo` | Media móvil, tendencia y pronóstico simple. Se dispara con "¿cómo se va a comportar el próximo mes?". |

#### Parche (6)

Tema: reparar código con red de seguridad.

| Función | Qué hace y qué la dispara |
|---|---|
| `falla_diagnosticar` | Causas probables SIN adivinar: cada una con su evidencia (archivo y línea). Se dispara con "esto truena, ¿qué pasó?". |
| `parche_proponer` | Registra un parche como sustituciones exactas, con huella. Se dispara con "propón el arreglo". |
| `parche_probar` | Prueba el parche en una copia aislada (pruebas antes y después). Se dispara con "pruébalo sin tocar nada". |
| `parche_aplicar` | Aplica al repositorio real con respaldo previo y aprobación humana vigente. Se dispara con "aplícalo" y exige tu aprobación. |
| `reparacion_revertir` | Deshace un parche aplicado (nunca pide aprobación: es la salida de seguridad). Se dispara con "deshazlo". |
| `reparaciones_listar` | Historial de parches con estado, huella y archivos. Se dispara con "¿qué se ha reparado?". |

#### Orquesta (5)

Tema: flujos de trabajo de varios pasos.

| Función | Qué hace y qué la dispara |
|---|---|
| `flujo_listar` | Los flujos disponibles (5 de batalla probada). Se dispara con "¿qué flujos tengo?". |
| `flujo_ejecutar` | Corre un flujo paso a paso y deja acta con duración y resultado. Se dispara con "ejecuta verificar despliegue". |
| `flujo_estado` | Últimas corridas y flujos que están fallando. Se dispara con "¿cómo salió la última corrida?". |
| `flujo_definir` | Crea un flujo propio combinando pasos permitidos. Se dispara con "hazme un flujo que haga esto y aquello". |
| `aprobacion_solicitar` | Pide permiso humano para una operación delicada. Se dispara cuando algo sensible necesita tu visto bueno. |

#### Rúter (4)

Tema: elegir modelo de IA y medir coste por resultado.

| Función | Qué hace y qué la dispara |
|---|---|
| `modelo_elegir` | Recomienda modelo y cascada de escalado (empieza barato, sube si falla). Se dispara antes de una tarea: "¿qué modelo uso para esto?". |
| `modelo_resultado` | Registra si la tarea sirvió (pruebas, aceptación). Se dispara al terminar: "sí funcionó" o "no sirvió". |
| `coste_comparar` | Coste por tarea exitosa con tus propios datos, no por tokens. Se dispara con "¿qué modelo me sale más barato de verdad?". |
| `politica_modelos` | Reglas por tipo de tarea: presupuesto y solo-local si hace falta. Se dispara con "para asuntos legales, nada a la nube". |

---

## 5. Lo demás que trae el proyecto (fuera del catálogo de 68)

- [x] **13 comandos de administración en el CLI:** keys, audit, backup, aprobaciones, aprobar, rechazar, parches, revertir, flujos, modelos, empresas, seguridad, check.
- [x] **API HTTP y panel web:** panel con el estado y los módulos, taller con el mapa 3D, y rutas de salud, métricas, catálogo, claves, auditoría, aprobaciones, parches y empresas.
- [x] **Seguridad de la infraestructura:** claves con formato propio y scopes, límite de peticiones, auditoría de solo agregar, métricas, HTTPS y respaldos con rotación.
- [x] **Formato de instalación:** dos binarios (`mcp-suite-mx` y `mcp-suite-mx-cli`), `--version` y `--help`, licencia MIT, Docker y CI.
- [x] **Registro MCP:** archivo `server.json` listo y el nombre del paquete coherente con lo que exige el registro.

## 6. Cómo se comprobó

- [x] **186 pruebas** automáticas en 17 archivos, todas en verde.
- [x] **CI completa simulada en local:** tipos, auditoría de dependencias, pruebas, compilación, paquete instalado desde cero, arranque por stdio, CLI, servidor HTTP con y sin clave, respaldos y HTTPS. Los 9 bloques pasan.
- [x] **Paquete instalado de verdad:** se empaquetó y se instaló en una carpeta limpia; responde `--version`, arranca, emite claves y lista las 68 herramientas.
- [x] **Centinela sobre el propio proyecto:** puntaje 100 con cero hallazgos.
- [x] **Evidencia guardada:** `docs/evidencia-5A-pack-completo.txt` (8 secciones) y `docs/evidencia-5A-demo.txt`.
- [x] **Copia instalable:** `/home/user/entrega/mcp-suite-mx-0.2.0.tgz`.
- [x] **Servidor en vivo:** puerto 8787 con el panel funcionando.

## 7. Pendientes

- [ ] **Publicar** con tus cuentas: `git push -u origin main`, luego `npm login && npm publish`, y al final `mcp-publisher login github && mcp-publisher publish`. La guía paso a paso está en `docs/PUBLICAR.md`.
- [ ] **Diferido por decisión:** timbrado real con PAC, CSD local (certificado en tu equipo), OAuth/SSO, webhooks y conexión con ERP/CRM.
- [ ] **Aclarar una duda suelta:** "estriñe" — no se sabe a qué se refería.
