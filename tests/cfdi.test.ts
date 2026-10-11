import { describe, expect, it } from 'vitest';
import {
  CP_PUBLICO_GENERAL,
  RFC_PUBLICO_GENERAL,
  esPersonaFisica,
  generarBorradorCfdi,
  renderXmlCfdi,
  validarFormatoRfc,
  type DatosFiscales,
} from '../src/lib/cfdi';

const emisorMoral: DatosFiscales = { rfc: 'DJA240118H72', nombre: 'Distribuidora Jalisco SA de CV', regimenFiscal: '601', codigoPostal: '44100' };
const receptorMoral: DatosFiscales = { rfc: 'CCH190903K31', nombre: 'Coworking Chapultepec SA de CV', regimenFiscal: '601', codigoPostal: '44100', usoCfdi: 'G01' };
const receptorPublico: DatosFiscales = { rfc: RFC_PUBLICO_GENERAL, nombre: 'PÚBLICO EN GENERAL', regimenFiscal: '616', codigoPostal: CP_PUBLICO_GENERAL, usoCfdi: 'S01' };

const error = (v: { nivel: string; campo: string }[], campo: string) => v.some((x) => x.nivel === 'error' && x.campo === campo);

describe('validación de RFC', () => {
  it('acepta personas morales y físicas con formato correcto', () => {
    expect(validarFormatoRfc('DJA240118H72')).toBe(true);
    expect(validarFormatoRfc('LOPA850312KD3')).toBe(true);
    expect(validarFormatoRfc('A&B120101AB1')).toBe(true);
    expect(esPersonaFisica('LOPA850312KD3')).toBe(true);
    expect(esPersonaFisica('DJA240118H72')).toBe(false);
  });

  it('rechaza formatos inválidos', () => {
    expect(validarFormatoRfc('XAXX01010100')).toBe(false); // 11 caracteres
    expect(validarFormatoRfc('1234567890123')).toBe(false);
    expect(validarFormatoRfc('ABC123')).toBe(false);
  });
});

