/**
 * Pruebas del módulo «centinela» (fase 4B): escaneo de seguridad, plan de
 * corrección comprobable, verificación contra línea base y gate de aprobación.
 *
 * El proyecto de ejemplo se construye con vulnerabilidades REALES (con
 * credenciales falsas pero con la forma exacta de las verdaderas) porque la
 * suite es limpia por diseño: no se inventan hallazgos, se demuestran.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { projectRoot } from '../src/config';
import { buildServer } from '../src/server';
import { contextoDePrueba, dataDirTemporal } from './helpers';
import { escanear } from '../src/lib/centinela';
import {
  solicitarAprobacion,
  resolverAprobacion,
  exigirAprobacion,
  requiereAprobacion,
  expirarVencidas,
  huellaDeContenido,
  obtenerAprobacion,
} from '../src/lib/aprobaciones';
import type { ServerCtx } from '../src/context';

let client: Client;
let ctx: ServerCtx;
let dataDir: string;
let vulnerables: string;
let ignorables: string;
let mejorable: string;
let paraCli: string;
let rutas: string;
let cerrar: () => Promise<void>;

async function llamar(nombre: string, args: Record<string, unknown> = {}) {
  const r = await client.callTool({ name: nombre, arguments: args });
  const texto = (r.content as { type: string; text: string }[])[0]!.text;
  return { isError: Boolean(r.isError), texto, json: () => JSON.parse(texto) };
}

async function escribir(raiz: string, rel: string, contenido: string): Promise<void> {
  const absoluto = path.join(raiz, rel);
  await fs.mkdir(path.dirname(absoluto), { recursive: true });
  await fs.writeFile(absoluto, contenido, 'utf8');
}

/** Proyecto con los errores más comunes y peligrosos, tal como llegan a producción. */
async function crearProyectoVulnerable(raiz: string): Promise<void> {
  await escribir(raiz, 'src/config.js', [
    '// Configuración del servicio (proyecto de ejemplo para inspección)',
    'const AWS_ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";',
    'const AWS_SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";',
    'const OPENAI_API_KEY = "sk-proj-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2";',
    'const GITHUB_TOKEN = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";',
    '',
    'module.exports = { AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, OPENAI_API_KEY, GITHUB_TOKEN };',
  ].join('\n'));
  await escribir(raiz, 'src/db.js', [
    'const mysql = require("mysql2");',
    'const pool = mysql.createPool(process.env.DATABASE_URL);',
    '',
    'function buscarUsuario(email) {',
    '  return pool.query("SELECT id, email, rol FROM usuarios WHERE email = \'" + email + "\'");',
    '}',
    '',
    'function evaluarFormula(expresion) {',
    '  return eval(expresion);',
    '}',
    '',
    'module.exports = { buscarUsuario, evaluarFormula };',
  ].join('\n'));
  await escribir(raiz, 'src/api.js', [
    'const express = require("express");',
    'const { buscarUsuario } = require("./db");',
    '',
    'const app = express();',
    '',
    'app.get("/reportes/:id", (req, res) => {',
    '  // TODO: falta validar el token de sesión',
    '  res.json({ id: req.params.id, datos: [] });',
    '});',
    '',
    'app.get("/usuarios/:email", (req, res) => {',
    '  res.json(buscarUsuario(req.db, req.params.email));',
    '});',
    '',
    'app.listen(3000);',
  ].join('\n'));
  await escribir(raiz, 'package.json', JSON.stringify({
    name: 'proy-inspeccion',
    version: '0.3.0',
    private: true,
    dependencies: { express: '4.18.2', jsonwebtoken: '8.5.1', lodash: '4.17.10', request: '2.88.2', sqlite3: '5.1.5' },
  }, null, 2));
  await escribir(raiz, 'deploy.sh', '#!/usr/bin/env bash\nset -euo pipefail\ncurl -fsSL https://get.ejemplo-instalador.dev/agent.sh | sh\nnpm install --production\n');
  await escribir(raiz, '.gitignore', 'node_modules/\n.env\n');
  await escribir(raiz, '.env', 'STRIPE_SECRET_KEY=' + 'sk_live_' + '51H8xYZabcdefGHIJKLmnopQRSTuvwxYZ0123456789\n');
}

