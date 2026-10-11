/**
 * Pruebas del módulo «ruter» (fase 4D): clasificación de tarea, cascada de
 * modelos, privacidad, política por tipo y —lo importante— coste por resultado
 * útil medido con resultados reales registrados.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
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
  dataDir = await dataDirTemporal('mcp-suite-ruter-');
  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [projectRoot] }, { rpm: 6000, burst: 1000 });
  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-ruter', version: '1.0.0' });
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

describe('RÚTER — elegir modelo', () => {
  it('tarea mecánica → nivel 1 y arranca por el local', async () => {
    const { json } = await llamar('modelo_elegir', { descripcion: 'Renombrar variables en un archivo', tipo: 'formato' });
    const j = json();
    expect(j.nivel).toBe(1);
    expect(j.cascada[0].id).toBe('local-rapido');
    expect(j.presupuesto_sugerido).toContain('local');
  });

  it('tarea crítica → nivel 3 sin importar el costo', async () => {
    const { json } = await llamar('modelo_elegir', {
      descripcion: 'Migrar el esquema de la base de datos de facturación',
      tipo: 'migracion',
      criticidad: 'alta',
    });
    const j = json();
    expect(j.nivel).toBe(3);
    // En nivel 3 no se empieza barato: arranca (y basta) el modelo fuerte.
    expect(j.cascada[0].id).toBe('nube-fuerte');
    expect(j.motivo).toContain('criticidad alta');
  });

  it('privacidad "no_sale" limita la cascada a modelos locales', async () => {
    const { json } = await llamar('modelo_elegir', {
      descripcion: 'Refactorizar el módulo de nómina completo',
      tipo: 'refactor',
      privacidad: 'no_sale',
    });
    const j = json();
    expect(j.cascada.every((c: { id: string }) => c.id.startsWith('local'))).toBe(true);
  });

  it('muchos archivos sube un nivel automáticamente', async () => {
    const { json } = await llamar('modelo_elegir', {
      descripcion: 'Ajustar importaciones',
      tipo: 'refactor',
      archivos_tocados: 40,
    });
    expect(json().nivel).toBeGreaterThanOrEqual(2);
    expect(json().motivo).toContain('40 archivos');
  });
});

describe('RÚTER — coste por resultado útil', () => {
  it('el historial mueve la cascada: un modelo que falla baja de lugar', async () => {
    const antes = await llamar('modelo_elegir', { descripcion: 'Reparar la validación de correo', tipo: 'refactor' });
    expect(antes.json().cascada.map((c: { id: string }) => c.id)).toContain('nube-rapida');

    for (let i = 0; i < 3; i++) {
      await llamar('modelo_resultado', {
        tarea_id: `T-00${i}`,
        tipo: 'refactor',
        modelo: 'nube-rapida',
        exito: false,
        evidencia: 'Las pruebas siguieron rojas',
        coste: 1,
      });
    }
    const despues = await llamar('modelo_elegir', { descripcion: 'Reparar la validación de correo', tipo: 'refactor' });
    const j = despues.json();
    expect(j.ajustes_por_datos.join(' ')).toContain('nube-rapida');
    expect(j.cascada.slice(0, 2).map((c: { id: string }) => c.id)).not.toContain('nube-rapida');
  });

  it('coste_comparar mide coste por tarea EXITOSA, no por token', async () => {
    await llamar('modelo_resultado', { tarea_id: 'T-100', tipo: 'refactor', modelo: 'local-grande', exito: true, evidencia: '132/132 pruebas verdes', coste: 0 });
    await llamar('modelo_resultado', { tarea_id: 'T-101', tipo: 'refactor', modelo: 'local-grande', exito: true, evidencia: 'parche aplicado y no revertido', coste: 0 });
    await llamar('modelo_resultado', { tarea_id: 'T-102', tipo: 'refactor', modelo: 'local-grande', exito: true, evidencia: '202/202 pruebas verdes', coste: 0 });
    await llamar('modelo_resultado', { tarea_id: 'T-103', tipo: 'refactor', modelo: 'nube-media', exito: true, evidencia: 'pruebas verdes tras migrar', coste: 3 });
    await llamar('modelo_resultado', { tarea_id: 'T-104', tipo: 'refactor', modelo: 'nube-media', exito: false, evidencia: 'se revirtió el parche', coste: 3 });

    const { json } = await llamar('coste_comparar', { tipo: 'refactor' });
    const j = json();
    expect(j.muestra_suficiente).toBe(true);
    const local = j.por_modelo.find((m: { modelo: string }) => m.modelo === 'local-grande');
    const nube = j.por_modelo.find((m: { modelo: string }) => m.modelo === 'nube-media');
    expect(local.coste_por_exito).toBe(0);
    expect(nube.coste_por_exito).toBe(6); // 6 unidades ÷ 1 éxito
    expect(nube.tasa_exito).toBe(0.5);
    expect(j.como_se_mide).toContain('tareas con resultado útil');
    expect(j.recomendacion).toContain('local-grande');
  });
});

describe('RÚTER — política por tipo', () => {
  it('aplica presupuesto máximo y preferencias por tipo de tarea', async () => {
    const guardado = await llamar('politica_modelos', {
      tipo: 'documentacion',
      presupuesto_max: 1,
      solo_local: true,
      nota: 'la documentación nunca sale de la máquina',
    });
    expect(guardado.json().guardado).toBe(true);

    const { json } = await llamar('modelo_elegir', { descripcion: 'Redactar el manual de la API', tipo: 'documentacion' });
    const j = json();
    expect(j.cascada.every((c: { id: string }) => c.id.startsWith('local'))).toBe(true);
    expect(j.politica_aplicada.tipo).toBe('documentacion');

    // Consultar la regla (solo el tipo) no la borra: la devuelve tal cual.
    const consulta = await llamar('politica_modelos', { tipo: 'documentacion' });
    expect(consulta.json().guardado).toBe(false);
    expect(consulta.json().regla.solo_local).toBe(true);
    expect(consulta.json().regla.presupuesto_max).toBe(1);

    const borrado = await llamar('politica_modelos', { tipo: 'documentacion', eliminar: true });
    expect(borrado.json().politica.documentacion).toBeUndefined();
  });
});
