# 开发问题与经验总结

> 文档状态：跨阶段问题记录，包含已退休实现。2026-10-04 整理时保留排障经验；示例中的旧服务、数据库、文件存储和账户方案不是当前实施指令。当前路径/配置以 [技术基线](technical-development-baseline.md) 为准，AI 当前状态见 [AI 文档](ai-chat-module.md)。

记录项目从零到第一阶段完成的全部典型问题与解决方案。

---

## 一、构建与环境

### 1.1 Maven 中文路径编码问题

**现象**：`mvn spring-boot:run` 和 `mvn package` 均失败，报 `ClassNotFoundException` 或 JAR 重命名错误。

**原因**：项目路径包含中文字符（`C:\Users\刘家瑞\desktop\计科学习\...`），Maven JAR 插件无法正确处理。

**解决**：使用 `mvn exec:java` 直接从编译后的 class 文件启动：

```bash
mvn compile
mvn exec:java -Dexec.mainClass="com.blogsystem.BlogServerApplication"
```

### 1.2 JAR 文件被进程锁定

**现象**：重新编译时旧 Java 进程持有 JAR 文件，导致覆盖失败。

**解决**：编译前先停止 Java 进程：

```bash
powershell -Command "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force"
```

### 1.3 前端 dev server 后台运行问题

**现象**：`npx vite --port 5174 &` 在 bash 中进程退出后 dev server 也停止。

**解决**：使用 `nohup` 分离进程：

```bash
nohup npx vite --port 5174 --host > /tmp/vite-admin.log 2>&1 &
disown
```

### 1.4 端口冲突静默切换

**现象**：Vite 启动时提示 `Port 5174 is in use, trying another one...`，自动切换到 5175，导致 curl / 浏览器访问失败。

**解决**：启动前确保无残留 node 进程：

```bash
powershell -Command "Get-Process node -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.Id -Force }"
```

---

## 二、前端

### 2.1 @kangc/v-md-editor 白屏问题

**现象**：admin 页面打开后白屏，控制台报 `Uncaught TypeError: Cannot read properties of undefined (reading 'prototype')`。

**原因**：`@kangc/v-md-editor/lib/codemirror-editor` 依赖 CodeMirror（CommonJS 模块），Vite ESM 环境无法正确解析 CJS 模块的导出。

**尝试过的方案**：

- `vite.config.js` 中 `optimizeDeps.include` 添加 `['codemirror', '@kangc/v-md-editor']`（无效）
- 安装 `codemirror` 作为直接依赖（无效）

**最终方案**：替换为 `@bytemd/vue-next`，原生 ESM 支持，Vite 完全兼容：

```bash
npm install @bytemd/vue-next bytemd --legacy-peer-deps
```

**代码变更**：

```js
// 旧（不兼容）
import VMdEditor from '@kangc/v-md-editor/lib/codemirror-editor'
import '@kangc/v-md-editor/lib/style/base-editor.css'
// <v-md-editor v-model="articleForm.contentMd" height="400px" />

// 新（兼容）
import { Editor } from '@bytemd/vue-next'
import 'bytemd/dist/index.css'
// <Editor :value="articleForm.contentMd" @change="v => articleForm.contentMd = v" />
```

**选择 bytemd 的理由**：
| 对比 | v-md-editor | bytemd |
|---|---|---|
| Vite ESM 兼容 | ❌ CodeMirror CJS 冲突 | ✅ 原生 ESM |
| 预览 | ✅ | ✅ |
| 插件生态 | 有限 | 丰富（数学公式 / Mermaid 等） |
| Vue 3 支持 | 部分 | 完整 |

### 2.2 Element Plus 组件引入差异

**现象**：blog-web 中 `import { ElMessage } from 'element-plus'` 报错。

**原因**：blog-web 未安装 Element Plus（仅 blog-admin 使用）。

**解决**：使用 Vue 原生的 `ref` + 条件渲染代替。

### 2.3 template literal 在 attribute 中的渲染错误

**现象**：`:placeholder="\`回复 ${c.nickname || '用户'}...\`"` 渲染为原始文本。

