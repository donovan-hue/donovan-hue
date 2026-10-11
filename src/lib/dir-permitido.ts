/**
 * Resolución de directorios autorizados (compartido por taller, centinela,
 * parche y orquesta). Ninguna herramienta debe poder leer ni escribir fuera de
 * ALLOWED_GIT_ROOTS.
 */
import path from 'node:path';
import type { ServerCtx } from '../context.js';

export function resolverDirectorio(ctx: ServerCtx, dir?: string): string {
  const candidato = path.resolve(dir ?? ctx.config.allowedGitRoots[0] ?? process.cwd());
  const permitido = ctx.config.allowedGitRoots.some(
    (root) => candidato === root || candidato.startsWith(`${root}${path.sep}`),
  );
  if (!permitido) {
    throw new Error(
      `Directorio fuera de las rutas permitidas: ${candidato}. Configura ALLOWED_GIT_ROOTS con las rutas autorizadas.`,
    );
  }
  return candidato;
}

/** ¿La ruta cae dentro de alguna raíz autorizada? (para validar destinos de escritura) */
export function esRutaPermitida(ctx: ServerCtx, ruta: string): boolean {
  const absoluta = path.resolve(ruta);
  return ctx.config.allowedGitRoots.some(
    (root) => absoluta === root || absoluta.startsWith(`${root}${path.sep}`),
  );
}
