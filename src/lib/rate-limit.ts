/**
 * Limitador de tasa por cubetas de fichas (token bucket), en memoria del proceso.
 * Nota de escala: para varias instancias detrás de un balanceador se necesita un
 * almacén compartido (Redis); con una sola instancia esto es suficiente y exacto.
 */

export interface ResultadoLimite {
  permitido: boolean;
  /** Unidades restantes tras consumir (redondeadas hacia abajo). */
  restantes: number;
  limite: number;
  /** Segundos recomendados para reintentar (solo cuando permitido=false). */
  retryAfterS: number;
}

interface Cubeta {
  fichas: number;
  ultima: number;
}

export class LimitadorTasas {
  private cubetas = new Map<string, Cubeta>();
  private readonly tasaPorSegundo: number;
  private readonly capacidad: number;

  constructor(
    rpm: number,
    burst: number,
  ) {
    if (rpm <= 0) throw new Error('El límite por minuto (rpm) debe ser mayor que cero.');
    this.tasaPorSegundo = rpm / 60;
    this.capacidad = Math.max(burst, 1);
  }

  /** Consume una ficha de la cubeta identificada por `clave`. */
  consumir(clave: string, ahoraMs = Date.now()): ResultadoLimite {
    let cubeta = this.cubetas.get(clave);
    if (!cubeta) {
      cubeta = { fichas: this.capacidad, ultima: ahoraMs };
      this.cubetas.set(clave, cubeta);
    }
    const transcurrido = Math.max(0, (ahoraMs - cubeta.ultima) / 1000);
    cubeta.fichas = Math.min(this.capacidad, cubeta.fichas + transcurrido * this.tasaPorSegundo);
    cubeta.ultima = ahoraMs;

    if (cubeta.fichas >= 1) {
      cubeta.fichas -= 1;
      this.purgar(ahoraMs);
      return { permitido: true, restantes: Math.floor(cubeta.fichas), limite: this.capacidad, retryAfterS: 0 };
    }
    const retryAfterS = Math.max(1, Math.ceil((1 - cubeta.fichas) / this.tasaPorSegundo));
    return { permitido: false, restantes: 0, limite: this.capacidad, retryAfterS };
  }

  /** Estado sin consumir ficha (para headers informativos). */
  estado(clave: string, ahoraMs = Date.now()): { restantes: number; limite: number } {
    const c = this.cubetas.get(clave);
    if (!c) return { restantes: this.capacidad, limite: this.capacidad };
    const transcurrido = Math.max(0, (ahoraMs - c.ultima) / 1000);
    return { restantes: Math.floor(Math.min(this.capacidad, c.fichas + transcurrido * this.tasaPorSegundo)), limite: this.capacidad };
  }

  /** Elimina cubetas inactivas para evitar crecimiento indefinido de memoria. */
  private purgar(ahoraMs: number, maxEntradas = 10_000, inactividadMs = 10 * 60 * 1000): void {
    if (this.cubetas.size <= maxEntradas) return;
    for (const [clave, c] of this.cubetas) {
      if (ahoraMs - c.ultima > inactividadMs) this.cubetas.delete(clave);
    }
  }

  get tamano(): number {
    return this.cubetas.size;
  }
}