/** Proyecto medible: sin críticos y con margen real de mejora, para demostrar la verificación. */
async function crearProyectoMejorable(raiz: string): Promise<void> {
  await escribir(raiz, 'src/db.js', [
    'const mysql = require("mysql2");',
    'const pool = mysql.createPool(process.env.DATABASE_URL);',
    '',
    'function buscarUsuario(email) {',
    '  return pool.query("SELECT id, email FROM usuarios WHERE email = \'" + email + "\'");',
    '}',
    '',
    'function calcularDescuento(expresion) {',
    '  return eval(expresion);',
    '}',
    '',
    'module.exports = { buscarUsuario, calcularDescuento };',
  ].join('\n'));
  await escribir(raiz, 'package.json', JSON.stringify({
    name: 'proy-mejorable',
    version: '1.2.0',
    private: true,
    dependencies: { lodash: '4.17.10' },
  }, null, 2));
}

/** Fixture de permisos: cada forma de proteger una ruta, y una que queda al aire. */
async function crearProyectoRutas(raiz: string): Promise<void> {
  await escribir(raiz, 'src/servidor.js', [
    'const express = require("express");',
    'const app = express();',
    'const { requireAuth } = require("./auth");',
    '',
    'app.get("/salud", (req, res) => res.json({ ok: true }));',
    '',
    'app.get("/reportes/:id", (req, res) => {',
    '  res.json({ id: req.params.id });',
    '});',
    '',
    'app.use(requireAuth);',
    '',
    'app.get("/pedidos", (req, res) => {',
    '  res.json({ usuario: req.user });',
    '});',
    '',
    'app.post("/pagos", requireAuth, (req, res) => res.json({ ok: true }));',
    '',
    'app.listen(3000);',
    '',
  ].join('\n'));
  // Archivo sin ninguna mención de autenticación: aquí sí queda al aire.
  await escribir(raiz, 'src/reportes.js', [
    'const express = require("express");',
    'const router = express.Router();',
    '',
    'router.get("/exportar/:id", (req, res) => {',
    '  res.json({ id: req.params.id, filas: [] });',
    '});',
    '',
    'module.exports = router;',
    '',
  ].join('\n'));
  await escribir(raiz, 'package.json', JSON.stringify({ name: 'proy-rutas', version: '1.0.0', private: true }, null, 2));
}

beforeAll(async () => {
  dataDir = await dataDirTemporal('mcp-suite-centinela-');
  vulnerables = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-vulnerable-'));
  ignorables = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-ignorables-'));
  rutas = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-rutas-'));
  await crearProyectoRutas(rutas);
  mejorable = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-mejorable-'));
  paraCli = await fs.mkdtemp(path.join(os.tmpdir(), 'proyecto-cli-'));
  await crearProyectoVulnerable(vulnerables);
  await crearProyectoMejorable(mejorable);
  await crearProyectoMejorable(paraCli);
  await escribir(ignorables, 'secreto.js', 'const TOKEN = "ghp_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";\nmodule.exports = { TOKEN };\n');

  ctx = await contextoDePrueba(
    dataDir,
    { allowedGitRoots: [projectRoot, vulnerables, ignorables, mejorable, paraCli, rutas] },
    { rpm: 6000, burst: 1000 },
  );

  const { server } = buildServer(ctx);
  const [tCliente, tServidor] = InMemoryTransport.createLinkedPair();
  client = new Client({ name: 'test-client-centinela', version: '1.0.0' });
  await Promise.all([client.connect(tCliente), server.connect(tServidor)]);
  cerrar = async () => {
    await client.close();
    await server.close();
  };
});

