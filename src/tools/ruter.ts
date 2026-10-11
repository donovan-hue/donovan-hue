/**
 * Módulo «ruter» (fase 4D) — enrutador con coste por resultado útil.
 *
 *   · modelo_elegir      → cascada de modelos según la tarea (barato primero)
 *   · modelo_resultado   → registra si la tarea SIRVIÓ (pruebas verdes, parche vivo)
 *   · coste_comparar     → coste por tarea exitosa, medido con tus datos
 *   · politica_modelos   → presupuesto y reglas por tipo de tarea
 *
 * La calidad no se estima por benchmark: se mide con hechos del taller.
 */
import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import {
  sugerirModelo, registrarResultado, compararCostes, leerCatalogo, leerPolitica,
  guardarPolitica, idTarea, type PoliticaTipo,
} from '../lib/ruter.js';

export function ruterToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'ruter',
    name: 'modelo_elegir',
    title: 'Elegir modelo para una tarea',
    description:
      'Recomienda qué modelo usar para una tarea y la cascada de escalado: empieza por el más barato que alcance y sube si falla. Tiene en cuenta tipo de tarea, número de archivos, criticidad, privacidad (¿el código puede salir de tu máquina?) y tu historial real de resultados. Con tarea_id puedes enlazar después el resultado.',
    readOnly: true,
    inputSchema: {
      descripcion: z.string().min(4).describe('Qué hay que hacer (ej. "renombrar variables en 3 archivos", "migrar el esquema de la base").'),
      tipo: z.string().optional().describe('Categoría de trabajo (ej. refactor, pruebas, documentacion, migracion, seguridad).'),
      archivos_tocados: z.number().int().min(0).optional().describe('Cuántos archivos se van a tocar (a más contexto, más nivel).'),
      criticidad: z.enum(['baja', 'media', 'alta']).optional().describe('alta = dinero, seguridad o datos: no se ahorra aquí.'),
      privacidad: z.enum(['puede_salir', 'no_sale']).optional().describe('no_sale = el contenido no puede salir de la máquina (solo modelos locales).'),
      tarea_id: z.string().optional().describe('Id de tarea para enlazar el resultado; si lo omites se sugiere uno.'),
    },
    handler: async (args: { descripcion: string; tipo?: string; archivos_tocados?: number; criticidad?: 'baja' | 'media' | 'alta'; privacidad?: 'puede_salir' | 'no_sale'; tarea_id?: string }) => {
      const politica = await leerPolitica(ctx);
      const tipo = args.tipo ?? 'general';
      const regla = politica[tipo];
      if (regla?.solo_local && !args.privacidad) args.privacidad = 'no_sale';
      const s = await sugerirModelo(ctx, args);
      const advertencias = [...s.advertencias];
      let cascada = s.cascada;
      if (regla?.presupuesto_max !== undefined) {
        const catalogo = await leerCatalogo(ctx);
        cascada = cascada.filter((c) => (catalogo.find((m) => m.id === c.id)?.coste_relativo ?? 0) <= regla.presupuesto_max!);
        if (cascada.length === 0) advertencias.push(`La política de "${tipo}" limita el coste a ${regla.presupuesto_max} y ningún candidato cabe: ajusta la política o la escala del catálogo.`);
      }
      if (regla?.modelos_preferidos?.length) {
        advertencias.push(`La política de "${tipo}" prefiere: ${regla.modelos_preferidos.join(', ')}.`);
      }
      return {
        ...s,
        cascada,
        tarea_id: args.tarea_id ?? idTarea(),
        politica_aplicada: regla ? { tipo, ...regla } : undefined,
        advertencias,
        despues: 'Al terminar, registra si sirvió: modelo_resultado {tarea_id, modelo, exito:true, evidencia:"132/132 pruebas verdes"}.',
      };
    },
  });

  push({
    module: 'ruter',
    name: 'modelo_resultado',
    title: 'Registrar el resultado real',
    description:
      'Guarda si la tarea SIRVIÓ: pruebas en verde, parche no revertido, respuesta aceptada. Este es el dato que convierte el consejo en medición de coste por resultado útil. La evidencia no es opcional: una afirmación sin prueba no cuenta.',
    inputSchema: {
      tarea_id: z.string().describe('Id de la tarea (el que devolvió modelo_elegir).'),
      tipo: z.string().describe('Categoría de la tarea (la misma con la que elegiste el modelo).'),
      modelo: z.string().describe('Modelo que la resolvió (id del catálogo).'),
      exito: z.boolean().describe('¿Sirvió? (pruebas verdes / cambio aceptado / no revertido).'),
      evidencia: z.string().min(4).describe('La prueba: "132/132 pruebas en verde", "parche PR-abc aplicado y no revertido", etc.'),
      coste: z.number().min(0).optional().describe('Coste en unidades relativas (0 para local).'),
      duracion_ms: z.number().int().min(0).optional(),
    },
    handler: async (args: { tarea_id: string; tipo: string; modelo: string; exito: boolean; evidencia: string; coste?: number; duracion_ms?: number }) => {
      registrarResultado(ctx, {
        tarea: args.tarea_id,
        tipo: args.tipo,
        modelo: args.modelo,
        exito: args.exito,
        coste: args.coste,
        duracion_ms: args.duracion_ms,
        evidencia: args.evidencia,
      });
      const c = compararCostes(ctx, args.tipo);
      return {
        registrado: true,
        tarea_id: args.tarea_id,
        mensaje: args.exito ? 'Resultado útil registrado (tarea terminada).' : 'Fracaso registrado: cuenta igual — los fracasos son los que mueven la cascada.',
        coste_por_exito_actual: c.por_modelo,
        recomendacion: c.recomendacion,
      };
    },
  });

  push({
    module: 'ruter',
    name: 'coste_comparar',
    title: 'Coste por resultado útil',
    description:
      'Compara modelos con TUS datos: tareas, tasa de éxito y coste por tarea exitosa (no por token). Muestra si la muestra ya alcanza para decidir y cuál modelo conviene hoy para cada tipo de tarea.',
    readOnly: true,
    inputSchema: {
      tipo: z.string().optional().describe('Filtrar por tipo de tarea (ej. refactor, pruebas, documentacion).'),
    },
    handler: async (args: { tipo?: string }) => {
      const c = compararCostes(ctx, args.tipo);
      const catalogo = await leerCatalogo(ctx);
      return {
        ...c,
        catalogo: catalogo.map((m) => ({ id: m.id, nombre: m.nombre, donde: m.donde, fuerza: m.fuerza, coste_relativo: m.coste_relativo })),
        como_se_mide: 'coste_por_exito = coste acumulado ÷ tareas con resultado útil (pruebas verdes o cambio no revertido).',
        nota: 'Los costes son unidades relativas editables en data/ruter/modelos.json: pon tus precios reales y la comparación se vuelve dinero.',
      };
    },
  });

  push({
    module: 'ruter',
    name: 'politica_modelos',
    title: 'Política de modelos por tipo de tarea',
    description:
      'Define reglas por tipo de tarea: presupuesto máximo (en unidades del catálogo), modelos preferidos y si el contenido debe quedarse local. Se guardan en data/ruter/politica.json y afectan a modelo_elegir.',
    inputSchema: {
      tipo: z.string().min(2).describe('Categoría a la que aplica la regla (ej. "seguridad", "documentacion").'),
      presupuesto_max: z.number().min(0).optional().describe('Coste máximo por tarea para este tipo.'),
      modelos_preferidos: z.array(z.string()).optional().describe('Ids del catálogo que prefieres para este tipo.'),
      solo_local: z.boolean().optional().describe('true = el contenido de este tipo nunca sale de la máquina.'),
      nota: z.string().optional(),
      eliminar: z.boolean().optional().describe('true = borra la regla de este tipo.'),
    },
    handler: async (args: { tipo: string; presupuesto_max?: number; modelos_preferidos?: string[]; solo_local?: boolean; nota?: string; eliminar?: boolean }) => {
      const actuales = await leerPolitica(ctx);
      const defineAlgo =
        args.presupuesto_max !== undefined ||
        args.modelos_preferidos !== undefined ||
        args.solo_local !== undefined ||
        args.nota !== undefined;
      // Solo con `tipo` la llamada es una consulta: no borra la regla existente.
      if (!args.eliminar && !defineAlgo) {
        return { guardado: false, tipo: args.tipo, politica: actuales, regla: actuales[args.tipo] ?? null };
      }
      if (args.eliminar) {
        delete actuales[args.tipo];
      } else {
        const regla: PoliticaTipo = {
          presupuesto_max: args.presupuesto_max,
          modelos_preferidos: args.modelos_preferidos,
          solo_local: args.solo_local,
          nota: args.nota,
        };
        actuales[args.tipo] = regla;
      }
      const archivo = await guardarPolitica(ctx, actuales);
      return { guardado: !args.eliminar, tipo: args.tipo, archivo, politica: actuales };
    },
  });
}
