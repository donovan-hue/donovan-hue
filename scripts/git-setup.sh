#!/usr/bin/env bash
# Restaura la configuración de git que NO sobrevive entre sesiones del workspace
# (.git/config está excluido de los snapshots) y comprueba el acceso al remoto.
#
# Uso:
#   ./scripts/git-setup.sh                 # aplica remoto + llave si existen
#   ./scripts/git-setup.sh --check         # solo informa
set -uo pipefail
cd "$(dirname "$0")/.."

REMOTE_URL="git@github.com:donovan-hue/donovan-hue.git"
KEY="/home/user/.cache/ssh/hifi_deploy"

if [ "${1:-}" = "--check" ]; then
  echo "remoto : $(git remote get-url origin 2>/dev/null || echo 'NO CONFIGURADO')"
  echo "llave  : $([ -f "$KEY" ] && echo 'presente' || echo 'AUSENTE (regenerar con ssh-keygen)')"
  echo "commits: $(git rev-list --count HEAD 2>/dev/null || echo 0)"
  exit 0
fi

git config user.name  >/dev/null 2>&1 || git config user.name  "HiFi Player"
git config user.email >/dev/null 2>&1 || git config user.email "dev@hifiplayer.local"
git config core.autocrlf false

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
fi

if [ -f "$KEY" ]; then
  git config core.sshCommand "ssh -i $KEY -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
  echo "llave SSH configurada: $KEY"
else
  echo "AVISO: falta la llave $KEY; el push por SSH fallará hasta regenerarla."
fi

echo "remoto: $(git remote get-url origin)"
git log --oneline | head -3