describe('generarBorradorCfdi', () => {
  it('calcula subtotal, IVA 16% y total en un caso limpio', () => {
    const b = generarBorradorCfdi(emisorMoral, receptorMoral, [
      { descripcion: 'Teclado mecánico', cantidad: 10, valorUnitario: 1290, claveProdServ: '43211500', claveUnidad: 'H87', unidad: 'Pieza' },
      { descripcion: 'Instalación', cantidad: 1, valorUnitario: 500, claveUnidad: 'E48' },
    ]);
    expect(b.listo_para_timbrar).toBe(true);
    expect(b.totales.subtotal).toBe(13400);
    expect(b.totales.totalImpuestosTrasladados).toBe(2144);
    expect(b.totales.total).toBe(15544);
    expect(b.conceptos[0]!.traslado).toMatchObject({ impuesto: '002', tipoFactor: 'Tasa', tasaOCuota: 0.16, importe: 2064 });
    expect(b.validaciones.filter((v) => v.nivel === 'error')).toHaveLength(0);
  });

  it('aplica descuento por concepto y calcula el IVA sobre la base neta', () => {
    const b = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'Servicio', cantidad: 1, valorUnitario: 1000, descuento: 200 }]);
    expect(b.totales.subtotal).toBe(1000);
    expect(b.totales.descuento).toBe(200);
    expect(b.totales.totalImpuestosTrasladados).toBe(128);
    expect(b.totales.total).toBe(928);
  });

  it('calcula retenciones de honorarios (ISR 1.25% y IVA 10.6667% sobre la base)', () => {
    const b = generarBorradorCfdi(
      { rfc: 'LOPA850312KD3', nombre: 'Laura López', regimenFiscal: '612', codigoPostal: '45500' },
      receptorMoral,
      [{ descripcion: 'Consultoría fiscal', cantidad: 1, valorUnitario: 10000, claveUnidad: 'E48' }],
      { retencionIsrPct: 1.25, retencionIvaPct: 10.6667 },
    );
    expect(b.totales.totalImpuestosTrasladados).toBe(1600);
    expect(b.totales.retenciones).toEqual([
      { impuesto: '001', importe: 125 },
      { impuesto: '002', importe: 1066.67 },
    ]);
    expect(b.totales.totalImpuestosRetenidos).toBe(1191.67);
    expect(b.totales.total).toBe(10408.33);
    expect(b.listo_para_timbrar).toBe(true);
  });

  it('maneja operaciones exentas', () => {
    const b = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'Medicamento', cantidad: 2, valorUnitario: 300 }], { exento: true });
    expect(b.totales.totalImpuestosTrasladados).toBe(0);
    expect(b.totales.total).toBe(600);
    expect(b.totales.traslados[0]).toMatchObject({ tipoFactor: 'Exento' });
    expect(b.conceptos[0]!.traslado.tipoFactor).toBe('Exento');
  });

  it('rechaza retenciones junto con operación exenta', () => {
    const b = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 100 }], {
      exento: true,
      retencionIsrPct: 1.25,
    });
    expect(b.listo_para_timbrar).toBe(false);
    expect(error(b.validaciones, 'retenciones')).toBe(true);
  });

  it('valida las reglas de público en general (XAXX010101000)', () => {
    const incorrecto = generarBorradorCfdi(
      emisorMoral,
      { rfc: RFC_PUBLICO_GENERAL, nombre: 'Juan Pérez', regimenFiscal: '612', codigoPostal: '44100', usoCfdi: 'G03' },
      [{ descripcion: 'Producto', cantidad: 1, valorUnitario: 100 }],
    );
    expect(incorrecto.listo_para_timbrar).toBe(false);
    expect(error(incorrecto.validaciones, 'receptor.nombre')).toBe(true);
    expect(error(incorrecto.validaciones, 'receptor.codigoPostal')).toBe(true);
    expect(error(incorrecto.validaciones, 'receptor.regimenFiscal')).toBe(true);
    expect(error(incorrecto.validaciones, 'receptor.usoCfdi')).toBe(true);

    const correcto = generarBorradorCfdi(emisorMoral, receptorPublico, [{ descripcion: 'Producto', cantidad: 1, valorUnitario: 100 }], { formaPago: '01' });
    expect(correcto.listo_para_timbrar).toBe(true);
  });

  it('aplica las reglas de forma y método de pago (PPD/PUE)', () => {
    const ppdMal = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 100 }], {
      metodoPago: 'PPD',
      formaPago: '03',
    });
    expect(error(ppdMal.validaciones, 'formaPago')).toBe(true);

    const ppdBien = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 100 }], {
      metodoPago: 'PPD',
      formaPago: '99',
    });
    expect(ppdBien.listo_para_timbrar).toBe(true);

    const pueMal = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 100 }], { metodoPago: 'PUE', formaPago: '99' });
    expect(error(pueMal.validaciones, 'formaPago')).toBe(true);
  });

  it('valida regímenes, uso del CFDI y moneda', () => {
    const regimenMalo = generarBorradorCfdi({ ...emisorMoral, regimenFiscal: '999' }, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 10 }]);
    expect(error(regimenMalo.validaciones, 'emisor.regimenFiscal')).toBe(true);

    const emisor616 = generarBorradorCfdi({ ...emisorMoral, regimenFiscal: '616' }, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 10 }]);
    expect(error(emisor616.validaciones, 'emisor.regimenFiscal')).toBe(true);

    const receptor616 = generarBorradorCfdi(emisorMoral, { ...receptorMoral, regimenFiscal: '616', usoCfdi: 'G03' }, [
      { descripcion: 'X', cantidad: 1, valorUnitario: 10 },
    ]);
    expect(error(receptor616.validaciones, 'receptor.usoCfdi')).toBe(true);

    const sinTipoCambio = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 10 }], { moneda: 'USD' });
    expect(error(sinTipoCambio.validaciones, 'tipoCambio')).toBe(true);

    const conTipoCambio = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 10 }], { moneda: 'USD', tipoCambio: 17.5 });
    expect(conTipoCambio.listo_para_timbrar).toBe(true);
    expect(conTipoCambio.tipoCambio).toBe(17.5);
  });

  it('avisa cuando se usa la clave comodín o el uso S01', () => {
    const b = generarBorradorCfdi(emisorMoral, { ...receptorMoral, usoCfdi: 'S01' }, [{ descripcion: 'X', cantidad: 1, valorUnitario: 10 }]);
    expect(b.validaciones.some((v) => v.nivel === 'aviso' && v.campo === 'receptor.usoCfdi')).toBe(true);
    expect(b.validaciones.some((v) => v.nivel === 'aviso' && v.campo === 'conceptos[1].claveProdServ')).toBe(true);
  });

  it('rechaza conceptos inválidos', () => {
    expect(() => generarBorradorCfdi(emisorMoral, receptorMoral, [])).toThrow(/al menos un concepto/);
    const b = generarBorradorCfdi(emisorMoral, receptorMoral, [{ descripcion: 'X', cantidad: 1, valorUnitario: 100, descuento: 500 }]);
    expect(error(b.validaciones, 'conceptos[1].descuento')).toBe(true);
  });
});

