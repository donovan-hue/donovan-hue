/**
 * Multi-empresa (fase 5A): un solo servidor, varios negocios (el caso del
 * despacho contable con varios clientes, o el dueño con dos giros).
 *
 * Reglas:
 *   · La empresa activa se decide así: la clave de API manda si está atada a
 *     una empresa; si no, lo que diga `empresa_actual` en la base; si no,
 *     `EMPRESA_DEFAULT` del entorno; si no, la empresa por defecto.
 *   · Todo dato de negocio (inventario, pedidos, plantillas) lleva `empresa_id`
 *     y los repositorios filtran SIEMPRE por empresa activa.
 *   · Cambiar la empresa activa es una operación local (stdio) o de una clave
 *     global; una clave atada a una empresa no puede mirar a otra.
 */
import crypto from 'node:crypto';
import type { ServerCtx } from '../context.js';
import { EMPRESA_POR_DEFECTO, leerMeta, escribirMeta } from '../db/index.js';

export interface Empresa {
  id: string;
  nombre: string;
  rfc: string;
  regimen: string;
  cp: string;
  series: string;
  color: string;
  creada_en: string;
  archivada: boolean;
}

interface FilaEmpresa {
  id: string;
  nombre: string;
  rfc: string;
  regimen: string;
  cp: string;
  series: string;
  color: string;
  creada_en: string;
  archivada: number;
}

const aEmpresa = (f: FilaEmpresa): Empresa => ({ ...f, archivada: f.archivada === 1 });

export function nuevaIdEmpresa(): string {
  return `EMP-${crypto.randomBytes(3).toString('hex')}`;
}

export function listarEmpresas(ctx: ServerCtx, opciones: { incluirArchivadas?: boolean } = {}): Empresa[] {
  const sql = opciones.incluirArchivadas
    ? `SELECT * FROM empresas ORDER BY archivada, nombre`
    : `SELECT * FROM empresas WHERE archivada = 0 ORDER BY nombre`;
  return (ctx.db.prepare(sql).all() as FilaEmpresa[]).map(aEmpresa);
}

export function obtenerEmpresa(ctx: ServerCtx, id: string): Empresa | undefined {
  const fila = ctx.db
    .prepare(`SELECT * FROM empresas WHERE id = ? OR LOWER(nombre) = LOWER(?)`)
    .get(id, id) as FilaEmpresa | undefined;
  return fila ? aEmpresa(fila) : undefined;
}

/** Id de la empresa activa para ESTA petición (ver reglas arriba). */
export function empresaActiva(ctx: ServerCtx): string {
  if (ctx.apiKey?.empresaId) return ctx.apiKey.empresaId;
  return leerMeta(ctx.db, 'empresa_actual') ?? ctx.config.empresaDefault ?? EMPRESA_POR_DEFECTO;
}

/** Empresa activa con sus datos; si el id guardado ya no existe, cae a la por defecto. */
export function empresaActivaDetalle(ctx: ServerCtx): Empresa {
  const id = empresaActiva(ctx);
  const empresa = obtenerEmpresa(ctx, id);
  if (empresa) return empresa;
  const porDefecto = obtenerEmpresa(ctx, EMPRESA_POR_DEFECTO);
  if (porDefecto) return porDefecto;
  throw new Error('No hay ninguna empresa configurada. Crea una con `empresa_crear`.');
}

export function establecerEmpresaActiva(ctx: ServerCtx, id: string): Empresa {
  if (ctx.apiKey?.empresaId) {
    throw new Error(
      `Esta clave está atada a la empresa ${ctx.apiKey.empresaId}: no puede cambiar de empresa. Usa una clave global (sin --empresa) o el servidor local (stdio).`,
    );
  }
  const empresa = obtenerEmpresa(ctx, id);
  if (!empresa) throw new Error(`No existe la empresa "${id}".`);
  if (empresa.archivada) throw new Error(`La empresa "${empresa.nombre}" está archivada; desarchívala antes de activarla.`);
  escribirMeta(ctx.db, 'empresa_actual', empresa.id);
  return empresa;
}

