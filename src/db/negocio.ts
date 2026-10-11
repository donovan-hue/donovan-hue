import type { DB } from './index.js';
import { EMPRESA_POR_DEFECTO, leerMeta, escribirMeta } from './index.js';
import { aplicarMovimiento, validarTransicion, type EstadoPedido } from '../lib/finance.js';

export interface ItemInventario {
  sku: string;
  nombre: string;
  categoria: string;
  precio: number;
  costo: number;
  stock: number;
  stock_minimo: number;
  unidad: string;
  proveedor: string;
  actualizado_en: string;
}

/** Forma de los datos semilla (data-seed.ts). */
export interface InventarioData {
  version: number;
  items: ItemInventario[];
}

export interface PedidoItem {
  sku: string;
  descripcion: string;
  cantidad: number;
  precio_unitario: number;
  importe: number;
}

export interface EntradaHistorial {
  estado: EstadoPedido;
  fecha: string;
  nota?: string | null;
}

export interface Pedido {
  folio: string;
  cliente: string;
  estado: EstadoPedido;
  items: PedidoItem[];
  subtotal: number;
  iva: number;
  total: number;
  notas: string;
  creado_en: string;
  actualizado_en: string;
  historial: EntradaHistorial[];
}

export interface PedidosData {
  version: number;
  consecutivo: number;
  pedidos: Pedido[];
}

const round2 = (n: number) => Number(n.toFixed(2));

export interface FiltrosInventario {
  sku?: string;
  query?: string;
  categoria?: string;
  bajo_minimo?: boolean;
}

export class InventarioRepo {
  /** `empresaId` nunca se omite en las herramientas: sin él se usa la empresa por defecto. */
  constructor(
    private readonly db: DB,
    private readonly empresaId: string = EMPRESA_POR_DEFECTO,
  ) {}

  private readonly columnas = `sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en`;

  total(): number {
    return (this.db.prepare(`SELECT COUNT(*) AS n FROM inventario WHERE empresa_id = ?`).get(this.empresaId) as { n: number }).n;
  }

  /** Búsqueda con los mismos filtros que la herramienta MCP. */
  listar(filtros: FiltrosInventario = {}): ItemInventario[] {
    const condiciones: string[] = ['empresa_id = @empresa'];
    const parametros: Record<string, unknown> = { empresa: this.empresaId };

    if (filtros.sku) {
      condiciones.push('UPPER(sku) = UPPER(@sku)');
      parametros.sku = filtros.sku;
    }
    if (filtros.query) {
      condiciones.push('(nombre LIKE @q OR sku LIKE @q OR proveedor LIKE @q OR categoria LIKE @q OR unidad LIKE @q)');
      parametros.q = `%${filtros.query}%`;
    }
    if (filtros.categoria) {
      condiciones.push('LOWER(categoria) = LOWER(@categoria)');
      parametros.categoria = filtros.categoria;
    }
    if (filtros.bajo_minimo) {
      condiciones.push('stock <= stock_minimo');
    }

    const where = `WHERE ${condiciones.join(' AND ')}`;
    return this.db
      .prepare(`SELECT ${this.columnas} FROM inventario ${where} ORDER BY sku`)
      .all(parametros) as ItemInventario[];
  }

  obtener(sku: string): ItemInventario | undefined {
    return this.db
      .prepare(`SELECT ${this.columnas} FROM inventario WHERE empresa_id = ? AND UPPER(sku) = UPPER(?)`)
      .get(this.empresaId, sku) as ItemInventario | undefined;
  }

  /** Aplica un movimiento (entrada/salida/ajuste) en una transacción. */
  aplicarMovimiento(datos: { sku: string; tipo: 'entrada' | 'salida' | 'ajuste'; cantidad: number; motivo: string }): {
    item: ItemInventario;
    stockAnterior: number;
  } {
    return this.db.transaction(() => {
      const item = this.obtener(datos.sku);
      if (!item) throw new Error(`No existe un producto con SKU "${datos.sku}" en esta empresa.`);
      const stockAnterior = item.stock;
      const stockNuevo = aplicarMovimiento(item.stock, datos.tipo, datos.cantidad, item.nombre);
      this.db
        .prepare(`UPDATE inventario SET stock = ?, actualizado_en = ? WHERE empresa_id = ? AND sku = ?`)
        .run(stockNuevo, new Date().toISOString(), this.empresaId, item.sku);
      return { item: { ...item, stock: stockNuevo }, stockAnterior };
    })();
  }
}

export interface FiltrosPedidos {
  folio?: string;
  cliente?: string;
  estado?: EstadoPedido;
}

export class PedidosRepo {
  constructor(
    private readonly db: DB,
    private readonly empresaId: string = EMPRESA_POR_DEFECTO,
  ) {}

  private leerPedido(folio: string): Pedido | undefined {
    const p = this.db.prepare(`SELECT * FROM pedidos WHERE empresa_id = ? AND UPPER(folio) = UPPER(?)`).get(this.empresaId, folio) as
      | Omit<Pedido, 'items' | 'historial'>
      | undefined;
    if (!p) return undefined;
    const items = this.db
      .prepare(`SELECT sku, descripcion, cantidad, precio_unitario, importe FROM pedido_items WHERE empresa_id = ? AND folio = ? ORDER BY id`)
      .all(this.empresaId, p.folio) as PedidoItem[];
    const historial = this.db
      .prepare(`SELECT estado, fecha, nota FROM pedido_historial WHERE empresa_id = ? AND folio = ? ORDER BY id`)
      .all(this.empresaId, p.folio) as EntradaHistorial[];
    return { ...p, items, historial };
  }