**原因**：HTML attribute 的模板语法不支持带引号的 template literal。

**解决**：去掉模板字面量内的引号：`:placeholder="\`回复 ${c.nickname || '用户'}...\`"`。

### 2.4 v-for key 与 ref 冲突

**现象**：列表渲染中 `:key` 使用 `item.id` 时，删除或新增项后状态未正确更新。

**解决**：确保 key 唯一且稳定，必要时使用组合 key（如 `` :key="`hot-${item.id}`" ``）。

---

## 三、CSS / 布局

### 3.1 三栏布局 DOM 顺序错误

**现象**：热门阅读模块排到最右边，标签栏在中间。

**原因**：DOM 顺序为 `分类 | 标签 | 主内容`，CSS Grid 按源码顺序分配列：分类(1) → 标签(2) → 主内容(3)，标签被放入第二列（中心列）。

**解决**：调整 DOM 顺序为 `分类 | 主内容 | 标签`，匹配 Grid 列分配。

**正确写法**：

```html
<div class="layout-with-sidebar">
  <aside><!-- 分类（左列）--></aside>
  <div class="main-area"><!-- 主内容（中列）--></div>
  <aside><!-- 标签（右列）--></aside>
</div>
```

**关键 CSS**：

```css
.layout-with-sidebar {
  display: grid;
  grid-template-columns: minmax(150px, 200px) 1fr minmax(150px, 200px);
  gap: clamp(18px, 3vw, 40px);
  align-items: start;
}
```

### 3.2 标签筛选时热门文章未隐藏

**现象**：点击标签后，热门文章和标签筛选结果同时显示。

**原因**：热门文章 `v-if` 仅判断 `!activeCategoryId`，未考虑标签激活状态。

**修复**：

```html
<!-- 旧 -->
<section v-if="!activeCategoryId && hotList.length > 0">
<!-- 新 -->
<section v-if="!activeCategoryId && !activeTagId && hotList.length > 0">
```

### 3.3 fetch 错误静默吞噬

**现象**：API 调用失败时前端无任何提示（如删除文章失败无错误消息）。

**原因**：`catch { /* cancelled */ }` 没有参数，捕获所有错误后静默忽略。

**修复**：区分用户取消操作和 API 错误：

```js
} catch (e) {
  if (e !== 'cancel' && e !== 'close') {
    ElMessage.error(e?.message || '操作失败')
  }
}
```

---

## 四、后端

### 4.1 MyBatis-Plus 逻辑删除与手动 updateById 冲突

**现象**：调用 `articleMapper.deleteById(id)` 返回 200，但数据库 `deleted` 字段未变化。

**原因**：MyBatis-Plus 全局逻辑删除配置 `logic-delete-field: deleted` 后，代码中手动 `setDeleted(1) + updateById()`
不会触发逻辑删除。`updateById` 是纯 UPDATE 操作，不经过逻辑删除拦截器。

**修复**：统一使用 `mapper.deleteById(id)` 方法，MP 自动转换为 `UPDATE SET deleted=1 WHERE id=? AND deleted=0`。

```java
// 错误写法
article.setDeleted(1);
articleMapper.updateById(article);

// 正确写法
articleMapper.deleteById(id);
```

**影响范围**：`ContentService.deleteArticle/deleteCategory/deleteTag`、`CommentService.adminDelete`、
`AdminService.deleteUser`。

### 4.2 Sa-Token JWT 配置冲突

**现象**：后端重启后 admin 页面的 API 请求全部返回 `{"code":500,"message":"系统繁忙"}`。服务器日志显示
`NotLoginException: token 无效`。

**原因**：`token-style: random-128` 与 `jwt-secret-key` 同时配置。`token-style` 生成的是随机字符串 token（有状态），依赖内存中的
session 映射。重启后 session 丢失，所有旧 token 失效。

**关键证据**：token 值类似 `UvEn4ICrS1tn56tnK...`（128 字符随机串），而非标准 JWT（`header.payload.signature` 三部分）。

**修复**：移除 `token-style` 配置，将 `is-share: true` 改为 `false`，启用纯 JWT 无状态模式：

