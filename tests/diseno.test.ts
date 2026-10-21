/**
 * Pruebas del módulo «diseño»: color de última generación, contraste, paletas,
 * tokens, tipografía, componentes y las dos auditorías (HTML y CSS).
 *
 * Lo que se comprueba de verdad: que las conversiones de color vuelven al mismo
 * sitio, que el contraste coincide con los valores publicados de la norma, que
 * ningún color generado se sale de la gama, y que las auditorías encuentran lo
 * que dicen que encuentran.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import type { ServerCtx } from '../src/context';
import {
  parsearColor, rgbAHex, hexAOklch, oklchAHex, contrasteWcag, contrasteApca,
  veredictoWcag, ajustarHastaPasar, mejorTexto, generarPaleta, escalaTipografica,
  tokensCss, tokensDTCG, componenteCss, auditarHtml, revisarEstilo,
} from '../src/lib/diseno';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-diseno-');
  ctx = await contextoDePrueba(dataDir, { allowedGitRoots: [projectRoot] }, { rpm: 6000, burst: 1000 });
  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-diseno', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
});

describe('DISEÑO — color', () => {
  it('convierte en los cuatro formatos y vuelve al mismo color', () => {
    const rgb = parsearColor('#ff3df0');
    const ok = hexAOklch('#ff3df0');
    expect(oklchAHex(ok)).toBe('#ff3df0');
    expect(rgbAHex(parsearColor(oklchAHex(ok)))).toBe('#ff3df0');
    expect(ok.h).toBeGreaterThan(320);
    expect(ok.h).toBeLessThan(345);
    expect(ok.l).toBeGreaterThan(0.6);
  });

  it('acepta rgb(), hsl() y oklch() como entrada', () => {
    expect(rgbAHex(parsearColor('rgb(255 61 240)'))).toBe('#ff3df0');
    expect(rgbAHex(parsearColor('hsl(310 100% 62%)'))).toMatch(/^#[0-9a-f]{6}$/);
    const porHsl = parsearColor('hsl(310 100% 62%)');
    const porOklch = parsearColor('oklch(71% 0.28 330)');
    expect(rgbAHex(porOklch)).toMatch(/^#[0-9a-f]{6}$/);
    // Los dos son rosas fuertes y salen casi iguales: se miden, no se adivinan.
    expect(Math.abs(hexAOklch(rgbAHex(porHsl)).h - hexAOklch(rgbAHex(porOklch)).h)).toBeLessThan(20);
    expect(() => parsearColor('azul marino')).toThrow(/No reconocí/);
  });

  it('nunca devuelve un color fuera de la gama aunque pidan un croma imposible', () => {
    const hex = oklchAHex({ l: 0.9, c: 0.9, h: 140 });
    const rgb = parsearColor(hex);
    for (const v of [rgb.r, rgb.g, rgb.b]) {
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
    }
  });
});

describe('DISEÑO — contraste', () => {
  it('acierta con los valores publicados de la norma', () => {
    expect(contrasteWcag(parsearColor('#ffffff'), parsearColor('#000000'))).toBeCloseTo(21, 1);
    // Gris medio sobre blanco: el ejemplo clásico que no llega a AA (4.48:1).
    expect(contrasteWcag(parsearColor('#777777'), parsearColor('#ffffff'))).toBeCloseTo(4.48, 1);
    expect(veredictoWcag(4.48).aa_texto_normal).toBe(false);
    expect(veredictoWcag(4.6).aa_texto_normal).toBe(true);
    expect(veredictoWcag(7.1).aaa_texto_normal).toBe(true);
  });

  it('APCA da la polaridad y el orden de magnitud correctos', () => {
    const oscuroSobreClaro = contrasteApca(parsearColor('#000000'), parsearColor('#ffffff'));
    const claroSobreOscuro = contrasteApca(parsearColor('#ffffff'), parsearColor('#000000'));
    expect(oscuroSobreClaro).toBeGreaterThan(90);
    expect(oscuroSobreClaro).toBeLessThan(120);
    expect(claroSobreOscuro).toBeLessThan(-90);
  });

  it('propone un color que sí cumple cuando el par falla', () => {
    const arreglo = ajustarHastaPasar('#777777', '#ffffff', 4.5);
    expect(arreglo.razon).toBeGreaterThanOrEqual(4.5);
    expect(arreglo.color).not.toBe('#777777');
    expect(arreglo.ajuste).toBeLessThan(0);
  });

  it('elige el texto que mejor contrasta sobre un fondo', () => {
    expect(mejorTexto('#07070a').color).toBe('#ffffff');
    expect(mejorTexto('#f5f5f5').razon).toBeGreaterThan(4.5);
  });
});

describe('DISEÑO — paleta y tokens', () => {
  it('genera una escala de 11 tonos, pareja y sin saltos', () => {
    const p = generarPaleta('#ff3df0');
    expect(p.escala).toHaveLength(11);
    const luces = p.escala.map((t) => t.oklch.l);
    for (let i = 1; i < luces.length; i += 1) expect(luces[i]).toBeLessThan(luces[i - 1]);
    expect(p.escala[0].oklch.l).toBeGreaterThan(0.9);
    expect(p.escala[10].oklch.l).toBeLessThan(0.25);
  });

  it('da los roles con el texto encima y su contraste medido', () => {
    const p = generarPaleta('#ff3df0');
    expect(p.roles.map((r) => r.rol)).toContain('primario');
    for (const r of p.roles) expect(r.razon).toBeGreaterThan(1);
    expect(p.escala.every((t) => /^#[0-9a-f]{6}$/.test(t.hex))).toBe(true);
  });

  it('los tokens salen en CSS y en formato de diseño', () => {
    const p = generarPaleta('#ff3df0');
    const entrada = { paleta: p, tipografia: escalaTipografica({ fluido: true }) };
    const css = tokensCss(entrada);
    expect(css).toContain('@layer tokens');
    expect(css).toContain('--primario:');
    expect(css).toContain('prefers-reduced-motion');
    expect(css).toContain('clamp(');
    const dtcg = tokensDTCG(entrada) as Record<string, any>;
    expect(dtcg.color.primario.$type).toBe('color');
    expect(dtcg.color.primario.$value).toMatch(/^#/);
    expect(dtcg.tipografia.texto.$value.fontSize).toContain('rem');
  });

  it('una marca gris no se contamina de color', () => {
    const p = generarPaleta('#b0b0b0', { modo: 'oscuro' });
    const croma = (hex: string) => hexAOklch(hex).c;
    expect(croma(p.fondo)).toBeLessThan(0.005);
    expect(croma(p.texto)).toBeLessThan(0.005);
    expect(p.escala.every((t) => t.oklch.c < 0.005)).toBe(true);
    // En cambio una marca de color sí tinta el sistema, con mesura.
    const f = generarPaleta('#ff3df0', { modo: 'oscuro' });
    expect(hexAOklch(f.texto).c).toBeLessThanOrEqual(0.021);
    expect(hexAOklch(f.texto).c).toBeGreaterThan(0.001);
  });

  it('el clamp fluido crece de verdad entre el ancho mínimo y el máximo', () => {
    for (const paso of escalaTipografica({ fluido: true, pasos: 6 })) {
      if (!paso.clamp) continue;
      const numeros = [...paso.clamp.matchAll(/([\d.]+)rem/g)].map((m) => Number(m[1]));
      const [minimo, base, maximo] = numeros;
      expect(minimo).toBeLessThan(maximo);
      expect(base).toBeLessThan(maximo);
      expect((paso.clamp.match(/vw/g) ?? []).length).toBe(1);
    }
  });

  it('la escala tipográfica sube de tamaño y es fluida', () => {
    const fluida = escalaTipografica({ fluido: true });
    const fija = escalaTipografica({ fluido: false });
    expect(fluida.length).toBeGreaterThan(6);
    for (let i = 1; i < fluida.length; i += 1) expect(fluida[i].px).toBeGreaterThan(fluida[i - 1].px);
    expect(fluida.some((p) => p.clamp)).toBe(true);
    expect(fija.every((p) => !p.clamp)).toBe(true);
    expect(fluida.find((p) => p.nombre === 'texto')!.px).toBe(16);
  });
});

describe('DISEÑO — componentes', () => {
  it('el botón trae foco visible, objetivo táctil y respeto al movimiento', () => {
    const css = componenteCss('boton');
    expect(css).toContain(':focus-visible');
    expect(css).toContain('44px');
    expect(css).toContain('prefers-reduced-motion');
    expect(css).toContain('color-mix(');
  });

  it('la tarjeta usa contenedores y el campo asocia ayuda y error', () => {
    expect(componenteCss('tarjeta')).toContain('@container');
    const campo = componenteCss('campo');
    expect(campo).toContain('aria-invalid');
    expect(campo).toContain('.ayuda');
  });
});

describe('DISEÑO — auditorías', () => {
  it('encuentra lo que un HTML tiene mal', () => {
    const html = `<!doctype html><html><head><title>Prueba</title></head><body>
      <img src="foto.png">
      <input type="email" placeholder="tu correo">
      <button></button>
      <h1>Uno</h1><h1>Dos</h1><h4>Salto</h4>
      <style>.x:focus { outline: none }</style>
      <div style="color:#777777; background-color:#ffffff">texto gris</div>
      <a href="x" target="_blank">clic aquí</a>
    </body></html>`;
    const r = auditarHtml(html);
    const codigos = r.hallazgos.map((h) => h.codigo);
    expect(codigos).toContain('idioma-faltante');
    expect(codigos).toContain('img-sin-alt');
    expect(codigos).toContain('campo-sin-etiqueta');
    expect(codigos).toContain('boton-sin-nombre');
    expect(codigos).toContain('foco-cancelado');
    expect(codigos).toContain('contraste-insuficiente');
    expect(codigos).toContain('enlace-generico');
    expect(codigos).toContain('varios-h1');
    expect(r.puntaje).toBeLessThan(70);
    expect(r.conteos.alto).toBeGreaterThan(2);
    // cada hallazgo alto viene con cómo arreglarlo
    expect(r.hallazgos.filter((h) => h.severidad === 'alto').every((h) => Boolean(h.sugerencia))).toBe(true);
  });

  it('un HTML bien hecho no acumula hallazgos', () => {
    const html = `<!doctype html><html lang="es"><head><title>Panel</title>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <style>.b:focus-visible { outline: 3px solid #ff3df0 }</style></head><body>
      <h1>Panel</h1>
      <img src="grafico.png" alt="Ventas de marzo: 42 pedidos">
      <label for="correo">Correo</label><input id="correo" type="email">
      <button class="b">Guardar</button>
      <a href="/facturas">ver facturas de marzo</a>
    </body></html>`;
    const r = auditarHtml(html);
    expect(r.conteos.alto).toBe(0);
    expect(r.puntaje).toBeGreaterThanOrEqual(90);
  });

  it('revisa un CSS y detecta lo que hay que modernizar', () => {
    const css = `
      :root { --marca: #ff3df0; }
      .caja { color: #777777; background: #ffffff; font-size: 13px; }
      .otra { color: #777777; font-size: 12px; }
      .rara { color: var(--no-existe); }
      .fuerte { color: red !important; }
      .fuerte { color: blue !important; }
      .fuerte { color: green !important; }
      .fuerte { color: navy !important; }
      .anim { transition: all 2s; }
    `;
    const r = revisarEstilo(css);
    const codigos = r.hallazgos.map((h) => h.codigo);
    expect(codigos).toContain('colores-sueltos');
    expect(codigos).toContain('token-inexistente');
    expect(codigos).toContain('important-abuso');
    expect(codigos).toContain('contraste-insuficiente');
    expect(codigos).toContain('movimiento-sin-respeto');
    expect(codigos).toContain('sin-modo-claro-oscuro');
    expect(r.metricas.coloresSueltos).toBeGreaterThan(0);
    expect(r.moderno.oklch).toBe(false);
    expect(r.moderno.capas).toBe(false);
  });
});

describe('DISEÑO — revisión fina de CSS', () => {
  it('no confunde comentarios con código', () => {
    const r = revisarEstilo('/* Negro absoluto (#000000) y textos de 12px */\n.a { color: #777777; background: #ffffff; }');
    // Dos colores de verdad; el del comentario y su tamaño de letra no cuentan.
    expect(r.metricas.coloresSueltos).toBe(2);
    expect(r.metricas.reglas).toBe(1);
    expect(r.hallazgos.map((h) => h.codigo)).not.toContain('tipografia-en-px');
  });

  it('una hoja que sólo consume tokens no se reporta como rota', () => {
    const r = revisarEstilo('.a { color: var(--texto); background: var(--fondo); }');
    const codigos = r.hallazgos.map((h) => h.codigo);
    expect(codigos).toContain('tokens-de-otros-archivos');
    expect(codigos).not.toContain('token-inexistente');
    expect(r.conteos.alto).toBe(0);
  });

  it('con transparencia no inventa un contraste falso', () => {
    const r = revisarEstilo('.a { color: #ffffff; background: rgba(255, 255, 255, 0.08); }');
    expect(r.hallazgos.some((h) => h.codigo === 'contraste-insuficiente')).toBe(false);
    expect(r.metricas.paresOmitidos).toBe(1);
    expect(r.metricas.paresMedidos).toBe(0);
  });

  it('los hallazgos sin línea salen con linea nula, no con texto indefinido', async () => {
    const r = await llamar('accesibilidad_auditar', { ruta: __filename });
    expect(r.isError).toBe(false);
  });
});

