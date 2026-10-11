# Entrega — pack completo (fases 4C y 4D + cierres de 4B)

Fecha: 2026-10-11 · Versión: **0.1.0** · Estado: **terminado y probado en vivo**

## Qué se entregó en esta pasada

| Pieza | Qué quedó | Herramientas |
|---|---|---|
| **PARCHE** (fase 4C) | Diagnóstico con evidencia, parche revisable, pruebas antes/después en copia aislada, aplicación con aprobación humana + respaldo, reversión en un comando | 6 |
| **ORQUESTA** (fase 4D) | 5 flujos de dev con actas JSON, flujos propios, post-mortem automático y aprobaciones genéricas | 5 |
| **RÚTER** (fase 4D) | Cascada de modelos por tarea, historial de resultados y **coste por resultado útil** | 4 |
| **Centinela** (cierres) | `permisos_auditar` (rutas sin guard real) y `dependencias_vulnerables` (`npm audit` con caché y respaldo honesto) | +2 |
| **Panel** | Sección de **Aprobaciones pendientes** con botones Aprobar/Rechazar + historial de **Parches** en vivo | — |
| **CLI** | `parches`, `revertir <PR-id>`, `flujos`, `modelos` | — |

Total: **61 herramientas MCP en 10 módulos**.

## Evidencia (real, de esta máquina)

```
npm test                      → 15 archivos · 169 pruebas en verde
npm run build                 → limpio
node dist/cli.js seguridad --directorio .   → puntaje 100 · semáforo verde · 0 hallazgos
```

Ciclo de parche completo sobre `data/fixtures/tienda-api` (vía MCP, en vivo):

```
falla_diagnosticar   → excepcion: propiedad de undefined en src/db.js:9 (confianza alta)
parche_proponer      → PR-a7c305 · huella d5d1d42ec871efcc · aprobación AP-e1ad1d
parche_probar        → ARREGLA (antes: rojo con 2 fallos → después: «2 passing») en copia aislada
parche_aplicar       → bloqueado hasta resolver la aprobación; después: aplicado con respaldo
reparacion_revertir  → archivo restaurado; el respaldo se conserva
```

Flujos (Orquesta), sobre el mismo proyecto:

```
flujo_ejecutar preparar_release --dry-run → 2 pasos, ninguno ejecutado
flujo_ejecutar preparar_release           → 1.4.0 → 1.4.1, notas desde git, «git tag … (lo haces tú)»
flujo_estado                              → corridas recientes + aprobaciones pendientes
```

Rúter, con datos reales de la sesión:

```
modelo_elegir (refactor, 4 archivos) → nivel 2 · cascada: local-grande → nube-media → nube-fuerte
modelo_elegir (migración crítica)    → nivel 3 · cascada: nube-fuerte (no se ahorra en lo crítico)
coste_comparar                       → local-grande: 0 por tarea exitosa (3/3); nube-media: 6 (1/2)
```

Centinela (cierres):

```
permisos_auditar         → 2 rutas sin guard real, con archivo:línea y recomendación
dependencias_vulnerables → npm audit real cuando hay lockfile; si no (ENOLOCK), tabla local
                           CON la nota que dice de dónde salió: nunca un «0» falso
```

Panel (HTTP, `AUTH_MODE=required`): `/api/aprobaciones` exige clave; el botón **Aprobar** resuelve
por el mismo camino que el CLI y queda en la auditoría (`resuelta_por: panel-demo`).

## Cómo lo pruebas tú (2 minutos)

```bash
cd /home/user/mcp-suite-mx
npm install && npm run build
ALLOWED_GIT_ROOTS=/home/user/mcp-suite-mx AUTH_MODE=required node dist/index.js --transport http
# panel:  http://localhost:8787/panel   (sección «Aprobaciones pendientes» y «Parches propuestos»)
node dist/cli.js keys create --nombre dev --scopes parche,orquesta,ruter,centinela
node dist/cli.js parches     # historial
node dist/cli.js flujos      # flujos + últimas corridas
node dist/cli.js modelos --tipo refactor
```

## Publicación: lo que ya está listo y lo que solo puedes hacer tú

Listo en el repositorio:

- Commit inicial completo (`git log`: «Suite MCP MX 0.1.0 — pack completo…»), **118 archivos**, con `.gitignore` que excluye datos en ejecución.
- `server.json` **válido** para el registro MCP oficial (`io.github.donovan-hue/mcp-suite-mx`, transporte stdio + remoto con cabecera `Authorization`).
- `package.json` sin `private`, con `repository`, `files` y `bin` (`mcp-suite-mx`).

Los tres pasos que necesitan tus credenciales (yo no puedo firmar por ti):

```bash
# 1. Subir el código
git remote add origin https://github.com/donovan-hue/donovan-hue.git
git push -u origin main

# 2. Publicar el paquete npm (el registro MCP lo exige)
npm login && npm publish --access public

# 3. Registro MCP oficial
curl -L https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher_linux_amd64.tar.gz | tar xz mcp-publisher
./mcp-publisher login github      # verifica la cuenta donovan-hue
./mcp-publisher publish           # lee server.json de este repo
```

> Si el dominio del servidor remoto todavía no existe, deja `remotes` con la URL de ejemplo o quita ese bloque antes de publicar; el paquete stdio funciona sin él.

## Lo que queda fuera a propósito (no es olvido)

- Nada de **PAC/SAT masivo**: el CFDI sigue siendo borrador + XML sin sellar (el sellado local con CSD es idea pendiente).
- Multi-empresa, OAuth/SSO, webhooks y ERP/CRM: fases posteriores.
- El Rúter es **consejero + contador + ejecutor opcional**: un servidor MCP no elige el modelo del host, y eso se dice claro en la propia herramienta.

## Por qué esto y no lo que ya existe

| Lo normal | Lo nuestro |
|---|---|
| Agentes que proponen y aplican parches | Pruebas antes/después en copia aislada, aprobación humana ligada a la **huella del parche** y reversión en un comando |
| Enrutadores que deciden por precio o benchmark | **Coste por resultado útil**: se mide con tus pruebas verdes y tus parches vivos |
| SAST que escupe hallazgos | Hallazgos **con plan comprobable** y verificación contra línea base (resueltos/nuevos, no «puntaje») |
