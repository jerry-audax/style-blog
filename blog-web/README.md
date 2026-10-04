# Javerry 公开站点：Hexo + Vue

更新日期：2026-10-04。本站是个人技术博客，不是独立个人主页/作品集。Hexo 使用安知鱼原主题提供公开阅读，Vue 提供文章动态计数和只读历史评论；编辑与账号维护都在独立管理端。

## 路由与边界

| 入口 | 当前职责 |
| --- | --- |
| `/` | 开发、预览、Nginx 均跳转到 `/blog/` |
| `/blog/` | 文章、归档、分类、标签、静态搜索和原首页组件 |
| `/blog/#recent-posts` | 最新文章区 |
| `/blog/music/` | 原主题音乐页，显示持久播放器的完整界面 |
| `/blog/about/` | 博主/本站介绍，不是个人信息编辑页 |
| `/blog/article/{数据库ID}/` | 静态正文 + Vue 只读计数/历史评论 |
| `/login /register /profile /ai` | 退休入口，兼容重定向回博客，不挂载账户/AI 页面 |
| `/api/` | Spring Boot API；公开端不发送管理 Token |

顶栏包含“主页（归档/分类/标签）、最新文章、我的→音乐、关于”。公开端不提供博客登录注册、个人资料编辑、发表评论/回复或点赞写入。

昵称、邮箱、头像、密码统一在 `blog-admin` 的 `/account`；原公开 ProfileView/LoginView 已清理，不再以组件路径指导开发。AI 聊天、状态、SSE 工具与依赖保留但不启用。详见 [单博主认证](../docs/single-owner-authentication.md)、[文件清理记录](../docs/project-cleanup-2026-10-03.md)。

## 源码与构建目录

```text
blog-web/
├── app/                         Vue workspace
│   └── src/
│       ├── interaction-widget.js 文章只读组件入口
│       └── site-background.js    独立轻量背景入口
├── source/                      Hexo 内容、CSS/JS、图片
├── _config.yml                  root=/blog/，public_dir=.build/blog
├── _config.anzhiyu.yml           原主题配置，不改 node_modules
├── scripts/site-loader.js       Hexo 自动发现入口，等待加载 tools 下的两个插件
├── tools/                       同步、集成、开发、预览和组装
├── .build/
│   ├── app/                     Vite 中间输出及 .vite/manifest.json
│   └── blog/                    Hexo 中间输出
└── dist/                        最终部署产物
    ├── assets/                  Vue/轻量入口资源
    ├── index.html               兼容 SPA fallback，根路径仍跳到博客
    └── blog/                    完整 Hexo 站点
```

当前目录不是 app-list/blog-list，也不使用 app-dist/blog-dist；以 Vite outDir、Hexo public_dir 和 `tools/assemble.cjs` 为准。`.build` 与 `dist` 可重新生成，不编辑/提交构建产物。

Hexo 自动加载的是 `scripts/`，不会自动发现 `tools/`。`scripts/site-loader.js` 通过当前 Hexo 实例的 `loadPlugin` 加载 `tools/site-integration.cjs` 和 `tools/vendor-assets.cjs`；此入口不能当作冗余文件删除，也不能直接用普通 require 替代。添加或修改此入口后，需要重启 Hexo 开发服务。

Node >=22.12，依赖只在 `blog-web` 根目录安装，使用根 package-lock：

```powershell
npm ci
npm test
npm run dev
```

dev 同时运行 Vite 5173 与自动发布进程 5176；从 `http://127.0.0.1:5173/` 访问，`/blog` 代理到 5176、`/api` 到 8080。后端须另行启动。发布状态与不可变产物在 ignored `.runtime/publication/`，dev 内部共享凭据自动生成，不提交或输出。

```powershell
npm run build
npm run preview
```

build 顺序为 build:app → build:static → assemble。Hexo 需要 Vite manifest，不能跳过第一步；assemble 校验生成 HTML/符号链接，再合并到 dist。preview 也占用 5173，不与 dev 同时运行；API 默认代理 8080，可用 BLOG_API_URL 指定。

## 技术文章发布

```text
后台保存/发布 → PostgreSQL
                   ↓ 自动发布进程每 5 秒校验公开列表
         隔离 Hexo 生成 → 校验 → 原子替换发布指针
                   ↓
                /blog/ → 最新静态页面
```

以下是离线导出/CI/归档操作，日常后台内容更新不再需要执行：

