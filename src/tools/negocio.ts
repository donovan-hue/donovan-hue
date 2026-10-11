import { z } from 'zod';
import type { ServerCtx } from '../context.js';
import type { ToolDef } from '../lib/result.js';
import { InventarioRepo, PedidosRepo } from '../db/negocio.js';
import { empresaActiva, empresaActivaDetalle } from '../lib/empresas.js';
import { calcularCotizacion, calcularImpuestos, calcularMargen, puntoEquilibrio } from '../lib/finance.js';
import { csvToRecords, guessNumber } from '../lib/csv.js';
import { readTextFile } from '../lib/store.js';
import type { EstadoPedido } from '../lib/finance.js';
import { log } from '../logger.js';

const round2 = (n: number) => Number(n.toFixed(2));

export function negocioToolDefs(defs: ToolDef[], ctx: ServerCtx): void {
  const push = (d: ToolDef) => void defs.push(d);
  const inventario = () => new InventarioRepo(ctx.db, empresaActiva(ctx));
  const pedidos = () => new PedidosRepo(ctx.db, empresaActiva(ctx));
  const empresa = () => empresaActivaDetalle(ctx);

  push({
    module: 'negocio',
    name: 'inventario_consultar',
    title: 'Consultar inventario',
    description:
      'Consulta el inventario de productos: filtra por SKU exacto, por texto libre (nombre/proveedor/categoría), por categoría o solo artículos bajo el stock mínimo.\n\nEjemplos de uso: "¿cuánto stock hay del SKU TEC-001?", "muéstrame los productos bajo mínimo", "busca todos los productos de la categoría Herramientas".',
    readOnly: true,
    inputSchema: {
      sku: z.string().optional().describe('SKU exacto del producto, ej. "TEC-001".'),
      query: z.string().optional().describe('Texto libre para buscar en nombre, SKU, proveedor o categoría.'),
      categoria: z.string().optional().describe('Filtrar por categoría exacta, ej. "Herramientas".'),
      bajo_minimo: z.boolean().optional().describe('Si es true, devuelve solo productos con stock <= stock_minimo.'),
    },
    handler: async (args: { sku?: string; query?: string; categoria?: string; bajo_minimo?: boolean }) => {
      const repo = inventario();
      const items = repo.listar(args);
      if (args.sku && items.length === 0) throw new Error(`No existe un producto con SKU "${args.sku}".`);

      return {
        empresa: { id: empresa().id, nombre: empresa().nombre },
        total_resultados: items.length,
        total_inventario: repo.total(),
        resultados: items.map((i) => ({
          sku: i.sku,
          nombre: i.nombre,
          categoria: i.categoria,
          stock: i.stock,
          stock_minimo: i.stock_minimo,
          bajo_minimo: i.stock <= i.stock_minimo,
          unidad: i.unidad,
          precio: i.precio,
          valor_inventario: round2(i.stock * i.costo),
          proveedor: i.proveedor,
        })),
      };
    },
  });

  push({
    module: 'negocio',
    name: 'inventario_actualizar',
    title: 'Actualizar inventario',
    description:
      'Registra movimientos de inventario de forma transaccional.\n\n- tipo "entrada": suma cantidad al stock (recepción de mercancía).\n- tipo "salida": resta cantidad del stock (venta/pérdida); falla si no hay suficiente.\n- tipo "ajuste": fija el stock absoluto a la cantidad indicada (conteo físico).\n\nRequiere motivo para dejar rastro de auditoría.',
    destructive: true,
    inputSchema: {
      sku: z.string().describe('SKU del producto a mover.'),
      tipo: z.enum(['entrada', 'salida', 'ajuste']).describe('Tipo de movimiento.'),
      cantidad: z.number().int().positive().describe('Cantidad del movimiento (para "ajuste" es el nuevo stock absoluto).'),
      motivo: z.string().min(3).describe('Motivo del movimiento, ej. "Recepción OC-812", "Conteo físico mensual".'),
    },
    handler: async (args: { sku: string; tipo: 'entrada' | 'salida' | 'ajuste'; cantidad: number; motivo: string }) => {
      const { item, stockAnterior } = inventario().aplicarMovimiento(args);
      log.info('inventario_movimiento', { sku: item.sku, tipo: args.tipo, cantidad: args.cantidad, motivo: args.motivo });
      return {
        sku: item.sku,
        nombre: item.nombre,
        tipo: args.tipo,
        motivo: args.motivo,
        stock_anterior: stockAnterior,
        stock_nuevo: item.stock,
        bajo_minimo: item.stock <= item.stock_minimo,
      };
    },
  });

  push({
    module: 'negocio',
    name: 'inventario_importar',
    title: 'Importar catálogo (CSV)',
    description:
      'Carga el catálogo de productos de la empresa activa desde un CSV con encabezados (sku, nombre, precio, stock y opcionalmente categoria, costo, stock_minimo, unidad, proveedor). Sirve para arrancar el espacio de un cliente nuevo sin capturar producto por producto. Con dry_run=true te dice qué haría sin tocar nada; modo "agregar" respeta los SKU existentes y modo "reemplazar" los sobrescribe.',
    destructive: true,
    inputSchema: {
      contenido_csv: z.string().optional().describe('Contenido del CSV (texto). Usa esto o ruta_csv.'),
      ruta_csv: z.string().optional().describe('Ruta a un archivo CSV dentro del área permitida (data dir o repositorios autorizados).'),
      delimitador: z.string().optional().describe('Delimitador explícito (por defecto se detecta: coma, punto y coma, tabulador o barra).'),
      modo: z.enum(['agregar', 'reemplazar']).optional().describe('agregar (por defecto) = solo altas nuevas; reemplazar = sobrescribe los SKU que ya existan.'),
      dry_run: z.boolean().optional().describe('true = valida y reporta sin escribir nada.'),
    },
    handler: async (args: { contenido_csv?: string; ruta_csv?: string; delimitador?: string; modo?: 'agregar' | 'reemplazar'; dry_run?: boolean }) => {
      if (!args.contenido_csv && !args.ruta_csv) throw new Error('Pasa "contenido_csv" (texto) o "ruta_csv" (archivo).');
      const csv = args.contenido_csv ?? (await readTextFile(args.ruta_csv!));
      const tabla = csvToRecords(csv, args.delimitador);
      if (tabla.headers.length === 0) throw new Error('El CSV está vacío o no tiene encabezados.');

      const col = (nombres: string[]) => nombres.find((n) => tabla.headers.some((h) => h.toLowerCase() === n));
      const cSku = col(['sku']);
      const cNombre = col(['nombre', 'name', 'descripcion', 'producto']);
      if (!cSku || !cNombre) {
        throw new Error(`Faltan columnas obligatorias (sku, nombre). Encontré: ${tabla.headers.join(', ')}.`);
      }
      const cCategoria = col(['categoria', 'category']);
      const cPrecio = col(['precio', 'price', 'precio_unitario']);
      const cCosto = col(['costo', 'cost']);
      const cStock = col(['stock', 'existencia', 'cantidad']);
      const cMinimo = col(['stock_minimo', 'minimo', 'min']);
      const cUnidad = col(['unidad', 'unit']);
      const cProveedor = col(['proveedor', 'provider']);

      const problemas: { fila: number; motivo: string }[] = [];
      const normalizados: {
        sku: string; nombre: string; categoria: string; precio: number; costo: number;
        stock: number; stock_minimo: number; unidad: string; proveedor: string;
      }[] = [];
      const vistos = new Set<string>();
      tabla.rows.forEach((fila, i) => {
        const num = i + 2; // 1 es el encabezado
        const sku = (fila[cSku] ?? '').trim();
        const nombre = (fila[cNombre] ?? '').trim();
        if (!sku) return void problemas.push({ fila: num, motivo: 'sin SKU' });
        if (!nombre) return void problemas.push({ fila: num, motivo: `SKU ${sku}: sin nombre` });
        if (vistos.has(sku.toUpperCase())) return void problemas.push({ fila: num, motivo: `SKU ${sku} repetido en el archivo` });
        vistos.add(sku.toUpperCase());
        const precio = cPrecio ? (guessNumber(fila[cPrecio] ?? '') ?? 0) : 0;
        const stock = cStock ? Math.trunc(guessNumber(fila[cStock] ?? '') ?? 0) : 0;
        if (precio < 0 || stock < 0) return void problemas.push({ fila: num, motivo: `SKU ${sku}: precio o stock negativos` });
        normalizados.push({
          sku,
          nombre,
          categoria: (cCategoria ? fila[cCategoria] : '') ?? '',
          precio,
          costo: cCosto ? (guessNumber(fila[cCosto] ?? '') ?? 0) : 0,
          stock,
          stock_minimo: cMinimo ? Math.trunc(guessNumber(fila[cMinimo] ?? '') ?? 0) : 0,
          unidad: ((cUnidad ? fila[cUnidad] : '') || 'pieza') as string,
          proveedor: (cProveedor ? fila[cProveedor] : '') ?? '',
        });
      });

      const emp = empresaActivaDetalle(ctx);
      const modo = args.modo ?? 'agregar';
      const existentes = new Set(
        (ctx.db.prepare(`SELECT UPPER(sku) AS sku FROM inventario WHERE empresa_id = ?`).all(emp.id) as { sku: string }[]).map((f) => f.sku),
      );
      const nuevos = normalizados.filter((n) => !existentes.has(n.sku.toUpperCase()));
      const yaEstaban = normalizados.length - nuevos.length;
      const aEscribir = modo === 'reemplazar' ? normalizados : nuevos;

      if (args.dry_run) {
        return {
          empresa: { id: emp.id, nombre: emp.nombre },
          modo,
          dry_run: true,
          total_filas: tabla.rows.length,
          listos: normalizados.length,
          nuevos: nuevos.length,
          ya_existentes: yaEstaban,
          se_escribirian: aEscribir.length,
          problemas,
          ejemplo: aEscribir.slice(0, 3),
          mensaje: `Simulación: ${aEscribir.length} producto(s) se cargarían en «${emp.nombre}». Nada se escribió.`,
        };
      }

      if (problemas.length && aEscribir.length === 0) {
        throw new Error(`El CSV no tiene filas válidas (${problemas.length} problema(s)). Revisa el detalle en el reporte de dry_run.`);
      }
      const ahora = new Date().toISOString();
      const upsert = ctx.db.prepare(
        `INSERT INTO inventario (empresa_id, sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en)
         VALUES (@empresa_id, @sku, @nombre, @categoria, @precio, @costo, @stock, @stock_minimo, @unidad, @proveedor, @actualizado_en)
         ON CONFLICT(empresa_id, sku) DO UPDATE SET
           nombre = excluded.nombre, categoria = excluded.categoria, precio = excluded.precio, costo = excluded.costo,
           stock = excluded.stock, stock_minimo = excluded.stock_minimo, unidad = excluded.unidad,
           proveedor = excluded.proveedor, actualizado_en = excluded.actualizado_en`,
      );
      const cargar = ctx.db.transaction(() => {
        for (const n of aEscribir) upsert.run({ ...n, empresa_id: emp.id, actualizado_en: ahora });
      });
      cargar();
      log.info('inventario_importado', { empresa: emp.id, modo, cargados: aEscribir.length, empresa_nombre: emp.nombre });

      return {
        empresa: { id: emp.id, nombre: emp.nombre },
        modo,
        total_filas: tabla.rows.length,
        cargados: aEscribir.length,
        omitidos_por_ya_existir: modo === 'agregar' ? yaEstaban : 0,
        problemas,
        total_inventario: inventario().total(),
        mensaje: `${aEscribir.length} producto(s) cargados en «${emp.nombre}». El inventario de esa empresa tiene ahora ${inventario().total()} artículo(s).`,
      };
    },
  });

  push({
    module: 'negocio',
    name: 'pedido_crear',
    title: 'Crear pedido',
    description:
      'Crea un pedido para un cliente validando que exista stock suficiente y descontándolo del inventario. Genera folio consecutivo (P-####), calcula subtotal, IVA y total. El pedido nace en estado "borrador". Todo ocurre en una sola transacción: o se guarda completo, o no cambia nada.',
    destructive: true,
    inputSchema: {
      cliente: z.string().min(2).describe('Nombre del cliente.'),
      items: z
        .array(z.object({ sku: z.string(), cantidad: z.number().int().positive() }))
        .min(1)
        .describe('Lista de conceptos: SKU y cantidad. El precio se toma del inventario.'),
      notas: z.string().optional().describe('Notas para producción/entrega.'),
    },
    handler: async (args: { cliente: string; items: { sku: string; cantidad: number }[]; notas?: string }) => {
      const pedido = pedidos().crear({ ...args, ivaPct: ctx.config.ivaPct });
      log.info('pedido_creado', { folio: pedido.folio, cliente: pedido.cliente, total: pedido.total, empresa: empresaActiva(ctx) });
      return {
        ...pedido,
        empresa: { id: empresa().id, nombre: empresa().nombre },
        mensaje: `Pedido ${pedido.folio} creado en «${empresa().nombre}» y stock descontado.`,
      };
    },
  });

  push({
    module: 'negocio',
    name: 'pedido_consultar',
    title: 'Consultar pedidos',
    description:
      'Lista o busca pedidos por folio, cliente o estado. Devuelve el estado actual, totales e historial de cambios. Usa el folio exacto (ej. "P-0001") para ver un pedido completo.',
    readOnly: true,
    inputSchema: {
      folio: z.string().optional().describe('Folio exacto del pedido, ej. "P-0001".'),
      cliente: z.string().optional().describe('Filtro parcial por nombre de cliente.'),
      estado: z.enum(['borrador', 'confirmado', 'en_produccion', 'enviado', 'entregado', 'cancelado']).optional().describe('Filtrar por estado.'),
    },
    handler: async (args: { folio?: string; cliente?: string; estado?: EstadoPedido }) => {
      const repo = pedidos();
      const lista = repo.listar(args);
      if (args.folio && lista.length === 0) throw new Error(`No existe el pedido "${args.folio}".`);

      return {
        empresa: { id: empresa().id, nombre: empresa().nombre },
        total: lista.length,
        pedidos: lista.map((p) => ({
          folio: p.folio,
          cliente: p.cliente,
          estado: p.estado,
          total: p.total,
          items: p.items.length,
          creado_en: p.creado_en,
          actualizado_en: p.actualizado_en,
          historial: p.historial,
        })),
      };
    },
  });

  push({
    module: 'negocio',
    name: 'pedido_actualizar_estado',
    title: 'Cambiar estado de pedido',
    description:
      'Avanza un pedido en su ciclo de vida: borrador → confirmado → en_produccion → enviado → entregado (cancelable desde borrador, confirmado o en_produccion). Valida que la transición sea legal y guarda historial.',
    inputSchema: {
      folio: z.string().describe('Folio del pedido, ej. "P-0001".'),
      nuevo_estado: z.enum(['borrador', 'confirmado', 'en_produccion', 'enviado', 'entregado', 'cancelado']).describe('Estado destino.'),
      nota: z.string().optional().describe('Nota opcional para el historial.'),
    },
    handler: async (args: { folio: string; nuevo_estado: EstadoPedido; nota?: string }) =>
      pedidos().cambiarEstado({ folio: args.folio, nuevo: args.nuevo_estado, nota: args.nota }),
  });

  push({
    module: 'negocio',
    name: 'cotizacion_generar',
    title: 'Generar cotización',
    description:
      'Calcula una cotización completa lista para enviar al cliente: líneas, subtotal, descuento, IVA (16% por defecto), retenciones de ISR/IVA (para clientes que retienen, ej. personas morales) y total. No guarda nada; devuelve el desglose.',
    readOnly: true,
    inputSchema: {
      cliente: z.string().describe('Nombre o razón social del cliente.'),
      items: z
        .array(
          z.object({
            descripcion: z.string(),
            cantidad: z.number().positive(),
            precio_unitario: z.number().nonnegative(),
          }),
        )
        .min(1)
        .describe('Conceptos de la cotización.'),
      descuento_pct: z.number().min(0).max(100).optional().describe('Descuento global en porcentaje.'),
      retencion_isr_pct: z.number().min(0).max(100).optional().describe('Retención de ISR en % (ej. 1.25 honorarios).'),
      retencion_iva_pct: z.number().min(0).max(100).optional().describe('Retención de IVA en % sobre la base (ej. 10.6667 = 2/3 del IVA).'),
      iva_pct: z.number().min(0).max(100).optional().describe('Tasa de IVA; por defecto 16. Usa 0 para exentos.'),
    },
    handler: async (args: {
      cliente: string;
      items: { descripcion: string; cantidad: number; precio_unitario: number }[];
      descuento_pct?: number;
      retencion_isr_pct?: number;
      retencion_iva_pct?: number;
      iva_pct?: number;
    }) => {
      const totales = calcularCotizacion(
        args.items.map((i) => ({ descripcion: i.descripcion, cantidad: i.cantidad, precioUnitario: i.precio_unitario })),
        {
          descuentoPct: args.descuento_pct,
          ivaPct: args.iva_pct ?? ctx.config.ivaPct,
          retencionIsrPct: args.retencion_isr_pct,
          retencionIvaPct: args.retencion_iva_pct,
        },
      );
      return {
        cliente: args.cliente,
        fecha: new Date().toISOString().slice(0, 10),
        vigencia_dias: 30,
        ...totales,
        resumen: `Subtotal $${totales.subtotal} − descuento $${totales.descuento} + IVA (${totales.ivaPct}%) $${totales.iva} − retenciones $${round2(totales.retencionIsr + totales.retencionIva)} = Total $${totales.total}`,
      };
    },
  });

  push({
    module: 'negocio',
    name: 'calculadora_impuestos',
    title: 'Calculadora de impuestos (IVA)',
    description:
      'Agrega o desglosa IVA sobre un monto.\n\n- operacion "agregar": monto es base → calcula IVA y total a cobrar (opcionalmente con retenciones).\n- operacion "desglosar": monto ya incluye IVA → separa base e IVA (útil para tickets y facturas).',
    readOnly: true,
    inputSchema: {
      monto: z.number().positive().describe('Monto sobre el que se calcula.'),
      operacion: z.enum(['agregar', 'desglosar']).describe('"agregar" IVA sobre una base o "desglosar" desde un total.'),
      iva_pct: z.number().min(0).max(100).optional().describe('Tasa de IVA en % (por defecto 16).'),
      retenciones_pct: z.number().min(0).max(100).optional().describe('Retenciones totales en % (solo para "agregar").'),
    },
    handler: async (args: { monto: number; operacion: 'agregar' | 'desglosar'; iva_pct?: number; retenciones_pct?: number }) =>
      calcularImpuestos(args.monto, args.operacion, args.iva_pct ?? ctx.config.ivaPct, args.retenciones_pct ?? 0),
  });

  push({
    module: 'negocio',
    name: 'margen_utilidad',
    title: 'Margen y utilidad',
    description:
      'Calcula utilidad, margen sobre precio (%), markup sobre costo (%) y precio con IVA. Útil para definir precios de venta o evaluar rentabilidad de un producto.',
    readOnly: true,
    inputSchema: {
      precio: z.number().positive().describe('Precio de venta sin IVA.'),
      costo: z.number().nonnegative().describe('Costo del producto.'),
      iva_pct: z.number().min(0).max(100).optional().describe('IVA aplicable (por defecto 16).'),
    },
    handler: async (args: { precio: number; costo: number; iva_pct?: number }) =>
      calcularMargen(args.precio, args.costo, args.iva_pct ?? ctx.config.ivaPct),
  });

  push({
    module: 'negocio',
    name: 'punto_equilibrio',
    title: 'Punto de equilibrio',
    description:
      'Calcula cuántas unidades necesitas vender para cubrir costos fijos, dato el precio unitario y el costo variable unitario. Incluye ventas necesarias y margen de contribución.',
    readOnly: true,
    inputSchema: {
      costos_fijos: z.number().nonnegative().describe('Costos fijos del periodo (pesos).'),
      precio_unitario: z.number().positive().describe('Precio de venta unitario sin IVA.'),
      costo_variable_unitario: z.number().nonnegative().describe('Costo variable por unidad.'),
    },
    handler: async (args: { costos_fijos: number; precio_unitario: number; costo_variable_unitario: number }) =>
      puntoEquilibrio(args.costos_fijos, args.precio_unitario, args.costo_variable_unitario),
  });
}
