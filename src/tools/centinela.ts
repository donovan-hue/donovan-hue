/**
 * Módulo «centinela» (fase 4B) — seguridad preventiva con verificación.
 *
 *   · seguridad_escanear        → hallazgos priorizados + puntaje (y línea base)
 *   · seguridad_plan            → plan de corrección con pasos comprobables
 *   · seguridad_verificar       → demuestra qué se resolvió contra la línea base
 *   · dependencias_revisar      → foco en dependencias y scripts de instalación
 *   · aprobaciones_pendientes   → cola del gate de aprobación (visible al humano)
 *
 * El gate de aprobación es asimétrico a propósito: el modelo puede SOLICITAR una
 * aprobación y CONSULTAR si sigue válida, pero solo una persona la resuelve (CLI
 * o panel). Ninguna operación destructiva se ejecuta sin esa resolución.
 */
import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import {
  escanear, verificarContra, planDeCorreccion, informeMarkdown, planMarkdown, sello,
  type Escaneo,
} from '../lib/centinela.js';
import { resolverDirectorio } from '../lib/dir-permitido.js';
import { auditarPermisos, revisarDependenciasVulnerables } from '../lib/centinela-extra.js';
import {
  solicitarAprobacion, exigirAprobacion, listarAprobaciones, expirarVencidas, huellaDeContenido,
} from '../lib/aprobaciones.js';

function dirCentinela(ctx: ServerCtx): string {
  return path.join(ctx.config.dataDir, 'centinela');
}

async function guardarEscaneo(ctx: ServerCtx, escaneo: Escaneo): Promise<string> {
  const dir = dirCentinela(ctx);
  await fs.mkdir(dir, { recursive: true });
  const archivo = path.join(dir, `escaneo-${path.basename(escaneo.raiz)}-${sello()}.json`);
  await fs.writeFile(archivo, JSON.stringify(escaneo, null, 2), 'utf8');
  return archivo;
}

async function ultimoEscaneo(ctx: ServerCtx, proyecto: string): Promise<{ escaneo: Escaneo; archivo: string } | null> {
  const dir = dirCentinela(ctx);
  let archivos: string[];
  try {
    archivos = await fs.readdir(dir);
  } catch {
    return null;
  }
  const candidatos = archivos
    .filter((a) => a.startsWith(`escaneo-${proyecto}-`) && a.endsWith('.json'))
    .sort();
  const ultimo = candidatos[candidatos.length - 1];
  if (!ultimo) return null;
  const archivo = path.join(dir, ultimo);
  return { escaneo: JSON.parse(await fs.readFile(archivo, 'utf8')) as Escaneo, archivo };
}

function resumenEscaneo(escaneo: Escaneo) {
  return {
    raiz: escaneo.raiz,
    proyecto: escaneo.proyecto,
    puntaje: escaneo.puntaje,
    semaforo: escaneo.semaforo,
    resumen: escaneo.resumen,
    archivos_analizados: escaneo.archivos_analizados,
    total_hallazgos: escaneo.hallazgos.length,
    notas: escaneo.notas,
  };
}

