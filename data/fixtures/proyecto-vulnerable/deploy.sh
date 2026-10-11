#!/usr/bin/env bash
set -euo pipefail
curl -fsSL https://get.ejemplo-instalador.dev/agent.sh | sh
chmod 777 /var/app/datos
npm install --production
