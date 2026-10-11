#!/usr/bin/env node
/**
 * CLI de administración. Todo va a stdout en formato legible (stderr para logs).
 *
 *   node dist/cli.js keys create --nombre staff --scopes negocio,analisis
 *   node dist/cli.js keys list
 *   node dist/cli.js keys revoke --nombre staff
 *   node dist/cli.js audit --limite 20
 *   node dist/cli.js check
 */
import { loadConfig } from './config.js';
import path from 'node:path';
import { setLogLevel } from './logger.js';
import { crearContexto } from './index.js';
import { crearClave, listarClaves, revocarClave, contarClavesActivas, SCOPES_VALIDOS } from './auth/keys.js';
import { crearBackup, listarBackups } from './lib/backup.js';
import { listarAprobaciones, resolverAprobacion, expirarVencidas } from './lib/aprobaciones.js';
import { escanear, planDeCorreccion, planMarkdown } from './lib/centinela.js';
import { listarParches, obtenerParche, deshacerParche } from './lib/parche.js';
import { FLUJOS_PREDEFINIDOS, leerFlujosPropios, ultimasCorridas } from './lib/orquesta.js';
import { leerCatalogo, compararCostes } from './lib/ruter.js';

function flag(nombre: string): string | undefined {
  const prefix = `--${nombre}=`;
  const exacto = process.argv.find((a) => a.startsWith(prefix));
  if (exacto) return exacto.slice(prefix.length);
  const idx = process.argv.indexOf(`--${nombre}`);
  return idx !== -1 && process.argv[idx + 1] && !process.argv[idx + 1]!.startsWith('--') ? process.argv[idx + 1] : undefined;
}

function bool(nombre: string): boolean {
  return process.argv.includes(`--${nombre}`);
}

const [comando, subcomando] = process.argv.slice(2).filter((a) => !a.startsWith('--'));

async function cmdKeysCreate(): Promise<void> {
  const nombre = flag('nombre');
  const scopesRaw = flag('scopes') ?? '*';
  const expiraDiasRaw = flag('expira-dias');
  if (!nombre) {
    console.error('Falta --nombre. Ejemplo: node dist/cli.js keys create --nombre staff --scopes negocio,analisis');
    process.exit(2);
  }
  const ctx = await crearContexto(loadConfig());
  const { key, clave } = await crearClave(ctx, {
    nombre,
    scopes: scopesRaw.split(',').map((s) => s.trim()).filter(Boolean),
    expiraDias: expiraDiasRaw ? Number(expiraDiasRaw) : undefined,
  });
  console.log('Clave creada correctamente.');
  console.log('');
  console.log(`  id:      ${key.id}`);
  console.log(`  nombre:  ${key.nombre}`);
  console.log(`  scopes:  ${key.scopes.join(', ')}`);
  console.log(`  expira:  ${key.expira ?? 'nunca'}`);
  console.log('');
  console.log('  CLAVE (cópiala ahora, no se vuelve a mostrar):');
  console.log(`  ${clave}`);
  console.log('');
  console.log('  Ejemplo de uso:');
  console.log(`  curl -s http://localhost:8787/mcp -H 'Authorization: Bearer ${clave}' \\`);
  console.log(`    -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' \\`);
  console.log(`    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`);
}

async function cmdKeysList(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const claves = await listarClaves(ctx);
  if (claves.length === 0) {
    console.log('No hay claves emitidas. Crea una con: node dist/cli.js keys create --nombre <nombre> --scopes <scopes>');
    return;
  }
  console.log(`${claves.length} clave(s) · scopes válidos: ${SCOPES_VALIDOS.join(', ')}\n`);
  for (const k of claves) {
    console.log(`  ${k.activa ? '●' : '○'} ${k.id}  ${k.nombre.padEnd(20)} scopes=${k.scopes.join(',')}`);
    console.log(`      creada=${k.creada.slice(0, 10)}  expira=${k.expira?.slice(0, 10) ?? 'nunca'}  usos=${k.usos}  último=${k.ultimo_uso?.slice(0, 16) ?? '—'}`);
  }
}

async function cmdKeysRevoke(): Promise<void> {
  const objetivo = flag('id') ?? flag('nombre');
  if (!objetivo) {
    console.error('Falta --id o --nombre. Ejemplo: node dist/cli.js keys revoke --nombre staff');
    process.exit(2);
  }
  const ctx = await crearContexto(loadConfig());
  const r = await revocarClave(ctx, objetivo);
  await ctx.audit.registrar({ tipo: 'clave_revocada', cliente: 'cli', detalle: `${r.id} (${r.nombre})` });
  console.log(`Clave revocada: ${r.id} (${r.nombre}).`);
}

