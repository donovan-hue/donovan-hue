/**
 * Pruebas de MULTI-EMPRESA (fase 5A): un servidor, varios negocios.
 *
 * Lo que se demuestra aquí, con datos reales:
 *   · dos empresas con el MISMO SKU no chocan ni se ven entre sí;
 *   · los pedidos descuentan stock en la empresa correcta;
 *   · las plantillas también están aisladas;
 *   · una clave de API atada a una empresa no puede mirar a otra;
 *   · la factura CFDI toma el emisor y la serie de la empresa activa;
 *   · la comparativa junta a todas sin mezclar cifras;
 *   · archivar oculta sin borrar.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { buildServer } from '../src/server';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';
import type { IdentidadCliente } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

/** Cliente conectado como si fuera una clave atada a una empresa. */
async function clienteAtadoA(empresaId: string): Promise<{ client: Client; cerrar: () => Promise<void> }> {
  const identidad: IdentidadCliente = { id: 'k-demo', nombre: 'clave-cliente', scopes: ['*'], origen: 'api_key', empresaId };
  const ctxAtado: ServerCtx = { ...ctx, apiKey: identidad };
  const { server } = buildServer(ctxAtado);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  const c = new Client({ name: 'test-cliente-atado', version: '1.0.0' });
  await Promise.all([c.connect(tCliente), server.connect(tServidor)]);
  return { client: c, cerrar: async () => { await c.close(); await server.close(); } };
}

const CSV_TORNILLO = [
  'sku,nombre,categoria,precio,costo,stock,stock_minimo,unidad,proveedor',
  'TOR-001,Tornillo 1/4 acero,Ferretería,2.5,1.2,500,50,pieza,Aceros MX',
  'TOR-002,Tornillo 3/8 acero,Ferretería,3.5,1.8,300,40,pieza,Aceros MX',
].join('\n');

const CSV_PAN = [
  'sku,nombre,precio,stock',
  'TOR-001,Concha de chocolate,18,60',
  'PAN-002,Cuernito,14,80',
].join('\n');

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-empresas-');
  ctx = await contextoDePrueba(dataDir, { ivaPct: 16 }, { rpm: 6000, burst: 1000 });
  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-empresas', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
});

describe('MULTI-EMPRESA — espacios separados', () => {
  it('arranca con una empresa por defecto y se puede crear otra con datos fiscales', async () => {
    const inicio = (await llamar('empresas_listar')).json();
    expect(inicio.total).toBe(1);
    expect(inicio.activa.id).toBe('EMP-principal');

    const creada = (await llamar('empresa_crear', {
      nombre: 'Ferretería El Tornillo',
      rfc: 'FER950101AB1',
      regimen: '626',
      cp: '44100',
      series: 'FERR',
    })).json();
    expect(creada.id).toMatch(/^EMP-[0-9a-f]{6}$/);
    expect(creada.series).toBe('FERR');

    // RFC con forma inválida: se rechaza con explicación, no se guarda basura.
    const malo = await llamar('empresa_crear', { nombre: 'Otra', rfc: '123' });
    expect(malo.isError).toBe(true);
    expect(malo.texto).toContain('RFC');
  });

  it('el MISMO SKU vive en dos empresas sin chocar, y los pedidos no se mezclan', async () => {
    const empresa = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Ferretería El Tornillo');
    await llamar('empresa_actual', { empresa: empresa.id });
    const cargado = (await llamar('inventario_importar', { contenido_csv: CSV_TORNILLO })).json();
    expect(cargado.cargados).toBe(2);
    expect(cargado.empresa.id).toBe(empresa.id);

    // La empresa por defecto NO ve esos productos…
    await llamar('empresa_actual', { empresa: 'EMP-principal' });
    const enPrincipal = (await llamar('inventario_consultar', { sku: 'TOR-001' })).isError;
    expect(enPrincipal).toBe(true);

    // …y la suya sí.
    await llamar('empresa_actual', { empresa: empresa.id });
    const suyo = (await llamar('inventario_consultar', { sku: 'TOR-001' })).json();
    expect(suyo.empresa.nombre).toBe('Ferretería El Tornillo');
    expect(suyo.resultados[0].nombre).toBe('Tornillo 1/4 acero');

    // Pedido en la ferretería: descuenta SU stock.
    const pedido = (await llamar('pedido_crear', { cliente: 'Constructora del Valle', items: [{ sku: 'TOR-001', cantidad: 100 }] })).json();
    expect(pedido.empresa.nombre).toBe('Ferretería El Tornillo');
    expect(pedido.subtotal).toBe(250);
    expect(pedido.total).toBe(290); // 250 + 16 %
  });
});

