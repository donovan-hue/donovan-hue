import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { ServerCtx } from './context.js';
import type { ToolDef, ToolInfo } from './lib/result.js';
import { registerModuloTools, registerTool } from './lib/result.js';
import { negocioToolDefs } from './tools/negocio.js';
import { documentosToolDefs } from './tools/documentos.js';
import { devopsToolDefs } from './tools/devops.js';
import { analisisToolDefs } from './tools/analisis.js';
import { facturacionToolDefs } from './tools/facturacion.js';
import { tallerToolDefs } from './tools/taller.js';
import { centinelaToolDefs } from './tools/centinela.js';
import { parcheToolDefs } from './tools/parche.js';
import { orquestaToolDefs } from './tools/orquesta.js';
import { ruterToolDefs } from './tools/ruter.js';
import { SERVER_NAME, SERVER_TITLE, VERSION } from './version.js';
import { describirScopes } from './auth/politica.js';

export interface ServerSuite {
  server: McpServer;
  catalog: ToolInfo[];
  modulosInfo: { modulo: string; registradas: number; omitidasPorPermisos: number }[];
}

/** Definiciones completas de las herramientas, construidas por módulo. */
export function toolDefsDeTodosLosModulos(ctx: ServerCtx): { modulo: string; defs: ToolDef[] }[] {
  const negocio: ToolDef[] = [];
  const facturacion: ToolDef[] = [];
  const documentos: ToolDef[] = [];
  const analisis: ToolDef[] = [];
  const devops: ToolDef[] = [];
  const taller: ToolDef[] = [];
  const centinela: ToolDef[] = [];
  const parche: ToolDef[] = [];
  const orquesta: ToolDef[] = [];
  const ruter: ToolDef[] = [];
  negocioToolDefs(negocio, ctx);
  facturacionToolDefs(facturacion, ctx);
  documentosToolDefs(documentos, ctx);
  analisisToolDefs(analisis, ctx);
  devopsToolDefs(devops, ctx);
  tallerToolDefs(taller, ctx);
  centinelaToolDefs(centinela, ctx);
  parcheToolDefs(parche, ctx);
  orquestaToolDefs(orquesta, ctx);
  ruterToolDefs(ruter, ctx);
  return [
    { modulo: 'negocio', defs: negocio },
    { modulo: 'facturacion', defs: facturacion },
    { modulo: 'documentos', defs: documentos },
    { modulo: 'analisis', defs: analisis },
    { modulo: 'devops', defs: devops },
    { modulo: 'taller', defs: taller },
    { modulo: 'centinela', defs: centinela },
    { modulo: 'parche', defs: parche },
    { modulo: 'orquesta', defs: orquesta },
    { modulo: 'ruter', defs: ruter },
  ];
}

/**
 * Construye una instancia del servidor MCP con los 5 módulos de herramientas,
 * filtrando el catálogo según los permisos del cliente autenticado (si lo hay).
 */
