/**
 * Módulo «orquesta» (fase 4D) — flujos de dev con aprobación y bitácora.
 *
 *   · flujo_listar         → los 5 flujos de batalla probada + los propios
 *   · flujo_ejecutar       → corre un flujo (con dry-run) y deja acta
 *   · flujo_estado         → últimas corridas y qué pasó
 *   · flujo_definir        → define un flujo propio con pasos permitidos
 *   · aprobacion_solicitar → pide autorización humana para cualquier operación
 */
import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { resolverDirectorio } from '../lib/dir-permitido.js';
import { solicitarAprobacion, huellaDeContenido, listarAprobaciones, expirarVencidas } from '../lib/aprobaciones.js';
import {
  FLUJOS_PREDEFINIDOS, TIPOS_PERMITIDOS, ejecutarFlujo, resolverFlujo, ultimasCorridas,
  leerFlujosPropios, guardarFlujoPropio, idFlujo, type DefinicionPaso,
} from '../lib/orquesta.js';

export function orquestaToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'orquesta',
    name: 'flujo_listar',
    title: 'Flujos disponibles',
    description:
      'Lista los flujos de trabajo: 5 de batalla probada (verificar despliegue, respaldo con rotación, auditoría de dependencias y seguridad, preparar release, post-mortem automático) más los que tú definas. Cada uno muestra sus pasos y parámetros.',
    readOnly: true,
    inputSchema: {},
    handler: async () => {
      const propios = await leerFlujosPropios(ctx);
      return {
        predefinidos: FLUJOS_PREDEFINIDOS.map((f) => ({ id: f.id, nombre: f.nombre, descripcion: f.descripcion, pasos: f.pasos.map((p) => p.nombre ?? p.tipo) })),
        propios: propios.map((f) => ({ id: f.id, nombre: f.nombre, descripcion: f.descripcion, pasos: f.pasos.map((p) => p.nombre ?? p.tipo) })),
        tipos_de_paso_permitidos: TIPOS_PERMITIDOS,
        como_correr: 'flujo_ejecutar {flujo:"<id>", parametros:{...}} — agrega dry_run:true para ver el plan sin ejecutar.',
      };
    },
  });

  push({
    module: 'orquesta',
    name: 'flujo_ejecutar',
    title: 'Ejecutar un flujo',
    description:
      'Corre un flujo paso a paso y deja un acta en data/orquesta/runs/ (qué paso, cuánto tardó, qué salió). Con dry_run=true solo muestra el plan. Si un paso falla, el flujo se detiene y lo dice. Nada salta el gate: lo que requiere permiso (publicar, desplegar) se resuelve con aprobacion_solicitar + aprobación humana.',
    inputSchema: {
      flujo: z.string().describe('Id del flujo (ver flujo_listar).'),
      directorio: z.string().optional().describe('Proyecto sobre el que corre (debe estar en ALLOWED_GIT_ROOTS).'),
      parametros: z.record(z.unknown()).optional().describe('Parámetros de la corrida (ej. {"urls":["http://localhost:8787/health"],"conservar":7}).'),
      dry_run: z.boolean().optional().describe('Mostrar el plan sin ejecutar nada.'),
      detener_en_error: z.boolean().optional().describe('Detener el flujo al primer paso fallido (por defecto true).'),
    },
    handler: async (args: { flujo: string; directorio?: string; parametros?: Record<string, unknown>; dry_run?: boolean; detener_en_error?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const r = await ejecutarFlujo(ctx, {
        flujo: args.flujo,
        raiz,
        parametros: args.parametros,
        dry_run: args.dry_run,
        detener_en_error: args.detener_en_error,
      });
      return {
        ...r,
        resumen: r.dry_run
          ? `Plan de "${r.nombre}" con ${r.pasos.length} paso(s) (no se ejecutó nada).`
          : r.ok
            ? `"${r.nombre}" completo: ${r.pasos.length} paso(s) en ${r.duracion_ms} ms.`
            : `"${r.nombre}" se detuvo: ${r.pasos.filter((p) => !p.ok).map((p) => p.paso).join(', ')}.`,
      };
    },
  });

  push({
    module: 'orquesta',
    name: 'flujo_estado',
    title: 'Estado de los flujos',
    description:
      'Muestra las últimas corridas (flujo, resultado, duración, pasos fallidos) y los flujos que están esperando una aprobación humana para seguir.',
    readOnly: true,
    inputSchema: {
      limite: z.number().int().min(1).max(50).optional().describe('Cuántas corridas listar (por defecto 10).'),
    },
    handler: async (args: { limite?: number }) => {
      expirarVencidas(ctx);
      const corridas = await ultimasCorridas(ctx, args.limite ?? 10);
      const pendientes = listarAprobaciones(ctx, { estado: 'pendiente', limite: 10 }).map((a) => ({
        id: a.id,
        tipo: a.tipo,
        titulo: a.titulo,
        como_aprobar: `node dist/cli.js aprobar ${a.id}`,
      }));
      return {
        corridas_recientes: corridas,
        total_corridas: corridas.length,
        aprobaciones_pendientes: pendientes,
        salud: corridas.length === 0
          ? 'Aún no has corrido ningún flujo. Prueba con dry_run primero.'
          : `${corridas.filter((c) => c.ok).length}/${corridas.length} corridas recientes sin fallas.`,
      };
    },
  });

  push({
    module: 'orquesta',
    name: 'flujo_definir',
    title: 'Definir un flujo propio',
    description:
      'Crea o actualiza un flujo propio combinando pasos permitidos (estado_git, salud_http, respaldo, seguridad_resumen, dependencias_resumen, pendientes_resumen, release_propuesta, post_mortem, espera). Se guarda en data/orquesta/flujos.json y queda disponible para flujo_ejecutar.',
    inputSchema: {
      nombre: z.string().min(3).describe('Nombre legible del flujo.'),
      descripcion: z.string().optional().describe('Para qué sirve.'),
      pasos: z
        .array(
          z.object({
            tipo: z.enum(['estado_git', 'salud_http', 'respaldo', 'seguridad_resumen', 'dependencias_resumen', 'pendientes_resumen', 'release_propuesta', 'post_mortem', 'espera']),
            nombre: z.string().optional(),
            parametros: z.record(z.unknown()).optional(),
          }),
        )
        .min(1),
      id: z.string().optional().describe('Identificador (se genera del nombre si lo omites).'),
    },
    handler: async (args: { nombre: string; descripcion?: string; pasos: DefinicionPaso[]; id?: string }) => {
      const id = args.id ?? idFlujo(args.nombre);
      if (FLUJOS_PREDEFINIDOS.some((f) => f.id === id)) {
        throw new Error(`"${id}" es un flujo predefinido y no se puede sobrescribir; usa otro id.`);
      }
      await guardarFlujoPropio(ctx, {
        id,
        nombre: args.nombre,
        descripcion: args.descripcion ?? '',
        predefinido: false,
        pasos: args.pasos,
      });
      const guardado = await resolverFlujo(ctx, id);
      return {
        id,
        guardado: true,
        pasos: guardado?.pasos.map((p) => p.nombre ?? p.tipo),
        como_correr: `flujo_ejecutar {flujo:"${id}"} (con dry_run:true primero, para ver el plan).`,
      };
    },
  });

  push({
    module: 'orquesta',
    name: 'aprobacion_solicitar',
    title: 'Solicitar aprobación humana',
    description:
      'Registra una solicitud de aprobación para CUALQUIER operación que necesite permiso humano (desplegar, publicar un release, tocar producción, aplicar un cambio). Queda pendiente en la cola; solo una persona la resuelve por CLI (aprobar/rechazar) o panel. Opcionalmente liga la solicitud a un contenido mediante huella: si lo que se va a ejecutar cambia, la aprobación caduca.',
    inputSchema: {
      tipo: z.string().min(2).describe('Categoría de la operación (ej. "despliegue", "publicacion_release", "produccion").'),
      titulo: z.string().min(4).describe('Qué se autoriza, en una frase clara.'),
      detalle: z.string().optional().describe('Contexto para quien decide: qué se hará, dónde, cómo revertirlo.'),
      contenido_para_huella: z.string().optional().describe('Texto que representa exactamente lo que se ejecutará; de él se deriva la huella que invalida la aprobación si cambia.'),
    },
    handler: async (args: { tipo: string; titulo: string; detalle?: string; contenido_para_huella?: string }) => {
      const ap = await solicitarAprobacion(ctx, {
        tipo: args.tipo,
        titulo: args.titulo,
        detalle: args.detalle ?? '',
        huella: args.contenido_para_huella ? huellaDeContenido(args.contenido_para_huella) : undefined,
      });
      return {
        id: ap.id,
        estado: ap.estado,
        huella: ap.huella,
        como_aprobar: `node dist/cli.js aprobar ${ap.id}`,
        como_rechazar: `node dist/cli.js rechazar ${ap.id} --motivo "…"`,
        nota: 'Un agente no puede resolver su propia solicitud: la decisión es de una persona.',
      };
    },
  });
}
