/**
 * Módulo «diseño» — el motor. Nada de esto llama a servicios externos: es
 * matemática de color y generación de CSS, todo local.
 *
 * Lo de última generación que sí es estándar y conviene usar:
 *   · OKLCH (CSS Color 4): el espacio donde la luminosidad se comporta como el
 *     ojo espera. Generar tonos en HSL produce saltos y colores sucios; en OKLCH
 *     no. Aquí todo se genera en OKLCH y se convierte a sRGB con corrección de
 *     gama para no salirse del rango.
 *   · WCAG 2.2 para el contraste obligatorio (la norma vigente).
 *   · APCA (algoritmo perceptivo): se reporta como segunda opinión, aclarando
 *     que todavía no es norma.
 *   · Tokens en el formato del grupo de diseño (DTCG: $value / $type), que es lo
 *     que entienden Figma y las herramientas nuevas.
 */

// ── Tipos ─────────────────────────────────────────────────────────────────────

export interface RGB { r: number; g: number; b: number }
export interface OKLCH { l: number; c: number; h: number }

export type Severidad = 'alto' | 'medio' | 'bajo' | 'info';

export interface Hallazgo {
  severidad: Severidad;
  codigo: string;
  mensaje: string;
  linea?: number | null;
  sugerencia?: string;
}

// ── Color: conversiones ───────────────────────────────────────────────────────

const clamp01 = (n: number) => Math.min(1, Math.max(0, n));
const a255 = (n: number) => Math.round(clamp01(n) * 255);