export function buildServer(ctx: ServerCtx): ServerSuite {
  const identidad = ctx.apiKey;
  const scopesTexto = identidad ? describirScopes(identidad.scopes).join(', ') : 'acceso local completo';

  const server = new McpServer(
    { name: SERVER_NAME, version: VERSION, title: SERVER_TITLE },
    {
      instructions: [
        `${SERVER_TITLE} (${SERVER_NAME}) expone 10 módulos de herramientas:`,
        '- negocio: inventario, pedidos y cotizaciones con IVA/retenciones de México.',
        '- facturacion: CFDI 4.0 (borrador + XML sin sellar), validación de datos fiscales, catálogos del SAT y cálculo de retenciones.',
        '- documentos: lectura de archivos, resumen de texto, extracción de datos (RFC, correo, montos) y plantillas.',
        '- analisis: perfilar y agregar CSV, estadística, correlación y series de tiempo.',
        '- devops: estado de repos git, resumen de proyectos, health checks HTTP y análisis de logs.',
        '- taller: mapa vivo del proyecto (gemelo digital), impacto de cambios, diagnóstico de rescate de proyectos abandonados y pendientes ordenados por impacto.',
        '- centinela: escaneo de seguridad (secretos, configs peligrosas, rutas sin protección, dependencias), plan de corrección comprobable y verificación contra línea base.',
        '- parche: reparar con red de seguridad (diagnóstico con evidencia, parche revisable, pruebas antes/después en copia aislada, aplicación con aprobación humana y reversión en un comando).',
        '- orquesta: flujos de dev con bitácora (verificar despliegue, respaldo con rotación, auditoría de dependencias y seguridad, preparar release, post-mortem), más flujos propios, con dry-run y aprobaciones.',
        '- ruter: elección de modelo por tarea con coste por resultado útil medido en tu propio taller (pruebas verdes, parches vivos).',
        identidad
          ? `Cliente autenticado: "${identidad.nombre}". Permisos: ${scopesTexto}. Solo verás las herramientas permitidas por tus scopes.`
          : 'Sin autenticación (uso local o AUTH_MODE=off).',
        'Reglas: verifica con las herramientas de consulta antes de modificar datos; toda operación destructiva queda registrada en la bitácora de auditoría.',
      ].join('\n'),
    },
  );

  const catalog: ToolInfo[] = [];
  const meta = { ctx };
  const modulosInfo: ServerSuite['modulosInfo'] = [];

  // Herramienta de descubrimiento: siempre disponible (módulo core).
  registerTool(
    server,
    catalog,
    {
      module: 'core',
      name: 'mcp_suite_info',
      title: 'Información del servidor MCP',
      description:
        'Devuelve la versión del servidor, el catálogo de herramientas disponibles para tu clave, la configuración activa (transporte, datos, tasas), tus permisos y los recursos MCP. Úsala para descubrir qué puedes hacer en esta sesión.',
      readOnly: true,
      inputSchema: {
        detallado: z.boolean().optional().describe('Si es true, incluye la descripción completa de cada herramienta.'),
      },
      handler: async (args: { detallado?: boolean }) => ({
        nombre: SERVER_NAME,
        version: VERSION,
        transporte: ctx.config.transport,
        directorio_datos: ctx.config.dataDir,
        iva_pct: ctx.config.ivaPct,
        repositorios_autorizados: ctx.config.allowedGitRoots,
        autenticacion: ctx.config.authMode,
        cliente: identidad ? { nombre: identidad.nombre, scopes: identidad.scopes, permisos: describirScopes(identidad.scopes) } : null,
        modulos: [...new Set(catalog.map((t) => t.module))],
        total_herramientas_disponibles: catalog.length,
        herramientas: catalog.map((t) =>
          args.detallado ? { ...t } : { nombre: t.name, modulo: t.module, titulo: t.title, solo_lectura: t.readOnly },
        ),
        recursos_disponibles: [`${SERVER_NAME}://config/actual`, `${SERVER_NAME}://catalogo/tools`],
      }),
    },
    meta,
  );

  registerTool(
    server,
    catalog,
    {
      module: 'core',
      name: 'auditoria_consultar',
      title: 'Consultar bitácora de auditoría',
      description:
        'Lee la bitácora de auditoría (append-only) del servidor: operaciones destructivas ejecutadas, intentos de autenticación fallidos y bloqueos por límite de tasa. Filtra por tipo de evento, herramienta, cliente o fecha. Úsala para responder "¿quién cambió qué y cuándo?".',
      readOnly: true,
      inputSchema: {
        tipo: z
          .enum(['tool_destructiva', 'auth_fallo', 'rate_limit', 'clave_creada', 'clave_revocada'])
          .optional()
          .describe('Tipo de evento a filtrar.'),
        herramienta: z.string().optional().describe('Nombre exacto de la herramienta, ej. "inventario_actualizar".'),
        cliente: z.string().optional().describe('Filtro parcial por nombre de clave/cliente.'),
        desde: z.string().optional().describe('Solo eventos posteriores a esta fecha ISO, ej. "2026-10-01T00:00:00.000Z".'),
        limite: z.number().int().min(1).max(500).optional().describe('Máximo de eventos a devolver (por defecto 50, más recientes primero).'),
      },
      handler: async (args: { tipo?: any; herramienta?: string; cliente?: string; desde?: string; limite?: number }) => {
        const eventos = await ctx.audit.leer({
          tipo: args.tipo,
          herramienta: args.herramienta,
          cliente: args.cliente,
          desde: args.desde,
          limite: args.limite ?? 50,
        });
        return {
          total_devuelto: eventos.length,
          resumen_por_tipo: await ctx.audit.resumen(),
          archivo: ctx.audit.ruta,
          eventos: [...eventos].reverse(), // más recientes primero
        };
      },
    },
    meta,
  );

  for (const { modulo, defs } of toolDefsDeTodosLosModulos(ctx)) {
    const r = registerModuloTools(server, catalog, defs, meta, identidad);
    modulosInfo.push({ modulo, registradas: r.registradas, omitidasPorPermisos: r.omitidasPorPermisos });
  }

  // Recursos MCP: contenido que el cliente puede leer directamente.
  server.registerResource(
    'config-actual',
    `${SERVER_NAME}://config/actual`,
    {
      title: 'Configuración activa del servidor',
      description: 'Transporte, datos, tasas, autenticación y rutas autorizadas en formato JSON.',
      mimeType: 'application/json',
    },
    async (uri) => ({
      contents: [
        {
          uri: uri.href,
          mimeType: 'application/json',
          text: JSON.stringify(
            {
              transporte: ctx.config.transport,
              puerto: ctx.config.transport === 'http' ? ctx.config.port : null,
              directorio_datos: ctx.config.dataDir,
              iva_pct: ctx.config.ivaPct,
              autenticacion: ctx.config.authMode,
              rate_limit_rpm: ctx.config.rateLimitRpm,
              repositorios_autorizados: ctx.config.allowedGitRoots,
              cliente: identidad ? { nombre: identidad.nombre, scopes: identidad.scopes } : null,
            },
            null,
            2,
          ),
        },
      ],
    }),
  );

  server.registerResource(
    'catalogo-tools',
    `${SERVER_NAME}://catalogo/tools`,
    {
      title: 'Catálogo de herramientas',
      description: 'Lista completa de herramientas disponibles para el cliente actual, con descripción, módulo y si son de solo lectura.',
      mimeType: 'application/json',
    },
    async (uri) => ({
      contents: [{ uri: uri.href, mimeType: 'application/json', text: JSON.stringify(catalog, null, 2) }],
    }),
  );

  // Prompts MCP: plantillas de conversación reutilizables.
  server.registerPrompt(
    'resumen_semanal_negocio',
    {
      title: 'Resumen semanal del negocio',
      description: 'Genera un reporte semanal: pedidos activos, inventario bajo mínimo y ventas por cliente.',
    },
    () => ({
      messages: [
        {
          role: 'user',
          content: {
            type: 'text',
            text:
              'Genera un resumen semanal del negocio usando las herramientas:\n' +
              '1. pedido_consultar (filtra pedidos activos: confirmado, en_produccion, enviado).\n' +
              '2. inventario_consultar con bajo_minimo=true.\n' +
              '3. dataset_agrupar sobre datasets/ventas_mensuales.csv (suma(venta_neta) por mes) para la tendencia.\n' +
              'Entrega: 3 bullets de estado, tabla de pedidos activos, alertas de inventario y 2 recomendaciones.',
          },
        },
      ],
    }),
  );

  server.registerPrompt(
    'diagnostico_produccion',
    {
      title: 'Diagnóstico de producción',
      description: 'Revisa el estado de un servicio en producción combinando logs, git y health check.',
      argsSchema: {
        servicio: z.string().describe('Nombre del servicio a diagnosticar.'),
        url: z.string().describe('URL del endpoint de salud, ej. https://api.miempresa.mx/health'),
        repo: z.string().optional().describe('Ruta local del repositorio del servicio (opcional).'),
        log: z.string().optional().describe('Ruta del archivo de logs (opcional).'),
      },
    },
    ({ servicio, url, repo, log }) => ({
      messages: [
        {
          role: 'user',
          content: {
            type: 'text',
            text:
              `Diagnostica el estado del servicio "${servicio}":\n` +
              `1. http_health_check en ${url}.\n` +
              (log ? `2. log_analizar sobre el archivo "${log}" (top errores y coincidencia del patrón de fallas).\n` : '') +
              (repo ? `3. git_log y git_status en ${repo} para ver cambios recientes sin desplegar.\n` : '') +
              'Concluye con: causa probable, evidencia y acción recomendada.',
          },
        },
      ],
    }),
  );

  return { server, catalog, modulosInfo };
}