```powershell
$env:BLOG_API_URL = 'http://127.0.0.1:8080'
$env:BLOG_SITE_URL = 'https://你的公开站点域名'
npm run content:sync
npm run build
```

- 只请求公开已发布列表，不读取账号、评论、草稿或管理员资料，不调用会增加阅读数的文章详情接口。
- 保留数据库 ID、标题、发布时间、分类和标签；固定路径 `/blog/article/{id}/` 不随标题修改变化。
- 兼容 Tiptap HTML 和旧 Markdown；Hexo 渲染后净化脚本、事件属性与危险 URL。业务文章不能自带脚本，可信交互由 `tools/site-integration.cjs` 集成。
- 分页/数量/正文校验失败即停止同步。仅清理清单中归本站导出所有的文件，不删除手写文章。
- 自动快照与清单不提交。干净 CI 需要显式同步或有经审核的手写文章；没有内容时显示真实空状态，不生成假文章/统计。
- 手写文章只有对应真实已发布记录的 blog_article_id 才能接入后端互动。
- build 仍是离线构建，不自动同步；运行中的发布进程会自动处理文章发布、修改、撤回、删除。更新需等待一次轮询及生成；失败继续上一版，后台可重试。外部 CDN 需绕过或清理 HTML 缓存。
- 分类/标签来自已发布文章关联；未被使用的项目不会生成公开列表项。

2026-10-03 的实施记录曾同步 5 篇真实文章（11–15）；这是当时快照，不是固定内容数量。

## 动态、安全与资源

公开端不读取/发送 blog_token 或 admin_token，不恢复旧会话或调用身份接口。动态组件仅展示计数和已审核历史评论，评论作为文本渲染；接口失败不阻断静态正文。

图片由后台调用 `/api/images` 管理，浏览器直接读取图床 URL；Token 仅后端。旧 `/uploads/` 返回 404，历史文件/记录未删除。详见 [图片说明](../docs/cloudflare-image-storage.md)。

网站标识为 `source/img/site-logo.jpg`，favicon 使用 `/blog/img/site-logo.jpg`；作者头像为 `source/img/avatar.jpg`，不能合并用途。资源入口见 [前端资源说明](../docs/frontend-assets-guide.md)。

主题字体、瀑布流、QR、Swiper、APlayer、Meting、Snackbar、medium-zoom 与 PJAX 随站点打包；数据/图片/封面/音频仍可能是远端资源。保留 Meting 组件不代表仍请求公共 Meting 音源。第三方评论、访客统计、赞赏二维码与 AI 摘要未启用，不使用作者示例账号或歌单。来源与许可见 [THIRD_PARTY](THIRD_PARTY.md)。

## 音乐

实际音源来自 `/api/music/hosted-playlist` 返回的图床音频。网易云歌单 939817038 只用于首次导入和歌手/封面匹配；博主可以在管理端维护自定义目录，不确定资料留空，不显示“本站音频”，不通过会员凭据替访客取流。

- 全站共用一个 APlayer，永久元素位于 PJAX 替换区域外；音乐页显示同一实例，离开前回收界面。Vue 互动组件按 PJAX 事件卸载/重挂。
- 新播放器随机选择起始曲，随后默认随机；继续播放、手动选曲或 PJAX 导航不会重新抽取/重置进度。
- 首次进入尝试播放，被浏览器拒绝时等真实交互；手动暂停不因切页自动恢复。刷新、关闭、站外或管理端跳转会结束连续播放。
- 迷你封面圆盘只播放/暂停，转动反映播放状态；减少动画时不转动。完整列表、进度、音量仅在音乐页，其他位置完整 APlayer 隐藏并 inert。
- 控制台 `#consoleMusic` 区分加载/播放/暂停/失败，支持键盘及取消未完成播放；不会与主题父子点击重复切换。
- 音源失败延迟 1.5 秒有限跳过，连续 5 首或剩余均失败时停止；实际 playing 重置失败预算。暂停/销毁取消跳歌，手动重试可重新尝试，不无限请求。
- 行为集中在 `source/js/site-music.js`，不修改 npm 主题。详见 [托管歌单](../docs/music-hosted-web-2026-10-03.md)、[连续播放](../docs/music-continuity-2026-10-03.md)、[圆盘控制](../docs/music-disc-controls-2026-10-03.md)。

## 主题、透明度与效果

