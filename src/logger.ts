import type { LogLevelName } from './config.js';

const LEVELS: Record<LogLevelName, number> = { debug: 10, info: 20, warn: 30, error: 40 };

let currentLevel: number = LEVELS.info;

export function setLogLevel(level: LogLevelName): void {
  currentLevel = LEVELS[level] ?? LEVELS.info;
}

function emit(level: LogLevelName, msg: string, meta?: Record<string, unknown>): void {
  if (LEVELS[level] < currentLevel) return;
  const line = JSON.stringify({ t: new Date().toISOString(), level, msg, ...meta });
  // IMPORTANTE: los logs van a stderr. En modo stdio, stdout es exclusivo del protocolo MCP.
  process.stderr.write(`${line}\n`);
}

export const log = {
  debug: (msg: string, meta?: Record<string, unknown>) => emit('debug', msg, meta),
  info: (msg: string, meta?: Record<string, unknown>) => emit('info', msg, meta),
  warn: (msg: string, meta?: Record<string, unknown>) => emit('warn', msg, meta),
  error: (msg: string, meta?: Record<string, unknown>) => emit('error', msg, meta),
};
