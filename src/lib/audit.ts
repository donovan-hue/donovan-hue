import fs from 'node:fs/promises';
import path from 'node:path';
import { log } from '../logger.js';

export type TipoEvento = 'tool_destructiva' | 'auth_fallo' | 'rate_limit' | 'clave_creada' | 'clave_revocada' | 'aprobacion_solicitada' | 'aprobacion_aprobada' | 'aprobacion_rechazada' | 'parche_probado' | 'parche_aplicado' | 'parche_revertido' | 'flujo_ejecutado';

export interface EventoAuditoria {
  t: string;
  tipo: TipoEvento;
  cliente: string;
  ip?: string;
  herramienta?: string;
  modulo?: string;
  ok?: boolean;
  duracion_ms?: number;
  error?: string;
  detalle?: string;
  /** Resumen truncado de los argumentos (nunca secretos completos). */
  args_resumen?: string;
}

const MAX_ARGS = 600;

export function resumirArgs(args: unknown): string {
  try {
    const texto = JSON.stringify(args ?? {});
    return texto.length > MAX_ARGS ? `${texto.slice(0, MAX_ARGS)}…` : texto;
  } catch {
    return '(argumentos no serializables)';
  }
}

/**
 * Bitácora de auditoría append-only en formato NDJSON. Una línea por evento;
 * nunca se reescribe el archivo (inmutable por diseño).
 */
export class Auditoria {
  constructor(private readonly archivo: string) {}

  get ruta(): string {
    return this.archivo;
  }

  async registrar(evento: Omit<EventoAuditoria, 't'> & { t?: string }): Promise<void> {
    const entrada: EventoAuditoria = { t: evento.t ?? new Date().toISOString(), ...evento } as EventoAuditoria;
    try {
      await fs.mkdir(path.dirname(this.archivo), { recursive: true });
      await fs.appendFile(this.archivo, `${JSON.stringify(entrada)}\n`, 'utf8');
    } catch (err: any) {
      // La auditoría nunca debe tumbar una operación, pero sí dejar rastro del fallo.
      log.error('auditoria_error', { error: err?.message ?? String(err), archivo: this.archivo });
    }
  }

  async leer(opciones: {
    tipo?: TipoEvento;
    herramienta?: string;
    cliente?: string;
    desde?: string;
    limite?: number;
  } = {}): Promise<EventoAuditoria[]> {
    let contenido: string;
    try {
      contenido = await fs.readFile(this.archivo, 'utf8');
    } catch (err: any) {
      if (err?.code === 'ENOENT') return [];
      throw err;
    }
    const eventos: EventoAuditoria[] = [];
    for (const linea of contenido.split('\n')) {
      if (!linea.trim()) continue;
      let e: EventoAuditoria;
      try {
        e = JSON.parse(linea) as EventoAuditoria;
      } catch {
        continue; // línea corrupta: se ignora en la lectura, el archivo no se toca
      }
      if (opciones.tipo && e.tipo !== opciones.tipo) continue;
      if (opciones.herramienta && e.herramienta !== opciones.herramienta) continue;
      if (opciones.cliente && !e.cliente.toLowerCase().includes(opciones.cliente.toLowerCase())) continue;
      if (opciones.desde && e.t < opciones.desde) continue;
      if (opciones.limite && eventos.length >= opciones.limite) break;
      eventos.push(e);
    }
    return eventos;
  }

  async resumen(): Promise<Record<string, number>> {
    const eventos = await this.leer();
    const conteo: Record<string, number> = {};
    for (const e of eventos) conteo[e.tipo] = (conteo[e.tipo] ?? 0) + 1;
    return conteo;
  }
}