afterAll(async () => {
  await cerrar?.();
  await fs.rm(dataDir, { recursive: true, force: true });
  await fs.rm(vulnerables, { recursive: true, force: true });
  await fs.rm(ignorables, { recursive: true, force: true });
  await fs.rm(mejorable, { recursive: true, force: true });
  await fs.rm(paraCli, { recursive: true, force: true });
  await fs.rm(rutas, { recursive: true, force: true });
});

describe('CENTINELA — catálogo', () => {
  it('expone las herramientas del módulo y el catálogo completo', async () => {
    const { tools } = await client.listTools();
    const nombres = tools.map((t) => t.name);
    for (const esperada of [
      'seguridad_escanear', 'seguridad_plan', 'seguridad_verificar', 'dependencias_revisar',
      'aprobaciones_pendientes', 'permisos_auditar', 'dependencias_vulnerables',
      'empresas_listar', 'empresa_crear', 'empresa_actual', 'empresa_editar', 'empresa_resumen', 'empresas_comparar',
        'inventario_importar',
        'color_convertir', 'contraste_verificar', 'paleta_generar', 'tipografia_escala',
        'tokens_generar', 'componente_generar', 'accesibilidad_auditar', 'estilo_revisar',
      ]) {
        expect(nombres).toContain(esperada);
      }
      expect(tools.length).toBe(76);
  });
});

describe('CENTINELA — escaneo', () => {
  it('detecta los riesgos reales del proyecto de ejemplo', async () => {
    const { isError, json } = await llamar('seguridad_escanear', { directorio: vulnerables, formato: 'completo' });
    expect(isError).toBe(false);
    const e = json();
    expect(e.semaforo).toBe('rojo');
    expect(e.puntaje).toBeLessThan(60);
    expect(e.archivos_analizados).toBeGreaterThanOrEqual(6);
    const reglas = e.hallazgos.map((h: { regla: string }) => h.regla);
    for (const regla of ['SEC001', 'SEC003', 'SEC004', 'SEC005', 'CFG006', 'CFG007', 'CFG012', 'DEP001', 'HIG002']) {
      expect(reglas, `falta la regla ${regla}`).toContain(regla);
    }
    // Ordenados por severidad: lo primero que se ve es lo crítico.
    expect(e.hallazgos[0].severidad).toBe('critica');
    const rutaAws = e.hallazgos.find((h: { regla: string }) => h.regla === 'SEC001');
    expect(rutaAws.archivo).toBe('src/config.js');
  });

  it('nunca muestra el secreto completo: lo redacta', async () => {
    const { texto } = await llamar('seguridad_escanear', { directorio: vulnerables, formato: 'completo' });
    expect(texto).not.toContain('wJalrXUtnFEMI/K7MDENG');
    expect(texto).not.toContain('ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789');
    expect(texto).toContain('redactado');
  });

  it('.centinela-ignore excluye hallazgos con motivo declarado', async () => {
    const antes = await llamar('seguridad_escanear', { directorio: ignorables, formato: 'completo' });
    expect(antes.json().hallazgos.map((h: { regla: string }) => h.regla)).toContain('SEC005');
    await escribir(ignorables, '.centinela-ignore', '# credenciales falsas usadas a propósito en pruebas\nSEC005:secreto.js # token de pruebas, jamás fue real ni estuvo activo\n');
    const despues = await llamar('seguridad_escanear', { directorio: ignorables, formato: 'completo' });
    const d = despues.json();
    expect(d.hallazgos.map((h: { regla: string }) => h.regla)).not.toContain('SEC005');
    const ignorado = d.ignorados.find((i: { regla: string }) => i.regla === 'SEC005');
    expect(ignorado?.motivo).toContain('token de pruebas');
  });

  it('la propia suite es limpia por diseño: sin críticos ni ejecución dinámica', async () => {
    const { json } = await llamar('seguridad_escanear', { directorio: projectRoot, formato: 'completo' });
    const e = json();
    expect(e.archivos_analizados).toBeGreaterThan(30);
    const reglas = new Set(e.hallazgos.map((h: { regla: string }) => h.regla));
    for (const regla of ['SEC001', 'SEC003', 'SEC004', 'SEC005', 'SEC007', 'CFG006', 'CFG012', 'DEP001']) {
      expect(reglas.has(regla), `la suite no debería tener ${regla}`).toBe(false);
    }
    expect(e.resumen.critica).toBe(0);
  });

  it('rechaza directorios fuera de ALLOWED_GIT_ROOTS', async () => {
    const { isError, texto } = await llamar('seguridad_escanear', { directorio: '/etc' });
    expect(isError).toBe(true);
    expect(texto).toContain('fuera de las rutas permitidas');
  });
});

