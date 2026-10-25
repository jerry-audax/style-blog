# 后端开发

## 发布保护机器协议（MEDIA-05B5A）

新增默认关闭的`/internal/media-publications`机器接口，提供prepare/get/verify/release，复用012表与publication凭据，不改博主登录权限。服务端固定环境/发布者，严格有界JSON、常量时间认证，prepare在资源锁后复核解析身份。详见[机器接口契约](docs/media-publication-machine-api.md)。此时发布器尚未接线，PUBLICATION覆盖仍不完整，不能开启物理删除；没有业务迁移或真实启用。

输出校验对尚不支持的CSS字符串图片函数明确拒绝，避免把`image-set()`中的资源漏算为空清单。05B5B2已收尾原三项传输差异：语义/解析超限统一413，仅接受具体application/json，任何Origin优先403；路由仍默认关闭，详见契约末节。

## 维护导入资源写保护（MEDIA-05B4B3，2026-10-24）

维护导入先登记资源并规划新增业务行，再一次保护全批引用，最后执行歌曲/视频幂等插入与收据提交。资源锁使用UUID全局顺序，早于任何业务INSERT；重复导入不覆盖已有编辑或恢复软删除记录。兼容仅005基础媒体表，不自动执行011/012/013。

离线import CLI现在必须显式提供非敏感`IMGBED_BASE_URL`（仅origin，不能带路径/凭据）；可选`MEDIA_IMPORT_IMGBED_ALLOWED_ORIGINS`为逗号分隔的历史origin白名单。不需要图床Token：这里只做本地链接解析及数据库写保护，不访问图床。维护wrapper继承这两个进程环境变量；须在执行任何需单独批准的真实导入前配置，不能拿用户输入的任意封面域名当白名单。

本阶段仅代码与隔离测试，未执行本地业务库导入、迁移、启用或线上操作。测试与审查状态见[实施记录](../docs/media-reference-write-protection-2026-10-18.md)，后续发布协议/Worker/真实POC仍未完成。

Spring Boot 单体，六个物理层模块：types → domain；application/infrastructure 向内依赖 domain/types；trigger 调用 application/domain；app 仅启动、配置和装配。唯一执行制品 `blog-app/target/blog-app.jar`，不是根聚合 POM。

domain 按业务定义模型、规则与 repository/gateway；MyBatis 的 mapper/record/converter 及仓储实现留 infrastructure，先技术/数据职责再区分业务。详见[架构与证据](../docs/backend-layering-implementation-2026-10-16.md)、[技术基线](../docs/technical-development-baseline.md)。

## 从项目根目录运行

```powershell
pwsh -NoProfile -File blog-server/tools/architecture/test-backend.ps1 -WithMediaPostgres -Package
node --test blog-server/tools/architecture/*.test.mjs
./blog-server/tools/start-local-backend.ps1
npm test --prefix blog-server/music-runtime
```

入口加载根 local.env/.env.imgbed.local，保持后端工作目录 blog-server，AI 关闭。独立启动需安全注入环境后运行本模块 JAR；不从聚合父工程执行 exec:java/spring-boot:run，不杀所有 Java/Node，不对使用 target/classes 的运行服务并行 clean。

本地音乐数据库维护（2026-10-17）：测试打包后，使用 `pwsh -NoProfile -File blog-server/tools/music/start-local-music-management.ps1 -EnableMediaUploads` 显式启动postgres歌单及受控上传，AI保持关闭；不带开关则上传关闭，不自动建表/停进程。批量封面工具、备份和剩余项见[执行记录](../docs/music-cover-backfill-2026-10-17.md)。线上/默认配置不随本地命令改变。

本地IDE开发profile已默认选择postgres目录（`MEDIA_CATALOG_STORAGE`可显式覆盖），避免启动后又扫描旧图床列表而漏掉数据库封面；不自动建表、不默认启用上传。生产profile与底层未指定profile时的旧目录兼容选项保持不变。上面的通用wrapper为兼容历史使用者仍默认legacy-json，操作已迁移的本地库请显式加`-MediaCatalogStorage postgres`，或使用音乐维护入口。

