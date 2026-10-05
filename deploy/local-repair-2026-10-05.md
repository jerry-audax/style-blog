# 本地公开端启动修复记录

## 故障与原因

Vite 加载配置时找不到 `dep-BK3b2jBa.js`。package.json 和 package-lock.json 均指定 Vite 7.3.6，但已安装的 Vite index.js 仍引用旧版本 chunk。此前代理在整理 Git 提交时恢复了部分已跟踪的 node_modules 文件，造成依赖版本混用。

## 本次处理

在 blog-web 执行 `npm ci --no-audit --no-fund`，按已有锁文件重新安装完整依赖。没有更改 package.json、package-lock.json 或 app/vite.config.js。

从 blog-web/app 启动 Vite 临时端口后，确认版本为 7.3.6，GET /login 返回 HTTP 200，随后关闭检查进程。

## 后续改动约定

- 环境差异写入独立环境文件、生产 Compose 覆盖文件或独立配置文件，不直接覆盖原有开发配置。
- 变更原因、文件范围、操作和验证结果记录在单独文档中。
- 不通过 git restore/reset 恢复部分 node_modules 文件；依赖损坏时用锁文件完整重装。
- Git 操作前检查暂存区，只提交任务涉及的文件，保留用户的其他本地修改。

## 本地启动

```powershell
cd blog-web
npm run dev
```

日常启动不需要重复安装。再次出现依赖损坏时，在 blog-web 执行 `npm ci --no-audit --no-fund` 后再启动。
