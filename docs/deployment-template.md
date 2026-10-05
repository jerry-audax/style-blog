# Blog System 部署模板

本文记录当前博客系统的标准线上部署方式。后续发布按这份流程执行，敏感配置只放在服务器的 `deploy/.env` 或本机未跟踪的环境文件中。

## 1. 组成

| 服务 | 镜像 | 容器端口 | 作用 |
| --- | --- | ---: | --- |
| `blog-server` | Spring Boot JAR | 8080 | API、登录、音乐、惊喜、管理接口 |
| `blog-web` | Vue 构建产物 + Hexo + 内置发布服务 | 80、8081 | 公开 Vue 页面、Hexo 博客、文章发布 |
| `blog-admin` | Vue 构建产物 + Nginx | 80 | 管理后台 |
| `redis` | Redis 7 | 6380 | 会话、缓存 |
| PostgreSQL | 服务器已有实例 | 5432 | 外置数据库，不由 Compose 创建 |

线上机器由宿主机 Nginx 监听 80，反代到 Compose 暴露的前端端口。推荐 `blog-web` 映射为 `127.0.0.1:8088:80`，`blog-admin` 映射为 `127.0.0.1:5174:80`，避免应用容器直接占用宿主机 80。

## 2. 首次部署

```bash
git clone <repository-url> blog-system
cd blog-system
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，填入数据库、JWT、发布令牌、图床令牌等值

docker compose --env-file deploy/.env -f docker-compose.prod.yml config --quiet
psql "$DB_URL" -v ON_ERROR_STOP=1 -f blog-server/src/main/resources/db/schema-postgresql.sql
docker compose --env-file deploy/.env -f docker-compose.prod.yml build
docker compose --env-file deploy/.env -f docker-compose.prod.yml up -d
docker compose --env-file deploy/.env -f docker-compose.prod.yml ps
```

数据库脚本只在新库初始化时执行一次。升级应用时不要自动重新执行 schema，也不要切换到旧的 MySQL 脚本。

## 3. 日常发布

```bash
git status --short
git diff --check
git push origin <branch>

# 构建三个应用镜像
docker build -t blog-system-server:<tag> ./blog-server
docker build --build-arg BLOG_SITE_URL="$BLOG_SITE_URL" -t blog-system-web:<tag> ./blog-web
docker build --build-arg VITE_BLOG_OWNER_PHONE="$BLOG_OWNER_PHONE" --build-arg VITE_BLOG_SITE_URL="$BLOG_SITE_URL" -t blog-system-admin:<tag> ./blog-admin

# 线上重建
docker compose --env-file deploy/.env -f docker-compose.prod.yml up -d --no-build --force-recreate blog-server blog-web blog-admin
docker compose --env-file deploy/.env -f docker-compose.prod.yml ps
docker compose --env-file deploy/.env -f docker-compose.prod.yml logs --tail=100 blog-server
```

提交前检查 `.env`、令牌、密码、私钥、数据库导出文件和本地媒体状态文件没有进入 Git。若离线传输镜像，使用 `docker save`/`docker load`，加载后再执行 Compose 重建。回滚时切回上一镜像 tag，重新 `up -d --force-recreate`；数据库和状态目录先备份。

## 4. 宿主机 Nginx

```nginx
server {
    listen 80;
    server_name example.com;

    location /api/ {
        proxy_pass http://127.0.0.1:8088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

`blog-web` 内部 Nginx 再把 `/api/` 转发到 `blog-server:8080`，把文章发布请求转给本容器的 `8081`。管理端可使用独立域名或 `127.0.0.1:5174`。

## 5. 敏感配置

生产 `deploy/.env` 至少需要：

```dotenv
BLOG_OWNER_PHONE=...
BLOG_SITE_URL=https://example.com/blog
DB_URL=jdbc:postgresql://host.docker.internal:5432/blog_system
DB_USERNAME=...
DB_PASSWORD=...
JWT_SECRET=...
PUBLICATION_API_TOKEN=...
IMGBED_BASE_URL=https://cloudflare-imgbed-6of.pages.dev
IMGBED_API_TOKEN=...
MUSIC_PLAYLIST_ID=939817038
```

令牌只通过 Compose 环境变量进入容器。不要把令牌写进源码、Dockerfile、构建参数日志、Markdown 文档或提交记录。前端构建参数只放公开站点地址和必要的公开标识。

## 6. 持久化数据

后端必须挂载音乐和惊喜状态目录：

```yaml
volumes:
  - ./data/music-docker:/var/lib/blog-music
environment:
  MUSIC_STATE_DIRECTORY: /var/lib/blog-music
  SURPRISE_STATE_DIRECTORY: /var/lib/blog-music
```

目录中的 `music-catalog.json`、`public-playlist.json` 和 `surprise-videos.json` 属于运行状态。发布前备份，发布时只同步公开状态文件；不要同步包含图床凭据的本地 `.config` 文件。

## 7. 发布检查清单

- `docker compose ... config --quiet` 成功。
- `blog-server`、`blog-web`、`blog-admin`、`redis` 都处于运行状态。
- `GET /api/content/article/list` 返回 200。
- `GET /api/music/hosted-playlist` 返回 200 且歌曲数量大于 0。
- `GET /api/surprise/random` 返回 200 且包含视频 URL。
- `/blog/`、`/blog/music/`、`/blog/surprise/` 返回 200。
- 管理端登录页可以打开，API 401/403 状态码正常。
- 查看后端日志无数据库连接、图床令牌或状态文件解析错误。
- 确认敏感文件没有被 `git status` 或 `git diff` 发现。

## 8. 本次部署的关键经验

1. 两个前端要分别构建：`blog-web` 同时包含 Vue 和 Hexo，`blog-admin` 单独构建管理端。
2. 后端必须以 Spring Boot JAR 镜像运行，不能把后端当静态网页发布。
3. 宿主机 Nginx 负责 80 端口和域名反代，容器内部使用服务名通信。
4. 音乐和惊喜依赖状态目录；只部署镜像而不挂载状态文件会导致页面能打开、接口返回 503。
5. 图床令牌为空时音乐接口会主动返回 503；上线前检查容器内变量是否已设置，但不要输出变量值。
6. 部署问题按“页面 → API → 容器日志 → 环境变量 → 持久化目录 → 外部服务”顺序定位。