## 本地媒体移除能力（2026-10-18，MEDIA-05A）

本地blog_system已按用户批准完成项目外备份和仅011迁移，新后端复用已测试JAR的校验副本启动于127.0.0.1:8080。当前显式参数为`media.catalog.storage=postgres`、`media.catalog.deletions.enabled=true`、`media.catalog.deletions.environment-id=local`；AI、uploads、physical-delete均false，SQL自动初始化仍never。仅从本站撤下和保存回执，不删除图床文件；34首歌/30个已入库封面与数据库一致，82条资源未更改，公开查询200、后台未登录401。

上述是本次本地进程配置，不是默认配置或线上已切换；两个现有启动入口仍不会默认开启删除，未修改用户YAML。运行副本避免锁定target制品；后续停止前须重新核对实际监听进程，Oracle Java启动器PID可能不同于JDK服务子进程。备份、运行路径、验证与博主界面待验收项见[实施记录](../docs/media-deletion-receipts-2026-10-18.md)。

## 发布保护基础（2026-10-18，MEDIA-05B1）

新增纯领域pin/覆盖证据、专用012迁移及MyBatis仓储、事务prepare/release。空清单有耐久记录；跨环境持有查询、同清单重放、不同清单冲突、ACTIVE资产锁和版本释放均已真实PG验证；用例自己拥有事务，拒绝活动外层事务，超过1000资源按全局UUID顺序分批锁定。无新HTTP、用例Bean或Worker接线，业务库未执行012，当前8080继续运行05A副本，物理删除仍关闭。

最终后端581项（0失败/错误、3既有opt-in跳过）及打包、架构38/38、管理端170/170、公开端127/127和两端构建通过，独立审查无Critical/Important/Minor。公开端首轮Windows EBUSY发生在临时副本清理，完整重跑通过但锁原因未确定；旧包精确清理另被环境策略拒绝，未绕过。交付接口、裁定、制品与未完成接线详见[05B1记录](../docs/media-publication-pins-2026-10-18.md)。不代表MEDIA-05或全部待办完成，不推送/合并。

## 引用预览增量（2026-10-18，MEDIA-05B2）

新增纯链接解析gateway及MyBatis只读引用仓储，核对未删除的歌曲/视频绑定、文章封面与HTML/Markdown正文、账号头像、已入库外部封面和跨环境HELD发布pin。禁用记录、草稿和第二页之后的引用也计入；解析或来源失败保留已发现用途，不能当作零引用。复用flexmark/Jsoup，无供应商请求、新表或业务库迁移。

博主删除预览追加`usages`、十进制字符串`usageCount`、`sourceFailures`，每资源只返回排序后的前50条用途，总数不截断；不返回对象键、URL、正文或SQL错误。新配置`media.catalog.references`支持显式历史`allowed-origins`、`max-content-chars`（1048576）和`max-links`（10000），origin始终包含可信`imgbed.base-url`。实际发布协议/旧版本覆盖未接通，PUBLICATION仍不完整，物理删除关闭，提交全部保留策略不变。

最终后端619项（0失败/错误、3既有opt-in跳过）/打包及架构38/38、管理端185/185、公开端127/127与两端构建通过；一次审查发现歧义srcset错误正引用，已RED→GREEN修复。后续单独获用户批准，仅本地8080切换到校验副本，Chrome真实预览/取消通过；34歌/30封面、82资源和零删除台账不变，012仍未迁移。AI/上传/物理删除继续关闭，线上未动。初次截图超时不当作视觉验收；证据与剩余边界见[05B2记录](../docs/media-reference-preview-2026-10-18.md)。

## 文件归属

- `tools/`：架构验证、启动、数据库转换/核对、媒体导入 wrapper；不包含迁移 SQL。
- 根 [migration](../migration/README.md)：初始化/维护/增量 SQL 单一来源。未来 SQL 文件名 `NNN_YYYYMMDD_specific_change.sql`，已执行文件不覆盖；不自动执行，编号不等于批处理顺序。
- [music-runtime](music-runtime/package.json)：官方个人 CLI 真实依赖；[资料接入](docs/music-provider.md)。后端根无独立 npm/Vite 应用。
- `.secrets/`：后端私有凭据，排除 Git 和 Docker；根 data/music 是授权/业务状态，不能当缓存删除。
- [质量待办](docs/quality-backlog.md)：建议而非已实现承诺；[原功能待办](../docs/todo-media-home-tags-2026-10-06.md)继续有效。

