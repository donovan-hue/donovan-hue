import Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import { INVENTARIO_SEED, PEDIDOS_SEED, PLANTILLAS_SEED } from '../data-seed.js';
import { log } from '../logger.js';

export type DB = Database.Database;

export const ESQUEMA = `
CREATE TABLE IF NOT EXISTS empresas (
  id         TEXT PRIMARY KEY,
  nombre     TEXT NOT NULL,
  rfc        TEXT NOT NULL DEFAULT '',
  regimen    TEXT NOT NULL DEFAULT '',
  cp         TEXT NOT NULL DEFAULT '',
  series     TEXT NOT NULL DEFAULT 'A',
  color      TEXT NOT NULL DEFAULT '',
  creada_en  TEXT NOT NULL,
  archivada  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inventario (
  empresa_id     TEXT NOT NULL DEFAULT 'EMP-principal',
  sku            TEXT NOT NULL,
  nombre         TEXT NOT NULL,
  categoria      TEXT NOT NULL DEFAULT '',
  precio         REAL NOT NULL DEFAULT 0,
  costo          REAL NOT NULL DEFAULT 0,
  stock          INTEGER NOT NULL DEFAULT 0,
  stock_minimo   INTEGER NOT NULL DEFAULT 0,
  unidad         TEXT NOT NULL DEFAULT 'pieza',
  proveedor      TEXT NOT NULL DEFAULT '',
  actualizado_en TEXT NOT NULL,
  PRIMARY KEY (empresa_id, sku)
);

CREATE TABLE IF NOT EXISTS pedidos (
  folio          TEXT NOT NULL,
  empresa_id     TEXT NOT NULL DEFAULT 'EMP-principal',
  cliente        TEXT NOT NULL,
  estado         TEXT NOT NULL,
  subtotal       REAL NOT NULL DEFAULT 0,
  iva            REAL NOT NULL DEFAULT 0,
  total          REAL NOT NULL DEFAULT 0,
  notas          TEXT NOT NULL DEFAULT '',
  creado_en      TEXT NOT NULL,
  actualizado_en TEXT NOT NULL,
  -- Los folios se numeran POR EMPRESA: cada negocio empieza en P-0001.
  PRIMARY KEY (empresa_id, folio)
);

CREATE TABLE IF NOT EXISTS pedido_items (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  empresa_id      TEXT NOT NULL DEFAULT 'EMP-principal',
  folio           TEXT NOT NULL,
  sku             TEXT NOT NULL,
  descripcion     TEXT NOT NULL,
  cantidad        INTEGER NOT NULL,
  precio_unitario REAL NOT NULL,
  importe         REAL NOT NULL,
  FOREIGN KEY (empresa_id, folio) REFERENCES pedidos(empresa_id, folio) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS pedido_historial (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  empresa_id TEXT NOT NULL DEFAULT 'EMP-principal',
  folio      TEXT NOT NULL,
  estado     TEXT NOT NULL,
  fecha      TEXT NOT NULL,
  nota       TEXT,
  FOREIGN KEY (empresa_id, folio) REFERENCES pedidos(empresa_id, folio) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plantillas (
  empresa_id  TEXT NOT NULL DEFAULT 'EMP-principal',
  nombre      TEXT NOT NULL,
  descripcion TEXT NOT NULL DEFAULT '',
  variables   TEXT NOT NULL,
  contenido   TEXT NOT NULL,
  creado_en   TEXT NOT NULL,
  PRIMARY KEY (empresa_id, nombre)
);

CREATE TABLE IF NOT EXISTS meta (
  clave TEXT PRIMARY KEY,
  valor TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_keys (
  id         TEXT PRIMARY KEY,
  empresa_id TEXT,
  nombre     TEXT NOT NULL UNIQUE,
  hash       TEXT NOT NULL,
  scopes     TEXT NOT NULL,
  creada     TEXT NOT NULL,
  expira     TEXT,
  activa     INTEGER NOT NULL DEFAULT 1,
  ultimo_uso TEXT,
  usos       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS aprobaciones (
  id             TEXT PRIMARY KEY,
  tipo           TEXT NOT NULL,
  titulo         TEXT NOT NULL,
  detalle        TEXT NOT NULL DEFAULT '',
  estado         TEXT NOT NULL DEFAULT 'pendiente',
  solicitada_en  TEXT NOT NULL,
  solicitada_por TEXT NOT NULL DEFAULT 'local',
  resuelta_en    TEXT,
  resuelta_por   TEXT,
  motivo         TEXT,
  huella         TEXT
);

CREATE INDEX IF NOT EXISTS idx_aprob_estado ON aprobaciones(estado);

CREATE TABLE IF NOT EXISTS parches (
  id            TEXT PRIMARY KEY,
  proyecto      TEXT NOT NULL,
  raiz          TEXT NOT NULL,
  titulo        TEXT NOT NULL,
  justificacion TEXT NOT NULL DEFAULT '',
  diff          TEXT NOT NULL,
  cambios       TEXT NOT NULL,
  huella        TEXT NOT NULL,
  estado        TEXT NOT NULL DEFAULT 'propuesto',
  creado_en     TEXT NOT NULL,
  creado_por    TEXT NOT NULL DEFAULT 'local',
  intentos      INTEGER NOT NULL DEFAULT 0,
  prueba        TEXT,
  aplicado_en   TEXT,
  respaldo      TEXT,
  revertido_en  TEXT
);

CREATE INDEX IF NOT EXISTS idx_parches_estado ON parches(estado);

CREATE TABLE IF NOT EXISTS costos_modelo (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  tarea          TEXT NOT NULL,
  tipo           TEXT NOT NULL,
  modelo         TEXT NOT NULL,
  exito          INTEGER NOT NULL,
  coste          REAL NOT NULL DEFAULT 0,
  duracion_ms    INTEGER,
  evidencia      TEXT,
  registrado_en  TEXT NOT NULL,
  registrado_por TEXT NOT NULL DEFAULT 'local'
);

CREATE INDEX IF NOT EXISTS idx_costos_modelo ON costos_modelo(modelo, tipo);
CREATE INDEX IF NOT EXISTS idx_pedidos_cliente ON pedidos(cliente);
CREATE INDEX IF NOT EXISTS idx_pedidos_estado  ON pedidos(estado);
CREATE INDEX IF NOT EXISTS idx_items_folio     ON pedido_items(folio);
CREATE INDEX IF NOT EXISTS idx_hist_folio      ON pedido_historial(folio);
`;