describe('renderXmlCfdi', () => {
  it('produce un XML 4.0 bien formado con nodos y atributos del Anexo 20', () => {
    const b = generarBorradorCfdi(
      emisorMoral,
      receptorMoral,
      [{ descripcion: 'Servicio A&B', cantidad: 2, valorUnitario: 1000, claveProdServ: '43211500', claveUnidad: 'H87', unidad: 'Pieza' }],
      { serie: 'A', folio: '1234', formaPago: '03' },
    );
    const xml = renderXmlCfdi(b);
    expect(xml).toContain('<?xml version="1.0" encoding="UTF-8"?>');
    expect(xml).toContain('<cfdi:Comprobante');
    expect(xml).toContain('Version="4.0"');
    expect(xml).toContain('Serie="A"');
    expect(xml).toContain('Folio="1234"');
    expect(xml).toContain('FormaPago="03"');
    expect(xml).toContain('MetodoPago="PUE"');
    expect(xml).toContain('Moneda="MXN"');
    expect(xml).toContain('Exportacion="01"');
    expect(xml).toContain('LugarExpedicion="44100"');
    expect(xml).toContain('Total="2320.00"');
    expect(xml).toContain('<cfdi:Emisor Rfc="DJA240118H72" Nombre="Distribuidora Jalisco SA de CV" RegimenFiscal="601"/>');
    expect(xml).toContain('<cfdi:Receptor Rfc="CCH190903K31"');
    expect(xml).toContain('DomicilioFiscalReceptor="44100"');
    expect(xml).toContain('UsoCFDI="G01"');
    expect(xml).toContain('ObjetoImp="02"');
    expect(xml).toContain('TasaOCuota="0.160000"');
    expect(xml).toContain('TotalImpuestosTrasladados="320.00"');
    expect(xml).toContain('Servicio A&amp;B'); // escapado correcto
    expect(xml).toContain('xmlns:cfdi="http://www.sat.gob.mx/cfd/4"');
  });

  it('incluye retenciones en el nodo de impuestos cuando existen', () => {
    const b = generarBorradorCfdi(
      { rfc: 'LOPA850312KD3', nombre: 'Laura López', regimenFiscal: '612', codigoPostal: '45500' },
      receptorMoral,
      [{ descripcion: 'Honorarios', cantidad: 1, valorUnitario: 10000 }],
      { retencionIsrPct: 1.25, retencionIvaPct: 10.6667 },
    );
    const xml = renderXmlCfdi(b);
    expect(xml).toContain('TotalImpuestosRetenidos="1191.67"');
    expect(xml).toContain('<cfdi:Retencion Impuesto="001" Importe="125.00"/>');
    expect(xml).toContain('<cfdi:Retencion Impuesto="002" Importe="1066.67"/>');
    expect(xml).toContain('Total="10408.33"');
  });
});
