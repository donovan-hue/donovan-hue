import { describe, expect, it } from 'vitest';
import { cagr, cambioPct, cuantil, media, mediaMovil, mediana, pearson, regresionLineal, resumir } from '../src/lib/stats';

describe('medidas básicas', () => {
  it('media y mediana', () => {
    expect(media([1, 2, 3, 4])).toBe(2.5);
    expect(mediana([1, 2, 3, 4])).toBe(2.5);
    expect(mediana([5, 1, 9])).toBe(5);
  });

  it('cuantiles con interpolación lineal', () => {
    expect(cuantil([1, 2, 3, 4], 0.5)).toBeCloseTo(2.5, 6);
    expect(cuantil([1, 2, 3, 4], 0.25)).toBeCloseTo(1.75, 6);
  });
});

describe('resumir', () => {
  it('calcula cuartiles, IQR y detecta outliers', () => {
    const r = resumir([10, 12, 12, 13, 14, 15, 90]);
    expect(r.n).toBe(7);
    expect(r.mediana).toBe(13);
    // Cuantil con interpolación lineal (equivalente a PERCENTILE.INC de Excel).
    expect(r.p75).toBe(14.5);
    expect(r.iqr).toBe(2.5);
    expect(r.atipicos.some((a) => a.valor === 90)).toBe(true);
    expect(r.atipicos[0]!.regla).toMatch(/alto/);
  });

  it('falla con arreglo vacío', () => {
    expect(() => resumir([])).toThrow(/al menos un valor/);
  });
});

describe('series y tendencias', () => {
  it('regresión lineal perfecta con R²=1', () => {
    const r = regresionLineal([10, 20, 30, 40]);
    expect(r.pendiente).toBe(10);
    expect(r.r2).toBe(1);
    expect(r.pronosticoSiguiente).toBe(50);
    expect(r.interpretacion).toMatch(/creciente/);
  });

  it('media móvil con ventana 3', () => {
    expect(mediaMovil([1, 2, 3, 4, 5], 3)).toEqual([null, null, 2, 3, 4]);
  });

  it('correlación de Pearson positiva y negativa', () => {
    expect(pearson([1, 2, 3], [2, 4, 6])).toBeCloseTo(1, 6);
    expect(pearson([1, 2, 3], [6, 4, 2])).toBeCloseTo(-1, 6);
    expect(() => pearson([1, 2], [1])).toThrow(/distinta longitud/);
  });

  it('cambio porcentual y CAGR', () => {
    expect(cambioPct(120, 100)).toBe(20);
    expect(() => cambioPct(120, 0)).toThrow(/cero/);
    expect(cagr(100, 121, 2)).toBeCloseTo(10, 2);
  });
});