describe('CENTINELA — plan de corrección', () => {
  it('cada paso dice qué hacer, dónde y cómo comprobar que quedó', async () => {
    const { json } = await llamar('seguridad_plan', { directorio: vulnerables });
    const p = json();
    expect(p.total_pasos).toBeGreaterThan(3);
    expect(p.pasos[0].prioridad).toBe('ahora');
    for (const paso of p.pasos) {
      expect(paso.verificacion.length).toBeGreaterThan(20);
      expect(['S', 'M', 'L']).toContain(paso.esfuerzo);
      expect(paso.archivos.length).toBeGreaterThan(0);
    }
    expect(p.pasos.map((x: { regla: string }) => x.regla)).toContain('CFG007');
    expect(p.como_verificar_el_resultado).toContain('seguridad_verificar');
  });
});

describe('CENTINELA — verificación contra línea base', () => {
  it('demuestra qué se resolvió, qué permanece y qué es nuevo', async () => {
    // 1) Línea base del proyecto medible.
    const base = await llamar('seguridad_escanear', { directorio: mejorable, guardar_linea_base: true });
    expect(base.json().semaforo).toBe('amarillo');
    expect(base.json().linea_base).toContain('escaneo-');

    // 2) Corrección real: consultas parametrizadas y sin ejecución dinámica.
    await escribir(mejorable, 'src/db.js', [
      'const mysql = require("mysql2");',
      'const pool = mysql.createPool(process.env.DATABASE_URL);',
      '',
      'function buscarUsuario(email) {',
      '  return pool.query("SELECT id, email FROM usuarios WHERE email = ?", [email]);',
      '}',
      '',
      'module.exports = { buscarUsuario };',
    ].join('\n'));

    // 3) Verificación: las dos reglas corregidas salen como resueltas.
    const v = await llamar('seguridad_verificar', { directorio: mejorable });
    const j = v.json();
    expect(j.veredicto).toBe('mejoro');
    expect(j.despues.puntaje).toBeGreaterThan(j.antes.puntaje);
    expect(j.porcentaje_resuelto).toBeGreaterThan(0);
    const resueltas = j.resueltos.map((h: { regla: string }) => h.regla);
    expect(resueltas).toContain('CFG006');
    expect(resueltas).toContain('CFG007');
    expect(j.permanecen.map((h: { regla: string }) => h.regla)).toContain('DEP001');
    expect(j.despues.semaforo).not.toBe('rojo');
    expect(v.texto).toContain('resueltos');

    // 4) Un secreto nuevo debe salir como NUEVO y hacer bajar el puntaje.
    await escribir(mejorable, 'src/integracion.js', 'const CLAVE = "sk-proj-zzzz1111yyyy2222pppp3333wwww4444vvvv5555";\nmodule.exports = { CLAVE };\n');
    const v2 = await llamar('seguridad_verificar', { directorio: mejorable });
    const j2 = v2.json();
    expect(j2.nuevos.map((h: { regla: string }) => h.regla)).toContain('SEC004');
    expect(j2.veredicto).toBe('empeoro');
    expect(j2.antes.hallazgos).toBe(j.despues.hallazgos);
  });

  it('un avance real cuenta aunque el puntaje siga en el piso', async () => {
    // Proyecto con críticas: el puntaje está en 0 y no se mueve al quitar UNA alta...
    const base = await llamar('seguridad_escanear', { directorio: vulnerables, guardar_linea_base: true });
    expect(base.json().puntaje).toBe(0);
    await escribir(vulnerables, 'src/db.js', 'const mysql = require("mysql2");\nmodule.exports = {};\n');
    // ...pero el hallazgo CFG006 sí quedó resuelto y eso es un avance demostrable.
    const v = await llamar('seguridad_verificar', { directorio: vulnerables });
    const j = v.json();
    expect(j.despues.puntaje).toBe(0);
    expect(j.veredicto).toBe('mejoro');
    expect(j.resueltos.map((h: { regla: string }) => h.regla).sort()).toEqual(['CFG006', 'CFG007']);
    expect(j.mensaje).toContain('puntaje todavía no sube');
  });

  it('sin línea base previa explica cómo crearla en vez de fallar en silencio', async () => {
    const { isError, texto } = await llamar('seguridad_verificar', { directorio: ignorables });
    expect(isError).toBe(true);
    expect(texto).toContain('línea base');
  });
});