```yaml
# 修复后的配置
sa-token:
  token-name: Authorization
  timeout: 2592000
  active-timeout: -1
  is-concurrent: true
  is-share: false       # ← 改为 false（无状态）
  is-log: false
  jwt-secret-key: ${sa-token.jwt-secret-key}
  # 移除 token-style: random-128
```

**影响**：修复后需要重新登录获取 JWT token。

### 4.3 getArticle 方法副作用

**现象**：`deleteArticle` 方法调用 `getArticle(id)`，导致删除前触发阅读量 +1。

**修复**：`deleteArticle` 中直接用 `articleMapper.selectById(id)` 查询，避免调用 `getArticle`（后者会在阅读详情时 +1）。

```java
// 错误：删除前触发 viewCount +1
public void deleteArticle(Long id) {
    Article article = getArticle(id);  // 内部会 setViewCount(+1) + updateById
    ...
}

// 正确：直接查询
public void deleteArticle(Long id) {
    Article article = articleMapper.selectById(id);
    if (article == null) throw new IllegalArgumentException("文章不存在");
    articleMapper.deleteById(id);
}
```

### 4.4 GlobalExceptionHandler 返回 HTTP 200

**现象**：API 返回 `{"code":500,"message":"系统繁忙","data":null}`，但 HTTP 状态码为 200。

**原因**：`GlobalExceptionHandler` 中 `@ExceptionHandler(Exception.class)` 捕获未预期的异常后返回
`ApiResponse.fail(500, "系统繁忙")`，但 HTTP 响应状态码被 Spring 默认处理为 200。

**解决**：在返回 `ApiResponse` 时需同时设置 HTTP 状态码。但当前前端拦截器已检查 `data.code` 字段，200 状态码不影响错误处理。可后续通过
`@ResponseStatus` 注解优化。

---

## 五、数据库

### 5.1 ALTER TABLE 编码显示问题

**现象**：MySQL 命令行查询中文数据时显示为乱码。

**原因**：Windows 终端默认编码与 MySQL UTF-8 不匹配。

**解决**：通过参数 `default-character-set=utf8mb4` 连接，或直接忽略（数据存储正常，仅终端显示异常）。

### 5.2 schema.sql 与增量 DDL

**实践**：数据库初始建表用 `schema.sql`，后续新增字段在文件末尾追加注释形式的 ALTER 语句：

```sql
-- 增量 DDL（已有数据库执行）
-- ALTER TABLE article ADD COLUMN content_html LONGTEXT DEFAULT NULL AFTER content_md;
```

实际执行通过 MySQL CLI 直接操作，schema.sql 保留完整建表语句作为文档。

---

## 六、问题速查表

| 现象               | 关键词                    | 根因        | 章节  |
|------------------|------------------------|-----------|-----|
| Maven 构建失败       | ClassNotFoundException | 中文路径      | 1.1 |
| 前端白屏             | prototype              | CJS → ESM | 2.1 |
| 删除不生效            | deleted=0 未变           | MP 逻辑删除   | 4.1 |
| 重启后全部 500        | token 无效               | JWT 配置冲突  | 4.2 |
| 布局错位             | 标签在中间                  | DOM 顺序    | 3.1 |
| API 错误无提示        | 静默失败                   | catch 吞噬  | 3.3 |
| dev server 自动换端口 | 5175                   | 残留进程      | 1.4 |

---

## 七、设计决策记录

| 决策               | 选项 A              | 选项 B           | 选择          | 原因          |
|------------------|-------------------|----------------|-------------|-------------|
| 启动方式             | `spring-boot:run` | `exec:java`    | exec:java   | 中文路径 bug    |
| Markdown 编辑器     | `v-md-editor`     | `bytemd`       | bytemd      | Vite ESM 兼容 |
| 前端 Markdown 渲染   | 手写正则              | `markdown-it`  | markdown-it | 准确性 & 可扩展   |
| 后端 Markdown→HTML | 无                 | `flexmark-all` | flexmark    | Java 最成熟    |
| 点赞模型             | 仅计数               | toggle 表       | toggle 表    | 防重复点赞       |
| Token 模式         | 有状态               | JWT 无状态        | JWT 无状态     | 跨重启持久化      |

