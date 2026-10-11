import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import { crearBackup, listarBackups } from '../src/lib/backup';
import { abrirDb } from '../src/db';
import { ensureSeedData } from '../src/seed';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';

let ctx: ServerCtx;
let dataDir: string;
let destino: string;

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-backup-');
  destino = path.join(dataDir, 'respaldos');
  ctx = await contextoDePrueba(dataDir);
  await ensureSeedData(ctx);
  // Un evento de auditoría y un archivo de facturación para verificar la copia de datos de trabajo.
  await ctx.audit.registrar({ tipo: 'tool_destructiva', cliente: 'pruebas', herramienta: 'inventario_actualizar', ok: true });
  await fs.mkdir(path.join(dataDir, 'facturacion'), { recursive: true });
  await fs.writeFile(path.join(dataDir, 'facturacion', 'A0001.xml'), '<cfdi:Comprobante Version="4.0"/>', 'utf8');
});

afterAll(async () => {
  await fs.rm(dataDir, { recursive: true, force: true });
});

describe('respaldos en caliente', () => {
  it('crea una copia consistente de SQLite, auditoría y datos de trabajo con manifiesto verificable', async () => {
    const r = await crearBackup(ctx, { destino, conservar: 7 });

    const dbRespaldo = path.join(r.ruta, 'suite.db');
    const auditRespaldo = path.join(r.ruta, 'auditoria.ndjson');
    const facturaRespaldo = path.join(r.ruta, 'facturacion', 'A0001.xml');
    for (const f of [dbRespaldo, auditRespaldo, facturaRespaldo, path.join(r.ruta, 'manifest.json')]) {
      expect(await fs.stat(f).catch(() => null)).toBeTruthy();
    }

    // La copia de la base abre y tiene los mismos conteos que el original.
    const copia = abrirDb(dbRespaldo);
    const conteo = (db: typeof copia, tabla: string) => (db.prepare(`SELECT COUNT(*) AS n FROM ${tabla}`).get() as { n: number }).n;
    expect(conteo(copia, 'inventario')).toBe(conteo(ctx.db, 'inventario'));
    expect(conteo(copia, 'pedidos')).toBe(conteo(ctx.db, 'pedidos'));
    expect(r.conteos.inventario).toBe(conteo(ctx.db, 'inventario'));
    copia.close();

    // El manifiesto incluye hashes y conteos.
    const manifest = JSON.parse(await fs.readFile(path.join(r.ruta, 'manifest.json'), 'utf8'));
    expect(manifest.servidor).toBe('mcp-suite-mx');
    expect(manifest.conteos.plantillas).toBe(r.conteos.plantillas);
    expect(manifest.archivos.some((a: any) => a.nombre === 'suite.db' && a.sha256.length === 64)).toBe(true);
    expect(r.bytes_total).toBeGreaterThan(0);
  });

  it('rota los respaldos viejos según la política de conservación', async () => {
    const creados: string[] = [];
    for (let i = 0; i < 3; i++) {
      const r = await crearBackup(ctx, { destino, conservar: 2 });
      creados.push(r.ruta);
    }
    // Rutas únicas incluso si se crean en el mismo milisegundo.
    expect(new Set(creados).size).toBe(3);

    const lista = await listarBackups(ctx, destino);
    expect(lista.length).toBe(2);
    expect(await fs.stat(creados[2]!).catch(() => null)).toBeTruthy(); // el más reciente se conserva
    expect(lista[0]!.ruta).toBe(creados[2]); // ordenados del más nuevo al más viejo
  });
});
