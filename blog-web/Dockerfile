FROM node:22-alpine AS builder

WORKDIR /app
ARG BLOG_SITE_URL
ENV BLOG_SITE_URL=${BLOG_SITE_URL}
COPY package*.json ./
COPY app/package.json ./app/package.json
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM builder AS publisher
ENV PUBLICATION_PORT=8081
ENV PUBLICATION_STATE_DIRECTORY=/var/lib/blog-publication
RUN mkdir -p /var/lib/blog-publication && chown node:node /var/lib/blog-publication
USER node
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8081/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "publication/interfaces/server.cjs"]

FROM nginx:1.27-alpine

ENV BACKEND_URL=http://blog-server:8080
ENV PUBLICATION_URL=http://blog-publisher:8081
ENV NGINX_ENVSUBST_VARS='$BACKEND_URL $PUBLICATION_URL'
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --spider --quiet http://127.0.0.1/ || exit 1
