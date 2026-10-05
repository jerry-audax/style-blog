FROM node:20-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci --legacy-peer-deps --no-audit --no-fund
COPY . .
ARG VITE_BLOG_OWNER_PHONE=19140838906
ENV VITE_BLOG_OWNER_PHONE=$VITE_BLOG_OWNER_PHONE
ARG VITE_BLOG_SITE_URL
ENV VITE_BLOG_SITE_URL=$VITE_BLOG_SITE_URL
RUN npm run build

FROM nginx:1.27-alpine

ENV BACKEND_URL=http://blog-server:8080
ENV NGINX_LISTEN_PORT=80
ENV NGINX_ENVSUBST_VARS='$BACKEND_URL $NGINX_LISTEN_PORT'
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --spider --quiet http://127.0.0.1/ || exit 1