describe('MULTI-EMPRESA — cada negocio con su catálogo', () => {
  it('importa el catálogo de la panadería, con el mismo SKU que ya existía en otra empresa', async () => {
    await llamar('empresa_crear', { nombre: 'Panadería La Espiga', cp: '44100' });
    const pan = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Panadería La Espiga');
    await llamar('empresa_actual', { empresa: pan.id });

    const sim = (await llamar('inventario_importar', { contenido_csv: CSV_PAN, dry_run: true })).json();
    expect(sim.se_escribirian).toBe(2);
    expect(sim.mensaje).toContain('Nada se escribió');

    const real = (await llamar('inventario_importar', { contenido_csv: CSV_PAN })).json();
    expect(real.cargados).toBe(2);
    const tor = (await llamar('inventario_consultar', { sku: 'TOR-001' })).json();
    expect(tor.resultados[0].nombre).toBe('Concha de chocolate'); // su TOR-001 es un pan, no un tornillo
    expect(tor.resultados[0].stock).toBe(60);

    // Reimportar en modo agregar no duplica ni pisa.
    const repetido = (await llamar('inventario_importar', { contenido_csv: CSV_PAN })).json();
    expect(repetido.cargados).toBe(0);
    expect(repetido.omitidos_por_ya_existir).toBe(2);
  });

  it('las plantillas también están aisladas por empresa', async () => {
    const pan = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Panadería La Espiga');
    await llamar('empresa_actual', { empresa: pan.id });
    await llamar('plantilla_guardar', {
      nombre: 'Recibo de pan',
      contenido: 'Gracias por su compra en {{negocio}}, {{cliente}}. Total: {{total}}',
    });
    const suyas = (await llamar('plantilla_listar')).json();
    expect(suyas.plantillas.map((p: { nombre: string }) => p.nombre)).toContain('Recibo de pan');

    await llamar('empresa_actual', { empresa: 'EMP-principal' });
    const ajenas = (await llamar('plantilla_listar')).json();
    expect(ajenas.plantillas.map((p: { nombre: string }) => p.nombre)).not.toContain('Recibo de pan');
  });
});

describe('MULTI-EMPRESA — una clave atada no ve otras empresas', () => {
  it('la clave del cliente solo ve su empresa, no puede cambiar ni comparar', async () => {
    const tornillo = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Ferretería El Tornillo');
    const atado = await clienteAtadoA(tornillo.id);
    try {
      const suyos = await atado.client.callTool({ name: 'inventario_consultar', arguments: {} });
      const datos = JSON.parse((suyos.content as { text: string }[])[0]!.text);
      expect(datos.empresa.nombre).toBe('Ferretería El Tornillo');
      expect(datos.resultados.some((r: { sku: string }) => r.sku === 'PAN-002')).toBe(false);

      const cambio = await atado.client.callTool({ name: 'empresa_actual', arguments: { empresa: 'EMP-principal' } });
      expect(cambio.isError).toBe(true);
      expect((cambio.content as { text: string }[])[0]!.text).toContain('atada a la empresa');

      const comparar = await atado.client.callTool({ name: 'empresas_comparar', arguments: {} });
      expect(comparar.isError).toBe(true);
      expect((comparar.content as { text: string }[])[0]!.text).toContain('una sola empresa');
    } finally {
      await atado.cerrar();
    }
  });
});

