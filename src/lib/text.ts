/** Procesamiento de texto: resúmenes, extracción de datos y plantillas. */

const STOPWORDS = new Set([
  'de','la','que','el','en','y','a','los','del','se','las','por','un','para','con','no','una','su','al','lo','como','más','pero','sus','le','ya','o','este','sí','porque','esta','entre','cuando','muy','sin','sobre','también','me','hasta','hay','donde','quien','desde','todo','nos','durante','todos','uno','les','ni','contra','otros','ese','eso','ante','ellos','e','esto','mí','antes','algunos','qué','unos','yo','otro','otras','otra','él','tanto','esa','estos','mucho','quienes','nada','muchos','cual','poco','ella','estar','estas','algunas','algo','nosotros','mi','mis','tú','te','ti','tu','tus','ellas','nosotras','vosotros','vosotras','os','mío','mía','míos','mías','tuyo','tuya','tuyos','tuyas','suyo','suya','suyos','suyas','nuestro','nuestra','nuestros','nuestras','vuestro','vuestra','vuestros','vuestras','esos','esas','estoy','estás','está','estamos','estáis','están','ser','es','son','fue','fueron','era','eran','han','ha','haber','tiene','tienen','hacer','hace','puede','pueden','mas','si','ya','así','cada','mismo','siempre','nunca','aquí','allí','acá','allá',
]);

export interface ResumenTexto {
  caracteres: number;
  caracteresSinEspacios: number;
  palabras: number;
  oraciones: number;
  parrafos: number;
  tiempoLecturaMin: number;
  palabrasTop: { palabra: string; frecuencia: number }[];
  oracionMasLarga: string;
  nivelLectureEscolar?: string;
}

export function resumirTexto(texto: string, topN = 10): ResumenTexto {
  if (!texto || texto.trim() === '') throw new Error('El texto está vacío.');
  const limpio = texto.replace(/\s+/g, ' ').trim();
  const palabrasArr = limpio.match(/[\p{L}\p{N}]+/gu) ?? [];
  const oraciones = (texto.match(/[^.!?…]+[.!?…]+/g) ?? (texto.trim() ? [texto.trim()] : []))
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  const parrafos = texto.split(/\n\s*\n/).filter((p) => p.trim().length > 0);

  const frecuencia = new Map<string, number>();
  for (const p of palabrasArr) {
    const norm = p.toLowerCase();
    if (norm.length < 4 || STOPWORDS.has(norm) || /^\d+$/.test(norm)) continue;
    frecuencia.set(norm, (frecuencia.get(norm) ?? 0) + 1);
  }
  const palabrasTop = [...frecuencia.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, topN)
    .map(([palabra, f]) => ({ palabra, frecuencia: f }));

  const oracionMasLarga = oraciones.reduce((a, b) => (b.length > a.length ? b : a), '');

  const promPalabrasPorOracion = oraciones.length > 0 ? palabrasArr.length / oraciones.length : 0;
  const silabas = (palabra: string) => (palabra.toLowerCase().match(/[aeiouáéíóúü]+/g) ?? []).length || 1;
  const promSilabas = palabrasArr.length > 0 ? palabrasArr.reduce((a, p) => a + silabas(p), 0) / palabrasArr.length : 0;
  // Fórmula de Fernández-Huerta (adaptación del índice de legibilidad para español).
  const legibilidad = 206.84 - 0.6 * promSilabas - 1.02 * promPalabrasPorOracion;
  const nivel =
    legibilidad >= 80 ? 'muy fácil (primaria)' :
    legibilidad >= 65 ? 'fácil (secundaria)' :
    legibilidad >= 50 ? 'normal (bachillerato)' :
    legibilidad >= 35 ? 'algo difícil (universitario)' : 'difícil (especializado)';

  return {
    caracteres: texto.length,
    caracteresSinEspacios: texto.replace(/\s/g, '').length,
    palabras: palabrasArr.length,
    oraciones: oraciones.length,
    parrafos: parrafos.length,
    tiempoLecturaMin: Number((palabrasArr.length / 200).toFixed(1)),
    palabrasTop,
    oracionMasLarga: oracionMasLarga.length > 300 ? `${oracionMasLarga.slice(0, 300)}…` : oracionMasLarga,
    nivelLectureEscolar: nivel,
  };
}

export interface DatosExtraidos {
  emails: string[];
  telefonos: string[];
  urls: string[];
  rfc: string[];
  curp: string[];
  montos: string[];
  fechas: string[];
  codigosPostales: string[];
  numerosLargo: string[];
}

export function extraerDatos(texto: string): DatosExtraidos {
  const unicos = (m: string[]) => [...new Set(m)];
  const matchAll = (re: RegExp) => [...texto.matchAll(re)].map((m) => m[0]);

  const telefonos = matchAll(/\+?\d[\d\s().-]{8,}\d/g).filter((t) => {
    const digitos = t.replace(/\D/g, '');
    return digitos.length >= 10 && digitos.length <= 13;
  });

  return {
    emails: unicos(matchAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g)),
    telefonos: unicos(telefonos.map((t) => t.trim())),
    urls: unicos(matchAll(/https?:\/\/[^\s<>"')]+/g)),
    // RFC persona física (13) o moral (12)
    rfc: unicos(matchAll(/\b([A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3})\b/g).map((r) => r.toUpperCase())),
    curp: unicos(matchAll(/\b[A-Z]{4}\d{6}[HM][A-Z]{5}[A-Z0-9]\d\b/g).map((r) => r.toUpperCase())),
    montos: unicos(matchAll(/(?:US)?\$\s?\d[\d,]*(?:\.\d{1,2})?|\b\d[\d,]*(?:\.\d{1,2})?\s?(?:MXN|USD|pesos?|dólares?)\b/gi)),
    fechas: unicos(matchAll(/\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b/g)),
    codigosPostales: unicos(matchAll(/\bC\.?P\.?\s?(\d{5})\b/gi).map((m) => (m.match(/\d{5}/) ?? [''])[0]!)),
    numerosLargo: unicos(matchAll(/\b\d{8,}\b/g)),
  };
}

export interface ResultadoPlantilla {
  texto: string;
  variablesUsadas: string[];
  variablesFaltantes: string[];
}

/** Sustituye marcadores {{variable}} en una plantilla. */
export function renderPlantilla(plantilla: string, variables: Record<string, string | number>): ResultadoPlantilla {
  const usadas = new Set<string>();
  const faltantes = new Set<string>();

  const texto = plantilla.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (_full, clave: string) => {
    usadas.add(clave);
    if (Object.prototype.hasOwnProperty.call(variables, clave)) {
      return String(variables[clave]);
    }
    faltantes.add(clave);
    return `{{${clave}}}`;
  });

  return {
    texto,
    variablesUsadas: [...usadas],
    variablesFaltantes: [...faltantes],
  };
}

/** Extrae las variables declaradas en una plantilla (sin renderizar). */
export function variablesDePlantilla(plantilla: string): string[] {
  return [...new Set([...plantilla.matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((m) => m[1]!))];
}
