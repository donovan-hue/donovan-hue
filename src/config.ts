import path from 'node:path';
import { fileURLToPath } from 'node:url';

export type LogLevelName = 'debug' | 'info' | 'warn' | 'error';
export type AuthMode = 'off' | 'optional' | 'required';

export interface Config {
  transport: 'stdio' | 'http';
  port: number;
  host: string;
  dataDir: string;
  ivaPct: number;
  allowedGitRoots: string[];
  /** Si es false, el mapa vivo (/taller y su API) exige autenticación. */
  tallerPublico: boolean;
  httpCheckAllowPrivate: boolean;
  corsOrigin: string;
  logLevel: LogLevelName;
  /** off = sin autenticación (solo desarrollo) · optional = valida si hay clave · required = obliga. */
  authMode: AuthMode;
  /** Claves completas definidas por entorno (bootstrap/CI). Nunca se guardan en disco. */
  apiKeys: string[];
  /** Límite de peticiones MCP por minuto y por cliente. 0 desactiva el limitador. */
  rateLimitRpm: number;
  /** Ráfaga máxima permitida (capacidad de la cubeta). */
  rateLimitBurst: number;
  /** Confiar en X-Forwarded-For (solo detrás de un reverse proxy propio). */
  trustProxy: boolean;
  /** Ruta al certificado TLS (PEM). Con tlsKey habilita HTTPS en el transporte http. */
  tlsCert?: string;
  tlsKey?: string;
  /** Datos fiscales del emisor para el módulo de facturación (CFDI). */
  cfdiEmisorRfc?: string;
  cfdiEmisorNombre?: string;
  cfdiEmisorRegimen?: string;
  cfdiEmisorCp?: string;
  /** Empresa activa por defecto en instalaciones nuevas (multi-empresa). */
  empresaDefault?: string;
}

const here = path.dirname(fileURLToPath(import.meta.url));
/** Raíz del proyecto (en dev `src/`, compilado `dist/` → siempre un nivel arriba). */
export const projectRoot = path.resolve(here, '..');

function cliFlag(name: string): string | undefined {
  const prefix = `--${name}=`;
  const exact = process.argv.find((a) => a.startsWith(prefix));
  if (exact) return exact.slice(prefix.length);
  const idx = process.argv.indexOf(`--${name}`);
  const next = idx !== -1 ? process.argv[idx + 1] : undefined;
  if (next && !next.startsWith('--')) return next;
  return undefined;
}

function envOrFlag(name: string, envKey: string): string | undefined {
  return cliFlag(name) ?? process.env[envKey];
}

function numero(valor: string | undefined, porDefecto: number, nombre: string, min = -Infinity, max = Infinity): number {
  if (valor === undefined || valor === '') return porDefecto;
  const n = Number(valor);
  if (!Number.isFinite(n) || n < min || n > max) {
    throw new Error(`Valor inválido para ${nombre}: "${valor}". Rango esperado: ${min}–${max}.`);
  }
  return n;
}

export function loadConfig(overrides: Partial<Config> = {}): Config {
  const transport = (envOrFlag('transport', 'MCP_TRANSPORT') ?? 'stdio') as Config['transport'];
  if (transport !== 'stdio' && transport !== 'http') {
    throw new Error(`Transporte inválido: "${transport}". Usa "stdio" o "http".`);
  }

  const authMode = (process.env.AUTH_MODE ?? 'off') as AuthMode;
  if (!['off', 'optional', 'required'].includes(authMode)) {
    throw new Error(`AUTH_MODE inválido: "${authMode}". Usa "off", "optional" o "required".`);
  }

  const rawDataDir = process.env.DATA_DIR ?? 'data';
  const port = numero(envOrFlag('port', 'PORT'), 8787, 'PORT', 1, 65535);

  const gitRoots = (process.env.ALLOWED_GIT_ROOTS ?? projectRoot)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((p) => path.resolve(p));

  const config: Config = {
    transport,
    port,
    host: process.env.HOST ?? '0.0.0.0',
    dataDir: path.isAbsolute(rawDataDir) ? rawDataDir : path.resolve(projectRoot, rawDataDir),
    ivaPct: numero(process.env.IVA_PCT, 16, 'IVA_PCT', 0, 100),
    allowedGitRoots: gitRoots.length > 0 ? gitRoots : [projectRoot],
    tallerPublico: (process.env.TALLER_PUBLICO ?? '1') !== '0',
    empresaDefault: process.env.EMPRESA_DEFAULT?.trim() || undefined,
    httpCheckAllowPrivate: process.env.HTTP_CHECK_ALLOW_PRIVATE === '1',
    corsOrigin: process.env.CORS_ORIGIN ?? '*',
    logLevel: (process.env.LOG_LEVEL as LogLevelName) ?? 'info',
    authMode,
    apiKeys: (process.env.MCP_API_KEYS ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
    rateLimitRpm: numero(process.env.RATE_LIMIT_RPM, 120, 'RATE_LIMIT_RPM', 0, 1_000_000),
    rateLimitBurst: numero(process.env.RATE_LIMIT_BURST, 30, 'RATE_LIMIT_BURST', 1, 100_000),
    trustProxy: process.env.TRUST_PROXY === '1',
    tlsCert: process.env.TLS_CERT ? path.resolve(process.env.TLS_CERT) : undefined,
    tlsKey: process.env.TLS_KEY ? path.resolve(process.env.TLS_KEY) : undefined,
    cfdiEmisorRfc: process.env.CFDI_EMISOR_RFC,
    cfdiEmisorNombre: process.env.CFDI_EMISOR_NOMBRE,
    cfdiEmisorRegimen: process.env.CFDI_EMISOR_REGIMEN,
    cfdiEmisorCp: process.env.CFDI_EMISOR_CP,
  };

  const resultado = { ...config, ...overrides };

  // Validaciones de coherencia entre campos (incluidas las sobreescrituras).
  if ((resultado.tlsCert && !resultado.tlsKey) || (!resultado.tlsCert && resultado.tlsKey)) {
    throw new Error('TLS_CERT y TLS_KEY deben configurarse juntos (o ninguno de los dos).');
  }
  if (resultado.transport === 'http' && resultado.authMode === 'required' && resultado.apiKeys.length === 0) {
    if (process.env.NODE_ENV === 'production') {
      throw new Error(
        'AUTH_MODE=required en producción exige al menos una clave bootstrap en MCP_API_KEYS ' +
          '(o crear claves con: node dist/cli.js keys create).',
      );
    }
  }
  if (resultado.transport === 'http' && resultado.authMode === 'off' && resultado.host === '0.0.0.0' && process.env.NODE_ENV === 'production') {
    throw new Error('AUTH_MODE=off con bind 0.0.0.0 en producción está prohibido. Usa AUTH_MODE=required.');
  }

  return resultado;
}
