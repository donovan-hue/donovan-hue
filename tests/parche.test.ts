/**
 * Pruebas del módulo «parche» (fase 4C): diagnóstico con evidencia, parche
 * revisable, prueba en copia aislada (antes/después), aplicación SOLO con
 * aprobación humana y reversión en un comando.
 *
 * El proyecto de prueba es real: un cálculo mal escrito que hace fallar su
 * prueba, y un error de import que rompe el arranque. Nada simulado.
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
import { existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolverAprobacion } from '../src/lib/aprobaciones';
import type { ServerCtx } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let reparable: string;
let verde: string;
let cliProyecto: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

async function escribir(raiz: string, rel: string, contenido: string): Promise<void> {
  const absoluto = path.join(raiz, rel);
  await fs.mkdir(path.dirname(absoluto), { recursive: true });
  await fs.writeFile(absoluto, contenido, 'utf8');
}

const CALC_ROTO = [
  'function sumar(a, b) {',
  '  return a - b;',
  '}',
  '',
  'function totalPedido(pedido) {',
  '  const renglones = pedido.items.length;',
  '  return renglones;',
  '}',
  '',
  'module.exports = { sumar, totalPedido };',
  '',
].join('\n');

async function crearProyectoVerde(raiz: string): Promise<void> {
  await escribir(raiz, 'package.json', JSON.stringify({ name: 'proy-verde', version: '1.0.0', private: true, scripts: { test: 'node prueba.js' } }, null, 2));
  await escribir(raiz, 'src/calc.js', 'function sumar(a, b) {\n  return a + b;\n}\nmodule.exports = { sumar };\n');
  await escribir(raiz, 'prueba.js', 'const { sumar } = require("./src/calc");\nif (sumar(2, 2) !== 4) process.exit(1);\nconsole.log("1 passing");\n');
}

async function crearProyectoReparable(raiz: string): Promise<void> {
  await escribir(raiz, 'package.json', JSON.stringify({ name: 'proy-reparable', version: '1.0.0', private: true, scripts: { test: 'node prueba.js' } }, null, 2));
  await escribir(raiz, 'src/calc.js', CALC_ROTO);
  await escribir(raiz, 'src/index.js', 'const { formatear } = require("./utils/formato");\nmodule.exports = { formatear };\n');
  await escribir(raiz, 'prueba.js', [
    'const { sumar } = require("./src/calc");',
    'if (sumar(2, 2) !== 4) {',
    '  console.error("sumar(2,2) deberia ser 4 y dio", sumar(2, 2));',
    '  process.exit(1);',
    '}',
    'console.log("1 passing");',
    '',
  ].join('\n'));
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-parche-');
  reparable = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-reparable-'));
  verde = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-verde-'));
  cliProyecto = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-cli-parche-'));
  await crearProyectoReparable(reparable);
  await crearProyectoVerde(verde);
  // Proyecto propio para las pruebas de la CLI: así ninguna otra prueba lo toca.
  await crearProyectoVerde(cliProyecto);
  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [projectRoot, reparable, verde, cliProyecto] }, { rpm: 6000, burst: 1000 });
  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-parche', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
  await fs.rm(reparable, { recursive: true, force: true });
  await fs.rm(verde, { recursive: true, force: true });
  await fs.rm(cliProyecto, { recursive: true, force: true });
});

describe('PARCHE — diagnóstico con evidencia', () => {
  it('identifica acceso a propiedad de undefined y cita el archivo y la línea', async () => {
    const error = ["TypeError: Cannot read properties of undefined (reading 'length')", '    at totalPedido (src/calc.js:6:28)'].join('\n');
    const { isError, json } = await llamar('falla_diagnosticar', { directorio: reparable, error });
    expect(isError).toBe(false);
    const d = json();
    expect(d.tipo).toBe('excepcion');
    const causa = d.causas[0];
    expect(causa.confianza).toBe('alta');
    expect(causa.causa).toContain('undefined');
    expect(causa.evidencia.join('\n')).toContain('src/calc.js:6');
    expect(d.siguiente_paso).toContain('parche_proponer');
  });

  it('detecta un import relativo que no resuelve y propone la ruta buena si existe', async () => {
    const error = ['Error: Cannot find module \'./utils/formato\'', '    at Object.<anonymous> (src/index.js:1:24)'].join('\n');
    const { json } = await llamar('falla_diagnosticar', { directorio: reparable, error });
    const causa = json().causas[0];
    expect(causa.causa).toContain('./utils/formato');
    expect(causa.sugerencia).toMatch(/Crea el archivo|apuntar/);
    // Ahora lo creamos y el diagnóstico debe ofrecer la ruta existente.
    await escribir(reparable, 'src/utils/formato.js', 'module.exports = { formatear: (x) => String(x) };\n');
    const { json: json2 } = await llamar('falla_diagnosticar', { directorio: reparable, error });
    expect(json2().causas[0].evidencia.join('\n')).toContain('existe: src/utils/formato.js');
  });

  it('una prueba rota se diagnostica como expectativa y no como adivinanza', async () => {
    const error = ['FAIL  prueba.js', 'AssertionError: expected 0 to be 4'].join('\n');
    const { json } = await llamar('falla_diagnosticar', { directorio: reparable, error });
    expect(json().tipo).toBe('prueba');
    expect(json().causas.some((c: { causa: string }) => /expectativa|no coincide|difiere/i.test(c.causa))).toBe(true);
  });
});

describe('PARCHE — proponer, probar, aplicar y revertir', () => {
  it('rechaza un parche cuyo bloque «antes» no existe (parche obsoleto)', async () => {
    const { isError, texto } = await llamar('parche_proponer', {
      directorio: reparable,
      titulo: 'Cambio obsoleto',
      cambios: [{ ruta: 'src/calc.js', antes: 'return a * b;', despues: 'return a + b;' }],
    });
    expect(isError).toBe(true);
    expect(texto).toContain('no aparece en el archivo');
  });

  it('propone, prueba en copia aislada y demuestra el arreglo', async () => {
    const propuesta = await llamar('parche_proponer', {
      directorio: reparable,
      titulo: 'sumar debe sumar',
      justificacion: 'La prueba prueba.js espera sumar(2,2)=4.',
      cambios: [{ ruta: 'src/calc.js', antes: '  return a - b;', despues: '  return a + b;', descripcion: 'sumar resta; debe sumar' }],
    });
    const p = propuesta.json();
    expect(p.id).toMatch(/^PR-[0-9a-f]{6}$/);
    expect(p.diff).toContain('-   return a - b;');
    expect(p.diff).toContain('+   return a + b;');

    const prueba = await llamar('parche_probar', { parche_id: p.id, comando: 'node prueba.js' });
    const j = prueba.json();
    expect(j.veredicto).toBe('arregla');
    expect(j.antes.codigo).toBe(1);
    expect(j.despues.codigo).toBe(0);
    expect(j.resumen).toContain('rojo a verde');
    // El repositorio original no se tocó: el bug sigue ahí.
    const calc = await fs.readFile(path.join(reparable, 'src/calc.js'), 'utf8');
    expect(calc).toContain('return a - b;');
  });

  it('no se aplica sin aprobación humana y sí con ella; revertir siempre está permitido', async () => {
    const propuesta = await llamar('parche_proponer', {
      directorio: reparable,
      titulo: 'Corregir la suma (con aprobación)',
      cambios: [{ ruta: 'src/calc.js', antes: '  return a - b;', despues: '  return a + b;' }],
      solicitar_aprobacion: true,
    });
    const { id, aprobacion } = propuesta.json();
    await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });

    // Sin aprobación válida: bloqueado, con mensaje claro.
    let salida = await llamar('parche_aplicar', { parche_id: id, aprobacion_id: aprobacion.id });
    expect(salida.isError).toBe(true);
    expect(salida.texto).toContain('pendiente');

    // La persona aprueba por CLI (aquí, la misma función que usa el CLI).
    await resolverAprobacion(ctx, aprobacion.id, 'aprobada', { por: 'tester-cli' });
    salida = await llamar('parche_aplicar', { parche_id: id, aprobacion_id: aprobacion.id });
    const aplicado = salida.json();
    expect(aplicado.aplicado).toBe(true);
    expect(await fs.readFile(path.join(reparable, 'src/calc.js'), 'utf8')).toContain('return a + b;');

    // Y la prueba pasa ahora en el repositorio REAL.
    salida = await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });
    expect(salida.isError).toBe(true); // ya aplicado: probar solo aplica a propuestos/probados

    // Revertir: sin aprobación, con respaldo, y deja el archivo como estaba.
    const revertido = await llamar('reparacion_revertir', { parche_id: id });
    expect(revertido.json().revertido).toBe(true);
    expect(await fs.readFile(path.join(reparable, 'src/calc.js'), 'utf8')).toContain('return a - b;');
    expect((await llamar('reparacion_revertir', { parche_id: id })).isError).toBe(true);
  });

  it('el límite de intentos evita bucles que no llevan a nada', async () => {
    const propuesta = await llamar('parche_proponer', {
      directorio: reparable,
      titulo: 'Propuesta sin efecto útil',
      cambios: [{ ruta: 'src/calc.js', antes: '  return a - b;', despues: '  return a - b; // revisado' }],
    });
    const { id } = propuesta.json();
    for (let i = 0; i < 3; i++) {
      await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });
    }
    const cuarta = await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });
    expect(cuarta.isError).toBe(true);
    expect(cuarta.texto).toContain('Límite de 3 intentos');
  });

  it('un parche que empeora las pruebas no se aplica ni con aprobación', async () => {
    // Proyecto en verde: el cambio lo rompe, y aun así alguien lo aprobó.
    const propuesta = await llamar('parche_proponer', {
      directorio: verde,
      titulo: 'Cambio que rompe la suma',
      cambios: [{ ruta: 'src/calc.js', antes: '  return a + b;', despues: '  return a - b;' }],
      solicitar_aprobacion: true,
    });
    const { id, aprobacion } = propuesta.json();
    const prueba = await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });
    expect(prueba.json().veredicto).toBe('empeora');
    expect(prueba.json().resumen).toContain('EMPEORA');

    await resolverAprobacion(ctx, aprobacion.id, 'aprobada', { por: 'tester-cli' });
    const r = await llamar('parche_aplicar', { parche_id: id, aprobacion_id: aprobacion.id });
    expect(r.isError).toBe(true);
    expect(r.texto).toContain('EMPEORÓ');
    // Y el archivo sigue intacto: el guardarraíl cumplió.
    expect(await fs.readFile(path.join(verde, 'src/calc.js'), 'utf8')).toContain('return a + b;');
  });

  it('lista el historial con sus estados y veredictos', async () => {
    const { json } = await llamar('reparaciones_listar');
    const j = json();
    expect(j.total).toBeGreaterThanOrEqual(3);
    const estados = new Set(j.parches.map((p: { estado: string }) => p.estado));
    expect(estados.has('revertido')).toBe(true);
    expect(estados.has('probado')).toBe(true);
    expect(j.guardarrailes.revertir_requiere).toContain('nada');
  });
});

describe('PARCHE — CLI', () => {
  const hayDist = existsSync(path.join(projectRoot, 'dist', 'cli.js'));
  const entorno = () => ({
    ...process.env,
    DATA_DIR: dataDir,
    ALLOWED_GIT_ROOTS: [projectRoot, reparable, verde, cliProyecto].join(path.delimiter),
    LOG_LEVEL: 'error',
  });
  const cli = (args: string[]) =>
    execFileSync('node', [path.join(projectRoot, 'dist', 'cli.js'), ...args], { env: entorno(), encoding: 'utf8' });

  it.skipIf(!hayDist)('`parches` lista el historial y `revertir` deshace un parche aplicado desde la consola', async () => {
    // 1. Un parche real: el modelo propone, las pruebas confirman, un humano aprueba.
    const propuesta = await llamar('parche_proponer', {
      directorio: cliProyecto,
      titulo: 'Comentario de claridad en la suma',
      justificacion: 'Prueba de la CLI: aplicar y luego revertir.',
      cambios: [{ ruta: 'src/calc.js', antes: '  return a + b;', despues: '  return a + b; // suma simple' }],
      solicitar_aprobacion: true,
    });
    const { id, aprobacion } = propuesta.json();
    const prueba = await llamar('parche_probar', { parche_id: id, comando: 'node prueba.js' });
    expect(['arregla', 'mejora', 'igual']).toContain(prueba.json().veredicto);
    await resolverAprobacion(ctx, aprobacion.id, 'aprobada', { por: 'tester-cli' });
    const aplicado = await llamar('parche_aplicar', { parche_id: id, aprobacion_id: aprobacion.id });
    expect(aplicado.isError).toBe(false);
    expect(await fs.readFile(path.join(cliProyecto, 'src/calc.js'), 'utf8')).toContain('// suma simple');

    // 2. `parches`: el historial legible para una persona, no JSON crudo.
    const listado = cli(['parches']);
    expect(listado).toContain(id);
    expect(listado).toContain('[aplicado]');
    expect(listado).toContain('revertir');

    // 3. `revertir <PR-id>`: deshacer nunca pide aprobación.
    const revertido = cli(['revertir', id]);
    expect(revertido).toContain('Revertido');
    expect(revertido).toContain('nunca pide aprobación');
    const restaurado = await fs.readFile(path.join(cliProyecto, 'src/calc.js'), 'utf8');
    expect(restaurado).toContain('return a + b;');
    expect(restaurado).not.toContain('// suma simple');

    const despues = cli(['parches', '--estado', 'revertido']);
    expect(despues).toContain(id);
  });

  it.skipIf(!hayDist)('`revertir` sin id explica el uso', () => {
    let salida = '';
    try {
      cli(['revertir']);
    } catch (e: any) {
      salida = String(e.stdout) + String(e.stderr);
    }
    expect(salida).toContain('revertir <PR-id>');
  });
});