---

## 八、第二阶段问题记录

### 8.1 Redis 序列化 — GenericJackson2JsonRedisSerializer 写入失败

**现象**：`listHotArticles` 带 `@Cacheable` 时 Redis 写入报 `Could not write JSON`。

**原因**：`GenericJackson2JsonRedisSerializer` 无法序列化 MyBatis-Plus CGLIB 代理对象，Jackson 尝试反射代理类内部字段时报错。

**解决**：放弃 `@Cacheable` / `@CacheEvict` 注解，改用 `StringRedisTemplate` + Jackson `ObjectMapper.writeValueAsString()`
手动序列化。`ObjectMapper` 对 MyBatis 代理兼容性更好，且存 JSON 字符串方便调式。

**教训**：Spring Cache 抽象不适合直接序列化 ORM 实体。缓存复杂对象时手动 JSON 序列化更灵活可控。

### 8.2 Redis 不可用导致业务中断

**现象**：Redis 连接失败时 `/api/content/article/hot` 返回 500。

**原因**：`@Cacheable` 内部调用 Redis 抛出异常，未捕获。

**解决**：改写为手动 get/set 包裹 try-catch，Redis 不可用时 log.warn + 查数据库兜底。

**教训**：缓存层必须做降级处理。所有缓存操作都应 try-catch，缓存不可用时不能阻断业务。

### 8.3 AOP 切面 — 缺少 spring-boot-starter-aop

**现象**：`LogAspect.java` 编译失败，`org.aspectj.lang` 包找不到。

**原因**：`spring-boot-starter-web` 不自带 AOP 依赖。

**解决**：`pom.xml` 加 `spring-boot-starter-aop`。

### 8.4 注解与实体类同名冲突

**现象**：`@OperationLog` 注解编译报错"单类型导入已定义了同名的简单类"。

**原因**：`OperationLog.java` 被同时用作实体类（`log.entity.OperationLog`）和注解名（`log.annotation.OperationLog`），Java
import 无法区分。

**解决**：注解改名为 `@OpLog`，避免与实体类同名。

### 8.5 spring.jackson.date-format 对 LocalDateTime 无效

**现象**：`spring.jackson.date-format: yyyy-MM-dd HH:mm:ss` 配置后，接口返回的 `LocalDateTime` 字段仍为 ISO 格式
`2026-05-21T16:33:16`。

**原因**：`spring.jackson.date-format` 底层用 `SimpleDateFormat`，只对 `java.util.Date` 有效。`LocalDateTime` 由
`JavaTimeModule` 控制，`date-format` 属性无法覆盖其默认的 ISO 格式。

**解决**：创建 `Jackson2ObjectMapperBuilderCustomizer` Bean，在 `JavaTimeModule` 上注册
`LocalDateTimeSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))`。`@PostConstruct` 方式太晚（MVC 已拿到
ObjectMapper），必须用 Customizer。

**教训**：Spring Boot 的 Jackson 自动配置对不同日期类型的覆盖范围不同，`java.time` 包的类型需要显式注册序列化器。

### 8.6 Jackson2ObjectMapperBuilderCustomizer 执行顺序

**现象**：`@PostConstruct` 方式注册 `LocalDateTimeSerializer` 无效。

**原因**：`@PostConstruct` 在 Bean 初始化后执行，但 Spring MVC 的 `MappingJackson2HttpMessageConverter` 在更早阶段已获取
ObjectMapper 引用，后续修改不生效。

**解决**：改为 `Jackson2ObjectMapperBuilderCustomizer` Bean，Spring Boot 自动配置阶段就会调用，保证 ObjectMapper
创建时就带正确的序列化器。

### 8.7 Sa-Token 权限接口返回空列表

**现象**：`@SaCheckPermission("xxx")` 全部返回 403/401。

**原因**：`StpInterfaceImpl.getPermissionList()` 返回 `Collections.emptyList()`，注释写"当前未启用细粒度权限"。

