import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import type http from 'node:http';
import { projectRoot } from '../src/config';
import { startHttp } from '../src/http';
import { ensureSeedData } from '../src/seed';
import { crearClave } from '../src/auth/keys';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';

const CLAVE_BOOTSTRAP = 'clave-bootstrap-de-prueba-123';

let servidor: http.Server;
let base: string;
let dataDir: string;
let ctx: ServerCtx;

function post(url: string, body: unknown, token?: string): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

const initMsg = {
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'curl-test', version: '0.0.1' } },
};

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-http-');
  ctx = await contextoDePrueba(dataDir, {
    transport: 'http',
    port: 0,
    host: '127.0.0.1',
    allowedGitRoots: [projectRoot],
    authMode: 'required',
    apiKeys: [CLAVE_BOOTSTRAP],
    rateLimitRpm: 6000,
    rateLimitBurst: 1000,
  });
  await ensureSeedData(ctx);
  servidor = await startHttp(ctx);
  const addr = servidor.address();
  base = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
});

afterAll(async () => {
  await new Promise<void>((resolve) => servidor.close(() => resolve()));
  await fs.rm(dataDir, { recursive: true, force: true });
});

describe('transporte HTTP con AUTH_MODE=required', () => {
  it('/health y /panel son públicos; /mcp exige clave', async () => {
    const health = await fetch(`${base}/health`);
    expect(health.status).toBe(200);
    expect((await health.json()).auth_mode).toBe('required');

    const panel = await fetch(`${base}/panel`);
    expect(panel.status).toBe(200);
    expect(await panel.text()).toContain('Catálogo de herramientas');

    const sinClave = await post(`${base}/mcp`, initMsg);
    expect(sinClave.status).toBe(401);
    expect(sinClave.headers.get('www-authenticate')).toBe('Bearer');
    expect((await sinClave.json()).error).toMatch(/Se requiere autenticación/);
  });

  it('rechaza claves inválidas y deja rastro en la auditoría', async () => {
    const mala = await post(`${base}/mcp`, initMsg, 'mcp_deadbeef_secreto-falso');
    expect(mala.status).toBe(401);

    const eventos = await ctx.audit.leer({ tipo: 'auth_fallo' });
    expect(eventos.length).toBeGreaterThan(0);
    expect(ctx.metrics.obtener('mcp_suite_auth_fallos_total')).toBeGreaterThan(0);
  });

  it('con la clave bootstrap completa el handshake MCP y llama herramientas', async () => {
    const init = await post(`${base}/mcp`, initMsg, CLAVE_BOOTSTRAP);
    expect(init.status).toBe(200);
    const initJson = await init.json();
    expect(initJson.result.serverInfo.name).toBe('mcp-suite-mx');

    const llamada = await post(
      `${base}/mcp`,
      {
        jsonrpc: '2.0',
        id: 3,
        method: 'tools/call',
        params: { name: 'calculadora_impuestos', arguments: { monto: 1000, operacion: 'agregar' } },
      },
      CLAVE_BOOTSTRAP,
    );
    const cuerpo = await llamada.json();
    expect(cuerpo.result.content[0].text).toContain('1160');
  });

  it('una clave con scopes limitados solo ve y ejecuta lo permitido', async () => {
    const { clave } = await crearClave(ctx, { nombre: 'lector-negocio', scopes: ['negocio:ro'] });

    const lista = await post(`${base}/mcp`, { jsonrpc: '2.0', id: 2, method: 'tools/list' }, clave);
    const nombres = (await lista.json()).result.tools.map((t: any) => t.name);
    expect(nombres).toContain('inventario_consultar'); // lectura de negocio
    expect(nombres).toContain('mcp_suite_info'); // core siempre
    expect(nombres).not.toContain('inventario_actualizar'); // destructiva bloqueada
    expect(nombres).not.toContain('pedido_crear');
    expect(nombres).not.toContain('git_status'); // otro módulo

    const prohibida = await post(
      `${base}/mcp`,
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'pedido_crear', arguments: { cliente: 'X', items: [{ sku: 'TEC-001', cantidad: 1 }] } } },
      clave,
    );
    // El SDK responde "Tool not found": la herramienta simplemente no existe para esa clave.
    const cuerpo = await prohibida.json();
    expect(cuerpo.result.isError).toBe(true);
    expect(cuerpo.result.content[0].text).toMatch(/pedido_crear/);
  });

  it('/metrics expone contadores en formato Prometheus', async () => {
    const r = await fetch(`${base}/metrics`);
    expect(r.headers.get('content-type')).toContain('text/plain');
    const texto = await r.text();
    expect(texto).toContain('# TYPE mcp_suite_tools_llamadas_total counter');
    expect(texto).toContain('# TYPE mcp_suite_http_peticiones_total counter');
    expect(texto).toContain('ruta="/mcp"');
  });

  it('/api/auditoria exige clave cuando AUTH_MODE=required', async () => {
    expect((await fetch(`${base}/api/auditoria`)).status).toBe(401);
    const conClave = await fetch(`${base}/api/auditoria?limite=5`, { headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}` } });
    expect(conClave.status).toBe(200);
    const cuerpo = await conClave.json();
    expect(cuerpo.resumen.auth_fallo).toBeGreaterThan(0);
  });

  it('el panel resuelve aprobaciones: /api/aprobaciones exige clave y el POST aprueba', async () => {
    const { solicitarAprobacion, obtenerAprobacion } = await import('../src/lib/aprobaciones');
    const ap = await solicitarAprobacion(ctx, {
      tipo: 'despliegue',
      titulo: 'Publicar 0.2.0 desde el panel',
      detalle: 'Prueba de extremo a extremo del botón Aprobar.',
      huella: 'huella-de-prueba-panel',
    });
    // Sin clave: cerrado.
    expect((await fetch(`${base}/api/aprobaciones`)).status).toBe(401);
    const listado = await fetch(`${base}/api/aprobaciones`, { headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}` } });
    const cuerpo = await listado.json();
    expect(cuerpo.pendientes).toBeGreaterThanOrEqual(1);
    expect(cuerpo.aprobaciones.some((a: any) => a.id === ap.id)).toBe(true);
    // Decisión inválida: 400. Aprobación inexistente: 409.
    const invalida = await fetch(`${base}/api/aprobaciones/${ap.id}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}`, 'content-type': 'application/json' },
      body: JSON.stringify({ decision: 'quizá' }),
    });
    expect(invalida.status).toBe(400);
    const fantasma = await fetch(`${base}/api/aprobaciones/AP-noexiste`, {
      method: 'POST',
      headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}`, 'content-type': 'application/json' },
      body: JSON.stringify({ decision: 'aprobar' }),
    });
    expect(fantasma.status).toBe(409);
    // El botón «Aprobar» del panel: mismo camino que el CLI y queda en la auditoría.
    const ok = await fetch(`${base}/api/aprobaciones/${ap.id}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}`, 'content-type': 'application/json' },
      body: JSON.stringify({ decision: 'aprobar', por: 'panel-de-prueba' }),
    });
    expect(ok.status).toBe(200);
    expect((await ok.json()).estado).toBe('aprobada');
    expect(obtenerAprobacion(ctx, ap.id)?.resuelta_por).toBe('panel-de-prueba');
  });

  it('/api/parches muestra el historial con huella y veredicto, y exige clave', async () => {
    const { crearParche, guardarParche } = await import('../src/lib/parche');
    // Proyecto mínimo: el parche solo se registra (nunca se aplica en esta prueba).
    const proyectoPanel = path.join(dataDir, 'proyecto-panel');
    await fs.mkdir(proyectoPanel, { recursive: true });
    await fs.writeFile(path.join(proyectoPanel, 'saludo.js'), 'console.log("hola");\n', 'utf8');
    const parche = await crearParche(proyectoPanel, {
      titulo: 'Parche de prueba para el panel',
      justificacion: 'Comprobar que el panel lista parches con su huella.',
      cambios: [{ ruta: 'saludo.js', antes: 'console.log("hola");', despues: 'console.log("hola, mundo");', descripcion: 'saludo más claro' }],
      creado_por: 'prueba',
    });
    guardarParche(ctx.db, parche);
    expect((await fetch(`${base}/api/parches`)).status).toBe(401);
    const r = await fetch(`${base}/api/parches?limite=5`, { headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}` } });
    const cuerpo = await r.json();
    expect(cuerpo.parches.some((p: any) => p.id === parche.id)).toBe(true);
    const mio = cuerpo.parches.find((p: any) => p.id === parche.id);
    expect(mio.huella).toMatch(/^[0-9a-f]{16}$/);
    expect(mio.archivos).toContain('saludo.js');
  });

  it('/api/empresas lista los espacios y su comparativa, y exige clave', async () => {
    expect((await fetch(`${base}/api/empresas`)).status).toBe(401);
    const { crearEmpresa } = await import('../src/lib/empresas');
    const nueva = crearEmpresa(ctx, { nombre: 'Empresa HTTP', rfc: 'ABC010101AB1', regimen: '601', cp: '44100', series: 'H' });
    const r = await fetch(`${base}/api/empresas?comparar=1`, { headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}` } });
    const cuerpo = await r.json();
    expect(cuerpo.total).toBeGreaterThanOrEqual(2);
    expect(cuerpo.activa.id).toBeDefined();
    expect(cuerpo.empresas.some((e: any) => e.id === nueva.id && e.series === 'H')).toBe(true);
    expect(Array.isArray(cuerpo.comparativa)).toBe(true);
  });

  it('/api/claves lista claves emitidas sin hashes', async () => {
    const r = await fetch(`${base}/api/claves`, { headers: { authorization: `Bearer ${CLAVE_BOOTSTRAP}` } });
    const cuerpo = await r.json();
    expect(cuerpo.total).toBeGreaterThan(0);
    expect(cuerpo.claves.every((k: any) => !('hash' in k))).toBe(true);
    expect(cuerpo.claves.some((k: any) => k.nombre === 'lector-negocio')).toBe(true);
  });
});

describe('límite de tasa por cliente (HTTP)', () => {
  let servidorLimitado: http.Server;
  let baseLimitada: string;

  beforeAll(async () => {
    const dir = await dataDirTemporal('mcp-suite-rl-');
    const ctxRl = await contextoDePrueba(
      dir,
      { transport: 'http', port: 0, host: '127.0.0.1', authMode: 'off' },
      { rpm: 1, burst: 1 },
    );
    await ensureSeedData(ctxRl);
    servidorLimitado = await startHttp(ctxRl);
    const addr = servidorLimitado.address();
    baseLimitada = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => servidorLimitado.close(() => resolve()));
  });

  it('bloquea la segunda llamada inmediata con mensaje de reintento', async () => {
    const llamar = () =>
      post(`${baseLimitada}/mcp`, {
        jsonrpc: '2.0',
        id: 9,
        method: 'tools/call',
        params: { name: 'calculadora_impuestos', arguments: { monto: 100, operacion: 'agregar' } },
      });

    const primera = await llamar();
    expect((await primera.json()).result).toBeTruthy();

    const segunda = await llamar();
    const cuerpo = await segunda.json();
    expect(cuerpo.result.isError).toBe(true);
    expect(cuerpo.result.content[0].text).toMatch(/Límite de peticiones excedido/);
  });
});

describe('rutas auxiliares sin autenticación (AUTH_MODE=off)', () => {
  let servidorAbierto: http.Server;
  let baseAbierta: string;

  beforeAll(async () => {
    const dir = await dataDirTemporal('mcp-suite-open-');
    const ctxAbierto = await contextoDePrueba(dir, { transport: 'http', port: 0, host: '127.0.0.1', authMode: 'off' });
    await ensureSeedData(ctxAbierto);
    servidorAbierto = await startHttp(ctxAbierto);
    const addr = servidorAbierto.address();
    baseAbierta = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => servidorAbierto.close(() => resolve()));
  });

  it('permite el handshake sin clave (modo desarrollo)', async () => {
    const init = await post(`${baseAbierta}/mcp`, initMsg);
    expect(init.status).toBe(200);
    const lista = await post(`${baseAbierta}/mcp`, { jsonrpc: '2.0', id: 2, method: 'tools/list' });
    expect((await lista.json()).result.tools.length).toBeGreaterThanOrEqual(35);
  });

  it('/api/catalogo agrupa por módulo y rechaza GET en /mcp', async () => {
    const r = await fetch(`${baseAbierta}/api/catalogo`);
    const j = await r.json();
    const modulos = j.modulos.map((m: any) => m.modulo);
    expect(modulos).toEqual(expect.arrayContaining(['negocio', 'facturacion', 'documentos', 'analisis', 'devops']));
    expect(j.total).toBeGreaterThanOrEqual(35);

    const get = await fetch(`${baseAbierta}/mcp`);
    expect(get.status).toBe(405);
    expect((await get.json()).error).toMatch(/Streamable HTTP/);
  });

  it('responde 404 con la lista de rutas', async () => {
    const r = await fetch(`${baseAbierta}/no-existe`);
    expect(r.status).toBe(404);
    expect((await r.json()).rutas).toContain('/api/auditoria');
  });
});