describe('MULTI-EMPRESA — folios por empresa', () => {
  it('cada empresa numera sus pedidos desde P-0001, aunque el folio se repita', async () => {
    await llamar('empresa_crear', { nombre: 'Taller Mecánico Sur', rfc: 'TMS180220Q14', regimen: '612', cp: '45601', series: 'TAL' });
    const taller = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Taller Mecánico Sur');

    // «Mi negocio» estrena su catálogo y su primer pedido: P-0001.
    await llamar('empresa_actual', { empresa: 'EMP-principal' });
    await llamar('inventario_importar', { contenido_csv: 'sku,nombre,precio,stock\nPRIN-1,Producto principal,50,10' });
    const enPrincipal = (await llamar('pedido_crear', { cliente: 'Cliente principal', items: [{ sku: 'PRIN-1', cantidad: 2 }] })).json();
    expect(enPrincipal.folio).toBe('P-0001');

    // El taller arranca SU numeración en P-0001, sin chocar con el de la otra.
    await llamar('empresa_actual', { empresa: taller.id });
    await llamar('inventario_importar', { contenido_csv: 'sku,nombre,precio,stock\nTAL-1,Aceite 5W30,320,12' });
    const primero = (await llamar('pedido_crear', { cliente: 'Cliente del taller', items: [{ sku: 'TAL-1', cantidad: 2 }] })).json();
    expect(primero.folio).toBe('P-0001');
    const segundo = (await llamar('pedido_crear', { cliente: 'Otro cliente', items: [{ sku: 'TAL-1', cantidad: 1 }] })).json();
    expect(segundo.folio).toBe('P-0002');

    // El folio repetido convive: cada empresa ve el suyo con SUS conceptos.
    await llamar('empresa_actual', { empresa: 'EMP-principal' });
    const mio = (await llamar('pedido_consultar', { folio: 'P-0001' })).json();
    expect(mio.pedidos).toHaveLength(1);
    expect(mio.pedidos[0].total).toBe(116); // 2 × $50 + 16 % de IVA: el MÍO
    expect(mio.pedidos.some((p: { folio: string }) => p.folio === 'P-0002')).toBe(false);

    await llamar('empresa_actual', { empresa: taller.id });
    const suyo = (await llamar('pedido_consultar', { folio: 'P-0001' })).json();
    expect(suyo.pedidos[0].total).toBe(742.4); // 2 × $320 + IVA: el SUYO, mismo folio
    expect(suyo.pedidos[0].cliente).toBe('Cliente del taller');

    // La factura del mismo folio sale con la serie de cada empresa.
    const borrador = (await llamar('cfdi_desde_pedido', {
      folio_pedido: 'P-0001',
      receptor: { rfc: 'CCH190903K31', nombre: 'Coworking Chapultepec', regimen_fiscal: '601', codigo_postal: '44100', uso_cfdi: 'G01' },
      incluir_xml: false,
    })).json();
    expect(borrador.comprobante.folio).toBe('0001');
    expect(borrador.comprobante.serie).toBe('TAL'); // su serie, no la de la ferretería
    expect(borrador.comprobante.emisor.rfc).toBe('TMS180220Q14');
    expect(borrador.comprobante.emisor.nombre).toBe('Taller Mecánico Sur');
    await llamar('empresa_actual', { empresa: 'EMP-principal' }); // dejar como estaba
  });
});

describe('MULTI-EMPRESA — facturación, comparativa y archivo', () => {
  it('el borrador CFDI toma emisor y serie de la empresa activa', async () => {
    const tornillo = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Ferretería El Tornillo');
    await llamar('empresa_actual', { empresa: tornillo.id });
    const pedido = (await llamar('pedido_consultar', { cliente: 'Constructora' })).json().pedidos[0];

    // Sin CFDI_EMISOR_* configurado: el emisor sale de la empresa.
    const borrador = (await llamar('cfdi_desde_pedido', {
      folio_pedido: pedido.folio,
      receptor: { rfc: 'CCH190903K31', nombre: 'Coworking Chapultepec', regimen_fiscal: '601', codigo_postal: '44100', uso_cfdi: 'G01' },
      forma_pago: '01',
      metodo_pago: 'PUE',
      incluir_xml: false,
    })).json();
    // El emisor y la serie salen de la empresa activa, sin configurar CFDI_EMISOR_*.
    expect(borrador.comprobante.emisor.rfc).toBe('FER950101AB1');
    expect(borrador.comprobante.serie).toBe('FERR');
    expect(borrador.comprobante.emisor.nombre).toBe('Ferretería El Tornillo');
    expect(borrador.listo_para_timbrar).toBe(true);
  });

  it('la comparativa junta a las empresas sin mezclar cifras y marca a la mejor', async () => {
    const c = (await llamar('empresas_comparar')).json();
    const nombres = c.empresas.map((e: { empresa: { nombre: string } }) => e.empresa.nombre);
    expect(nombres).toContain('Ferretería El Tornillo');
    expect(nombres).toContain('Panadería La Espiga');
    const tornillo = c.empresas.find((e: { empresa: { nombre: string } }) => e.empresa.nombre === 'Ferretería El Tornillo');
    const pan = c.empresas.find((e: { empresa: { nombre: string } }) => e.empresa.nombre === 'Panadería La Espiga');
    expect(tornillo.pedidos.ventas_totales).toBe(290); // 250 + IVA, SUS ventas
    expect(pan.pedidos.ventas_totales).toBe(0);
    // La comparativa señala a quien más vendió, calculado sobre los mismos datos.
    const maximo = Math.max(...c.empresas.map((e: { pedidos: { ventas_totales: number } }) => e.pedidos.ventas_totales));
    const quien = c.empresas.find((e: { pedidos: { ventas_totales: number } }) => e.pedidos.ventas_totales === maximo);
    expect(c.mejor_en.ventas_totales).toBe(quien.empresa.nombre);
  });

  it('archivar la empresa activa se bloquea; archivar otra la oculta sin borrar datos', async () => {
    const activa = (await llamar('empresa_actual')).json().activa;
    const bloqueado = await llamar('empresa_editar', { empresa: activa.id, archivar: true });
    expect(bloqueado.isError).toBe(true);
    expect(bloqueado.texto).toContain('empresa activa');

    const pan = (await llamar('empresas_listar')).json().empresas.find((e: { nombre: string }) => e.nombre === 'Panadería La Espiga');
    const archivada = (await llamar('empresa_editar', { empresa: pan.id, archivar: true })).json();
    expect(archivada.archivada).toBe(true);

    const visibles = (await llamar('empresas_listar')).json();
    expect(visibles.empresas.some((e: { id: string }) => e.id === pan.id)).toBe(false);
    const conArchivadas = (await llamar('empresas_listar', { incluir_archivadas: true })).json();
    expect(conArchivadas.empresas.some((e: { id: string }) => e.id === pan.id)).toBe(true);
    // Sus datos siguen ahí (la comparativa puede incluirla si se pide por id).
    const resumen = (await llamar('empresa_resumen', { empresa: pan.id })).json();
    expect(resumen.inventario.productos).toBe(2);

    // La empresa activa vuelve a «Mi negocio» para no ensuciar el resto.
    await llamar('empresa_actual', { empresa: 'EMP-principal' });
  });
});