**解决**：实现联表查询：`sys_user_role` → 角色 ID → `sys_role_permission` + `sys_permission` → 权限码列表。新增
`SysRolePermissionMapper.getPermissionCodesByRoleIds()`。

### 8.8 YAML 重复 key 导致配置不生效

**现象**：`spring.jackson.date-format` 和 `spring.servlet.multipart` 配置写入后不生效。

**原因**：

- `jackson` 被错误缩进到 `sa-token:` 下面，成为 sa-token 的子属性
- `spring.servlet.multipart:` 写成了顶级 key（0 缩进），而非 `spring:` 的子属性
- YAML 缩进错误不会报错，只会静默纳入错误的父节点

**解决**：统一 `application.yml` 格式，所有 `spring.*` 配置正确嵌套在 `spring:` 下。

**教训**：YAML 修改后应检查 `target/classes/` 中编译后的文件确认层级正确。

### 8.9 Jackson 定制格式导致 Redis 反序列化失败

**现象**：`JacksonConfig` 注册 `LocalDateTimeSerializer("yyyy-MM-dd HH:mm:ss")` 后，热门文章缓存反序列化失败。

**原因**：全局 `ObjectMapper` 的序列化器输出空格分隔格式，但默认 `LocalDateTimeDeserializer` 只认 ISO `T` 格式，读写格式不一致。

**解决**：缓存专用 `ObjectMapper cacheMapper = new ObjectMapper().registerModule(new JavaTimeModule())`，与 API 的定制
`ObjectMapper` 隔离。缓存内用标准 ISO 格式，API 输出用自定义格式。

### 8.10 ZSet 替代 String 缓存

**现象**：热门文章缓存用 JSON String 存储整个 `List<Article>`，写入时需 `ObjectMapper` 序列化，读取需反序列化，日期格式、代理对象等问题反复出现。

**解决**：改用 Redis ZSet `cache:hotArticles`，member 只存 `articleId`（Long），score 存 `viewCount`。取 Top N 用 `ZREVRANGE`
，拿到 ID 后 `selectBatchIds` 查库。不再需要 ObjectMapper 参与缓存读写。

**优势**：

- 零序列化——只存 Long ID，没有日期/代理问题
- 实时排序——`ZADD` 更新阅读量即更新排名
- 自然降级——缓存未命中查库重建 ZSet

### 8.11 SimpleVectorStore 构造函数变更

**现象**：`new SimpleVectorStore(embeddingModel)` 编译失败。

**原因**：Spring AI 1.0.0 中 `SimpleVectorStore` 不再提供直接构造函数，改为 Builder 模式。

**解决**：`SimpleVectorStore.builder(embeddingModel).build()`

### 8.12 spring-ai-advisors-vector-store 缺失

**现象**：`import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor` 找不到类。

**原因**：`QuestionAnswerAdvisor` 不在 `spring-ai-core`，需要单独依赖 `spring-ai-advisors-vector-store`。

**解决**：`pom.xml` 显式加入该依赖，版本由 `spring-ai-bom` 管理。

---

## 九、第三阶段问题记录（文章编辑器重构 + Redis 会话管理）

### 9.1 Options API 模板字符串在 `<script setup>` 中编译失败

**现象**：Tiptap 自定义 NodeView 组件使用 `template: \`...\`` 字符串，Vite 构建通过但运行时编辑器区域空白，控制台无报错。

**原因**：Vite 默认使用 Vue 运行时构建（`@vue/runtime-dom`），不含模板编译器。Options API 的 `template` 字符串不会被编译。

**解决**：改用 `setup()` 返回 `h()` 渲染函数：

```js
// 错误（不编译）
const MyNode = {
  template: `<NodeViewWrapper>...</NodeViewWrapper>`
}

// 正确（h 函数直接生成 VNode）
const MyNode = {
  setup(props) {
    return () => h(NodeViewWrapper, { class: 'my-node' }, [
      h('img', { src: props.node.attrs.src })
    ])
  }
}
```

