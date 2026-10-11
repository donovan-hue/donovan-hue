/**
 * Pruebas del módulo «taller» (fase 4A): GEMELO + RESCATE.
 * Usan el propio proyecto como sujeto real y un proyecto abandonado sintético.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import { politicaDesdeScopes } from '../src/auth/politica';
import { validarScopes } from '../src/auth/keys';
import type { ServerCtx } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let abandonado: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

async function crearProyectoAbandonado(raiz: string): Promise<void> {
  const escribir = async (rel: string, contenido: string) => {
    const absoluto = path.join(raiz, rel);
    await fs.mkdir(path.dirname(absoluto), { recursive: true });
    await fs.writeFile(absoluto, contenido, 'utf8');
  };
  await escribir('package.json', JSON.stringify({ name: 'proyecto-a-medias', version: '0.1.0', main: 'src/index.js', scripts: { start: 'node src/index.js' } }));
  await escribir('README.md', '# Proyecto a medias\n\n- `npm run build` no existe todavía.\n\nEstado: 99 pruebas en total.\n\nVer `src/api/ventas.js` para dudas.\n');
  await escribir('src/index.js', "const { rutas } = require('./rutas/pedidos');\n// TODO: conectar el módulo de reportes\nmodule.exports = { rutas };\n");
  await escribir('src/rutas/pedidos.js', "function montar(app) {\n  app.get('/api/pedidos', (req, res) => res.json([]));\n}\nmodule.exports = { montar };\n");
  await escribir('src/rutas/reportes.js', "// FIXME: nunca se conectó al servidor principal\nfunction montar(app) {\n  app.get('/api/reportes', (req, res) => res.json([]));\n  app.post('/api/reportes/exportar', (req, res) => res.json({}));\n}\nmodule.exports = { montar };\n");
  await escribir('src/servicios/pagos.js', "function cobrar() {\n  throw new Error('no implementado: pasarela pendiente');\n}\nmodule.exports = { cobrar };\n");
  await escribir('src/utils/calculo.js', "function calcularTotal(items, iva = 0.16) {\n  let subtotal = 0;\n  for (const item of items) {\n    subtotal += item.precio * item.cantidad;\n  }\n  const impuestos = subtotal * iva;\n  const total = subtotal + impuestos;\n  return { subtotal: Number(subtotal.toFixed(2)), impuestos: Number(impuestos.toFixed(2)), total: Number(total.toFixed(2)) };\n}\nmodule.exports = { calcularTotal };\n");
  await escribir('src/utils/duplicado.js', "function calcularTotal(items, iva = 0.16) {\n  let subtotal = 0;\n  for (const item of items) {\n    subtotal += item.precio * item.cantidad;\n  }\n  const impuestos = subtotal * iva;\n  const total = subtotal + impuestos;\n  return { subtotal: Number(subtotal.toFixed(2)), impuestos: Number(impuestos.toFixed(2)), total: Number(total.toFixed(2)) };\n}\nmodule.exports = { calcularTotal };\n");
  await escribir('src/vacio.js', '');
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-taller-');
  abandonado = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-abandonado-'));
  await crearProyectoAbandonado(abandonado);

  ctx = await contextoDePrueba(
    dataDir,
    { allowedGitRoots: [projectRoot, abandonado] },
    { rpm: 6000, burst: 1000 },
  );

  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-taller', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
  await fs.rm(abandonado, { recursive: true, force: true });
});

describe('GEMELO — mapa_proyecto', () => {
  it('expone las cuatro herramientas del taller en el catálogo', async () => {
    const { tools } = await client.listTools();
    const nombres = tools.map((t) => t.name);
    for (const esperada of ['mapa_proyecto', 'impacto_calcular', 'rescate_diagnostico', 'pendientes_listar']) {
      expect(nombres).toContain(esperada);
    }
  });

  it('mapea el proyecto real de la suite: módulos, rutas, servicios y entradas', async () => {
    const { isError, json } = await llamar('mapa_proyecto');
    expect(isError).toBe(false);
    const m = json();
    expect(m.totales.archivos_codigo).toBeGreaterThanOrEqual(30);
    const modulos = m.modulos.map((x: { modulo: string }) => x.modulo);
    expect(modulos).toContain('src/tools');
    expect(modulos).toContain('src/lib');
    expect(modulos).toContain('tests');
    const rutas = m.rutas.map((r: { ruta: string }) => r.ruta);
    expect(rutas).toContain('/health');
    expect(rutas).toContain('/mcp');
    expect(rutas).toContain('/panel');
    const paquetes = m.servicios.map((s: { paquete: string }) => s.paquete);
    expect(paquetes).toContain('@modelcontextprotocol/sdk');
    expect(paquetes).toContain('better-sqlite3');
    expect(m.entradas).toContain('src/index.ts');
  });

  it('genera el informe Markdown con tablas y diagrama Mermaid', async () => {
    const { json } = await llamar('mapa_proyecto', { formato: 'markdown' });
    const contenido = json().contenido as string;
    expect(contenido).toContain('# Mapa del proyecto');
    expect(contenido).toContain('```mermaid');
    expect(contenido).toContain('| Módulo |');
  });

  it('genera el diagrama SVG', async () => {
    const { json } = await llamar('mapa_proyecto', { formato: 'svg' });
    const svg = json().contenido as string;
    expect(svg.startsWith('<svg')).toBe(true);
    expect(svg).toContain('</svg>');
    expect(svg).toContain('archivos');
  });

  it('guarda el informe en data/taller/ cuando se pide', async () => {
    const { json } = await llamar('mapa_proyecto', { formato: 'markdown', guardar: true });
    const ruta = json().guardado_en as string;
    expect(ruta).toContain(path.join('taller', 'mapa-'));
    await fs.access(ruta); // lanza si no existe
  });

  it('detecta el módulo huérfano del proyecto abandonado', async () => {
    const { json } = await llamar('mapa_proyecto', { directorio: abandonado });
    const desconectados = json().desconectados as { archivo: string; rutas: number }[];
    const reportes = desconectados.find((d) => d.archivo === 'src/rutas/reportes.js');
    expect(reportes).toBeDefined();
    expect(reportes!.rutas).toBe(2);
  });

  it('rechaza directorios fuera de las rutas autorizadas', async () => {
    const { isError, texto } = await llamar('mapa_proyecto', { directorio: '/etc' });
    expect(isError).toBe(true);
    expect(texto).toContain('fuera de las rutas permitidas');
  });
});

describe('GEMELO — impacto_calcular', () => {
  it('calcula el impacto de tocar un archivo compartido con evidencia y pruebas', async () => {
    const { isError, json } = await llamar('impacto_calcular', { objetivo: 'src/lib/cfdi.ts' });
    expect(isError).toBe(false);
    const r = json();
    const dependientes = r.dependientesDirectos.map((d: { archivo: string }) => d.archivo);
    expect(dependientes).toContain('src/tools/facturacion.ts');
    expect(r.pruebasAfectadas).toContain('tests/cfdi.test.ts');
    expect(r.dependientesDirectos[0].evidencia.length).toBeGreaterThan(0);
    expect(['bajo', 'medio', 'alto']).toContain(r.nivelRiesgo);
    expect(r.comando_sugerido).toContain('npm test');
    expect(r.explicacion.length).toBeGreaterThan(20);
  });

  it('resuelve símbolos exportados por nombre', async () => {
    const { json } = await llamar('impacto_calcular', { objetivo: 'generarBorradorCfdi' });
    expect(json().archivosObjetivo).toContain('src/lib/cfdi.ts');
  });

  it('explica con claridad cuando no encuentra el objetivo', async () => {
    const { isError, texto } = await llamar('impacto_calcular', { objetivo: 'no/existe.ts' });
    expect(isError).toBe(true);
    expect(texto).toContain('No encontré');
  });
});

describe('RESCATE — rescate_diagnostico', () => {
  it('reconstruye la arquitectura del proyecto abandonado', async () => {
    const { isError, json } = await llamar('rescate_diagnostico', { directorio: abandonado });
    expect(isError).toBe(false);
    const d = json();
    expect(d.arquitectura.entradas).toContain('src/index.js');
    expect(d.arquitectura.totalArchivos).toBeGreaterThanOrEqual(7);
    expect(['abandonado', 'dañado']).toContain(d.estado);
    expect(d.veredicto.length).toBeGreaterThan(40);
  });

  it('encuentra stubs, archivos vacíos, desconectados y duplicados', async () => {
    const { json } = await llamar('rescate_diagnostico', { directorio: abandonado });
    const d = json();
    expect(d.resumen.stubs).toBeGreaterThanOrEqual(1);
    expect(d.resumen.vacios).toBeGreaterThanOrEqual(1);
    expect(d.resumen.desconectados).toBeGreaterThanOrEqual(1);
    expect(
      d.huecos.some((h: { tipo: string; detalle: string }) => h.tipo === 'duplicado' && h.detalle.includes('calcularTotal')),
    ).toBe(true);
  });

  it('compara la documentación con la realidad y detecta los desajustes', async () => {
    const { json } = await llamar('rescate_diagnostico', { directorio: abandonado });
    const docs = json().documentacion;
    expect(docs.archivo).toBe('README.md');
    expect(docs.desajustes).toBeGreaterThanOrEqual(2);
    const script = docs.reclamos.find((r: { tipo: string; dicho: string }) => r.tipo === 'script' && r.dicho === 'npm run build');
    expect(script?.coincide).toBe(false);
    const archivo = docs.reclamos.find((r: { tipo: string; dicho: string }) => r.tipo === 'ruta_archivo' && r.dicho === 'src/api/ventas.js');
    expect(archivo?.coincide).toBe(false);
  });

  it('produce un plan de terminación ordenado por impacto', async () => {
    const { json } = await llamar('rescate_diagnostico', { directorio: abandonado });
    const plan = json().plan as { orden: number; impacto: number }[];
    expect(plan.length).toBeGreaterThanOrEqual(4);
    expect(plan[0]!.orden).toBe(1);
    for (let i = 1; i < plan.length; i++) {
      expect(plan[i - 1]!.impacto).toBeGreaterThanOrEqual(plan[i]!.impacto);
    }
  });

  it('guarda el informe de rescate en Markdown', async () => {
    const { json } = await llamar('rescate_diagnostico', { directorio: abandonado, guardar: true });
    const ruta = json().guardado_en as string;
    await fs.access(ruta);
    const contenido = await fs.readFile(ruta, 'utf8');
    expect(contenido).toContain('# Diagnóstico de rescate');
    expect(contenido).toContain('## Plan de terminación');
  });
});

describe('RESCATE — pendientes_listar', () => {
  it('lista pendientes ordenados por dependientes', async () => {
    const { json } = await llamar('pendientes_listar', { directorio: abandonado });
    const r = json();
    expect(r.total).toBeGreaterThanOrEqual(4);
    expect(r.mostrados).toBeGreaterThanOrEqual(4);
    const primera = r.pendientes[0];
    const ultima = r.pendientes[r.pendientes.length - 1];
    expect(primera.dependientes).toBeGreaterThanOrEqual(ultima.dependientes);
    expect(r.pendientes.some((p: { tipo: string }) => p.tipo === 'stub')).toBe(true);
  });
});

describe('política de permisos del módulo taller', () => {
  it('acepta los scopes taller y taller:ro', () => {
    expect(() => validarScopes(['taller'])).not.toThrow();
    expect(() => validarScopes(['taller:ro'])).not.toThrow();
  });

  it('taller:ro solo permite herramientas de solo lectura', () => {
    const politica = politicaDesdeScopes(['taller:ro']);
    expect(politica({ name: 'mapa_proyecto', module: 'taller', readOnly: true })).toBe(true);
    expect(politica({ name: 'herramienta_que_escribe', module: 'taller', readOnly: false })).toBe(false);
  });
});
