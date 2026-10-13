# Cerrar Kronos con la suite: qué cubren las 76 herramientas y qué falta

Este documento responde dos preguntas: si con las herramientas que ya tiene la suite se puede
terminar un proyecto como Kronos Space, y cuáles habría que desarrollar para que la suite sirva
como historial verificable de ese trabajo.

## 1. Dónde está Kronos hoy

El propio repositorio lleva su lista de cierre (`docs/CHECKLIST-CIERRE-TOTAL-KRONOS.md`), ordenada
en 17 bloques (T01 a T17). Medido el día de hoy:

| Estado | Casillas |
|---|---:|
| Verificado con evidencia | 28 |
| Pendiente de acción en código ([P-A]) | 25 |
| Pendiente de credencial o acceso ([P-P]) | 11 |
| Pendiente de decisión del dueño ([P-D]) | 10 |
| Puertas del acta de cierre (T16) | 10 |

## 2. Lo que la suite ya puede hacer sobre ese cierre

Las 76 herramientas resuelven bien todo lo que se mide **leyendo el proyecto**, sin salir a la red
ni tocar base de datos. Contra el checklist de Kronos:

| Bloque de Kronos | Herramientas que ya sirven | Alcance real |
|---|---|---|
| T01 sistema visual y kit | `accesibilidad_auditar`, `estilo_revisar`, `tokens_generar`, `contraste_verificar` | Auditoría estática del CSS y del kit; no mide el CSS computado |
| T11 CI/CD, T12 producción | `git_status`, `git_log`, `git_diff`, `http_health_check`, `http_health_check`+`proyecto_resumen` | Estado del repo, salud del servicio y configuración desplegada |
| T13 pruebas | `flujo_ejecutar`, `flujo_estado` | Corre la cadena y deja acta con tiempos |
| T14 documentación | `documento_leer`, `documento_resumir`, `plantilla_renderizar` | Corregir afirmaciones y actualizar matrices |
| T15 aprobaciones | `aprobacion_solicitar`, `aprobaciones_pendientes` | Registro de lo que autoriza el dueño |
| T17 duración de corridas | `log_analizar` | Agrupa y clasifica el log de una corrida |
| Transversal | `mapa_proyecto`, `impacto_calcular`, `pendientes_listar`, `rescate_diagnostico`, `seguridad_escanear`, `seguridad_verificar`, `permisos_auditar`, `dependencias_vulnerables`, `falla_diagnosticar`, `parche_proponer`, `parche_probar`, `parche_aplicar`, `reparacion_revertir` | Análisis, diagnóstico y reparación con reversión |

En números: **de las 46 casillas pendientes, las herramientas actuales alcanzan unas 15**. Todas
las demás piden algo que la suite todavía no sabe hacer: abrir un navegador, conectarse a la base,
llamar al proveedor real, disparar carga o levantar un acta.

## 3. Las nueve herramientas que faltan

Cada una cierra un hueco concreto del cierre de Kronos y sirve igual para cualquier otro proyecto.

