FROM node:24.19.0-alpine AS builder

WORKDIR /workspace/frontend

COPY package.json package-lock.json ./
RUN npm ci

COPY . ./

ARG VITE_API_BASE_URL=""
ARG VITE_NAVER_MAPS_CLIENT_ID=""
ARG VITE_GA_MEASUREMENT_ID=""
ARG VITE_POSTHOG_KEY=""
ARG VITE_POSTHOG_HOST="https://us.i.posthog.com"
ARG VITE_ANALYTICS_ENV=""
ARG VITE_POSTHOG_LOCAL_ENABLED="false"
RUN VITE_API_BASE_URL="${VITE_API_BASE_URL}" \
    VITE_NAVER_MAPS_CLIENT_ID="${VITE_NAVER_MAPS_CLIENT_ID}" \
    VITE_GA_MEASUREMENT_ID="${VITE_GA_MEASUREMENT_ID}" \
    VITE_POSTHOG_KEY="${VITE_POSTHOG_KEY}" \
    VITE_POSTHOG_HOST="${VITE_POSTHOG_HOST}" \
    VITE_ANALYTICS_ENV="${VITE_ANALYTICS_ENV}" \
    VITE_POSTHOG_LOCAL_ENABLED="${VITE_POSTHOG_LOCAL_ENABLED}" \
    npm run build


FROM nginx:1.29.5-alpine

COPY nginx/http.conf /etc/nginx/nginx.conf
COPY nginx/ /etc/nginx/toadzip/
COPY --from=builder --chown=nginx:nginx /workspace/frontend/dist/ /usr/share/nginx/html/

USER nginx

EXPOSE 8080 8443