测试只允许 blog_media_test 随机 schema，禁止误连业务库/family_memory。此整理不执行迁移/导入/图床写入，不改线上开关。图片音视频二进制仍在图床，元数据迁移保持显式选择。独立 blog-ai、主站 AI/依赖/历史数据保留且默认禁用。

## 图片元数据闭环（2026-10-18，MEDIA-05B3）

图片上传复用现有耐久台账，IMAGE_CREATE按ARTICLE/COVER/AVATAR冻结用途，成功为独立schema2图片事实；media_asset与SUCCEEDED同事务。文件仍在图床，失败/未知按原operationId核对与恢复，不自动重传/清理。真实签名及全量SHA-256在供应商请求前核验；头像2MiB、正文/封面10MiB，DIRECT，原音乐/视频协议与schema1保持。

postgres图片库为博主GET `/api/admin/media/images`，只读当前provider/root下ACTIVE/IMAGE元数据，total/assetVersion为十进制字符串，NULL大小与0不同，不访问供应商索引。images能力独立于音乐/视频和删除；上传关闭仍可读，旧`/api/images`上传/列表/删除均503。legacy-json只有显式模式保留旧入口，不推断未知能力。

增量013只扩006现有CHECK；未执行业务库迁移、未重启启用本段新版本，AI/上传/物理删除继续关闭。旧版本不认识schema2，不能通过降约束/删回执回退。全部业务写保护、发布机器协议、受控图片删除和真实图床POC继续后续；完整自动化与启用门槛见[05B3记录](../docs/media-image-metadata-2026-10-18.md)。

## 文章与头像引用写保护（2026-10-18，MEDIA-05B4A）

postgres模式在原保存事务内解析文章封面、原正文及实际渲染HTML，或写后保留的头像；按UUID字符串全局顺序分批锁旧/新资源，再锁业务行并核对媒体快照。写后受管引用须已登记且锁后ACTIVE；旧未知/失效链接可明确移除，不能在昵称更新时以“头像未改”为由绕过。没有新增引用表、DDL、HTTP路由或文章版本字段。

账号登录只更新登录时间及可选升级密码，改密码只写密码，已有用户启停只写status/updated_at，避免迟到整行写入恢复旧头像。头像字段省略/NULL保留、空串清空；省略的资料字段不会被旧快照覆盖。

专用冲突返回HTTP409/code409“资源引用已变化或尚未登记，请先核对资源后重试”；保护不可用返回503/code503“资源引用保护暂不可用，请稍后重试”。响应不附对象键、原URL或SQL。管理端原保存流程保留草稿/上传头像和会话，不自动重试或重传；发布沿用POST `/api/content/article` 的status=1，不新增publish路由。

本段于2026-10-19完成：完整后端144类715项（0失败/错误、3既有opt-in跳过）、打包及架构38、管理端240、公开端127和两端构建通过。唯一审查的两项Important已通过RED→GREEN修复：实际HTML显式解析，Markdown代码示例不误报；修复后完整门禁再次通过。详细命令、裁定和后延项见[引用写保护记录](../docs/media-reference-write-protection-2026-10-18.md)。配置复用已有media.catalog.storage，保护不依赖uploads=true；legacy-json（含现有默认）显式兼容，未知模式/缺依赖不降级。启用新版本前须核对历史受管图片登记；不会为保存失败自动补录或传图。未迁移业务库012/013、未重启服务、未操作图床或线上。音乐/视频/维护导入/上传finalizer及发布/Worker仍待后续，物理删除继续关闭。业务快照/行锁数据库异常的局部503映射列入MEDIA-05B4A-M1；既有并发密码升级问题单列AUTH-CONCURRENCY-01。
