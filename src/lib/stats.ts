/** Estadística descriptiva y análisis de series, sin dependencias externas. */

export function suma(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0);
}

export function media(xs: number[]): number {
  return xs.length === 0 ? 0 : suma(xs) / xs.length;
}

export function mediana(xs: number[]): number {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 === 0 ? (s[m - 1]! + s[m]!) / 2 : s[m]!;
}

/** Cuantil con interpolación lineal (método tipo Excel PERCENTILE.INC). */
export function cuantil(xs: number[], q: number): number {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const pos = (s.length - 1) * q;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  if (lo === hi) return s[lo]!;
  return s[lo]! + (s[hi]! - s[lo]!) * (pos - lo);
}

export function desviacion(xs: number[], muestra = true): number {
  if (xs.length < 2) return 0;
  const m = media(xs);
  const ss = xs.reduce((acc, x) => acc + (x - m) ** 2, 0);
  return Math.sqrt(ss / (muestra ? xs.length - 1 : xs.length));
}

export interface Resumen {
  n: number;
  suma: number;
  media: number;
  mediana: number;
  desviacion: number;
  minimo: number;
  maximo: number;
  rango: number;
  p25: number;
  p75: number;
  iqr: number;
  atipicos: { valor: number; regla: string }[];
}

const r4 = (n: number) => Number(n.toFixed(4));

export function resumir(xs: number[]): Resumen {
  if (xs.length === 0) throw new Error('Se requiere al menos un valor numérico.');
  const p25 = cuantil(xs, 0.25);
  const p75 = cuantil(xs, 0.75);
  const iqr = p75 - p25;
  const limInf = p25 - 1.5 * iqr;
  const limSup = p75 + 1.5 * iqr;
  const atipicos = xs
    .filter((x) => x < limInf || x > limSup)
    .map((valor) => ({ valor: r4(valor), regla: valor < limInf ? 'bajo (Q1-1.5·IQR)' : 'alto (Q3+1.5·IQR)' }));

  return {
    n: xs.length,
    suma: r4(suma(xs)),
    media: r4(media(xs)),
    mediana: r4(mediana(xs)),
    desviacion: r4(desviacion(xs)),
    minimo: r4(Math.min(...xs)),
    maximo: r4(Math.max(...xs)),
    rango: r4(Math.max(...xs) - Math.min(...xs)),
    p25: r4(p25),
    p75: r4(p75),
    iqr: r4(iqr),
    atipicos,
  };
}

/** Correlación de Pearson entre dos series de igual longitud. */
export function pearson(xs: number[], ys: number[]): number {
  if (xs.length !== ys.length || xs.length < 2) throw new Error('Series de distinta longitud o insuficientes.');
  const mx = media(xs);
  const my = media(ys);
  let num = 0;
  let dx = 0;
  let dy = 0;
  for (let i = 0; i < xs.length; i++) {
    num += (xs[i]! - mx) * (ys[i]! - my);
    dx += (xs[i]! - mx) ** 2;
    dy += (ys[i]! - my) ** 2;
  }
  const den = Math.sqrt(dx * dy);
  return den === 0 ? 0 : num / den;
}

export interface Regresion {
  pendiente: number;
  intercepto: number;
  r2: number;
  pronosticoSiguiente: number;
  interpretacion: string;
}

/** Mínimos cuadrados sobre el índice de la serie (0..n-1). */
export function regresionLineal(ys: number[]): Regresion {
  if (ys.length < 2) throw new Error('Se requieren al menos 2 puntos para calcular una tendencia.');
  const xs = ys.map((_, i) => i);
  const mx = media(xs);
  const my = media(ys);
  let num = 0;
  let den = 0;
  for (let i = 0; i < xs.length; i++) {
    num += (xs[i]! - mx) * (ys[i]! - my);
    den += (xs[i]! - mx) ** 2;
  }
  const pendiente = den === 0 ? 0 : num / den;
  const intercepto = my - pendiente * mx;
  const r = pearson(xs, ys);
  const r2 = r * r;
  const pronostico = pendiente * ys.length + intercepto;
  const dir = pendiente > 0 ? 'creciente' : pendiente < 0 ? 'decreciente' : 'estable';
  return {
    pendiente: r4(pendiente),
    intercepto: r4(intercepto),
    r2: r4(r2),
    pronosticoSiguiente: r4(pronostico),
    interpretacion: `Tendencia ${dir}: ${pendiente > 0 ? '+' : ''}${r4(pendiente)} por periodo (R²=${r4(r2)}).`,
  };
}

export function mediaMovil(xs: number[], ventana: number): (number | null)[] {
  if (ventana < 1) throw new Error('La ventana debe ser >= 1.');
  return xs.map((_, i) => {
    if (i + 1 < ventana) return null;
    return r4(media(xs.slice(i + 1 - ventana, i + 1)));
  });
}

export function cambioPct(actual: number, anterior: number): number {
  if (anterior === 0) throw new Error('El valor anterior no puede ser cero para calcular el cambio porcentual.');
  return r4(((actual - anterior) / Math.abs(anterior)) * 100);
}

/** Tasa de crecimiento anual compuesta, en porcentaje. */
export function cagr(inicial: number, final: number, periodos: number): number {
  if (inicial <= 0) throw new Error('El valor inicial debe ser mayor que cero.');
  if (final <= 0) throw new Error('El valor final debe ser mayor que cero.');
  if (periodos <= 0) throw new Error('Los periodos deben ser mayores que cero.');
  return r4(((Math.pow(final / inicial, 1 / periodos) - 1) * 100));
}