export function crearEmpresa(
  ctx: ServerCtx,
  datos: { nombre: string; rfc?: string; regimen?: string; cp?: string; series?: string; color?: string },
): Empresa {
  const nombre = datos.nombre.trim();
  if (nombre.length < 2) throw new Error('El nombre de la empresa debe tener al menos 2 caracteres.');
  const repetida = ctx.db.prepare(`SELECT id FROM empresas WHERE LOWER(nombre) = LOWER(?)`).get(nombre);
  if (repetida) throw new Error(`Ya existe una empresa llamada "${nombre}".`);

  const limpio = (v?: string) => (v ?? '').trim();
  const rfc = limpio(datos.rfc).toUpperCase();
  if (rfc && !/^[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3}$/.test(rfc)) {
    throw new Error(`El RFC "${rfc}" no tiene la forma esperada (12 o 13 caracteres: letras + fecha + homoclave).`);
  }
  const cp = limpio(datos.cp);
  if (cp && !/^\d{5}$/.test(cp)) throw new Error(`El código postal "${cp}" debe ser de 5 dígitos.`);

  const empresa: Empresa = {
    id: nuevaIdEmpresa(),
    nombre,
    rfc,
    regimen: limpio(datos.regimen),
    cp,
    series: limpio(datos.series) || 'A',
    color: limpio(datos.color) || '#4ec3e0',
    creada_en: new Date().toISOString(),
    archivada: false,
  };
  ctx.db
    .prepare(
      `INSERT INTO empresas (id, nombre, rfc, regimen, cp, series, color, creada_en, archivada)
       VALUES (@id, @nombre, @rfc, @regimen, @cp, @series, @color, @creada_en, 0)`,
    )
    .run(empresa);
  return empresa;
}

export function editarEmpresa(
  ctx: ServerCtx,
  id: string,
  cambios: { nombre?: string; rfc?: string; regimen?: string; cp?: string; series?: string; color?: string; archivar?: boolean },
): Empresa {
  const actual = obtenerEmpresa(ctx, id);
  if (!actual) throw new Error(`No existe la empresa "${id}".`);
  const nombre = cambios.nombre?.trim() ?? actual.nombre;
  if (nombre !== actual.nombre) {
    const repetida = ctx.db.prepare(`SELECT id FROM empresas WHERE LOWER(nombre) = LOWER(?) AND id <> ?`).get(nombre, actual.id);
    if (repetida) throw new Error(`Ya existe otra empresa llamada "${nombre}".`);
  }
  const rfc = (cambios.rfc ?? actual.rfc).toUpperCase().trim();
  if (rfc && !/^[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3}$/.test(rfc)) throw new Error(`El RFC "${rfc}" no tiene la forma esperada.`);
  const cp = (cambios.cp ?? actual.cp).trim();
  if (cp && !/^\d{5}$/.test(cp)) throw new Error(`El código postal "${cp}" debe ser de 5 dígitos.`);
  const archivar = cambios.archivar ?? actual.archivada;
  if (archivar && actual.id === empresaActiva(ctx)) {
    throw new Error('No puedes archivar la empresa activa: cambia de empresa primero (`empresa_actual`).');
  }

  ctx.db
    .prepare(
      `UPDATE empresas SET nombre = @nombre, rfc = @rfc, regimen = @regimen, cp = @cp, series = @series, color = @color, archivada = @archivada
       WHERE id = @id`,
    )
    .run({
      id: actual.id,
      nombre,
      rfc,
      regimen: cambios.regimen ?? actual.regimen,
      cp,
      series: cambios.series ?? actual.series,
      color: cambios.color ?? actual.color,
      archivada: archivar ? 1 : 0,
    });
  return { ...actual, nombre, rfc, regimen: cambios.regimen ?? actual.regimen, cp, series: cambios.series ?? actual.series, color: cambios.color ?? actual.color, archivada: archivar };
}

export interface ResumenEmpresa {
  empresa: Empresa;
  inventario: { productos: number; valor_a_precio: number; valor_a_costo: number; bajo_minimo: number };
  pedidos: { total: number; por_estado: Record<string, number>; ventas_totales: number; ticket_promedio: number; ultimo_pedido: string | null };
  plantillas: number;
}

