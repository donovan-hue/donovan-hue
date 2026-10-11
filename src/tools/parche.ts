/**
 * Módulo «parche» (fase 4C) — reparar con red de seguridad.
 *
 *   · falla_diagnosticar    → causa probable con evidencia archivo:línea
 *   · parche_proponer       → cambio revisable (diff + huella) + aprobación opcional
 *   · parche_probar         → pruebas antes/después en copia aislada (no toca el repo)
 *   · parche_aplicar        → aplica al repo real SOLO con aprobación humana + respaldo
 *   · reparacion_revertir   → deshace un parche aplicado (un comando)
 *   · reparaciones_listar   → historial de parches
 *
 * Reparto de responsabilidades: el modelo puede diagnosticar, proponer y probar;
 * aplicar exige una aprobación resuelta por una persona (CLI/panel) y con la
 * misma huella del parche. Revertir siempre está permitido: deshacer nunca debe
 * requerir permiso.
 */
import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { resolverDirectorio } from '../lib/dir-permitido.js';
import { exigirAprobacion, solicitarAprobacion } from '../lib/aprobaciones.js';
import {
  diagnosticar, crearParche, guardarParche, obtenerParche, listarParches, actualizarParche,
  probarParche, aplicarParcheReal, deshacerParche, MAX_INTENTOS,
} from '../lib/parche.js';

function resumenParche(p: ReturnType<typeof listarParches>[number]) {
  return {
    id: p.id,
    titulo: p.titulo,
    proyecto: p.proyecto,
    estado: p.estado,
    huella: p.huella,
    archivos: p.cambios.map((c) => c.ruta),
    creado_en: p.creado_en,
    intentos: p.intentos,
    veredicto_prueba: p.prueba?.veredicto,
  };
}