async function cmdAudit(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const limite = Number(flag('limite') ?? 20);
  const eventos = await ctx.audit.leer({
    tipo: (flag('tipo') as any) ?? undefined,
    herramienta: flag('herramienta'),
    cliente: flag('cliente'),
    limite,
  });
  if (eventos.length === 0) {
    console.log(`Bitácora vacía todavía (${ctx.audit.ruta}).`);
    return;
  }
  console.log(`Bitácora: ${ctx.audit.ruta}\nResumen: ${JSON.stringify(await ctx.audit.resumen())}\n`);
  for (const e of eventos.reverse()) {
    const partes = [e.t, e.tipo.padEnd(16), (e.cliente ?? '').padEnd(14), e.herramienta ?? ''];
    if (e.ok === false || e.error) partes.push(`FALLO: ${e.error ?? ''}`);
    if (e.detalle) partes.push(`(${e.detalle})`);
    console.log(partes.join('  '));
  }
}

async function cmdBackup(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const destino = flag('destino');
  const conservar = Number(flag('conservar') ?? 7);
  const r = await crearBackup(ctx, { destino, conservar });
  console.log(`Respaldo creado: ${r.ruta}`);
  console.log(`  fecha:    ${r.fecha}`);
  console.log(`  archivos: ${r.archivos.length} (${(r.bytes_total / 1024).toFixed(1)} KB)`);
  console.log(`  conteos:  ${JSON.stringify(r.conteos)}`);
  console.log(`  política: conservar ${conservar} · respaldos actuales ${r.respaldos_conservados}`);
  for (const a of r.archivos) console.log(`    ${a.nombre.padEnd(30)} ${String(a.bytes).padStart(8)} bytes  sha256:${a.sha256.slice(0, 12)}…`);
  if (r.eliminados.length) console.log(`  eliminados por rotación: ${r.eliminados.join(', ')}`);
  console.log(`\n  Verificación y restauración:`);
  console.log(`    cat ${r.ruta}/manifest.json`);
  console.log(`    # detén el servidor y copia ${r.ruta}/suite.db sobre data/suite.db`);
}

async function cmdBackupsList(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const lista = await listarBackups(ctx, flag('destino'));
  if (lista.length === 0) {
    console.log('No hay respaldos. Crea uno con: node dist/cli.js backup');
    return;
  }
  console.log(`${lista.length} respaldo(s):\n`);
  for (const b of lista) console.log(`  ${b.fecha}  ${b.ruta}`);
}

async function cmdAprobaciones(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const vencidas = expirarVencidas(ctx);
  const lista = listarAprobaciones(ctx, { limite: 30 });
  if (lista.length === 0) {
    console.log('No hay aprobaciones registradas.\nLas operaciones destructivas (aplicar un parche, desplegar) piden aprobación y aquí aparecen para que las apruebes o rechaces.');
    return;
  }
  const icono: Record<string, string> = { pendiente: '⏳', aprobada: '✅', rechazada: '⛔', expirada: '⌛' };
  console.log(`${lista.length} aprobación(es)${vencidas ? ` · ${vencidas} expirada(s) ahora` : ''}:\n`);
  for (const a of lista) {
    console.log(`  ${icono[a.estado]} ${a.id}  [${a.estado}]  ${a.tipo}`);
    console.log(`      ${a.titulo}`);
    console.log(`      pedida por ${a.solicitada_por} el ${a.solicitada_en}${a.resuelta_en ? ` · resuelta ${a.resuelta_en} por ${a.resuelta_por}` : ''}${a.motivo ? ` · ${a.motivo}` : ''}`);
    if (a.detalle) console.log(`      detalle: ${a.detalle.slice(0, 160)}`);
  }
  console.log('\nPara resolver:  node dist/cli.js aprobar <id>   |   node dist/cli.js rechazar <id> --motivo "..."');
}

async function cmdResolverAprobacion(estado: 'aprobada' | 'rechazada'): Promise<void> {
  const id = subcomando;
  if (!id) throw new Error(`Uso: node dist/cli.js ${estado === 'aprobada' ? 'aprobar' : 'rechazar'} <id> [--motivo "…"] [--por "nombre"]`);
  const ctx = await crearContexto(loadConfig());
  const r = await resolverAprobacion(ctx, id, estado, { motivo: flag('motivo'), por: flag('por') ?? 'cli' });
  console.log(`${estado === 'aprobada' ? '✅ Aprobada' : '⛔ Rechazada'}: ${r.id} · ${r.titulo}`);
  console.log(`   Quedó registrado en la auditoría (${ctx.audit.ruta}).`);
}

