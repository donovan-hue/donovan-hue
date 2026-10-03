#!/usr/bin/env bash
# Restaura la configuración local de git que NO se conserva entre sesiones
# (la carpeta .git/config está excluida de los snapshots del workspace).
#
# Uso:
#   ./scripts/git-setup.sh "Tu Nombre" tu@correo.com [url-del-remoto]
set -euo pipefail

NAME="${1:-}"
EMAIL="${2:-}"
REMOTE="${3:-}"

cd "$(dirname "$0")/.."

if [ -n "$NAME" ]; then git config user.name "$NAME"; fi
if [ -n "$EMAIL" ]; then git config user.email "$EMAIL"; fi
git config core.autocrlf false
git config pull.rebase true
git config fetch.prune true

if [ -n "$REMOTE" ]; then
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$REMOTE"
  else
    git remote add origin "$REMOTE"
  fi
  echo "Remoto 'origin' -> $REMOTE"
fi

echo "Identidad git: $(git config user.name 2>/dev/null || echo 'SIN CONFIGURAR') <$(git config user.email 2>/dev/null || echo 'SIN CONFIGURAR')>"
git remote -v || true