describe('CENTINELA — dependencias', () => {
  it('resume dependencias, lockfile y avisos locales', async () => {
    const { json } = await llamar('dependencias_revisar', { directorio: vulnerables });
    const d = json();
    expect(d.dependencias).toBe(5);
    expect(d.lockfile).toBe(false);
    expect(d.total_avisos).toBeGreaterThanOrEqual(2);
    expect(d.avisos_locales.map((a: { regla: string }) => a.regla)).toContain('DEP001');
    expect(d.alcance_de_la_revision).toContain('npm audit');
  });
});

describe('GATE DE APROBACIÓN', () => {
  it('nada se autoriza sin resolución humana: pendiente → rechazada → aprobada', async () => {
    const huella = huellaDeContenido('parche: mover secretos a variables de entorno');
    const ap = await solicitarAprobacion(ctx, {
      tipo: 'parche',
      titulo: 'Aplicar parche de seguridad en src/config.js',
      detalle: 'Sustituye las credenciales por variables de entorno con respaldo local.',
      huella,
    });
    expect(ap.id).toMatch(/^AP-[0-9a-f]{6}$/);
    expect(ap.estado).toBe('pendiente');

    // Sin id no hay permiso; con id pendiente tampoco.
    expect(exigirAprobacion(ctx, undefined).permitido).toBe(false);
    const pendiente = exigirAprobacion(ctx, ap.id);
    expect(pendiente.permitido).toBe(false);
    expect(pendiente.motivo).toContain('pendiente');

    // Se rechaza: sigue sin autorizar y no se puede re-resolver.
    const rechazada = await resolverAprobacion(ctx, ap.id, 'rechazada', { por: 'tester', motivo: 'faltó respaldo' });
    expect(rechazada.estado).toBe('rechazada');
    expect(exigirAprobacion(ctx, ap.id).permitido).toBe(false);
    await expect(resolverAprobacion(ctx, ap.id, 'aprobada', { por: 'tester' })).rejects.toThrow(/ya está/);

    // Segunda solicitud aprobada: autoriza SOLO con la huella correcta.
    const ap2 = await solicitarAprobacion(ctx, { tipo: 'parche', titulo: 'Aplicar parche corregido', detalle: 'con respaldo', huella });
    await resolverAprobacion(ctx, ap2.id, 'aprobada', { por: 'tester' });
    expect(exigirAprobacion(ctx, ap2.id, huella).permitido).toBe(true);
    const cambiado = exigirAprobacion(ctx, ap2.id, huellaDeContenido('otro contenido'));
    expect(cambiado.permitido).toBe(false);
    expect(cambiado.motivo).toContain('huella');

    // `requiereAprobacion` lanza mensaje claro cuando no hay permiso.
    expect(() => requiereAprobacion(ctx, undefined)).toThrow(/aprobación/i);
    expect(requiereAprobacion(ctx, ap2.id, huella).id).toBe(ap2.id);
  });

  it('el ciclo completo pasa por las herramientas y solo una persona autoriza', async () => {
    const plan = await llamar('seguridad_plan', { directorio: mejorable, solicitar_aplicacion: true });
    const g = plan.json().gate_de_aprobacion;
    expect(g.aprobacion).toMatch(/^AP-[0-9a-f]{6}$/);
    expect(g.estado).toBe('pendiente');
    expect(g.como_aprobar).toContain('aprobar');

    // Mientras no haya resolución humana, la consulta no autoriza nada.
    const antes = await llamar('seguridad_plan', { directorio: mejorable, aprobacion_id: g.aprobacion });
    expect(antes.json().gate_de_aprobacion.autorizado_para_aplicar).toBe(false);

    // La cola es visible para la persona (identificador + cómo resolverlo).
    const cola = await llamar('aprobaciones_pendientes');
    const pendiente = cola.json().pendientes.find((x: { id: string }) => x.id === g.aprobacion);
    expect(pendiente).toBeTruthy();
    expect(pendiente.como_resolver.aprobar).toContain(g.aprobacion);
    expect(cola.json().recuerda).toContain('no puede resolver');

    // Se aprueba por fuera del canal del modelo, como en la vida real.
    await resolverAprobacion(ctx, g.aprobacion, 'aprobada', { por: 'tester-cli' });
    const despues = await llamar('seguridad_plan', { directorio: mejorable, aprobacion_id: g.aprobacion });
    expect(despues.json().gate_de_aprobacion.autorizado_para_aplicar).toBe(true);

    // Si el plan cambia (nuevo hallazgo), la autorización deja de valer.
    await escribir(mejorable, 'src/extra.js', 'const T = "ghp_QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";\n');
    const cambiado = await llamar('seguridad_plan', { directorio: mejorable, aprobacion_id: g.aprobacion });
    expect(cambiado.json().gate_de_aprobacion.autorizado_para_aplicar).toBe(false);
    expect(cambiado.json().gate_de_aprobacion.motivo).toContain('huella');
  });

  it('deja rastro inmutable en la auditoría', async () => {
    const texto = await fs.readFile(path.join(dataDir, 'runtime', 'auditoria.ndjson'), 'utf8');
    const eventos = texto.trim().split('\n').map((l) => JSON.parse(l));
    const tipos = eventos.map((e) => e.tipo);
    expect(tipos).toContain('aprobacion_solicitada');
    expect(tipos).toContain('aprobacion_aprobada');
    expect(tipos).toContain('aprobacion_rechazada');
  });

  it('una aprobación vencida deja de servir', async () => {
    const ap = await solicitarAprobacion(ctx, { tipo: 'despliegue', titulo: 'Publicar versión 0.4.0', detalle: 'sin cambios de fondo' });
    // Simula que la solicitud quedó olvidada desde ayer.
    ctx.db.prepare('UPDATE aprobaciones SET solicitada_en = ? WHERE id = ?')
      .run(new Date(Date.now() - 26 * 60 * 60_000).toISOString(), ap.id);
    expect(expirarVencidas(ctx)).toBeGreaterThanOrEqual(1);
    expect(obtenerAprobacion(ctx, ap.id)?.estado).toBe('expirada');
    const r = exigirAprobacion(ctx, ap.id);
    expect(r.permitido).toBe(false);
    expect(r.motivo).toContain('expirada');
  });
});

