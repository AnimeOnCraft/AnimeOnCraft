FROM node:24.20.0-bookworm-slim AS base
RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*

FROM base AS build
WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@12.10.1 --activate
COPY . .
RUN pnpm install --frozen-lockfile
RUN pnpm --filter @animeoncraft/backend... build

FROM base AS runtime
ENV NODE_ENV=production
WORKDIR /workspace
COPY --from=build /workspace /workspace
USER node
CMD ["node", "apps/backend/dist/main.js"]
