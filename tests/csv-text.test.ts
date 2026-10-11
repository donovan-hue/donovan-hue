import { describe, expect, it } from 'vitest';
import { csvToRecords, detectDelimiter, parseCsv, profileTable, toCsv, toMarkdownTable } from '../src/lib/csv';
import { extraerDatos, renderPlantilla, resumirTexto, variablesDePlantilla } from '../src/lib/text';

describe('CSV', () => {
  const csv = 'nombre,precio,cantidad\nTeclado,1290,4\nMouse,650,10\n"Monitor 27 pulgadas",5890,2\n';

  it('detecta delimitador', () => {
    expect(detectDelimiter(csv)).toBe(',');
    expect(detectDelimiter('a;b;c\n1;2;3')).toBe(';');
  });

  it('parsea comillas, comas internas y saltos', () => {
    const filas = parseCsv('a,b\n"uno,dos",3\n"con ""comillas""",4\n');
    expect(filas[1]).toEqual(['uno,dos', '3']);
    expect(filas[2]![0]).toBe('con "comillas"');
  });

  it('convierte a registros y genera csv/markdown', () => {
    const tabla = csvToRecords(csv);
    expect(tabla.headers).toEqual(['nombre', 'precio', 'cantidad']);
    expect(tabla.rows).toHaveLength(3);
    expect(tabla.rows[2]!.nombre).toBe('Monitor 27 pulgadas');
    const md = toMarkdownTable([tabla.headers, ...tabla.rows.map((r) => tabla.headers.map((h) => r[h]!))]);
    expect(md).toContain('| nombre | precio | cantidad |');
    expect(toCsv([['a', 'b,c'], [1, 2]])).toContain('"b,c"');
  });

  it('perfila columnas numéricas, de texto y vacías', () => {
    const perfiles = profileTable(csvToRecords('x,y,z\n1,hola,\n2,hola,\n3,otro,\n'));
    expect(perfiles[0]).toMatchObject({ tipo: 'numerica', min: 1, max: 3, media: 2 });
    expect(perfiles[1]).toMatchObject({ tipo: 'texto', unicos: 2 });
    expect(perfiles[1]!.top!.find((t) => t.valor === 'hola')!.conteo).toBe(2);
    expect(perfiles[2]).toMatchObject({ tipo: 'vacia', nulos: 3 });
  });
});

describe('texto', () => {
  it('resume métricas de un texto en español', () => {
    const r = resumirTexto('La facturación del mes creció mucho. El equipo de ventas superó la meta trimestral. Los clientes nuevos compraron más productos.');
    expect(r.palabras).toBeGreaterThan(15);
    expect(r.oraciones).toBe(3);
    expect(r.tiempoLecturaMin).toBeGreaterThan(0);
    expect(r.palabrasTop.length).toBeGreaterThan(0);
    expect(r.nivelLectureEscolar).toBeTruthy();
  });

  it('extrae datos de contacto y fiscales mexicanos', () => {
    const texto =
      'Contacto: Ana López <ana.lopez@empresa.mx>, tel +52 33 3812 4400. RFC: DJA240118H72, CURP: LOPA850312MJCPRN05. Factura por $12,450.00 MXN con fecha 2026-03-15. Sitio https://empresa.mx/facturas. C.P. 45500.';
    const d = extraerDatos(texto);
    expect(d.emails).toContain('ana.lopez@empresa.mx');
    expect(d.rfc).toContain('DJA240118H72');
    expect(d.curp).toContain('LOPA850312MJCPRN05');
    expect(d.fechas).toContain('2026-03-15');
    expect(d.codigosPostales).toContain('45500');
    expect(d.urls[0]).toContain('https://empresa.mx');
  });

  it('renderiza plantillas y reporta variables faltantes', () => {
    const t = 'Hola {{cliente}}, tu total es ${{total}} y vence el {{fecha}}.';
    expect(variablesDePlantilla(t)).toEqual(['cliente', 'total', 'fecha']);
    const r = renderPlantilla(t, { cliente: 'ACME', total: 1160 });
    expect(r.texto).toContain('Hola ACME');
    expect(r.texto).toContain('$1160');
    expect(r.variablesFaltantes).toEqual(['fecha']);
    expect(r.texto).toContain('{{fecha}}');
  });
});
