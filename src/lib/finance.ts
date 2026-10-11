/** Reglas financieras y comerciales (México: IVA 16%, retenciones ISR/IVA). */

const r2 = (n: number) => Number(n.toFixed(2));

export interface LineaCotizacion {
  descripcion: string;
  cantidad: number;
  precioUnitario: number;
  importe: number;
}

export interface ItemCotizacion {
  descripcion: string;
  cantidad: number;
  precioUnitario: number;
}

export interface OpcionesCotizacion {
  descuentoPct?: number;
  ivaPct?: number;
  retencionIsrPct?: number;
  retencionIvaPct?: number;
}

export interface TotalesCotizacion {
  lineas: LineaCotizacion[];
  subtotal: number;
  descuentoPct: number;
  descuento: number;
  baseGravable: number;
  ivaPct: number;
  iva: number;
  retencionIsrPct: number;
  retencionIsr: number;
  retencionIvaPct: number;
  retencionIva: number;
  total: number;
}

export function calcularCotizacion(items: ItemCotizacion[], opciones: OpcionesCotizacion = {}): TotalesCotizacion {
  if (items.length === 0) throw new Error('La cotización necesita al menos un concepto.');
  const descuentoPct = opciones.descuentoPct ?? 0;
  const ivaPct = opciones.ivaPct ?? 16;
  const retencionIsrPct = opciones.retencionIsrPct ?? 0;
  const retencionIvaPct = opciones.retencionIvaPct ?? 0;

  for (const [i, it] of items.entries()) {
    if (it.cantidad <= 0) throw new Error(`El concepto ${i + 1} ("${it.descripcion}") debe tener cantidad mayor que cero.`);
    if (it.precioUnitario < 0) throw new Error(`El concepto ${i + 1} ("${it.descripcion}") no puede tener precio negativo.`);
  }
  for (const [nombre, v] of Object.entries({ descuentoPct, ivaPct, retencionIsrPct, retencionIvaPct })) {
    if (v < 0 || v > 100) throw new Error(`${nombre} debe estar entre 0 y 100 (recibido: ${v}).`);
  }

  const lineas: LineaCotizacion[] = items.map((it) => ({
    descripcion: it.descripcion,
    cantidad: it.cantidad,
    precioUnitario: r2(it.precioUnitario),
    importe: r2(it.cantidad * it.precioUnitario),
  }));

  const subtotal = lineas.reduce((a, l) => a + l.importe, 0);
  const descuento = r2(subtotal * (descuentoPct / 100));
  const baseGravable = r2(subtotal - descuento);
  const iva = r2(baseGravable * (ivaPct / 100));
  const retencionIsr = r2(baseGravable * (retencionIsrPct / 100));
  // Convención mexicana: la retención de IVA (típicamente 10.6667% = 2/3 del IVA)
  // se calcula sobre la BASE, no sobre el IVA trasladado.
  const retencionIva = r2(baseGravable * (retencionIvaPct / 100));

  return {
    lineas,
    subtotal: r2(subtotal),
    descuentoPct,
    descuento,
    baseGravable,
    ivaPct,
    iva,
    retencionIsrPct,
    retencionIsr,
    retencionIvaPct,
    retencionIva,
    total: r2(baseGravable + iva - retencionIsr - retencionIva),
  };
}

export interface ImpuestosDesglose {
  operacion: 'agregar' | 'desglosar';
  montoEntrada: number;
  base: number;
  ivaPct: number;
  iva: number;
  retenciones: number;
  total: number;
}

/**
 * agregar: monto = base → devuelve base + IVA − retenciones (valor a cobrar/pagar).
 * desglosar: monto = total con IVA incluido → separa base e IVA.
 */
