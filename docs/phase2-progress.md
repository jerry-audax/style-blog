# 第二阶段进度报告

> 文档状态：历史 AI/RAG 与基础设施集成记录，2026-10-04 标注。保留当时的故障和演进证据，不作为当前运行配置或待执行步骤。文中的公开注册、用户管理、本地上传、旧包路径与旧测试数量已经过时。
>
> 当前说明见 [技术基线](technical-development-baseline.md)、[单博主认证](single-owner-authentication.md)、[PostgreSQL](postgresql-migration.md)、[图床](cloudflare-image-storage.md)、[AI 模块](ai-chat-module.md)。AI 代码/资料继续保留，当前默认关闭。

## 第 1 步：Redis 缓存

### 完成时间

2026-05-22

### 具体内容

| 子任务      | 实现方式                                                                                                        | 涉及文件                                                        |
|----------|-------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| 引入依赖     | `spring-boot-starter-data-redis`                                                                            | `pom.xml`                                                   |
| 连接配置     | `application-dev.yml` → `spring.data.redis`，用户自行填入 IP/端口                                                    | `application-dev.yml`                                       |
| 验证码频率限制  | `StringRedisTemplate`：手机号 `sms:phone:` 60s/次，IP `sms:ip:` 每天 10 次                                           | `AuthService.java`                                          |
| 热门文章缓存   | `cache:hotArticles:6` → Jackson JSON 序列化，10min TTL，手动 `redisTemplate.opsForValue().get/set`                 | `ContentService.java`                                       |
| 文章增删清除缓存 | `saveArticle()` / `deleteArticle()` → `clearHotCache()` 删除所有 hotArticles key                                | `ContentService.java`                                       |
| AI 聊天记忆  | `RedisChatMemory` 实现 Spring AI `ChatMemory` 接口，`U:`/`A:` 前缀存 `StringRedisTemplate`，替代旧 `InMemoryChatMemory` | `RedisChatMemory.java` / `AiConfig.java` / `AiService.java` |
| 缓存故障降级   | try-catch + log.warn，Redis 不可用时自动查数据库                                                                       | `ContentService.java`                                       |

### 设计决策

- **不用 Spring Cache 注解**：`@Cacheable` / `@CacheEvict` 序列化依赖 JDK 序列化，要求实体 `implements Serializable`。改用
  `StringRedisTemplate` + Jackson JSON 手动 get/set，存人类可读 JSON，便于调试。
- **不用 JSON 序列化器**：`GenericJackson2JsonRedisSerializer` 无法处理 MyBatis-Plus CGLIB 代理对象，报
  `Could not write JSON`。直接 `objectMapper.writeValueAsString()` 灵活可控。
- **限流用 StringRedisTemplate**：与缓存同一套 API，`redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(60))` 天然
  TTL，比 `@Cacheable` 的固定分区更直观。

---

## 第 2 步：日志系统

### 完成时间

2026-05-22

### 具体内容

| 子任务         | 实现方式                                                                                                                     | 涉及文件                                      |
|-------------|--------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|
| 建表          | `operation_log` + `login_log` DDL 执行 + `schema.sql` 更新                                                                   | DB / `schema.sql`                         |
| 实体 + Mapper | 2 个实体 + 2 个 MyBatis-Plus Mapper                                                                                          | `log/entity/*.java` / `log/mapper/*.java` |
| 自定义注解       | `@OpLog(module, action)`                                                                                                 | `log/annotation/OpLog.java`               |
| AOP 切面      | `LogAspect`：`@Around("@annotation(OpLog)")` → 自动获取当前用户/IP/UA/参数 → 写 `operation_log`                                      | `log/aspect/LogAspect.java`               |
| 登录日志        | `AuthService.loginByPhoneCode()` 成功写 `login_log(login_status=1)`，用户不存在写 `login_log(login_status=0, fail_reason="用户不存在")` | `AuthService.java`                        |
| 管理端查看       | `GET /api/admin/logs`（分页 + 按模块筛选）+ `LogManage.vue`（表格 + 筛选下拉）                                                            | `AdminController.java` / `LogManage.vue`  |
| 依赖          | `spring-boot-starter-aop`                                                                                                | `pom.xml`                                 |

