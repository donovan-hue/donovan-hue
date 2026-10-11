import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import type { IdentidadCliente, ServerCtx } from './context.js';
import { buildServer } from './server.js';
import { projectRoot } from './config.js';
import { obtenerEstadoVivo } from './lib/taller-vivo.js';
import { SERVER_NAME, SERVER_TITLE, VERSION } from './version.js';
import { log } from './logger.js';
import { contarClavesActivas, listarClaves, verificarClave } from './auth/keys.js';
import { listarAprobaciones, resolverAprobacion } from './lib/aprobaciones.js';
import { listarParches } from './lib/parche.js';
import { listarEmpresas, empresaActivaDetalle, compararEmpresas } from './lib/empresas.js';

interface SesionAutenticada {
  ctx: ServerCtx;
  identidad?: IdentidadCliente;
}

function cors(ctx: ServerCtx, res: http.ServerResponse): void {
  res.setHeader('Access-Control-Allow-Origin', ctx.config.corsOrigin);
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Accept, Mcp-Session-Id, Last-Event-ID, Authorization');
  res.setHeader('Access-Control-Expose-Headers', 'Mcp-Session-Id, X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After');
  res.setHeader('Vary', 'Origin');
  if (ctx.config.tlsCert && ctx.config.tlsKey) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }
}

function ipDe(req: http.IncomingMessage, confiarEnProxy: boolean): string {
  if (confiarEnProxy) {
    const reenviada = req.headers['x-forwarded-for'];
    const primera = Array.isArray(reenviada) ? reenviada[0] : reenviada;
    if (primera) return primera.split(',')[0]!.trim();
  }
  return req.socket.remoteAddress ?? 'desconocida';
}

function extraerClave(req: http.IncomingMessage): string | undefined {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) return undefined;
  const valor = header.slice(7).trim();
  return valor.length > 0 ? valor : undefined;
}

/**
 * Resuelve la identidad de la petición. Reglas:
 * - Sin clave: se permite solo si AUTH_MODE != "required" (modo local/desarrollo).
 * - Clave bootstrap (MCP_API_KEYS): acceso total, sin tocar disco.
 * - Clave emitida: se valida contra data/api-keys.json (hash + vigencia).
 */
async function autenticar(ctx: ServerCtx, req: http.IncomingMessage): Promise<{ ok: true; sesion: SesionAutenticada } | { ok: false; status: number; mensaje: string }> {
  const clave = extraerClave(req);
  const ip = ipDe(req, ctx.config.trustProxy);

  if (!clave) {
    if (ctx.config.authMode === 'required') {
      return {
        ok: false,
        status: 401,
        mensaje:
          'Se requiere autenticación: envía "Authorization: Bearer <api key>". ' +
          'Genera una clave con: node dist/cli.js keys create --nombre mi-cliente --scopes negocio,analisis',
      };
    }
    return { ok: true, sesion: { ctx } };
  }

  const idxBootstrap = ctx.config.apiKeys.indexOf(clave);
  if (idxBootstrap !== -1) {
    const identidad: IdentidadCliente = {
      id: `bootstrap-${idxBootstrap}`,
      nombre: `bootstrap-${idxBootstrap + 1}`,
      scopes: ['*'],
      origen: 'bootstrap',
    };
    return { ok: true, sesion: { ctx: { ...ctx, apiKey: identidad }, identidad } };
  }

  const verificacion = await verificarClave(ctx, clave);
  if (!verificacion.ok || !verificacion.key) {
    await ctx.audit.registrar({ tipo: 'auth_fallo', cliente: '(desconocido)', ip, detalle: verificacion.motivo ?? 'clave inválida' });
    ctx.metrics.inc('mcp_suite_auth_fallos_total');
    return { ok: false, status: 401, mensaje: `Clave rechazada: ${verificacion.motivo ?? 'inválida'}` };
  }

  const identidad: IdentidadCliente = {
    id: verificacion.key.id,
    nombre: verificacion.key.nombre,
    scopes: verificacion.key.scopes,
    origen: 'api_key',
    empresaId: verificacion.key.empresa_id ?? undefined,
  };
  return { ok: true, sesion: { ctx: { ...ctx, apiKey: identidad }, identidad } };
}

async function leerCuerpo(req: http.IncomingMessage, maxBytes = 5_000_000): Promise<unknown> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    total += (chunk as Buffer).length;
    if (total > maxBytes) throw new Error('Cuerpo de la petición demasiado grande.');
    chunks.push(chunk as Buffer);
  }
  if (total === 0) return undefined;
  const texto = Buffer.concat(chunks).toString('utf8');
  try {
    return JSON.parse(texto);
  } catch {
    return undefined;
  }
}

