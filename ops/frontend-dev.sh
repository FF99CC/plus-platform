#!/bin/sh
set -eu

pnpm install --frozen-lockfile
exec pnpm dev --host 0.0.0.0 --port 80 --strictPort
