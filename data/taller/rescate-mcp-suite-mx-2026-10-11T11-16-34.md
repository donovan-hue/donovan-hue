# Diagnóstico de rescate — mcp-suite-mx

**Veredicto:** Proyecto "en obra": 47 archivos, 12 rutas activas y 10 huecos (0 sin implementar, 0 desconectados, 0 vacíos, 0 desajustes de documentación). El plan de abajo está ordenado por lo que más desbloquea primero.

Raíz analizada: `/home/user/mcp-suite-mx` · estado: **en obra** · repositorio git: no

## Arquitectura reconstruida

| Módulo | Propósito | Archivos | Líneas | Rutas |
|---|---|---:|---:|---:|
| `src/lib` | utilidades compartidas | 13 | 2774 | 0 |
| `src/tools` | herramientas expuestas al cliente | 6 | 1844 | 0 |
| `tests` | pruebas automatizadas | 11 | 1563 | 3 |
| `src` | módulo del proyecto | 10 | 1160 | 9 |
| `src/db` | persistencia y modelos de datos | 3 | 569 | 0 |
| `src/auth` | autenticación y permisos | 2 | 216 | 0 |
| `scripts` | tareas de operación | 2 | 152 | 0 |

Entradas: `src/index.ts`, `src/cli.ts`, `scripts/certificado-autofirmado.mjs`, `scripts/generar-datasets.mjs` · Rutas: 12 · Pruebas: 104 casos en 10 archivos.

## Huecos detectados

Pendientes: 10 · Sin implementar: 0 · Vacíos: 0 · Desconectados: 0 · Duplicados: 0 · Desajustes de documentación: 0

- **pendiente** (baja) — `src/lib/cfdi.ts:67`: XXX: ', descripcion: 'Los códigos asignados para las transacciones en que no canalice moneda (solo tipo P)' },
- **pendiente** (media) — `src/lib/grafo.ts:66`: TODO: ' | 'FIXME' | 'HACK' | 'XXX' | 'STUB';
- **pendiente** (media) — `src/lib/grafo.ts:159`: TODO: |FIXME|HACK|XXX)\b[:：]?\s*(.{0,120})/;
- **pendiente** (media) — `src/lib/rescate.ts:193`: TODO: en pruebas no aporta al plan
- **pendiente** (media) — `src/lib/rescate.ts:197`: FIXME: ') {
- **pendiente** (media) — `src/lib/rescate.ts:198`: FIXME: ${m.texto}`, gravedad: 'media' });
- **pendiente** (media) — `src/lib/rescate.ts:199`: TODO: ') {
- **pendiente** (media) — `src/lib/rescate.ts:200`: TODO: ${m.texto}`, gravedad: 'media' });
- **pendiente** (media) — `src/tools/taller.ts:339`: TODO: /FIXME, código que lanza "no implementado", archivos vacíos), lo desconectado (módulos huérfanos, rutas sin montar), dup
- **pendiente** (media) — `src/tools/taller.ts:365`: TODO: , FIXME, código sin implementar y archivos vacíos) ordenado por impacto real: primero lo que está en archivos de los que

## Plan de terminación (ordenado por impacto)

1. **Resolver pendiente marcado en el código — src/lib/grafo.ts:66** — impacto 6, esfuerzo L. TODO: ' | 'FIXME' | 'HACK' | 'XXX' | 'STUB';
2. **Resolver pendiente marcado en el código — src/lib/grafo.ts:159** — impacto 6, esfuerzo L. TODO: |FIXME|HACK|XXX)\b[:：]?\s*(.{0,120})/;
3. **Resolver pendiente marcado en el código — src/lib/rescate.ts:193** — impacto 5, esfuerzo L. TODO: en pruebas no aporta al plan
4. **Resolver pendiente marcado en el código — src/lib/rescate.ts:197** — impacto 5, esfuerzo L. FIXME: ') {
5. **Resolver pendiente marcado en el código — src/lib/rescate.ts:198** — impacto 5, esfuerzo L. FIXME: ${m.texto}`, gravedad: 'media' });
6. **Resolver pendiente marcado en el código — src/lib/rescate.ts:199** — impacto 5, esfuerzo L. TODO: ') {
7. **Resolver pendiente marcado en el código — src/lib/rescate.ts:200** — impacto 5, esfuerzo L. TODO: ${m.texto}`, gravedad: 'media' });
8. **Resolver pendiente marcado en el código — src/tools/taller.ts:339** — impacto 5, esfuerzo L. TODO: /FIXME, código que lanza "no implementado", archivos vacíos), lo desconectado (módulos huérfanos, rutas sin montar), dup
9. **Resolver pendiente marcado en el código — src/tools/taller.ts:365** — impacto 5, esfuerzo L. TODO: , FIXME, código sin implementar y archivos vacíos) ordenado por impacto real: primero lo que está en archivos de los que
10. **Resolver pendiente marcado en el código — src/lib/cfdi.ts:67** — impacto 4, esfuerzo L. XXX: ', descripcion: 'Los códigos asignados para las transacciones en que no canalice moneda (solo tipo P)' },
