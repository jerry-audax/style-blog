# 博客管理端

更新日期：2026-10-04。Vue 3 + Element Plus + Tiptap，沿用 Spring Boot API 和单博主密码登录；不新增公开端登录。

## 当前适配范围

参考 [安和鱼应用](https://github.com/anzhiyu-c/anheyu-app) 及其 [前端](https://github.com/anzhiyu-c/anheyu-app-frontend) 的内容管理流程：分组导航、写作快捷入口、编辑器侧栏与图片复用。参考系统使用不同技术栈，本项目未安装该应用，也未复制其源码；当前改造为独立 Vue 实现。

- 工作台：真实文章、草稿、历史评论统计，最近文章与图片/音乐/博主设置入口；不再以注册用户作为博客概览指标。
- 文章列表：封面、摘要、分类、标签、状态与真实总数分页；标题筛选明确只作用于当前页。
- 写作页：标题在正文上方，发布状态、分类标签、封面摘要在侧栏；新文章默认草稿。首页卡片预览不等于部署结果。
- 图片库：正文、封面、头像按用途上传；编辑器可搜索、分页选择已有图片用于正文或封面，无需重复上传。
- 统一外壳：桌面分组导航与移动抽屉共用配置，网站标识与博主头像分开；浅/深色主色与公开博客协调。保留分类、标签、历史评论、日志、音乐与账号维护功能。

公开端的黑色半透明底板与粒子背景仍由 Hexo 管理；管理端使用可读的实色编辑/表格底板，不加入粒子。管理端沿用 `admin_theme` 偏好，首次默认浅色，不改变公开端默认深色。AI 模块保留且关闭。

## 启动与公开博客地址

```powershell
cd blog-admin
npm ci --legacy-peer-deps
npm test
npm run dev -- --host 127.0.0.1 --port 5174 --strictPort
```

管理端访问 `http://127.0.0.1:5174/`，开发 `/api` 代理到后端 8080。使用已有博主账号密码；不生成默认密码，不在前端放图床 Token。

“查看博客”、文章公开链接及网站标识使用构建配置 `VITE_BLOG_SITE_URL`：开发默认 `http://127.0.0.1:5173/blog/`，生产缺省为当前站点的 `/blog/`。管理端与公开端分域部署时须指定公开域名：

```powershell
$env:VITE_BLOG_SITE_URL = 'https://blog.example.com'
npm run build
```

只接受 HTTP/HTTPS 站点根地址或 `/blog/`，不接受凭据、查询参数或片段；无效配置不显示外部链接。Docker 构建支持同名 build arg；Jenkins 将现有 `BLOG_SITE_URL` 参数传给管理端构建和镜像。该参数不是运行时 Secret，修改后需重新构建。

## 文章与图片流程

数据库是内容事实来源，公开端为 Hexo 静态快照。后台保存为已发布、修改、删除或撤回文章后，运行中的发布进程自动校验并生成页面，不再手动执行同步/构建命令。全局 PublicationNotice 显示最近发布、等待、生成、失败及未连接状态，并通过受保护接口异步重试。保存成功不等于生成已完成；失败继续旧页面，数据保留。详见 [PUB-01](../docs/automatic-publication.md)。外部 CDN 需绕过或清理 HTML 缓存。

图片链路保持 `管理端 → /api/images → asset → Cloudflare ImgBed → Telegram`。渠道类型 `telegram`、名称 `TelegramBot`；Token 仅在后端。正文/封面最大 10 MiB，头像最大 2 MiB，JPEG/PNG/GIF/WebP；前端校验不替代后端字节校验。音频不通过图片接口上传，而在音乐管理中进入 `blog/music` 目录；同页支持封面和 LRC 歌词上传、替换、删除，目录元数据持久化在后端状态目录。

从图片库选择、替换图片或取消编辑，只修改未保存内容，不自动删除图床文件。上传完成不代表文章已保存；关闭选图窗口后完成的请求不会自动应用或重试。明确永久删除仍需用户确认，并可能破坏历史文章/头像引用。图片 URL 为公开链接，请勿上传私人资料。

主要入口：`src/config/navigation.js`（导航）、`src/config/blog.js`（公开地址）、`src/styles/admin.css`（实际配色/布局）、`src/config/theme.js`（品牌）、`src/components/ImagePicker.vue`（选图）。博主资料不会自动改写 Hexo 静态配置或静态头像。

## 本轮验证与未完成项

PUB-01 自动发布：最新完整测试为 62 项通过，构建成功；全局状态提示、失败/未连接反馈及异步重试均有真实 Vue DOM 测试。后端与发布进程的真实 HTTP、Linux 生成及恢复证据见 [发布记录](../docs/automatic-publication.md)。尚未真实登录点击后台或做浏览器视觉验收。

2026-10-04：管理端 62 项自动化测试通过，生产构建成功。测试包含真实 Tiptap 加载/保存 HTML、默认草稿、失败重试、图片复用、音乐授权状态及关闭后的异步结果隔离；API 用 mock，不向真实图床上传/删除，也不改数据库。

构建仍提示 UI chunk 大于 500 kB 与 VueUse PURE 注释警告。此前两次 Chrome 连接失败，桌面/手机视觉、键盘、实际上传仍待验收；不把 DOM 测试当成实机通过。自动发布已另按 PUB-01 实施；全量站点配置编辑、版本历史和完整存储平台仍未实施。

本轮代码改动前备份：`../.artifacts/admin-adaptation-2026-10-04-before/admin-before.zip`，含当时管理端源码与包配置，不含密钥。当前 docs/及部分测试受仓库既有忽略规则影响，发布前需确认版本管理策略。

详见 [技术基线](../docs/technical-development-baseline.md)、[图床说明](../docs/cloudflare-image-storage.md)、[公开端说明](../blog-web/README.md) 与 [待办](../docs/backlog.md)。