**教训**：在 Vite + Vue 3 `<script setup>` 中定义组件必须用 `h()` 或 `defineComponent` + `setup()`，不能用字符串模板。

### 9.2 ByteMD 分栏模式无法满足所见即所得需求

**现象**：用户要求"插入图片直接出现在编辑框里，点击图片就能看到上传地址"，但 ByteMD 是分栏 Markdown 源码编辑器，图片显示为
`![alt](url)` 文本。

**原因**：ByteMD 本质是 CodeMirror 代码编辑器 + 右侧渲染预览，不支持 WYSIWYG。用户需要的是富文本编辑器体验。

**解决**：整个编辑器从 `@bytemd/vue-next` 迁移到 `@tiptap/vue-3`：

| 对比   | ByteMD             | Tiptap                           |
|------|--------------------|----------------------------------|
| 编辑模式 | Markdown 源码 + 预览分栏 | 单栏 WYSIWYG                       |
| 图片显示 | `![](url)` 文本      | 图片直显                             |
| 图片缩放 | 不支持                | 自定义 NodeView 拖拽缩放                |
| 图片说明 | 不支持                | 自定义 caption 属性                   |
| 工具栏  | 无                  | Bold/Italic/H1-H3/引用/代码/列表/链接/图片 |

**安装**：

```bash
npm install @tiptap/vue-3 @tiptap/starter-kit @tiptap/extension-image \
            @tiptap/extension-placeholder @tiptap/extension-link --legacy-peer-deps
```

### 9.3 `@tiptap/extension-image` 不支持拖拽缩放

**现象**：Tiptap 自带的 Image 扩展只能显示图片，不提供缩放功能。

**解决**：创建自定义扩展 `ResizableImageExt`，继承 Image 并添加：

- `width` 属性：存储缩放百分比（如 `"50%"`）
- `caption` 属性：存储图片说明文字
- `VueNodeViewRenderer`：自定义节点视图渲染

图片包裹在 `resize: both; overflow: hidden` 的容器中，浏览器原生 resize 手柄实现拖拽缩放。缩放后通过
`updateAttributes({ width })` 将百分比持久化到节点属性。

### 9.4 flex 全屏布局中 `position: sticky` 无效

**现象**：编辑页侧边栏设置 `position: sticky; top: 16px` 后位置错误，不跟随滚动。

**原因**：全屏 flex 布局中 `.page` 高度恰好 `calc(100vh - 96px)`，页面不产生滚动，`sticky` 没有滚动容器导致位置计算异常。

**解决**：移除 `position: sticky`，仅保留 `align-self: start` 让侧边栏自然对齐顶部。

### 9.5 `align-items: start` 导致 Grid 单元格高度塌陷

**现象**：编辑页 Grid 布局中 `.edit-main` 设置了 `height: 100%` 但仍不撑满，编辑器区域高度为 0。

**原因**：Grid 容器的 `align-items: start` 阻止了单元格的默认 stretch 行为，子元素 `height: 100%` 无法解析。

**解决**：移除 `align-items: start`，给侧边栏单独设置 `align-self: start`。

### 9.6 `height: 0` + `flex: 1` hack 在 ByteMD 中失效

**现象**：给 ByteMD 编辑器设置 `height: 0; flex: 1; min-height: 0` 后编辑器消失。

**原因**：ByteMD 内部用 JavaScript 设置固定高度，CSS `height: 0` 被 JS 覆盖后又因 flex 计算时序问题无法正确展开。

**解决**：改用 `height: 100%` 沿完整的 DOM 链传递高度：

```
.page (height: calc(100vh - 96px), flex column)
  → .edit-grid (flex: 1, grid)
    → .edit-main (height: 100%, flex column)
      → .editor-section (flex: 1, height: 100%)
        → .bytemd (height: 100%)
```

每层父元素都必须有明确的高度值，`height: 100%` 才能正确解析。

### 9.7 `localStorage` → `sessionStorage` 又回退

**背景**：用户先要求"管理端每次访问需要登录"，将 `localStorage` 改为 `sessionStorage` 实现关闭标签即清除登录态。后又要求"
Redis 有凭证就不需要再登录"，又改回 `localStorage`。