export function centinelaToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'centinela',
    name: 'seguridad_escanear',
    title: 'Escaneo de seguridad del proyecto',
    description:
      'Analiza el código de un proyecto y reporta riesgos reales: secretos expuestos (llaves de AWS/Google/Stripe/IA, tokens, llaves privadas, contraseñas en código), configuraciones peligrosas (eval, SQL por concatenación, TLS sin verificar, credenciales por defecto, curl|sh), rutas sin noción de autenticación, dependencias con avisos conocidos e higiene de repositorio (.env, .gitignore). Devuelve puntaje 0-100 y semáforo. Los secretos se muestran redactados. Opcionalmente guarda el escaneo como línea base para verificar después. Respeta el archivo .centinela-ignore del proyecto.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS). Por defecto, la primera autorizada.'),
      guardar_linea_base: z.boolean().optional().describe('Guardar este escaneo como línea base en data/centinela/ (para seguridad_verificar).'),
      formato: z.enum(['resumen', 'completo', 'markdown']).optional().describe('resumen (por defecto), completo (con evidencia y recomendación por hallazgo) o markdown (informe).'),
      guardar_informe: z.boolean().optional().describe('Guardar el informe en Markdown dentro de data/centinela/.'),
    },
    handler: async (args: { directorio?: string; guardar_linea_base?: boolean; formato?: 'resumen' | 'completo' | 'markdown'; guardar_informe?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const escaneo = await escanear(raiz);
      const extra: Record<string, unknown> = {};
      if (args.guardar_linea_base) extra.linea_base = await guardarEscaneo(ctx, escaneo);
      if (args.guardar_informe) {
        const dir = dirCentinela(ctx);
        await fs.mkdir(dir, { recursive: true });
        const archivo = path.join(dir, `informe-${escaneo.proyecto}-${sello()}.md`);
        await fs.writeFile(archivo, informeMarkdown(escaneo), 'utf8');
        extra.informe = archivo;
      }
      const formato = args.formato ?? 'resumen';
      if (formato === 'markdown') return { ...resumenEscaneo(escaneo), ...extra, contenido: informeMarkdown(escaneo) };
      if (formato === 'completo') return { ...resumenEscaneo(escaneo), hallazgos: escaneo.hallazgos, ignorados: escaneo.ignorados, ...extra };
      return {
        ...resumenEscaneo(escaneo),
        ...extra,
        por_categoria: escaneo.hallazgos.reduce<Record<string, number>>((acc, h) => {
          acc[h.categoria] = (acc[h.categoria] ?? 0) + 1;
          return acc;
        }, {}),
        top_hallazgos: escaneo.hallazgos.slice(0, 8).map((h) => ({ regla: h.regla, severidad: h.severidad, archivo: `${h.archivo}:${h.linea}`, evidencia: h.evidencia })),
      };
    },
  });

  push({
    module: 'centinela',
    name: 'seguridad_plan',
    title: 'Plan de corrección de seguridad',
    description:
      'Convierte los hallazgos del escaneo en un plan de corrección priorizado (ahora / esta semana / cuando puedas) donde cada paso indica qué hacer, en qué archivos y —lo importante— CÓMO COMPROBAR que quedó resuelto. Opcionalmente guarda el plan en Markdown. Con solicitar_aplicacion=true registra una solicitud de aprobación para que una persona autorice aplicar el plan (se aprueba por CLI, nunca por el modelo); con aprobacion_id=AP-xxxx comprueba si esa autorización sigue siendo válida para el plan actual.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      guardar: z.boolean().optional().describe('Guardar el plan en Markdown dentro de data/centinela/.'),
      solicitar_aplicacion: z.boolean().optional().describe('Registrar la solicitud de aprobación humana para aplicar este plan (queda pendiente hasta que la resuelva una persona por CLI).'),
      aprobacion_id: z.string().optional().describe('Comprobar si la aprobación AP-… autoriza este plan (misma huella) antes de aplicarlo.'),
    },
    handler: async (args: { directorio?: string; guardar?: boolean; solicitar_aplicacion?: boolean; aprobacion_id?: string }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const escaneo = await escanear(raiz);
      const plan = planDeCorreccion(escaneo);
      const huellaDelPlan = huellaDeContenido(planMarkdown(plan, escaneo));
      const extra: Record<string, unknown> = {};
      if (args.guardar) {
        const dir = dirCentinela(ctx);
        await fs.mkdir(dir, { recursive: true });
        const archivo = path.join(dir, `plan-${escaneo.proyecto}-${sello()}.md`);
        await fs.writeFile(archivo, planMarkdown(plan, escaneo), 'utf8');
        extra.guardado_en = archivo;
      }
      let gate: Record<string, unknown> | undefined;
      if (args.solicitar_aplicacion) {
        const ap = await solicitarAprobacion(ctx, {
          tipo: 'aplicar_plan_seguridad',
          titulo: `Aplicar el plan de seguridad de ${escaneo.proyecto} (${plan.length} paso(s), puntaje ${escaneo.puntaje})`,
          detalle: `Plan derivado del escaneo ${escaneo.generado_en}. Hallazgos críticos: ${escaneo.resumen.critica}. Si el plan cambia después de aprobarlo, la huella deja de coincidir y la autorización caduca.`,
          huella: huellaDelPlan,
        });
        gate = {
          aprobacion: ap.id,
          estado: ap.estado,
          como_aprobar: `node dist/cli.js aprobar ${ap.id}`,
          como_rechazar: `node dist/cli.js rechazar ${ap.id} --motivo "…"`,
          nota: 'La solicitud queda visible en aprobaciones_pendientes y en `node dist/cli.js aprobaciones`. Nadie puede aplicarla hasta que una persona la apruebe.',
        };
      }
      if (args.aprobacion_id) {
        const r = exigirAprobacion(ctx, args.aprobacion_id, huellaDelPlan);
        gate = {
          ...(gate ?? {}),
          aprobacion_consultada: args.aprobacion_id,
          autorizado_para_aplicar: r.permitido,
          motivo: r.motivo ?? 'La aprobación corresponde a este plan exacto.',
        };
      }
      return {
        proyecto: escaneo.proyecto,
        puntaje: escaneo.puntaje,
        semaforo: escaneo.semaforo,
        total_pasos: plan.length,
        pasos: plan,
        como_verificar_el_resultado: 'Ejecuta seguridad_verificar tras aplicar cada paso: compara contra la línea base y te dice exactamente qué quedó resuelto y qué sigue pendiente.',
        ...extra,
        ...(gate ? { gate_de_aprobacion: gate } : {}),
      };
    },
  });

  push({
    module: 'centinela',
    name: 'seguridad_verificar',
    title: 'Verificar que la corrección funcionó',
    description:
      'Vuelve a escanear el proyecto y lo compara contra la última línea base guardada (o contra un archivo de escaneo indicado): lista los hallazgos RESUELTOS, los que permanecen y los NUEVOS, con el cambio de puntaje. Es la prueba de que una corrección de seguridad realmente funcionó, no una suposición.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      contra: z.string().optional().describe('Ruta de un archivo de escaneo (JSON) para comparar; si se omite, usa la última línea base guardada en data/centinela/.'),
      guardar_actual: z.boolean().optional().describe('Guardar el escaneo actual como nueva línea base (por defecto true para poder encadenar verificaciones).'),
    },
    handler: async (args: { directorio?: string; contra?: string; guardar_actual?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const actual = await escanear(raiz);
      let previo: Escaneo | undefined;
      let origen = args.contra;
      if (args.contra) {
        previo = JSON.parse(await fs.readFile(path.resolve(args.contra), 'utf8')) as Escaneo;
      } else {
        const ultimo = await ultimoEscaneo(ctx, path.basename(raiz));
        if (ultimo) {
          previo = ultimo.escaneo;
          origen = ultimo.archivo;
        }
      }
      if (!previo) {
        throw new Error(
          'No hay línea base con la que comparar. Primero corre seguridad_escanear con guardar_linea_base=true (o pasa "contra" con un escaneo previo).',
        );
      }
      const verificacion = verificarContra(actual, previo);
      const extra: Record<string, unknown> = {};
      if (args.guardar_actual !== false) extra.nueva_linea_base = await guardarEscaneo(ctx, actual);
      return {
        ...verificacion,
        contra: origen,
        resueltos: verificacion.resueltos.map((h) => ({ regla: h.regla, severidad: h.severidad, archivo: `${h.archivo}:${h.linea}`, evidencia: h.evidencia })),
        nuevos: verificacion.nuevos.map((h) => ({ regla: h.regla, severidad: h.severidad, archivo: `${h.archivo}:${h.linea}`, evidencia: h.evidencia })),
        ...extra,
        veredicto_final:
          actual.semaforo === 'verde'
            ? 'SEMÁFORO VERDE: la corrección se puede dar por buena con las reglas actuales.'
            : verificacion.veredicto === 'mejoro'
              ? 'Avance confirmado, pero aún hay hallazgos pendientes: revisa los "permanecen".'
              : 'Sin mejora demostrable todavía. Revisa los pasos del plan y vuelve a verificar.',
      };
    },
  });

  push({
    module: 'centinela',
    name: 'aprobaciones_pendientes',
    title: 'Cola de aprobaciones humanas',
    description:
      'Muestra las solicitudes de aprobación en espera (por ejemplo, aplicar un plan de seguridad) con su identificador, qué autorizan y cómo resolverlas. Las aprobaciones las resuelve SIEMPRE una persona por CLI o panel; un agente no puede autorizarse a sí mismo. Útil para preguntar al humano qué está esperando.',
    readOnly: true,
    inputSchema: {
      incluir_resueltas: z.boolean().optional().describe('Incluir también las ya aprobadas, rechazadas o expiradas (últimas 20).'),
      limite: z.number().int().min(1).max(100).optional().describe('Máximo de solicitudes a listar (por defecto 20).'),
    },
    handler: async (args: { incluir_resueltas?: boolean; limite?: number }) => {
      expirarVencidas(ctx);
      const pendientes = listarAprobaciones(ctx, { estado: 'pendiente', limite: args.limite ?? 20 });
      const recientes = args.incluir_resueltas
        ? listarAprobaciones(ctx, { limite: 20 }).filter((a) => a.estado !== 'pendiente')
        : [];
      const limpiar = (a: (typeof pendientes)[number]) => ({
        id: a.id,
        tipo: a.tipo,
        titulo: a.titulo,
        estado: a.estado,
        solicitada_en: a.solicitada_en,
        solicitada_por: a.solicitada_por,
        detalle: a.detalle,
        como_resolver: {
          aprobar: `node dist/cli.js aprobar ${a.id}`,
          rechazar: `node dist/cli.js rechazar ${a.id} --motivo "…"`,
          o: 'node dist/cli.js aprobaciones (vista interactiva del administrador)',
        },
      });
      return {
        pendientes: pendientes.map(limpiar),
        total_pendientes: pendientes.length,
        ...(recientes.length ? { resueltas_recientes: recientes.map(limpiar) } : {}),
        recuerda: 'Nada se aplica sin la aprobación de una persona: el modelo no puede resolver sus propias solicitudes.',
      };
    },
  });

  push({
    module: 'centinela',
    name: 'permisos_auditar',
    title: 'Auditar protección real de las rutas',
    description:
      'Va más allá del aviso genérico: revisa CÓMO queda protegida cada ruta del proyecto — middleware en la propia línea, guard global montado antes, o consulta de sesión/token dentro del archivo — y solo marca las que de verdad quedan descubiertas, con su archivo:línea y la corrección sugerida. Distingue las rutas que parecen públicas a propósito (health, login, webhooks).',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
    },
    handler: async (args: { directorio?: string }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const a = await auditarPermisos(raiz);
      return {
        proyecto: path.basename(raiz),
        total_rutas: a.rutas.length,
        resumen: a.resumen,
        descubiertas: a.descubiertas,
        recomendaciones: a.recomendaciones,
        todas: a.rutas,
        nota: a.nota,
      };
    },
  });

  push({
    module: 'centinela',
    name: 'dependencias_vulnerables',
    title: 'Dependencias con avisos reales (npm audit + caché)',
    description:
      'Consulta los avisos REALES de tus dependencias con npm audit, guarda el resultado en caché local por contenido de package.json/lockfile (24 h) y lo complementa con la tabla local de avisos frecuentes. Si no hay red o falta el lockfile, lo dice y sigue funcionando con la tabla local: nunca deja de responder.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
      forzar: z.boolean().optional().describe('Ignorar la caché y volver a consultar.'),
    },
    handler: async (args: { directorio?: string; forzar?: boolean }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const r = await revisarDependenciasVulnerables(ctx, raiz, { forzar: args.forzar });
      return {
        proyecto: path.basename(raiz),
        ...r,
        como_interpretarlo: r.total === 0 && r.fuente === 'npm-audit'
          ? 'Sin avisos reportados por la fuente primaria. La tabla local sigue listada como segunda opinión.'
          : 'Atiende primero por severidad y confirma con tus pruebas después de actualizar.',
      };
    },
  });

  push({
    module: 'centinela',
    name: 'dependencias_revisar',
    title: 'Revisión de dependencias y scripts',
    description:
      'Revisión enfocada de dependencias: versiones con avisos conocidos (tabla local, sin conexión), dependencias sin versión fija, scripts de instalación peligrosos (curl | sh), ausencia de lockfile y conteo por tipo. Útil antes de publicar o desplegar.',
    readOnly: true,
    inputSchema: {
      directorio: z.string().optional().describe('Ruta del proyecto (debe estar en ALLOWED_GIT_ROOTS).'),
    },
    handler: async (args: { directorio?: string }) => {
      const raiz = resolverDirectorio(ctx, args.directorio);
      const escaneo = await escanear(raiz);
      const deps = escaneo.hallazgos.filter((h) => h.categoria === 'dependencia');
      let paquete: any = {};
      try {
        paquete = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
      } catch {
        throw new Error(`No encontré package.json en ${raiz}.`);
      }
      const cuenta = (o: unknown) => Object.keys((o as object) ?? {}).length;
      const tieneLock = await fs.access(path.join(raiz, 'package-lock.json')).then(() => true).catch(() => false);
      return {
        proyecto: paquete.name ?? path.basename(raiz),
        dependencias: cuenta(paquete.dependencies),
        dev_dependencias: cuenta(paquete.devDependencies),
        lockfile: tieneLock,
        scripts: Object.keys(paquete.scripts ?? {}).length,
        avisos_locales: deps.map((h) => ({ regla: h.regla, severidad: h.severidad, evidencia: h.evidencia, recomendacion: h.recomendacion })),
        total_avisos: deps.length,
        alcance_de_la_revision:
          'Tabla local de avisos frecuentes (sin conexión) + patrones de riesgo en scripts. Para una revisión exhaustiva con la base completa de vulnerabilidades, ejecuta npm audit (requiere red) y complementa.',
      };
    },
  });
}
