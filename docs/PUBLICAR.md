# Publicar la suite (lo único que necesita tus credenciales)

Todo lo que se puede comprobar sin cuenta está comprobado: el paquete se empaqueta, se instala en un proyecto limpio y **arranca** (HTTP y stdio), el registro MCP (`server.json`) cumple el esquema y el `mcpName` coincide con el nombre declarado, y `npm audit` da 0. Faltan los tres comandos que sí requieren tu cuenta.

## 1. Código a GitHub

```bash
git remote add origin git@github.com:donovan-hue/donovan-hue.git   # ya está añadido
git push -u origin main
```

Si preferís HTTPS: `git remote set-url origin https://github.com/donovan-hue/donovan-hue.git` y `gh auth login` (o un token en vez de contraseña).

## 2. Paquete a npm

```bash
npm login          # tu usuario de npm
npm publish        # publica mcp-suite-mx@0.2.0
```

Antes de publicar (esto ya está hecho y se puede repetir):

```bash
npm run build && npm test      # 186 pruebas
npm pack --dry-run             # 108 archivos, ~254 kB: dist/, paginas/, docs/, README, LICENSE, .env.example
npm audit                      # 0 vulnerabilidades
```

Después de publicar, comprobación real (lo que ya validé con el `.tgz`):

```bash
mkdir /tmp/prueba && cd /tmp/prueba && npm init -y
npm install mcp-suite-mx
npx mcp-suite-mx --version                    # → 0.2.0
npx mcp-suite-mx --help
DATA_DIR=./datos npx mcp-suite-mx-cli empresas
```

## 3. Registro MCP (opcional, recomendado)

El registro oficial usa `server.json` en la raíz del repositorio (esquema 2025-12-11). Ya está listo:

- `name`: `io.github.donovan-hue/mcp-suite-mx`
- `packages[0]`: npm `mcp-suite-mx@0.2.0`, transporte `stdio`, variables `DATA_DIR`, `AUTH_MODE`, `ALLOWED_GIT_ROOTS`, `EMPRESA_DEFAULT`
- `remotes[0]`: `streamable-http` en `https://TU-DOMINIO/mcp` con `Authorization: Bearer` (cámbialo cuando tengas dominio)
- `mcpName` en `package.json` idéntico al `name` del `server.json` (es un requisito del registro)

```bash
# Instala el binario mcp-publisher del registro y entra con GitHub:
mcp-publisher login github
mcp-publisher publish
```

Ojo: el registro exige que el paquete **ya esté publicado en npm** y que el repositorio sea el declarado, así que este paso va después del 2.

## Dos fallos que iban a romper el primer `push`

1. **El paquete instalado no arrancaba** (binario enlazado por npm + guard de arranque). Arreglado y con pruebas.
2. **`package-lock.json` desincronizado** (le faltaba el bin `mcp-suite-mx-cli` en la raíz): `npm ci` habría fallado en la primera ejecución de CI. Corregido.
3. De paso, en el propio CI el paso de claves usaba `--nombre ci` (dos letras) y el CLI exige tres: ahora usa `ci-demo`. Y la auditoría de dependencias quedó como paso obligatorio.

## Lo que ya verifiqué con el paquete empaquetado

| Comprobación | Resultado |
|---|---|
| `npm pack --dry-run` | 108 archivos, 254.4 kB |
| Instalación en proyecto limpio (`npm install <tgz>`) | OK, 131 paquetes, sin errores |
| `mcp-suite-mx --version` / `--help` | `0.2.0` / ayuda con variables y binarios |
| Servidor HTTP instalado (`/health`) | `{"ok":true,"version":"0.2.0"}` |
| `tools/list` del paquete instalado | 68 herramientas, módulo `empresas` incluido |
| Servidor stdio instalado (initialize por stdin) | responde `serverInfo` correcto |
| `mcp-suite-mx-cli keys create` | emite clave y la lista después |
| CI de GitHub, paso a paso en local | los 8 pasos pasan (tipos, auditoría, pruebas, build, paquete instalado, stdio, CLI, HTTP, respaldos, HTTPS) |

Durante esta revisión apareció (y se arregló, con prueba) un fallo serio de empaquetado: npm enlaza los binarios en `node_modules/.bin/`, y el servidor solo se auto-arrancaba cuando se ejecutaba por ruta real, así que **el paquete instalado no hacía nada**. Ahora la comprobación resuelve la ruta real (`realpath`) y hay tres pruebas que espían ese camino (`tests/bin.test.ts`), además de `--version`/`--help`.