/** Abre (o crea) la base SQLite en modo WAL y aplica el esquema. */
export function abrirDb(ruta: string): DB {
  if (ruta !== ':memory:') fs.mkdirSync(path.dirname(ruta), { recursive: true });
  const db = new Database(ruta);
  db.pragma('journal_mode = WAL');
  db.pragma('busy_timeout = 5000');
  db.pragma('foreign_keys = ON');
  db.exec(ESQUEMA);
  migrarMultiEmpresa(db);
  return db;
}

/** ¿La tabla ya tiene esta columna? */
function tieneColumna(db: DB, tabla: string, columna: string): boolean {
  const filas = db.prepare(`PRAGMA table_info(${tabla})`).all() as { name: string }[];
  return filas.some((f) => f.name === columna);
}

export const EMPRESA_POR_DEFECTO = 'EMP-principal';

/**
 * Pasa una base anterior (sin multi-empresa) al modelo de espacios:
 * crea la empresa por defecto y le asigna TODO lo que ya existía, sin perder datos.
 * `inventario` y `plantillas` se reconstruyen con clave compuesta (empresa + nombre/sku)
 * porque dos empresas distintas pueden tener el mismo SKU o la misma plantilla.
 */
export function migrarMultiEmpresa(db: DB): { migrada: boolean; detalles: string[] } {
  const detalles: string[] = [];
  const yaExistia = tieneColumna(db, 'inventario', 'empresa_id');

  const cuantas = (db.prepare(`SELECT COUNT(*) AS n FROM empresas`).get() as { n: number }).n;
  if (cuantas === 0) {
    const nombre = leerMeta(db, 'empresa_nombre') ?? 'Mi negocio';
    db.prepare(
      `INSERT INTO empresas (id, nombre, rfc, regimen, cp, series, color, creada_en, archivada)
       VALUES (?, ?, '', '', '', 'A', '#4ec3e0', ?, 0)`,
    ).run(EMPRESA_POR_DEFECTO, nombre, new Date().toISOString());
    detalles.push(`empresa por defecto creada: ${EMPRESA_POR_DEFECTO} («${nombre}»)`);
  }

  const reconstruir = db.transaction(() => {
    if (!tieneColumna(db, 'inventario', 'empresa_id')) {
      db.exec(`
        CREATE TABLE inventario_me (
          empresa_id     TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}',
          sku            TEXT NOT NULL,
          nombre         TEXT NOT NULL,
          categoria      TEXT NOT NULL DEFAULT '',
          precio         REAL NOT NULL DEFAULT 0,
          costo          REAL NOT NULL DEFAULT 0,
          stock          INTEGER NOT NULL DEFAULT 0,
          stock_minimo   INTEGER NOT NULL DEFAULT 0,
          unidad         TEXT NOT NULL DEFAULT 'pieza',
          proveedor      TEXT NOT NULL DEFAULT '',
          actualizado_en TEXT NOT NULL,
          PRIMARY KEY (empresa_id, sku)
        );
        INSERT INTO inventario_me (empresa_id, sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en)
          SELECT '${EMPRESA_POR_DEFECTO}', sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en FROM inventario;
        DROP TABLE inventario;
        ALTER TABLE inventario_me RENAME TO inventario;
        CREATE INDEX IF NOT EXISTS idx_inventario_empresa ON inventario(empresa_id);
      `);
      detalles.push('inventario migrado a clave (empresa, sku)');
    }

    if (!tieneColumna(db, 'plantillas', 'empresa_id')) {
      db.exec(`
        CREATE TABLE plantillas_me (
          empresa_id  TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}',
          nombre      TEXT NOT NULL,
          descripcion TEXT NOT NULL DEFAULT '',
          variables   TEXT NOT NULL,
          contenido   TEXT NOT NULL,
          creado_en   TEXT NOT NULL,
          PRIMARY KEY (empresa_id, nombre)
        );
        INSERT INTO plantillas_me (empresa_id, nombre, descripcion, variables, contenido, creado_en)
          SELECT '${EMPRESA_POR_DEFECTO}', nombre, descripcion, variables, contenido, creado_en FROM plantillas;
        DROP TABLE plantillas;
        ALTER TABLE plantillas_me RENAME TO plantillas;
        CREATE INDEX IF NOT EXISTS idx_plantillas_empresa ON plantillas(empresa_id);
      `);
      detalles.push('plantillas migradas a clave (empresa, nombre)');
    }

    if (!tieneColumna(db, 'pedidos', 'empresa_id')) {
      db.exec(`ALTER TABLE pedidos ADD COLUMN empresa_id TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}'`);
      db.exec(`CREATE INDEX IF NOT EXISTS idx_pedidos_empresa ON pedidos(empresa_id)`);
      detalles.push('pedidos etiquetados con la empresa por defecto');
    }

    if (!tieneColumna(db, 'api_keys', 'empresa_id')) {
      db.exec(`ALTER TABLE api_keys ADD COLUMN empresa_id TEXT`);
      detalles.push('api_keys listas para atarse a una empresa');
    }

    // Pedidos: folio único POR empresa (antes era único global y la segunda
    // empresa arrancaba en un número ya usado). Se reconstruye sin perder nada.
    const ddlPedidos = String((db.prepare(`SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'pedidos'`).get() as { sql?: string } | undefined)?.sql ?? '');
    if (!/PRIMARY\s+KEY\s*\(\s*empresa_id\s*,\s*folio\s*\)/i.test(ddlPedidos)) {
      db.exec(`
        ALTER TABLE pedido_items     RENAME TO pedido_items_viejo;
        ALTER TABLE pedido_historial RENAME TO pedido_historial_viejo;
        ALTER TABLE pedidos          RENAME TO pedidos_viejo;

        CREATE TABLE pedidos (
          folio          TEXT NOT NULL,
          empresa_id     TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}',
          cliente        TEXT NOT NULL,
          estado         TEXT NOT NULL,
          subtotal       REAL NOT NULL DEFAULT 0,
          iva            REAL NOT NULL DEFAULT 0,
          total          REAL NOT NULL DEFAULT 0,
          notas          TEXT NOT NULL DEFAULT '',
          creado_en      TEXT NOT NULL,
          actualizado_en TEXT NOT NULL,
          PRIMARY KEY (empresa_id, folio)
        );
        CREATE TABLE pedido_items (
          id              INTEGER PRIMARY KEY AUTOINCREMENT,
          empresa_id      TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}',
          folio           TEXT NOT NULL,
          sku             TEXT NOT NULL,
          descripcion     TEXT NOT NULL,
          cantidad        INTEGER NOT NULL,
          precio_unitario REAL NOT NULL,
          importe         REAL NOT NULL,
          FOREIGN KEY (empresa_id, folio) REFERENCES pedidos(empresa_id, folio) ON DELETE CASCADE
        );
        CREATE TABLE pedido_historial (
          id         INTEGER PRIMARY KEY AUTOINCREMENT,
          empresa_id TEXT NOT NULL DEFAULT '${EMPRESA_POR_DEFECTO}',
          folio      TEXT NOT NULL,
          estado     TEXT NOT NULL,
          fecha      TEXT NOT NULL,
          nota       TEXT,
          FOREIGN KEY (empresa_id, folio) REFERENCES pedidos(empresa_id, folio) ON DELETE CASCADE
        );

        INSERT INTO pedidos (folio, empresa_id, cliente, estado, subtotal, iva, total, notas, creado_en, actualizado_en)
          SELECT folio, COALESCE(empresa_id, '${EMPRESA_POR_DEFECTO}'), cliente, estado, subtotal, iva, total, notas, creado_en, actualizado_en
          FROM pedidos_viejo;
        INSERT INTO pedido_items (id, empresa_id, folio, sku, descripcion, cantidad, precio_unitario, importe)
          SELECT i.id, COALESCE(p.empresa_id, '${EMPRESA_POR_DEFECTO}'), i.folio, i.sku, i.descripcion, i.cantidad, i.precio_unitario, i.importe
          FROM pedido_items_viejo i LEFT JOIN pedidos_viejo p ON p.folio = i.folio;
        INSERT INTO pedido_historial (id, empresa_id, folio, estado, fecha, nota)
          SELECT h.id, COALESCE(p.empresa_id, '${EMPRESA_POR_DEFECTO}'), h.folio, h.estado, h.fecha, h.nota
          FROM pedido_historial_viejo h LEFT JOIN pedidos_viejo p ON p.folio = h.folio;

        DROP TABLE pedido_items_viejo;
        DROP TABLE pedido_historial_viejo;
        DROP TABLE pedidos_viejo;
      `);
      detalles.push('pedidos reconstruidos: folio único por empresa (items e historial incluidos)');
    }
  });
  reconstruir();

  // Índices al final: en una base vieja las columnas no existían hasta ahora.
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_inventario_empresa ON inventario(empresa_id);
    CREATE INDEX IF NOT EXISTS idx_pedidos_empresa    ON pedidos(empresa_id);
    CREATE INDEX IF NOT EXISTS idx_plantillas_empresa ON plantillas(empresa_id);
    CREATE INDEX IF NOT EXISTS idx_pedidos_cliente    ON pedidos(cliente);
    CREATE INDEX IF NOT EXISTS idx_pedidos_estado     ON pedidos(estado);
    CREATE INDEX IF NOT EXISTS idx_items_folio        ON pedido_items(folio);
    CREATE INDEX IF NOT EXISTS idx_items_empresa      ON pedido_items(empresa_id, folio);
    CREATE INDEX IF NOT EXISTS idx_hist_folio         ON pedido_historial(folio);
    CREATE INDEX IF NOT EXISTS idx_hist_empresa       ON pedido_historial(empresa_id, folio);
  `);

  for (const d of detalles) log.info('multi_empresa', { detalle: d });
  return { migrada: !yaExistia, detalles };
}

function tablaVacia(db: DB, tabla: string): boolean {
  const fila = db.prepare(`SELECT COUNT(*) AS n FROM ${tabla}`).get() as { n: number };
  return fila.n === 0;
}

/**
 * Migra los archivos JSON de la versión anterior (fase 1) a SQLite.
 * Solo actúa cuando la tabla destino está vacía y el JSON existe; los JSON
 * no se borran (quedan como respaldo) pero dejan de usarse.
 */
export function migrarDesdeJson(db: DB, dataDir: string): string[] {
  const detalles: string[] = [];
  const leerJson = (archivo: string): unknown | null => {
    const ruta = path.join(dataDir, archivo);
    if (!fs.existsSync(ruta)) return null;
    try {
      return JSON.parse(fs.readFileSync(ruta, 'utf8'));
    } catch (err: any) {
      log.warn('migracion_json_ilegible', { archivo: ruta, error: err?.message });
      return null;
    }
  };

  const migrar = db.transaction(() => {
    // ── inventario.json → inventario ────────────────────────────────────────
    if (tablaVacia(db, 'inventario')) {
      const data = leerJson('inventario.json') as { items?: any[] } | null;
      if (data?.items?.length) {
        const ins = db.prepare(
          `INSERT OR IGNORE INTO inventario (sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en)
           VALUES (@sku, @nombre, @categoria, @precio, @costo, @stock, @stock_minimo, @unidad, @proveedor, @actualizado_en)`,
        );
        for (const it of data.items) ins.run(it);
        detalles.push(`inventario: ${data.items.length} artículos migrados de inventario.json`);
      }
    }

    // ── pedidos.json → pedidos / pedido_items / pedido_historial ────────────
    if (tablaVacia(db, 'pedidos')) {
      const data = leerJson('pedidos.json') as { consecutivo?: number; pedidos?: any[] } | null;
      if (data?.pedidos?.length) {
        const insPedido = db.prepare(
          `INSERT OR IGNORE INTO pedidos (folio, cliente, estado, subtotal, iva, total, notas, creado_en, actualizado_en)
           VALUES (@folio, @cliente, @estado, @subtotal, @iva, @total, @notas, @creado_en, @actualizado_en)`,
        );
        const insItem = db.prepare(
          `INSERT INTO pedido_items (folio, sku, descripcion, cantidad, precio_unitario, importe)
           VALUES (?, ?, ?, ?, ?, ?)`,
        );
        const insHist = db.prepare(`INSERT INTO pedido_historial (folio, estado, fecha, nota) VALUES (?, ?, ?, ?)`);
        for (const p of data.pedidos) {
          insPedido.run({
            folio: p.folio,
            cliente: p.cliente,
            estado: p.estado,
            subtotal: p.subtotal,
            iva: p.iva,
            total: p.total,
            notas: p.notas ?? '',
            creado_en: p.creado_en,
            actualizado_en: p.actualizado_en,
          });
          for (const it of p.items ?? []) insItem.run(p.folio, it.sku, it.descripcion, it.cantidad, it.precio_unitario, it.importe);
          for (const h of p.historial ?? []) insHist.run(p.folio, h.estado, h.fecha, h.nota ?? null);
        }
        if (data.consecutivo) db.prepare(`INSERT OR REPLACE INTO meta (clave, valor) VALUES ('consecutivo_pedidos', ?)`).run(String(data.consecutivo));
        detalles.push(`pedidos: ${data.pedidos.length} pedidos migrados de pedidos.json`);
      }
    }

    // ── plantillas.json → plantillas ────────────────────────────────────────
    if (tablaVacia(db, 'plantillas')) {
      const data = leerJson('plantillas.json') as { plantillas?: any[] } | null;
      if (data?.plantillas?.length) {
        const ins = db.prepare(
          `INSERT OR IGNORE INTO plantillas (nombre, descripcion, variables, contenido, creado_en)
           VALUES (?, ?, ?, ?, ?)`,
        );
        for (const p of data.plantillas) ins.run(p.nombre, p.descripcion ?? '', JSON.stringify(p.variables ?? []), p.contenido, p.creado_en);
        detalles.push(`plantillas: ${data.plantillas.length} plantillas migradas de plantillas.json`);
      }
    }
  });

  try {
    migrar();
  } catch (err: any) {
    log.warn('migracion_json_fallo', { error: err?.message });
    return [`La migración desde JSON falló: ${err?.message}`];
  }
  for (const d of detalles) log.info('migracion_json', { detalle: d });
  return detalles;
}

/** Inserta los datos de ejemplo en una base vacía (instalación nueva). */
export function sembrarSiVacio(db: DB): void {
  const sembrar = db.transaction(() => {
    if (tablaVacia(db, 'inventario')) {
      const ins = db.prepare(
        `INSERT INTO inventario (sku, nombre, categoria, precio, costo, stock, stock_minimo, unidad, proveedor, actualizado_en)
         VALUES (@sku, @nombre, @categoria, @precio, @costo, @stock, @stock_minimo, @unidad, @proveedor, @actualizado_en)`,
      );
      for (const it of INVENTARIO_SEED.items) ins.run(it);
    }
    if (tablaVacia(db, 'pedidos')) {
      const insPedido = db.prepare(
        `INSERT INTO pedidos (folio, cliente, estado, subtotal, iva, total, notas, creado_en, actualizado_en)
         VALUES (@folio, @cliente, @estado, @subtotal, @iva, @total, @notas, @creado_en, @actualizado_en)`,
      );
      const insItem = db.prepare(
        `INSERT INTO pedido_items (folio, sku, descripcion, cantidad, precio_unitario, importe) VALUES (?, ?, ?, ?, ?, ?)`,
      );
      const insHist = db.prepare(`INSERT INTO pedido_historial (folio, estado, fecha, nota) VALUES (?, ?, ?, ?)`);
      for (const p of PEDIDOS_SEED.pedidos) {
        insPedido.run(p);
        for (const it of p.items) insItem.run(p.folio, it.sku, it.descripcion, it.cantidad, it.precio_unitario, it.importe);
        for (const h of p.historial) insHist.run(p.folio, h.estado, h.fecha, h.nota ?? null);
      }
      db.prepare(`INSERT OR REPLACE INTO meta (clave, valor) VALUES ('consecutivo_pedidos', ?)`).run(String(PEDIDOS_SEED.consecutivo));
    }
    if (tablaVacia(db, 'plantillas')) {
      const ins = db.prepare(
        `INSERT INTO plantillas (nombre, descripcion, variables, contenido, creado_en) VALUES (?, ?, ?, ?, ?)`,
      );
      for (const p of PLANTILLAS_SEED.plantillas) ins.run(p.nombre, p.descripcion, JSON.stringify(p.variables), p.contenido, p.creado_en);
    }
  });
  sembrar();
}

export function leerMeta(db: DB, clave: string): string | null {
  const fila = db.prepare(`SELECT valor FROM meta WHERE clave = ?`).get(clave) as { valor: string } | undefined;
  return fila?.valor ?? null;
}

export function escribirMeta(db: DB, clave: string, valor: string): void {
  db.prepare(`INSERT OR REPLACE INTO meta (clave, valor) VALUES (?, ?)`).run(clave, valor);
}
