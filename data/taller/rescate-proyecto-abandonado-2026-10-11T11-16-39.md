# Diagnóstico de rescate — gestor-tienda

**Veredicto:** Proyecto "dañado": 8 archivos, 5 rutas activas y 14 huecos (1 sin implementar, 5 desconectados, 1 vacíos, 3 desajustes de documentación). El plan de abajo está ordenado por lo que más desbloquea primero.

Raíz analizada: `/home/user/mcp-suite-mx/data/fixtures/proyecto-abandonado` · estado: **dañado** · repositorio git: no

## Arquitectura reconstruida

| Módulo | Propósito | Archivos | Líneas | Rutas |
|---|---|---:|---:|---:|
| `src/utils` | utilidades compartidas | 2 | 36 | 0 |
| `src/rutas` | endpoints y controladores HTTP | 2 | 32 | 5 |
| `src/servicios` | integraciones, datos y lógica de servicio | 2 | 30 | 0 |
| `src` | módulo del proyecto | 2 | 27 | 0 |

Entradas: `src/index.js` · Rutas: 5 · Pruebas: 0 casos en 0 archivos.

## Huecos detectados

Pendientes: 3 · Sin implementar: 1 · Vacíos: 1 · Desconectados: 5 · Duplicados: 1 · Desajustes de documentación: 3

- **pendiente** (media) — `src/index.js:1`: TODO: mover la configuración a variables de entorno antes de publicar.
- **pendiente** (media) — `src/rutas/reportes.js:1`: FIXME: este módulo nunca se conectó al servidor principal.
- **pendiente** (media) — `src/servicios/db.js:1`: FIXME: agregar índices cuando la tienda crezca (todo está en memoria).
- **stub** (alta) — `src/servicios/pagos.js:2`: código sin implementar (lanza error de pendiente)
- **archivo_vacio** (media) — `src/vacio.js`: archivo sin contenido útil
- **desconectado** (alta) — `src/rutas/reportes.js`: define 2 ruta(s) pero nadie lo importa: posible módulo huérfano
- **desconectado** (media) — `src/servicios/pagos.js`: ningún archivo lo importa (código desconectado)
- **desconectado** (media) — `src/utils/calculos.js`: ningún archivo lo importa (código desconectado)
- **desconectado** (media) — `src/utils/formato.js`: ningún archivo lo importa (código desconectado)
- **desconectado** (media) — `src/vacio.js`: ningún archivo lo importa (código desconectado)
- **duplicado** (media) — `src/utils/calculos.js:1`: función duplicada (calcularTotal) en: src/utils/calculos.js:1 · src/utils/formato.js:1
- **documentacion** (baja) — `README.md`: la documentación dice "npm run lint" pero NO existe en package.json
- **documentacion** (baja) — `README.md`: la documentación dice "src/api/ventas.js" pero NO existe en el proyecto
- **documentacion** (baja) — `README.md`: la documentación dice "12 pruebas" pero 0 casos de prueba detectados en archivos

## Plan de terminación (ordenado por impacto)

1. **Conectar o eliminar módulo huérfano — src/rutas/reportes.js** — impacto 8, esfuerzo S. define 2 ruta(s) pero nadie lo importa: posible módulo huérfano
2. **Implementar código que hoy lanza "no implementado" — src/servicios/pagos.js:2** — impacto 6, esfuerzo S. código sin implementar (lanza error de pendiente)
3. **Resolver pendiente marcado en el código — src/servicios/db.js:1** — impacto 5, esfuerzo S. FIXME: agregar índices cuando la tienda crezca (todo está en memoria).
4. **Resolver pendiente marcado en el código — src/index.js:1** — impacto 4, esfuerzo S. TODO: mover la configuración a variables de entorno antes de publicar.
5. **Resolver pendiente marcado en el código — src/rutas/reportes.js:1** — impacto 4, esfuerzo S. FIXME: este módulo nunca se conectó al servidor principal.
6. **Conectar o eliminar módulo huérfano — src/servicios/pagos.js** — impacto 4, esfuerzo S. ningún archivo lo importa (código desconectado)
7. **Conectar o eliminar módulo huérfano — src/utils/calculos.js** — impacto 4, esfuerzo S. ningún archivo lo importa (código desconectado)
8. **Unificar código duplicado — src/utils/calculos.js:1** — impacto 4, esfuerzo S. función duplicada (calcularTotal) en: src/utils/calculos.js:1 · src/utils/formato.js:1
9. **Conectar o eliminar módulo huérfano — src/utils/formato.js** — impacto 4, esfuerzo S. ningún archivo lo importa (código desconectado)
10. **Completar o eliminar archivo vacío — src/vacio.js** — impacto 4, esfuerzo S. archivo sin contenido útil
11. **Conectar o eliminar módulo huérfano — src/vacio.js** — impacto 4, esfuerzo S. ningún archivo lo importa (código desconectado)
12. **Alinear la documentación con la realidad — README.md** — impacto 2, esfuerzo S. la documentación dice "npm run lint" pero NO existe en package.json
13. **Alinear la documentación con la realidad — README.md** — impacto 2, esfuerzo S. la documentación dice "src/api/ventas.js" pero NO existe en el proyecto
14. **Alinear la documentación con la realidad — README.md** — impacto 2, esfuerzo S. la documentación dice "12 pruebas" pero 0 casos de prueba detectados en archivos
