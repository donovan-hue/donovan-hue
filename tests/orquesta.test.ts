/**
 * Pruebas del módulo «orquesta» (fase 4D): flujos con bitácora, dry-run,
 * flujos propios y solicitud de aprobación humana.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let proyecto: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-orquesta-');
  proyecto = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-orquesta-'));
  await fs.writeFile(path.join(proyecto, 'package.json'), JSON.stringify({ name: 'proy-orquesta', version: '0.1.0', private: true }, null, 2));
  await fs.writeFile(path.join(proyecto, 'package-lock.json'), '{}\n');
  await fs.mkdir(path.join(proyecto, 'src'), { recursive: true });
  await fs.writeFile(path.join(proyecto, 'src', 'index.js'), 'module.exports = { ok: true };\n');
  // Repositorio con un commit real: los flujos leen git de verdad.
  execFileSync('git', ['-C', proyecto, 'init', '-q'], { stdio: 'ignore' });
  execFileSync('git', ['-C', proyecto, 'add', '-A'], { stdio: 'ignore' });
  execFileSync('git', ['-C', proyecto, '-c', 'user.email=t@t', '-c', 'user.name=tester', 'commit', '-qm', 'inicio'], { stdio: 'ignore' });
  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [proyecto, projectRoot] }, { rpm: 6000, burst: 1000 });
  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-orquesta', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
  await fs.rm(proyecto, { recursive: true, force: true });
});

describe('ORQUESTA — flujos', () => {
  it('lista los 5 flujos de batalla probada con sus pasos', async () => {
    const { json } = await llamar('flujo_listar');
    const j = json();
    expect(j.predefinidos.map((f: { id: string }) => f.id)).toEqual([
      'verificar_despliegue', 'respaldo_rotacion', 'auditoria_dependencias', 'preparar_release', 'post_mortem',
    ]);
    expect(j.predefinidos[0].pasos.length).toBe(2);
    expect(j.tipos_de_paso_permitidos).toContain('salud_http');
  });

  it('dry-run muestra el plan sin ejecutar nada', async () => {
    const { json } = await llamar('flujo_ejecutar', { flujo: 'auditoria_dependencias', dry_run: true });
    const j = json();
    expect(j.dry_run).toBe(true);
    expect(j.pasos.every((p: { resumen: string }) => p.resumen.includes('No ejecutado'))).toBe(true);
  });

  it('auditoría de dependencias y seguridad deja acta con los resultados', async () => {
    const { json } = await llamar('flujo_ejecutar', { flujo: 'auditoria_dependencias' });
    const j = json();
    expect(j.ok).toBe(true);
    expect(j.acta).toContain('orquesta/runs');
    const dependencias = j.pasos.find((p: { tipo: string }) => p.tipo === 'dependencias_resumen');
    expect(dependencias.datos.lockfile).toBe(true);
    const seguridad = j.pasos.find((p: { tipo: string }) => p.tipo === 'seguridad_resumen');
    expect(seguridad.datos.semaforo).toBe('verde');
    expect(seguridad.datos.puntaje).toBe(100);
  });

  it('respaldo con rotación crea un respaldo real con su manifiesto', async () => {
    const { json } = await llamar('flujo_ejecutar', { flujo: 'respaldo_rotacion', parametros: { conservar: 3 } });
    const j = json();
    expect(j.ok).toBe(true);
    const paso = j.pasos[0];
    expect(paso.datos.bytes_total).toBeGreaterThan(0);
    const manifiesto = JSON.parse(await fs.readFile(path.join(paso.datos.ruta, 'manifest.json'), 'utf8'));
    expect(manifiesto.archivos.length).toBeGreaterThan(0);
  });

  it('verificar despliegue distingue URLs sanas de caídas sin mentir', async () => {
    const { json } = await llamar('flujo_ejecutar', {
      flujo: 'verificar_despliegue',
      parametros: { urls: ['http://127.0.0.1:9/health', 'no-es-una-url'] },
    });
    const j = json();
    expect(j.ok).toBe(true); // el flujo corrió: el resultado del paso es el dato
    const salud = j.pasos.find((p: { tipo: string }) => p.tipo === 'salud_http');
    expect(salud.ok).toBe(true);
    expect(salud.datos.filter((r: { estado: number | null }) => r.estado === null)).toHaveLength(2);
    expect(salud.resumen).toContain('2 de 2');
  });

  it('preparar release PROPONE la versión y las notas (no publica nada)', async () => {
    const { json } = await llamar('flujo_ejecutar', { flujo: 'preparar_release' });
    const j = json();
    const propuesta = j.pasos.find((p: { tipo: string }) => p.tipo === 'release_propuesta');
    expect(propuesta.datos.version_actual).toBe('0.1.0');
    expect(propuesta.datos.version_propuesta).toBe('0.1.1');
    expect(propuesta.datos.para_publicar).toContain('lo haces tú');
  });

  it('post-mortem escribe un informe en Markdown con lo que encontró', async () => {
    const { json } = await llamar('flujo_ejecutar', { flujo: 'post_mortem' });
    const j = json();
    const informe = j.pasos[0].datos.informe as string;
    const contenido = await fs.readFile(informe, 'utf8');
    expect(contenido).toContain('# Post-mortem');
    expect(contenido).toContain('## Preguntas para la reunión');
  });

  it('define un flujo propio y lo corre; el estado muestra las corridas', async () => {
    const definido = await llamar('flujo_definir', {
      nombre: 'Chequeo diario',
      descripcion: 'Seguridad + pendientes del proyecto',
      pasos: [
        { tipo: 'seguridad_resumen', nombre: 'Seguridad' },
        { tipo: 'pendientes_resumen', nombre: 'Pendientes' },
      ],
    });
    const { id } = definido.json();
    expect(id).toBe('chequeo_diario');

    const corrida = await llamar('flujo_ejecutar', { flujo: id });
    expect(corrida.json().ok).toBe(true);

    const listado = await llamar('flujo_listar');
    expect(listado.json().propios.map((f: { id: string }) => f.id)).toContain('chequeo_diario');

    const estado = await llamar('flujo_estado');
    const j = estado.json();
    expect(j.corridas_recientes.length).toBeGreaterThanOrEqual(5);
    expect(j.corridas_recientes[0].flujo).toBe('chequeo_diario');
    expect(j.salud).toMatch(/corridas recientes sin fallas/);
  });

  it('no deja sobrescribir un flujo predefinido', async () => {
    const { isError, texto } = await llamar('flujo_definir', {
      id: 'post_mortem',
      nombre: 'Otro post mortem',
      pasos: [{ tipo: 'estado_git' }],
    });
    expect(isError).toBe(true);
    expect(texto).toContain('predefinido');
  });
});

describe('ORQUESTA — aprobaciones desde el flujo', () => {
  it('solicitar aprobación liga la decisión a un contenido (huella)', async () => {
    const solicitud = await llamar('aprobacion_solicitar', {
      tipo: 'despliegue',
      titulo: 'Publicar v0.1.1 en el servidor de producción',
      detalle: 'Rollback: restaurar el respaldo más reciente.',
      contenido_para_huella: 'release v0.1.1 | commit abc123',
    });
    const j = solicitud.json();
    expect(j.id).toMatch(/^AP-[0-9a-f]{6}$/);
    expect(j.estado).toBe('pendiente');
    expect(j.huella).toHaveLength(16);

    const estado = await llamar('flujo_estado');
    expect(estado.json().aprobaciones_pendientes.map((a: { id: string }) => a.id)).toContain(j.id);
  });
});
