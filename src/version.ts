import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

export const SERVER_NAME = 'mcp-suite-mx';
export const SERVER_TITLE = 'Suite MCP MX';
/** Versión real del paquete: siempre la de package.json (una sola fuente de verdad). */
export const VERSION: string = (require('../package.json') as { version: string }).version;
