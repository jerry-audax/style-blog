# Blog System

个人技术博客：Hexo + 安知鱼原主题负责公开阅读，Vue 负责文章动态展示与独立管理后台，Spring Boot 提供业务 API。后端采用 DDD 模块化单体；独立 AI 服务保留，当前不启用。

文档基线更新：2026-10-16。先看 [文档索引](docs/README.md)、[产品定位](docs/product-positioning-and-requirements.md)、[技术开发基线](docs/technical-development-baseline.md) 和 [六模块架构实施记录](docs/backend-layering-implementation-2026-10-16.md)。

## 项目结构

```text
blog-system/
├── blog-server/     六模块 Maven 聚合工程，单一 Spring Boot 服务，默认端口 8080
├── blog-ai/         独立 AI/RAG 服务，暂不启用
├── blog-web/        Hexo 技术博客 + Vue 只读动态组件
├── blog-admin/      Vue 管理后台，仅单博主密码登录
├── blog-server/tools/           本地启动、数据库迁移和音频验证工具
├── docs/            当前六模块架构、功能契约、实施证据与待办
├── Jenkinsfile      测试、打包、Docker 镜像构建与推送
├── docker-compose.prod.yml
└── docker-compose.host-gateway.yml
```

技术栈：Java 17、Spring Boot 3.3.2、MyBatis-Plus 3.5.7、Sa-Token 1.38.0、PostgreSQL、Redis；公开端 Hexo 8.1.2 / hexo-theme-anzhiyu 1.7.0 / Vue 3 / Vite 7，管理端 Vue 3 / Element Plus / Tiptap。公开端要求 Node.js >=22.12。

## 当前产品边界

- 公开入口 `/` 跳转 `/blog/`；文章、归档、分类、标签、搜索、音乐和关于页使用原安知鱼主题，不保留独立作品集。
- 访客只读，不提供博客登录、注册、资料编辑、发表评论或点赞写入。文章动态组件读取计数与已审核历史评论。
- 管理端只允许 `BLOG_OWNER_PHONE` 指定的已有博主账号使用原密码登录。资料、头像、密码编辑统一在 `/account`；其他历史账号的数据不删除。
- 后台 Tiptap 输出 HTML，数据库历史字段 `contentMd` 仍沿用原名；旧 Markdown 文章继续兼容。
- 文章发布闭环为“后台保存 → 发布进程自动校验 → 隔离生成 Hexo → 切换公开页面”。管理端展示状态并可重试；日常内容更新不再手动构建镜像。详见 [自动发布](docs/automatic-publication.md)。外部 CDN 必须避免缓存旧 HTML。
- 图片由 Cloudflare ImgBed 的 Telegram 渠道管理，Token 仅后端持有；不恢复本地文件模块、`/uploads` 或图片反代，不自动物理清理历史文件。
- 音乐播放使用已导入图床的授权音频；网易云个人 CLI 仅同步歌单资料。不是网易云官方取流或社区 Meting 音源。
- 默认深色，保留原组件和黑色半透明底板以透出粒子；浅色停用粒子。迷你圆盘只播放/暂停，进度操作位于音乐页。
- AI 源码、依赖、聊天组件和历史数据保留，但默认关闭，不开放匿名模型调用。

## 后端架构

主站拆成六个物理层模块，仍只部署一个后端进程。模块内部按业务组织：

```text
blog-app             唯一 Boot JAR；启动、配置、Bean 装配和资源
blog-trigger         HTTP/CLI/启动事件 → application/domain/types
blog-application     用例、事务、跨业务协调 → domain/types
blog-infrastructure  数据访问、图床、Redis、JSON、模型适配 → domain/types
blog-domain          业务模型、规则、repository/gateway 契约 → types
blog-types           少量纯 Java 共享基础类型
```

domain 不依赖 Spring、MyBatis、Redis、HTTP SDK 或 Reactor；业务数据操作经仓储契约，由 infrastructure 实现。application 不反向依赖 infrastructure 或 HTTP DTO，Controller 不暴露 ORM 实体。只读管理统计不强造聚合。

domain 按业务组织，asset 再分 image/metadata/upload/inventory。infrastructure 先按技术/数据职责再分业务，例如 `mybatis/mapper/music`、`mybatis/record/music`、`mybatis/converter/music`、`adapter/repository/music`；装配只在 `app/config`。

当前架构与证据见 [ARCH-B 实施记录](docs/backend-layering-implementation-2026-10-16.md)；重复的旧四层蓝图与旧架构记录已移除，不再作为开发入口。媒体删除、真实上传验收、首页热门标签与 AI 耐久任务仍是独立待办，不因架构迁移或文档清理而视为完成。

## 本地开发

环境：JDK 17、Maven 3.8+、Node.js 22.12+、PostgreSQL、Redis。现有本机 PostgreSQL 使用容器 `unruffled_bartik` 中独立的 `blog_system` 库；不要改动 `family_memory`。

在根目录构建后端：

```powershell
mvn -B -ntp -f blog-server/pom.xml clean test package
# 本机安全入口；真实媒体仓储测试仅使用 blog_media_test
pwsh -NoProfile -File blog-server/tools/architecture/test-backend.ps1 -WithMediaPostgres -Package
```

不要在正在运行的后端读取 `target/classes` 时并行 clean 或重编译。配置通过环境变量注入；不把密码、JWT 密钥、图床 Token、网易云私钥写入文档、源码或前端产物。