| # | Herramienta | Módulo | Qué hace | Cierra |
|---|---|---|---|---|
| 1 | `rutas_comparar` | taller | Compara dos proyectos o dos ramas: rutas que faltan, rutas con otro nombre, alias y controles (botones) por pantalla. Devuelve la tabla de diferencias y el plan de paridad. | T01, T03 (paridad de pantallas y navegación) |
| 2 | `navegador_verificar` | navegador | Corre un escenario declarado en navegador real (Playwright sin cabeza): pasos, capturas, errores de consola, foco atrapado, CSS computado y geometría. Es la única forma honesta de cerrar "verificado en navegador real". | T01 (barrido de utilidades inertes, accesibilidad del kit), T02 (refresco de token), T03 (estados vacíos), T04 (límites de subida en interfaz), T10 (reconexión tras refresh) |
| 3 | `api_probar` | api | Ejecuta un escenario contra un backend real: crear, leer, editar, borrar, paginación, doble envío (idempotencia), exportación de datos y códigos de error. Adjunta código, cuerpo y tiempo de cada llamada. | T02 (revocación de sesión), T03 (certificación V03), T04 (límites de subida), T06 (idempotencia del apoyo), T07 (exportación de datos) |
| 4 | `carga_probar` | carga | Dispara ráfagas contra una URL: p50 y p95, tasa de error y comprobación de los encabezados de reintento cuando el límite responde 429. | T07 (límites de velocidad reales) |
| 5 | `base_auditar` | base | Índices presentes contra índices esperados, colecciones y tamaño, duplicados, huérfanos de media y consultas frecuentes sin índice (con `explain`). | T03 (feed sin N+1), T04 (huérfanos), T08 (índices y salud) |
| 6 | `base_ensayo` | base | Ensayo sobre copia, nunca sobre producción: respaldo verificado, TTL, rollback de migración y restore, con acta de cada paso. | T08 (restore real), T11 (cadena de base de datos) |
| 7 | `tiempo_real_verificar` | tiempo real | Dos clientes conectados al mismo servidor: entrega, orden, lectura, reconexión y cotejo de que el mensaje guardado por REST coincide con el evento recibido. | T10 (los tres pendientes de Socket.IO) |
| 8 | `servicios_verificar` | servicios | Por cada capacidad declarada (OpenRouter, video, Google, correo): revisa la variable exacta, dice si está configurada y, si lo está, hace una llamada mínima real. Si no, deja por escrito "no ofrecida" en lugar de prometerla. | T02 (Google y correos), T05 (OpenRouter y video), T12 (configuración desplegada), T15 (prueba posterior a cada credencial) |
| 9 | `evidencia_recopilar` | evidencia | Junta la evidencia del cierre: corridas y jobs de GitHub por su API, resultado de las suites, commit congelado, salud de producción, barrido NO-MOCK (mocks, endpoints decorativos, botones sin acción), `git diff --check` y pendientes externos con su variable exacta. Escribe el acta y la agrega al historial del proyecto. | T11, T12, T14, T16 (las diez puertas) y T17 |

## 4. El historial de buenos puntos

`evidencia_recopilar` no solo redacta un acta suelta: mantiene un archivo por proyecto con una
entrada por corrida.

```
data/evidencia/<proyecto>.json
  { fecha, commit, herramientas_usadas, hallazgos, veredicto, duracion }
```

De ahí salen dos cosas:

1. La referencia honesta de uso real: cuántas herramientas se usaron sobre el proyecto, cuántos
   hallazgos se detectaron y cuántos quedaron corregidos, con el commit y la fecha.
2. La comparación antes y después, que ya existe en la suite para seguridad
   (`seguridad_verificar` guarda línea base). El mismo patrón se aplica al acta: la segunda corrida
   demuestra mejora, no la promete.

Con `plantilla_renderizar` ese historial se convierte en una hoja de una página para enseñar el
caso, y `aprobaciones_pendientes` deja claro qué falta autorizar antes de declarar el cierre.

## 5. Orden de trabajo propuesto

**Primera tanda (las cuatro que más desbloquean):**

1. `rutas_comparar` — es la tarea viva de Kronos y no necesita nada externo.
2. `evidencia_recopilar` — da el acta y el historial; varias de sus piezas ya existen en la suite.
3. `navegador_verificar` — abre cinco bloques del checklist de una sola vez.
4. `api_probar` — cierra otros cinco.

**Segunda tanda:** `base_auditar`, `base_ensayo`, `tiempo_real_verificar`, `carga_probar`,
`servicios_verificar`.

**Al agregar cada herramienta hay que tocar, sin olvidar ninguno:**

- `src/tools/<modulo>.ts` y su registro en `src/server.ts`.
- `tests/<modulo>.test.ts` con sus pruebas.
- `tests/centinela.test.ts`, que hoy exige exactamente 76 herramientas y pasa a exigir el total nuevo.
- Los conteos visibles: `README.md`, `docs/ALCANCE.md`, `paginas/orquesta-visual.html`, el pie de
  `paginas/landing.html` y la descripción de `server.json` (máximo 100 caracteres).

## 6. Lo que la suite no puede resolver, aunque tenga herramientas

- Colocar una credencial en el entorno del servidor o del build: eso es del dueño.
- Decidir alcance (Tailwind, media privada, borrado de cuenta, pago real, federación): son las diez
  casillas [P-D] y ninguna herramienta las sustituye.
- Aprobar visualmente una pantalla: eso es criterio humano; la suite aporta la evidencia.
- Declarar el cierre: el acta se arma con evidencia, pero la firma es del dueño.

En corto: **sí se puede terminar Kronos con la suite, pero no con las 76 de hoy**. Con estas nueve,
la suite pasa de analizar el proyecto a poder demostrar su cierre.
