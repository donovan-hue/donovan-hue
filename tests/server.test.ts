import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
import { ensureSeedData } from '../src/seed';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-test-');
  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [projectRoot] }, { rpm: 6000, burst: 1000 });
  await ensureSeedData(ctx);

  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client', version: '1.0.0' });
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

describe('servidor MCP (extremo a extremo en memoria)', () => {
  it('expone el catálogo completo de herramientas', async () => {
    const { tools } = await client.listTools();
    const nombres = tools.map((t) => t.name);
    expect(tools.length).toBeGreaterThanOrEqual(35);
    for (const esperado of [
      'mcp_suite_info',
      'auditoria_consultar',
      'cfdi_generar',
      'cfdi_desde_pedido',
      'catalogo_sat_consultar',
      'inventario_consultar',
      'pedido_crear',
      'cotizacion_generar',
      'plantilla_renderizar',
      'dataset_perfilar',
      'datos_estadisticas',
      'git_status',
      'log_analizar',
    ]) {
      expect(nombres).toContain(esperado);
    }
    for (const t of tools) {
      expect(t.description?.length ?? 0).toBeGreaterThan(20);
      expect(t.inputSchema?.type).toBe('object');
    }
  });

  it('expone recursos y prompts', async () => {
    const recursos = await client.listResources();
    expect(recursos.resources.map((r) => r.uri)).toContain('mcp-suite-mx://config/actual');
    const prompts = await client.listPrompts();
    expect(prompts.prompts.map((p) => p.name)).toContain('resumen_semanal_negocio');
  });

  it('mcp_suite_info describe el propio servidor', async () => {
    const info = (await llamar('mcp_suite_info', { detallado: false })).json();
    expect(info.version).toBe('0.1.0');
    expect(info.modulos).toEqual(expect.arrayContaining(['negocio', 'facturacion', 'documentos', 'analisis', 'devops']));
    expect(info.total_herramientas_disponibles).toBeGreaterThanOrEqual(35);
    expect(info.cliente).toBeNull();
  });

  it('consulta inventario y detecta artículos bajo mínimo (datos semilla)', async () => {
    const inv = (await llamar('inventario_consultar', { bajo_minimo: true })).json();
    expect(inv.total_resultados).toBeGreaterThan(0);
    expect(inv.resultados.every((i: any) => i.bajo_minimo === true)).toBe(true);
  });

  it('calcula cotización con IVA mexicano', async () => {
    const c = (
      await llamar('cotizacion_generar', {
        cliente: 'ACME SA de CV',
        items: [{ descripcion: 'Servicio', cantidad: 2, precio_unitario: 5000 }],
        descuento_pct: 10,
      })
    ).json();
    expect(c.subtotal).toBe(10000);
    expect(c.baseGravable).toBe(9000);
    expect(c.iva).toBe(1440);
    expect(c.total).toBe(10440);
  });

  it('crea un pedido en una transacción, descuenta inventario y bloquea stock insuficiente', async () => {
    const antes = (await llamar('inventario_consultar', { sku: 'TEC-001' })).json().resultados[0].stock;
    const creado = (await llamar('pedido_crear', { cliente: 'Cliente Prueba', items: [{ sku: 'TEC-001', cantidad: 2 }] })).json();
    expect(creado.folio).toMatch(/^P-\d{4}$/);
    expect(creado.estado).toBe('borrador');
    expect(creado.historial).toHaveLength(1);
    const despues = (await llamar('inventario_consultar', { sku: 'TEC-001' })).json().resultados[0].stock;
    expect(despues).toBe(antes - 2);

    const insuficiente = await llamar('pedido_crear', { cliente: 'Cliente Prueba', items: [{ sku: 'TEC-001', cantidad: 9999 }] });
    expect(insuficiente.isError).toBe(true);
    expect(insuficiente.texto).toMatch(/Stock insuficiente/);

    // La transacción fallida no debe haber consumido folio ni stock.
    const stockTrasFallo = (await llamar('inventario_consultar', { sku: 'TEC-001' })).json().resultados[0].stock;
    expect(stockTrasFallo).toBe(despues);

    const transicion = await llamar('pedido_actualizar_estado', { folio: creado.folio, nuevo_estado: 'confirmado' });
    expect(transicion.json().nuevo).toBe('confirmado');
    const ilegal = await llamar('pedido_actualizar_estado', { folio: creado.folio, nuevo_estado: 'enviado' });
    expect(ilegal.isError).toBe(true);
    expect(ilegal.texto).toMatch(/Transición inválida/);
  });

  it('genera un CFDI 4.0 desde un pedido existente y guarda el XML', async () => {
    const r = await llamar('cfdi_desde_pedido', {
      folio_pedido: 'P-0002',
      emisor: { rfc: 'DJA240118H72', nombre: 'Distribuidora Jalisco SA de CV', regimen_fiscal: '601', codigo_postal: '44100' },
      receptor: { rfc: 'CCH190903K31', nombre: 'Coworking Chapultepec SA de CV', regimen_fiscal: '601', codigo_postal: '44100', uso_cfdi: 'G01' },
      forma_pago: '03',
      guardar_xml: true,
    });
    const cfdi = r.json();
    expect(cfdi.listo_para_timbrar).toBe(true);
    expect(cfdi.pedido.folio).toBe('P-0002');
    expect(cfdi.totales.total).toBe(cfdi.pedido.total_pedido);
    expect(cfdi.xml).toContain('Version="4.0"');
    expect(cfdi.xml).toContain('FormaPago="03"');
    expect(cfdi.xml_guardado_en).toContain('facturacion');
    expect(await fs.stat(cfdi.xml_guardado_en).catch(() => null)).toBeTruthy();

    const catalogo = (await llamar('catalogo_sat_consultar', { tipo: 'forma_pago', query: 'transferencia' })).json();
    expect(catalogo.resultados[0].clave).toBe('03');

    const retenciones = (await llamar('cfdi_retenciones_calcular', { monto: 10000 })).json();
    expect(retenciones.retencion_isr).toBe(125);
    expect(retenciones.retencion_iva).toBe(1066.67);
    expect(retenciones.total_a_pagar).toBe(10408.33);
  });

  it('la bitácora de auditoría registra las operaciones destructivas', async () => {
    await llamar('inventario_actualizar', { sku: 'LAM-006', tipo: 'entrada', cantidad: 5, motivo: 'Prueba de auditoría' });
    const audit = (await llamar('auditoria_consultar', { tipo: 'tool_destructiva', herramienta: 'inventario_actualizar' })).json();
    expect(audit.total_devuelto).toBeGreaterThan(0);
    const evento = audit.eventos[0];
    expect(evento.herramienta).toBe('inventario_actualizar');
    expect(evento.ok).toBe(true);
    expect(evento.args_resumen).toContain('Prueba de auditoría');
    expect(audit.resumen_por_tipo.tool_destructiva).toBeGreaterThanOrEqual(1);

    // También quedó rastro de la llamada fallida al crear pedido sin stock.
    const fallos = (await llamar('auditoria_consultar', { herramienta: 'pedido_crear' })).json();
    expect(fallos.eventos.some((e: any) => e.ok === false)).toBe(true);
  });

  it('las métricas en formato Prometheus acumulan llamadas por herramienta', async () => {
    const texto = ctx.metrics.render();
    expect(texto).toContain('# TYPE mcp_suite_tools_llamadas_total counter');
    expect(texto).toContain('herramienta="inventario_consultar"');
    expect(texto).toContain('mcp_suite_tool_duracion_segundos_count');
    expect(ctx.metrics.obtener('mcp_suite_tools_llamadas_total', { herramienta: 'inventario_consultar', modulo: 'negocio', resultado: 'ok' })).toBeGreaterThan(0);
  });

  it('perfila un dataset CSV real del proyecto', async () => {
    const ruta = path.join('datasets', 'ventas_mensuales.csv');
    await fs.mkdir(path.join(dataDir, 'datasets'), { recursive: true });
    await fs.copyFile(path.join(projectRoot, 'data', ruta), path.join(dataDir, ruta));
    const perfil = (await llamar('dataset_perfilar', { ruta })).json();
    expect(perfil.filas).toBe(48);
    expect(perfil.perfilar?.columnas ?? perfil.nombres_columnas).toContain('venta_neta');
    expect(perfil.perfil_columnas.find((c: any) => c.columna === 'venta_neta').tipo).toBe('numerica');
  });

  it('agrupa ventas por sucursal y suma venta_neta', async () => {
    const agg = (
      await llamar('dataset_agrupar', {
        ruta: path.join('datasets', 'ventas_mensuales.csv'),
        columna_grupo: 'sucursal',
        columna_valor: 'venta_neta',
        agregacion: 'suma',
      })
    ).json();
    expect(agg.grupos).toBe(2);
    expect(agg.resultados[0].valor).toBeGreaterThan(agg.resultados[1].valor);
  });

  it('protege contra rutas fuera del área permitida', async () => {
    const r = await llamar('documento_leer', { ruta: '../../../etc/passwd' });
    expect(r.isError).toBe(true);
    expect(r.texto).toMatch(/fuera del área permitida/);
  });

  it('guarda y renderiza una plantilla persistida en SQLite', async () => {
    await llamar('plantilla_guardar', {
      nombre: 'prueba_entrega',
      contenido: 'Hola {{cliente}}: tu pedido {{folio}} llega el {{fecha}}.',
      descripcion: 'Prueba',
    });
    const faltante = (await llamar('plantilla_renderizar', { nombre: 'prueba_entrega', variables: { cliente: 'ACME' } })).json();
    expect(faltante.variables_faltantes).toEqual(['folio', 'fecha']);
    expect(faltante.documento).toContain('Hola ACME');

    // Reemplazo idempotente de la misma plantilla.
    const reguardada = (await llamar('plantilla_guardar', {
      nombre: 'prueba_entrega',
      contenido: 'Hola {{cliente}}, gracias por tu compra.',
    })).json();
    expect(reguardada.reemplazada).toBe(true);
  });

  it('analiza estadísticas, correlación y serie de tiempo', async () => {
    const stats = (await llamar('datos_estadisticas', { valores: [10, 20, 30, 40], etiquetas: ['T1', 'T2', 'T3', 'T4'] })).json();
    expect(stats.media).toBe(25);
    expect(stats.tendencia.pendiente).toBe(10);

    const corr = (await llamar('datos_correlacion', { valores_a: [1, 2, 3], valores_b: [2, 4, 6] })).json();
    expect(corr.pearson_r).toBe(1);
    expect(corr.interpretacion).toMatch(/positiva/);

    const serie = (
      await llamar('datos_serie_tiempo', { valores: [100, 110, 121, 133.1], periodos_por_anio: 12, periodos_pronostico: 2 })
    ).json();
    expect(serie.pronosticos).toHaveLength(2);
    expect(serie.cagr_pct).toBeGreaterThan(0);
  });

  it('analiza el log de ejemplo y agrupa errores repetidos', async () => {
    const rutaRel = path.join('logs', 'api_produccion.log');
    await fs.mkdir(path.join(dataDir, 'logs'), { recursive: true });
    await fs.copyFile(path.join(projectRoot, 'data', rutaRel), path.join(dataDir, rutaRel));
    const a = (await llamar('log_analizar', { ruta: rutaRel, patron: 'timeout' })).json();
    expect(a.lineas_analizadas).toBe(180);
    expect(a.niveles.ERROR).toBeGreaterThan(50);
    expect(a.top_errores.length).toBeGreaterThan(0);
    expect(a.coincidencias_patron).toBeGreaterThan(0);
    expect(a.rango_fechas?.desde).toMatch(/^2026-10-08/);
  });

  it('no permite revisar repos fuera de ALLOWED_GIT_ROOTS', async () => {
    const r = await llamar('git_status', { repo: '/etc' });
    expect(r.isError).toBe(true);
    expect(r.texto).toMatch(/fuera de las rutas permitidas/);
  });

  it('devuelve error claro cuando la herramienta falla', async () => {
    const r = await llamar('inventario_consultar', { sku: 'NO-EXISTE' });
    expect(r.isError).toBe(true);
    expect(r.texto).toMatch(/No existe un producto con SKU/);
  });
});
