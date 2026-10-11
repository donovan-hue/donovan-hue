# Entrega 5A — Multi-empresa: un servidor, varios negocios

**Fecha:** 2026-10-11 · **Versión:** 0.2.0 · **Herramientas:** 68 en 11 módulos · **Pruebas:** 181 en verde

Esta entrega abre la puerta del canal que interesa: **un despacho contable (o un dueño con dos giros) usa un solo servidor MCP para atender a varios negocios**. Cada empresa tiene su inventario, sus pedidos, sus plantillas y sus datos fiscales; nada se mezcla y cada cliente puede recibir una clave que solo ve lo suyo.

---

## 1. Qué se entregó

| Pieza | Qué hace |
|---|---|
| **Espacios de empresa** | Tabla `empresas` + columna `empresa_id` en inventario, pedidos, pedido_items, pedido_historial, plantillas y claves de API. La empresa activa se resuelve así: clave atada → empresa activa de la sesión → `EMPRESA_DEFAULT` → la primera. |
| **Aislamiento real** | `inventario`, `pedidos` y `plantillas` usan clave compuesta `(empresa, sku/folio/nombre)`. El **mismo SKU** puede existir en dos empresas siendo cosas distintas, y el **mismo folio** (P-0001) puede existir en dos empresas: cada pedido descuenta stock en la empresa correcta. |
| **6 herramientas nuevas** | `empresas_listar`, `empresa_crear`, `empresa_actual`, `empresa_editar`, `empresa_resumen`, `empresas_comparar`. |
| **`inventario_importar`** | Carga el catálogo de un negocio desde CSV (texto o archivo): elige delimitador, valida `sku` y `nombre`, acepta `modo: agregar | reemplazar`, tiene `dry_run` y devuelve los problemas fila por fila. Es la forma práctica de arrancar al cliente nuevo. |
| **Claves atadas a una empresa** | `keys create --empresa EMP-xxxxxx`: esa clave solo ve los datos de su empresa; **no** puede cambiar de espacio ni comparar (y si se le da `empresas`, la herramienta lo rechaza con un mensaje claro). |
| **CFDI por empresa** | El borrador toma emisor (RFC, razón social, régimen, CP) y **serie de folios** de la empresa activa cuando no hay `CFDI_EMISOR_*` configurado. Cada negocio factura con su identidad: `FERR0003`, `TAL0001`… |
| **Numeración por empresa** | El consecutivo de pedidos vive en `consecutivo_pedidos:<empresa>` y se siembra del mayor folio local: **cada negocio empieza en P-0001**. |
| **Migración automática** | Una base anterior se convierte sola al arrancar: todo lo que ya existía pasa a la empresa por defecto **sin perder nada** (inventario, pedidos con sus conceptos e historial, plantillas, claves). |
| **CLI y HTTP** | `node dist/cli.js empresas [--todas] [--comparar]` y `empresas crear …`; `GET /api/empresas` (y `?comparar=1`) bajo clave de administración. El panel muestra el módulo «Empresas» con su color y su descripción. |

---

## 2. Cómo probarlo (5 minutos)

```bash
npm install && npm run build

# 1) Ver los espacios (la base vieja ya migró sola)
node dist/cli.js empresas

# 2) Crear un negocio con sus datos fiscales y su serie de folios
node dist/cli.js empresas crear --nombre "Ferretería El Tornillo" --rfc FER950101AB1 --regimen 626 --cp 44100 --series FERR

# 3) Comparar a todos los negocios (ventas, pedidos, inventario, riesgos)
node dist/cli.js empresas --comparar

# 4) Una clave que solo ve una empresa
node dist/cli.js keys create --nombre cliente-ferreteria --scopes negocio,facturacion --empresa EMP-xxxxxx
```

Con un cliente MCP (o en el panel http) la secuencia es la misma:

```
empresa_actual  → activar «Ferretería El Tornillo»
inventario_importar → contenido_csv con las columnas sku,nombre,precio,stock (dry_run primero)
pedido_crear    → P-0001 … y el stock se descuenta en ESA empresa
cfdi_desde_pedido → el borrador sale con el RFC y la serie de la empresa activa
empresas_comparar → los dos negocios lado a lado, sin mezclar cifras
```

---

## 3. La comparación honesta

