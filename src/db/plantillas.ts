import type { DB } from './index.js';
import { EMPRESA_POR_DEFECTO } from './index.js';
import { variablesDePlantilla } from '../lib/text.js';

export interface Plantilla {
  nombre: string;
  descripcion: string;
  variables: string[];
  contenido: string;
  creado_en: string;
}

export interface PlantillasData {
  version: number;
  plantillas: Plantilla[];
}

interface FilaPlantilla {
  nombre: string;
  descripcion: string;
  variables: string;
  contenido: string;
  creado_en: string;
}

export class PlantillasRepo {
  constructor(
    private readonly db: DB,
    private readonly empresaId: string = EMPRESA_POR_DEFECTO,
  ) {}

  listar(query?: string): Plantilla[] {
    const filas = (
      query
        ? this.db
            .prepare(`SELECT * FROM plantillas WHERE empresa_id = ? AND (nombre LIKE ? OR descripcion LIKE ?) ORDER BY nombre`)
            .all(this.empresaId, `%${query}%`, `%${query}%`)
        : this.db.prepare(`SELECT * FROM plantillas WHERE empresa_id = ? ORDER BY nombre`).all(this.empresaId)
    ) as FilaPlantilla[];
    return filas.map((f) => ({ ...f, variables: JSON.parse(f.variables) as string[] }));
  }

  obtener(nombre: string): Plantilla | undefined {
    const fila = this.db.prepare(`SELECT * FROM plantillas WHERE empresa_id = ? AND UPPER(nombre) = UPPER(?)`).get(this.empresaId, nombre) as
      | FilaPlantilla
      | undefined;
    return fila ? { ...fila, variables: JSON.parse(fila.variables) as string[] } : undefined;
  }

  /** Crea o reemplaza una plantilla. Devuelve si reemplazó una existente. */
  guardar(datos: { nombre: string; contenido: string; descripcion?: string }): {
    reemplazada: boolean;
    total: number;
    variables: string[];
  } {
    const variables = variablesDePlantilla(datos.contenido);
    if (variables.length === 0) {
      throw new Error(
        'La plantilla no contiene ninguna variable {{...}}. Agrega al menos una o usa documento_leer para guardar texto fijo.',
      );
    }
    return this.db.transaction(() => {
      const existente = this.obtener(datos.nombre);
      this.db
        .prepare(
          `INSERT INTO plantillas (empresa_id, nombre, descripcion, variables, contenido, creado_en)
           VALUES (@empresa_id, @nombre, @descripcion, @variables, @contenido, @creado_en)
           ON CONFLICT(empresa_id, nombre) DO UPDATE SET
             descripcion = excluded.descripcion,
             variables   = excluded.variables,
             contenido   = excluded.contenido`,
        )
        .run({
          empresa_id: this.empresaId,
          nombre: datos.nombre,
          descripcion: datos.descripcion ?? existente?.descripcion ?? '',
          variables: JSON.stringify(variables),
          contenido: datos.contenido,
          creado_en: existente?.creado_en ?? new Date().toISOString(),
        });
      const total = (this.db.prepare(`SELECT COUNT(*) AS n FROM plantillas WHERE empresa_id = ?`).get(this.empresaId) as { n: number }).n;
      return { reemplazada: Boolean(existente), total, variables };
    })();
  }
}
