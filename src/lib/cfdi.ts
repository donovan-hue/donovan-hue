/**
 * CFDI 4.0 (México) — generación del comprobante SIN SELLAR y validaciones fiscales.
 *
 * Alcance: produce el XML previo al timbrado (estructura, catálogos, cálculo de
 * impuestos y reglas de validación del Anexo 20). El sellado y timbrado los hace
 * un PAC autorizado con el CSD del emisor; este módulo no sustituye al PAC.
 *
 * Fuente de catálogos: SAT (Anexo 20). Aquí vive un subconjunto de uso frecuente;
 * para códigos específicos, consulta los catálogos oficiales vigentes.
 */

// ── Catálogos SAT (subconjunto de uso frecuente) ─────────────────────────────

export const CATALOGO_REGIMENES = [
  { clave: '601', descripcion: 'General de Ley Personas Morales' },
  { clave: '603', descripcion: 'Personas Morales con Fines no Lucrativos' },
  { clave: '605', descripcion: 'Sueldos y Salarios e Ingresos Asimilados a Salarios' },
  { clave: '606', descripcion: 'Arrendamiento' },
  { clave: '608', descripcion: 'Demás ingresos' },
  { clave: '612', descripcion: 'Personas Físicas con Actividades Empresariales y Profesionales' },
  { clave: '614', descripcion: 'Ingresos por intereses' },
  { clave: '616', descripcion: 'Sin obligaciones fiscales' },
  { clave: '620', descripcion: 'Sociedades Cooperativas de Producción' },
  { clave: '621', descripcion: 'Incorporación Fiscal' },
  { clave: '622', descripcion: 'Actividades Agrícolas, Ganaderas, Silvícolas y Pesqueras' },
  { clave: '626', descripcion: 'Régimen Simplificado de Confianza (RESICO)' },
] as const;

export const CATALOGO_USOS_CFDI = [
  { clave: 'G01', descripcion: 'Adquisición de mercancías' },
  { clave: 'G02', descripcion: 'Devoluciones, descuentos o bonificaciones' },
  { clave: 'G03', descripcion: 'Gastos en general' },
  { clave: 'I01', descripcion: 'Construcciones' },
  { clave: 'I02', descripcion: 'Mobiliario y equipo de oficina por inversiones' },
  { clave: 'I03', descripcion: 'Equipo de transporte' },
  { clave: 'I04', descripcion: 'Equipo de cómputo y accesorios' },
  { clave: 'I08', descripcion: 'Otra maquinaria y equipo' },
  { clave: 'D01', descripcion: 'Honorarios médicos, dentales y gastos hospitalarios' },
  { clave: 'D10', descripcion: 'Pagos por servicios educativos (colegiaturas)' },
  { clave: 'P01', descripcion: 'Por definir' },
  { clave: 'S01', descripcion: 'Sin efectos fiscales (no deducible)' },
] as const;

export const CATALOGO_FORMAS_PAGO = [
  { clave: '01', descripcion: 'Efectivo' },
  { clave: '02', descripcion: 'Cheque nominativo' },
  { clave: '03', descripcion: 'Transferencia electrónica de fondos' },
  { clave: '04', descripcion: 'Tarjeta de crédito' },
  { clave: '05', descripcion: 'Monedero electrónico' },
  { clave: '06', descripcion: 'Dinero electrónico' },
  { clave: '08', descripcion: 'Vales de despensa' },
  { clave: '28', descripcion: 'Tarjeta de débito' },
  { clave: '29', descripcion: 'Tarjeta de servicios' },
  { clave: '30', descripcion: 'Aplicación de anticipos' },
  { clave: '99', descripcion: 'Por definir (obligatorio cuando el método es PPD)' },
] as const;

export const CATALOGO_METODOS_PAGO = [
  { clave: 'PUE', descripcion: 'Pago en una sola exhibición' },
  { clave: 'PPD', descripcion: 'Pago en parcialidades o diferido' },
] as const;