| La normal tiene… | La nuestra |
|---|---|
| Un servidor por cliente (o una instalación por empresa, con su base aparte) | **Un servidor** con varios espacios aislados; se cambia de empresa con una llamada |
| Claves de API globales: quien entra, ve todo | Clave **atada a una empresa**: el cliente solo ve su inventario, sus pedidos y sus folios |
| Numeración global de pedidos (el segundo cliente arranca en un folio ya usado) | Numeración **por empresa**: cada negocio empieza en P-0001 y lleva su serie de CFDI |
| Multiusuario de nube: los datos salen de tu equipo y pagas por asiento | **Local-first** en SQLite, en tu computadora; el aislamiento es una columna, no un tenant de nube |
| Migrar a multi-empresa = exportar, limpiar y volver a cargar | La base anterior **se migra sola** al arrancar, con los datos intactos y verificados |
| Cargar el catálogo de un cliente a mano, producto por producto | `inventario_importar`: CSV con `dry_run`, reporte de problemas por fila y modo reemplazar |

---

## 4. Evidencia recogida en vivo

- Migración de la base real (`data/suite.db`): 3 pedidos, 5 conceptos y 7 entradas de historial **conservados**; inventario repartido en `EMP-principal` (8 productos) y una segunda empresa (3).
- Demo de punta a punta: crear empresa → importar CSV → pedido → CFDI `FERR0003` con el emisor de la empresa → volver a la principal sin ver nada ajeno → comparativa → clave atada que solo ve su negocio.
- Suite: **181 pruebas en verde**, 11 de ellas específicas de multi-empresa (mismo SKU en dos empresas, mismo folio en dos empresas, CFDI con emisor propio, migración de base vieja con índices incluidos, clave atada, resumen y comparativa, archivar sin borrar).
- Dos fallos reales aparecieron y quedaron cubiertos con prueba: los índices de empresa no podían crearse antes de migrar la columna, y los conceptos de un pedido se leían sin filtrar por empresa.

La corrida completa del módulo, tal cual salió, está en `docs/evidencia-5A-demo.txt` (servidor recién instalado, sin datos previos): empresa nueva → catálogo por CSV → pedido P-0001 → CFDI `FERR0001` con emisor propio → segunda empresa con su propio P-0001 → la original intacta → comparativa → clave atada → resumen → archivar sin borrar.

Y la corrida de todo el pack sobre la versión 0.2.0 está en `docs/evidencia-5A-pack-completo.txt`: 68 herramientas, 11 módulos; empresas con clave atada (que no puede ver ni comparar otras); ciclo de parche real sobre un proyecto de ejemplo (diagnóstico del TypeError reportado → parche con huella → pruebas rojo→verde en copia aislada → bloqueo por aprobación pendiente → aprobación desde el panel por HTTP → aplicación con respaldo → reversión y pruebas en rojo otra vez); centinela con 10 hallazgos y 2 rutas sin guard; orquesta con dry-run y corrida real con acta; rúter con cascada, coste por resultado útil y política «solo local» para un tipo de tarea; y la bitácora con cada paso sensible.

---

## 5. Límites (dicho sin adornos)

- **No hay herencia entre empresas**: las plantillas y el inventario de una no se comparten con otra (a propósito; si algún día hace falta, se copia explícitamente).
- **La empresa activa por sesión** se guarda en la base (`meta.empresa_actual`) porque el servidor es local y de un solo operador. Para varios operadores simultáneos con espacios distintos, la vía correcta ya existe: **una clave por empresa**.
- **No se borran empresas**: se archivan (ocultan sin borrar datos). Archivar la activa está bloqueado.
- **Sigue sin timbrar**: el CFDI es borrador con XML sin sellar, como estaba acordado. El PAC real es la fase 5B.
- **`inventario_importar` reemplaza por SKU**, no fusiona campos sueltos: si el SKU existe y el modo es `agregar`, se omite (y se reporta).

---

## 6. Publicar (requiere tus credenciales)

```bash
# 1) Código a GitHub (repo de destino: donovan-hue/donovan-hue)
git remote add origin git@github.com:donovan-hue/donovan-hue.git && git push -u origin main

# 2) Paquete a npm (ya lleva "mcpName": "io.github.donovan-hue/mcp-suite-mx")
npm login && npm publish

# 3) Registro MCP oficial (server.json ya está listo, schema 2025-12-11)
mcp-publisher login github && mcp-publisher publish
```

`server.json` ya declara: nombre `io.github.donovan-hue/mcp-suite-mx`, versión 0.2.0, paquete npm `mcp-suite-mx` por stdio con `DATA_DIR`, `AUTH_MODE`, `ALLOWED_GIT_ROOTS` y `EMPRESA_DEFAULT`, y el remoto `streamable-http` con `Authorization: Bearer`.
