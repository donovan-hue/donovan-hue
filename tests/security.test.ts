import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import { contarClavesActivas, crearClave, listarClaves, revocarClave, verificarClave } from '../src/auth/keys';
import { describirScopes, politicaDesdeScopes } from '../src/auth/politica';
import { LimitadorTasas } from '../src/lib/rate-limit';
import { Metricas } from '../src/lib/metrics';
import { Auditoria, resumirArgs } from '../src/lib/audit';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';

let ctx: ServerCtx;
let dataDir: string;

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-sec-');
  ctx = await contextoDePrueba(dataDir);
});

afterAll(async () => {
  await fs.rm(dataDir, { recursive: true, force: true });
});

describe('API keys (SQLite)', () => {
  it('crea una clave, la guarda hasheada y la verifica', async () => {
    const { key, clave } = await crearClave(ctx, { nombre: 'cliente-prueba', scopes: ['negocio:ro', 'analisis'] });
    expect(clave).toMatch(/^mcp_[0-9a-f]{8}_[A-Za-z0-9_-]+$/);
    expect(clave.startsWith(`mcp_${key.id}_`)).toBe(true);

    const fila = ctx.db.prepare(`SELECT hash FROM api_keys WHERE id = ?`).get(key.id) as { hash: string };
    expect(fila.hash).toHaveLength(64); // sha256 hex
    expect(fila.hash).not.toContain(clave.slice(-8)); // el secreto no queda en claro

    const v = await verificarClave(ctx, clave);
    expect(v.ok).toBe(true);
    expect(v.key?.nombre).toBe('cliente-prueba');
    expect(v.key?.scopes).toEqual(['negocio:ro', 'analisis']);

    // El uso se contabiliza.
    const trasUso = ctx.db.prepare(`SELECT usos, ultimo_uso FROM api_keys WHERE id = ?`).get(key.id) as any;
    expect(trasUso.usos).toBe(1);
    expect(trasUso.ultimo_uso).toBeTruthy();
  });

  it('rechaza claves con formato inválido, desconocidas o con secreto alterado', async () => {
    expect((await verificarClave(ctx, 'no-tiene-formato')).motivo).toMatch(/formato/i);
    expect((await verificarClave(ctx, 'mcp_deadbeef_xxxxxxxx')).motivo).toMatch(/no reconocida/i);

    const { clave, key } = await crearClave(ctx, { nombre: 'clave-alterada', scopes: ['*'] });
    const alterada = `${clave.slice(0, -1)}${clave.endsWith('A') ? 'B' : 'A'}`;
    expect((await verificarClave(ctx, alterada)).ok).toBe(false);
    expect(key.id).toBeTruthy();
  });

  it('revoca claves y detecta expiración', async () => {
    const { clave, key } = await crearClave(ctx, { nombre: 'para-revocar', scopes: ['negocio'] });
    await revocarClave(ctx, key.id);
    expect((await verificarClave(ctx, clave)).motivo).toMatch(/revocada/i);
    await expect(revocarClave(ctx, key.id)).rejects.toThrow(/ya estaba revocada/);

    const expirada = await crearClave(ctx, { nombre: 'para-expirar', scopes: ['negocio'], expiraDias: 1 });
    ctx.db.prepare(`UPDATE api_keys SET expira = ? WHERE id = ?`).run('2020-01-01T00:00:00.000Z', expirada.key.id);
    expect((await verificarClave(ctx, expirada.clave)).motivo).toMatch(/expirada/i);
  });

  it('acepta los scopes del módulo de facturación', async () => {
    const { key } = await crearClave(ctx, { nombre: 'facturador', scopes: ['facturacion', 'negocio:ro'] });
    expect(key.scopes).toEqual(['facturacion', 'negocio:ro']);
  });

  it('valida scopes, nombres duplicados y listado sin hashes', async () => {
    await expect(crearClave(ctx, { nombre: 'scopes-malos', scopes: ['rrhh'] })).rejects.toThrow(/Scopes inválidos/);
    await expect(crearClave(ctx, { nombre: 'sin-scopes', scopes: [] })).rejects.toThrow(/al menos un scope/);
    await crearClave(ctx, { nombre: 'duplicada', scopes: ['*'] });
    await expect(crearClave(ctx, { nombre: 'DUPLICADA', scopes: ['*'] })).rejects.toThrow(/Ya existe una clave/);

    const lista = await listarClaves(ctx);
    expect(lista.length).toBeGreaterThan(0);
    expect(lista.every((k) => !('hash' in k))).toBe(true);
    expect(await contarClavesActivas(ctx)).toBeGreaterThan(0);
  });
});