### 注解覆盖

`@OpLog` 贴了 ContentController 的 6 个写操作：

| 方法             | module | action  |
|----------------|--------|---------|
| saveArticle    | 文章管理   | 新增/更新文章 |
| deleteArticle  | 文章管理   | 删除文章    |
| saveCategory   | 分类管理   | 新增/更新分类 |
| deleteCategory | 分类管理   | 删除分类    |
| saveTag        | 标签管理   | 新增/更新标签 |
| deleteTag      | 标签管理   | 删除标签    |

---

## 第 3 步：权限系统

### 完成时间

2026-05-22

### 具体内容

| 子任务                 | 实现方式                                                                                                                      | 涉及文件                                                                                |
|---------------------|---------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 补齐权限数据              | 13 条权限 INSERT + 绑定 ADMIN 角色                                                                                               | DB / `schema.sql`                                                                   |
| Controller 注解替换     | `@SaCheckRole("ADMIN")` → `@SaCheckPermission("xxx")`：ContentController 6 个 + CommentController 3 个 + AdminController 4 个 | 3 个 Controller                                                                      |
| StpInterfaceImpl 修复 | `getPermissionList()` 从返回空改为联表查询 `sys_role_permission` + `sys_permission`，新建 `SysRolePermissionMapper`                    | `StpInterfaceImpl.java` / `SysRolePermissionMapper.java` / `SysRolePermission.java` |
| SaTokenConfigure 合并 | `SaTokenConfigure.java` 删除，`addInterceptors` 合并到 `WebMvcConfig`                                                           | `WebMvcConfig.java`                                                                 |

### 最终权限模型

| 角色    | 权限码数量 | 鉴权方式                        |
|-------|-------|-----------------------------|
| ADMIN | 13 条  | `@SaCheckPermission("xxx")` |
| USER  | 0 条   | `@SaCheckLogin`（隐式）         |
| 游客    | —     | 无注解                         |

### ADMIN 13 条权限码

```
admin:user:list / update / delete
admin:log:list
content:article:write / delete
content:category:write / delete
content:tag:write / delete
comment:admin:list / audit / delete
```

---

## 日期格式修复

**问题**：所有 `LocalDateTime` 字段输出为 ISO 格式 `2026-05-21T16:33:16`（带 T 分隔符）。

**原因**：`spring.jackson.date-format` 只对 `java.util.Date` 有效，`LocalDateTime` 由 `JavaTimeModule` 控制，YAML 配置无法覆盖。

**修复**：`JacksonConfig` → `Jackson2ObjectMapperBuilderCustomizer` Bean，注册
`LocalDateTimeSerializer("yyyy-MM-dd HH:mm:ss")`。

---

## WebMvc 配置合并

**改动**：`SaTokenConfigure.java`（SaInterceptor 注册）删除，合并到 `WebMvcConfig.java`。

**最终 WebMvcConfig**：

- `addInterceptors` → Sa-Token 鉴权拦截器
- `addResourceHandlers` → `/uploads/**` 静态资源映射

---

## 热门文章缓存改为 ZSet

### 完成时间

2026-05-22

### 背景

之前用 `String` JSON 存热门文章，存在日期格式冲突（`LocalDateTimeSerializer` 定制格式与默认反序列化不兼容）。改为 ZSet 后不再需要
JSON 序列化。

### 具体内容

