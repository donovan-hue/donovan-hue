import type { InventarioData, PedidosData } from './db/negocio.js';
import type { PlantillasData } from './db/plantillas.js';

/** Datos de ejemplo para que las herramientas funcionen desde el primer arranque. */

export const INVENTARIO_SEED: InventarioData = {
  version: 1,
  items: [
    { sku: 'TEC-001', nombre: 'Teclado mecánico RGB', categoria: 'Electrónica', precio: 1290, costo: 780, stock: 24, stock_minimo: 5, unidad: 'pieza', proveedor: 'TecnoImport MX', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'MOU-002', nombre: 'Mouse inalámbrico ergonómico', categoria: 'Electrónica', precio: 650, costo: 340, stock: 3, stock_minimo: 8, unidad: 'pieza', proveedor: 'TecnoImport MX', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'MON-003', nombre: 'Monitor 27 pulgadas 144Hz', categoria: 'Electrónica', precio: 5890, costo: 4200, stock: 7, stock_minimo: 3, unidad: 'pieza', proveedor: 'DisplayPro', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'SIL-004', nombre: 'Silla ergonómica malla', categoria: 'Mobiliario', precio: 3490, costo: 2100, stock: 12, stock_minimo: 4, unidad: 'pieza', proveedor: 'Oficinas del Bajío', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'ESC-005', nombre: 'Escritorio elevable 1.4m', categoria: 'Mobiliario', precio: 4990, costo: 3100, stock: 5, stock_minimo: 2, unidad: 'pieza', proveedor: 'Oficinas del Bajío', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'LAM-006', nombre: 'Lámpara LED de escritorio', categoria: 'Accesorios', precio: 420, costo: 190, stock: 40, stock_minimo: 10, unidad: 'pieza', proveedor: 'Ilumina GDL', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'WEB-007', nombre: 'Webcam Full HD con micrófono', categoria: 'Electrónica', precio: 890, costo: 520, stock: 2, stock_minimo: 5, unidad: 'pieza', proveedor: 'TecnoImport MX', actualizado_en: '2026-09-01T10:00:00.000Z' },
    { sku: 'DIA-008', nombre: 'Diadema con cancelación de ruido', categoria: 'Audio', precio: 1450, costo: 860, stock: 15, stock_minimo: 5, unidad: 'pieza', proveedor: 'AudioSur', actualizado_en: '2026-09-01T10:00:00.000Z' },
  ],
};

export const PEDIDOS_SEED: PedidosData = {
  version: 1,
  consecutivo: 2,
  pedidos: [
    {
      folio: 'P-0001',
      cliente: 'Distribuidora Jalisco SA de CV',
      estado: 'enviado',
      items: [
        { sku: 'TEC-001', descripcion: 'Teclado mecánico RGB', cantidad: 10, precio_unitario: 1290, importe: 12900 },
        { sku: 'LAM-006', descripcion: 'Lámpara LED de escritorio', cantidad: 20, precio_unitario: 420, importe: 8400 },
      ],
      subtotal: 21300,
      iva: 3408,
      total: 24708,
      notas: 'Entrega en bodega Tlaquepaque.',
      creado_en: '2026-09-20T16:00:00.000Z',
      actualizado_en: '2026-09-25T18:30:00.000Z',
      historial: [
        { estado: 'borrador', fecha: '2026-09-20T16:00:00.000Z' },
        { estado: 'confirmado', fecha: '2026-09-21T10:00:00.000Z' },
        { estado: 'en_produccion', fecha: '2026-09-22T09:00:00.000Z' },
        { estado: 'enviado', fecha: '2026-09-25T18:30:00.000Z', nota: 'Guía DHL 1234567890' },
      ],
    },
    {
      folio: 'P-0002',
      cliente: 'Coworking Chapultepec',
      estado: 'confirmado',
      items: [{ sku: 'SIL-004', descripcion: 'Silla ergonómica malla', cantidad: 4, precio_unitario: 3490, importe: 13960 }],
      subtotal: 13960,
      iva: 2233.6,
      total: 16193.6,
      notas: '',
      creado_en: '2026-10-02T15:00:00.000Z',
      actualizado_en: '2026-10-03T11:00:00.000Z',
      historial: [
        { estado: 'borrador', fecha: '2026-10-02T15:00:00.000Z' },
        { estado: 'confirmado', fecha: '2026-10-03T11:00:00.000Z', nota: 'Anticipo 50% recibido' },
      ],
    },
  ],
};

export const PLANTILLAS_SEED: PlantillasData = {
  version: 1,
  plantillas: [
    {
      nombre: 'recordatorio_pago',
      descripcion: 'Correo de recordatorio de pago para clientes con facturas vencidas.',
      variables: ['cliente', 'folio', 'monto', 'fecha_limite', 'dias_retraso', 'vendedor'],
      contenido:
        'Estimado(a) {{cliente}}:\n\n' +
        'Le recordamos amablemente que la factura con folio {{folio}} por un monto de ${{monto}} MXN venció el {{fecha_limite}} ' +
        '({{dias_retraso}} días de atraso).\n\n' +
        'Le agradeceremos realizar el pago a la brevedad posible. Si ya lo realizó, por favor comparta su comprobante para actualizar el registro.\n\n' +
        'Quedamos a sus órdenes.\n\nSaludos cordiales,\n{{vendedor}}\nÁrea de Cobranza',
      creado_en: '2026-09-15T12:00:00.000Z',
    },
    {
      nombre: 'propuesta_servicios',
      descripcion: 'Propuesta comercial básica para servicios profesionales.',
      variables: ['cliente', 'proyecto', 'monto', 'duracion_semanas', 'fecha'],
      contenido:
        'PROPUESTA COMERCIAL\n\nCliente: {{cliente}}\nProyecto: {{proyecto}}\nFecha: {{fecha}}\n\n' +
        'Alcance: implementación de {{proyecto}} con acompañamiento técnico durante {{duracion_semanas}} semanas.\n' +
        'Inversión: ${{monto}} MXN + IVA.\nVigencia de la propuesta: 30 días naturales.\n\nFirma de conformidad: ____________________',
      creado_en: '2026-09-15T12:05:00.000Z',
    },
  ],
};
