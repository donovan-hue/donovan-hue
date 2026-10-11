export interface ToolInfoPolitica {
  name: string;
  module: string;
  readOnly: boolean;
}

/**
 * Convierte una lista de scopes en un predicado que decide qué herramientas se
 * exponen a un cliente:
 *
 *   ["*"]             → todas
 *   ["negocio"]       → todas las de negocio
 *   ["negocio:ro"]    → solo las de negocio marcadas como readOnly
 *   ["analisis","devops:ro"] → combinaciones
 *
 * El módulo `core` (mcp_suite_info) siempre se permite: solo describe el servidor.
 */
export function politicaDesdeScopes(scopes: string[]): (tool: ToolInfoPolitica) => boolean {
  const completos = new Set<string>();
  const soloLectura = new Set<string>();

  for (const scope of scopes) {
    if (scope === '*') {
      completos.add('*');
      continue;
    }
    const [modulo, restriccion] = scope.split(':');
    if (restriccion === 'ro') soloLectura.add(modulo!);
    else completos.add(modulo!);
  }

  return (tool) => {
    if (tool.module === 'core') return true;
    if (completos.has('*') || completos.has(tool.module)) return true;
    if (soloLectura.has(tool.module) && tool.readOnly) return true;
    return false;
  };
}

/** Resume para la respuesta de mcp_suite_info y el panel. */
export function describirScopes(scopes: string[]): string[] {
  if (scopes.includes('*')) return ['acceso total a todos los módulos'];
  return scopes.map((s) => {
    const [modulo, restriccion] = s.split(':');
    return restriccion === 'ro' ? `${modulo} (solo lectura)` : `${modulo} (completo)`;
  });
}