async function cmdSeguridad(): Promise<void> {
  const config = loadConfig();
  const directorio = path.resolve(flag('directorio') ?? config.allowedGitRoots[0] ?? process.cwd());
  const escaneo = await escanear(directorio);
  const minimo = Number(flag('minimo') ?? 0);
  if (bool('plan')) {
    console.log(planMarkdown(planDeCorreccion(escaneo), escaneo));
  } else {
    console.log(JSON.stringify({
      proyecto: escaneo.proyecto,
      raiz: escaneo.raiz,
      puntaje: escaneo.puntaje,
      semaforo: escaneo.semaforo,
      archivos_analizados: escaneo.archivos_analizados,
      resumen: escaneo.resumen,
      hallazgos: escaneo.hallazgos.map((h) => ({ regla: h.regla, severidad: h.severidad, donde: `${h.archivo}:${h.linea}`, evidencia: h.evidencia })),
      notas: escaneo.notas,
    }, null, 2));
  }
  if (minimo && escaneo.puntaje < minimo) {
    console.error(`\nPuntaje ${escaneo.puntaje} por debajo del mínimo exigido (${minimo}).`);
    process.exit(1);
  }
}

async function cmdCheck(): Promise<void> {
  const config = loadConfig();
  const ctx = await crearContexto(config);
  const respaldos = await listarBackups(ctx);
  console.log(JSON.stringify({
    version: '0.1.0',
    transporte: config.transport,
    tls: config.tlsCert && config.tlsKey ? { certificado: config.tlsCert, llave: config.tlsKey } : 'deshabilitado',
    directorio_datos: config.dataDir,
    auth_mode: config.authMode,
    claves_bootstrap: config.apiKeys.length,
    claves_emitidas_activas: await contarClavesActivas(ctx).catch(() => 0),
    rate_limit_rpm: config.rateLimitRpm,
    rate_limit_burst: config.rateLimitBurst,
    bitacora: ctx.audit.ruta,
    respaldos: { total: respaldos.length, mas_reciente: respaldos[0]?.fecha ?? null },
    iva_pct: config.ivaPct,
    emisor_cfdi: config.cfdiEmisorRfc ? { rfc: config.cfdiEmisorRfc, nombre: config.cfdiEmisorNombre, regimen: config.cfdiEmisorRegimen, cp: config.cfdiEmisorCp } : 'no configurado (pásalo en cada llamada o define CFDI_EMISOR_*)',
    repositorios_autorizados: config.allowedGitRoots,
  }, null, 2));
}

const ICONO_ESTADO: Record<string, string> = {
  propuesto: '·', probado: '🧪', aplicado: '✅', revertido: '↩️', descartado: '✖️',
};

async function cmdParches(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const estado = flag('estado') as 'propuesto' | 'probado' | 'aplicado' | 'revertido' | undefined;
  const lista = listarParches(ctx.db, { limite: Number(flag('limite') ?? 20), estado });
  if (!lista.length) {
    console.log('No hay parches registrados todavía. Propón uno con la herramienta MCP `parche_proponer`.');
    return;
  }
  console.log(`Parches (${lista.length}):\n`);
  for (const p of lista) {
    console.log(`  ${ICONO_ESTADO[p.estado] ?? '?'} ${p.id}  [${p.estado}]  veredicto: ${p.prueba?.veredicto ?? 'sin probar'}  intentos: ${p.intentos}`);
    console.log(`      ${p.titulo}  ·  ${[...new Set(p.cambios.map((c) => c.ruta))].join(', ')}  ·  huella ${p.huella}`);
    if (p.aplicado_en) console.log(`      aplicado ${p.aplicado_en}${p.respaldo ? ` · respaldo ${p.respaldo}` : ''}`);
    if (p.revertido_en) console.log(`      revertido ${p.revertido_en}`);
  }
  if (lista.some((p) => p.estado === 'aplicado')) console.log('\nPara deshacer:  node dist/cli.js revertir <PR-id>');
}

async function cmdRevertir(): Promise<void> {
  const id = subcomando;
  if (!id) throw new Error('Uso: node dist/cli.js revertir <PR-id>');
  const ctx = await crearContexto(loadConfig());
  const parche = obtenerParche(ctx.db, id);
  if (!parche) throw new Error(`No encontré el parche ${id}. Lista con: node dist/cli.js parches`);
  const { archivos_restaurados } = await deshacerParche(ctx, parche, { herramienta: 'cli_revertir' });
  console.log(`↩️  Revertido: ${parche.id} · ${parche.titulo}`);
  console.log(`   archivos restaurados: ${archivos_restaurados.join(', ')}`);
  console.log('   (deshacer nunca pide aprobación: es la salida de seguridad)');
}

