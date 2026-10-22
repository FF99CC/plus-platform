FROM node:24.22.0-alpine3.24

WORKDIR /workspace/frontend
COPY frontend/package.json ./package.json
RUN npm install --global "$(node -p "require('./package.json').packageManager")" \
    && pnpm config set store-dir /pnpm/store --global

COPY --chmod=755 ops/frontend-dev.sh /usr/local/bin/frontend-dev
EXPOSE 80
ENTRYPOINT ["/usr/local/bin/frontend-dev"]
