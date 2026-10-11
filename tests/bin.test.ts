/**
 * El paquete publicado, tal como lo instala npm.
 *
 * Aquí se prueba lo que rompió una vez y es fácil de romper otra vez: npm
 * instala los binarios como enlaces (node_modules/.bin/mcp-suite-mx) y el
 * servidor debe arrancar igual. Si la comprobación de «me están ejecutando»
 * mira la ruta del enlace en vez de la real, el servidor instalado sale en
 * silencio sin hacer nada.
 */
import { describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, symlinkSync, rmSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { projectRoot } from '../src/config';
import { VERSION } from '../src/version';

const distIndex = path.join(projectRoot, 'dist', 'index.js');
const distCli = path.join(projectRoot, 'dist', 'cli.js');
const hayDist = existsSync(distIndex) && existsSync(distCli);

/** Copia el binario publicado como npm lo deja: un enlace en .bin. */
function binarioEnlazado(): { raiz: string; enlace: string } {
  const raiz = mkdtempSync(path.join(os.tmpdir(), 'mcp-suite-bin-'));
  const bin = path.join(raiz, 'node_modules', '.bin');
  const paquete = path.join(raiz, 'node_modules', 'mcp-suite-mx', 'dist');
  execFileSync('mkdir', ['-p', bin, paquete]);
  for (const archivo of ['index.js', 'cli.js']) {
    symlinkSync(path.join(projectRoot, 'dist', archivo), path.join(paquete, archivo));
  }
  const enlace = path.join(bin, 'mcp-suite-mx');
  symlinkSync(path.join(paquete, 'index.js'), enlace);
  return { raiz, enlace };
}

describe('PAQUETE — binarios tal como los instala npm', () => {
  it.skipIf(!hayDist)('el binario enlazado responde --version y --help', () => {
    const { raiz, enlace } = binarioEnlazado();
    try {
      const version = execFileSync(process.execPath, [enlace, '--version'], { encoding: 'utf8' }).trim();
      expect(version).toBe(VERSION);

      const ayuda = execFileSync(process.execPath, [enlace, '--help'], { encoding: 'utf8' });
      expect(ayuda).toContain('mcp-suite-mx');
      expect(ayuda).toContain('--transport http');
      expect(ayuda).toContain('mcp-suite-mx-cli');
    } finally {
      rmSync(raiz, { recursive: true, force: true });
    }
  });

  it.skipIf(!hayDist)('el binario enlazado arranca de verdad (no sale en silencio)', () => {
    const { raiz, enlace } = binarioEnlazado();
    try {
      const respuesta = execFileSync(process.execPath, [enlace], {
        encoding: 'utf8',
        input: `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'prueba-bin', version: '1' } } })}\n`,
        timeout: 20_000,
        env: { ...process.env, DATA_DIR: path.join(raiz, 'datos') },
      });
      const linea = respuesta.split('\n').find((l) => l.includes('"serverInfo"'));
      expect(linea).toBeTruthy();
      expect(JSON.parse(linea!).result.serverInfo).toMatchObject({ name: 'mcp-suite-mx', version: VERSION });
    } finally {
      rmSync(raiz, { recursive: true, force: true });
    }
  });

  it.skipIf(!hayDist)('lo que se publica lleva shebang y no se cuela nada raro', () => {
    for (const archivo of [distIndex, distCli]) {
      expect(readFileSync(archivo, 'utf8').startsWith('#!/usr/bin/env node')).toBe(true);
    }
    const pkg = JSON.parse(readFileSync(path.join(projectRoot, 'package.json'), 'utf8')) as {
      bin: Record<string, string>;
      files: string[];
      mcpName?: string;
      private?: boolean;
      name: string;
    };
    expect(pkg.bin).toEqual({ 'mcp-suite-mx': 'dist/index.js', 'mcp-suite-mx-cli': 'dist/cli.js' });
    expect(pkg.files).toEqual(expect.arrayContaining(['dist', 'paginas', 'docs', 'README.md', '.env.example']));
    expect(pkg.private).toBeUndefined();
    // El registro MCP exige mcpName igual al nombre declarado en server.json.
    const server = JSON.parse(readFileSync(path.join(projectRoot, 'server.json'), 'utf8')) as { name: string; version: string };
    expect(pkg.mcpName).toBe(server.name);
    expect(pkg.name).toBe('mcp-suite-mx');
  });
});
