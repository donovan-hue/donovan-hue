/**
 * Registro de métricas mínimo en formato Prometheus (sin dependencias).
 * Contadores e histogramas con etiquetas; render() produce texto expositivo.
 */

type Etiquetas = Record<string, string | number>;

function formatearEtiquetas(etiquetas?: Etiquetas): string {
  if (!etiquetas || Object.keys(etiquetas).length === 0) return '';
  const partes = Object.entries(etiquetas)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}="${String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`);
  return `{${partes.join(',')}}`;
}

export const BUCKETS_POR_DEFECTO = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10];

export class Metricas {
  private contadores = new Map<string, number>();
  private histogramas = new Map<string, { count: number; sum: number; cubos: Map<number, number>; buckets: number[] }>();
  private readonly inicio = Date.now();

  inc(nombre: string, etiquetas?: Etiquetas, valor = 1): void {
    const clave = `${nombre}${formatearEtiquetas(etiquetas)}`;
    this.contadores.set(clave, (this.contadores.get(clave) ?? 0) + valor);
  }

  observe(nombre: string, valor: number, etiquetas?: Etiquetas, buckets: number[] = BUCKETS_POR_DEFECTO): void {
    const clave = `${nombre}${formatearEtiquetas(etiquetas)}`;
    let h = this.histogramas.get(clave);
    if (!h) {
      h = { count: 0, sum: 0, cubos: new Map(buckets.map((b) => [b, 0])), buckets };
      this.histogramas.set(clave, h);
    }
    h.count++;
    h.sum += valor;
    for (const b of h.buckets) {
      if (valor <= b) h.cubos.set(b, (h.cubos.get(b) ?? 0) + 1);
    }
  }

  /** Valor actual de un contador (para pruebas y diagnósticos). */
  obtener(nombre: string, etiquetas?: Etiquetas): number {
    return this.contadores.get(`${nombre}${formatearEtiquetas(etiquetas)}`) ?? 0;
  }

  uptimeSegundos(): number {
    return Math.round((Date.now() - this.inicio) / 1000);
  }

  /** Texto en formato de exposición de Prometheus. */
  render(): string {
    const lineas: string[] = [];
    const contadores = [...this.contadores.entries()].sort(([a], [b]) => a.localeCompare(b));
    const basesContadores = [...new Set(contadores.map(([k]) => k.split('{')[0]!))];

    for (const base of basesContadores) {
      lineas.push(`# TYPE ${base} counter`);
      for (const [clave, valor] of contadores) {
        if (clave.split('{')[0] === base) lineas.push(`${clave} ${valor}`);
      }
    }

    const histogramas = [...this.histogramas.entries()].sort(([a], [b]) => a.localeCompare(b));
    const basesHist = [...new Set(histogramas.map(([k]) => k.split('{')[0]!))];

    for (const base of basesHist) {
      lineas.push(`# TYPE ${base} histogram`);
      for (const [clave, h] of histogramas) {
        if (clave.split('{')[0] !== base) continue;
        const etiquetasBase = clave.slice(base.length); // "{...}" o ""
        const interior = etiquetasBase.replace(/^\{(.*)\}$/, '$1');
        for (const b of h.buckets) {
          const etiquetasLe = interior ? `{${interior},le="${b}"}` : `{le="${b}"}`;
          lineas.push(`${base}_bucket${etiquetasLe} ${h.cubos.get(b) ?? 0}`);
        }
        const etiquetasInf = interior ? `{${interior},le="+Inf"}` : `{le="+Inf"}`;
        lineas.push(`${base}_bucket${etiquetasInf} ${h.count}`);
        lineas.push(`${base}_sum${etiquetasBase} ${Number(h.sum.toFixed(6))}`);
        lineas.push(`${base}_count${etiquetasBase} ${h.count}`);
      }
    }

    lineas.push('# TYPE mcp_suite_uptime_segundos gauge');
    lineas.push(`mcp_suite_uptime_segundos ${this.uptimeSegundos()}`);
    return `${lineas.join('\n')}\n`;
  }
}
