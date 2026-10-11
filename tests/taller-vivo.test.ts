/**
 * Pruebas del GEMELO VIVO: estado en movimiento, delta entre revisiones y
 * página/API del mapa interactivo.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { obtenerEstadoVivo, limpiarCacheVivo } from '../src/lib/taller-vivo';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import { startHttp } from '../src/http';
import type { ServerCtx } from '../src/context';
import type { AddressInfo } from 'node:net';

let dataDir: string;
let proyecto: string;
let ctx: ServerCtx;
let servidor: { close: () => Promise<void>; puerto: number };
let base: string;

async function escribir(rel: string, contenido: string): Promise<void> {
  const absoluto = path.join(proyecto, rel);
  await fs.mkdir(path.dirname(absoluto), { recursive: true });
  await fs.writeFile(absoluto, contenido, 'utf8');
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-vivo-');
  proyecto = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-vivo-'));
  await escribir('package.json', JSON.stringify({ name: 'proyecto-vivo', version: '1.0.0', main: 'src/index.js', scripts: { test: 'node --test' } }));
  await escribir('src/index.js', "const { montar } = require('./rutas/pedidos');\nmodule.exports = { montar };\n");
  await escribir('src/rutas/pedidos.js', "function montar(app) {\n  app.get('/api/pedidos', (req, res) => res.json([]));\n}\nmodule.exports = { montar };\n");

  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [proyecto], port: 0 }, { rpm: 6000, burst: 1000 });
  const srv = await startHttp(ctx);
  const direccion = srv.address() as AddressInfo;
  base = `http://127.0.0.1:${direccion.port}`;
  servidor = { close: () => new Promise((res) => srv.close(() => res())), puerto: direccion.port };
});

afterAll(async () => {
  await servidor?.close();
  await fs.rm(dataDir, { recursive: true, force: true });
  await fs.rm(proyecto, { recursive: true, force: true });
  limpiarCacheVivo();
});

describe('gemelo vivo — estado y delta', () => {
  it('primera revisión: estado completo sin cambios previos', async () => {
    limpiarCacheVivo(proyecto);
    const r = await obtenerEstadoVivo(proyecto);
    expect(r.estado.revision).toBe(1);
    expect(r.cambios).toEqual([]);
    expect(r.estado.totales.archivos).toBe(2);
    expect(r.estado.totales.rutas).toBe(1);
    expect(r.estado.entradas).toContain('src/index.js');
    expect(r.estado.archivos.find((a) => a.ruta === 'src/rutas/pedidos.js')?.dependientes).toEqual(['src/index.js']);
  });

  it('sin cambios: devuelve la caché sin re-analizar', async () => {
    const r = await obtenerEstadoVivo(proyecto);
    expect(r.cacheado).toBe(true);
    expect(r.cambios).toEqual([]);
  });

  it('detecta archivo nuevo, ruta nueva y pendiente nuevo al guardar', async () => {
    await escribir('src/rutas/reportes.js', "// TODO: conectar al servidor\nfunction montar(app) {\n  app.get('/api/reportes', (req, res) => res.json([]));\n}\nmodule.exports = { montar };\n");
    const r = await obtenerEstadoVivo(proyecto);
    expect(r.cacheado).toBe(false);
    expect(r.estado.revision).toBe(2);
    const tipos = r.cambios.map((c) => c.tipo);
    expect(tipos).toContain('archivo_nuevo');
    expect(tipos).toContain('ruta_nueva');
    expect(tipos).toContain('pendiente_nuevo');
    expect(r.cambios.some((c) => c.detalle.includes('/api/reportes'))).toBe(true);
  });

  it('detecta huérfano nuevo y su resolución al conectarlo', async () => {
    await escribir('src/huerfano.js', 'function util() { return 1; }\nmodule.exports = { util };\n');
    let r = await obtenerEstadoVivo(proyecto);
    expect(r.cambios.some((c) => c.tipo === 'huerfano_nuevo' && c.archivo === 'src/huerfano.js')).toBe(true);

    await escribir('src/index.js', "const { montar } = require('./rutas/pedidos');\nconst { util } = require('./huerfano');\nmodule.exports = { montar, util };\n");
    r = await obtenerEstadoVivo(proyecto);
    expect(r.cambios.some((c) => c.tipo === 'huerfano_resuelto' && c.archivo === 'src/huerfano.js')).toBe(true);
  });

  it('detecta líneas eliminadas y archivos borrados', async () => {
    await fs.rm(path.join(proyecto, 'src/huerfano.js'));
    const r = await obtenerEstadoVivo(proyecto);
    expect(r.cambios.some((c) => c.tipo === 'archivo_eliminado' && c.archivo === 'src/huerfano.js')).toBe(true);
  });
});

describe('gemelo vivo — página y API HTTP', () => {
  it('GET /api/taller/mapa devuelve estado, cambios y raíces', async () => {
    const r = await fetch(`${base}/api/taller/mapa`);
    expect(r.status).toBe(200);
    const d = await r.json();
    expect(d.estado.archivos.length).toBeGreaterThanOrEqual(3);
    expect(Array.isArray(d.cambios)).toBe(true);
    expect(Array.isArray(d.raices)).toBe(true);
    expect(d.raices).toContain(proyecto);
  });

  it('GET /taller sirve la página con el estado inyectado', async () => {
    const r = await fetch(`${base}/taller`);
    expect(r.status).toBe(200);
    const html = await r.text();
    expect(html).toContain('Mapa vivo');
    expect(html).toContain('api/taller/mapa');
    expect(html).not.toContain('<!--TALLER_JSON-->');
    expect(html).toContain('proyecto-vivo');
    // La escena 3D viaja en la página (motor propio, sin librerías ni CDN).
    expect(html).toContain('lienzo3d');
    expect(html).toContain('escena3d');
    expect(html).toContain('3D en vivo');
    expect(html).not.toMatch(/https?:\/\/[^"' ]+\.(js|css)/i); // nada de recursos externos
  });

  it('rechaza raíces fuera de las rutas autorizadas (usa la primera)', async () => {
    const r = await fetch(`${base}/api/taller/mapa?raiz=${encodeURIComponent('/etc')}`);
    const d = await r.json();
    expect(d.estado.raiz).toBe(proyecto);
  });

  it('con TALLER_PUBLICO=0 y auth required, exige clave', async () => {
    const dataDir2 = await dataDirTemporal('mcp-suite-vivo-priv-');
    const ctx2 = await contextoDePrueba(dataDir2, { allowedGitRoots: [proyecto], tallerPublico: false, authMode: 'required', port: 0 }, { rpm: 6000, burst: 1000 });
    const srv = await startHttp(ctx2);
    const puerto = (srv.address() as AddressInfo).port;
    const sinClave = await fetch(`http://127.0.0.1:${puerto}/api/taller/mapa`);
    expect(sinClave.status).toBe(401);
    await new Promise((res) => srv.close(() => res()));
    await fs.rm(dataDir2, { recursive: true, force: true });
  });
});
