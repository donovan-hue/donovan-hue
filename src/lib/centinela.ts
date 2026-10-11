/**
 * CENTINELA (fase 4B) — seguridad preventiva con plan de corrección VERIFICABLE.
 *
 * Escanea un proyecto de código en busca de secretos expuestos, configuraciones
 * peligrosas, rutas sin protección, dependencias riesgosas e higiene de repositorio.
 * Todo local: no envía código ni hallazgos a ningún servicio.
 *
 * El diferenciador no es listar problemas: es que cada hallazgo se convierte en
 * un plan de corrección concreto y que `verificar()` compara contra una línea
 * base y demuestra —o no— que quedó resuelto.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { DIRS_IGNORADOS } from './grafo.js';

export type Severidad = 'critica' | 'alta' | 'media' | 'baja' | 'info';
export type Categoria = 'secreto' | 'config' | 'rutas' | 'dependencia' | 'higiene';

export interface Hallazgo {
  /** Clave estable para comparar entre escaneos (regla + archivo + línea). */
  clave: string;
  regla: string;
  categoria: Categoria;
  severidad: Severidad;
  archivo: string;
  linea: number;
  evidencia: string;
  recomendacion: string;
}

export interface Escaneo {
  raiz: string;
  proyecto: string;
  generado_en: string;
  archivos_analizados: number;
  puntaje: number;
  semaforo: 'verde' | 'amarillo' | 'rojo';
  resumen: Record<Severidad, number>;
  hallazgos: Hallazgo[];
  ignorados: { regla: string; archivo: string; linea: number; motivo: string }[];
  notas: string[];
}

const ARCHIVOS_IGNORADOS = new Set([
  'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock', 'bun.lockb',
]);

const ES_CODIGO = /\.(ts|tsx|js|jsx|mjs|cjs|mts|cts)$/;
const ES_CONFIG = /\.(json|ya?ml|toml|ini|env|example|properties|conf)$/;

async function listarArchivos(raiz: string): Promise<string[]> {
  const salida: string[] = [];
  async function recorrer(dir: string): Promise<void> {
    let entradas;
    try {
      entradas = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entradas) {
      if (e.isDirectory()) {
        if (DIRS_IGNORADOS.has(e.name) || (e.name.startsWith('.') && e.name !== '.github' && e.name !== '.env')) continue;
        await recorrer(path.join(dir, e.name));
      } else if (e.isFile()) {
        if (ARCHIVOS_IGNORADOS.has(e.name)) continue;
        salida.push(path.join(dir, e.name));
      }
    }
  }
  await recorrer(raiz);
  return salida;
}

/* ─────────────────────────── Reglas ─────────────────────────── */

interface ReglaTexto {
  id: string;
  categoria: Categoria;
  severidad: Severidad;
  descripcion: string;
  recomendacion: string;
  patron: RegExp;
  /** Solo se aplica a estos nombres/extensiones (si se define). */
  aplica?: (rel: string) => boolean;
  /** Filtro final sobre la coincidencia capturada. */
  valido?: (m: RegExpExecArray, linea: string) => boolean;
}