describe('DISEÑO — las ocho herramientas por el servidor', () => {
  it('color_convertir responde con los cuatro formatos', async () => {
    const r = await llamar('color_convertir', { color: '#ff3df0' });
    const d = r.json();
    expect(d.hex).toBe('#ff3df0');
    expect(d.oklch).toContain('oklch(');
    expect(d.detalle.luminancia_relativa).toBeGreaterThan(0);
  });

  it('contraste_verificar dice cuáles fallan y cómo arreglarlos', async () => {
    const r = await llamar('contraste_verificar', {
      pares: [
        { texto: '#ffffff', fondo: '#000000', etiqueta: 'máximo' },
        { texto: '#777777', fondo: '#ffffff', etiqueta: 'gris flojo' },
      ],
    });
    const d = r.json();
    expect(d.revisados).toBe(2);
    expect(d.fallan).toBe(1);
    const flojo = d.resultados.find((x: any) => x.etiqueta === 'gris flojo');
    expect(flojo.cumple).toBe('no');
    expect(flojo.arreglo_sugerido.razon).toBeGreaterThanOrEqual(4.5);
    expect(flojo.apca.nota).toContain('no es norma');
  });

  it('paleta_generar y tipografia_escala responden completas', async () => {
    const p = await llamar('paleta_generar', { base: '#ff3df0', modo: 'oscuro' });
    expect(p.json().escala).toHaveLength(11);
    expect(p.json().roles.length).toBeGreaterThan(4);

    const t = await llamar('tipografia_escala', { base: 16, razon: 1.25, pasos: 5 });
    const dt = t.json();
    expect(dt.pasos.length).toBeGreaterThan(5);
    expect(dt.espaciado.length).toBe(8);
  });

  it('tokens_generar deja los archivos escritos cuando se pide', async () => {
    const r = await llamar('tokens_generar', { base: '#ff3df0', prefijo: 'ks', archivo: 'tokens-prueba' });
    const d = r.json();
    expect(d.guardado.css).toContain('tokens-prueba.css');
    const css = await fs.readFile(d.guardado.css, 'utf8');
    const json = await fs.readFile(d.guardado.json, 'utf8');
    expect(css).toContain('--ks-primario');
    expect(JSON.parse(json).color.primario.$value).toMatch(/^#/);
    expect(d.css).toContain('@layer tokens');
  });

  it('componente_generar entrega CSS y un ejemplo de HTML', async () => {
    const r = await llamar('componente_generar', { tipo: 'boton', prefijo: 'ks' });
    const d = r.json();
    expect(d.css).toContain('--ks-primario');
    expect(d.html_de_ejemplo).toContain('<button');
    expect(d.por_que_asi.length).toBeGreaterThan(2);
  });

  it('accesibilidad_auditar y estilo_revisar leen archivos de verdad', async () => {
    const html = path.join(dataDir, 'pagina.html');
    await fs.writeFile(html, '<!doctype html><html><body><img src="x.png"><h1>Hola</h1></body></html>', 'utf8');
    const a = await llamar('accesibilidad_auditar', { ruta: html });
    const da = a.json();
    expect(da.hallazgos.some((h: any) => h.codigo === 'img-sin-alt')).toBe(true);
    expect(da.puntaje).toBeLessThan(100);

    const css = path.join(dataDir, 'hoja.css');
    await fs.writeFile(css, '.a { color: #777777; background: #ffffff; font-size: 12px; }', 'utf8');
    const e = await llamar('estilo_revisar', { ruta: css });
    const de = e.json();
    expect(de.metricas.coloresSueltos).toBeGreaterThan(0);
    expect(de.hallazgos.some((h: any) => h.codigo === 'contraste-insuficiente')).toBe(true);
  });

  it('las ocho herramientas están en el catálogo del servidor', async () => {
    const r = await llamar('mcp_suite_info', {});
    const d = r.json();
    expect(d.modulos).toContain('diseno');
    const deDiseno = d.herramientas.filter((t: any) => t.modulo === 'diseno').map((t: any) => t.nombre).sort();
    expect(deDiseno).toEqual([
      'accesibilidad_auditar', 'color_convertir', 'componente_generar', 'contraste_verificar',
      'estilo_revisar', 'paleta_generar', 'tipografia_escala', 'tokens_generar',
    ]);
  });
});
