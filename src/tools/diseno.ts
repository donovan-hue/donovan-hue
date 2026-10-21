/**
 * Módulo «diseño» — ocho herramientas para vestir un producto sin depender de
 * nadie: color de última generación (OKLCH), contraste obligatorio (WCAG 2.2) y
 * segunda opinión perceptiva (APCA), paletas, tokens en formato de diseño,
 * tipografía fluida, componentes en CSS moderno y revisión de lo que ya existe.
 *
 *   · color_convertir        → hex, rgb, hsl y oklch, con el tono exacto
 *   · contraste_verificar    → razón WCAG + Lc de APCA, y cómo arreglarlo
 *   · paleta_generar         → escala completa y roles legibles de una marca
 *   · tipografia_escala      → escala modular y fluida (clamp), con interlineado
 *   · tokens_generar         → tokens en CSS y en formato de diseño (DTCG)
 *   · componente_generar     → botón, tarjeta, campo, insignia, aviso, interruptor
 *   · accesibilidad_auditar  → revisión de un HTML: lo que se puede comprobar
 *   · estilo_revisar         → revisión de un CSS: qué arreglar y qué modernizar
 *
 * Todo se calcula aquí. Nada sale a internet.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { readTextFile, resolveAllowedPath } from '../lib/store.js';
import { log } from '../logger.js';
import {
  parsearColor, rgbAHex, hexAOklch, oklchAHex, aCss, contrasteWcag, contrasteApca,
  veredictoWcag, umbralApca, ajustarHastaPasar, mejorTexto, generarPaleta,
  escalaTipografica, escalaEspaciado, tokensCss, tokensDTCG, componenteCss,
  auditarHtml, revisarEstilo, type TipoComponente,
} from '../lib/diseno.js';

export function disenoToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);

  push({
    module: 'diseno',
    name: 'color_convertir',
    title: 'Convertir un color entre formatos',
    description:
      'Convierte un color entre hex, rgb, hsl y oklch (el espacio de color moderno que ya usan los navegadores). Devuelve también la luminosidad y el croma en oklch, la luminancia relativa que usa la norma de contraste, y el valor listo para pegar en CSS. Úsala para llevar un color de la marca a un sistema de tokens sin adivinar.',
    readOnly: true,
    inputSchema: {
      color: z.string().min(3).describe('El color a convertir: #ff3df0, rgb(255,61,240), hsl(310 100% 62%) u oklch(71% 0.28 330).'),
      a: z.enum(['hex', 'rgb', 'hsl', 'oklch']).optional().describe('Formato de salida que quieres (por defecto: todos).'),
    },
    handler: async (args: { color: string; a?: 'hex' | 'rgb' | 'hsl' | 'oklch' }) => {
      const rgb = parsearColor(args.color);
      const oklch = hexAOklch(rgbAHex(rgb));
      const [r, g, b] = [rgb.r, rgb.g, rgb.b].map((v) => Math.round(v * 255));
      const max = Math.max(rgb.r, rgb.g, rgb.b), min = Math.min(rgb.r, rgb.g, rgb.b);
      const l = (max + min) / 2;
      const d = max - min;
      const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
      let h = 0;
      if (d !== 0) {
        if (max === rgb.r) h = 60 * (((rgb.g - rgb.b) / d) % 6);
        else if (max === rgb.g) h = 60 * ((rgb.b - rgb.r) / d + 2);
        else h = 60 * ((rgb.r - rgb.g) / d + 4);
      }
      if (h < 0) h += 360;

      const formatos = {
        hex: rgbAHex(rgb),
        rgb: `rgb(${r} ${g} ${b})`,
        hsl: `hsl(${h.toFixed(1)} ${(s * 100).toFixed(1)}% ${(l * 100).toFixed(1)}%)`,
        oklch: aCss(oklch, true),
      };
      const salida = args.a ? { [args.a]: formatos[args.a] } : formatos;
      return {
        entrada: args.color,
        ...salida,
        detalle: {
          oklch: { l: Number(oklch.l.toFixed(4)), c: Number(oklch.c.toFixed(4)), h: Number(oklch.h.toFixed(2)), dentro_de_gama: oklchAHex(oklch) === rgbAHex(rgb) },
          luminancia_relativa: Number((0.2126 * ((rgb.r <= 0.04045 ? rgb.r / 12.92 : ((rgb.r + 0.055) / 1.055) ** 2.4)) +
            0.7152 * ((rgb.g <= 0.04045 ? rgb.g / 12.92 : ((rgb.g + 0.055) / 1.055) ** 2.4)) +
            0.0722 * ((rgb.b <= 0.04045 ? rgb.b / 12.92 : ((rgb.b + 0.055) / 1.055) ** 2.4))).toFixed(4)),
          mas_claro: oklchAHex({ ...oklch, l: Math.min(0.99, oklch.l + 0.1) }),
          mas_oscuro: oklchAHex({ ...oklch, l: Math.max(0.01, oklch.l - 0.1) }),
          mitad_de_croma: oklchAHex({ ...oklch, c: oklch.c / 2 }),
        },
        nota: 'En oklch la L es la que percibe el ojo: moviéndola sube o baja el tono sin ensuciarlo. Con el mismo hex en hsl eso no pasa.',
      };
    },
  });

  push({
    module: 'diseno',
    name: 'contraste_verificar',
    title: 'Verificar contraste de texto y fondo',
    description:
      'Mide el contraste entre un texto y su fondo con la norma vigente (WCAG 2.2) y, como segunda opinión, con APCA (algoritmo perceptivo; todavía no es norma y se dice en la respuesta). Indica si cumple para texto normal, texto grande y componentes, y si no cumple propone el color más cercano que sí cumple. Sirve para pares sueltos o para una lista de pares de una vez.',
    readOnly: true,
    inputSchema: {
      pares: z.array(z.object({
        texto: z.string().describe('Color del texto, ej. #a9a4bb'),
        fondo: z.string().describe('Color de fondo, ej. #07070a'),
        etiqueta: z.string().optional().describe('Para qué es este par (ej. "subtítulo sobre tarjeta").'),
      })).min(1).describe('Pares a revisar.'),
      objetivo: z.number().min(1).max(21).optional().describe('Razón mínima que exiges (por defecto 4.5, que es AA para texto normal).'),
    },
    handler: async (args: { pares: Array<{ texto: string; fondo: string; etiqueta?: string }>; objetivo?: number }) => {
      const objetivo = args.objetivo ?? 4.5;
      const resultados = args.pares.map((p) => {
        const texto = parsearColor(p.texto);
        const fondo = parsearColor(p.fondo);
        const razon = contrasteWcag(texto, fondo);
        const lc = contrasteApca(texto, fondo);
        const cumple = razon >= objetivo;
        const arreglo = cumple ? undefined : ajustarHastaPasar(p.texto, p.fondo, objetivo);
        return {
          etiqueta: p.etiqueta,
          par: { texto: p.texto, fondo: p.fondo },
          razon: Number(razon.toFixed(2)),
          cumple: cumple ? 'sí' : 'no',
          wcag: veredictoWcag(razon),
          apca: {
            lc,
            polaridad: lc >= 0 ? 'texto oscuro sobre fondo claro' : 'texto claro sobre fondo oscuro',
            alcanza_cuerpo: umbralApca(lc, 'cuerpo'),
            alcanza_grande: umbralApca(lc, 'grande'),
            nota: 'APCA no es norma todavía: úsalo como segunda opinión, WCAG es el que obliga.',
          },
          arreglo_sugerido: arreglo ? {
            texto: arreglo.color,
            razon: arreglo.razon,
            cambio_de_luminosidad: arreglo.ajuste,
            como: `${arreglo.ajuste > 0 ? 'aclara' : 'oscurece'} el texto en oklch y queda en ${arreglo.razon}:1`,
          } : undefined,
          alternativa: { mejor_texto_para_ese_fondo: mejorTexto(p.fondo) },
        };
      });
      const fallan = resultados.filter((r) => r.cumple === 'no').length;
      return {
        objetivo,
        revisados: resultados.length,
        fallan,
        resultados,
        resumen: fallan === 0
          ? `Los ${resultados.length} pares cumplen ${objetivo}:1.`
          : `${fallan} de ${resultados.length} pares no llegan a ${objetivo}:1. Cada uno trae el color más cercano que sí cumple.`,
      };
    },
  });

  push({
    module: 'diseno',
    name: 'paleta_generar',
    title: 'Generar una paleta completa desde un color',
    description:
      'De un color de marca saca la escala completa de tonos (50 a 950, generada en oklch para que no haya saltos), los roles del sistema (primario, éxito, aviso, peligro, info, neutro), el texto y la superficie del modo claro u oscuro, y qué color de texto va sobre cada rol con su contraste medido. Avisa cuando algún rol no permite texto pequeño encima. Es la base de los tokens.',
    readOnly: true,
    inputSchema: {
      base: z.string().min(3).describe('Color de la marca, ej. #ff3df0'),
      modo: z.enum(['claro', 'oscuro']).optional().describe('Modo por defecto (oscuro).'),
      neutro: z.string().optional().describe('Color de fondo neutro si quieres uno propio (por defecto se deriva).'),
    },
    handler: async (args: { base: string; modo?: 'claro' | 'oscuro'; neutro?: string }) => {
      const paleta = generarPaleta(args.base, { modo: args.modo, neutro: args.neutro });
      return {
        base: paleta.base,
        modo: paleta.modo,
        fondo: paleta.fondo,
        superficie: paleta.superficie,
        texto: paleta.texto,
        texto_suave: paleta.textoSuave,
        escala: paleta.escala.map((t) => ({ paso: t.paso, hex: t.hex, oklch: `oklch(${(t.oklch.l * 100).toFixed(1)}% ${t.oklch.c.toFixed(4)} ${t.oklch.h.toFixed(1)})` })),
        roles: paleta.roles.map((r) => ({ rol: r.rol, color: r.hex, texto_sobre: r.sobre, contraste: r.razon, apca: r.apca })),
        avisos: paleta.avisos,
        siguiente: 'Con esto puedes generar los tokens: tokens_generar con el mismo color base.',
      };
    },
  });

  push({
    module: 'diseno',
    name: 'tipografia_escala',
    title: 'Calcular escala tipográfica fluida',
    description:
      'Calcula la escala de tamaños de letra con una razón modular y la hace fluida: cada paso sale como clamp() para que crezca con la pantalla en vez de saltar en puntos de quiebre. Incluye interlineado recomendado por tamaño y la medida de línea (cuántos caracteres) para cada paso. Úsala para decidir tamaños con criterio y no a ojo.',
    readOnly: true,
    inputSchema: {
      base: z.number().min(10).max(28).optional().describe('Tamaño base en píxeles (16 por defecto).'),
      razon: z.number().min(1.05).max(1.8).optional().describe('Razón entre un paso y el siguiente: 1.2 es apretada, 1.25 (por defecto) equilibrada, 1.33 marcada.'),
      pasos: z.number().int().min(2).max(8).optional().describe('Cuántos pasos hacia arriba (6 por defecto).'),
      fluido: z.boolean().optional().describe('true (por defecto) para clamp() adaptable; false para rem fijo.'),
      ancho_min: z.number().min(280).max(900).optional().describe('Ancho de pantalla mínimo de referencia (360).'),
      ancho_max: z.number().min(600).max(2400).optional().describe('Ancho de pantalla máximo de referencia (1240).'),
    },
    handler: async (args: { base?: number; razon?: number; pasos?: number; fluido?: boolean; ancho_min?: number; ancho_max?: number }) => {
      const pasos = escalaTipografica({
        base: args.base, razon: args.razon, pasos: args.pasos,
        fluido: args.fluido, anchoMin: args.ancho_min, anchoMax: args.ancho_max,
      });
      const espaciado = escalaEspaciado();
      return {
        base_px: args.base ?? 16,
        razon: args.razon ?? 1.25,
        pasos: pasos.map((p) => ({
          token: p.nombre,
          tamano: p.clamp ? p.clamp : `${p.rem}rem`,
          px_equivalente: p.px,
          interlineado: p.interlinea,
          medida_de_linea: p.medida,
          uso: p.uso,
        })),
        espaciado: espaciado.slice(0, 8).map((e) => ({ token: e.token, valor: `${e.rem}rem`, px: e.px })),
        reglas: [
          'El texto de cuerpo nunca por debajo de 16 px (o 1rem): por debajo, la mitad de la gente lo amplía.',
          'La medida de línea importa tanto como el tamaño: entre 60 y 75 caracteres se lee sin perder el renglón.',
          'Los títulos se aprietan (interlineado 1.1-1.25); el cuerpo se suelta (1.45-1.6).',
          'Usa rem, no px: respeta el tamaño que la persona ya eligió en su navegador.',
        ],
      };
    },
  });

  push({
    module: 'diseno',
    name: 'tokens_generar',
    title: 'Generar tokens de diseño (CSS y formato de diseño)',
    description:
      'Genera el juego completo de tokens a partir de un color base: fondos, texto, escala de marca, roles con su texto encima, tamaños de letra, espaciado, radios y sombras. Los entrega en dos formatos: variables CSS listas para pegar (con capas y sin dependencias) y el formato del grupo de diseño (DTCG, con $value y $type), que es el que entienden Figma y las herramientas nuevas. Con guardar=true los deja escritos en disco.',
    readOnly: false,
    inputSchema: {
      base: z.string().min(3).describe('Color de la marca, ej. #ff3df0'),
      modo: z.enum(['claro', 'oscuro']).optional().describe('Modo de la paleta (oscuro por defecto).'),
      prefijo: z.string().max(20).optional().describe('Prefijo de las variables (ej. "ks" para --ks-primario).'),
      archivo: z.string().optional().describe('Nombre base si quieres guardarlos, ej. "tokens" → tokens.css y tokens.json en datos/diseno/.'),
    },
    handler: async (args: { base: string; modo?: 'claro' | 'oscuro'; prefijo?: string; archivo?: string }) => {
      const paleta = generarPaleta(args.base, { modo: args.modo });
      const tipografia = escalaTipografica({ fluido: true });
      const espaciado = escalaEspaciado();
      const entrada = { paleta, tipografia, espaciado, prefijo: args.prefijo };
      const css = tokensCss(entrada);
      const dtcg = tokensDTCG(entrada);

      let guardado: { css: string; json: string } | undefined;
      if (args.archivo) {
        const dir = path.join(ctx.config.dataDir, 'diseno');
        await fs.mkdir(dir, { recursive: true });
        const nombre = args.archivo.replace(/[^a-zA-Z0-9_-]/g, '');
        const rutaCss = path.join(dir, `${nombre}.css`);
        const rutaJson = path.join(dir, `${nombre}.json`);
        await fs.writeFile(rutaCss, css, 'utf8');
        await fs.writeFile(rutaJson, JSON.stringify(dtcg, null, 2), 'utf8');
        guardado = { css: rutaCss, json: rutaJson };
        log.info('tokens_guardados', { css: rutaCss, json: rutaJson, base: args.base });
      }

      return {
        base: args.base,
        modo: paleta.modo,
        variables: paleta.escala.length + paleta.roles.length * 2 + tipografia.length * 2 + espaciado.length + 9,
        avisos: paleta.avisos,
        guardado,
        css,
        dtcg,
        como_se_usa: [
          'Pega el CSS al principio de tu hoja de estilos; todo lo demás usa var(--…).',
          'El JSON es para Figma, Tokens Studio o cualquier herramienta que lea tokens.',
          'Cambiar la marca es cambiar el color base y volver a generar: nada más.',
        ],
      };
    },
  });

  push({
    module: 'diseno',
    name: 'componente_generar',
    title: 'Generar un componente en CSS moderno',
    description:
      'Escribe el CSS de un componente (botón, tarjeta, campo, insignia, aviso o interruptor) usando sólo lo que ya corre en los navegadores actuales: capas (@layer), anidado, color-mix() para los estados, foco visible con :focus-visible, objetivo táctil de 44 px y respeto a quien pidió menos movimiento. Usa los tokens, así que hereda la marca y el modo claro u oscuro sin retocar nada.',
    readOnly: false,
    inputSchema: {
      tipo: z.enum(['boton', 'tarjeta', 'campo', 'insignia', 'aviso', 'interruptor']).describe('Qué componente generar.'),
      prefijo: z.string().max(20).optional().describe('El mismo prefijo que usaste en los tokens (ej. "ks").'),
      variantes: z.boolean().optional().describe('Incluye clases de variante (secundario, peligro, éxito) cuando aplique.'),
      archivo: z.string().optional().describe('Si quieres guardarlo, ej. "boton" → datos/diseno/boton.css'),
    },
    handler: async (args: { tipo: TipoComponente; prefijo?: string; variantes?: boolean; archivo?: string }) => {
      let css = componenteCss(args.tipo, { prefijo: args.prefijo, conContenedor: true });
      if (args.variantes === false) css = css.replace(/\n  \.[a-z-]+--(secundario|peligro|exito|aviso) \{[^}]*\}/g, '');
      let guardado: string | undefined;
      if (args.archivo) {
        const dir = path.join(ctx.config.dataDir, 'diseno');
        await fs.mkdir(dir, { recursive: true });
        const nombre = args.archivo.replace(/[^a-zA-Z0-9_-]/g, '');
        guardado = path.join(dir, `${nombre}.css`);
        await fs.writeFile(guardado, css, 'utf8');
      }
      const html = {
        boton: '<button class="boton">Guardar cambios</button>',
        tarjeta: '<article class="tarjeta"><h3>Pedido P-0002</h3><p>10 teclados · entregado el martes</p></article>',
        campo: '<label class="campo">Correo<input type="email" name="correo" autocomplete="email"><span class="ayuda">Sólo para avisos del pedido.</span></label>',
        insignia: '<span class="insignia insignia--exito">pagado</span>',
        aviso: '<div class="aviso" data-tono="aviso" role="status"><span aria-hidden="true">!</span><p>Quedan 3 piezas de este producto.</p></div>',
        interruptor: '<label class="interruptor"><input type="checkbox" role="switch" checked>Avisarme por correo</label>',
      }[args.tipo];
      return {
        tipo: args.tipo,
        css,
        html_de_ejemplo: html,
        guardado,
        por_que_asi: [
          'El foco se ve con :focus-visible: quien navega con teclado no se pierde, y quien usa ratón no ve el anillo.',
          'Los estados se calculan con color-mix() en oklch: un solo color de marca y el resto se deriva.',
          'Nada de píxeles mágicos: todo sale de los tokens, así que el modo claro u oscuro no pide más CSS.',
          args.tipo === 'boton' ? 'El mínimo de 44 px de alto es de WCAG 2.2: objetivo táctil cómodo.' : 'El movimiento se apaga si el sistema pide menos.',
        ],
      };
    },
  });

  push({
    module: 'diseno',
    name: 'accesibilidad_auditar',
    title: 'Auditar la accesibilidad de un HTML',
    description:
      'Revisa un archivo HTML y reporta lo que se puede comprobar sin navegador: idioma, título, imágenes sin texto alternativo, campos sin etiqueta, botones y enlaces sin nombre, saltos de encabezados, foco cancelado sin reemplazo, zoom bloqueado, movimiento automático y los pares de color declarados en línea que no alcanzan el contraste mínimo. Da un puntaje y, en cada hallazgo, la línea y cómo se arregla. No sustituye probar con lector de pantalla, pero encuentra lo que casi nadie revisa.',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Archivo HTML a revisar (relativo a la carpeta de datos, al proyecto o absoluto).'),
      detalle: z.enum(['todo', 'solo_alto']).optional().describe('solo_alto muestra únicamente lo que hay que atender ya.'),
    },
    handler: async (args: { ruta: string; detalle?: 'todo' | 'solo_alto' }) => {
      const file = resolveAllowedPath(ctx.config.dataDir, args.ruta, [process.cwd()]);
      const html = await readTextFile(file, 20_000_000);
      const r = auditarHtml(html);
      const hallazgos = args.detalle === 'solo_alto' ? r.hallazgos.filter((h) => h.severidad === 'alto') : r.hallazgos;
      return {
        archivo: file,
        puntaje: r.puntaje,
        resumen: r.resumen,
        conteos: r.conteos,
        hallazgos: hallazgos.map((h) => ({ ...h, linea: h.linea ?? null })),
        como_se_arregla: hallazgos.slice(0, 3).map((h) => ({ codigo: h.codigo, linea: h.linea ?? null, sugerencia: h.sugerencia })),
        siguiente: 'Después de arreglar, vuelve a correrla: el puntaje tiene que subir y los hallazgos de atención alta desaparecer.',
      };
    },
  });

  push({
    module: 'diseno',
    name: 'estilo_revisar',
    title: 'Revisar una hoja de estilos',
    description:
      'Revisa un CSS y dice qué arreglar y qué modernizar: colores escritos a mano que deberían ser tokens, variables usadas que nadie declara, abuso de !important, foco cancelado, animaciones que no respetan a quien pidió menos movimiento, tamaños de letra en píxeles, pares de color que no alcanzan contraste y si falta modo claro u oscuro. Además reporta qué de lo moderno ya usa (oklch, color-mix, capas, anidado, contenedores, clamp).',
    readOnly: true,
    inputSchema: {
      ruta: z.string().describe('Archivo CSS a revisar.'),
      detalle: z.enum(['todo', 'solo_alto']).optional().describe('solo_alto deja únicamente lo urgente.'),
    },
    handler: async (args: { ruta: string; detalle?: 'todo' | 'solo_alto' }) => {
      const file = resolveAllowedPath(ctx.config.dataDir, args.ruta, [process.cwd()]);
      const css = await readTextFile(file, 20_000_000);
      const r = revisarEstilo(css);
      const hallazgos = args.detalle === 'solo_alto' ? r.hallazgos.filter((h) => h.severidad === 'alto') : r.hallazgos;
      const faltantes = Object.entries(r.moderno).filter(([, v]) => !v).map(([k]) => k);
      return {
        archivo: file,
        puntaje: r.puntaje,
        resumen: r.resumen,
        conteos: r.conteos,
        metricas: r.metricas,
        lo_moderno: r.moderno,
        por_modernizar: faltantes,
        hallazgos: hallazgos.map((h) => ({ ...h, linea: h.linea ?? null })),
        siguiente: 'Si vas a construir el sistema de verdad, genera tokens con tokens_generar y ve sustituyendo los colores sueltos.',
      };
    },
  });
}
