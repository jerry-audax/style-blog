# Production deployment

The production stack builds five images: the Spring Boot API, the publication worker, the public site, the admin site, and an internal Redis service. PostgreSQL is intentionally external and is never initialized by Compose.

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

## Upgrade and rollback

Build a new image tag, start it with the same `deploy/.env`, and check `docker compose ... ps` plus the public site before removing old images. Do not run a database migration automatically during an application upgrade. Back up PostgreSQL and the `blog-publication` volume before a rollback.

