FROM node:24.22.0-alpine3.24 AS build

WORKDIR /workspace/frontend
COPY frontend/package.json ./
RUN npm install --global "$(node -p "require('./package.json').packageManager")"
COPY frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
# Copy installs into the image; the persistent store is only a download cache.
RUN --mount=type=cache,id=em-plus-local-pnpm-node24-alpine,target=/pnpm/store,sharing=locked \
    pnpm install --frozen-lockfile --store-dir=/pnpm/store --package-import-method=copy
COPY frontend/ ./
RUN VITE_APP_ENCRYPT=false VITE_APP_RSA_PUBLIC_KEY='' VITE_APP_RSA_PRIVATE_KEY='' pnpm build:prod

FROM nginx:1.29.3-alpine3.22
COPY ops/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist/ /usr/share/nginx/html/
EXPOSE 80
