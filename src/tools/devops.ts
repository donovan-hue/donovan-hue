import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { readTextFile, resolveAllowedPath } from '../lib/store.js';
import { log } from '../logger.js';

const exec = promisify(execFile);
const TIMEOUT_MS = 15_000;

function resolverRepo(ctx: ServerCtx, repo?: string): string {
  const candidato = path.resolve(repo ?? ctx.config.allowedGitRoots[0] ?? process.cwd());
  const permitido = ctx.config.allowedGitRoots.some(
    (root) => candidato === root || candidato.startsWith(`${root}${path.sep}`),
  );
  if (!permitido) {
    throw new Error(
      `Repositorio fuera de las rutas permitidas: ${candidato}. Configura ALLOWED_GIT_ROOTS con las rutas autorizadas.`,
    );
  }
  return candidato;
}

async function git(repo: string, args: string[]): Promise<string> {
  try {
    const { stdout } = await exec('git', ['-C', repo, ...args], {
      timeout: TIMEOUT_MS,
      maxBuffer: 10 * 1024 * 1024,
    });
    return stdout;
  } catch (err: any) {
    if (err?.code === 'ENOENT') throw new Error('git no está instalado o no está en el PATH de este servidor.');
    const detalle = (err?.stderr ?? err?.message ?? '').toString().trim();
    throw new Error(`git ${args.join(' ')} falló: ${detalle || `código ${err?.code}`}`);
  }
}

const IP_PRIVADA = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|::1|\[::1\]|.*\.local|169\.254\.)/i;