describe('CENTINELA — CLI', () => {
  const hayDist = existsSync(path.join(projectRoot, 'dist', 'cli.js'));
  const entorno = () => ({
    ...process.env,
    DATA_DIR: dataDir,
    ALLOWED_GIT_ROOTS: `${projectRoot}${path.delimiter}${paraCli}`,
    LOG_LEVEL: 'error',
  });
  const cli = (args: string[]) =>
    execFileSync('node', [path.join(projectRoot, 'dist', 'cli.js'), ...args], { env: entorno(), encoding: 'utf8' });

  it.skipIf(!hayDist)('seguridad --minimo falla con código 1 si el puntaje no alcanza', () => {
    let fallo = 0;
    try {
      cli(['seguridad', '--directorio', paraCli, '--minimo', '95']);
    } catch (e: any) {
      fallo = e.status;
      expect(String(e.stdout)).toContain('"semaforo": "amarillo"');
      expect(String(e.stderr)).toContain('por debajo del mínimo');
    }
    expect(fallo).toBe(1);
  });

  it.skipIf(!hayDist)('seguridad --plan emite el plan con verificación paso a paso', () => {
    const salida = cli(['seguridad', '--directorio', paraCli, '--plan']);
    expect(salida).toContain('**Verificación:**');
    expect(salida).toContain('CFG007');
  });

  it.skipIf(!hayDist)('aprobar exige un id y lo explica', () => {
    let salida = '';
    try {
      cli(['aprobar']);
    } catch (e: any) {
      salida = String(e.stdout) + String(e.stderr);
    }
    expect(salida).toContain('aprobar <id>');
  });
});