describe('MULTI-EMPRESA — migración de una base anterior', () => {
  it('abrirDb() sobre una base vieja de verdad migra sin reventar (índices incluidos)', async () => {
    const { abrirDb } = await import('../src/db/index');
    const ruta = path.join(dataDir, 'legacy.db');

    // 1) Base recién creada: la degradamos al esquema de ANTES (sin empresa_id).
    const moderna = abrirDb(ruta);
    moderna.prepare(`INSERT INTO inventario (sku, nombre, precio, stock, actualizado_en) VALUES ('L-1', 'Viejo', 5, 5, '2026-01-01T00:00:00.000Z')`).run();
    moderna.close();

    const legacy = new (await import('better-sqlite3')).default(ruta);
    legacy.exec(`
      DROP INDEX IF EXISTS idx_inventario_empresa;
      DROP INDEX IF EXISTS idx_pedidos_empresa;
      DROP INDEX IF EXISTS idx_plantillas_empresa;
      ALTER TABLE api_keys DROP COLUMN empresa_id;
      DROP TABLE pedido_items;
      DROP TABLE pedido_historial;
      DROP TABLE pedidos;
      CREATE TABLE pedidos (
        folio TEXT PRIMARY KEY, cliente TEXT NOT NULL, estado TEXT NOT NULL, subtotal REAL NOT NULL DEFAULT 0,
        iva REAL NOT NULL DEFAULT 0, total REAL NOT NULL DEFAULT 0, notas TEXT NOT NULL DEFAULT '',
        creado_en TEXT NOT NULL, actualizado_en TEXT NOT NULL);
      CREATE TABLE pedido_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT, folio TEXT NOT NULL REFERENCES pedidos(folio) ON DELETE CASCADE,
        sku TEXT NOT NULL, descripcion TEXT NOT NULL, cantidad INTEGER NOT NULL, precio_unitario REAL NOT NULL, importe REAL NOT NULL);
      CREATE TABLE pedido_historial (
        id INTEGER PRIMARY KEY AUTOINCREMENT, folio TEXT NOT NULL REFERENCES pedidos(folio) ON DELETE CASCADE,
        estado TEXT NOT NULL, fecha TEXT NOT NULL, nota TEXT);
      INSERT INTO pedidos (folio, cliente, estado, subtotal, iva, total, creado_en, actualizado_en)
        VALUES ('P-0099', 'Cliente de antes', 'borrador', 100, 16, 116, '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z');
      INSERT INTO pedido_items (folio, sku, descripcion, cantidad, precio_unitario, importe)
        VALUES ('P-0099', 'L-1', 'Viejo', 1, 100, 100);
      INSERT INTO pedido_historial (folio, estado, fecha, nota) VALUES ('P-0099', 'borrador', '2026-01-01T00:00:00.000Z', NULL);
      DROP TABLE inventario;
      CREATE TABLE inventario (
        sku TEXT PRIMARY KEY, nombre TEXT NOT NULL, categoria TEXT NOT NULL DEFAULT '', precio REAL NOT NULL DEFAULT 0,
        costo REAL NOT NULL DEFAULT 0, stock INTEGER NOT NULL DEFAULT 0, stock_minimo INTEGER NOT NULL DEFAULT 0,
        unidad TEXT NOT NULL DEFAULT 'pieza', proveedor TEXT NOT NULL DEFAULT '', actualizado_en TEXT NOT NULL);
      INSERT INTO inventario (sku, nombre, precio, stock, actualizado_en) VALUES ('L-1', 'Viejo', 5, 5, '2026-01-01T00:00:00.000Z');
      DROP TABLE plantillas;
      CREATE TABLE plantillas (nombre TEXT PRIMARY KEY, descripcion TEXT NOT NULL DEFAULT '', variables TEXT NOT NULL, contenido TEXT NOT NULL, creado_en TEXT NOT NULL);
      INSERT INTO plantillas (nombre, descripcion, variables, contenido, creado_en) VALUES ('P vieja', '', '[]', 'hola {{x}}', '2026-01-01T00:00:00.000Z');
    `);
    legacy.close();

    // 2) El arranque real debe migrarla (antes fallaba: índice sobre columna inexistente).
    const migrada = abrirDb(ruta);
    const fila = migrada.prepare(`SELECT empresa_id, sku FROM inventario`).get() as { empresa_id: string; sku: string };
    expect(fila).toEqual({ empresa_id: 'EMP-principal', sku: 'L-1' });
    const plantilla = migrada.prepare(`SELECT empresa_id, nombre FROM plantillas`).get() as { empresa_id: string; nombre: string };
    expect(plantilla.empresa_id).toBe('EMP-principal');
    const indices = migrada.prepare(`SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%_empresa'`).all() as { name: string }[];
    expect(indices.map((i) => i.name).sort()).toEqual([
      'idx_hist_empresa', 'idx_inventario_empresa', 'idx_items_empresa', 'idx_pedidos_empresa', 'idx_plantillas_empresa',
    ]);
    // Y los pedidos de antes siguen ahí, con sus conceptos, ya numerables por empresa.
    const pedido = migrada.prepare(`SELECT folio, empresa_id, cliente FROM pedidos`).get() as { folio: string; empresa_id: string; cliente: string };
    expect(pedido).toEqual({ folio: 'P-0099', empresa_id: 'EMP-principal', cliente: 'Cliente de antes' });
    const item = migrada.prepare(`SELECT empresa_id, sku FROM pedido_items`).get() as { empresa_id: string; sku: string };
    expect(item).toEqual({ empresa_id: 'EMP-principal', sku: 'L-1' });
    migrada.close();
  });


  it('una base sin multi-empresa se migra sin perder nada y todo queda en la empresa por defecto', async () => {
    const { abrirDb, migrarMultiEmpresa } = await import('../src/db/index');
    const ruta = path.join(dataDir, 'vieja.db');
    const vieja = abrirDb(ruta);
    // Simula la base de antes: inventario sin columna empresa_id.
    vieja.exec('DROP TABLE inventario');
    vieja.exec(`CREATE TABLE inventario (
      sku TEXT PRIMARY KEY, nombre TEXT NOT NULL, categoria TEXT NOT NULL DEFAULT '', precio REAL NOT NULL DEFAULT 0,
      costo REAL NOT NULL DEFAULT 0, stock INTEGER NOT NULL DEFAULT 0, stock_minimo INTEGER NOT NULL DEFAULT 0,
      unidad TEXT NOT NULL DEFAULT 'pieza', proveedor TEXT NOT NULL DEFAULT '', actualizado_en TEXT NOT NULL)`);
    vieja.prepare(
      `INSERT INTO inventario (sku, nombre, precio, stock, actualizado_en) VALUES ('X-1', 'Producto viejo', 10, 5, '2026-01-01T00:00:00.000Z')`,
    ).run();

    const r = migrarMultiEmpresa(vieja);
    expect(r.migrada).toBe(true);
    const fila = vieja.prepare(`SELECT empresa_id, sku, nombre FROM inventario`).get() as { empresa_id: string; sku: string; nombre: string };
    expect(fila).toEqual({ empresa_id: 'EMP-principal', sku: 'X-1', nombre: 'Producto viejo' });
    // Y ahora sí aguanta el mismo SKU en dos empresas.
    vieja.prepare(
      `INSERT INTO inventario (empresa_id, sku, nombre, precio, stock, actualizado_en) VALUES ('EMP-otra', 'X-1', 'Mismo SKU, otra empresa', 12, 3, '2026-01-02T00:00:00.000Z')`,
    ).run();
    const total = (vieja.prepare(`SELECT COUNT(*) AS n FROM inventario`).get() as { n: number }).n;
    expect(total).toBe(2);
    vieja.close();
  });
});