describe('política de permisos por scopes', () => {
  const tools = [
    { name: 'inventario_consultar', module: 'negocio', readOnly: true },
    { name: 'inventario_actualizar', module: 'negocio', readOnly: false },
    { name: 'dataset_analizar_columna', module: 'analisis', readOnly: true },
    { name: 'dataset_exportar_json', module: 'analisis', readOnly: false },
    { name: 'git_status', module: 'devops', readOnly: true },
    { name: 'mcp_suite_info', module: 'core', readOnly: true },
  ];

  it('acceso total con "*"', () => {
    const p = politicaDesdeScopes(['*']);
    expect(tools.every(p)).toBe(true);
  });

  it('módulo completo sin restricción', () => {
    const p = politicaDesdeScopes(['negocio']);
    expect(p(tools[0]!)).toBe(true);
    expect(p(tools[1]!)).toBe(true);
    expect(p(tools[2]!)).toBe(false); // otro módulo
    expect(p(tools[5]!)).toBe(true); // core siempre
  });

  it('módulo en solo lectura excluye herramientas destructivas', () => {
    const p = politicaDesdeScopes(['negocio:ro', 'analisis:ro']);
    expect(p(tools[0]!)).toBe(true);
    expect(p(tools[1]!)).toBe(false); // inventario_actualizar queda fuera
    expect(p(tools[2]!)).toBe(true);
    expect(p(tools[3]!)).toBe(false); // dataset_exportar_json escribe archivos
    expect(p(tools[4]!)).toBe(false);
  });

  it('describe los permisos para el usuario', () => {
    expect(describirScopes(['*'])).toEqual(['acceso total a todos los módulos']);
    expect(describirScopes(['negocio:ro', 'devops'])).toEqual(['negocio (solo lectura)', 'devops (completo)']);
  });
});

describe('limitador de tasa (token bucket)', () => {
  it('agota la ráfaga y repone con el tiempo', () => {
    const lim = new LimitadorTasas(60, 3); // 1 por segundo, ráfaga 3
    const t0 = 1_000_000;
    expect(lim.consumir('ana', t0).permitido).toBe(true);
    expect(lim.consumir('ana', t0).permitido).toBe(true);
    const tercera = lim.consumir('ana', t0);
    expect(tercera.permitido).toBe(true);
    expect(tercera.restantes).toBe(0);

    const cuarta = lim.consumir('ana', t0);
    expect(cuarta.permitido).toBe(false);
    expect(cuarta.retryAfterS).toBeGreaterThanOrEqual(1);

    // Tras 2.5 s hay ~2.5 fichas de nuevo.
    const despues = lim.consumir('ana', t0 + 2500);
    expect(despues.permitido).toBe(true);
    expect(lim.estado('ana', t0 + 2500).restantes).toBe(1);
  });

  it('aísla clientes entre sí', () => {
    const lim = new LimitadorTasas(60, 1);
    expect(lim.consumir('a', 0).permitido).toBe(true);
    expect(lim.consumir('a', 0).permitido).toBe(false);
    expect(lim.consumir('b', 0).permitido).toBe(true); // otra cubeta
  });
});

describe('métricas Prometheus', () => {
  it('renderiza contadores e histogramas con etiquetas y +Inf', () => {
    const m = new Metricas();
    m.inc('pruebas_total', { herramienta: 'x', resultado: 'ok' });
    m.inc('pruebas_total', { herramienta: 'x', resultado: 'ok' });
    m.inc('pruebas_total', { herramienta: 'y', resultado: 'error' });
    m.observe('pruebas_duracion_segundos', 0.03, { herramienta: 'x' });
    m.observe('pruebas_duracion_segundos', 3, { herramienta: 'x' });

    expect(m.obtener('pruebas_total', { herramienta: 'x', resultado: 'ok' })).toBe(2);
    const texto = m.render();
    expect(texto).toContain('# TYPE pruebas_total counter');
    expect(texto).toContain('pruebas_total{herramienta="x",resultado="ok"} 2');
    expect(texto).toContain('# TYPE pruebas_duracion_segundos histogram');
    expect(texto).toContain('pruebas_duracion_segundos_bucket{herramienta="x",le="0.025"} 0');
    expect(texto).toContain('pruebas_duracion_segundos_bucket{herramienta="x",le="+Inf"} 2');
    expect(texto).toContain('pruebas_duracion_segundos_count{herramienta="x"} 2');
    expect(texto).toContain('mcp_suite_uptime_segundos');
  });
});

describe('auditoría NDJSON', () => {
  it('registra, filtra y resume eventos sin reescribir el archivo', async () => {
    const dir = await fs.mkdtemp(path.join(dataDir, 'audit-'));
    const log = new Auditoria(path.join(dir, 'auditoria.ndjson'));

    await log.registrar({ tipo: 'tool_destructiva', cliente: 'ana', herramienta: 'inventario_actualizar', ok: true });
    await log.registrar({ tipo: 'auth_fallo', cliente: 'desconocido', ip: '10.0.0.5', detalle: 'clave no reconocida' });
    await log.registrar({ tipo: 'tool_destructiva', cliente: 'ana', herramienta: 'pedido_crear', ok: false, error: 'Stock insuficiente' });

    const todas = await log.leer();
    expect(todas).toHaveLength(3);

    const destructivas = await log.leer({ tipo: 'tool_destructiva' });
    expect(destructivas).toHaveLength(2);

    const deAna = await log.leer({ cliente: 'ana', herramienta: 'pedido_crear' });
    expect(deAna).toHaveLength(1);
    expect(deAna[0]!.ok).toBe(false);

    expect(await log.resumen()).toEqual({ tool_destructiva: 2, auth_fallo: 1 });

    // Línea por evento, ilegible no rompe la lectura.
    const contenido = await fs.readFile(log.ruta, 'utf8');
    expect(contenido.trim().split('\n')).toHaveLength(3);
    await fs.appendFile(log.ruta, '{linea corrupta\n', 'utf8');
    expect(await log.leer()).toHaveLength(3);
  });

  it('trunca resúmenes de argumentos largos', () => {
    const resumen = resumirArgs({ texto: 'x'.repeat(2000) });
    expect(resumen.length).toBeLessThanOrEqual(601);
    expect(resumen.endsWith('…')).toBe(true);
  });
});
