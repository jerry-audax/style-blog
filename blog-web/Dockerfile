FROM node:22-alpine AS builder

WORKDIR /app
ARG BLOG_SITE_URL
ENV BLOG_SITE_URL=${BLOG_SITE_URL}
COPY package*.json ./
COPY app/package.json ./app/package.json
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM builder

RUN apk add --no-cache nginx gettext \
    && rm -f /etc/nginx/http.d/default.conf /etc/nginx/conf.d/default.conf \
    && mkdir -p /var/lib/blog-publication /run/nginx \
    && chown -R node:node /var/lib/blog-publication

ENV BACKEND_URL=http://blog-server:8080
ENV PUBLICATION_URL=http://127.0.0.1:8081
ENV NGINX_LISTEN_PORT=80
ENV NGINX_ENVSUBST_VARS='$BACKEND_URL $PUBLICATION_URL $NGINX_LISTEN_PORT'
ENV PUBLICATION_PORT=8081
ENV PUBLICATION_STATE_DIRECTORY=/var/lib/blog-publication
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.sh /usr/local/bin/blog-web-entrypoint
RUN chmod +x /usr/local/bin/blog-web-entrypoint

EXPOSE 80 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+process.env.NGINX_LISTEN_PORT+'/').then(r=>process.exit(r.status<500?0:1)).catch(()=>process.exit(1))"
CMD ["/usr/local/bin/blog-web-entrypoint"]