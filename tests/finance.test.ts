import { describe, expect, it } from 'vitest';
import {
  aplicarMovimiento,
  calcularCotizacion,
  calcularImpuestos,
  calcularMargen,
  puntoEquilibrio,
  validarTransicion,
} from '../src/lib/finance';

describe('calcularCotizacion', () => {
  it('calcula línea por línea, IVA 16% y total', () => {
    const r = calcularCotizacion([
      { descripcion: 'Teclado', cantidad: 2, precioUnitario: 1000 },
      { descripcion: 'Mouse', cantidad: 1, precioUnitario: 500 },
    ]);
    expect(r.subtotal).toBe(2500);
    expect(r.iva).toBe(400);
    expect(r.total).toBe(2900);
    expect(r.lineas[0]).toMatchObject({ importe: 2000 });
  });

  it('aplica descuento global antes de impuestos y retenciones', () => {
    const r = calcularCotizacion([{ descripcion: 'Consultoría', cantidad: 1, precioUnitario: 10000 }], {
      descuentoPct: 10,
      retencionIsrPct: 1.25,
      retencionIvaPct: 10.6667,
    });
    expect(r.baseGravable).toBe(9000);
    expect(r.iva).toBe(1440);
    expect(r.retencionIsr).toBe(112.5);
    // Retención de IVA sobre la base (10.6667% ≈ 2/3 del IVA trasladado).
    expect(r.retencionIva).toBe(960);
    expect(r.total).toBe(9367.5);
  });

  it('rechaza conceptos con cantidad cero y porcentajes fuera de rango', () => {
    expect(() => calcularCotizacion([{ descripcion: 'X', cantidad: 0, precioUnitario: 10 }])).toThrow(/cantidad mayor que cero/);
    expect(() => calcularCotizacion([{ descripcion: 'X', cantidad: 1, precioUnitario: 10 }], { descuentoPct: 120 })).toThrow(/entre 0 y 100/);
  });
});

describe('calcularImpuestos', () => {
  it('agrega IVA sobre una base', () => {
    const r = calcularImpuestos(1000, 'agregar');
    expect(r).toMatchObject({ base: 1000, iva: 160, total: 1160 });
  });

  it('desglosa IVA desde un total con IVA incluido', () => {
    const r = calcularImpuestos(1160, 'desglosar');
    expect(r.base).toBe(1000);
    expect(r.iva).toBe(160);
  });

  it('resta retenciones en operaciones a personas morales', () => {
    const r = calcularImpuestos(10000, 'agregar', 16, 11.5);
    expect(r.retenciones).toBe(1150);
    expect(r.total).toBe(10450);
  });
});

describe('calcularMargen', () => {
  it('distingue margen sobre precio y markup sobre costo', () => {
    const r = calcularMargen(1000, 600);
    expect(r.utilidad).toBe(400);
    expect(r.margenPct).toBe(40);
    expect(r.markupPct).toBe(66.67);
    expect(r.precioConIva).toBe(1160);
  });
});

describe('puntoEquilibrio', () => {
  it('calcula unidades y ventas necesarias', () => {
    const r = puntoEquilibrio(50000, 120, 70);
    expect(r.margenContribucion).toBe(50);
    expect(r.unidades).toBe(1000);
    expect(r.ventas).toBe(120000);
  });

  it('falla si el margen de contribución no es positivo', () => {
    expect(() => puntoEquilibrio(10000, 50, 60)).toThrow(/margen de contribución/);
  });
});

describe('aplicarMovimiento y transiciones de pedido', () => {
  it('valida stock insuficiente en salidas', () => {
    expect(aplicarMovimiento(10, 'salida', 4)).toBe(6);
    expect(() => aplicarMovimiento(3, 'salida', 5, 'Tornillos')).toThrow(/Stock insuficiente de Tornillos/);
  });

  it('permite solo transiciones legales', () => {
    expect(() => validarTransicion('borrador', 'confirmado')).not.toThrow();
    expect(() => validarTransicion('borrador', 'enviado')).toThrow(/Transición inválida/);
    expect(() => validarTransicion('entregado', 'cancelado')).toThrow(/estado final/);
  });
});
