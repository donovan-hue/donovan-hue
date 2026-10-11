import fs from 'node:fs/promises';
import path from 'node:path';

/**
 * Almacén JSON con escrituras atómicas (tmp + rename) y cola de
 * lectura-modificación-escritura para evitar condiciones de carrera.
 */
export class JsonStore<T> {
  private queue: Promise<unknown> = Promise.resolve();

  constructor(
    private readonly file: string,
    private readonly initial: T,
  ) {}

  get ruta(): string {
    return this.file;
  }

  async read(): Promise<T> {
    try {
      const raw = await fs.readFile(this.file, 'utf8');
      return JSON.parse(raw) as T;
    } catch (err: any) {
      if (err?.code === 'ENOENT') {
        await this.write(this.initial);
        return structuredClone(this.initial);
      }
      throw new Error(`No se pudo leer ${this.file}: ${err?.message}`);
    }
  }

  async write(data: T): Promise<void> {
    await fs.mkdir(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.${process.pid}.${Date.now()}.tmp`;
    await fs.writeFile(tmp, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
    await fs.rename(tmp, this.file);
  }

  /** Ejecuta `fn` con el estado actual de forma serializada y lo persiste al final. */
  async update<R>(fn: (data: T) => R | Promise<R>): Promise<R> {
    const run = this.queue.then(async () => {
      const data = await this.read();
      const result = await fn(data);
      await this.write(data);
      return result;
    });
    this.queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run as Promise<R>;
  }
}

/** Lee un archivo de texto con límite de tamaño para evitar volcar archivos enormes a un LLM. */
export async function readTextFile(file: string, maxBytes = 2_000_000): Promise<string> {
  const stat = await fs.stat(file).catch(() => null);
  if (!stat) throw new Error(`Archivo no encontrado: ${file}`);
  if (!stat.isFile()) throw new Error(`No es un archivo: ${file}`);
  if (stat.size > maxBytes) {
    throw new Error(
      `Archivo demasiado grande (${(stat.size / 1e6).toFixed(1)} MB). Límite: ${(maxBytes / 1e6).toFixed(0)} MB.`,
    );
  }
  return fs.readFile(file, 'utf8');
}

/**
 * Resuelve una ruta relativa contra el directorio de datos y verifica que quede
 * dentro de las áreas permitidas (data dir o cwd). Evita path traversal.
 */
export function resolveAllowedPath(dataDir: string, p: string, extraRoots: string[] = []): string {
  const target = path.resolve(path.isAbsolute(p) ? p : path.join(dataDir, p));
  const roots = [dataDir, ...extraRoots];
  const ok = roots.some((root) => {
    const r = path.resolve(root);
    return target === r || target.startsWith(`${r}${path.sep}`);
  });
  if (!ok) {
    throw new Error(`Ruta fuera del área permitida: ${p}. Permitido dentro de: ${roots.join(', ')}`);
  }
  return target;
}
