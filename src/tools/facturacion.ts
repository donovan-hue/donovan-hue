import { z } from 'zod';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { PedidosRepo } from '../db/negocio.js';
import { empresaActiva, empresaActivaDetalle } from '../lib/empresas.js';
import {
  CATALOGO_EXPORTACION,
  CATALOGO_FORMAS_PAGO,
  CATALOGO_METODOS_PAGO,
  CATALOGO_MONEDAS,
  CATALOGO_REGIMENES,
  CATALOGO_UNIDADES,
  CATALOGO_USOS_CFDI,
  generarBorradorCfdi,
  renderXmlCfdi,
  type DatosFiscales,
  type OpcionesCfdi,
} from '../lib/cfdi.js';
import { log } from '../logger.js';

const emisorSchema = z
  .object({
    rfc: z.string().describe('RFC del emisor (12 o 13 caracteres).'),
    nombre: z.string().describe('Razón social o nombre tal como está en la constancia de situación fiscal.'),
    regimen_fiscal: z.string().describe('Clave del régimen fiscal del emisor, ej. "601", "612", "626".'),
    codigo_postal: z.string().describe('Código postal del domicilio fiscal del emisor (5 dígitos).'),
  })
  .describe('Datos del emisor. Si se configuran las variables CFDI_EMISOR_* puedes omitirlo.');

const receptorSchema = z.object({
  rfc: z.string().describe('RFC del receptor. Para ventas al público usa "XAXX010101000".'),
  nombre: z.string().describe('Nombre o razón social del receptor. Para el RFC genérico debe ser "PÚBLICO EN GENERAL".'),
  regimen_fiscal: z.string().describe('Régimen fiscal del receptor, ej. "601", "612", "616".'),
  codigo_postal: z.string().describe('Código postal del domicilio fiscal del receptor.'),
  uso_cfdi: z.string().optional().describe('Uso del CFDI, ej. "G03" gastos en general, "G01" mercancías, "S01" sin efectos. Por defecto G03.'),
});

const opcionesSchema = {
  serie: z.string().optional().describe('Serie del comprobante, ej. "A".'),
  folio: z.string().optional().describe('Folio del comprobante. Si se omite se genera uno.'),
  forma_pago: z.string().optional().describe('Clave de forma de pago, ej. "01" efectivo, "03" transferencia, "99" por definir (PPD).'),
  metodo_pago: z.enum(['PUE', 'PPD']).optional().describe('PUE (una exhibición) o PPD (parcialidades/diferido).'),
  moneda: z.string().optional().describe('Moneda (MXN, USD…). Por defecto MXN.'),
  tipo_cambio: z.number().positive().optional().describe('Tipo de cambio requerido cuando la moneda no es MXN.'),
  lugar_expedicion: z.string().optional().describe('Código postal de expedición. Por defecto el del emisor.'),
  iva_pct: z.number().min(0).max(100).optional().describe('Tasa de IVA: 16, 8 (frontera) o 0. Por defecto 16.'),
  exento: z.boolean().optional().describe('Si es true, los conceptos se marcan como exentos de IVA.'),
  retencion_isr_pct: z.number().min(0).max(100).optional().describe('Retención de ISR (ej. 1.25 honorarios).'),
  retencion_iva_pct: z.number().min(0).max(100).optional().describe('Retención de IVA sobre la base (ej. 10.6667 = 2/3 del IVA).'),
  tipo_comprobante: z.enum(['I', 'E', 'P']).optional().describe('I ingreso (por defecto), E egreso/nota de crédito, P pago.'),
} as const;