const PLACEHOLDERS = /(example|ejemplo|placeholder|cambiar|change_?me|tu[_-]?clave|your[_-]|xxx+|\.\.\.|dummy|fake|sample|redacted|process\.env|import\.meta\.env|\$\{)/i;

const REGLAS_TEXTO: ReglaTexto[] = [
  {
    id: 'SEC001', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Llave de acceso de AWS en el código',
    recomendacion: 'Revoca la llave en IAM y muévela a variables de entorno o un gestor de secretos. Rota cualquier llave expuesta: asume que ya está comprometida.',
    patron: /AKIA[0-9A-Z]{16}/g,
  },
  {
    id: 'SEC002', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Llave de API de Google',
    recomendacion: 'Revócala en Google Cloud Console, restringe por origen/IP y muévela a variables de entorno.',
    patron: /AIza[0-9A-Za-z\-_]{35}/g,
  },
  {
    id: 'SEC003', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Llave secreta de Stripe en producción',
    recomendacion: 'Rótala en el panel de Stripe de inmediato y usa claves de prueba (sk_test_) solo en desarrollo.',
    patron: /sk_live_[0-9a-zA-Z]{20,}/g,
  },
  {
    id: 'SEC004', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Llave de API de un proveedor de IA (OpenAI/Anthropic/…)',
    recomendacion: 'Revócala en el panel del proveedor y cárgala por variable de entorno. Revisa el consumo facturado por si hubo uso ajeno.',
    patron: /\bsk-(?:ant-|proj-|live-)?[A-Za-z0-9_-]{24,}/g,
    valido: (_m, linea) => !PLACEHOLDERS.test(linea),
  },
  {
    id: 'SEC005', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Token de GitHub',
    recomendacion: 'Revócalo en GitHub → Settings → Developer settings y genera uno nuevo con el mínimo alcance.',
    patron: /\bgh[pousr]_[A-Za-z0-9]{36,}/g,
  },
  {
    id: 'SEC006', categoria: 'secreto', severidad: 'alta',
    descripcion: 'Token de Slack',
    recomendacion: 'Revócalo en la app de Slack y rota el webhook si aplica.',
    patron: /xox[baprs]-[A-Za-z0-9-]{10,}/g,
  },
  {
    id: 'SEC007', categoria: 'secreto', severidad: 'critica',
    descripcion: 'Llave privada en el repositorio',
    recomendacion: 'Retira el archivo del repositorio (y de su historial), genera un par nuevo y guarda la llave fuera del control de versiones con permisos 600.',
    patron: /-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----/g,
  },
  {
    id: 'SEC008', categoria: 'secreto', severidad: 'alta',
    descripcion: 'Contraseña o token escrito directamente en el código',
    recomendacion: 'Muévelo a una variable de entorno (o gestor de secretos). Si el repo es compartido, considera el valor comprometido y cámbialo.',
    patron: /\b(?:contrase(?:n|ñ)a|password|passwd|pwd|api_?key|secret|token)\s*[:=]\s*['"`]([^'"`\n]{8,})['"`]/gi,
    valido: (m, linea) => !PLACEHOLDERS.test(linea) && !/^\s*(?:\/\/|\/\*|\*)/.test(linea) && !/^\s*['"]?\s*(?:hash|sha|md5)/i.test(m[1] ?? ''),
  },
  {
    id: 'SEC009', categoria: 'secreto', severidad: 'media',
    descripcion: 'Cadena de alta entropía que parece un secreto',
    recomendacion: 'Si es un secreto: rótalo y cárgalo por variable de entorno. Si es un dato legítimo (por ejemplo un hash de prueba), agrégalo a .centinela-ignore con su razón.',
    patron: /['"`]([A-Za-z0-9+/_=-]{28,})['"`]/g,
    aplica: (rel) => ES_CODIGO.test(rel) && !/\.test\.[cm]?[jt]sx?$/.test(rel),
    valido: (m) => {
      const s = m[1] ?? '';
      if (!/[A-Z]/.test(s) || !/[a-z]/.test(s) || !/[0-9]/.test(s)) return false;
      if (/^[a-z0-9-]+$/i.test(s.replace(/[+/_=-]/g, ''))) return false;
      const set = new Map<string, number>();
      for (const ch of s) set.set(ch, (set.get(ch) ?? 0) + 1);
      let h = 0;
      for (const n of set.values()) { const p = n / s.length; h -= p * Math.log2(p); }
      return h >= 4.1;
    },
  },
  {
    id: 'CFG006', categoria: 'config', severidad: 'alta',
    descripcion: 'Ejecución dinámica de código (eval / new Function)',
    recomendacion: 'Sustituye la ejecución dinámica por lógica explícita. Si es inevitable, valida la entrada contra una lista blanca estricta.',
    patron: /\b(?:eval|new\s+Function)\s*\(/g,
    valido: (_m, linea) => !/eslint-disable|no-eval|comentario/.test(linea),
  },
  {
    id: 'CFG007', categoria: 'config', severidad: 'alta',
    descripcion: 'Consulta SQL construida por concatenación o plantilla',
    recomendacion: 'Usa consultas parametrizadas (sentencias preparadas con ? o $1). Es la corrección definitiva contra inyección SQL.',
    patron: /(?:query|execute|raw)\s*\(\s*(?:`[^`]*\$\{[^`]*`|['"][^'"]*['"]{1,2}\s*\+)/gi,
  },
  {
    id: 'CFG009', categoria: 'config', severidad: 'media',
    descripcion: 'Verificación de certificado TLS desactivada',
    recomendacion: 'Activa la verificación (rejectUnauthorized: true). Si es por un certificado propio, agrega la CA correcta en lugar de desactivar la comprobación.',
    patron: /rejectUnauthorized\s*:\s*false|NODE_TLS_REJECT_UNAUTHORIZED\s*=\s*['"]?0/g,
  },
  {
    id: 'CFG010', categoria: 'config', severidad: 'media',
    descripcion: 'Permisos de archivo excesivos (777)',
    recomendacion: 'Aplica el principio de menor privilegio: 600 para secretos, 644 para archivos normales, 755 para ejecutables.',
    patron: /chmod\s+777|0o?777/g,
  },
  {
    id: 'CFG011', categoria: 'config', severidad: 'alta',
    descripcion: 'Credenciales de administrador por defecto',
    recomendacion: 'Elimina las credenciales por defecto y obliga a definirlas por entorno; si ya se usaron en producción, cámbialas.',
    patron: /\b(?:admin|root|usuario)\s*[:=]\s*['"`](?:admin|root|123456|password|contrase\w*)['"`]/gi,
  },
  {
    id: 'CFG012', categoria: 'config', severidad: 'alta',
    descripcion: 'Script de instalación que descarga y ejecuta código de internet',
    recomendacion: 'No ejecutes `curl | sh` en postinstall. Descarga verificando hash/firma o usa un paquete versionado y auditado.',
    patron: /(?:curl|wget)\s[^|;&\n]*https?:\/\/[^|;&\n]*\|\s*(?:sudo\s+)?(?:sh|bash|zsh)\b/g,
    valido: (_m, linea) => !/^\s*(?:#|\/\/|\*)/.test(linea),
  },
  {
    id: 'RUT001', categoria: 'rutas', severidad: 'media',
    descripcion: 'Archivo con rutas que no menciona autenticación',
    recomendacion: 'Verifica que cada ruta esté protegida. Si usa middleware global, documéntalo; si no, agrega el guard correspondiente o marca la ruta como pública a propósito.',
    patron: /(?:app|router|servidor|rutas?|routes?)\s*\.\s*(?:get|post|put|patch|delete|use)\s*\(\s*['"`][^'"`]+['"`]/g,
    aplica: (rel) => ES_CODIGO.test(rel) && !/\.test\.[cm]?[jt]sx?$/.test(rel),
  },
];

/** Tabla local de avisos: versiones con vulnerabilidad conocida (revisión propia, sin red). */
export const AVISOS_DEPENDENCIAS: { paquete: string; afectado: string; corrige: string; nota: string }[] = [
  { paquete: 'lodash', afectado: '<4.17.21', corrige: '4.17.21', nota: 'Prototype pollution (CVE-2021-23337 y otros).' },
  { paquete: 'minimist', afectado: '<1.2.6', corrige: '1.2.6', nota: 'Contaminación de prototipo vía argumentos.' },
  { paquete: 'axios', afectado: '<1.6.0', corrige: '1.6.0', nota: 'SSRF y filtrado de credenciales en redirecciones.' },
  { paquete: 'jsonwebtoken', afectado: '<9.0.0', corrige: '9.0.0', nota: 'Verificación de firma evitable en versiones antiguas.' },
  { paquete: 'node-fetch', afectado: '<2.6.7', corrige: '2.6.7', nota: 'Fuga de cookies hacia terceros en redirecciones.' },
  { paquete: 'moment', afectado: '<2.29.4', corrige: '2.29.4', nota: 'Path traversal y ReDoS.' },
  { paquete: 'qs', afectado: '<6.11.1', corrige: '6.11.1', nota: 'DoSS por crecimiento de arreglos anidados.' },
  { paquete: 'tar', afectado: '<6.1.9', corrige: '6.1.9', nota: 'Escritura fuera de destino al extraer archivos.' },
  { paquete: 'ws', afectado: '<8.17.1', corrige: '8.17.1', nota: 'Denegación de servicio por frames preparados.' },
  { paquete: 'semver', afectado: '<7.5.2', corrige: '7.5.2', nota: 'ReDoS en versiónes con expresiones largas.' },
  { paquete: 'express', afectado: '<4.19.2', corrige: '4.19.2', nota: 'Open redirect y otros arreglos de seguridad.' },
  { paquete: 'braces', afectado: '<3.0.3', corrige: '3.0.3', nota: 'ReDoS por expansión de llaves.' },
  { paquete: 'undici', afectado: '<5.28.4', corrige: '5.28.4', nota: 'Filtrado de cabeceras en redirecciones.' },
  { paquete: 'esbuild', afectado: '<0.25.0', corrige: '0.25.0', nota: 'El servidor de desarrollo permitía peticiones arbitrarias.' },
];

/** Extrae el número de versión mínimo permitido de un rango semver simple. */
function versionMinima(rango: string): number[] | null {
  const m = rango.match(/\d+(?:\.\d+){0,2}/);
  if (!m) return null;
  const partes = m[0].split('.').map(Number);
  while (partes.length < 3) partes.push(0);
  return partes;
}

function compararVersiones(a: number[], b: number[]): number {
  for (let i = 0; i < 3; i++) if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) - (b[i] ?? 0);
  return 0;
}

/* ─────────────────────── Archivo de exclusiones ─────────────────────── */

interface Exclusion { regla?: string; ruta?: string; motivo: string }

async function leerExclusiones(raiz: string): Promise<Exclusion[]> {
  const salida: Exclusion[] = [];
  for (const nombre of ['.centinela-ignore', '.centinela-ignorar']) {
    try {
      const texto = await fs.readFile(path.join(raiz, nombre), 'utf8');
      for (const cruda of texto.split('\n')) {
        const linea = cruda.trim();
        if (!linea || linea.startsWith('#')) continue;
        // Formatos:  REGLA  |  REGLA:ruta/glob  |  ruta/glob   # motivo
        const [cuerpo, motivo = 'excluido por .centinela-ignore'] = linea.split('#').map((s) => s.trim());
        if (!cuerpo) continue;
        const [a, b] = cuerpo.split(':').map((s) => s.trim());
        if (b) salida.push({ regla: a, ruta: b, motivo });
        else if (/^\d*[A-Z]{3}\d+|^[A-Z]{3}\d+$/.test(a)) salida.push({ regla: a, motivo });
        else salida.push({ ruta: a, motivo });
      }
    } catch {
      /* sin archivo de exclusiones */
    }
  }
  return salida;
}

function globCoincide(glob: string, rel: string): boolean {
  const patron = glob
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*/g, '\u0000')
    .replace(/\*/g, '[^/]*')
    .replace(/\u0000/g, '.*');
  return new RegExp(`^${patron}$`).test(rel);
}

function excluido(exclusiones: Exclusion[], regla: string, rel: string): string | null {
  for (const e of exclusiones) {
    const reglaOk = !e.regla || e.regla === regla;
    const rutaOk = !e.ruta || globCoincide(e.ruta, rel);
    if (reglaOk && rutaOk) return e.motivo;
  }
  return null;
}

/* ─────────────────────────── Escaneo ─────────────────────────── */

const PESO: Record<Severidad, number> = { critica: 22, alta: 12, media: 5, baja: 2, info: 0 };

export async function escanear(raiz: string): Promise<Escaneo> {
  const archivos = await listarArchivos(raiz);
  const exclusiones = await leerExclusiones(raiz);
  const hallazgos: Hallazgo[] = [];
  const ignorados: Escaneo['ignorados'] = [];
  const notas: string[] = [];
  let analizados = 0;

  const agregar = (h: Omit<Hallazgo, 'clave'>) => {
    const motivo = excluido(exclusiones, h.regla, h.archivo);
    if (motivo) {
      ignorados.push({ regla: h.regla, archivo: h.archivo, linea: h.linea, motivo });
      return;
    }
    hallazgos.push({
      ...h,
      clave: `${h.regla}|${h.archivo}|${h.linea}`,
      // Solo se redactan las reglas que capturan material sensible; la evidencia
      // de una configuración o de un aviso de dependencia debe leerse completa.
      evidencia: ES_SECRETO.test(h.regla) ? redactar(h.evidencia) : h.evidencia,
    });
  };

  for (const absoluto of archivos) {
    const rel = absoluto.slice(raiz.length + 1).split(path.sep).join('/');
    const base = path.basename(rel);
    const analizar = ES_CODIGO.test(rel) || ES_CONFIG.test(rel) || base === 'package.json' || base.startsWith('.env') || base.endsWith('.sh') || base === 'Dockerfile';
    if (!analizar) continue;
    let contenido: string;
    try {
      const st = await fs.stat(absoluto);
      if (st.size > 2_000_000) continue;
      contenido = await fs.readFile(absoluto, 'utf8');
    } catch {
      continue;
    }
    analizados++;
    const lineas = contenido.split('\n');

    for (const regla of REGLAS_TEXTO) {
      if (regla.aplica && !regla.aplica(rel)) continue;
      for (let i = 0; i < lineas.length; i++) {
        const linea = lineas[i]!;
        if (linea.length > 1000) continue;
        const re = new RegExp(regla.patron.source, regla.patron.flags);
        let m: RegExpExecArray | null;
        while ((m = re.exec(linea)) !== null) {
          if (regla.valido && !regla.valido(m, linea)) break;
          // RUT001 es un hallazgo por archivo, no por ruta: se resume después.
          if (regla.id === 'RUT001') break;
          agregar({
            regla: regla.id, categoria: regla.categoria, severidad: regla.severidad,
            archivo: rel, linea: i + 1,
            evidencia: m[0].slice(0, 90),
            recomendacion: regla.recomendacion,
          });
          break; // una coincidencia por línea basta
        }
      }
    }

    // RUT001 a nivel archivo: rutas y ninguna noción de autenticación.
    if (ES_CODIGO.test(rel) && !/\.test\.[cm]?[jt]sx?$/.test(rel)) {
      const rutas = contenido.match(/(?:app|router|servidor|rutas?|routes?)\s*\.\s*(?:get|post|put|patch|delete|use)\s*\(\s*['"`]([^'"`]+)['"`]/g);
      const mencionaAuth = /\b(?:auth|autoriz|autentic|bearer|token|permiso|scope|guard|sesion|sesión|login|clave)\b/i.test(contenido);
      if (rutas && rutas.length >= 2 && !mencionaAuth) {
        const primeraLinea = lineas.findIndex((l) => /(?:app|router|servidor|rutas?|routes?)\s*\.\s*(?:get|post|put|patch|delete|use)\s*\(/.test(l));
        agregar({
          regla: 'RUT001', categoria: 'rutas', severidad: 'media',
          archivo: rel, linea: (primeraLinea >= 0 ? primeraLinea : 0) + 1,
          evidencia: `${rutas.length} rutas definidas (ej. ${rutas[0]!.replace(/\s+/g, ' ').slice(0, 60)})`,
          recomendacion: REGLAS_TEXTO.find((r) => r.id === 'RUT001')!.recomendacion,
        });
      }
    }

    // ── Higiene: archivos sensibles presentes en el repositorio ──
    if (/^\.env(?:\.|$)/.test(base) && base !== '.env.example' && !base.endsWith('.example')) {
      const gitignore = await fs.readFile(path.join(raiz, '.gitignore'), 'utf8').catch(() => '');
      const cubierto = /^\.env/m.test(gitignore) || /\.env/.test(gitignore);
      agregar({
        regla: cubierto ? 'HIG002' : 'HIG001', categoria: 'higiene',
        severidad: cubierto ? 'media' : 'alta',
        archivo: rel, linea: 1,
        evidencia: `archivo ${base} presente ${cubierto ? '(cubierto por .gitignore)' : 'SIN cobertura en .gitignore'}`,
        recomendacion: cubierto
          ? 'Correcto tenerlo en .gitignore; revisa que nunca se haya versionado (git log --all -- .env).'
          : 'Agrega .env (y variantes) a .gitignore y, si ya se versionó, rota los secretos que contenga y limpia el historial.',
      });
    }
    if (/(^|\/)\.gitignore$/.test(rel)) {
      const cubreEnv = /^\.env/m.test(contenido) || /\.env/.test(contenido);
      const cubreLlaves = /\.(pem|key|p12|pfx)\b/.test(contenido) || /\*\.key/.test(contenido);
      if (!cubreEnv || !cubreLlaves) {
        agregar({
          regla: 'HIG003', categoria: 'higiene', severidad: 'baja',
          archivo: rel, linea: 1,
          evidencia: `.gitignore no cubre ${[!cubreEnv ? '.env' : null, !cubreLlaves ? 'llaves/certificados (*.pem, *.key)' : null].filter(Boolean).join(' ni ')}`,
          recomendacion: 'Agrega .env*, *.pem y *.key al .gitignore para evitar publicar credenciales por descuido.',
        });
      }
    }
  }

  // ── package.json: dependencias y scripts ──
  let paquete: any;
  try {
    paquete = JSON.parse(await fs.readFile(path.join(raiz, 'package.json'), 'utf8'));
  } catch {
    paquete = undefined;
  }
  if (paquete) {
    const todas: Record<string, string> = { ...(paquete.dependencies ?? {}), ...(paquete.devDependencies ?? {}) };
    for (const [nombre, rango] of Object.entries(todas)) {
      if (typeof rango !== 'string') continue;
      if (rango === '*' || rango === 'latest' || rango === '') {
        agregar({
          regla: 'DEP002', categoria: 'dependencia', severidad: 'baja',
          archivo: 'package.json', linea: 1,
          evidencia: `"${nombre}": "${rango}"`,
          recomendacion: 'Fija un rango semver (por ejemplo ^1.2.3) y usa lockfile para tener instalaciones reproducibles.',
        });
      }
      const aviso = AVISOS_DEPENDENCIAS.find((a) => a.paquete === nombre);
      const version = versionMinima(rango);
      if (aviso && version) {
        const corregida = aviso.corrige.split('.').map(Number);
        const afectadoMax = aviso.afectado.replace(/[<>=^~\s]/g, '').split('.').map(Number);
        while (afectadoMax.length < 3) afectadoMax.push(0);
        if (compararVersiones(version, afectadoMax) < 0) {
          agregar({
            regla: 'DEP001', categoria: 'dependencia', severidad: 'alta',
            archivo: 'package.json', linea: 1,
            evidencia: `"${nombre}": "${rango}" — ${aviso.nota}`,
            recomendacion: `Actualiza a ${nombre} >= ${aviso.corrige} y revisa la guía de migración. Después corre tus pruebas completas.`,
          });
        }
      }
    }
    for (const [nombre, comando] of Object.entries<string>(paquete.scripts ?? {})) {
      if (/(?:curl|wget)[^|;&]*\|\s*(?:sudo\s+)?(?:sh|bash|zsh)\b/.test(comando ?? '')) {
        agregar({
          regla: 'CFG012', categoria: 'config', severidad: 'alta',
          archivo: 'package.json', linea: 1,
          evidencia: `script "${nombre}": ${String(comando).slice(0, 80)}`,
          recomendacion: 'Ejecutar código descargado sin verificar permite un compromiso total de la máquina. Sustitúyelo por una dependencia versionada.',
        });
      }
    }
    const existeLock = await fs.access(path.join(raiz, 'package-lock.json')).then(() => true).catch(() => false);
    if (!existeLock && Object.keys(todas).length > 0) {
      notas.push('No hay package-lock.json: las instalaciones no son reproducibles (DEP003).');
    }
  }

  // ── Puntaje y semáforo ──
  const resumen: Record<Severidad, number> = { critica: 0, alta: 0, media: 0, baja: 0, info: 0 };
  for (const h of hallazgos) resumen[h.severidad]++;
  const descuento = hallazgos.reduce((acc, h) => acc + PESO[h.severidad], 0);
  const puntaje = Math.max(0, Math.min(100, 100 - descuento));
  const semaforo: Escaneo['semaforo'] = resumen.critica > 0 ? 'rojo' : puntaje >= 80 ? 'verde' : 'amarillo';

  if (hallazgos.length === 0) notas.push('Sin hallazgos: el proyecto cumple las revisiones de CENTINELA.');

  return {
    raiz,
    proyecto: paquete?.name ?? path.basename(raiz),
    generado_en: new Date().toISOString(),
    archivos_analizados: analizados,
    puntaje,
    semaforo,
    resumen,
    hallazgos: hallazgos.sort(
      (a, b) => PESO[b.severidad] - PESO[a.severidad] || a.archivo.localeCompare(b.archivo) || a.linea - b.linea,
    ),
    ignorados,
    notas,
  };
}

/** Reglas cuyo hallazgo captura material sensible (lo único que se redacta). */
const ES_SECRETO = /^SEC0\d\d$/;

/** Oculta el valor de un posible secreto dejando solo pistas para ubicarlo. */
function redactar(valor: string): string {
  const sinEspacios = valor.trim();
  if (sinEspacios.length <= 12) return sinEspacios.replace(/(.{3}).*(.{2})/, '$1…$2');
  const cabeza = sinEspacios.slice(0, Math.min(10, Math.floor(sinEspacios.length / 4)));
  const cola = sinEspacios.slice(-4);
  return `${cabeza}…${cola} (redactado, ${sinEspacios.length} caracteres)`;
}

/* ─────────────────────── Verificación contra línea base ─────────────────────── */

export interface Verificacion {
  raiz: string;
  contra: string;
  antes: { puntaje: number; semaforo: Escaneo['semaforo']; hallazgos: number };
  despues: { puntaje: number; semaforo: Escaneo['semaforo']; hallazgos: number };
  resueltos: Hallazgo[];
  nuevos: Hallazgo[];
  permanecen: Hallazgo[];
  porcentaje_resuelto: number;
  veredicto: 'mejoro' | 'igual' | 'empeoro';
  mensaje: string;
}

export function verificarContra(actual: Escaneo, previo: Escaneo): Verificacion {
  const antes = new Map(previo.hallazgos.map((h) => [h.clave, h]));
  const ahora = new Map(actual.hallazgos.map((h) => [h.clave, h]));
  const resueltos = previo.hallazgos.filter((h) => !ahora.has(h.clave));
  const nuevos = actual.hallazgos.filter((h) => !antes.has(h.clave));
  const permanecen = actual.hallazgos.filter((h) => antes.has(h.clave));
  const totalPrevios = previo.hallazgos.length;
  const porcentaje = totalPrevios === 0 ? 100 : Math.round((resueltos.length / totalPrevios) * 100);
  // El puntaje tiene piso 0: en proyectos muy dañados un arreglo real no lo mueve.
  // Por eso el veredicto también mira lo que se resolvió, lo que nació y las críticas.
  const criticasNuevas = nuevos.filter((h) => h.severidad === 'critica' && !resueltos.some((r) => r.severidad === 'critica'));
  const veredicto: Verificacion['veredicto'] =
    actual.puntaje < previo.puntaje
      ? 'empeoro'
      : actual.puntaje > previo.puntaje
        ? 'mejoro'
        : criticasNuevas.length > 0
          ? 'empeoro'
          : resueltos.length > 0 && nuevos.length === 0
            ? 'mejoro'
            : 'igual';
  const puntajeEstancado = actual.puntaje === previo.puntaje;
  const mensaje =
    veredicto === 'mejoro'
      ? `Mejoró: ${resueltos.length} hallazgo(s) resueltos (${porcentaje}% de los previos), puntaje ${previo.puntaje} → ${actual.puntaje}.${nuevos.length ? ` Atención: ${nuevos.length} hallazgo(s) nuevos.` : ''}${puntajeEstancado ? ' El puntaje todavía no sube porque quedan hallazgos críticos pendientes: sigue con ellos.' : ''}`
      : veredicto === 'igual'
        ? `Sin cambio de puntaje (${actual.puntaje}). Resueltos: ${resueltos.length}, nuevos: ${nuevos.length}, pendientes: ${permanecen.length}.`
        : `Empeoró: puntaje ${previo.puntaje} → ${actual.puntaje}. ${nuevos.length} hallazgo(s) nuevos requieren revisión.`;

  return {
    raiz: actual.raiz,
    contra: previo.generado_en,
    antes: { puntaje: previo.puntaje, semaforo: previo.semaforo, hallazgos: totalPrevios },
    despues: { puntaje: actual.puntaje, semaforo: actual.semaforo, hallazgos: actual.hallazgos.length },
    resueltos,
    nuevos,
    permanecen,
    porcentaje_resuelto: porcentaje,
    veredicto,
    mensaje,
  };
}

/* ─────────────────────── Plan de corrección ─────────────────────── */

export interface ItemPlanSeguridad {
  orden: number;
  regla: string;
  prioridad: 'ahora' | 'esta_semana' | 'cuando_puedas';
  titulo: string;
  descripcion: string;
  archivos: string[];
  hallazgos: number;
  esfuerzo: 'S' | 'M' | 'L';
  verificacion: string;
}

const PASOS: Record<string, { titulo: string; esfuerzo: ItemPlanSeguridad['esfuerzo']; verificacion: string }> = {
  SEC001: { titulo: 'Rotar y sacar del código la llave de AWS', esfuerzo: 'S', verificacion: 'Volver a escanear: SEC001 debe desaparecer y la app seguir funcionando con la variable de entorno.' },
  SEC002: { titulo: 'Rotar la llave de Google y restringirla', esfuerzo: 'S', verificacion: 'Escanear: SEC002 en cero; la app arranca con GOOGLE_API_KEY.' },
  SEC003: { titulo: 'Rotar la llave de Stripe de producción', esfuerzo: 'S', verificacion: 'Escanear: SEC003 en cero y pagos probados en modo test.' },
  SEC004: { titulo: 'Rotar la llave del proveedor de IA', esfuerzo: 'S', verificacion: 'Escanear: SEC004 en cero; las llamadas siguen funcionando con variable de entorno.' },
  SEC005: { titulo: 'Revocar el token de GitHub', esfuerzo: 'S', verificacion: 'Escanear: SEC005 en cero.' },
  SEC006: { titulo: 'Rotar el token de Slack', esfuerzo: 'S', verificacion: 'Escanear: SEC006 en cero.' },
  SEC007: { titulo: 'Retirar la llave privada del repositorio', esfuerzo: 'M', verificacion: 'Escanear: SEC007 en cero; el servicio arranca leyendo la llave desde disco con permisos 600.' },
  SEC008: { titulo: 'Mover contraseñas y tokens a variables de entorno', esfuerzo: 'S', verificacion: 'Escanear: SEC008 en cero; pruebas de autenticación en verde.' },
  SEC009: { titulo: 'Revisar cadenas de alta entropía (posibles secretos)', esfuerzo: 'S', verificacion: 'Escanear: SEC009 en cero o con exclusiones justificadas en .centinela-ignore.' },
  CFG006: { titulo: 'Eliminar la ejecución dinámica de código', esfuerzo: 'M', verificacion: 'Escanear: CFG006 en cero; pruebas funcionales en verde.' },
  CFG007: { titulo: 'Parametrizar las consultas SQL', esfuerzo: 'M', verificacion: 'Escanear: CFG007 en cero; pruebas de base de datos en verde (incluye caso con comillas en el dato).' },
  CFG012: { titulo: 'Quitar la descarga-y-ejecución de scripts', esfuerzo: 'S', verificacion: 'Escanear: CFG012 en cero; instalar en limpio (rm -rf node_modules && npm ci) sin errores.' },
  CFG009: { titulo: 'Reactivar la verificación TLS', esfuerzo: 'S', verificacion: 'Escanear: CFG009 en cero; la conexión sigue estable con el certificado correcto.' },
  CFG010: { titulo: 'Corregir permisos de archivo excesivos', esfuerzo: 'S', verificacion: 'Escanear: CFG010 en cero; el servicio lee y escribe lo que necesita.' },
  CFG011: { titulo: 'Eliminar credenciales por defecto', esfuerzo: 'S', verificacion: 'Escanear: CFG011 en cero; login con credenciales reales en verde.' },
  DEP001: { titulo: 'Actualizar dependencias con avisos conocidos', esfuerzo: 'M', verificacion: 'npm audit sin hallazgos de severidad alta y suite de pruebas completa en verde.' },
  DEP002: { titulo: 'Fijar rangos de versión en dependencias', esfuerzo: 'S', verificacion: 'Escanear: DEP002 en cero; instalación reproducible con lockfile.' },
  RUT001: { titulo: 'Proteger (o documentar como pública) cada ruta', esfuerzo: 'M', verificacion: 'Escanear: RUT001 en cero; petición sin credenciales responde 401 en las rutas privadas.' },
  HIG001: { titulo: 'Sacar el archivo .env del repositorio', esfuerzo: 'S', verificacion: 'git check-ignore -v .env lo confirma ignorado; escanear: HIG001 en cero.' },
  HIG002: { titulo: 'Confirmar que .env nunca se versionó', esfuerzo: 'S', verificacion: 'git log --all -- .env vacío; escanear: HIG002 en cero.' },
  HIG003: { titulo: 'Completar el .gitignore', esfuerzo: 'S', verificacion: 'Escanear: HIG003 en cero; git status no muestra .env ni llaves.' },
};

export function planDeCorreccion(escaneo: Escaneo): ItemPlanSeguridad[] {
  const porRegla = new Map<string, Hallazgo[]>();
  for (const h of escaneo.hallazgos) {
    if (!porRegla.has(h.regla)) porRegla.set(h.regla, []);
    porRegla.get(h.regla)!.push(h);
  }
  const items = [...porRegla.entries()].map(([regla, hs]) => {
    const peor = hs[0]!;
    const paso = PASOS[regla] ?? {
      titulo: `Corregir ${regla}`,
      esfuerzo: 'M' as const,
      verificacion: 'Volver a escanear y confirmar que la regla desaparece.',
    };
    const prioridad: ItemPlanSeguridad['prioridad'] =
      peor.severidad === 'critica' || peor.severidad === 'alta' ? 'ahora' : peor.severidad === 'media' ? 'esta_semana' : 'cuando_puedas';
    return {
      regla,
      prioridad,
      titulo: paso.titulo,
      descripcion: `${hs.length} hallazgo(s) · ${peor.evidencia}`,
      archivos: [...new Set(hs.map((h) => h.archivo))].slice(0, 6),
      hallazgos: hs.length,
      esfuerzo: paso.esfuerzo,
      verificacion: paso.verificacion,
    };
  });
  const orden: Record<ItemPlanSeguridad['prioridad'], number> = { ahora: 0, esta_semana: 1, cuando_puedas: 2 };
  return items
    .sort((a, b) => orden[a.prioridad] - orden[b.prioridad] || b.hallazgos - a.hallazgos || a.regla.localeCompare(b.regla))
    .map((p, i) => ({ orden: i + 1, ...p }));
}

/* ─────────────────────── Informes ─────────────────────── */

export function informeMarkdown(escaneo: Escaneo): string {
  const l: string[] = [];
  l.push(`# Informe de seguridad — ${escaneo.proyecto}`);
  l.push('');
  l.push(`**Puntaje: ${escaneo.puntaje}/100 · semáforo ${escaneo.semaforo.toUpperCase()}** · ${escaneo.archivos_analizados} archivos analizados · ${escaneo.generado_en}`);
  l.push('');
  l.push(`Críticos: ${escaneo.resumen.critica} · Altos: ${escaneo.resumen.alta} · Medios: ${escaneo.resumen.media} · Bajos: ${escaneo.resumen.baja}`);
  l.push('');
  if (escaneo.notas.length) {
    l.push('## Notas');
    for (const n of escaneo.notas) l.push(`- ${n}`);
    l.push('');
  }
  l.push('## Hallazgos');
  l.push('');
  for (const h of escaneo.hallazgos) {
    l.push(`### ${h.regla} · ${h.severidad.toUpperCase()} — ${h.archivo}:${h.linea}`);
    l.push(`- **Evidencia:** \`${h.evidencia}\``);
    l.push(`- **Recomendación:** ${h.recomendacion}`);
    l.push('');
  }
  if (escaneo.ignorados.length) {
    l.push('## Excluidos por .centinela-ignore');
    for (const i of escaneo.ignorados) l.push(`- ${i.regla} en ${i.archivo}:${i.linea} — ${i.motivo}`);
    l.push('');
  }
  return l.join('\n');
}

export function planMarkdown(plan: ItemPlanSeguridad[], escaneo: Escaneo): string {
  const l: string[] = [];
  l.push(`# Plan de corrección de seguridad — ${escaneo.proyecto}`);
  l.push('');
  l.push(`Derivado del escaneo con puntaje **${escaneo.puntaje}/100 (${escaneo.semaforo})**. Cada paso incluye cómo **comprobar** que quedó resuelto.`);
  l.push('');
  for (const p of plan) {
    l.push(`## ${p.orden}. ${p.titulo}  \`[${p.prioridad.replace('_', ' ')} · esfuerzo ${p.esfuerzo} · ${p.regla}]\``);
    l.push(`- ${p.descripcion}`);
    l.push(`- Archivos: ${p.archivos.map((a) => `\`${a}\``).join(', ')}`);
    l.push(`- **Verificación:** ${p.verificacion}`);
    l.push('');
  }
  return l.join('\n');
}

/** Firma de un escaneo (para guardar como línea base). */
export function sello(): string {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

export function hashContenido(texto: string): string {
  return crypto.createHash('sha256').update(texto).digest('hex').slice(0, 16);
}
