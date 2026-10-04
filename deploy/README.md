# Production deployment

The production stack builds three application images: the Spring Boot API, the public Vue site, and the admin Vue site. The public image also runs the internal publication worker beside Nginx. Redis is an internal runtime service, and PostgreSQL is intentionally external and is never initialized by Compose.

## First deployment

1. Copy this repository to the server and enter its root directory.
2. Copy `deploy/.env.example` to `deploy/.env` and replace every placeholder. Keep `deploy/.env` outside Git.
3. If `blog_system` is a new empty database, apply the structure once with a PostgreSQL client:

   ```bash
   psql "$DB_URL" -v ON_ERROR_STOP=1 -f blog-server/src/main/resources/db/schema-postgresql.sql
   ```

   The application has `spring.sql.init.mode=never`; it does not run this script during startup.

4. Validate and start the stack:

   ```bash
   docker compose --env-file deploy/.env -f docker-compose.prod.yml config --quiet
   docker compose --env-file deploy/.env -f docker-compose.prod.yml build
   docker compose --env-file deploy/.env -f docker-compose.prod.yml up -d
   docker compose --env-file deploy/.env -f docker-compose.prod.yml ps
   ```

The public site is on `BLOG_HTTP_PORT` and the admin site is on `BLOG_ADMIN_PORT`. Only those two ports are published. The blog API and publication worker stay on the internal Compose network. Set `DB_URL` to a hostname reachable from a container; on Linux Docker hosts, `host.docker.internal` is provided by the Compose file.

The supplied server uses its existing host Nginx on port 80 and proxies all public requests to `127.0.0.1:8088`. See `deploy/nginx-blog-system.conf.example` for the equivalent configuration. The admin site remains on `BLOG_ADMIN_PORT`.

If the server blocks TCP between containers on a Docker bridge network, use the host-gateway override. It publishes the API, publication worker, and Redis on the host, then routes service traffic through `host.docker.internal`:

```bash
docker compose --env-file deploy/.env \
  -f docker-compose.prod.yml -f docker-compose.host-gateway.yml config --quiet
docker compose --env-file deploy/.env \
  -f docker-compose.prod.yml -f docker-compose.host-gateway.yml up -d
```

The override defaults to host ports `8080` (API), `8081` (publication worker inside the public image), and `6380` (Redis). Change them with `BLOG_SERVER_PORT`, `BLOG_PUBLICATION_PORT`, and `BLOG_REDIS_PORT` in `deploy/.env` in environments that need different ports.

## Upgrade and rollback

Build a new image tag, start it with the same `deploy/.env`, and check `docker compose ... ps` plus the public site before removing old images. Do not run a database migration automatically during an application upgrade. Back up PostgreSQL and the `blog-publication` volume before a rollback.

