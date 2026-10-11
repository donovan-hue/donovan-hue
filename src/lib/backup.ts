import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import type { ServerCtx } from '../context.js';
import { log } from '../logger.js';

export interface ArchivoBackup {
  nombre: string;
  bytes: number;
  sha256: string;
}

export interface ResultadoBackup {
  ruta: string;
  fecha: string;
  archivos: ArchivoBackup[];
  bytes_total: number;
  conteos: Record<string, number>;
  respaldos_conservados: number;
  eliminados: string[];
}

async function hashArchivo(ruta: string): Promise<{ bytes: number; sha256: string }> {
  const contenido = await fs.readFile(ruta);
  return { bytes: contenido.length, sha256: crypto.createHash('sha256').update(contenido).digest('hex') };
}

async function existe(ruta: string): Promise<boolean> {
  return Boolean(await fs.stat(ruta).catch(() => null));
}

/**
 * Respaldo en caliente y consistente:
 * 1. `VACUUM INTO` produce una copia íntegra de SQLite sin detener el servidor.
 * 2. Copia la bitácora de auditoría (append-only) y los datos de trabajo.
 * 3. Escribe un manifiesto con hashes sha256 para verificar integridad.
 * 4. Rota: conserva los N respaldos más recientes.
 */
export async function crearBackup(ctx: ServerCtx, opciones: { destino?: string; conservar?: number } = {}): Promise<ResultadoBackup> {
  const base = opciones.destino ? path.resolve(opciones.destino) : path.join(ctx.config.dataDir, 'backups');
  const conservar = opciones.conservar ?? 7;
  const fecha = new Date().toISOString();
  // Sello con milisegundos para que dos respaldos seguidos no colisionen.
  const sello = fecha.replace('T', '_').replace('Z', '').replace(/[:.]/g, '-');
  let dir = path.join(base, `backup-${sello}`);
  for (let i = 2; await existe(dir); i++) {
    dir = path.join(base, `backup-${sello}_${i}`);
  }
  await fs.mkdir(dir, { recursive: true });

  // 1. Copia consistente de la base (funciona con el servidor en marcha).
  const dbDestino = path.join(dir, 'suite.db');
  await ctx.db.exec(`VACUUM INTO '${dbDestino.replace(/'/g, "''")}'`);

  // 2. Auditoría y datos de trabajo.
  if (await existe(ctx.audit.ruta)) {
    await fs.copyFile(ctx.audit.ruta, path.join(dir, 'auditoria.ndjson'));
  }
  for (const sub of ['datasets', 'logs', 'facturacion']) {
    const origen = path.join(ctx.config.dataDir, sub);
    if (await existe(origen)) {
      await fs.cp(origen, path.join(dir, sub), { recursive: true });
    }
  }

  // 3. Manifiesto con hashes.
  const listar = async (d: string, prefijo = ''): Promise<ArchivoBackup[]> => {
    const entradas = await fs.readdir(d, { withFileTypes: true });
    const archivos: ArchivoBackup[] = [];
    for (const e of entradas) {
      const completa = path.join(d, e.name);
      const relativa = prefijo ? `${prefijo}/${e.name}` : e.name;
      if (e.isDirectory()) {
        archivos.push(...(await listar(completa, relativa)));
      } else if (e.name !== 'manifest.json') {
        const { bytes, sha256 } = await hashArchivo(completa);
        archivos.push({ nombre: relativa, bytes, sha256 });
      }
    }
    return archivos;
  };
  const archivos = await listar(dir);

  const contar = (tabla: string): number => (ctx.db.prepare(`SELECT COUNT(*) AS n FROM ${tabla}`).get() as { n: number }).n;
  const conteos = {
    inventario: contar('inventario'),
    pedidos: contar('pedidos'),
    plantillas: contar('plantillas'),
    api_keys: contar('api_keys'),
  };

  const manifest = {
    servidor: 'mcp-suite-mx',
    fecha,
    sqlite_version: (ctx.db.prepare(`SELECT sqlite_version() AS v`).get() as { v: string }).v,
    conteos,
    archivos,
  };
  await fs.writeFile(path.join(dir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

  // 4. Rotación.
  const entradas = (await fs.readdir(base, { withFileTypes: true }))
    .filter((e) => e.isDirectory() && e.name.startsWith('backup-'))
    .map((e) => e.name)
    .sort();
  const eliminados = entradas.slice(0, Math.max(0, entradas.length - conservar));
  for (const viejo of eliminados) {
    await fs.rm(path.join(base, viejo), { recursive: true, force: true });
  }

  const resultado: ResultadoBackup = {
    ruta: dir,
    fecha,
    archivos,
    bytes_total: archivos.reduce((a, f) => a + f.bytes, 0),
    conteos,
    respaldos_conservados: entradas.length - eliminados.length,
    eliminados,
  };
  log.info('backup_creado', {
    ruta: dir,
    bytes: resultado.bytes_total,
    archivos: archivos.length,
    eliminados: eliminados.length,
  });
  return resultado;
}

/** Lista los respaldos existentes (más reciente primero). */
export async function listarBackups(ctx: ServerCtx, destino?: string): Promise<{ nombre: string; ruta: string; fecha: string }[]> {
  const base = destino ? path.resolve(destino) : path.join(ctx.config.dataDir, 'backups');
  const entradas = await fs.readdir(base, { withFileTypes: true }).catch(() => []);
  return entradas
    .filter((e) => e.isDirectory() && e.name.startsWith('backup-'))
    .map((e) => ({ nombre: e.name, ruta: path.join(base, e.name), fecha: e.name.replace('backup-', '') }))
    .sort((a, b) => b.nombre.localeCompare(a.nombre));
}