export function devopsToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);
  push({
    module: 'devops',
    name: 'git_status',
    title: 'Estado del repositorio git',
    description:
      'Muestra el estado del repositorio: rama actual, si está al día con el remoto (ahead/behind) y los archivos modificados, agregados o sin seguimiento. Primer comando para saber si hay cambios pendientes antes de compilar o desplegar.',
    readOnly: true,
    inputSchema: {
      repo: z.string().optional().describe('Ruta del repositorio. Debe estar dentro de las rutas autorizadas (ALLOWED_GIT_ROOTS). Si se omite, usa la primera ruta autorizada.'),
    },
    handler: async (args: { repo?: string }) => {
      const repo = resolverRepo(ctx, args.repo);
      const salida = await git(repo, ['status', '--porcelain=v1', '--branch']);
      const lineas = salida.split('\n').filter((l) => l.trim().length > 0);
      const rama = lineas.find((l) => l.startsWith('##')) ?? '';
      const archivos = lineas
        .filter((l) => !l.startsWith('##'))
        .map((l) => ({ codigo: l.slice(0, 2).trim() || '??', archivo: l.slice(3).trim() }));

      const traducir: Record<string, string> = {
        M: 'modificado', A: 'agregado', D: 'borrado', '??': 'sin seguimiento', R: 'renombrado', UU: 'conflicto',
      };
      archivos.forEach((a) => {
        (a as any).estado = traducir[a.codigo.replace(/\s/g, '')] ?? traducir[a.codigo[0]!] ?? a.codigo;
      });

      return {
        repo,
        rama: rama.replace(/^##\s*/, ''),
        limpio: archivos.length === 0,
        total_cambios: archivos.length,
        archivos,
      };
    },
  });

  push({
    module: 'devops',
    name: 'git_log',
    title: 'Historial de commits',
    description:
      'Lista los últimos commits del repositorio con hash corto, autor, fecha y mensaje. Filtra opcionalmente por autor o por ruta de archivo. Úsalo para saber qué cambió recientemente y quién lo hizo.',
    readOnly: true,
    inputSchema: {
      repo: z.string().optional().describe('Ruta del repositorio (debe estar en ALLOWED_GIT_ROOTS).'),
      limite: z.number().int().min(1).max(100).optional().describe('Número de commits a devolver (por defecto 15).'),
      autor: z.string().optional().describe('Filtrar por autor (parcial).'),
      ruta: z.string().optional().describe('Filtrar commits que tocan esta ruta de archivo.'),
    },
    handler: async (args: { repo?: string; limite?: number; autor?: string; ruta?: string }) => {
      const repo = resolverRepo(ctx, args.repo);
      const limite = args.limite ?? 15;
      const formato = '--pretty=format:%h|%an|%ad|%s';
      const cmd = ['log', `-n`, String(limite), formato, '--date=short'];
      if (args.autor) cmd.push(`--author=${args.autor}`);
      if (args.ruta) cmd.push('--', args.ruta);
      const salida = await git(repo, cmd);
      const commits = salida
        .split('\n')
        .filter(Boolean)
        .map((l) => {
          const [hash, autor, fecha, ...resto] = l.split('|');
          return { hash, autor, fecha, mensaje: resto.join('|') };
        });
      return { repo, total: commits.length, commits };
    },
  });

  push({
    module: 'devops',
    name: 'git_diff',
    title: 'Ver diferencias sin commit',
    description:
      'Muestra las diferencias del working tree (o del área de staging con staged=true): resumen por archivo (--stat) y el diff unificado. Úsalo para revisar cambios antes de hacer commit o para entender qué rompió el build.',
    readOnly: true,
    inputSchema: {
      repo: z.string().optional().describe('Ruta del repositorio (debe estar en ALLOWED_GIT_ROOTS).'),
      staged: z.boolean().optional().describe('Si es true, muestra lo que está en staging (git diff --cached).'),
      archivo: z.string().optional().describe('Ruta específica del archivo a comparar.'),
      contexto: z.number().int().min(0).max(20).optional().describe('Líneas de contexto del diff (por defecto 3).'),
    },
    handler: async (args: { repo?: string; staged?: boolean; archivo?: string; contexto?: number }) => {
      const repo = resolverRepo(ctx, args.repo);
      const base = args.staged ? ['diff', '--cached'] : ['diff'];
      const common = [`-U${args.contexto ?? 3}`, '--no-color'];
      const target = args.archivo ? ['--', args.archivo] : [];
      const stat = await git(repo, [...base, '--stat', ...target]);
      const diff = await git(repo, [...base, ...common, ...target]);
      const MAX = 400;
      const lineas = diff.split('\n');
      const truncado = lineas.length > MAX;
      return {
        repo,
        staged: Boolean(args.staged),
        resumen_por_archivo: stat.trim() || '(sin cambios)',
        truncado,
        diff: truncado ? `${lineas.slice(0, MAX).join('\n')}\n…(diff truncado a ${MAX} líneas de ${lineas.length})` : diff,
      };
    },
  });

  push({
    module: 'devops',
    name: 'proyecto_resumen',
    title: 'Resumen de un proyecto local',
    description:
      'Analiza una carpeta local y resume su estructura técnica: tipo de proyecto (Node/Python/Go/Rust), dependencias declaradas, scripts disponibles, conteo de archivos por extensión, archivos más pesados y presencia de README, tests, CI y Docker. Útil para onboarding a un repo desconocido.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      max_profundidad: z.number().int().min(1).max(6).optional().describe('Profundidad máxima de escaneo (por defecto 3).'),
    },
    handler: async (args: { ruta: string; max_profundidad?: number }) => {
      const raiz = resolverRepo(ctx, args.ruta);
      const profundidad = args.max_profundidad ?? 3;
      const IGNORAR = new Set(['node_modules', '.git', 'dist', 'build', 'coverage', '.venv', '__pycache__', '.next', 'out']);

      const porExt = new Map<string, { archivos: number; bytes: number }>();
      const grandes: { ruta: string; kb: number }[] = [];
      let totalArchivos = 0;
      let totalBytes = 0;

      async function recorrer(dir: string, nivel: number): Promise<void> {
        if (nivel > profundidad) return;
        let entries;
        try {
          entries = await fs.readdir(dir, { withFileTypes: true });
        } catch {
          return;
        }
        for (const e of entries) {
          if (IGNORAR.has(e.name)) continue;
          const full = path.join(dir, e.name);
          if (e.isDirectory()) {
            await recorrer(full, nivel + 1);
          } else if (e.isFile()) {
            const stat = await fs.stat(full).catch(() => null);
            if (!stat) continue;
            totalArchivos++;
            totalBytes += stat.size;
            const ext = path.extname(e.name).toLowerCase() || '(sin extensión)';
            const acc = porExt.get(ext) ?? { archivos: 0, bytes: 0 };
            acc.archivos++;
            acc.bytes += stat.size;
            porExt.set(ext, acc);
            grandes.push({ ruta: path.relative(raiz, full), kb: Math.round(stat.size / 1024) });
          }
        }
      }
      await recorrer(raiz, 0);

      const existe = async (rel: string) => Boolean(await fs.stat(path.join(raiz, rel)).catch(() => null));
      const marcadores = {
        package_json: await existe('package.json'),
        requirements_txt: await existe('requirements.txt'),
        pyproject_toml: await existe('pyproject.toml'),
        go_mod: await existe('go.mod'),
        cargo_toml: await existe('Cargo.toml'),
        readme: (await existe('README.md')) || (await existe('README.rst')),
        dockerfile: await existe('Dockerfile'),
        docker_compose: (await existe('docker-compose.yml')) || (await existe('compose.yaml')),
        ci_github: await existe('.github/workflows'),
        tests_dir: (await existe('tests')) || (await existe('test')) || (await existe('__tests__')),
        git: await existe('.git'),
      };

      const stack: string[] = [];
      let extras: Record<string, unknown> = {};
      if (marcadores.package_json) {
        stack.push('Node.js');
        try {
          const pkg = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
          if (pkg.dependencies?.typescript || pkg.devDependencies?.typescript) stack.push('TypeScript');
          extras = {
            nombre: pkg.name,
            version: pkg.version,
            scripts: Object.keys(pkg.scripts ?? {}),
            dependencias: Object.keys(pkg.dependencies ?? {}),
            dependencias_dev: Object.keys(pkg.devDependencies ?? {}).slice(0, 20),
          };
        } catch {
          /* package.json ilegible */
        }
      }
      if (marcadores.requirements_txt) stack.push('Python');
      if (marcadores.pyproject_toml) stack.push('Python (pyproject)');
      if (marcadores.go_mod) stack.push('Go');
      if (marcadores.cargo_toml) stack.push('Rust');

      return {
        raiz,
        stack: stack.length ? stack : ['no detectado'],
        archivos_analizados: totalArchivos,
        tamaño_total_mb: Number((totalBytes / 1e6).toFixed(2)),
        por_extension: [...porExt.entries()]
          .sort((a, b) => b[1].bytes - a[1].bytes)
          .slice(0, 15)
          .map(([ext, v]) => ({ extension: ext, archivos: v.archivos, kb: Math.round(v.bytes / 1024) })),
        archivos_mas_pesados: grandes.sort((a, b) => b.kb - a.kb).slice(0, 10),
        indicadores: marcadores,
        ...extras,
      };
    },
  });

  push({
    module: 'devops',
    name: 'http_health_check',
    title: 'Health check de un endpoint',
    description:
      'Hace una petición HTTP (GET/HEAD) a una URL y reporta si el servicio responde: código de estado, tiempo de respuesta, headers relevantes (Content-Type, Server) y los primeros caracteres del cuerpo. Útil para monitorear sitios y APIs propias.',
    readOnly: true,
    inputSchema: {
      url: z.string().url().describe('URL completa a verificar, ej. "https://miempresa.mx" o "https://api.miempresa.mx/health".'),
      metodo: z.enum(['GET', 'HEAD']).optional().describe('Método HTTP (por defecto GET).'),
      timeout_ms: z.number().int().min(1000).max(30_000).optional().describe('Timeout en milisegundos (por defecto 10000).'),
      mostrar_cuerpo: z.boolean().optional().describe('Incluir los primeros 500 caracteres del cuerpo.'),
    },
    handler: async (args: { url: string; metodo?: 'GET' | 'HEAD'; timeout_ms?: number; mostrar_cuerpo?: boolean }) => {
      const u = new URL(args.url);
      if (!ctx.config.httpCheckAllowPrivate && IP_PRIVADA.test(u.hostname)) {
        throw new Error(
          `La URL apunta a una dirección privada/local (${u.hostname}). Por seguridad está bloqueado; activa HTTP_CHECK_ALLOW_PRIVATE=1 si es intencional.`,
        );
      }
      const t0 = performance.now();
      const res = await fetch(args.url, {
        method: args.metodo ?? 'GET',
        redirect: 'follow',
        signal: AbortSignal.timeout(args.timeout_ms ?? 10_000),
        headers: { 'user-agent': 'mcp-suite-mx-healthcheck/0.1' },
      });
      const ms = Math.round(performance.now() - t0);
      const salida: Record<string, unknown> = {
        url: args.url,
        ok: res.ok,
        status: res.status,
        status_text: res.statusText,
        tiempo_respuesta_ms: ms,
        content_type: res.headers.get('content-type'),
        server: res.headers.get('server'),
        redirige_a: res.url !== args.url ? res.url : null,
        veredicto: res.ok ? (ms < 1000 ? 'saludable y rápido' : 'saludable pero lento') : 'con problemas',
      };
      if (args.mostrar_cuerpo && (args.metodo ?? 'GET') === 'GET') {
        const cuerpo = await res.text();
        salida.cuerpo_preview = cuerpo.slice(0, 500);
        salida.cuerpo_bytes = Buffer.byteLength(cuerpo, 'utf8');
      }
      log.info('health_check', { url: args.url, status: res.status, ms });
      return salida;
    },
  });

  push({
    module: 'devops',
    name: 'log_analizar',
    title: 'Analizar archivo de logs',
    description:
      'Analiza un archivo de log: cuenta líneas, clasifica por nivel (ERROR, WARN, INFO, DEBUG), agrupa los errores más repetidos, aplica un patrón regex opcional y detecta el rango de fechas. Útil para diagnosticar caídas o picos de error.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Ruta del archivo de log (relativa al directorio de datos o absoluta en rutas autorizadas).'),
      patron: z.string().optional().describe('Expresión regular para contar coincidencias, ej. "(timeout|connection refused)".'),
      limite_lineas: z.number().int().min(100).max(200_000).optional().describe('Máximo de líneas a analizar (por defecto 50000).'),
      max_ejemplos: z.number().int().min(1).max(20).optional().describe('Cuántos ejemplos de líneas de error devolver (por defecto 5).'),
    },
    handler: async (args: { ruta: string; patron?: string; limite_lineas?: number; max_ejemplos?: number }) => {
      const file = resolveAllowedPath(ctx.config.dataDir, args.ruta, [process.cwd()]);
      const contenido = await readTextFile(file, 50_000_000);
      const lineas = contenido
        .split('\n')
        .filter((l) => l.trim() !== '')
        .slice(0, args.limite_lineas ?? 50_000);

      const niveles: Record<string, number> = { ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0, otros: 0 };
      const errores = new Map<string, { conteo: number; ultima: string }>();
      const ejemplosErrores: string[] = [];
      let coincidenciasPatron = 0;
      let re: RegExp | null = null;

      if (args.patron) {
        try {
          re = new RegExp(args.patron, 'i');
        } catch (e: any) {
          throw new Error(`Patrón regex inválido: ${e.message}`);
        }
      }

      for (const linea of lineas) {
        if (!linea.trim()) continue;
        const up = linea;
        if (/\b(ERROR|FATAL|CRITICAL|ERR)\b/.test(up) || /\berror\b/i.test(up)) niveles.ERROR!++;
        else if (/\bWARN(ING)?\b/i.test(up)) niveles.WARN!++;
        else if (/\bINFO\b/i.test(up)) niveles.INFO!++;
        else if (/\bDEBUG\b/i.test(up)) niveles.DEBUG!++;
        else niveles.otros!++;

        if (/\b(ERROR|FATAL|CRITICAL)\b/i.test(up)) {
          const clave = up
            .replace(/\d{4}-\d{2}-\d{2}[T ][\d:.]+/g, '')
            .replace(/\b\d+\b/g, 'N')
            .slice(0, 160)
            .trim();
          const acc = errores.get(clave) ?? { conteo: 0, ultima: '' };
          acc.conteo++;
          acc.ultima = linea.trim().slice(0, 300);
          errores.set(clave, acc);
          if (ejemplosErrores.length < (args.max_ejemplos ?? 5)) ejemplosErrores.push(linea.trim().slice(0, 300));
        }
        if (re && re.test(linea)) coincidenciasPatron++;
      }

      const fechaRegex = /\d{4}-\d{2}-\d{2}[T ][\d:]{5,8}/;
      const primeraFecha = lineas.find((l) => fechaRegex.test(l))?.match(fechaRegex)?.[0] ?? null;
      const ultimaFecha = [...lineas].reverse().find((l) => fechaRegex.test(l))?.match(fechaRegex)?.[0] ?? null;

      return {
        archivo: path.basename(file),
        lineas_analizadas: lineas.length,
        niveles,
        rango_fechas: primeraFecha || ultimaFecha ? { desde: primeraFecha, hasta: ultimaFecha } : null,
        patron_aplicado: args.patron ?? null,
        coincidencias_patron: args.patron ? coincidenciasPatron : null,
        errores_unicos: errores.size,
        top_errores: [...errores.entries()]
          .sort((a, b) => b[1].conteo - a[1].conteo)
          .slice(0, 10)
          .map(([plantilla, v]) => ({ plantilla, conteo: v.conteo, ejemplo: v.ultima })),
        ejemplos_errores: ejemplosErrores,
      };
    },
  });
}