export function facturacionToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  const emisorConfigurado = (): DatosFiscales | null => {
    const c = ctx.config;
    if (c.cfdiEmisorRfc && c.cfdiEmisorNombre && c.cfdiEmisorRegimen && c.cfdiEmisorCp) {
      return { rfc: c.cfdiEmisorRfc, nombre: c.cfdiEmisorNombre, regimenFiscal: c.cfdiEmisorRegimen, codigoPostal: c.cfdiEmisorCp };
    }
    return null;
  };

  const resolverEmisor = (entrada?: { rfc: string; nombre: string; regimen_fiscal: string; codigo_postal: string }): DatosFiscales => {
    if (entrada) {
      return { rfc: entrada.rfc, nombre: entrada.nombre, regimenFiscal: entrada.regimen_fiscal, codigoPostal: entrada.codigo_postal };
    }
    const conf = emisorConfigurado();
    if (conf) return conf;
    // Multi-empresa: si la empresa activa tiene datos fiscales, son el emisor.
    const emp = empresaActivaDetalle(ctx);
    if (emp.rfc && emp.nombre && emp.regimen && emp.cp) {
      return { rfc: emp.rfc, nombre: emp.nombre, regimenFiscal: emp.regimen, codigoPostal: emp.cp };
    }
    throw new Error(
      `Falta el emisor: completa los datos fiscales de la empresa "${emp.nombre}" (\`empresa_editar\` con rfc, regimen y cp), pásalo en el parámetro "emisor", o configura CFDI_EMISOR_RFC, CFDI_EMISOR_NOMBRE, CFDI_EMISOR_REGIMEN y CFDI_EMISOR_CP.`,
    );
  };

  const aOpciones = (args: Record<string, any>): OpcionesCfdi => ({
    // La serie por defecto es la de la empresa activa (cada empresa folia lo suyo).
    serie: args.serie ?? empresaActivaDetalle(ctx).series,
    folio: args.folio,
    formaPago: args.forma_pago,
    metodoPago: args.metodo_pago,
    moneda: args.moneda,
    tipoCambio: args.tipo_cambio,
    lugarExpedicion: args.lugar_expedicion,
    ivaPct: args.iva_pct,
    exento: args.exento,
    retencionIsrPct: args.retencion_isr_pct,
    retencionIvaPct: args.retencion_iva_pct,
    tipoComprobante: args.tipo_comprobante,
  });

  const respuesta = async (borrador: ReturnType<typeof generarBorradorCfdi>, incluirXml: boolean, guardar: boolean) => {
    const xml = incluirXml || guardar ? renderXmlCfdi(borrador) : null;
    let guardadoEn: string | null = null;
    if (guardar && xml) {
      const dir = path.join(ctx.config.dataDir, 'facturacion');
      const destino = path.join(dir, `${borrador.serie}${borrador.folio}.xml`);
      await fs.mkdir(dir, { recursive: true });
      await fs.writeFile(destino, xml, 'utf8');
      guardadoEn = destino;
      log.info('cfdi_xml_guardado', { archivo: destino, total: borrador.totales.total, receptor: borrador.receptor.rfc });
    }
    return {
      listo_para_timbrar: borrador.listo_para_timbrar,
      errores: borrador.validaciones.filter((v) => v.nivel === 'error'),
      avisos: borrador.validaciones.filter((v) => v.nivel === 'aviso'),
      comprobante: {
        version: borrador.version,
        tipo: borrador.tipoComprobante,
        serie: borrador.serie,
        folio: borrador.folio,
        fecha: borrador.fecha,
        emisor: borrador.emisor,
        receptor: borrador.receptor,
        forma_pago: borrador.formaPago,
        metodo_pago: borrador.metodoPago,
        moneda: borrador.moneda,
        tipo_cambio: borrador.tipoCambio,
        exportacion: borrador.exportacion,
        lugar_expedicion: borrador.lugarExpedicion,
      },
      conceptos: borrador.conceptos,
      totales: borrador.totales,
      xml: incluirXml ? xml : '(omito el XML; usa incluir_xml=true para verlo)',
      xml_guardado_en: guardadoEn,
      siguiente_paso: guardadoEn
        ? `Envía ${guardadoEn} a tu PAC para sellado y timbrado con el CSD del emisor.`
        : 'Entrega el XML a tu PAC (Proveedor Autorizado de Certificación) para el timbrado.',
    };
  };

  push({
    module: 'facturacion',
    name: 'cfdi_generar',
    title: 'Generar CFDI 4.0 (borrador + XML)',
    description:
      'Genera un borrador de CFDI 4.0 (México) listo para timbrar: valida RFC, regímenes, uso del CFDI, forma y método de pago contra el Anexo 20; calcula IVA (16/8/0 o exento), retenciones de ISR/IVA y total; y produce el XML SIN SELLAR.\n\nNo timbra: el sellado lo hace un PAC autorizado con el CSD del emisor. Opcionalmente guarda el XML en datos/facturacion/.\n\nEjemplos de uso: "factúrale a Distribuidora Jalisco SA de CV (RFC DJA240118H72, régimen 601, CP 44100) por 10 teclados a 1290 con transferencia", "genera el CFDI de una consultoría por 10000 con retención de ISR 1.25% y de IVA 10.6667% y guárdalo".',
    inputSchema: {
      emisor: emisorSchema.optional(),
      receptor: receptorSchema,
      conceptos: z
        .array(
          z.object({
            descripcion: z.string(),
            cantidad: z.number().positive(),
            valor_unitario: z.number().nonnegative(),
            clave_prodserv: z.string().optional().describe('Clave de producto/servicio del SAT (8 dígitos). Por defecto 01010101 (comodín).'),
            clave_unidad: z.string().optional().describe('Clave de unidad del SAT, ej. "E48" servicio, "H87" pieza.'),
            unidad: z.string().optional().describe('Nombre de la unidad, ej. "Pieza".'),
            descuento: z.number().nonnegative().optional().describe('Descuento en pesos para el concepto.'),
          }),
        )
        .min(1)
        .describe('Conceptos de la factura.'),
      ...opcionesSchema,
      incluir_xml: z.boolean().optional().describe('Incluir el XML en la respuesta (por defecto true).'),
      guardar_xml: z.boolean().optional().describe('Guardar el XML en datos/facturacion/ (por defecto false).'),
    },
    handler: async (args: any) => {
      const emisor = resolverEmisor(args.emisor);
      const receptor: DatosFiscales = {
        rfc: args.receptor.rfc,
        nombre: args.receptor.nombre,
        regimenFiscal: args.receptor.regimen_fiscal,
        codigoPostal: args.receptor.codigo_postal,
        usoCfdi: args.receptor.uso_cfdi,
      };
      const conceptos = args.conceptos.map((c: any) => ({
        descripcion: c.descripcion,
        cantidad: c.cantidad,
        valorUnitario: c.valor_unitario,
        claveProdServ: c.clave_prodserv,
        claveUnidad: c.clave_unidad,
        unidad: c.unidad,
        descuento: c.descuento,
      }));
      const borrador = generarBorradorCfdi(emisor, receptor, conceptos, aOpciones(args));
      return respuesta(borrador, args.incluir_xml ?? true, args.guardar_xml ?? false);
    },
  });

  push({
    module: 'facturacion',
    name: 'cfdi_desde_pedido',
    title: 'Facturar un pedido',
    description:
      'Genera el borrador de CFDI 4.0 a partir de un pedido existente (folio P-####): toma los conceptos y precios del pedido y solo necesitas los datos fiscales del receptor. Recomendado para facturar pedidos sin recapturar información.\n\nEjemplo: "factura el pedido P-0002 a Coworking Chapultepec, RFC CCH190903K31, régimen 601, CP 44100, uso G01".',
    inputSchema: {
      folio_pedido: z.string().describe('Folio del pedido a facturar, ej. "P-0002".'),
      emisor: emisorSchema.optional(),
      receptor: receptorSchema,
      ...opcionesSchema,
      incluir_xml: z.boolean().optional().describe('Incluir el XML en la respuesta (por defecto true).'),
      guardar_xml: z.boolean().optional().describe('Guardar el XML en datos/facturacion/ (por defecto false).'),
    },
    handler: async (args: any) => {
      const pedido = new PedidosRepo(ctx.db, empresaActiva(ctx)).listar({ folio: args.folio_pedido })[0];
      if (!pedido) throw new Error(`No existe el pedido "${args.folio_pedido}".`);
      if (pedido.estado === 'cancelado') throw new Error(`El pedido ${pedido.folio} está cancelado; no se puede facturar.`);

      const emisor = resolverEmisor(args.emisor);
      const receptor: DatosFiscales = {
        rfc: args.receptor.rfc,
        nombre: args.receptor.nombre,
        regimenFiscal: args.receptor.regimen_fiscal,
        codigoPostal: args.receptor.codigo_postal,
        usoCfdi: args.receptor.uso_cfdi,
      };
      const conceptos = pedido.items.map((i) => ({
        descripcion: i.descripcion,
        cantidad: i.cantidad,
        valorUnitario: i.precio_unitario,
      }));
      const borrador = generarBorradorCfdi(emisor, receptor, conceptos, {
        ...aOpciones(args),
        folio: args.folio ?? pedido.folio.replace(/^P-/, ''),
      });
      const salida = await respuesta(borrador, args.incluir_xml ?? true, args.guardar_xml ?? false);
      return { pedido: { folio: pedido.folio, cliente: pedido.cliente, estado: pedido.estado, total_pedido: pedido.total }, ...salida };
    },
  });

  push({
    module: 'facturacion',
    name: 'cfdi_validar_datos_fiscales',
    title: 'Validar datos fiscales',
    description:
      'Valida (sin generar XML) los datos fiscales de una contraparte antes de facturar: formato de RFC, existencia del régimen fiscal, reglas de público en general (XAXX010101000), uso del CFDI contra el régimen, forma/método de pago y moneda. Úsala para limpiar la ficha de un cliente nuevo o detectar por qué un PAC rechazaría el comprobante.',
    readOnly: true,
    inputSchema: {
      emisor: emisorSchema.optional(),
      receptor: receptorSchema,
      forma_pago: opcionesSchema.forma_pago,
      metodo_pago: opcionesSchema.metodo_pago,
      moneda: opcionesSchema.moneda,
      tipo_cambio: opcionesSchema.tipo_cambio,
      exento: opcionesSchema.exento,
      retencion_isr_pct: opcionesSchema.retencion_isr_pct,
      retencion_iva_pct: opcionesSchema.retencion_iva_pct,
    },
    handler: async (args: any) => {
      const emisor = resolverEmisor(args.emisor);
      const receptor: DatosFiscales = {
        rfc: args.receptor.rfc,
        nombre: args.receptor.nombre,
        regimenFiscal: args.receptor.regimen_fiscal,
        codigoPostal: args.receptor.codigo_postal,
        usoCfdi: args.receptor.uso_cfdi,
      };
      // Se reutiliza el motor de validación con un concepto simbólico y se descartan sus avisos de concepto.
      const borrador = generarBorradorCfdi(emisor, receptor, [{ descripcion: 'Validación de datos fiscales', cantidad: 1, valorUnitario: 1 }], aOpciones(args));
      const validaciones = borrador.validaciones.filter((v) => !v.campo.startsWith('conceptos['));
      return {
        valido: validaciones.every((v) => v.nivel !== 'error'),
        errores: validaciones.filter((v) => v.nivel === 'error'),
        avisos: validaciones.filter((v) => v.nivel === 'aviso'),
        nota: validaciones.length === 0 ? 'Los datos cumplen las reglas revisadas del Anexo 20.' : undefined,
      };
    },
  });

  push({
    module: 'facturacion',
    name: 'catalogo_sat_consultar',
    title: 'Consultar catálogos del SAT',
    description:
      'Consulta rápidamente los catálogos del SAT incluidos: regímenes fiscales, usos del CFDI, formas de pago, métodos de pago, monedas, unidades y claves de exportación. Úsala cuando no sepas qué clave corresponde (ej. "¿qué clave es transferencia?", "¿qué uso del CFDI para comprar equipo de cómputo?").',
    readOnly: true,
    inputSchema: {
      tipo: z
        .enum(['regimen_fiscal', 'uso_cfdi', 'forma_pago', 'metodo_pago', 'moneda', 'unidad', 'exportacion'])
        .describe('Catálogo a consultar.'),
      query: z.string().optional().describe('Filtro por clave o descripción (ej. "transferencia", "G01", "honorarios").'),
    },
    handler: async (args: { tipo: string; query?: string }) => {
      const catalogos: Record<string, readonly { clave: string; descripcion: string }[]> = {
        regimen_fiscal: CATALOGO_REGIMENES,
        uso_cfdi: CATALOGO_USOS_CFDI,
        forma_pago: CATALOGO_FORMAS_PAGO,
        metodo_pago: CATALOGO_METODOS_PAGO,
        moneda: CATALOGO_MONEDAS,
        unidad: CATALOGO_UNIDADES,
        exportacion: CATALOGO_EXPORTACION,
      };
      const base = catalogos[args.tipo]!;
      const q = args.query?.toLowerCase();
      const resultados = q
        ? base.filter((c) => c.clave.toLowerCase().includes(q) || c.descripcion.toLowerCase().includes(q))
        : base;
      return {
        catalogo: args.tipo,
        total: resultados.length,
        resultados,
        nota: 'Subconjunto de uso frecuente. Los catálogos oficiales vigentes están publicados por el SAT (Anexo 20).',
      };
    },
  });

  push({
    module: 'facturacion',
    name: 'cfdi_retenciones_calcular',
    title: 'Calcular retenciones para CFDI',
    description:
      'Calcula el desglose exacto de un CFDI de honorarios o servicios profesionales con retenciones de ISR y IVA: base, IVA trasladado, retención de ISR (sobre la base), retención de IVA (10.6667% sobre la base = 2/3 del IVA) y total a pagar. Sirve para anticipar cuánto te pagará realmente el cliente.',
    readOnly: true,
    inputSchema: {
      monto: z.number().positive().describe('Monto de los honorarios/servicios sin IVA (base).'),
      retencion_isr_pct: z.number().min(0).max(100).optional().describe('Retención de ISR en %; por defecto 1.25 (honorarios a personas morales).'),
      retencion_iva_pct: z.number().min(0).max(100).optional().describe('Retención de IVA en % sobre la base; por defecto 10.6667.'),
      iva_pct: z.number().min(0).max(100).optional().describe('Tasa de IVA aplicable; por defecto 16.'),
    },
    handler: async (args: { monto: number; retencion_isr_pct?: number; retencion_iva_pct?: number; iva_pct?: number }) => {
      const isrPct = args.retencion_isr_pct ?? 1.25;
      const ivaRetPct = args.retencion_iva_pct ?? 10.6667;
      const ivaPct = args.iva_pct ?? ctx.config.ivaPct;
      const r2 = (n: number) => Number(n.toFixed(2));
      const base = r2(args.monto);
      const iva = r2(base * (ivaPct / 100));
      const retIsr = r2(base * (isrPct / 100));
      const retIva = r2(base * (ivaRetPct / 100));
      return {
        base,
        iva_trasladado: iva,
        retencion_isr: retIsr,
        retencion_iva: retIva,
        total_retenciones: r2(retIsr + retIva),
        total_a_pagar: r2(base + iva - retIsr - retIva),
        equivalencia: `La retención de IVA equivale a ${((ivaRetPct / ivaPct) * 100).toFixed(1)}% del IVA trasladado.`,
        nota: 'Aplica cuando quien factura es persona física por honorarios/arrendamiento a una persona moral. Verifica tu caso particular con tu contador.',
      };
    },
  });
}