| 改动      | 旧                                   | 新                                                             |
|---------|-------------------------------------|---------------------------------------------------------------|
| 存储方式    | `cache:hotArticles:6` → JSON String | `cache:hotArticles` → ZSet（member=articleId, score=viewCount） |
| 取 Top N | JSON 反序列化 → 返回                      | `ZREVRANGE 0 N-1` → `selectBatchIds` → 查库                     |
| 阅读量更新   | 不更新，等缓存过期                           | `ZADD` 实时更新 score                                             |
| 增删文章    | `DEL` 全部 key                        | `DEL` 整个 ZSet，下次查库重建                                          |
| 序列化依赖   | 需要 ObjectMapper                     | 无（只存 Long ID）                                                 |

### 涉及文件

| 文件                    | 说明                                                   |
|-----------------------|------------------------------------------------------|
| `ContentService.java` | `listHotArticles` → ZSet 操作，`getArticle` → 同步更新 ZSet |

---

## 第 4 步：Docker 部署

### 完成时间

2026-05-22

### 具体内容

| 子任务                  | 说明                                                                     | 涉及文件                                  |
|----------------------|------------------------------------------------------------------------|---------------------------------------|
| Dockerfile           | 多阶段构建（Maven 编译 → JRE 运行）                                               | `blog-server/Dockerfile`              |
| docker-compose.yml   | MySQL + Redis(:6380) + blog-server + Nginx 四容器                         | `docker-compose.yml`                  |
| nginx.conf           | 前端托管 + `/api` `/uploads` 反向代理                                          | `nginx.conf`                          |
| application-prod.yml | 密码/密钥全部从 `${ENV}` 读取                                                   | `application-prod.yml`                |
| 环境变量模板               | `.env.example` + `.gitignore`                                          | `.env.example` / `.gitignore`         |
| 前端 baseURL 适配        | `import.meta.env.PROD ? '' : 'http://localhost:8080'`                  | `blog-web` + `blog-admin` 的 `http.js` |
| AiChatView 去硬编码      | `fetch('http://localhost:8080/api/ai/chat')` → `fetch('/api/ai/chat')` | `AiChatView.vue`                      |

### 架构

```
docker-compose up
  ├── MySQL :3306
  ├── Redis :6380
  ├── blog-server :8080
  └── Nginx :80
        ├── / → blog-web (dist/)
        ├── /admin → blog-admin (dist/)
        ├── /api/* → blog-server:8080
        └── /uploads/* → blog-server:8080
```

---

## 第 5 步：AI RAG 增强

### 完成时间

2026-05-22

### 具体内容

| 子任务              | 说明                                                                                   | 涉及文件             |
|------------------|--------------------------------------------------------------------------------------|------------------|
| 知识库来源            | `article` 表已发布文章（`content_md`），过滤 < 100 字                                            | —                |
| VectorStore Bean | 启动时加载文章 → `TokenTextSplitter`(200字/50重叠) → DashScope Embedding → `SimpleVectorStore` | `AiConfig.java`  |
| RAG 检索           | `QuestionAnswerAdvisor(vectorStore)` 自动检索相关文章段落                                      | `AiService.java` |
| 依赖               | `spring-ai-advisors-vector-store`                                                    | `pom.xml`        |

### 流程

```
启动时:
  article 表 → contentMd 分段 → 向量化 → SimpleVectorStore

对话时:
  用户提问 → QuestionAnswerAdvisor 检索文章段落 → 注入 Qwen → 基于文章回答
```

### 踩坑

- `SimpleVectorStore` 在 Spring AI 1.0 用 `SimpleVectorStore.builder(embeddingModel).build()`，不是
  `new SimpleVectorStore(embeddingModel)`
- `spring-ai-advisors-vector-store` 需显式加入依赖

---

## 第二阶段总结

| # | 步骤        | 状态 |
|---|-----------|----|
| 1 | Redis 缓存  | ✅  |
| 2 | 日志系统      | ✅  |
| — | 日期格式      | ✅  |
| 3 | 权限系统      | ✅  |
| — | ZSet 热门文章 | ✅  |
| 4 | Docker 部署 | ✅  |
| 5 | AI RAG 增强 | ✅  |

**第二阶段全部完成。**