  listar(filtros: FiltrosPedidos = {}): Pedido[] {
    const condiciones: string[] = ['empresa_id = @empresa'];
    const parametros: Record<string, unknown> = { empresa: this.empresaId };
    if (filtros.folio) {
      condiciones.push('UPPER(folio) = UPPER(@folio)');
      parametros.folio = filtros.folio;
    }
    if (filtros.cliente) {
      condiciones.push('LOWER(cliente) LIKE LOWER(@cliente)');
      parametros.cliente = `%${filtros.cliente}%`;
    }
    if (filtros.estado) {
      condiciones.push('estado = @estado');
      parametros.estado = filtros.estado;
    }
    const where = `WHERE ${condiciones.join(' AND ')}`;
    const folios = this.db
      .prepare(`SELECT folio FROM pedidos ${where} ORDER BY folio`)
      .all(parametros) as { folio: string }[];
    return folios.map((f) => this.leerPedido(f.folio)!);
  }

  /**
   * Crea un pedido validando y descontando stock en UNA transacción:
   * o se guarda todo (pedido + stock), o no cambia nada.
   */
  crear(datos: { cliente: string; items: { sku: string; cantidad: number }[]; notas?: string; ivaPct: number }): Pedido {
    return this.db.transaction(() => {
      const inv = new InventarioRepo(this.db, this.empresaId);
      const conceptos: PedidoItem[] = [];

      for (const solicitado of datos.items) {
        const item = inv.obtener(solicitado.sku);
        if (!item) throw new Error(`No existe un producto con SKU "${solicitado.sku}" (pedido no creado).`);
        if (item.stock < solicitado.cantidad) {
          throw new Error(
            `Stock insuficiente de "${item.nombre}" (${item.sku}): disponible ${item.stock}, solicitado ${solicitado.cantidad}. Pedido no creado.`,
          );
        }
        conceptos.push({
          sku: item.sku,
          descripcion: item.nombre,
          cantidad: solicitado.cantidad,
          precio_unitario: item.precio,
          importe: round2(item.precio * solicitado.cantidad),
        });
      }

      const subtotal = round2(conceptos.reduce((a, c) => a + c.importe, 0));
      const iva = round2(subtotal * (datos.ivaPct / 100));
      const ahora = new Date().toISOString();

      // Numeración POR EMPRESA: cada negocio lleva su propio P-0001, P-0002…
      const claveConsecutivo = `consecutivo_pedidos:${this.empresaId}`;
      const guardado = leerMeta(this.db, claveConsecutivo);
      const mayorLocal = (this.db
        .prepare(`SELECT MAX(CAST(SUBSTR(folio, 3) AS INTEGER)) AS n FROM pedidos WHERE empresa_id = ? AND folio LIKE 'P-%'`)
        .get(this.empresaId) as { n: number | null }).n ?? 0;
      const consecutivo = Math.max(Number(guardado ?? 0), mayorLocal) + 1;
      const folio = `P-${String(consecutivo).padStart(4, '0')}`;
      escribirMeta(this.db, claveConsecutivo, String(consecutivo));
      escribirMeta(this.db, 'consecutivo_pedidos', String(consecutivo)); // compatibilidad

      this.db
        .prepare(
          `INSERT INTO pedidos (folio, empresa_id, cliente, estado, subtotal, iva, total, notas, creado_en, actualizado_en)
           VALUES (?, ?, ?, 'borrador', ?, ?, ?, ?, ?, ?)`,
        )
        .run(folio, this.empresaId, datos.cliente, subtotal, iva, round2(subtotal + iva), datos.notas ?? '', ahora, ahora);

      const insItem = this.db.prepare(
        `INSERT INTO pedido_items (empresa_id, folio, sku, descripcion, cantidad, precio_unitario, importe) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      );
      for (const c of conceptos) insItem.run(this.empresaId, folio, c.sku, c.descripcion, c.cantidad, c.precio_unitario, c.importe);

      this.db.prepare(`INSERT INTO pedido_historial (empresa_id, folio, estado, fecha, nota) VALUES (?, ?, 'borrador', ?, NULL)`).run(this.empresaId, folio, ahora);

      const updStock = this.db.prepare(`UPDATE inventario SET stock = stock - ?, actualizado_en = ? WHERE empresa_id = ? AND sku = ?`);
      for (const c of conceptos) updStock.run(c.cantidad, ahora, this.empresaId, c.sku);

      return this.leerPedido(folio)!;
    })();
  }

  cambiarEstado(datos: { folio: string; nuevo: EstadoPedido; nota?: string }): {
    folio: string;
    anterior: EstadoPedido;
    nuevo: EstadoPedido;
    cliente: string;
    historial: EntradaHistorial[];
  } {
    return this.db.transaction(() => {
      const p = this.leerPedido(datos.folio);
      if (!p) throw new Error(`No existe el pedido "${datos.folio}" en esta empresa.`);
      validarTransicion(p.estado, datos.nuevo);

      const ahora = new Date().toISOString();
      this.db.prepare(`UPDATE pedidos SET estado = ?, actualizado_en = ? WHERE empresa_id = ? AND folio = ?`).run(datos.nuevo, ahora, this.empresaId, p.folio);
      this.db
        .prepare(`INSERT INTO pedido_historial (empresa_id, folio, estado, fecha, nota) VALUES (?, ?, ?, ?, ?)`)
        .run(this.empresaId, p.folio, datos.nuevo, ahora, datos.nota ?? null);

      return {
        folio: p.folio,
        anterior: p.estado,
        nuevo: datos.nuevo,
        cliente: p.cliente,
        historial: this.leerPedido(p.folio)!.historial,
      };
    })();
  }
}
