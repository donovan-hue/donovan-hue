/**
 * Genera los datasets de ejemplo de forma determinista (sin Math.random con semilla externa).
 * Uso: node scripts/generar-datasets.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const datasets = path.join(root, 'data', 'datasets');
const logs = path.join(root, 'data', 'logs');
fs.mkdirSync(datasets, { recursive: true });
fs.mkdirSync(logs, { recursive: true });

// ── ventas_mensuales.csv ──────────────────────────────────────────────────────
const sucursales = ['Guadalajara', 'Monterrey'];
const vendedores = ['Ana Ruiz', 'Carlos Medina', 'Luis Ortega'];
const productos = [
  ['TEC-001', 'Teclado mecánico RGB', 'Electrónica', 1290, 0.62],
  ['MON-003', 'Monitor 27 pulgadas 144Hz', 'Electrónica', 5890, 0.71],
  ['SIL-004', 'Silla ergonómica malla', 'Mobiliario', 3490, 0.60],
  ['LAM-006', 'Lámpara LED de escritorio', 'Accesorios', 420, 0.45],
];

const filas = [['mes', 'sucursal', 'vendedor', 'sku', 'producto', 'categoria', 'unidades', 'precio_unitario', 'venta_neta', 'costo_total']];
let i = 0;
for (const anio of [2024, 2025]) {
  for (let mes = 1; mes <= 12; mes++) {
    for (const sucursal of sucursales) {
      const producto = productos[i % productos.length];
      const vendedor = vendedores[i % vendedores.length];
      const estacionalidad = 1 + 0.28 * Math.sin(((mes - 1) / 12) * Math.PI * 2) + (mes === 12 ? 0.35 : 0) + (mes === 1 ? -0.18 : 0);
      const tendencia = 1 + (anio - 2024) * 0.22;
      const unidades = Math.round(9 * estacionalidad * tendencia * (1 + ((i % 5) - 2) / 10));
      const precio = producto[3];
      const venta = unidades * precio;
      const costo = Math.round(venta * (1 - producto[4]));
      filas.push([
        `${anio}-${String(mes).padStart(2, '0')}`,
        sucursal,
        vendedor,
        producto[0],
        producto[1],
        producto[2],
        unidades,
        precio,
        Math.round(venta),
        costo,
      ]);
      i++;
    }
  }
}
fs.writeFileSync(path.join(datasets, 'ventas_mensuales.csv'), filas.map((f) => f.join(',')).join('\n') + '\n');

// ── clientes.csv ──────────────────────────────────────────────────────────────
const clientes = [
  ['Distribuidora Jalisco SA de CV', 'DJA240118H72', 'Guadalajara', 'Distribución', 'compras@dja.mx', '+52 33 3812 4400', 'Alma Navarro', 250000, 84200, 'activo'],
  ['Coworking Chapultepec SA de CV', 'CCH190903K31', 'Guadalajara', 'Servicios', 'admin@coworkchapu.mx', '+52 33 3615 7788', 'Raúl Ibarra', 120000, 16193, 'activo'],
  ['TecnoImport MX S. de R.L.', 'TIM170522B45', 'Monterrey', 'Importación', 'ventas@tecnoimport.mx', '+52 81 8340 1122', 'Sofía Cantú', 400000, 0, 'activo'],
  ['Oficinas del Bajío SA de CV', 'OBA150711Q98', 'León', 'Mobiliario', 'contacto@oficinasbajio.mx', '+52 477 712 3344', 'Jorge Rincón', 180000, 45300, 'activo'],
  ['AudioSur SA de CV', 'AUR200214M77', 'Mérida', 'Audio', 'pagos@audiosur.mx', '+52 999 923 5566', 'Mariana Pech', 90000, 12750, 'activo'],
  ['Constructora Cárdenas e Hijos', 'CCI110428N12', 'Tlaquepaque', 'Construcción', 'obra@ccih.mx', '+52 33 3658 9012', 'Pedro Cárdenas', 300000, 218000, 'con atraso'],
  ['Estudio Contable Zepeda', 'ECZ180330P56', 'Zapopan', 'Servicios profesionales', 'zepeda@contablezep.mx', '+52 33 3122 8877', 'Verónica Zepeda', 60000, 8300, 'activo'],
  ['Hotel Ribera del Lago SA de CV', 'HRL160619R23', 'Chapala', 'Hotelería', 'compras@riberalago.mx', '+52 376 765 4433', 'Iván Serrano', 150000, 0, 'activo'],
  ['MedicalForce MX S.A.P.I.', 'MMX210907T64', 'Guadalajara', 'Salud', 'adquisiciones@medicalforce.mx', '+52 33 3846 2299', 'Daniela Robles', 200000, 94200, 'activo'],
  ['Restaurante El Fogón Tapatío', 'RFT120215F09', 'Guadalajara', 'Alimentos', 'elfogontapatio@gmail.com', '+52 33 3820 6611', 'Hugo Trujillo', 40000, 36700, 'con atraso'],
  ['Universidad Tecnológica del Sur', 'UTS990301X88', 'Ocotlán', 'Educación', 'compras@uts.edu.mx', '+52 392 925 1177', 'Elena Márquez', 250000, 0, 'activo'],
  ['Fábrica de Muebles Zamora', 'FMZ140812V31', 'Zamora', 'Manufactura', 'ventas@muebleszamora.mx', '+52 351 512 4477', 'Ricardo Alfaro', 95000, 52100, 'inactivo'],
];
const encabezado = ['cliente', 'rfc', 'ciudad', 'giro', 'email', 'telefono', 'contacto', 'limite_credito', 'saldo_actual', 'estado'];
fs.writeFileSync(path.join(datasets, 'clientes.csv'), [encabezado, ...clientes].map((f) => f.join(',')).join('\n') + '\n');

// ── logs/api_produccion.log ───────────────────────────────────────────────────
const errores = [
  ['ERROR', 'database', 'connection refused al conectar a postgres://db-primary:5432 (intento 3/5)'],
  ['ERROR', 'payments', 'timeout agotado tras 30000ms al llamar a https://api.pagos.mx/v2/charges'],
  ['ERROR', 'database', 'connection refused al conectar a postgres://db-primary:5432 (intento 4/5)'],
  ['WARN', 'http', 'petición lenta: GET /api/reportes/semanal tardó 8421ms'],
  ['ERROR', 'payments', 'timeout agotado tras 30000ms al llamar a https://api.pagos.mx/v2/charges'],
  ['INFO', 'auth', 'sesión renovada para usuario operador_12'],
  ['ERROR', 'database', 'connection refused al conectar a postgres://db-primary:5432 (intento 5/5)'],
  ['DEBUG', 'cache', 'hit de caché para clave clientes:activos'],
  ['ERROR', 'api', 'UnhandledPromiseRejection: Cannot read properties of undefined (reading "total")'],
  ['WARN', 'http', 'petición lenta: POST /api/facturas tardó 6120ms'],
  ['ERROR', 'payments', 'timeout agotado tras 30000ms al llamar a https://api.pagos.mx/v2/charges'],
  ['INFO', 'deploy', 'versión v2.14.3 desplegada en nodo prod-02'],
];
let lineaLog = '';
let d = new Date('2026-10-08T06:00:00Z');
for (let n = 0; n < 180; n++) {
  const [nivel, modulo, mensaje] = errores[n % errores.length];
  const ts = new Date(d.getTime());
  lineaLog += `${ts.toISOString()} ${nivel.padEnd(5)} [${modulo}] req_id=${(n * 7919 % 100000).toString(36)} ${mensaje}\n`;
  d = new Date(d.getTime() + (n % 7 === 0 ? 240000 : 55000));
}
fs.writeFileSync(path.join(logs, 'api_produccion.log'), lineaLog);

console.log('OK:', {
  ventas: filas.length - 1,
  clientes: clientes.length,
  log_lineas: lineaLog.trim().split('\n').length,
});