describe('CENTINELA — permisos reales de las rutas', () => {
  it('distingue guard global, guard en la línea y ruta descubierta', async () => {
    const { json } = await llamar('permisos_auditar', { directorio: rutas });
    const j = json();
    const porRuta = Object.fromEntries(j.todas.map((r: { ruta: string; proteccion: string }) => [r.ruta, r.proteccion]));
    expect(porRuta['/pedidos']).toBe('guard_global');
    expect(porRuta['/pagos']).toBe('middleware_en_linea');
    expect(porRuta['/reportes/:id']).toBe('indicio_en_archivo');
    expect(porRuta['/exportar/:id']).toBe('ninguna');
    expect(j.descubiertas.map((r: { ruta: string }) => r.ruta)).toEqual(['/exportar/:id']);
    expect(j.descubiertas[0].evidencia.length).toBeGreaterThan(10);
    expect(j.recomendaciones[0]).toContain('/exportar/:id');
    // /salud no alarma: el archivo sí verifica sesión en sus rutas privadas.
    expect(j.recomendaciones.join(' ')).not.toContain('/salud');
  });
});

describe('CENTINELA — dependencias con avisos reales', () => {
  it('siempre responde: fuente real si puede, tabla local si no', async () => {
    const { isError, json } = await llamar('dependencias_vulnerables', { directorio: vulnerables });
    expect(isError).toBe(false);
    const j = json();
    expect(['npm-audit', 'tabla-local']).toContain(j.fuente);
    expect(j.avisos_locales.length).toBeGreaterThanOrEqual(3);
    expect(j.como_interpretarlo.length).toBeGreaterThan(20);
  });

  it('una fuente rota cae a la tabla local con una nota honesta', async () => {
    const { revisarDependenciasVulnerables } = await import('../src/lib/centinela-extra');
    const r = await revisarDependenciasVulnerables(ctx, vulnerables, { comando: 'node -e "process.exit(1)"', forzar: true });
    expect(r.fuente).toBe('tabla-local');
    expect(r.avisos_locales.length).toBeGreaterThanOrEqual(3);
    expect(r.notas.join(' ')).toContain('tabla local');
  });

  it('el JSON de error de npm (ENOLOCK) no se confunde con "cero avisos" — ni con ruido en stderr', async () => {
    // npm imprime el JSON en stdout y, además, líneas de error en stderr: el
    // recorte debe quedarse con el objeto balanceado y no romperse en silencio.
    const errorNpm = JSON.stringify({ error: { code: 'ENOLOCK', summary: 'This command requires an existing lockfile.' } });
    const archivo = `${mejorable}/audit-error.sh`;
    await fs.writeFile(
      archivo,
      `#!/bin/sh\necho '${errorNpm}'\necho 'npm error code ENOLOCK' >&2\necho 'npm error Try creating one first with: npm i --package-lock-only' >&2\nexit 1\n`,
      'utf8',
    );
    await fs.chmod(archivo, 0o755);
    const { revisarDependenciasVulnerables } = await import('../src/lib/centinela-extra');
    const r = await revisarDependenciasVulnerables(ctx, mejorable, { comando: './audit-error.sh', forzar: true });
    expect(r.fuente).toBe('tabla-local');
    expect(r.notas.join(' ')).toMatch(/ENOLOCK/);
    expect(r.avisos_locales.length).toBeGreaterThanOrEqual(1); // lodash viejo sí aparece
  });

  it('el recorte del JSON de npm es balanceado (soporta llaves dentro de cadenas)', async () => {
    const { extraerObjetoJson } = await import('../src/lib/centinela-extra');
    const texto = 'basura previa\n{"a":{"b":"} no cierra "},"metadata":{"vulnerabilities":{"total":0}}}\nnpm error cola';
    const json = extraerObjetoJson(texto);
    expect(json).toBe('{"a":{"b":"} no cierra "},"metadata":{"vulnerabilities":{"total":0}}}');
    expect(extraerObjetoJson('sin json aquí')).toBeNull();
  });

  it('una salida ilegible de npm NO se reporta como "cero vulnerabilidades"', async () => {
    const { interpretarAudit } = await import('../src/lib/centinela-extra');
    expect(interpretarAudit('esto no es json').total).toBe(-1);
    expect(interpretarAudit('{"error":{"code":"ENOLOCK"}}').total).toBe(-1);
  });

  it('con una fuente real (comando simulado) interpreta los avisos y los cachea', async () => {
    const salida = JSON.stringify({
      vulnerabilities: {
        lodash: { severity: 'high', range: '<4.17.21', via: [{ title: 'Prototype Pollution', url: 'https://example.invalid/x' }], fixAvailable: { version: '4.17.21' } },
        minimist: { severity: 'critical', range: '<1.2.6', via: ['Prototype Pollution'], fixAvailable: true },
      },
    });
    const archivo = path.join(mejorable, 'audit-falso.sh');
    await fs.writeFile(archivo, `#!/bin/sh\ncat <<'JSON'\n${salida}\nJSON\n`, 'utf8');
    await fs.chmod(archivo, 0o755);

    const { revisarDependenciasVulnerables } = await import('../src/lib/centinela-extra');
    const r = await revisarDependenciasVulnerables(ctx, mejorable, { comando: './audit-falso.sh', forzar: true });
    expect(r.fuente).toBe('npm-audit');
    expect(r.total).toBe(2);
    expect(r.por_severidad.alta).toBe(1);
    expect(r.por_severidad.critica).toBe(1);
    expect(r.avisos.map((a) => a.paquete)).toEqual(['lodash', 'minimist']);
    expect(r.avisos[1].corrige).toContain('actualización automática');

    // Segunda llamada sin forzar: viene de la caché.
    const r2 = await revisarDependenciasVulnerables(ctx, mejorable, { comando: './audit-falso.sh' });
    expect(r2.cacheada).toBe(true);
    expect(r2.total).toBe(2);
  });
});

describe('CENTINELA — integridad del propio motor', () => {
  it('escanear dos veces el mismo proyecto da el mismo resultado (determinista)', async () => {
    const a = await escanear(vulnerables);
    const b = await escanear(vulnerables);
    expect(a.hallazgos.map((h) => h.clave)).toEqual(b.hallazgos.map((h) => h.clave));
    expect(a.puntaje).toBe(b.puntaje);
  });
});