本机已有环境的启动入口：

```powershell
# 在根目录 local.env 配置数据库、JWT 等；该文件被忽略，不提交
./blog-server/tools/start-local-backend.ps1
```

该脚本加载根目录 `local.env` 和兼容的 `.env.imgbed.local`，并强制关闭 AI。数据库密码、JWT、图床 Token、发布 Token 等不再写入 `application-dev.yml`。其他环境先配置数据库、Redis、JWT 等，再启动：

```powershell
cd blog-server
mvn -B -ntp package -DskipTests
java -Dfile.encoding=UTF-8 -jar blog-app/target/blog-app.jar --spring.profiles.active=dev --ai.enabled=false --media.catalog.uploads.enabled=false
```

不要使用 `mvn spring-boot:run`，当前中文路径存在兼容问题。聚合父 POM 不是可执行服务；唯一运行制品为 `blog-server/blog-app/target/blog-app.jar`，主类仍为 `com.blogsystem.BlogServerApplication`。业务 API 默认为 `http://localhost:8080`，Knife4j 为 `http://localhost:8080/doc.html`。

公开端：

```powershell
cd blog-web
npm ci
npm test
npm run dev
```

从 `http://127.0.0.1:5173/` 访问；脚本同时启动 Vite 5173 和自动发布进程 5176，`/api` 代理到后端 8080。后端启动目录为 blog-server，dev 共享凭据自动生成并忽略提交。

另一个终端启动管理端：

```powershell
cd blog-admin
npm install --legacy-peer-deps
npx vite --port 5174
```

管理端地址为 `http://localhost:5174/`。单博主认证与权限见 [认证说明](docs/single-owner-authentication.md)。

## 内容发布与构建目录

在 `blog-web` 执行：

```powershell
$env:BLOG_API_URL = 'http://127.0.0.1:8080'
$env:BLOG_SITE_URL = 'https://你的公开站点域名'
npm run content:sync
npm run build
```

同步只读取公开已发布列表，不携带管理员 Token。构建顺序为 Vue → Hexo → 组装：

| 目录 | 当前用途 |
| --- | --- |
| `blog-web/app/` | Vue 源码，不是构建输出 |
| `blog-web/source/` | Hexo 内容与静态资源 |
| `blog-web/.build/app/` | Vite 中间输出，含 Hexo 所需 manifest |
| `blog-web/.build/blog/` | Hexo 中间输出 |
| `blog-web/dist/` | 最终部署产物，`dist/blog/` 为博客 |

以当前配置为准，中间目录不是 `app-dist` / `blog-dist`。不要编辑或提交生成产物。只执行 `npm run build` 不会自动同步数据库；详细流程见 [公开端开发说明](blog-web/README.md)。

## 部署与 CI

Jenkins 当前执行数据库迁移工具测试、后端测试/打包、音乐运行时契约测试、两个前端测试/构建，再构建三个生产 Docker 镜像：`blog-system-server / blog-system-web / blog-system-admin`。是否推送由 `PUSH_IMAGES` 控制，默认分支额外推送 `latest`。

构建前检查六模块测试源码供给，构建后核对全部报告及五个内嵌层 JAR。当前 Java 测试和 docs 被仓库 ignore：本地有文件不等于干净检出能得到它们；CI 缺少测试时明确失败，不绕过 ignore 或放宽零测试门槛。真实媒体测试须显式注入 `MEDIA_PG_TEST_*` 指向 `blog_media_test`，不使用业务库代替；当前交付缺口与既有回归失败见实施记录。

需配置 Registry 地址、命名空间及 Jenkins 凭据。若构建需要数据库最新文章，显式启用 `SYNC_BLOG_CONTENT` 并设置 `BLOG_PUBLIC_API_URL` 与 `BLOG_SITE_URL`；默认不自动同步，干净工作区可能没有导出的文章。

Compose 使用已有外部 PostgreSQL，不创建或初始化数据库。普通启动不包含可选 `ai` profile；构建 AI 镜像不代表启用 AI。Nginx 反代业务 API 与发布进程的静态博客，图片/音频仍由浏览器直接读取图床。生产增加共享 PUBLICATION_API_TOKEN（至少 32 字符）和发布持久卷，不公开内部端口。部署配置与风险见 [技术开发基线](docs/technical-development-baseline.md)。

架构脚本夹具还要求 Jenkins 构建节点提供 PowerShell 7（`pwsh`）；Toolchain 阶段已前置检查。Windows/Linux 均需此工具，不会在流水线中隐式安装。制品检查使用可清理的系统临时目录，不依赖本地 `.artifacts` 已存在。

## 专项文档

- [PostgreSQL 迁移、备份与回退](docs/postgresql-migration.md)
- [Cloudflare 图片模块](docs/cloudflare-image-storage.md)
- [公开端音乐：音源、连续播放与圆盘](blog-web/docs/music-playback.md)、[后端个人资料接入](blog-server/docs/music-provider.md)
- [音乐目录管理](docs/music-catalog-management.md)：管理端自定义歌曲资料及音频/封面/歌词资源
- [主题组件与配色](blog-web/docs/appearance.md)、[前端资源](blog-web/docs/assets.md)
- [AI 模块与未完成边界](docs/ai-chat-module.md)、[AI 开关](docs/ai-disabled-validation.md)
- [后续待办](docs/backlog.md)、[文档整理与恢复](docs/document-maintenance.md)
