# Mapa del proyecto — mcp-suite-mx

> Generado por GEMELO (mcp-suite-mx) el 2026-10-11T11:16:34.700Z. Raíz: `/home/user/mcp-suite-mx`.

## Resumen

| Indicador | Valor |
|---|---|
| Archivos de código | 47 |
| Líneas | 8278 |
| Rutas HTTP detectadas | 12 |
| Archivos de prueba | 10 |
| Entradas | 4 |
| Desconectados | 0 |
| Funciones duplicadas | 0 |

## Módulos

| Módulo | Archivos | Líneas | Rutas | Exportados |
|---|---:|---:|---:|---:|
| `src/lib` | 13 | 2774 | 0 | 115 |
| `src/tools` | 6 | 1844 | 0 | 6 |
| `tests` | 11 | 1563 | 3 | 2 |
| `src` | 10 | 1160 | 9 | 22 |
| `src/db` | 3 | 569 | 0 | 20 |
| `src/auth` | 2 | 216 | 0 | 13 |
| `scripts` | 2 | 152 | 0 | 0 |

## Dependencias entre módulos

```mermaid
graph LR
  src["src"] -->|4| src_auth["src/auth"]
  src["src"] -->|5| src_db["src/db"]
  src["src"] -->|8| src_lib["src/lib"]
  src["src"] -->|6| src_tools["src/tools"]
  src_auth["src/auth"] -->|1| src["src"]
  src_db["src/db"] -->|2| src["src"]
  src_db["src/db"] -->|2| src_lib["src/lib"]
  src_lib["src/lib"] -->|5| src["src"]
  src_lib["src/lib"] -->|1| src_auth["src/auth"]
  src_tools["src/tools"] -->|11| src["src"]
  src_tools["src/tools"] -->|3| src_db["src/db"]
  src_tools["src/tools"] -->|16| src_lib["src/lib"]
  tests["tests"] -->|18| src["src"]
  tests["tests"] -->|5| src_auth["src/auth"]
  tests["tests"] -->|2| src_db["src/db"]
  tests["tests"] -->|12| src_lib["src/lib"]
```

## Rutas HTTP

| Método | Ruta | Archivo | Línea |
|---|---|---|---:|
| ANY | `/mcp` | `src/http.ts` | 143 |
| ANY | `/health` | `src/http.ts` | 184 |
| ANY | `/metrics` | `src/http.ts` | 194 |
| ANY | `/api/config` | `src/http.ts` | 199 |
| ANY | `/api/catalogo` | `src/http.ts` | 213 |
| ANY | `/api/herramientas` | `src/http.ts` | 222 |
| ANY | `/` | `src/http.ts` | 228 |
| ANY | `/panel` | `src/http.ts` | 228 |
| ANY | `/api/auditoria` | `src/http.ts` | 259 |
| GET | `/api/pedidos` | `tests/taller.test.ts` | 39 |
| GET | `/api/reportes` | `tests/taller.test.ts` | 40 |
| POST | `/api/reportes/exportar` | `tests/taller.test.ts` | 40 |

## Servicios y dependencias externas

| Paquete | Uso | Archivos |
|---|---|---:|
| `vitest` | paquete externo | 10 |
| `zod` | validación de datos | 8 |
| `@modelcontextprotocol/sdk` | SDK de MCP | 6 |
| `better-sqlite3` | SQLite local | 1 |