原安知鱼顶部区、分类入口、推荐轮播/小卡、作者卡、公告、文章列表、归档/分类/标签、统计、控制台、右键菜单、放大及版权区保留。推荐来自真实文章，不使用占位内容。

主色入口为 `_config.anzhiyu.yml → theme_color`：浅色 #4F46E5、深色 #A5B4FC；mainTone/cover_change 关闭，不随封面换主色。默认深色，保留手动主题偏好。

`source/css/site.css` 控制本站派生色和底板：深色底色 #1B1722，卡片黑色 alpha 0.62，正文/导航/控制台 0.8，次级底板 0.7，搜索/菜单 0.96，边框 #45414C。粒子装饰 opacity 0.55；只改背景 alpha，不降低文字、按钮、封面、头像的整体 opacity。全透明方案已取消。

粒子参数唯一入口为 `app/src/config/particles.js`：350、spread 10、speed 0.1、四色 #6366F1/#10B981/#ffffff/#EC4899、moveParticlesOnHover=true、hoverFactor=1、alphaParticles=false、baseSize=100、sizeRandomness=1、cameraDistance=25、disableRotation=false。使用 OGL/React Bits 的 Hexo/Vue 适配，不运行 React/shadcn 脚手架。

浅色隐藏并停止 WebGL；切回深色复用画布，隐藏标签暂停，减少动画显示静态粒子，WebGL 不可用保留纯色底，减少透明偏好恢复实底。详见 [背景记录](../docs/particles-background-2026-10-03.md)、[当前配色与组件](../docs/theme-card-restoration-2026-10-03.md)。

点击彩球是参考 click-colorful 的本站独立实现，不复制无声明许可的上游脚本。配置在 theme 的 site_click_effect：enable、colors、size=30、maxCount=30、duration=1000、maxActiveBalls=150；实现为 `source/js/site-click-effect.js`。不阻止默认交互，输入区/滑块/键盘/右键排除，减少动画时关闭，隐藏/离页清理。配置修改需重启 Hexo dev 或重新构建部署，不改 npm 主题，不启用管理端效果。

## Docker / Jenkins / 验证

PUB-01 的最新验证为 94 项（62 Node + 32 Vitest）通过，完整构建及发布进程镜像构建成功；Windows 与 Linux 均从实际 API 生成了包含新图床封面的页面。详见 [执行记录](../docs/automatic-publication.md)。后面的日期段落保留对应历史结果，不代替此发布切片证据。

blog-web 默认镜像同时提供 Nginx/Vue 资源和运行时 Hexo 发布进程。Nginx `/blog/` 代理同一容器内的不可变静态产物，缺失页面返回真实 404，不落入 SPA；API 反代，AI 路径保留 SSE 配置但服务禁用。内部控制路由不代理。HTML/API 不长期缓存。

构建设置真实 BLOG_SITE_URL。Jenkins 的 SYNC_BLOG_CONTENT 仅影响构建时离线快照；运行时发布进程使用公开 API 自动校验内容。生产为它和后端注入同一个至少 32 字符的 PUBLICATION_API_TOKEN，并挂载专用持久卷，发布端口只在容器网络内使用。见 [自动发布配置与验收](../docs/automatic-publication.md)。

npm test 覆盖内容同步完整性/净化、真实隔离 Hexo 构建、Vue 只读边界、SSE、APlayer/生命周期/反馈、粒子和组件；测试内容不进入正式文章目录。

2026-10-03 历史记录为 86 项测试（54 Node + 32 Vitest）、联合构建和首页组件/HTTP 检查通过；随后文档整理未重跑业务测试或浏览器试听。旧 ui-check.cjs 部分断言属于退休账户/首页，不能作为当前完整验收标准。Chrome 连接失败后真实桌面/手机视觉、WebGL、音频及 Lighthouse 仍待验收，DOM/HTTP 检查不能替代。

2026-10-04 启动修复：恢复上述 Hexo 加载入口；重新执行 86 项测试与完整构建，均通过。隔离构建额外检查了入口注册、空赞赏数组与带图文章二次生成。仅重启公开端后，主页、文章、音乐、关于、归档及背景/播放器依赖均返回 HTTP 200；管理端使用 localhost:5174 也返回 200，后端与管理端进程未重启。没有改写正式文章或调用真实上传接口。此次不是浏览器视觉或音频试听验收。

后续缺口见 [待办](../docs/backlog.md)，AI 保留边界见 [AI 文档](../docs/ai-chat-module.md)。