export function hexARgb(hex: string): RGB {
  const t = hex.trim().replace(/^#/, '');
  const t6 = t.length === 3 ? t.split('').map((c) => c + c).join('') : t;
  if (!/^[0-9a-fA-F]{6}$/.test(t6)) throw new Error(`Color hexadecimal inválido: ${hex}`);
  return {
    r: parseInt(t6.slice(0, 2), 16) / 255,
    g: parseInt(t6.slice(2, 4), 16) / 255,
    b: parseInt(t6.slice(4, 6), 16) / 255,
  };
}

export function rgbAHex({ r, g, b }: RGB): string {
  return `#${[r, g, b].map((c) => a255(c).toString(16).padStart(2, '0')).join('')}`;
}

/** Acepta #rgb, #rrggbb, rgb(), hsl() y oklch(). Lo que un diseñador escribe. */
export function parsearColor(entrada: string): RGB {
  const s = entrada.trim().toLowerCase();
  if (s.startsWith('#')) return hexARgb(s);

  const nums = (t: string) => (t.match(/-?[\d.]+%?/g) ?? []).map((n) => n.endsWith('%') ? Number(n.slice(0, -1)) / 100 : Number(n));

  if (s.startsWith('rgb')) {
    const [r, g, b] = nums(s).slice(0, 3);
    const esc = (v: number) => (v > 1 ? v / 255 : v);
    return { r: esc(r), g: esc(g), b: esc(b) };
  }
  if (s.startsWith('hsl')) {
    const [h, ss, ll] = nums(s);
    return hslARgb(h, ss, ll);
  }
  if (s.startsWith('oklch')) {
    const [l, c, h] = nums(s);
    return oklchARgb({ l, c, h });
  }
  throw new Error(`No reconocí el color «${entrada}». Usa #rrggbb, rgb(), hsl() u oklch().`);
}

export function hslARgb(h: number, s: number, l: number): RGB {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const hh = ((h % 360) + 360) % 360 / 60;
  const x = c * (1 - Math.abs((hh % 2) - 1));
  const [r1, g1, b1] =
    hh < 1 ? [c, x, 0] : hh < 2 ? [x, c, 0] : hh < 3 ? [0, c, x] :
    hh < 4 ? [0, x, c] : hh < 5 ? [x, 0, c] : [c, 0, x];
  const m = l - c / 2;
  return { r: r1 + m, g: g1 + m, b: b1 + m };
}

const srgbALineal = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
const linealAsRgb = (v: number) => (v <= 0.0031308 ? v * 12.92 : 1.055 * v ** (1 / 2.4) - 0.055);

export function rgbAOklab({ r, g, b }: RGB): { L: number; a: number; b: number } {
  const R = srgbALineal(r), G = srgbALineal(g), B = srgbALineal(b);
  const l = 0.4122214708 * R + 0.5363325363 * G + 0.0514459929 * B;
  const m = 0.2119034982 * R + 0.6806995451 * G + 0.1073969566 * B;
  const s = 0.0883024619 * R + 0.2817188376 * G + 0.6299787005 * B;
  const l_ = Math.cbrt(l), m_ = Math.cbrt(m), s_ = Math.cbrt(s);
  return {
    L: 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
    a: 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
    b: 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
  };
}

export function oklabARgb({ L, a, b }: { L: number; a: number; b: number }): RGB {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.2914855480 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  return {
    r: linealAsRgb(+4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    g: linealAsRgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    b: linealAsRgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
  };
}

export function rgbAOklch(rgb: RGB): OKLCH {
  const { L, a, b } = rgbAOklab(rgb);
  const c = Math.hypot(a, b);
  let h = (Math.atan2(b, a) * 180) / Math.PI;
  if (h < 0) h += 360;
  return { l: L, c, h };
}

/** Convierte OKLCH a sRGB bajando el croma si el color se sale de la gama. */
export function oklchARgb(color: OKLCH): RGB {
  const dentro = (v: RGB) => v.r >= -0.001 && v.r <= 1.001 && v.g >= -0.001 && v.g <= 1.001 && v.b >= -0.001 && v.b <= 1.001;
  const crudo = (c: number) => oklabARgb({ L: color.l, a: c * Math.cos((color.h * Math.PI) / 180), b: c * Math.sin((color.h * Math.PI) / 180) });
  let rgb = crudo(color.c);
  if (!dentro(rgb)) {
    let bajo = 0, alto = color.c;
    for (let i = 0; i < 24; i += 1) {
      const medio = (bajo + alto) / 2;
      if (dentro(crudo(medio))) bajo = medio; else alto = medio;
    }
    rgb = crudo(bajo);
  }
  return { r: clamp01(rgb.r), g: clamp01(rgb.g), b: clamp01(rgb.b) };
}

export function hexAOklch(hex: string): OKLCH { return rgbAOklch(hexARgb(hex)); }
export function oklchAHex(color: OKLCH): string { return rgbAHex(oklchARgb(color)); }

export function aOklch(color: OKLCH, opciones: { l?: number; c?: number; h?: number } = {}): OKLCH {
  return { l: opciones.l ?? color.l, c: opciones.c ?? color.c, h: opciones.h ?? color.h };
}

export function aCss(color: OKLCH, exacto = false): string {
  const rgb = oklchARgb(color);
  const l = (color.l * 100).toFixed(1);
  const c = color.c.toFixed(4);
  const h = color.h.toFixed(1);
  return exacto ? `oklch(${l}% ${c} ${h})` : rgbAHex(rgb);
}

// ── Contraste ─────────────────────────────────────────────────────────────────

export function luminanciaRelativa(rgb: RGB): number {
  const [r, g, b] = [rgb.r, rgb.g, rgb.b].map(srgbALineal);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Razón de contraste WCAG 2.2: de 1 (invisible) a 21 (negro sobre blanco). */
export function contrasteWcag(a: RGB, b: RGB): number {
  const [l1, l2] = [luminanciaRelativa(a), luminanciaRelativa(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

interface VeredictoWcag {
  razon: number;
  aa_texto_normal: boolean;
  aaa_texto_normal: boolean;
  aa_texto_grande: boolean;
  aa_componentes: boolean;
}

export function veredictoWcag(razon: number): VeredictoWcag {
  return {
    razon: Number(razon.toFixed(2)),
    aa_texto_normal: razon >= 4.5,
    aaa_texto_normal: razon >= 7,
    aa_texto_grande: razon >= 3,
    aa_componentes: razon >= 3,
  };
}

const APCA = {
  sR: 0.2126729, sG: 0.7151522, sB: 0.072175,
  normBG: 0.56, normTXT: 0.57, revTXT: 0.62, revBG: 0.65,
  blkThrs: 0.022, blkClmp: 1.414, scale: 1.14, offset: 0.027,
  deltaYmin: 0.0005, loClip: 0.1,
};

function apcaY({ r, g, b }: RGB): number {
  const y = APCA.sR * r ** 2.4 + APCA.sG * g ** 2.4 + APCA.sB * b ** 2.4;
  return y > APCA.blkThrs ? y : y + (APCA.blkThrs - y) ** APCA.blkClmp;
}

/**
 * APCA (algoritmo perceptivo de contraste, revisión 0.1.9). Devuelve Lc: valores
 * absolutos de 0 a 108. Positivo = texto oscuro sobre fondo claro; negativo =
 * texto claro sobre fondo oscuro. Todavía NO es norma: sirve como segunda
 * opinión al lado de WCAG, que es el que obliga.
 */
export function contrasteApca(texto: RGB, fondo: RGB): number {
  const ytxt = apcaY(texto), ybg = apcaY(fondo);
  if (Math.abs(ybg - ytxt) < APCA.deltaYmin) return 0;
  let c: number;
  if (ybg > ytxt) {
    c = (ybg ** APCA.normBG - ytxt ** APCA.normTXT) * APCA.scale;
    c = c < APCA.loClip ? 0 : c - APCA.offset;
  } else {
    c = (ybg ** APCA.revBG - ytxt ** APCA.revTXT) * APCA.scale;
    c = c > -APCA.loClip ? 0 : c + APCA.offset;
  }
  return Number((c * 100).toFixed(1));
}

/** Umbrales de APCA para saber si el Lc alcanza, según qué se está leyendo. */
export function umbralApca(lc: number, uso: 'cuerpo' | 'grande' | 'destacado' | 'no_texto' = 'cuerpo'): boolean {
  const minimo = uso === 'cuerpo' ? 60 : uso === 'grande' ? 45 : uso === 'destacado' ? 30 : 15;
  return Math.abs(lc) >= minimo;
}

/** Sube o baja la luminosidad hasta que el par cumpla la razón pedida. */
export function ajustarHastaPasar(texto: string, fondo: string, objetivo = 4.5): { color: string; razon: number; ajuste: number } {
  const fondoRgb = parsearColor(fondo);
  const base = hexAOklch(texto);
  const paso = base.l > 0.5 ? -0.01 : 0.01;
  let color = base;
  for (let i = 0; i < 100; i += 1) {
    const razon = contrasteWcag(oklchARgb(color), fondoRgb);
    if (razon >= objetivo) break;
    color = aOklch(color, { l: clamp01(color.l + paso) });
    if (color.l <= 0.02 || color.l >= 0.99) break;
  }
  const razon = contrasteWcag(oklchARgb(color), fondoRgb);
  return { color: rgbAHex(oklchARgb(color)), razon: Number(razon.toFixed(2)), ajuste: Number((color.l - base.l).toFixed(3)) };
}

/** De una lista de fondos, cuál texto (claro u oscuro) conviene. */
export function mejorTexto(fondo: string | RGB, candidatos: string[] = ['#ffffff', '#0b0b10']): { color: string; razon: number; apca: number } {
  const f = typeof fondo === 'string' ? parsearColor(fondo) : fondo;
  const evaluados = candidatos.map((c) => {
    const rgb = parsearColor(c);
    return { color: rgbAHex(rgb), razon: contrasteWcag(rgb, f), apca: contrasteApca(rgb, f) };
  }).sort((a, b) => b.razon - a.razon);
  return { ...evaluados[0], razon: Number(evaluados[0].razon.toFixed(2)) };
}

// ── Generadores ───────────────────────────────────────────────────────────────

export interface Tono { paso: number; nombre: string; hex: string; oklch: OKLCH }

const PASOS_TONO = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950];
// Luminosidad objetivo por paso, ya probada para que la escala se lea pareja.
const L_POR_PASO: Record<number, number> = {
  50: 0.97, 100: 0.94, 200: 0.88, 300: 0.80, 400: 0.71, 500: 0.62,
  600: 0.54, 700: 0.45, 800: 0.36, 900: 0.27, 950: 0.19,
};

/** Escala de tonos de una marca, generada en OKLCH (pareja de claro a oscuro). */
export function escalaTonos(base: string): Tono[] {
  const color = hexAOklch(base);
  // Si la marca es un gris (croma casi nulo), la escala se queda gris: no se le inventa color.
  const cromaPico = color.c < 0.005 ? 0 : color.c;
  return PASOS_TONO.map((paso) => {
    const l = L_POR_PASO[paso];
    // El croma se abre en los medios y se cierra en las puntas, como el ojo espera.
    const factor = 1 - Math.abs(l - 0.62) * 1.35;
    const oklchColor = { l, c: Math.max(0, cromaPico * Math.max(0.18, factor)), h: color.h };
    return { paso, nombre: String(paso), hex: oklchAHex(oklchColor), oklch: oklchColor };
  });
}

export interface Rol { rol: string; hex: string; sobre: string; razon: number; apca: number; css: string }

export interface Paleta {
  base: string;
  modo: 'claro' | 'oscuro';
  fondo: string;
  superficie: string;
  texto: string;
  textoSuave: string;
  escala: Tono[];
  roles: Rol[];
  avisos: string[];
}

/**
 * Paleta completa con roles y textos legibles. El modo cambia el fondo y los
 * textos, no los tonos: la marca se mantiene.
 */
export function generarPaleta(base: string, opciones: { modo?: 'claro' | 'oscuro'; neutro?: string } = {}): Paleta {
  const modo = opciones.modo ?? 'oscuro';
  const escala = escalaTonos(base);
  const tono = (paso: number) => escala.find((t) => t.paso === paso)!.hex;
  const avisos: string[] = [];

  // Todo se deriva de la marca, pero con mesura: si la marca es un gris, el sistema queda gris.
  const colorBase = hexAOklch(base);
  const tinte = Math.min(0.02, colorBase.c * 0.15);
  const cFondo = Math.min(0.012, colorBase.c * 0.08);
  const fondo = modo === 'oscuro' ? (opciones.neutro ?? oklchAHex({ l: 0.115, c: cFondo, h: colorBase.h })) : '#ffffff';
  const superficie = modo === 'oscuro'
    ? oklchAHex({ ...hexAOklch(fondo), l: Math.min(0.16, hexAOklch(fondo).l + 0.06) })
    : oklchAHex({ ...colorBase, l: 0.98, c: colorBase.c * 0.06 });

  const principal = modo === 'oscuro' ? tono(400) : tono(600);
  const texto = modo === 'oscuro' ? oklchAHex({ l: 0.965, c: tinte, h: colorBase.h }) : oklchAHex({ l: 0.22, c: tinte, h: colorBase.h });
  const textoSuave = modo === 'oscuro' ? oklchAHex({ l: 0.72, c: tinte, h: colorBase.h }) : oklchAHex({ l: 0.45, c: tinte, h: colorBase.h });

  const pedidos: Array<{ rol: string; hex: string }> = [
    { rol: 'primario', hex: principal },
    { rol: 'primario-fuerte', hex: modo === 'oscuro' ? tono(300) : tono(700) },
    { rol: 'exito', hex: modo === 'oscuro' ? '#7ff0cd' : '#0f7a5a' },
    { rol: 'aviso', hex: modo === 'oscuro' ? '#ffcd75' : '#8a5a00' },
    { rol: 'peligro', hex: modo === 'oscuro' ? '#ff9ab4' : '#b3123f' },
    { rol: 'info', hex: modo === 'oscuro' ? '#8fd8ef' : '#0b5f7a' },
    { rol: 'neutro', hex: modo === 'oscuro' ? tono(700) : tono(200) },
  ];

  const roles: Rol[] = pedidos.map(({ rol, hex }) => {
    const sobre = mejorTexto(hex);
    if (sobre.razon < 4.5) avisos.push(`El texto sobre «${rol}» (${hex}) queda en ${sobre.razon}:1; para texto pequeño no alcanza AA. Úsalo en piezas grandes o baja la luminosidad del rol.`);
    return { rol, hex, sobre: sobre.color, razon: sobre.razon, apca: sobre.apca, css: `--${rol}` };
  });

  const razon = contrasteWcag(parsearColor(texto), parsearColor(fondo));
  if (razon < 4.5) avisos.push(`El texto principal sobre el fondo queda en ${razon.toFixed(2)}:1.`);

  return { base, modo, fondo, superficie, texto, textoSuave, escala, roles, avisos };
}

export interface Paso {
  nombre: string;
  rem: number;
  px: number;
  clamp?: string;
  interlinea: number;
  medida: string;
  uso: string;
}

/** Escala tipográfica modular; con fluido=true genera clamp() en vez de rem fijo. */
export function escalaTipografica(opciones: { base?: number; razon?: number; pasos?: number; fluido?: boolean; anchoMin?: number; anchoMax?: number } = {}): Paso[] {
  const base = opciones.base ?? 16;
  const razon = opciones.razon ?? 1.25;
  const pasos = opciones.pasos ?? 6;
  const fluido = opciones.fluido ?? true;
  const min = opciones.anchoMin ?? 360;
  const max = opciones.anchoMax ?? 1240;

  const nombres: Record<number, string> = { 0: 'texto', [-1]: 'texto-chico', [-2]: 'pie', 1: 'subtitulo', 2: 'titulo-4', 3: 'titulo-3', 4: 'titulo-2', 5: 'titulo-1', 6: 'portada' };
  const usos: Record<number, string> = {
    0: 'cuerpo de texto', [-1]: 'textos secundarios', [-2]: 'notas y pies', 1: 'entradillas',
    2: 'títulos de tarjeta', 3: 'títulos de sección', 4: 'títulos de página', 5: 'portada', 6: 'portada grande (rara vez)',
  };

  const salida: Paso[] = [];
  for (let i = 0; i <= pasos; i += 1) {
    for (const signo of i === 0 ? [0] : [1, -1]) {
      const nivel = signo >= 0 ? i : -i;
      if (!nombres[nivel] && nivel !== 0) continue;
      const cl = nivel;
      const pxGrande = base * razon ** cl;
      const pxChico = pxGrande * 0.82;
      const interlinea = pxGrande >= 32 ? 1.15 : pxGrande >= 22 ? 1.25 : pxGrande >= 17 ? 1.45 : 1.6;
      const medida = pxGrande >= 28 ? '20-26 caracteres' : pxGrande >= 20 ? '35-45 caracteres' : '60-75 caracteres';
      const paso: Paso = {
        nombre: nombres[cl] ?? `nivel-${cl}`,
        rem: Number((pxGrande / 16).toFixed(4)),
        px: Number(pxGrande.toFixed(1)),
        interlinea,
        medida,
        uso: usos[cl] ?? 'a criterio del diseñador',
      };
      if (fluido && (cl >= 1 || cl <= -1)) {
        // Pendiente real entre el ancho mínimo y el máximo: cuánto crece por cada vw.
        const vw = (100 * (pxGrande - pxChico)) / (max - min);
        const remBase = pxChico / 16 - ((min / 16) * vw) / 100;
        paso.clamp = `clamp(${(pxChico / 16).toFixed(3)}rem, ${remBase.toFixed(3)}rem + ${vw.toFixed(4)}vw, ${paso.rem.toFixed(3)}rem)`;
      }
      salida.push(paso);
    }
  }
  return salida.sort((a, b) => a.px - b.px);
}

export function escalaEspaciado(base = 4, pasos = 12): Array<{ token: string; px: number; rem: number }> {
  return Array.from({ length: pasos }, (_, i) => {
    const px = base * (i + 1);
    return { token: `espacio-${i + 1}`, px, rem: Number((px / 16).toFixed(3)) };
  });
}

// ── Tokens: CSS y DTCG ────────────────────────────────────────────────────────

export interface EntradaTokens {
  paleta: Paleta;
  tipografia?: Paso[];
  espaciado?: ReturnType<typeof escalaEspaciado>;
  radios?: Record<string, string>;
  sombras?: Record<string, string>;
  prefijo?: string;
}

const RADIOS = { sm: '6px', md: '10px', lg: '14px', xl: '22px', total: '999px' };
const SOMBRAS = {
  suave: '0 1px 2px rgba(0,0,0,.18)',
  media: '0 6px 18px rgba(0,0,0,.28)',
  brillo: '0 0 22px color-mix(in oklch, var(--primario) 45%, transparent)',
};

export function tokensCss(entrada: EntradaTokens): string {
  const p = entrada.paleta;
  const pref = entrada.prefijo ? `${entrada.prefijo}-` : '';
  const t = entrada.tipografia ?? escalaTipografica({ fluido: true });
  const e = entrada.espaciado ?? escalaEspaciado();
  const radios = entrada.radios ?? RADIOS;
  const sombras = entrada.sombras ?? SOMBRAS;

  const lineas: string[] = [];
  lineas.push(`/* Tokens generados desde ${p.base} (modo ${p.modo}). Sin dependencias. */`);
  lineas.push('@layer tokens {');
  lineas.push('  :root {');
  lineas.push(`    --${pref}fondo: ${p.fondo};`);
  lineas.push(`    --${pref}superficie: ${p.superficie};`);
  lineas.push(`    --${pref}texto: ${p.texto};`);
  lineas.push(`    --${pref}texto-suave: ${p.textoSuave};`);
  for (const t2 of p.escala) lineas.push(`    --${pref}marca-${t2.paso}: ${t2.hex};`);
  for (const r of p.roles) lineas.push(`    --${pref}${r.rol}: ${r.hex};\n    --${pref}sobre-${r.rol}: ${r.sobre};`);
  for (const paso of t) lineas.push(`    --${pref}${paso.nombre}: ${paso.clamp ?? `${paso.rem}rem`};`);
  for (const paso of t) lineas.push(`    --${pref}${paso.nombre}-interlinea: ${paso.interlinea};`);
  for (const esp of e) lineas.push(`    --${pref}${esp.token}: ${esp.rem}rem;`);
  for (const [k, v] of Object.entries(radios)) lineas.push(`    --${pref}radio-${k}: ${v};`);
  for (const [k, v] of Object.entries(sombras)) lineas.push(`    --${pref}sombra-${k}: ${v};`);
  lineas.push('    --fuente: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;');
  lineas.push('    --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;');
  lineas.push('  }');
  lineas.push('');
  lineas.push('  /* Quien pida menos movimiento, lo recibe. */');
  lineas.push('  @media (prefers-reduced-motion: reduce) {');
  lineas.push(`    :root { --${pref}transicion: 0.01ms; }`);
  lineas.push('  }');
  lineas.push(`  :root { --${pref}transicion: 160ms cubic-bezier(.2,.7,.2,1); }`);
  lineas.push('}');
  return lineas.join('\n');
}

/** Formato del grupo de diseño (DTCG): lo que entienden Figma y las herramientas nuevas. */
export function tokensDTCG(entrada: EntradaTokens): Record<string, unknown> {
  const p = entrada.paleta;
  const t = entrada.tipografia ?? escalaTipografica({ fluido: false });
  const e = entrada.espaciado ?? escalaEspaciado();
  const color: Record<string, unknown> = {
    fondo: { $type: 'color', $value: p.fondo },
    superficie: { $type: 'color', $value: p.superficie },
    texto: { $type: 'color', $value: p.texto },
    'texto-suave': { $type: 'color', $value: p.textoSuave },
  };
  for (const tono of p.escala) color[`marca-${tono.paso}`] = { $type: 'color', $value: tono.hex };
  for (const r of p.roles) color[r.rol] = { $type: 'color', $value: r.hex };

  const tipografia: Record<string, unknown> = {};
  for (const paso of t) {
    tipografia[paso.nombre] = {
      $type: 'typography',
      $value: { fontSize: `${paso.rem}rem`, lineHeight: paso.interlinea, fontFamily: '{fuente.texto}' },
      $description: paso.uso,
    };
  }
  const espacio: Record<string, unknown> = {};
  for (const esp of e) espacio[esp.token] = { $type: 'dimension', $value: `${esp.rem}rem` };

  return {
    $schema: 'https://design-tokens.org/schema.json',
    color,
    tipografia,
    espacio,
    radio: Object.fromEntries(Object.entries(RADIOS).map(([k, v]) => [k, { $type: 'dimension', $value: v }])),
    fuente: {
      texto: { $type: 'fontFamily', $value: ['ui-sans-serif', 'system-ui', 'Segoe UI', 'Roboto', 'sans-serif'] },
      mono: { $type: 'fontFamily', $value: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'] },
    },
  };
}

// ── Componentes en CSS moderno ────────────────────────────────────────────────

export type TipoComponente = 'boton' | 'tarjeta' | 'campo' | 'insignia' | 'aviso' | 'interruptor';

export function componenteCss(tipo: TipoComponente, opciones: { prefijo?: string; conContenedor?: boolean } = {}): string {
  const pref = opciones.prefijo ? `${opciones.prefijo}-` : '';
  const v = (n: string) => `var(--${pref}${n})`;
  const base = `/* CSS moderno: capas, anidado, focus-visible y color-mix. Sin dependencias. */
@layer componentes {
  .${tipo} {`;

  const cuerpos: Record<TipoComponente, string> = {
    boton: `    font: inherit;
    display: inline-flex;
    align-items: center;
    gap: ${v('espacio-2')};
    padding: ${v('espacio-3')} ${v('espacio-5')};
    min-height: 44px; /* WCAG 2.2: objetivo táctil cómodo */
    border: 1px solid color-mix(in oklch, ${v('primario')} 62%, ${v('texto')} 12%);
    border-radius: ${v('radio-md')};
    background: ${v('primario')};
    color: ${v('sobre-primario')};
    cursor: pointer;
    transition: transform ${v('transicion')}, background ${v('transicion')};

    &:hover { background: color-mix(in oklch, ${v('primario')} 88%, white 12%); }
    &:active { transform: translateY(1px); }
    &:focus-visible { outline: 3px solid ${v('primario-fuerte')}; outline-offset: 2px; }
    &[disabled], &[aria-disabled="true"] { opacity: .55; cursor: not-allowed; }

    @media (prefers-reduced-motion: reduce) { transition: none; transform: none; }
  }
  .${tipo}--secundario { background: transparent; color: ${v('primario')}; }
  .${tipo}--peligro { background: ${v('peligro')}; color: ${v('sobre-peligro')}; }`,
    tarjeta: `    background: ${v('superficie')};
    border: 1px solid color-mix(in oklch, ${v('texto')} 14%, transparent);
    border-radius: ${v('radio-lg')};
    padding: ${v('espacio-5')};
    container-type: inline-size;

    & > h3 { font-size: ${v('subtitulo')}; line-height: ${v('subtitulo-interlinea')}; margin: 0 0 ${v('espacio-2')}; }
    & > p { color: ${v('texto-suave')}; margin: 0; }

    /* La tarjeta se reacomoda según su propio ancho, no el de la ventana. */
    @container (min-width: 420px) {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: ${v('espacio-4')};
    }
  }`,
    campo: `    display: block;
    font-size: ${v('texto-chico')};

    & > input {
      display: block;
      inline-size: 100%;
      margin-top: ${v('espacio-1')};
      padding: ${v('espacio-3')};
      font: inherit;
      color: ${v('texto')};
      background: color-mix(in oklch, ${v('superficie')} 70%, black 30%);
      border: 1px solid color-mix(in oklch, ${v('texto')} 22%, transparent);
      border-radius: ${v('radio-sm')};

      &:focus-visible { outline: 3px solid ${v('primario')}; outline-offset: 1px; border-color: transparent; }
      &[aria-invalid="true"] { border-color: ${v('peligro')}; }
    }
    & > .ayuda { color: ${v('texto-suave')}; font-size: ${v('pie')}; }
    & > .error { color: ${v('peligro')}; font-size: ${v('pie')}; }
  }`,
    insignia: `    display: inline-flex;
    align-items: center;
    gap: ${v('espacio-1')};
    padding: ${v('espacio-1')} ${v('espacio-3')};
    border: 1px solid ${v('primario')};
    border-radius: ${v('radio-total')};
    color: ${v('primario')};
    font-size: ${v('pie')};
    line-height: 1.4;
  }
  .${tipo}--exito { color: ${v('exito')}; border-color: color-mix(in oklch, ${v('exito')} 60%, transparent); }
  .${tipo}--aviso { color: ${v('aviso')}; border-color: color-mix(in oklch, ${v('aviso')} 60%, transparent); }
  .${tipo}--peligro { color: ${v('peligro')}; border-color: color-mix(in oklch, ${v('peligro')} 60%, transparent); }`,
    aviso: `    display: grid;
    grid-template-columns: auto 1fr;
    gap: ${v('espacio-3')};
    padding: ${v('espacio-4')};
    border: 1px solid color-mix(in oklch, ${v('aviso')} 45%, transparent);
    border-inline-start-width: 4px;
    border-radius: ${v('radio-md')};
    background: color-mix(in oklch, ${v('aviso')} 10%, ${v('superficie')});
    color: ${v('texto')};

    &[data-tono="peligro"] { border-color: color-mix(in oklch, ${v('peligro')} 45%, transparent); background: color-mix(in oklch, ${v('peligro')} 10%, ${v('superficie')}); }
    &[data-tono="exito"] { border-color: color-mix(in oklch, ${v('exito')} 45%, transparent); background: color-mix(in oklch, ${v('exito')} 10%, ${v('superficie')}); }
  }`,
    interruptor: `    display: inline-flex;
    align-items: center;
    gap: ${v('espacio-3')};
    cursor: pointer;

    & > input { appearance: none; inline-size: 52px; block-size: 30px; border-radius: ${v('radio-total')};
      background: color-mix(in oklch, ${v('texto')} 22%, transparent); position: relative;
      transition: background ${v('transicion')}; }
    & > input::after { content: ""; position: absolute; inset: 3px auto 3px 3px; inline-size: 24px;
      border-radius: 50%; background: ${v('texto')}; transition: translate ${v('transicion')}; }
    & > input:checked { background: ${v('primario')}; }
    & > input:checked::after { translate: 22px 0; background: ${v('sobre-primario')}; }
    & > input:focus-visible { outline: 3px solid ${v('primario-fuerte')}; outline-offset: 2px; }
    @media (prefers-reduced-motion: reduce) { & > input, & > input::after { transition: none; } }
  }`,
  };

  const extra = opciones.conContenedor && tipo === 'tarjeta'
    ? `\n\n  /* La página decide el ancho desde fuera; el componente se adapta desde dentro. */\n  .rejilla { display: grid; gap: ${v('espacio-4')}; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }`
    : '';

  return `${base}\n${cuerpos[tipo]}${extra}\n}\n`;
}

// ── Auditoría de accesibilidad (HTML) ─────────────────────────────────────────

export interface ResultadoAuditoria {
  puntaje: number;
  resumen: string;
  hallazgos: Hallazgo[];
  conteos: { alto: number; medio: number; bajo: number; info: number };
}

function contarSeveridad(hallazgos: Hallazgo[]): ResultadoAuditoria['conteos'] {
  return {
    alto: hallazgos.filter((h) => h.severidad === 'alto').length,
    medio: hallazgos.filter((h) => h.severidad === 'medio').length,
    bajo: hallazgos.filter((h) => h.severidad === 'bajo').length,
    info: hallazgos.filter((h) => h.severidad === 'info').length,
  };
}

function puntaje(hallazgos: Hallazgo[]): number {
  const peso = { alto: 14, medio: 7, bajo: 3, info: 1 } as const;
  const total = hallazgos.reduce((a, h) => a + peso[h.severidad], 0);
  return Math.max(0, Math.round(100 - total));
}

/**
 * Revisión de accesibilidad de un HTML: lo que se puede comprobar sin navegador.
 * No sustituye a probar con lector de pantalla, pero encuentra lo que casi nadie
 * revisa y sí se puede medir.
 */
export function auditarHtml(html: string): ResultadoAuditoria {
  const lineas = html.split('\n');
  const hallazgos: Hallazgo[] = [];
  const lineaDe = (frag: string): number | undefined => {
    const i = lineas.findIndex((l) => l.includes(frag));
    return i >= 0 ? i + 1 : undefined;
  };
  const push = (h: Hallazgo) => hallazgos.push(h);

  if (!/<html[^>]*\slang=/i.test(html)) {
    push({ severidad: 'alto', codigo: 'idioma-faltante', mensaje: 'La etiqueta <html> no declara el idioma.', linea: lineaDe('<html'), sugerencia: 'Añade lang="es" (o el que corresponda): los lectores de pantalla cambian de voz según esto.' });
  }
  if (!/<title>[^<]+<\/title>/i.test(html)) {
    push({ severidad: 'alto', codigo: 'titulo-faltante', mensaje: 'No hay <title>: el enlace se anuncia vacío.', sugerencia: 'Pon un título que diga de qué va la página.' });
  }
  if (!/name=["']viewport["']/i.test(html)) {
    push({ severidad: 'medio', codigo: 'viewport-faltante', mensaje: 'Falta la etiqueta de viewport.', sugerencia: 'Añade <meta name="viewport" content="width=device-width, initial-scale=1">.' });
  }

  const imgs = [...html.matchAll(/<img\b[^>]*>/gi)].map((m) => m[0]);
  for (const img of imgs) {
    if (!/\salt=/i.test(img)) {
      push({ severidad: 'alto', codigo: 'img-sin-alt', mensaje: `Imagen sin atributo alt: ${img.slice(0, 90)}`, linea: lineaDe(img.slice(0, 60)), sugerencia: 'Si es decorativa, alt=""; si aporta información, descríbela en una frase.' });
    } else if (/alt=["']\s*(imagen|foto|imagen de|logo)\s*["']/i.test(img)) {
      push({ severidad: 'medio', codigo: 'alt-inutil', mensaje: `El alt no aporta información: ${img.slice(0, 90)}`, linea: lineaDe(img.slice(0, 60)), sugerencia: 'Describe qué muestra, no que es una imagen.' });
    }
  }

  const inputs = [...html.matchAll(/<input\b[^>]*>/gi)].map((m) => m[0]);
  for (const input of inputs) {
    const tipo = /type=["']([^"']+)["']/i.exec(input)?.[1]?.toLowerCase();
    if (['hidden', 'submit', 'button', 'reset', 'image'].includes(tipo ?? '')) continue;
    const id = /id=["']([^"']+)["']/i.exec(input)?.[1];
    const etiquetado = (id && new RegExp(`<label[^>]*for=["']${id}["']`, 'i').test(html)) || /aria-label=/i.test(input) || /aria-labelledby=/i.test(input);
    if (!etiquetado) {
      push({ severidad: 'alto', codigo: 'campo-sin-etiqueta', mensaje: `Campo sin etiqueta asociada: ${input.slice(0, 90)}`, linea: lineaDe(input.slice(0, 60)), sugerencia: 'Asocia un <label for="id"> o ponle aria-label. El placeholder no sirve como etiqueta.' });
    }
    if (/placeholder=/i.test(input) && !etiquetado) {
      push({ severidad: 'medio', codigo: 'placeholder-como-etiqueta', mensaje: 'El único texto del campo está en el placeholder y desaparece al escribir.', linea: lineaDe(input.slice(0, 60)) });
    }
  }

  const botones = [...html.matchAll(/<button\b[^>]*>([\s\S]*?)<\/button>/gi)];
  for (const [, contenido] of botones) {
    const limpio = contenido.replace(/<[^>]+>/g, '').trim();
    if (!limpio) {
      push({ severidad: 'alto', codigo: 'boton-sin-nombre', mensaje: 'Botón sin texto: se anuncia como «botón» y nada más.', linea: lineaDe(contenido.slice(0, 30)), sugerencia: 'Pon texto visible o aria-label que diga qué hace.' });
    }
  }

  const enlaces = [...html.matchAll(/<a\b[^>]*>([\s\S]*?)<\/a>/gi)];
  for (const [completo, contenido] of enlaces) {
    const limpio = contenido.replace(/<[^>]+>/g, '').trim().toLowerCase();
    if (!limpio && !/aria-label=/i.test(completo)) {
      push({ severidad: 'medio', codigo: 'enlace-sin-texto', mensaje: 'Enlace sin texto visible ni aria-label.', linea: lineaDe(completo.slice(0, 50)) });
    }
    if (/^(clic aquí|click aquí|aquí|leer más|más|ver más)$/.test(limpio)) {
      push({ severidad: 'bajo', codigo: 'enlace-generico', mensaje: `«${limpio}» no dice a dónde va: quien navega por enlaces oye «más, más, más».`, linea: lineaDe(completo.slice(0, 50)), sugerencia: 'Usa el destino: «ver facturas de marzo».' });
    }
    if (/target=["']_blank["']/i.test(completo) && !/rel=["'][^"']*noopener/i.test(completo)) {
      push({ severidad: 'bajo', codigo: 'blank-sin-noopener', mensaje: 'Enlace con target="_blank" sin rel="noopener".', linea: lineaDe(completo.slice(0, 50)) });
    }
  }

  const niveles = [...html.matchAll(/<h([1-6])\b/gi)].map((m) => Number(m[1]));
  const h1 = niveles.filter((n) => n === 1).length;
  if (h1 === 0) push({ severidad: 'medio', codigo: 'sin-h1', mensaje: 'No hay encabezado de nivel 1.', linea: null, sugerencia: 'El título principal de la página debería ser un <h1>.' });
  if (h1 > 1) push({ severidad: 'bajo', codigo: 'varios-h1', mensaje: `Hay ${h1} encabezados de nivel 1.`, sugerencia: 'Uno solo por página; lo demás, <h2> en adelante.' });
  for (let i = 1; i < niveles.length; i += 1) {
    if (niveles[i] - niveles[i - 1] > 1) {
      push({ severidad: 'medio', codigo: 'salto-encabezado', mensaje: `Salto de <h${niveles[i - 1]}> a <h${niveles[i]}>: se pierde la estructura.`, sugerencia: 'Sube de nivel de uno en uno.' });
      break;
    }
  }

  if (/outline\s*:\s*(none|0)/i.test(html) && !/:focus-visible/i.test(html)) {
    push({ severidad: 'alto', codigo: 'foco-cancelado', mensaje: 'Se quita el contorno del foco sin reemplazarlo: quien navega con teclado se pierde.', linea: lineaDe('outline'), sugerencia: 'Usa :focus-visible con un anillo visible en vez de outline:none a secas.' });
  }

  // Pares color/fondo declarados en estilos en línea: se puede medir ahora mismo.
  for (const [i, linea] of lineas.entries()) {
    const m = /style=["']([^"']*)["']/i.exec(linea);
    if (!m) continue;
    const estilo = m[1];
    const color = /(?:^|;)\s*color\s*:\s*([^;]+)/i.exec(estilo)?.[1]?.trim();
    const fondo = /background(?:-color)?\s*:\s*([^;]+)/i.exec(estilo)?.[1]?.trim();
    if (!color || !fondo) continue;
    try {
      const razon = contrasteWcag(parsearColor(color), parsearColor(fondo));
      if (razon < 4.5) {
        push({ severidad: 'alto', codigo: 'contraste-insuficiente', mensaje: `Texto sobre fondo con contraste ${razon.toFixed(2)}:1 (mínimo 4.5:1 para texto normal).`, linea: i + 1, sugerencia: `Prueba ${ajustarHastaPasar(color, fondo).color} para el texto, o aclara/oscurece el fondo.` });
      }
    } catch { /* color en formato que no se puede leer sin navegador */ }
  }

  if (/tabindex=["']0["']/i.test(html) && !/role=/i.test(html)) {
    push({ severidad: 'info', codigo: 'tabindex-revisar', mensaje: 'Hay elementos con tabindex="0": revisa que no sean divs haciendo de botón.', sugerencia: 'Si se comporta como botón o enlace, usa <button> o <a>: ya traen teclado y anuncio.' });
  }
  if (/<marquee|autoplay/i.test(html)) {
    push({ severidad: 'medio', codigo: 'movimiento-automatico', mensaje: 'Contenido que se mueve solo y no se puede pausar.', sugerencia: 'Ofrece pausa, o quítalo si es decorativo.' });
  }
  if (/user-scalable\s*=\s*no|maximum-scale\s*=\s*1/i.test(html)) {
    push({ severidad: 'alto', codigo: 'zoom-bloqueado', mensaje: 'Se bloquea el zoom: quien necesita ampliar no puede.', sugerencia: 'Quita user-scalable=no y maximum-scale=1.' });
  }

  hallazgos.sort((a, b) => ({ alto: 0, medio: 1, bajo: 2, info: 3 }[a.severidad] - { alto: 0, medio: 1, bajo: 2, info: 3 }[b.severidad]));
  return {
    puntaje: puntaje(hallazgos),
    resumen: hallazgos.length === 0 ? 'Sin hallazgos de los que se pueden comprobar sin navegador.' : `${hallazgos.length} hallazgo(s): ${hallazgos.filter((h) => h.severidad === 'alto').length} de atención alta.`,
    hallazgos,
    conteos: contarSeveridad(hallazgos),
  };
}

// ── Revisión de estilo (CSS) ──────────────────────────────────────────────────

export interface ResultadoEstilo extends ResultadoAuditoria {
  metricas: {
    lineas: number;
    reglas: number;
    coloresSueltos: number;
    tokensUsados: number;
    important: number;
    tokensDeclarados: string[];
      coloresRepetidos: Array<{ color: string; veces: number }>;
      paresMedidos: number;
      paresOmitidos: number;
    };
  moderno: {
    oklch: boolean;
    colorMix: boolean;
    capas: boolean;
    anidado: boolean;
    contenedores: boolean;
    clamp: boolean;
    esquemaColor: boolean;
    menosMovimiento: boolean;
    lightDark: boolean;
  };
}

/** Revisa un CSS: colores sueltos, contraste en pares declarados y qué le falta para ser moderno. */
export function revisarEstilo(css: string): ResultadoEstilo {
  const lineas = css.split('\n');
  // Los comentarios no son código: si dicen un color o un tamaño, no cuentan.
  const cssLimpio = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  const hallazgos: Hallazgo[] = [];
  const push = (h: Hallazgo) => hallazgos.push(h);
  const lineaDe = (frag: string) => {
    const i = lineas.findIndex((l) => l.includes(frag));
    return i >= 0 ? i + 1 : undefined;
  };

  const colores = [...cssLimpio.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0].toLowerCase());
  // Un color cuenta como token si cae dentro de una declaración --nombre: … aunque esté repartida en varias líneas.
  const declaraciones = cssLimpio.split(';');
  const dentroDeUnToken = (c: string) =>
    declaraciones.some((d) => /--[a-zA-Z0-9-]+\s*:/.test(d) && d.toLowerCase().includes(c));
  const fueraDeTokens = colores.filter((c) => !dentroDeUnToken(c));
  const cuenta = new Map<string, number>();
  for (const c of fueraDeTokens) cuenta.set(c, (cuenta.get(c) ?? 0) + 1);
  const repetidos = [...cuenta.entries()].filter(([, n]) => n > 1).map(([color, veces]) => ({ color, veces })).sort((a, b) => b.veces - a.veces);

  if (fueraDeTokens.length > 0) {
    push({
      severidad: fueraDeTokens.length > 8 ? 'medio' : 'bajo',
      codigo: 'colores-sueltos',
      mensaje: `${fueraDeTokens.length} color(es) escrito(s) a mano fuera de los tokens.`,
      linea: lineaDe(fueraDeTokens[0]),
      sugerencia: `Muévelos a variables. Los más repetidos: ${repetidos.slice(0, 3).map((r) => `${r.color} (${r.veces}×)`).join(', ') || 'ninguno repetido'}.`,
    });
  }

  const tokensDeclarados = [...new Set([...cssLimpio.matchAll(/--[a-zA-Z0-9-]+(?=\s*:)/g)].map((m) => m[0]))];
  const tokensUsados = [...new Set([...cssLimpio.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)].map((m) => m[1]))];
  const sinUsar = tokensDeclarados.filter((t) => !tokensUsados.includes(t));
  const sinDeclarar = tokensUsados.filter((t) => !tokensDeclarados.includes(t));
  if (sinDeclarar.length && tokensDeclarados.length === 0) {
    // Hoja que sólo consume tokens: es lo normal en los componentes, no es un error.
    push({ severidad: 'info', codigo: 'tokens-de-otros-archivos', mensaje: `Usa ${sinDeclarar.length} variable(s) que se declaran en otro archivo (normal en una hoja de componentes).`, linea: null, sugerencia: 'Comprueba que el archivo de tokens se cargue antes que éste.' });
  } else if (sinDeclarar.length) {
    push({ severidad: 'medio', codigo: 'token-inexistente', mensaje: `Se usan ${sinDeclarar.length} variable(s) que nadie declara en este archivo: ${sinDeclarar.slice(0, 4).join(', ')}.`, linea: lineaDe(`var(${sinDeclarar[0]}`) ?? null, sugerencia: 'Si vienen de otro archivo, ignóralo; si no, están rotas y el navegador usa el valor de respaldo o nada.' });
  }
  if (sinUsar.length > 6) {
    push({ severidad: 'info', codigo: 'tokens-sin-uso', mensaje: `${sinUsar.length} variable(s) declaradas que no se usan en este archivo.`, sugerencia: 'Limpia o documéntalas: los sistemas crecen por acumulación.' });
  }

  const important = (cssLimpio.match(/!important/g) ?? []).length;
  if (important > 3) {
    push({ severidad: 'medio', codigo: 'important-abuso', mensaje: `${important} usos de !important.`, linea: lineaDe('!important'), sugerencia: 'Casi siempre es señal de capas mal ordenadas: usa @layer en vez de forzar.' });
  }

  if (/outline\s*:\s*(none|0)/i.test(cssLimpio) && !/:focus-visible/i.test(cssLimpio)) {
    push({ severidad: 'alto', codigo: 'foco-cancelado', mensaje: 'Se quita el foco sin dar alternativa visible.', linea: lineaDe('outline'), sugerencia: 'Añade :focus-visible con un anillo.' });
  }

  const anima = /animation|transition/i.test(cssLimpio);
  const menosMovimiento = /prefers-reduced-motion/i.test(cssLimpio);
  if (anima && !menosMovimiento) {
    push({ severidad: 'medio', codigo: 'movimiento-sin-respeto', mensaje: 'Hay animaciones y no se respeta a quien pidió menos movimiento.', linea: lineaDe('transition') ?? lineaDe('animation'), sugerencia: 'Envuelve lo que se mueve en @media (prefers-reduced-motion: reduce).' });
  }

  const px = [...cssLimpio.matchAll(/font-size\s*:\s*(\d+)px/g)].map((m) => Number(m[1]));
  if (px.length >= 3) {
    push({ severidad: 'bajo', codigo: 'tipografia-en-px', mensaje: `${px.length} tamaños de letra en píxeles fijos.`, linea: lineaDe(`${px[0]}px`), sugerencia: 'Usa rem: respeta el tamaño que la persona ya eligió en su navegador.' });
  }

  const esquemaColor = /prefers-color-scheme/i.test(cssLimpio) || /light-dark\(/i.test(cssLimpio);
  if (!esquemaColor) {
    push({ severidad: 'bajo', codigo: 'sin-modo-claro-oscuro', mensaje: 'No hay modo claro/oscuro.', sugerencia: 'Con tokens, se resuelve con light-dark() o un media query: es casi gratis.' });
  }

  // Pares declarados en la misma regla: se puede medir el contraste sin navegador.
  const reglas = [...cssLimpio.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  // Sin navegador no se puede saber el color final de algo con transparencia, degradado o herencia.
  const noMedible = (v: string) => /var\(|color-mix\(|gradient|currentColor|inherit|transparent|\/\s*[\d.]+%?\)|,\s*[\d.]+\s*\)/.test(v);
  let paresMedidos = 0, paresOmitidos = 0;
  for (const [, , cuerpo] of reglas) {
    const color = /(?:^|;)\s*color\s*:\s*([^;]+)/i.exec(cuerpo)?.[1]?.trim();
    const fondo = /background(?:-color)?\s*:\s*([^;]+)/i.exec(cuerpo)?.[1]?.trim();
    if (!color || !fondo) continue;
    if (noMedible(color) || noMedible(fondo)) { paresOmitidos += 1; continue; }
    try {
      const razon = contrasteWcag(parsearColor(color), parsearColor(fondo));
      paresMedidos += 1;
      if (razon < 4.5) {
        push({ severidad: 'alto', codigo: 'contraste-insuficiente', mensaje: `Un par de colores del archivo queda en ${razon.toFixed(2)}:1 (mínimo 4.5:1).`, linea: lineaDe(fondo.slice(0, 20)) ?? null, sugerencia: `Para el texto, ${ajustarHastaPasar(color, fondo).color}.` });
      }
    } catch { paresOmitidos += 1; }
  }
  if (paresOmitidos > 0) {
    push({ severidad: 'info', codigo: 'pares-con-transparencia', mensaje: `${paresOmitidos} par(es) de color no se pudieron medir aquí (transparencia, degradado o color heredado).`, linea: null, sugerencia: 'Mídelos con color_convertir y contraste_verificar cuando sepas el color final.' });
  }

  hallazgos.sort((a, b) => ({ alto: 0, medio: 1, bajo: 2, info: 3 }[a.severidad] - { alto: 0, medio: 1, bajo: 2, info: 3 }[b.severidad]));
  return {
    puntaje: puntaje(hallazgos),
    resumen: hallazgos.length === 0 ? 'Sin hallazgos: el archivo está en orden.' : `${hallazgos.length} hallazgo(s): ${hallazgos.filter((h) => h.severidad === 'alto').length} de atención alta.`,
    hallazgos,
    conteos: contarSeveridad(hallazgos),
    metricas: {
      lineas: lineas.length,
      reglas: reglas.length,
      coloresSueltos: fueraDeTokens.length,
      tokensUsados: tokensUsados.length,
      important,
      tokensDeclarados,
      coloresRepetidos: repetidos.slice(0, 10),
      paresMedidos,
      paresOmitidos,
    },
    moderno: {
      oklch: /oklch\(|oklab\(/i.test(css),
      colorMix: /color-mix\(/i.test(css),
      capas: /@layer/i.test(css),
      anidado: /&\s*[:.[a-zA-Z]/.test(css),
      contenedores: /@container/i.test(css),
      clamp: /clamp\(/i.test(css),
      esquemaColor,
      menosMovimiento,
      lightDark: /light-dark\(/i.test(css),
    },
  };
}