export const CATALOGO_MONEDAS = [
  { clave: 'MXN', descripcion: 'Peso Mexicano (moneda nacional)' },
  { clave: 'USD', descripcion: 'Dólar estadounidense' },
  { clave: 'EUR', descripcion: 'Euro' },
  { clave: 'XXX', descripcion: 'Los códigos asignados para las transacciones en que no canalice moneda (solo tipo P)' },
] as const;

export const CATALOGO_UNIDADES = [
  { clave: 'E48', descripcion: 'Unidad de servicio' },
  { clave: 'H87', descripcion: 'Pieza' },
  { clave: 'EA', descripcion: 'Cada uno' },
  { clave: 'XBX', descripcion: 'Caja' },
  { clave: 'XPK', descripcion: 'Paquete' },
  { clave: 'MTR', descripcion: 'Metro' },
  { clave: 'KGM', descripcion: 'Kilogramo' },
  { clave: 'LTR', descripcion: 'Litro' },
  { clave: 'HUR', descripcion: 'Hora' },
  { clave: 'ACT', descripcion: 'Actividad' },
] as const;

export const CATALOGO_EXPORTACION = [
  { clave: '01', descripcion: 'No aplica' },
  { clave: '02', descripcion: 'Definitiva con clave A1' },
  { clave: '03', descripcion: 'Temporal' },
  { clave: '04', descripcion: 'Definitiva con clave distinta a A1' },
] as const;

/** Claves genéricas del SAT para receptor sin RFC / público en general. */
export const RFC_PUBLICO_GENERAL = 'XAXX010101000';
export const RFC_EXTRANJERO = 'XEXX010101000';
export const CP_PUBLICO_GENERAL = '85000';
export const CLAVE_PRODSERV_COMODIN = '01010101';

// ── Tipos ────────────────────────────────────────────────────────────────────

export interface DatosFiscales {
  rfc: string;
  nombre: string;
  codigoPostal: string;
  regimenFiscal: string;
  usoCfdi?: string;
}

export interface ConceptoEntrada {
  descripcion: string;
  cantidad: number;
  valorUnitario: number;
  claveProdServ?: string;
  claveUnidad?: string;
  unidad?: string;
  descuento?: number;
}

export interface OpcionesCfdi {
  serie?: string;
  folio?: string;
  fecha?: string;
  formaPago?: string;
  metodoPago?: 'PUE' | 'PPD';
  moneda?: string;
  tipoCambio?: number;
  lugarExpedicion?: string;
  exportacion?: string;
  ivaPct?: number;
  exento?: boolean;
  retencionIsrPct?: number;
  retencionIvaPct?: number;
  tipoComprobante?: 'I' | 'E' | 'P';
}

export interface Validacion {
  nivel: 'error' | 'aviso';
  campo: string;
  mensaje: string;
}

export interface ConceptoCalculado {
  claveProdServ: string;
  cantidad: number;
  claveUnidad: string;
  unidad: string;
  descripcion: string;
  valorUnitario: number;
  importe: number;
  descuento: number;
  base: number;
  objetoImp: '02';
  traslado: { impuesto: '002'; tipoFactor: 'Tasa' | 'Exento'; tasaOCuota?: number; importe?: number };
}

export interface TotalesCfdi {
  subtotal: number;
  descuento: number;
  totalImpuestosTrasladados: number;
  totalImpuestosRetenidos: number;
  total: number;
  retenciones: { impuesto: '001' | '002'; importe: number }[];
  traslados: { impuesto: '002'; tipoFactor: 'Tasa' | 'Exento'; base: number; tasaOCuota?: number; importe?: number }[];
}

export interface BorradorCfdi {
  version: '4.0';
  tipoComprobante: 'I' | 'E' | 'P';
  serie: string;
  folio: string;
  fecha: string;
  formaPago: string;
  metodoPago: 'PUE' | 'PPD';
  moneda: string;
  tipoCambio: number | null;
  lugarExpedicion: string;
  exportacion: string;
  emisor: { rfc: string; nombre: string; regimenFiscal: string };
  receptor: { rfc: string; nombre: string; domicilioFiscalReceptor: string; regimenFiscalReceptor: string; usoCFDI: string };
  conceptos: ConceptoCalculado[];
  totales: TotalesCfdi;
  validaciones: Validacion[];
  listo_para_timbrar: boolean;
}