**教训**：会话持久化策略应由后端控制（Redis token 有效期），前端使用 `localStorage` 保持 token 不丢失。Token 是否有效由 Redis
决定，不在前端判断。

### 9.8 Sa-Token JWT + Redis：写入但不校验

**现象**：

1. 添加 `sa-token-redis-jackson` 依赖后，Redis 中能看到登录凭证
2. 手动删除 Redis 中的 token key，刷新管理端页面仍能正常访问，未跳转登录

**原因**：JWT 模式的校验链路不经过 Redis DAO。

```
普通模式（不加 sa-token-jwt）：
  请求 → dao.get(token) → Redis → 有则通过

JWT 模式（加 sa-token-jwt）：
  请求 → 解析 JWT 签名 → 签名有效则通过 → 跳过 dao.get()
```

JWT 自包含用户信息，Sa-Token 认为签名有效 = 登录有效，根本不会查 Redis。`sa-token-redis-jackson` 替换了 DAO 实现为 Redis，但
JWT 模式不调 DAO 的 `get()` 方法，所以 Redis 写入正常但不会被查询。

**解决**：新增 `TokenRedisInterceptor`，在 SaInterceptor 之后对每个 `/api/**` 请求执行
`redis.hasKey("Authorization:login:token:" + token)`。Redis 中 key 不存在 → 抛 `NotLoginException` → 前端 HTTP 拦截器捕获
401 → 清除 localStorage → 跳转登录。

**核心代码**：

```java
public class TokenRedisInterceptor implements HandlerInterceptor {
    private static final String PREFIX = "Authorization:login:token:";

    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String token = request.getHeader("Authorization");
        if (token == null) return true;
        if (Boolean.FALSE.equals(redis.hasKey(PREFIX + token))) {
            throw new NotLoginException("admin", "token", "凭证已失效");
        }
        return true;
    }
}
```

**前端配合**：`http.js` 新增错误拦截器：

```js
http.interceptors.response.use(
  (res) => { ... },
  (err) => {
    if (err.response?.status === 401 || err.response?.status === 403) {
      localStorage.removeItem('admin_token')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)
```

### 9.9 `StpLogicJwtForSimple` 在 Sa-Token 1.38 中不存在

**现象**：尝试通过继承 `StpLogicJwtForSimple` 自定义 StpLogic 时编译失败，类找不到。

**原因**：Sa-Token 1.38 的 `sa-token-jwt` 模块中不存在该公开类，或类名不同。

**解决**：放弃继承方案，改用 Spring `HandlerInterceptor` 方式实现 Redis 二级校验。

### 9.10 `NotLoginException` 构造函数签名的反复尝试

**现象**：`new NotLoginException("message")` 和 `NotLoginException.newInstance("type", "key")` 均编译失败。

**解决**：Sa-Token 1.38 的 `NotLoginException` 需使用三参数构造函数：

```java
new NotLoginException("admin", "token", "凭证已失效，请重新登录")
```

三个参数分别为：loginType、loginKey、message。

### 9.11 Sa-Token 异常被全局 500 兜底

**现象**：后端日志出现 `GlobalExceptionHandler: Unhandled error on /api/auth/me`，前端收到的响应是
`{"code":500,"message":"系统繁忙"}` 而非 401。

**原因**：`GlobalExceptionHandler` 只处理了 `IllegalArgumentException` 和 `MethodArgumentNotValidException`，Sa-Token 的
`NotLoginException` 和 `NotPermissionException` 落到 `Exception.class` 兜底处理，HTTP 状态码为 200（由
`@RestControllerAdvice` 默认行为决定），`data.code` 为 500。

**解决**：在 `GlobalExceptionHandler` 中新增两个处理器：

```java
@ExceptionHandler(NotLoginException.class)
public ApiResponse<Void> handleNotLogin(NotLoginException e) {
    return ApiResponse.fail(401, "请先登录");
}

@ExceptionHandler(NotPermissionException.class)
public ApiResponse<Void> handleNotPermission(NotPermissionException e) {
    return ApiResponse.fail(403, "权限不足");
}
```

