# Jenkins 自动构建

仓库根目录 `Jenkinsfile` 面向 Linux Jenkins Agent。`style-blog` 是 GitHub 源码仓库；镜像仓库命名空间独立配置。

## 节点与插件

- 为构建节点设置 `linux-docker` 标签，安装 Git、Bash、JDK 17（含 `jar`）、Maven 3.9、Node.js `>=22.23.3 <23`、npm、PowerShell 7、Docker CLI/Engine、tar。架构回归夹具需要 `pwsh`。
- 安装 Pipeline、Git、Credentials Binding、JUnit、Timestamper 插件；不依赖 Docker Pipeline、SSH 或 AnsiColor 插件。
- Jenkins 用户须能访问节点本机 Docker Engine。采用专用构建节点，不连接生产 Docker daemon，不在生产服务目录中运行 Job。节点需要访问 Maven Central、npm、Docker Hub。
- 流水线自动创建 PostgreSQL 16 临时容器和 `blog_media_test`，绑定随机本机端口；测试结束删除自己创建的容器。临时库没有业务数据，不需要生产数据库、图床或 SSH 凭据。不要把生产凭据注入构建节点。

## 创建任务

1. 创建 Multibranch Pipeline，连接实际 GitHub 仓库和源码读取凭据，Script Path 为 `Jenkinsfile`。也可创建 Pipeline from SCM，构建分支填写 `*/master`。
2. 首次运行默认测试、打包、构建三个镜像，不推送、不部署、不执行业务库迁移。后续可通过 GitHub Webhook `/github-webhook/` 触发分支构建。
3. 需要推送时，在 Jenkins Credentials 添加 Username with password：ID 为 `dockerhub-blog-system`，用户名为镜像仓库用户名，Password 填仓库访问 Token。不要填写 GitHub 源码仓库名或在 Jenkinsfile 写 Token。
4. 在 Build with Parameters 勾选 `PUSH_IMAGES`，配置 `DOCKER_REGISTRY` 和 `DOCKER_NAMESPACE`。只有 `main/master` 可推送；`vibe_1` 只测试、构建。

| 参数 | 默认值 / 用途 |
| --- | --- |
| `DOCKER_REGISTRY` | `docker.io`；GHCR 可填 `ghcr.io`，不含 `https://` |
| `DOCKER_NAMESPACE` | 推送时填写 Docker Hub 用户/组织；GHCR 填 GitHub 用户/组织的小写名称 |
| `DOCKER_CREDENTIALS_ID` | `dockerhub-blog-system`，可替换为实际 Jenkins 凭据 ID |
| `PUSH_IMAGES` | `false`，开启后推送三个镜像的版本标签，主分支同时更新 `latest` |
| `BLOG_SITE_URL` | `https://blog.jrtitan.xyz` |
| `BLOG_OWNER_PHONE` | `19140838906`，公开的单博主登录账号，非密码 |
| `SYNC_BLOG_CONTENT` | 默认关闭；开启后从 `BLOG_PUBLIC_API_URL` 读取公开文章，之后再构建镜像 |

推送命名为 `<registry>/<namespace>/blog-system-server:<构建号>-<12位提交>`，另有 `blog-system-web`、`blog-system-admin`。未配置命名空间且不推送时使用 `style-blog-local` 作为本机镜像前缀。

## 流程与产物

检出 → 工具/参数检查 → 迁移工具回归 → 临时 PostgreSQL 后端完整测试与打包 → 六层制品/报告检查 → 音乐运行时测试 → 两个前端测试和构建 → 三个 Dockerfile 构建 → 可选推送 → 归档。

归档包含后端 JAR、两个前端压缩包、`images.env`（三个 Compose 镜像变量）和源码提交号。Jenkins 保留 20 次日志，归档产物仅保留最近两次构建；镜像仓库的历史版本由仓库保留策略管理，不执行全局 Docker prune。

前端测试后的构建产物和 Dockerfile 内构建使用相同源码、站点/账号参数；若开启文章同步，同步结果也进入 Web Docker 构建上下文。线上现有发布卷仍由生产发布器管理，流水线不接触其数据。

构建成功不等于上线：本次不自动 SSH、更新生产容器或执行 SQL。发布时使用归档 `images.env` 中的不可变版本标签，并保留线上私有环境配置和现有媒体/发布卷。不要直接用默认 Compose 覆盖现行生产入口。

## 验证边界

本地可运行 `bash -n deploy/ci/test-backend.sh` 和后端架构回归；Jenkins Declarative 校验需在实际 Jenkins 使用 `/pipeline-model-converter/validate` 或内置 Pipeline 校验。首次 Job 运行还需验证插件、Agent 标签、网络、镜像凭据和推送权限，不能由本地语法检查替代。