// ── Utilidades internas ──────────────────────────────────────────────────────

const m2 = (n: number) => Number(n.toFixed(2));
const f2 = (n: number) => n.toFixed(2);
const f6 = (n: number) => n.toFixed(6);
const fCant = (n: number) => String(Number(n.toFixed(6)));

const RE_RFC = /^([A-ZÑ&]{3,4})\d{6}[A-Z0-9]{3}$/;

export function validarFormatoRfc(rfc: string): boolean {
  return RE_RFC.test((rfc ?? '').toUpperCase().trim());
}

export function esPersonaFisica(rfc: string): boolean {
  return /^[A-ZÑ&]{4}/.test((rfc ?? '').toUpperCase().trim());
}

function escaparXml(valor: string): string {
  return String(valor)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function fechaCfdi(iso?: string): string {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) throw new Error(`Fecha inválida: ${iso}`);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

// ── Generación del borrador ──────────────────────────────────────────────────

export function generarBorradorCfdi(
  emisor: DatosFiscales,
  receptor: DatosFiscales,
  conceptos: ConceptoEntrada[],
  opciones: OpcionesCfdi = {},
): BorradorCfdi {
  if (!Array.isArray(conceptos) || conceptos.length === 0) {
    throw new Error('El CFDI necesita al menos un concepto.');
  }

  const validaciones: Validacion[] = [];
  const error = (campo: string, mensaje: string) => validaciones.push({ nivel: 'error', campo, mensaje });
  const aviso = (campo: string, mensaje: string) => validaciones.push({ nivel: 'aviso', campo, mensaje });

  // ── Valores por defecto ───────────────────────────────────────────────────
  const tipoComprobante = opciones.tipoComprobante ?? 'I';
  const formaPago = opciones.formaPago ?? '01';
  const metodoPago = opciones.metodoPago ?? 'PUE';
  const moneda = (opciones.moneda ?? 'MXN').toUpperCase();
  const exportacion = opciones.exportacion ?? '01';
  const ivaPct = opciones.ivaPct ?? 16;
  const exento = opciones.exento ?? false;
  const retIsrPct = opciones.retencionIsrPct ?? 0;
  const retIvaPct = opciones.retencionIvaPct ?? 0;
  const lugarExpedicion = opciones.lugarExpedicion ?? emisor.codigoPostal;
  const usoCFDI = receptor.usoCfdi ?? 'G03';

  const rfcEmisor = emisor.rfc.toUpperCase().trim();
  const rfcReceptor = receptor.rfc.toUpperCase().trim();

  // ── Validaciones de identidad fiscal ──────────────────────────────────────
  if (!validarFormatoRfc(rfcEmisor)) error('emisor.rfc', `RFC del emisor con formato inválido: "${emisor.rfc}".`);
  if (!validarFormatoRfc(rfcReceptor)) error('receptor.rfc', `RFC del receptor con formato inválido: "${receptor.rfc}".`);
  if (!/^\d{5}$/.test(emisor.codigoPostal)) error('emisor.codigoPostal', 'El código postal del emisor debe tener 5 dígitos.');
  if (!/^\d{5}$/.test(receptor.codigoPostal)) error('receptor.codigoPostal', 'El código postal del receptor debe tener 5 dígitos.');
  if (!/^\d{5}$/.test(lugarExpedicion)) error('lugarExpedicion', 'El lugar de expedición debe ser un código postal de 5 dígitos.');

  if (!CATALOGO_REGIMENES.some((r) => r.clave === emisor.regimenFiscal)) {
    error('emisor.regimenFiscal', `Régimen fiscal del emisor no reconocido: "${emisor.regimenFiscal}".`);
  } else if (emisor.regimenFiscal === '616') {
    error('emisor.regimenFiscal', 'El régimen 616 (sin obligaciones fiscales) solo aplica a receptores; el emisor no puede usarlo.');
  }
  if (!CATALOGO_REGIMENES.some((r) => r.clave === receptor.regimenFiscal)) {
    error('receptor.regimenFiscal', `Régimen fiscal del receptor no reconocido: "${receptor.regimenFiscal}".`);
  }
  if (!CATALOGO_USOS_CFDI.some((u) => u.clave === usoCFDI)) {
    error('receptor.usoCfdi', `Uso del CFDI no reconocido: "${usoCFDI}".`);
  }
  if (!CATALOGO_FORMAS_PAGO.some((f) => f.clave === formaPago)) {
    error('formaPago', `Forma de pago no reconocida: "${formaPago}".`);
  }

  // ── Reglas de público en general / extranjero ─────────────────────────────
  const esPublicoGeneral = rfcReceptor === RFC_PUBLICO_GENERAL || rfcReceptor === RFC_EXTRANJERO;
  if (esPublicoGeneral) {
    if (!/PÚBLICO EN GENERAL|PUBLICO EN GENERAL/i.test(receptor.nombre)) {
      error('receptor.nombre', 'Para el RFC genérico XAXX010101000 el nombre debe ser exactamente "PÚBLICO EN GENERAL".');
    }
    if (receptor.codigoPostal !== CP_PUBLICO_GENERAL) {
      error('receptor.codigoPostal', `Para el RFC genérico, el domicilio fiscal del receptor debe ser ${CP_PUBLICO_GENERAL}.`);
    }
    if (receptor.regimenFiscal !== '616') {
      error('receptor.regimenFiscal', 'Para el RFC genérico, el régimen del receptor debe ser 616.');
    }
    if (usoCFDI !== 'S01') {
      error('receptor.usoCfdi', 'Para el RFC genérico, el uso del CFDI debe ser S01 (sin efectos fiscales).');
    }
  } else {
    if (usoCFDI === 'S01') {
      aviso('receptor.usoCfdi', 'Uso S01: el CFDI no será deducible para el receptor. Confirma que es intencional.');
    }
    if (receptor.regimenFiscal === '616' && usoCFDI !== 'S01') {
      error('receptor.usoCfdi', 'El régimen del receptor 616 solo admite el uso S01.');
    }
  }

  // ── Reglas de forma/método de pago ────────────────────────────────────────
  if (metodoPago === 'PPD' && formaPago !== '99') {
    error('formaPago', 'Con método PPD la forma de pago debe ser 99 (por definir).');
  }
  if (metodoPago === 'PUE' && formaPago === '99') {
    error('formaPago', 'Con método PUE la forma de pago no puede ser 99: indica cómo se pagó (01 efectivo, 03 transferencia…).');
  }
  if (!CATALOGO_METODOS_PAGO.some((m) => m.clave === metodoPago)) {
    error('metodoPago', `Método de pago no reconocido: "${metodoPago}".`);
  }

  // ── Moneda y exportación ──────────────────────────────────────────────────
  const tipoCambio = moneda === 'MXN' ? null : (opciones.tipoCambio ?? null);
  if (moneda !== 'MXN' && (!tipoCambio || tipoCambio <= 0)) {
    error('tipoCambio', `La moneda ${moneda} requiere tipo de cambio mayor que cero.`);
  }
  if (!CATALOGO_MONEDAS.some((m) => m.clave === moneda)) {
    aviso('moneda', `Moneda "${moneda}" fuera del subconjunto local del catálogo; verifica que exista en el catálogo del SAT.`);
  }
  if (!CATALOGO_EXPORTACION.some((e) => e.clave === exportacion)) {
    error('exportacion', `Clave de exportación inválida: "${exportacion}".`);
  }

  // ── Conceptos ─────────────────────────────────────────────────────────────
  const calculados: ConceptoCalculado[] = conceptos.map((c, i) => {
    const n = i + 1;
    if (!c.descripcion || c.descripcion.trim().length === 0) error(`conceptos[${n}].descripcion`, 'Cada concepto necesita descripción.');
    if (!(c.cantidad > 0)) error(`conceptos[${n}].cantidad`, 'La cantidad debe ser mayor que cero.');
    if (!(c.valorUnitario >= 0)) error(`conceptos[${n}].valorUnitario`, 'El valor unitario no puede ser negativo.');
    const descuento = c.descuento ?? 0;
    const importe = m2(c.cantidad * c.valorUnitario);
    if (descuento < 0) error(`conceptos[${n}].descuento`, 'El descuento no puede ser negativo.');
    if (descuento > importe) error(`conceptos[${n}].descuento`, `El descuento (${descuento}) no puede superar el importe (${importe}).`);

    const claveProdServ = c.claveProdServ ?? CLAVE_PRODSERV_COMODIN;
    if (!/^\d{8}$/.test(claveProdServ)) error(`conceptos[${n}].claveProdServ`, `La clave de producto/servicio debe tener 8 dígitos: "${claveProdServ}".`);
    if (claveProdServ === CLAVE_PRODSERV_COMODIN) {
      aviso(`conceptos[${n}].claveProdServ`, 'Se usó la clave comodín 01010101 (no existe en el catálogo). Para deducibilidad conviene la clave real del SAT.');
    }

    const claveUnidad = c.claveUnidad ?? 'E48';
    const unidad = c.unidad ?? (CATALOGO_UNIDADES.find((u) => u.clave === claveUnidad)?.descripcion ?? 'Unidad de servicio');
    const base = m2(importe - descuento);

    const traslado: ConceptoCalculado['traslado'] = exento
      ? { impuesto: '002', tipoFactor: 'Exento' }
      : { impuesto: '002', tipoFactor: 'Tasa', tasaOCuota: ivaPct / 100, importe: m2(base * (ivaPct / 100)) };

    return {
      claveProdServ,
      cantidad: Number(c.cantidad.toFixed(6)),
      claveUnidad,
      unidad,
      descripcion: c.descripcion.trim(),
      valorUnitario: m2(c.valorUnitario),
      importe,
      descuento,
      base,
      objetoImp: '02',
      traslado,
    };
  });

  // ── Totales ───────────────────────────────────────────────────────────────
  const subtotal = m2(calculados.reduce((a, c) => a + c.importe, 0));
  const descuentoTotal = m2(calculados.reduce((a, c) => a + c.descuento, 0));
  const baseNeta = m2(subtotal - descuentoTotal);

  const traslados: TotalesCfdi['traslados'] = exento
    ? [{ impuesto: '002', tipoFactor: 'Exento', base: baseNeta }]
    : [{ impuesto: '002', tipoFactor: 'Tasa', base: baseNeta, tasaOCuota: ivaPct / 100, importe: m2(baseNeta * (ivaPct / 100)) }];
  const totalTrasladado = exento ? 0 : (traslados[0]!.importe ?? 0);

  const retenciones: TotalesCfdi['retenciones'] = [];
  if (retIsrPct > 0) retenciones.push({ impuesto: '001', importe: m2(baseNeta * (retIsrPct / 100)) });
  if (retIvaPct > 0) retenciones.push({ impuesto: '002', importe: m2(baseNeta * (retIvaPct / 100)) });
  const totalRetenido = m2(retenciones.reduce((a, r) => a + r.importe, 0));

  if (exento && (retIsrPct > 0 || retIvaPct > 0)) {
    error('retenciones', 'No puede haber retenciones de IVA sobre operaciones exentas (y la de ISR requiere importe gravable).');
  }
  if (retIvaPct > 0 && !exento) {
    aviso('retencionIvaPct', 'La retención de IVA (10.6667% ≈ 2/3 del IVA) se calcula sobre la base, no sobre el IVA trasladado.');
  }
  if ((retIsrPct > 0 || retIvaPct > 0) && !esPersonaFisica(rfcEmisor) && rfcEmisor !== RFC_PUBLICO_GENERAL) {
    aviso('retenciones', 'Las retenciones de ISR/IVA suelen aplicar cuando quien factura es persona física (honorarios/arrendamiento). Verifica que corresponda.');
  }

  const total = m2(baseNeta + totalTrasladado - totalRetenido);

  if (tipoComprobante !== 'I') {
    aviso('tipoComprobante', 'Para notas de crédito (E) o complementos (P) se requiere el nodo CfdiRelacionados con el UUID original; agrégalo antes de timbrar.');
  }
  if (opciones.serie && !/^[A-Za-z0-9]{1,25}$/.test(opciones.serie)) error('serie', 'La serie debe ser alfanumérica (1–25 caracteres, sin espacios).');
  if (opciones.folio && !/^[A-Za-z0-9]{1,40}$/.test(opciones.folio)) error('folio', 'El folio debe ser alfanumérico (1–40 caracteres, sin espacios).');

  const borrador: BorradorCfdi = {
    version: '4.0',
    tipoComprobante,
    serie: opciones.serie ?? 'A',
    folio: opciones.folio ?? String(Date.now()).slice(-8),
    fecha: fechaCfdi(opciones.fecha),
    formaPago,
    metodoPago,
    moneda,
    tipoCambio,
    lugarExpedicion,
    exportacion,
    emisor: { rfc: rfcEmisor, nombre: emisor.nombre.trim(), regimenFiscal: emisor.regimenFiscal },
    receptor: {
      rfc: rfcReceptor,
      nombre: receptor.nombre.trim(),
      domicilioFiscalReceptor: receptor.codigoPostal,
      regimenFiscalReceptor: receptor.regimenFiscal,
      usoCFDI,
    },
    conceptos: calculados,
    totales: {
      subtotal,
      descuento: descuentoTotal,
      totalImpuestosTrasladados: totalTrasladado,
      totalImpuestosRetenidos: totalRetenido,
      total,
      retenciones,
      traslados,
    },
    validaciones,
    listo_para_timbrar: !validaciones.some((v) => v.nivel === 'error'),
  };

  return borrador;
}

// ── Render del XML (sin sellar) ──────────────────────────────────────────────

export function renderXmlCfdi(b: BorradorCfdi): string {
  const attr = (nombre: string, valor: string | number | null | undefined) =>
    valor === null || valor === undefined ? '' : ` ${nombre}="${escaparXml(String(valor))}"`;

  const conceptosXml = b.conceptos
    .map((c) => {
      const traslado =
        c.traslado.tipoFactor === 'Exento'
          ? `<cfdi:Traslado Base="${f2(c.base)}" Impuesto="002" TipoFactor="Exento"/>`
          : `<cfdi:Traslado Base="${f2(c.base)}" Impuesto="002" TipoFactor="Tasa" TasaOCuota="${f6(c.traslado.tasaOCuota!)}" Importe="${f2(c.traslado.importe!)}"/>`;
      const apertura = [
        '    <cfdi:Concepto',
        attr('ClaveProdServ', c.claveProdServ),
        attr('Cantidad', fCant(c.cantidad)),
        attr('ClaveUnidad', c.claveUnidad),
        attr('Unidad', c.unidad),
        attr('Descripcion', c.descripcion),
        attr('ValorUnitario', f2(c.valorUnitario)),
        attr('Importe', f2(c.importe)),
        c.descuento > 0 ? attr('Descuento', f2(c.descuento)) : '',
        attr('ObjetoImp', c.objetoImp),
        '>',
      ].join('');
      return [
        apertura,
        '      <cfdi:Impuestos>',
        '        <cfdi:Traslados>',
        `          ${traslado}`,
        '        </cfdi:Traslados>',
        '      </cfdi:Impuestos>',
        '    </cfdi:Concepto>',
      ].join('\n');
    })
    .join('\n');

  const retencionesXml = b.totales.retenciones.length
    ? [
        '    <cfdi:Retenciones>',
        ...b.totales.retenciones.map((r) => `      <cfdi:Retencion Impuesto="${r.impuesto}" Importe="${f2(r.importe)}"/>`),
        '    </cfdi:Retenciones>',
      ].join('\n')
    : '';

  const trasladosXml = b.totales.traslados
    .map((t) =>
      t.tipoFactor === 'Exento'
        ? `      <cfdi:Traslado Base="${f2(t.base)}" Impuesto="002" TipoFactor="Exento"/>`
        : `      <cfdi:Traslado Base="${f2(t.base)}" Impuesto="002" TipoFactor="Tasa" TasaOCuota="${f6(t.tasaOCuota!)}" Importe="${f2(t.importe!)}"/>`,
    )
    .join('\n');

  const aperturaImpuestos = [
    '  <cfdi:Impuestos',
    b.totales.totalImpuestosRetenidos > 0 ? attr('TotalImpuestosRetenidos', f2(b.totales.totalImpuestosRetenidos)) : '',
    b.totales.totalImpuestosTrasladados > 0 ? attr('TotalImpuestosTrasladados', f2(b.totales.totalImpuestosTrasladados)) : '',
    '>',
  ].join('');
  const impuestosXml = [
    aperturaImpuestos,
    ...(retencionesXml ? [retencionesXml] : []),
    '    <cfdi:Traslados>',
    trasladosXml,
    '    </cfdi:Traslados>',
    '  </cfdi:Impuestos>',
  ].join('\n');

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    [
      `<cfdi:Comprobante`,
      attr('Version', '4.0'),
      attr('Serie', b.serie),
      attr('Folio', b.folio),
      attr('Fecha', b.fecha),
      attr('FormaPago', b.formaPago),
      attr('MetodoPago', b.metodoPago),
      attr('Moneda', b.moneda),
      b.tipoCambio ? attr('TipoCambio', f2(b.tipoCambio)) : '',
      attr('Exportacion', b.exportacion),
      attr('LugarExpedicion', b.lugarExpedicion),
      attr('SubTotal', f2(b.totales.subtotal)),
      b.totales.descuento > 0 ? attr('Descuento', f2(b.totales.descuento)) : '',
      attr('Total', f2(b.totales.total)),
      attr('TipoDeComprobante', b.tipoComprobante),
      ' xmlns:cfdi="http://www.sat.gob.mx/cfd/4"',
      ' xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"',
      ' xsi:schemaLocation="http://www.sat.gob.mx/cfd/4 http://www.sat.gob.mx/sitio_internet/cfd/4/cfdv40.xsd">',
    ].join(''),
    `  <cfdi:Emisor${attr('Rfc', b.emisor.rfc)}${attr('Nombre', b.emisor.nombre)}${attr('RegimenFiscal', b.emisor.regimenFiscal)}/>`,
    [
      `  <cfdi:Receptor`,
      attr('Rfc', b.receptor.rfc),
      attr('Nombre', b.receptor.nombre),
      attr('DomicilioFiscalReceptor', b.receptor.domicilioFiscalReceptor),
      attr('RegimenFiscalReceptor', b.receptor.regimenFiscalReceptor),
      attr('UsoCFDI', b.receptor.usoCFDI),
      '/>',
    ].join(''),
    '  <cfdi:Conceptos>',
    conceptosXml,
    '  </cfdi:Conceptos>',
    impuestosXml,
    '</cfdi:Comprobante>',
    '',
  ].join('\n');
}

/** Conceptos a partir de los artículos de un pedido. */
export function conceptosDesdePedido(items: { descripcion: string; cantidad: number; precio_unitario: number; importe: number }[]): ConceptoEntrada[] {
  return items.map((i) => ({
    descripcion: i.descripcion,
    cantidad: i.cantidad,
    valorUnitario: i.precio_unitario,
  }));
}