export function resumenEmpresa(ctx: ServerCtx, id: string): ResumenEmpresa {
  const empresa = obtenerEmpresa(ctx, id);
  if (!empresa) throw new Error(`No existe la empresa "${id}".`);
  const inv = ctx.db
    .prepare(
      `SELECT COUNT(*) AS productos,
              COALESCE(SUM(precio * stock), 0) AS valor_precio,
              COALESCE(SUM(costo * stock), 0)   AS valor_costo,
              COALESCE(SUM(CASE WHEN stock <= stock_minimo THEN 1 ELSE 0 END), 0) AS bajo_minimo
       FROM inventario WHERE empresa_id = ?`,
    )
    .get(empresa.id) as { productos: number; valor_precio: number; valor_costo: number; bajo_minimo: number };
  const porEstado = ctx.db
    .prepare(`SELECT estado, COUNT(*) AS n FROM pedidos WHERE empresa_id = ? GROUP BY estado`)
    .all(empresa.id) as { estado: string; n: number }[];
  const ventas = ctx.db
    .prepare(`SELECT COUNT(*) AS n, COALESCE(SUM(total), 0) AS total, MAX(creado_en) AS ultimo FROM pedidos WHERE empresa_id = ?`)
    .get(empresa.id) as { n: number; total: number; ultimo: string | null };
  const plantillas = ctx.db.prepare(`SELECT COUNT(*) AS n FROM plantillas WHERE empresa_id = ?`).get(empresa.id) as { n: number };

  const por_estado: Record<string, number> = {};
  for (const f of porEstado) por_estado[f.estado] = f.n;

  return {
    empresa,
    inventario: {
      productos: inv.productos,
      valor_a_precio: Number(inv.valor_precio.toFixed(2)),
      valor_a_costo: Number(inv.valor_costo.toFixed(2)),
      bajo_minimo: inv.bajo_minimo,
    },
    pedidos: {
      total: ventas.n,
      por_estado,
      ventas_totales: Number(ventas.total.toFixed(2)),
      ticket_promedio: ventas.n ? Number((ventas.total / ventas.n).toFixed(2)) : 0,
      ultimo_pedido: ventas.ultimo,
    },
    plantillas: plantillas.n,
  };
}

export interface ComparativaEmpresas {
  empresas: (ResumenEmpresa & { etiquetas: string[] })[];
  mejor_en: Record<string, string>;
  nota: string;
}

/** Compara varias empresas (por defecto, todas las activas) — el reporte que pide un despacho. */
export function compararEmpresas(ctx: ServerCtx, ids?: string[]): ComparativaEmpresas {
  const lista = ids?.length
    ? ids.map((id) => {
        const e = obtenerEmpresa(ctx, id);
        if (!e) throw new Error(`No existe la empresa "${id}".`);
        return e;
      })
    : listarEmpresas(ctx);
  if (lista.length < 1) throw new Error('No hay empresas que comparar.');

  const resumenes = lista.map((e) => {
    const r = resumenEmpresa(ctx, e.id);
    const etiquetas: string[] = [];
    if (r.pedidos.ventas_totales > 0) etiquetas.push(`ventas $${r.pedidos.ventas_totales.toLocaleString('es-MX')}`);
    if (r.inventario.bajo_minimo > 0) etiquetas.push(`${r.inventario.bajo_minimo} bajo mínimo`);
    if (r.inventario.productos === 0) etiquetas.push('sin inventario');
    return { ...r, etiquetas };
  });

  const mejorEn: Record<string, string> = {};
  const pick = (metrica: string, valor: (r: ResumenEmpresa) => number) => {
    const orden = [...resumenes].sort((a, b) => valor(b) - valor(a));
    if (orden[0] && valor(orden[0]) > 0) mejorEn[metrica] = orden[0].empresa.nombre;
  };
  pick('ventas_totales', (r) => r.pedidos.ventas_totales);
  pick('pedidos', (r) => r.pedidos.total);
  pick('valor_inventario', (r) => r.inventario.valor_a_precio);

  return {
    empresas: resumenes,
    mejor_en: mejorEn,
    nota:
      resumenes.length < 2
        ? 'Solo hay una empresa: la comparación tiene sentido con dos o más.'
        : 'Ventas = suma de pedidos creados (todos los estados) en cada empresa, según su propio historial.',
  };
}
