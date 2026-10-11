import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import type http from 'node:http';
import { startHttp } from '../src/http';
import { ensureSeedData } from '../src/seed';
import { contextoDePrueba, dataDirTemporal } from './helpers';

let tieneOpenSsl = true;
try {
  execFileSync('openssl', ['version'], { stdio: 'ignore' });
} catch {
  tieneOpenSsl = false;
}

const describeTls = tieneOpenSsl ? describe : describe.skip;

function httpsGet(url: string): Promise<{ status: number; headers: http.IncomingHttpHeaders; body: string }> {
  return new Promise((resolve, reject) => {
    https
      .get(url, { rejectUnauthorized: false }, (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => resolve({ status: res.statusCode!, headers: res.headers, body }));
      })
      .on('error', reject);
  });
}

describeTls('transporte HTTPS con certificado propio', () => {
  let servidor: http.Server;
  let base: string;
  let dataDir: string;

  beforeAll(async () => {
    dataDir = await dataDirTemporal('mcp-suite-tls-');
    const tlsDir = path.join(dataDir, 'tls');
    await fs.mkdir(tlsDir, { recursive: true });
    const cert = path.join(tlsDir, 'cert.pem');
    const key = path.join(tlsDir, 'key.pem');
    execFileSync(
      'openssl',
      ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert, '-days', '2', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'],
      { stdio: 'ignore' },
    );

    const ctx = await contextoDePrueba(dataDir, {
      transport: 'http',
      port: 0,
      host: '127.0.0.1',
      authMode: 'required',
      apiKeys: ['clave-tls-123'],
      tlsCert: cert,
      tlsKey: key,
    });
    await ensureSeedData(ctx);
    servidor = await startHttp(ctx);
    const addr = servidor.address();
    base = `https://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => servidor.close(() => resolve()));
    await fs.rm(dataDir, { recursive: true, force: true });
  });

  it('sirve /health por HTTPS con HSTS', async () => {
    const res = await httpsGet(`${base}/health`);
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body).ok).toBe(true);
    expect(res.headers['strict-transport-security']).toContain('max-age=');
  });

  it('mantiene la autenticación y el panel sobre TLS', async () => {
    const panel = await httpsGet(`${base}/panel`);
    expect(panel.status).toBe(200);
    expect(panel.body).toContain('Catálogo de herramientas');
  });
});
