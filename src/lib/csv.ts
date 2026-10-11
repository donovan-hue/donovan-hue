/** Utilidades CSV/JSON sin dependencias externas. */

export function detectDelimiter(text: string): string {
  const line = text.split(/\r?\n/).find((l) => l.trim().length > 0) ?? '';
  const candidatos = [',', ';', '\t', '|'];
  let mejor = ',';
  let max = 0;
  for (const c of candidatos) {
    const n = line.split(c).length - 1;
    if (n > max) {
      max = n;
      mejor = c;
    }
  }
  return mejor;
}

export function parseCsv(text: string, delimiter?: string): string[][] {
  const d = delimiter ?? detectDelimiter(text);
  const rows: string[][] = [];
  let row: string[] = [];
  let cur = '';
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          cur += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cur += c;
      }
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === d) {
      row.push(cur);
      cur = '';
    } else if (c === '\n') {
      row.push(cur);
      rows.push(row);
      row = [];
      cur = '';
    } else if (c !== '\r') {
      cur += c;
    }
  }
  if (cur !== '' || row.length > 0) {
    row.push(cur);
    rows.push(row);
  }
  return rows.filter((r) => !(r.length === 1 && r[0]!.trim() === ''));
}

export function toCsv(rows: (string | number | null | undefined)[][]): string {
  const escapar = (v: string | number | null | undefined): string => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return rows.map((r) => r.map(escapar).join(',')).join('\n');
}

export function toMarkdownTable(rows: (string | number | null | undefined)[][]): string {
  if (rows.length === 0) return '';
  const header = rows[0]!;
  const sep = header.map(() => '---');
  const body = rows.slice(1);
  const linea = (r: (string | number | null | undefined)[]) =>
    `| ${r.map((c) => String(c ?? '').replace(/\|/g, '\\|')).join(' | ')} |`;
  return [linea(header), linea(sep), ...body.map(linea)].join('\n');
}

export interface Table {
  headers: string[];
  rows: Record<string, string>[];
}

/** Convierte texto CSV (con encabezados) a una tabla de registros. */
export function csvToRecords(text: string, delimiter?: string): Table {
  const matriz = parseCsv(text, delimiter);
  if (matriz.length === 0) return { headers: [], rows: [] };
  const headers = matriz[0]!.map((h) => h.trim());
  const rows = matriz.slice(1).map((fila) => {
    const rec: Record<string, string> = {};
    headers.forEach((h, i) => {
      rec[h] = (fila[i] ?? '').trim();
    });
    return rec;
  });
  return { headers, rows };
}

/** Convierte un arreglo de objetos JSON a tabla. */
export function jsonToTable(data: unknown): Table {
  const arr = Array.isArray(data) ? data : [data];
  const objetos = arr.filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null);
  if (objetos.length === 0) return { headers: [], rows: [] };
  const headers = [...new Set(objetos.flatMap((o) => Object.keys(o)))];
  const rows = objetos.map((o) => {
    const rec: Record<string, string> = {};
    for (const h of headers) {
      const v = o[h];
      rec[h] = v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    }
    return rec;
  });
  return { headers, rows };
}

export function guessNumber(s: string): number | null {
  if (s === null || s === undefined) return null;
  const limpio = String(s).replace(/[$,\s]/g, '').replace(/%$/, '');
  if (limpio === '' || !/^-?\d*\.?\d+$/.test(limpio)) return null;
  const n = Number(limpio);
  return Number.isFinite(n) ? n : null;
}

export interface ColumnProfile {
  columna: string;
  tipo: 'numerica' | 'fecha' | 'texto' | 'vacia';
  nulos: number;
  unicos: number;
  ejemplo: string | null;
  min?: number;
  max?: number;
  media?: number;
  top?: { valor: string; conteo: number }[];
}

const RE_FECHA = /^\d{4}-\d{2}-\d{2}([T ].*)?$|^\d{1,2}[/-]\d{1,2}[/-]\d{2,4}$/;

export function profileTable(table: Table): ColumnProfile[] {
  return table.headers.map((col) => {
    const valores = table.rows.map((r) => r[col] ?? '');
    const noVacios = valores.filter((v) => v !== '');
    const numeros = noVacios.map(guessNumber).filter((n): n is number => n !== null);
    const fechas = noVacios.filter((v) => RE_FECHA.test(v));
    const unicos = new Set(noVacios);

    const perfil: ColumnProfile = {
      columna: col,
      tipo: 'texto',
      nulos: valores.length - noVacios.length,
      unicos: unicos.size,
      ejemplo: noVacios[0] ?? null,
    };

    if (noVacios.length === 0) {
      perfil.tipo = 'vacia';
      return perfil;
    }
    if (numeros.length === noVacios.length) {
      perfil.tipo = 'numerica';
      perfil.min = Math.min(...numeros);
      perfil.max = Math.max(...numeros);
      perfil.media = Number((numeros.reduce((a, b) => a + b, 0) / numeros.length).toFixed(4));
      return perfil;
    }
    if (fechas.length === noVacios.length) {
      perfil.tipo = 'fecha';
      const ordenadas = [...noVacios].sort();
      perfil.min = undefined;
      perfil.max = undefined;
      perfil.ejemplo = ordenadas[0]!;
      return perfil;
    }
    const conteo = new Map<string, number>();
    for (const v of noVacios) conteo.set(v, (conteo.get(v) ?? 0) + 1);
    perfil.top = [...conteo.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([valor, c]) => ({ valor, conteo: c }));
    return perfil;
  });
}
