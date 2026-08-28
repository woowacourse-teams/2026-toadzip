FROM node:24.19.0-alpine AS builder

WORKDIR /workspace/frontend

COPY package.json package-lock.json ./
RUN npm ci

COPY . ./

ARG VITE_API_BASE_URL=""
ARG VITE_NAVER_MAPS_CLIENT_ID=""
RUN VITE_API_BASE_URL="${VITE_API_BASE_URL}" \
    VITE_NAVER_MAPS_CLIENT_ID="${VITE_NAVER_MAPS_CLIENT_ID}" \
    npm run build


FROM nginx:1.29.5-alpine

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder --chown=nginx:nginx /workspace/frontend/dist/ /usr/share/nginx/html/

USER nginx

EXPOSE 8080