**教训**：引入任何安全框架时，必须为其异常类型添加专用的 `@ExceptionHandler`，否则错误信息会被通用兜底淹没，且 HTTP
状态码不正确会导致前端拦截器无法识别。

### 9.12 `FileService.upload()` 目录硬编码

**现象**：上传文章图片时所有文件都存到 `/uploads/avatars/` 目录，与头像混在一起。

**原因**：`FileService.upload()` 中 `String subDir = "avatars"` 硬编码。

**解决**：新增 `upload(MultipartFile file, String subDir)` 重载方法，文章图片传 `"articles"`，头像继续用默认 `"avatars"`。
`FileController` 新增可选参数 `@RequestParam(required = false) String subDir`。

### 9.13 取消编辑需清理本次会话上传的图片

**现象**：用户在编辑页上传图片后点击"取消"，图片文件保留在后端但文章未保存，成为孤儿文件。

**解决**：前端维护 `uploadedUrls` Set，追踪本次会话中上传的所有图片 URL：

- 点击"取消"/"返回" → `Promise.all` 并发调用 `DELETE /api/file/delete` 清理
- 保存成功 → 对比新旧 HTML 中的图片 URL，仅删不再引用的
- 关闭标签/浏览器后退 → `onBeforeUnmount` 中 `fetch` + `keepalive: true` 尽力清理

### 9.14 `flex` 全屏布局迁移到编辑器置顶自然流布局

**现象**：全屏 flex 布局（`height: calc(100vh - 96px)`）中反复出现高度计算问题、sticky 失效、grid 塌陷等问题。

**最终方案**：放弃强制全屏，改为编辑器置顶的自然滚动布局：

- `.page` 移除固定高度约束
- 编辑器设置 `height: 65vh; min-height: 550px; max-height: 80vh`
- 发布设置和基本信息在编辑器下方自然滚动
- 编辑体验更好，布局更稳定

**教训**：复杂 flex 嵌套高度计算容易出 bug。编辑器为主的页面用固定 vh 高度更可控，信息型页面用自然流布局更健壮。

### 9.15 `contentMd` 存储格式从 Markdown 变为 HTML

**背景**：Tiptap 输出 HTML 而非 Markdown，`contentMd` 字段现在存储的是 HTML。

**兼容方案**：

- 后端 `ContentService.mdToHtml()` 检测内容是否以 `<` 开头，是则跳过 Flexmark 转换直接透传
- 前端 `ArticleDetailView.vue` 的 markdown-it 配置 `html: true`，HTML 内容直接透传渲染
- 旧文章（Markdown）和新文章（HTML）在同一个字段中共存，前端无需改动

### 9.16 问题速查表（第三阶段）

| 现象                       | 关键词          | 根因                 | 章节   |
|--------------------------|--------------|--------------------|------|
| NodeView 不渲染             | 编辑器空白        | template 字符串不编译    | 9.1  |
| 图片无法直接显示                 | 分栏编辑         | ByteMD 源码编辑器       | 9.2  |
| 图片无法缩放                   | resize 不生效   | Image 扩展无此功能       | 9.3  |
| 侧边栏位置错误                  | sticky 偏移    | 页面高度固定无滚动          | 9.4  |
| 编辑器高度为 0                 | 消失不见         | align-items: start | 9.5  |
| 编辑器消失                    | height:0 不生效 | ByteMD JS 覆盖 CSS   | 9.6  |
| 删 Redis key 仍能访问         | 登录不失效        | JWT 不查 DAO         | 9.8  |
| StpLogicJwtForSimple 找不到 | 编译失败         | 1.38 无此类           | 9.9  |
| NotLoginException 编译失败   | 构造函数不匹配      | 需三参数               | 9.10 |
| /api/auth/me 返回 500      | 异常兜底         | 缺 Sa-Token 异常处理器   | 9.11 |
| 图片存到 avatars 目录          | 目录混乱         | subDir 硬编码         | 9.12 |
| 取消编辑留孤儿文件                | 文件残留         | 未追踪上传              | 9.13 |