async function cmdFlujos(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const propios = await leerFlujosPropios(ctx);
  console.log('Flujos disponibles:\n');
  for (const f of [...FLUJOS_PREDEFINIDOS, ...propios]) {
    console.log(`  · ${f.id}${propios.some((x) => x.id === f.id) ? ' (propio)' : ''} — ${f.nombre}`);
    console.log(`      ${f.descripcion}`);
  }
  const corridas = await ultimasCorridas(ctx, 5);
  if (corridas.length) {
    console.log('\nÚltimas corridas:');
    for (const c of corridas) {
      console.log(`  ${c.ok ? '✅' : '⚠️'} ${c.flujo}  ${c.inicio.replace('T', ' ').slice(0, 19)}  ${c.duracion_ms} ms${c.pasos_fallidos.length ? `  fallaron: ${c.pasos_fallidos.join(', ')}` : ''}`);
    }
  }
  console.log('\nPara ejecutar:  herramienta MCP `flujo_ejecutar` (con --simular para no tocar nada).');
}

async function cmdModelos(): Promise<void> {
  const ctx = await crearContexto(loadConfig());
  const catalogo = await leerCatalogo(ctx);
  const comparativa = compararCostes(ctx, flag('tipo'));
  console.log(`Catálogo de modelos (${catalogo.length}):\n`);
  for (const m of catalogo) {
    console.log(`  · ${m.id.padEnd(14)} fuerza ${m.fuerza} · coste relativo ${m.coste_relativo} · ${m.donde} · latencia ${m.latencia}${m.nota ? ` · ${m.nota}` : ''}`);
  }
  if (comparativa.por_modelo.length) {
    console.log('\nCoste por resultado útil (lo que de verdad importa):');
    for (const m of comparativa.por_modelo) {
      console.log(`  · ${m.modelo.padEnd(14)} ${m.exitos}/${m.tareas} éxitos · coste total ${m.coste_total} · por éxito ${m.coste_por_exito ?? '—'}`);
    }
    console.log(`\n${comparativa.recomendacion}`);
  } else {
    console.log('\nAún no hay resultados registrados: la herramienta MCP `modelo_resultado` los va llenando.');
  }
}

async function main(): Promise<void> {
  setLogLevel('warn'); // En CLI solo queremos el resultado en stdout.
  switch (comando) {
    case 'keys':
      if (subcomando === 'create') return cmdKeysCreate();
      if (subcomando === 'list') return cmdKeysList();
      if (subcomando === 'revoke') return cmdKeysRevoke();
      console.error('Subcomando desconocido. Uso: keys create|list|revoke');
      process.exit(2);
      break;
    case 'audit':
      return cmdAudit();
    case 'backup':
      if (subcomando === 'list') return cmdBackupsList();
      return cmdBackup();
    case 'aprobaciones':
      return cmdAprobaciones();
    case 'aprobar':
      return cmdResolverAprobacion('aprobada');
    case 'rechazar':
      return cmdResolverAprobacion('rechazada');
    case 'parches':
      return cmdParches();
    case 'revertir':
      return cmdRevertir();
    case 'flujos':
      return cmdFlujos();
    case 'modelos':
      return cmdModelos();
    case 'seguridad':
      return cmdSeguridad();
    case 'check':
      return cmdCheck();
    default:
      console.log(
        [
          'Suite MCP MX — CLI de administración',
          '',
          '  keys create --nombre <n> [--scopes negocio,analisis] [--expira-dias 30]',
          '  keys list',
          '  keys revoke --id <id> | --nombre <n>',
          '  audit [--tipo tool_destructiva] [--herramienta inventario_actualizar] [--limite 20]',
          '  backup [--destino <dir>] [--conservar 7]',
          '  backup list',
          '  aprobaciones                        (gate de aprobación: lista pendientes)',
          '  aprobar <id> [--motivo "…"] [--por "nombre"]',
          '  rechazar <id> [--motivo "…"]',
          '  parches [--estado aplicado|propuesto|probado|revertido] [--limite 20]',
          '  revertir <PR-id>',
          '  flujos',
          '  modelos [--tipo refactor]',
          '  seguridad [--directorio <dir>] [--minimo 80] [--plan]',
          '  check',
          '',
          `  scopes válidos: ${SCOPES_VALIDOS.join(', ')}`,
        ].join('\n'),
      );
  }
}

main().catch((err) => {
  console.error(`Error: ${err?.message ?? String(err)}`);
  process.exit(1);
});
