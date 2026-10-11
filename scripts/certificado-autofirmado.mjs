/**
 * Genera un certificado TLS autofirmado para desarrollo/pruebas (no usar en
 * producción: los clientes verán advertencia de certificado).
 *
 * Uso: node scripts/certificado-autofirmado.mjs [CN]
 * Salida: data/tls/cert.pem y data/tls/key.pem + las variables a exportar.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const dir = path.join(root, 'data', 'tls');
const cn = process.argv[2] ?? 'localhost';
fs.mkdirSync(dir, { recursive: true });

const cert = path.join(dir, 'cert.pem');
const key = path.join(dir, 'key.pem');

execFileSync(
  'openssl',
  [
    'req',
    '-x509',
    '-newkey',
    'rsa:2048',
    '-nodes',
    '-keyout',
    key,
    '-out',
    cert,
    '-days',
    '365',
    '-subj',
    `/CN=${cn}`,
    '-addext',
    `subjectAltName=DNS:${cn},DNS:localhost,IP:127.0.0.1`,
  ],
  { stdio: 'inherit' },
);

console.log('\nCertificado autofirmado listo:');
console.log(`  ${cert}`);
console.log(`  ${key}\n`);
console.log('Para arrancar con HTTPS:');
console.log(`  TLS_CERT=${path.relative(root, cert)} TLS_KEY=${path.relative(root, key)} npm run start:http\n`);
console.log('Prueba rápida (el certificado es autofirmado, por eso -k):');
console.log('  curl -k https://localhost:8787/health\n');