export function calcularImpuestos(
  monto: number,
  operacion: 'agregar' | 'desglosar',
  ivaPct = 16,
  retencionesPct = 0,
): ImpuestosDesglose {
  if (monto <= 0) throw new Error('El monto debe ser mayor que cero.');
  const factorRet = retencionesPct / 100;
  const factorIva = ivaPct / 100;

  if (operacion === 'desglosar') {
    const base = r2(monto / (1 + factorIva));
    const iva = r2(monto - base);
    return {
      operacion,
      montoEntrada: r2(monto),
      base,
      ivaPct,
      iva,
      retenciones: 0,
      total: r2(monto),
    };
  }

  const base = r2(monto);
  const iva = r2(base * factorIva);
  const retenciones = r2(base * factorRet);
  return {
    operacion,
    montoEntrada: base,
    base,
    ivaPct,
    iva,
    retenciones,
    total: r2(base + iva - retenciones),
  };
}

export interface Margen {
  precio: number;
  costo: number;
  utilidad: number;
  margenPct: number;
  markupPct: number;
  precioConIva: number;
}

export function calcularMargen(precio: number, costo: number, ivaPct = 16): Margen {
  if (precio <= 0) throw new Error('El precio debe ser mayor que cero.');
  if (costo < 0) throw new Error('El costo no puede ser negativo.');
  const utilidad = r2(precio - costo);
  return {
    precio: r2(precio),
    costo: r2(costo),
    utilidad,
    margenPct: r2((utilidad / precio) * 100),
    markupPct: costo === 0 ? 0 : r2((utilidad / costo) * 100),
    precioConIva: r2(precio * (1 + ivaPct / 100)),
  };
}

export interface PuntoEquilibrio {
  unidades: number;
  ventas: number;
  costoVariableUnitario: number;
  precioUnitario: number;
  costosFijos: number;
  margenContribucion: number;
}

export function puntoEquilibrio(costosFijos: number, precioUnitario: number, costoVariableUnitario: number): PuntoEquilibrio {
  if (costosFijos < 0) throw new Error('Los costos fijos no pueden ser negativos.');
  const mc = r2(precioUnitario - costoVariableUnitario);
  if (mc <= 0) throw new Error('El precio debe ser mayor que el costo variable unitario (margen de contribución <= 0).');
  const vu = Math.ceil(costosFijos / mc);
  return {
    unidades: vu,
    ventas: r2(vu * precioUnitario),
    costoVariableUnitario: r2(costoVariableUnitario),
    precioUnitario: r2(precioUnitario),
    costosFijos: r2(costosFijos),
    margenContribucion: mc,
  };
}

/** Validación de un movimiento de inventario. Devuelve el nuevo stock. */
export function aplicarMovimiento(
  stockActual: number,
  tipo: 'entrada' | 'salida' | 'ajuste',
  cantidad: number,
  nombreItem = 'artículo',
): number {
  if (cantidad < 0) throw new Error('La cantidad no puede ser negativa.');
  if (tipo === 'entrada') return stockActual + cantidad;
  if (tipo === 'salida') {
    if (cantidad > stockActual) {
      throw new Error(`Stock insuficiente de ${nombreItem}: disponible ${stockActual}, solicitado ${cantidad}.`);
    }
    return stockActual - cantidad;
  }
  return cantidad; // ajuste: la cantidad es el nuevo stock absoluto
}

/** Estados válidos del ciclo de vida de un pedido. */
export const ESTADOS_PEDIDO = ['borrador', 'confirmado', 'en_produccion', 'enviado', 'entregado', 'cancelado'] as const;
export type EstadoPedido = (typeof ESTADOS_PEDIDO)[number];

const TRANSICIONES: Record<EstadoPedido, EstadoPedido[]> = {
  borrador: ['confirmado', 'cancelado'],
  confirmado: ['en_produccion', 'cancelado'],
  en_produccion: ['enviado', 'cancelado'],
  enviado: ['entregado'],
  entregado: [],
  cancelado: [],
};

export function validarTransicion(actual: EstadoPedido, nuevo: EstadoPedido): void {
  if (actual === nuevo) throw new Error(`El pedido ya está en estado "${nuevo}".`);
  const permitidos = TRANSICIONES[actual];
  if (!permitidos.includes(nuevo)) {
    throw new Error(
      `Transición inválida: "${actual}" → "${nuevo}". Desde "${actual}" solo se permite: ${permitidos.length ? permitidos.join(', ') : 'ninguno (estado final)'}.`,
    );
  }
}
