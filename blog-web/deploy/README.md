# 公开端部署修复材料

`Dockerfile.runtime-repair` 与 `production-branding.cjs` 属于公开端，不是独立应用。保留既有镜像入口行尾与预加载头像修复，不改变标准 Dockerfile/Compose 的部署流程。

若经独立批准使用，构建上下文仍为项目根目录：

```sh
docker build -f blog-web/deploy/Dockerfile.runtime-repair -t blog-system-web:runtime-repair .
```

依赖本机已有 `blog-system-web:latest`；上面的标签/命令不表示本次已构建或部署。域名/HTTPS/三应用编排仍归根 [deploy](../../deploy/README.md)，不能把后端凭据或运行数据加入上下文。
