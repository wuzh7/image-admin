FROM node:24-alpine AS build

RUN corepack enable && corepack prepare pnpm@10.34.5 --activate
WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .
ARG VITE_APP_ENCRYPT=true
ENV VITE_APP_ENCRYPT=${VITE_APP_ENCRYPT}
RUN sed -i "s/^VITE_APP_ENCRYPT=.*/VITE_APP_ENCRYPT=${VITE_APP_ENCRYPT}/" .env.production \
    && grep -q "^VITE_APP_ENCRYPT=${VITE_APP_ENCRYPT}$" .env.production \
    && pnpm build

FROM nginx:1.29-alpine
COPY deploy/local/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