function json(res: http.ServerResponse, status: number, data: unknown): void {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data, null, 2));
}

export async function startHttp(ctx: ServerCtx): Promise<http.Server> {
  const { catalog } = buildServer(ctx);

  const landingTemplate = await fs
    .readFile(path.join(projectRoot, 'paginas', 'landing.html'), 'utf8')
    .catch(() => '<!doctype html><html><body><h1>mcp-suite-mx</h1><p>Landing no encontrada.</p></body></html>');

  const tallerTemplate = await fs
    .readFile(path.join(projectRoot, 'paginas', 'taller.html'), 'utf8')
    .catch(() => '<!doctype html><html><body><h1>Taller</h1><p>Página del mapa vivo no encontrada.</p></body></html>');

  const manejador = async (req: http.IncomingMessage, res: http.ServerResponse): Promise<void> => {
    const t0 = performance.now();
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    cors(ctx, res);

    res.on('finish', () => {
      ctx.metrics.inc('mcp_suite_http_peticiones_total', { ruta: url.pathname, metodo: req.method ?? '?', codigo: res.statusCode });
      ctx.metrics.observe('mcp_suite_http_duracion_segundos', (performance.now() - t0) / 1000, { ruta: url.pathname });
    });

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    try {
      // ── Endpoint MCP (Streamable HTTP, stateless) con autenticación ───────
      if (url.pathname === '/mcp') {
        if (req.method !== 'POST') {
          json(res, 405, { error: 'Este servidor usa transporte Streamable HTTP sin sesiones: usa POST /mcp.' });
          return;
        }
        const auth = await autenticar(ctx, req);
        if (!auth.ok) {
          res.setHeader('WWW-Authenticate', 'Bearer');
          json(res, auth.status, { error: auth.mensaje });
          return;
        }

        // Encabezados informativos del límite de tasa del cliente.
        if (ctx.limiter) {
          const claveCubeta = auth.sesion.identidad?.id ?? 'anon';
          const estado = ctx.limiter.estado(claveCubeta);
          res.setHeader('X-RateLimit-Limit', String(estado.limite));
          res.setHeader('X-RateLimit-Remaining', String(estado.restantes));
        }

        let cuerpo: unknown;
        try {
          cuerpo = await leerCuerpo(req);
        } catch (e: any) {
          json(res, 413, { error: e.message });
          return;
        }
        const { server: mcp } = buildServer(auth.sesion.ctx);
        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: undefined,
          enableJsonResponse: true,
        });
        res.on('close', () => {
          transport.close().catch(() => undefined);
        });
        await mcp.connect(transport);
        await transport.handleRequest(req, res, cuerpo);
        return;
      }

      // ── Endpoints públicos de operación ───────────────────────────────────
      if (url.pathname === '/health') {
        json(res, 200, {
          ok: true,
          nombre: SERVER_NAME,
          version: VERSION,
          uptime_s: ctx.metrics.uptimeSegundos(),
          auth_mode: ctx.config.authMode,
        });
        return;
      }
      if (url.pathname === '/metrics') {
        res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4; charset=utf-8' });
        res.end(ctx.metrics.render());
        return;
      }
      if (url.pathname === '/api/config') {
        json(res, 200, {
          nombre: SERVER_NAME,
          version: VERSION,
          transporte: ctx.config.transport,
          puerto: ctx.config.port,
          directorio_datos: ctx.config.dataDir,
          iva_pct: ctx.config.ivaPct,
          autenticacion: ctx.config.authMode,
          rate_limit_rpm: ctx.config.rateLimitRpm,
          repositorios_autorizados: ctx.config.allowedGitRoots,
        });
        return;
      }
      if (url.pathname === '/api/catalogo') {
        const porModulo = [...new Set(catalog.map((t) => t.module))].map((modulo) => ({
          modulo,
          herramienta_count: catalog.filter((t) => t.module === modulo).length,
          herramientas: catalog.filter((t) => t.module === modulo),
        }));
        json(res, 200, { total: catalog.length, modulos: porModulo });
        return;
      }
      if (url.pathname === '/api/herramientas') {
        json(res, 200, catalog);
        return;
      }

      // ── Panel (público: no expone datos de negocio) ───────────────────────
      if (url.pathname === '/' || url.pathname === '/panel') {
        const porModulo = [...new Set(catalog.map((t) => t.module))].map((modulo) => ({
          modulo,
          total: catalog.filter((t) => t.module === modulo).length,
          herramientas: catalog.filter((t) => t.module === modulo),
        }));
        const datosPanel = {
          modulos: porModulo,
          authMode: ctx.config.authMode,
          rateLimitRpm: ctx.config.rateLimitRpm,
          clavesActivas: await contarClavesActivas(ctx).catch(() => 0),
        };
        const html = landingTemplate
          .replace('<!--CONFIG_JSON-->', JSON.stringify({ nombre: SERVER_NAME, version: VERSION, transporte: ctx.config.transport, puerto: ctx.config.port, iva_pct: ctx.config.ivaPct }))
          .replace('<!--PANEL_JSON-->', JSON.stringify(datosPanel));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(html);
        return;
      }

      // ── Mapa vivo del Taller (público salvo TALLER_PUBLICO=0) ────────────
      if (url.pathname === '/taller' || url.pathname === '/api/taller/mapa') {
        if (!ctx.config.tallerPublico && ctx.config.authMode === 'required') {
          const auth = await autenticar(ctx, req);
          if (!auth.ok) {
            res.setHeader('WWW-Authenticate', 'Bearer');
            json(res, auth.status, { error: auth.mensaje });
            return;
          }
        }
        const raices = ctx.config.allowedGitRoots;
        const raizPedida = url.searchParams.get('raiz');
        const raiz = raizPedida && raices.some((r) => raizPedida === r || raizPedida.startsWith(`${r}${path.sep}`)) ? raizPedida : raices[0];
        if (!raiz) {
          json(res, 400, { error: 'No hay rutas autorizadas configuradas (ALLOWED_GIT_ROOTS).' });
          return;
        }
        try {
          const vivo = await obtenerEstadoVivo(raiz, { forzar: url.searchParams.get('forzar') === '1' });
          if (url.pathname === '/api/taller/mapa') {
            json(res, 200, { ...vivo, raices });
            return;
          }
          const html = tallerTemplate
            .replace('<!--TALLER_JSON-->', JSON.stringify({ estado: vivo.estado }))
            .replace('<!--CONFIG_JSON-->', JSON.stringify({ raices, taller_publico: ctx.config.tallerPublico }));
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(html);
          return;
        } catch (e: any) {
          json(res, 500, { error: `No se pudo analizar el proyecto: ${e?.message ?? e}` });
          return;
        }
      }

      // ── Administración (protegida si AUTH_MODE=required) ──────────────────
      const rutasAdmin = ['/api/auditoria', '/api/claves', '/api/aprobaciones', '/api/parches', '/api/empresas'];
      if (rutasAdmin.includes(url.pathname)) {
        if (ctx.config.authMode === 'required') {
          const auth = await autenticar(ctx, req);
          if (!auth.ok) {
            res.setHeader('WWW-Authenticate', 'Bearer');
            json(res, auth.status, { error: auth.mensaje });
            return;
          }
        }
        if (url.pathname === '/api/auditoria') {
          const limite = Number(url.searchParams.get('limite') ?? 50);
          const eventos = await ctx.audit.leer({
            tipo: (url.searchParams.get('tipo') as any) ?? undefined,
            herramienta: url.searchParams.get('herramienta') ?? undefined,
            cliente: url.searchParams.get('cliente') ?? undefined,
            desde: url.searchParams.get('desde') ?? undefined,
            limite: Number.isFinite(limite) ? Math.min(Math.max(limite, 1), 500) : 50,
          });
          json(res, 200, { total: eventos.length, resumen: await ctx.audit.resumen(), eventos: [...eventos].reverse() });
          return;
        }
        if (url.pathname === '/api/empresas') {
          const activa = empresaActivaDetalle(ctx);
          json(res, 200, {
            activa: { id: activa.id, nombre: activa.nombre },
            total: listarEmpresas(ctx).length,
            empresas: listarEmpresas(ctx).map((e) => ({ id: e.id, nombre: e.nombre, rfc: e.rfc, series: e.series, color: e.color })),
            comparativa: url.searchParams.get('comparar') === '1' ? compararEmpresas(ctx).empresas.map((r) => ({
              empresa: r.empresa.nombre,
              productos: r.inventario.productos,
              ventas: r.pedidos.ventas_totales,
              pedidos: r.pedidos.total,
              bajo_minimo: r.inventario.bajo_minimo,
            })) : undefined,
          });
          return;
        }
        if (url.pathname === '/api/parches') {
          const estado = url.searchParams.get('estado') as 'propuesto' | 'probado' | 'aplicado' | 'revertido' | null;
          const limite = Number(url.searchParams.get('limite') ?? 20);
          const parches = listarParches(ctx.db, {
            limite: Number.isFinite(limite) ? Math.min(Math.max(limite, 1), 200) : 20,
            estado: estado ?? undefined,
          });
          json(res, 200, {
            total: parches.length,
            parches: parches.map((p) => ({
              id: p.id, titulo: p.titulo, estado: p.estado, huella: p.huella,
              archivos: [...new Set(p.cambios.map((c) => c.ruta))],
              veredicto: p.prueba?.veredicto ?? null, intentos: p.intentos,
              creado_en: p.creado_en, aplicado_en: p.aplicado_en ?? null, revertido_en: p.revertido_en ?? null,
            })),
          });
          return;
        }
        if (url.pathname === '/api/aprobaciones') {
          const estado = (url.searchParams.get('estado') ?? 'pendiente') as 'pendiente' | 'aprobada' | 'rechazada' | 'expirada';
          const todas = url.searchParams.get('todas') === '1';
          const lista = listarAprobaciones(ctx, { estado: todas ? undefined : estado, limite: 200 });
          json(res, 200, {
            total: lista.length,
            pendientes: lista.filter((a) => a.estado === 'pendiente').length,
            aprobaciones: lista.map((a) => ({
              id: a.id, tipo: a.tipo, titulo: a.titulo, detalle: a.detalle ?? null,
              estado: a.estado, huella: a.huella ?? null, solicitada_por: a.solicitada_por,
              solicitada_en: a.solicitada_en,
              resuelta_en: a.resuelta_en ?? null, resuelta_por: a.resuelta_por ?? null, motivo: a.motivo ?? null,
            })),
          });
          return;
        }
        json(res, 200, { total: (await listarClaves(ctx)).length, claves: await listarClaves(ctx) });
        return;
      }

      if (req.method === 'POST' && url.pathname.startsWith('/api/aprobaciones/')) {
        const id = decodeURIComponent(url.pathname.replace('/api/aprobaciones/', ''));
        if (ctx.config.authMode === 'required') {
          const auth = await autenticar(ctx, req);
          if (!auth.ok) {
            res.setHeader('WWW-Authenticate', 'Bearer');
            json(res, auth.status, { error: auth.mensaje });
            return;
          }
        }
        const cuerpo = ((await leerCuerpo(req, 64 * 1024)) ?? {}) as Record<string, unknown>;
        const decision = String(cuerpo.decision ?? cuerpo.accion ?? '').toLowerCase();
        if (!decision.startsWith('aprob') && !decision.startsWith('rechaz')) {
          json(res, 400, { error: 'Falta "decision": usa "aprobar" o "rechazar".' });
          return;
        }
        const estado = decision.startsWith('rechaz') ? 'rechazada' : 'aprobada';
        try {
          const r = await resolverAprobacion(ctx, id, estado, {
            motivo: cuerpo.motivo ? String(cuerpo.motivo) : undefined,
            por: String(cuerpo.por ?? 'panel'),
          });
          json(res, 200, { id: r.id, estado: r.estado, titulo: r.titulo, resuelta_en: r.resuelta_en, resuelta_por: r.resuelta_por });
        } catch (e: any) {
          json(res, 409, { error: e?.message ?? String(e) });
        }
        return;
      }

      json(res, 404, {
        error: 'Ruta no encontrada',
        rutas: ['/', '/panel', '/taller', '/health', '/metrics', '/api/config', '/api/catalogo', '/api/herramientas', '/api/claves', '/api/auditoria', '/api/aprobaciones', 'POST /api/aprobaciones/<id>', '/api/parches', '/api/empresas', '/api/taller/mapa', 'POST /mcp'],
      });
    } catch (err: any) {
      log.error('http_error', { path: url.pathname, error: err?.message ?? String(err) });
      if (!res.headersSent) json(res, 500, { error: err?.message ?? 'Error interno' });
      else res.end();
    }
  };

  const tls = Boolean(ctx.config.tlsCert && ctx.config.tlsKey);
  const server: http.Server = tls
    ? https.createServer(
        {
          cert: fsSync.readFileSync(ctx.config.tlsCert!),
          key: fsSync.readFileSync(ctx.config.tlsKey!),
        },
        manejador,
      )
    : http.createServer(manejador);

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(ctx.config.port, ctx.config.host, () => resolve());
  });

  const esquema = tls ? 'https' : 'http';
  log.info('http_listo', {
    url: `${esquema}://${ctx.config.host}:${ctx.config.port}`,
    mcp_endpoint: `${esquema}://${ctx.config.host}:${ctx.config.port}/mcp`,
    panel: `${esquema}://${ctx.config.host}:${ctx.config.port}/panel`,
    tls,
    auth_mode: ctx.config.authMode,
    rate_limit_rpm: ctx.config.rateLimitRpm,
  });
  return server;
}