export function parcheToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'parche',
    name: 'falla_diagnosticar',
    title: 'Diagnóstico de una falla con evidencia',
    description:
      'Analiza un error, un log o una prueba rota y devuelve causas probables SIN adivinar: cada afirmación viene con evidencia archivo:línea leída del proyecto. Detecta entre otros: módulo que no resuelve (ruta relativa rota o dependencia no declarada), archivo inexistente (ENOENT), acceso a propiedad de undefined/null, expectativas de prueba incumplidas, errores de sintaxis y servicios caídos. No modifica nada.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      error: z.string().min(1).describe('El error, la salida de la prueba o el renglón del log que quieres diagnosticar (mientras más completo, mejor).'),
    },
    handler: async (args: { directorio?: string; error: string }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const d = await diagnosticar(raiz, args.error);
      return {
        proyecto: path.basename(raiz),
        ...d,
        recuerda: 'Esto es un diagnóstico, no una promesa: el parche se prueba antes de aplicarse.',
      };
    },
  });

  push({
    module: 'parche',
    name: 'parche_proponer',
    title: 'Proponer un parche revisable',
    description:
      'Registra un parche como una lista de sustituciones exactas (archivo + bloque «antes» + bloque «después»). Valida que cada bloque exista tal cual en el archivo (si no, el parche está obsoleto y lo dice), genera un diff legible y una huella: si el parche cambia después de aprobarlo, la aprobación deja de valer. Con solicitar_aprobacion=true pide la autorización humana para aplicarlo (se resuelve por CLI, nunca por el modelo).',
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      titulo: z.string().min(4).describe('Título corto del arreglo (ej. "Validar sesión antes de /reportes").'),
      justificacion: z.string().optional().describe('Por qué es correcto este cambio (referencia el diagnóstico).'),
      cambios: z
        .array(
          z.object({
            ruta: z.string().describe('Ruta relativa del archivo dentro del proyecto.'),
            antes: z.string().min(1).describe('Texto EXACTO que existe hoy (una o varias líneas).'),
            despues: z.string().describe('Texto que lo sustituye.'),
            descripcion: z.string().optional(),
          }),
        )
        .min(1)
        .describe('Sustituciones exactas; usa varias entradas si tocan varios archivos.'),
      solicitar_aprobacion: z.boolean().optional().describe('Crear la solicitud de aprobación humana para aplicar este parche.'),
    },
    handler: async (args: {
      directorio?: string; titulo: string; justificacion?: string;
      cambios: { ruta: string; antes: string; despues: string; descripcion?: string }[];
      solicitar_aprobacion?: boolean;
    }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const parche = await crearParche(raiz, {
        titulo: args.titulo,
        justificacion: args.justificacion,
        cambios: args.cambios,
        creado_por: ctx.apiKey?.nombre ?? 'local',
      });
      guardarParche(ctx.db, parche);
      const extra: Record<string, unknown> = {};
      if (args.solicitar_aprobacion) {
        const ap = await solicitarAprobacion(ctx, {
          tipo: 'aplicar_parche',
          titulo: `Aplicar el parche ${parche.id}: ${parche.titulo}`,
          detalle: `Archivos: ${parche.cambios.map((c) => c.ruta).join(', ')}. Si el parche cambia, la huella deja de coincidir y la aprobación caduca.`,
          huella: parche.huella,
        });
        extra.aprobacion = { id: ap.id, estado: ap.estado, como_aprobar: `node dist/cli.js aprobar ${ap.id}` };
      }
      return {
        id: parche.id,
        huella: parche.huella,
        estado: parche.estado,
        archivos: parche.cambios.map((c) => c.ruta),
        diff: parche.diff,
        siguiente_paso: 'Pruébalo con `parche_probar` (no toca tu repositorio: corre en una copia) y, si queda en verde, aplica con aprobación.',
        ...extra,
      };
    },
  });

  push({
    module: 'parche',
    name: 'parche_probar',
    title: 'Probar el parche en una copia aislada',
    description:
      'Corre las pruebas del proyecto ANTES y DESPUÉS de aplicar el parche, siempre dentro de una copia aislada bajo data/parche/pruebas/ — tu repositorio no se toca. Devuelve el veredicto (arregla / mejora / igual / empeora) con la salida de las pruebas. Límite de intentos por parche para evitar bucles que no llevan a nada.',
    inputSchema: {
      parche_id: z.string().describe('Id del parche (PR-xxxxxx).'),
      comando: z.string().optional().describe('Comando de pruebas; por defecto usa el script "test" de package.json.'),
      timeout_ms: z.number().int().min(5000).max(600000).optional().describe('Tiempo máximo para cada corrida de pruebas (por defecto 120000).'),
    },
    handler: async (args: { parche_id: string; comando?: string; timeout_ms?: number }) => {
      const parche = obtenerParche(ctx.db, args.parche_id);
      if (!parche) throw new Error(`No existe el parche ${args.parche_id}.`);
      if (parche.estado === 'aplicado' || parche.estado === 'revertido') {
        throw new Error(`El parche ${parche.id} está "${parche.estado}"; probar solo aplica a parches propuestos o probados.`);
      }
      if (parche.intentos >= MAX_INTENTOS) {
        throw new Error(
          `Límite de ${MAX_INTENTOS} intentos alcanzado para ${parche.id}. Revisa el diagnóstico y ajusta el parche (o divide el problema) en lugar de reintentar a ciegas.`,
        );
      }
      const prueba = await probarParche(ctx, parche, { comando: args.comando, timeoutMs: args.timeout_ms });
      actualizarParche(ctx.db, parche.id, {
        estado: 'probado',
        intentos: parche.intentos + 1,
        prueba: JSON.stringify(prueba),
      });
      await ctx.audit.registrar({
        tipo: 'parche_probado',
        cliente: ctx.apiKey?.nombre ?? 'local',
        herramienta: 'parche_probar',
        detalle: `${parche.id} · ${prueba.veredicto} · antes=${prueba.antes.codigo} después=${prueba.despues.codigo}`,
      });
      return {
        id: parche.id,
        veredicto: prueba.veredicto,
        resumen: prueba.resumen,
        antes: { comando: prueba.antes.comando, codigo: prueba.antes.codigo, aprobadas: prueba.antes.aprobadas, fallidas: prueba.antes.fallidas, ms: prueba.antes.duracion_ms, salida: prueba.antes.salida },
        despues: { comando: prueba.despues.comando, codigo: prueba.despues.codigo, aprobadas: prueba.despues.aprobadas, fallidas: prueba.despues.fallidas, ms: prueba.despues.duracion_ms, salida: prueba.despues.salida },
        copia_de_trabajo: prueba.copia,
        intentos_usados: parche.intentos + 1,
        intentos_maximos: MAX_INTENTOS,
        nota: 'Tu repositorio no se tocó: todo corrió en la copia aislada.',
      };
    },
  });

  push({
    module: 'parche',
    name: 'parche_aplicar',
    title: 'Aplicar el parche (requiere aprobación humana)',
    description:
      'Aplica el parche al repositorio real con respaldo previo de cada archivo afectado. Exige una aprobación humana vigente (AP-xxxx) resuelta por CLI/panel cuya huella corresponda EXACTAMENTE a este parche: si el parche cambió después de aprobarse, se niega. Queda registrado en la auditoría y se puede deshacer con reparacion_revertir.',
    destructive: true,
    inputSchema: {
      parche_id: z.string().describe('Id del parche (PR-xxxxxx).'),
      aprobacion_id: z.string().describe('Id de la aprobación humana (AP-xxxxxx) que autoriza este parche exacto.'),
      forzar_sin_prueba: z.boolean().optional().describe('Aplicar aunque el parche no se haya probado (por defecto false; úsalo solo si sabes lo que haces).'),
    },
    handler: async (args: { parche_id: string; aprobacion_id: string; forzar_sin_prueba?: boolean }) => {
      const parche = obtenerParche(ctx.db, args.parche_id);
      if (!parche) throw new Error(`No existe el parche ${args.parche_id}.`);
      if (parche.estado === 'aplicado') throw new Error(`El parche ${parche.id} ya está aplicado.`);
      if (parche.estado === 'revertido') throw new Error(`El parche ${parche.id} fue revertido; propón uno nuevo en vez de reaplicar el viejo.`);
      if (!parche.prueba && !args.forzar_sin_prueba) {
        throw new Error(`El parche ${parche.id} no se ha probado. Corre parche_probar primero (o usa forzar_sin_prueba).`);
      }
      if (parche.prueba?.veredicto === 'empeora') {
        throw new Error(`El parche ${parche.id} EMPEORÓ las pruebas; no se aplica. Corrige el cambio o descártalo.`);
      }
      const permiso = exigirAprobacion(ctx, args.aprobacion_id, parche.huella);
      if (!permiso.permitido) throw new Error(permiso.motivo);

      const { archivos, respaldo } = await aplicarParcheReal(ctx, parche);
      actualizarParche(ctx.db, parche.id, {
        estado: 'aplicado',
        aplicado_en: new Date().toISOString(),
        respaldo,
      });
      await ctx.audit.registrar({
        tipo: 'parche_aplicado',
        cliente: ctx.apiKey?.nombre ?? 'local',
        herramienta: 'parche_aplicar',
        detalle: `${parche.id} · ${archivos.join(', ')} · aprobación ${args.aprobacion_id}`,
      });
      return {
        id: parche.id,
        aplicado: true,
        archivos: [...new Set(archivos)],
        respaldo,
        aprobacion_usada: args.aprobacion_id,
        deshacer: `reparacion_revertir con parche_id="${parche.id}" (o node dist/cli.js revertir ${parche.id}).`,
        siguiente_paso: 'Vuelve a correr tus pruebas en el repositorio para confirmar en condiciones reales.',
      };
    },
  });

  push({
    module: 'parche',
    name: 'reparacion_revertir',
    title: 'Deshacer un parche aplicado',
    description:
      'Restaura los archivos desde el respaldo que se tomó al aplicar el parche. Deshacer NUNCA requiere aprobación (por eso existe): revierte con un comando, deja el rastro en la auditoría y no borra la copia de pruebas.',
    destructive: true,
    inputSchema: {
      parche_id: z.string().describe('Id del parche aplicado (PR-xxxxxx).'),
    },
    handler: async (args: { parche_id: string }) => {
      const parche = obtenerParche(ctx.db, args.parche_id);
      if (!parche) throw new Error(`No existe el parche ${args.parche_id}.`);
      const { archivos_restaurados } = await deshacerParche(ctx, parche);
      return {
        id: parche.id,
        revertido: true,
        archivos_restaurados,
        respaldo_conservado: parche.respaldo,
        nota: 'El respaldo se conserva por si necesitas comparar antes/después.',
      };
    },
  });

  push({
    module: 'parche',
    name: 'reparaciones_listar',
    title: 'Historial de reparaciones',
    description:
      'Lista los parches con su estado (propuesto, probado, aplicado, revertido), huella, archivos y veredicto de las pruebas. Sirve para saber qué se arregló, qué falta aprobar y qué se deshizo.',
    readOnly: true,
    inputSchema: {
      estado: z.enum(['propuesto', 'probado', 'aplicado', 'revertido']).optional().describe('Filtrar por estado.'),
      limite: z.number().int().min(1).max(100).optional().describe('Máximo a listar (por defecto 20).'),
    },
    handler: async (args: { estado?: 'propuesto' | 'probado' | 'aplicado' | 'revertido'; limite?: number }) => {
      const parches = listarParches(ctx.db, { estado: args.estado, limite: args.limite ?? 20 });
      return {
        total: parches.length,
        parches: parches.map(resumenParche),
        detalle: parches.slice(0, 5).map((p) => ({ id: p.id, diff: p.diff })),
        guardarrailes: {
          max_intentos_por_parche: MAX_INTENTOS,
          aplicar_requiere: 'aprobación humana (AP-xxxx) con la huella del parche',
          revertir_requiere: 'nada: deshacer siempre está permitido',
        },
      };
    },
  });
}
