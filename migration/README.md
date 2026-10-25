# 数据库脚本

## 013 图片上传台账扩展（2026-10-18；尚未执行业务库）

`013_20261018_extend_media_upload_ledger_for_images.sql`前提为005媒体表与006台账。仅以一个原子ALTER替换target/file/result三项CHECK，加入IMAGE_CREATE、IMAGE/AVATAR、精确purpose、2/10MiB及schema2图片回执；旧音乐/视频schema1和历史数据不改，不新增表/列/索引，不执行数据UPDATE/DELETE。新约束名使重复执行刻意失败。

隔离PG已验证旧行/JSON不变、非法直写、重复脚本、006-only及篡改约束拒绝；上传开启的新包只接受已核对013指纹。只读图库不依赖上传台账就绪。业务库仍未执行013；先备份/停止核对的旧服务、仅执行此增量及兼容新包切换均须另批，不顺带执行012或遍历历史脚本。保持SQL init never、上传/物理删除/AI关闭。

保留新回执，不强改schema1或回退约束迁就旧包；旧版uploads=false也不保证旧图片上传旁路已封闭，回退需要保留新协议保护的兼容版本。具体契约和证据见[05B3记录](../docs/media-image-metadata-2026-10-18.md)。

## 012 发布资源保护清单（2026-10-18；尚未执行业务库）

`012_20261018_add_publication_media_pins.sql`仅新增专用`media_publication_pin`及HELD资源数组的GIN索引。每个环境/发布器/版本一行，保留不可变指纹、UUID集合、状态/版本/时间；空清单也有记录，HELD无自动过期。不新增通用业务引用表，不改变现有资源、歌曲、视频或删除台账。

先仅在`blog_media_test`随机schema验证；业务库单批执行需另行批准与备份，不能遍历历史脚本，重复执行应失败。UUID数组不提供资产FK，prepare用例必须在同一事务锁资产并检查全部ACTIVE。当前未接HTTP、发布指针或Worker，不能据此开启物理删除。回退保留清单证据，不直接删表或释放仍在服务/回退的版本。

## 011 媒体删除归属与回执（创建2026-10-17，本地执行2026-10-18）

`011_20261017_add_media_deletion_ownership_and_operations.sql`要求005三张媒体表已存在。
给media_asset增加删除归属及声明审计字段，历史/新增默认UNVERIFIED；新增media_delete_operation和media_delete_item，保存幂等本站移除回执、资源项及后续执行恢复所需字段。
唯一约束保护actor/key和operation/asset；同资源活动执行项唯一，复合FK限制LINKED只能指向同资源的非LINKED项。

脚本不删除歌曲/资源/图床文件，不推断独占，不修改既有绑定。先在blog_media_test随机schema验证；2026-10-18用户另批后已备份并仅在本地blog_system执行011。线上执行仍须另行批准、备份和核对，不自动遍历迁移，重复执行应失败。
05A物理执行禁止开启，实际候选项记录RETAINED；后续不会自动清理这些保留项。回退保留新列/台账，先关闭提交，继续封闭postgres旧图片直删，不能只换旧JAR绕过保护。

2026-10-18本地执行记录：完整备份位于项目同级的`style-blog-backups/media05a-20261018-160826/blog_system-before-011-20261018-160826.dump`，归档110个TOC条目可完整读取，容器与本机SHA-256一致。005前提核对后，仅011事务提交；原15表行数及媒体内容指纹未变，82资源默认UNVERIFIED，34首歌/0视频保留，两张新台账0行。新后端启动时schema verifier通过，跨PostgreSQL格式差异仍失败关闭。未执行其他脚本、未操作线上或图床；配置、回退与证据见[05A实施记录](../docs/media-deletion-receipts-2026-10-18.md)。

## 010 热门标签字段（2026-10-17）

`010_20261017_move_home_tag_selection_to_tag_is_hot.sql`要求tag与009旧表已存在。停止旧后端、备份并单独批准后，仅运行010：给tag增加is_hot（0/1、非空、默认0），将旧选择ID标为热门，再删除旧表。不保留旧槽位顺序，展示改用既有sort/id。无CASCADE，意外依赖会使整批事务回滚；重复执行失败。热门总数不限三个，首页选择/不足三个的整体回退属于domain业务，不加新表。

本地执行已获独立批准并完成：备份`blog_system-before-010-20261017-200824.dump`位于`.artifacts/backups/hot-tags-20261017/`，归档检查及容器/本机SHA-256一致；010提交后tag仍10行，9/8/20三ID转为热门，旧表移除。新JAR本地启动，公开查询200、管理未登录401；线上未操作。旧JAR不兼容迁移后结构，回退须另行确认恢复旧配置表/数据及旧应用，不得直接启动旧版本。自动建表继续关闭。

## 009 首页三标签配置（2026-10-17）

`009_20261017_add_home_tag_selection.sql`是已执行的历史迁移，不修改正文。曾新增首页单例表和初始空行，后由010替代；不可在010后重跑以恢复旧结构。历史备份位于`.artifacts/backups/home-tags-20261017/`，不代表线上执行。

009阶段的执行前提是表不存在且备份/单独批准；该阶段曾采用保留表回退应用。已执行010的环境不适用此历史回退方式，须遵循上方010说明。应用保持自动建表关闭。

状态：当前 SQL 源码统一入口。数据库为 PostgreSQL；文件编号用于识别，**不是可以从 001 连续执行到 006 的升级序列**。

| 文件 | 用途与前提 |
| --- | --- |
| [001_schema_postgresql.sql](001_schema_postgresql.sql) | 历史空库初始化基线；不是已有库升级脚本，不含所有后续媒体表。只能在明确批准的新空库使用。 |
| [002_assign_blog_owner.sql](002_assign_blog_owner.sql) | 为已存在的博主处理角色关系；按本地实际脚本版本核对前提，不创建默认账号或密码。 |
| [003_cleanup_unused_legacy_tables.sql](003_cleanup_unused_legacy_tables.sql) | 历史表清理，可能删除数据。必须先审计与备份，不自动重跑。 |
| 004_reduce_owner_visitor_roles.sql | 历史角色收缩；本机搬迁保留的原未跟踪脚本，不属于本次提交供给。不得据此假定其他检出有该文件，执行需独立核对与批准。 |
| [005_add_media_metadata_tables.sql](005_add_media_metadata_tables.sql) | 新增媒体资源、歌曲、惊喜视频三表；要求相关表尚未存在，重复执行应失败。 |
| [006_add_media_upload_operation_table.sql](006_add_media_upload_operation_table.sql) | 新增上传台账；先验证媒体表结构与版本，重复执行应失败。 |
| [007_20261017_remove_unknown_music_tracks.sql](007_20261017_remove_unknown_music_tracks.sql) | 仅本地已批准的两条UUID标题歌曲记录清理；删除前备份、锁定并核对身份/未编辑资料。保留资源登记及图床文件，禁止当作线上通用清理。 |
| [008_20261017_backfill_verified_music_source_ids.sql](008_20261017_backfill_verified_music_source_ids.sql) | 本地四首歌曲根据线上歌单快照核实准确ID后补source_id；逐项锁定并校验歌曲ID/标题/歌手/初始版本，冲突整批回滚、重复执行不重复增版本。先备份；不覆盖线上、封面绑定、未确认上传或两首无法唯一匹配的歌曲。 |

应用保持 `spring.sql.init.mode=never`，这些脚本不打入运行 JAR；未引入 Flyway、Liquibase 或自动遍历执行器。已有数据库只运行经批准、适用前提满足的单份增量脚本，绝不能用初始化脚本覆盖升级。

历史目录归位仅搬迁文件、不改SQL正文或执行业务库迁移。后续用户单独批准的本地增量执行另按对应脚本记录（009见上），不能理解为允许重跑历史脚本。自动化测试只允许隔离的 `blog_media_test` 随机 schema，不使用业务库或 `family_memory`。

MyBatis 的运行时查询/CAS SQL 仍归 `blog-server/blog-infrastructure`，不是 migration 文件。MySQL 导出转换与快照校验工具在 [blog-server/tools/database](../blog-server/tools/database)，媒体导入 wrapper 在 [blog-server/tools/media](../blog-server/tools/media)。

脚本修改必须同时更新映射/约束、调用工具、测试、文档及回退说明；保留用户已有未提交内容，不夹带权限或表结构调整。

## 后续 SQL 命名与变更规则

2026-10-16 用户确认：后续新增 SQL 使用 `NNN_YYYYMMDD_具体变更.sql`，日期精确到日，变更动作用小写英文下划线明确表达。例如 `007_20261016_add_music_track_index.sql`（仅命名示例，不表示该变更已经发生）。不能只写 update、fix、new 等无法识别实际改动的名字。

已经执行或发布的脚本不覆盖、不改名重放；后续调整另建带日期和具体意图的增量脚本，并补充适用前提、影响、验证和回退说明。尚未执行的草稿若变更日期或意图，也应同步文件名、引用和评审记录。

001–006 是本次仅搬迁、正文未改的历史基线，保留已确认名称，不伪造历史修改日期。编号仅用于识别和排序，不代表可一键执行所有脚本。

### SQL 命名自动检查（2026-10-17已实现）

REQ-MIG-NAME-02 / IMPLEMENTED：`node blog-server/tools/architecture/verify-migration-names.mjs`检查目录，不执行SQL。Jenkins数据库阶段及后端测试wrapper在执行前调用；架构测试覆盖精确历史豁免、有效日期（含闰年）、缺日期、泛化命名和编号重复。15项RED→GREEN；文件名的业务语义仍须评审，不能因此自动执行数据库脚本。

- 在现有架构检查中为上述六个历史文件名建立精确豁免名单；不能按编号范围放行其他未带日期的新文件。
- 其余文件检查 `NNN_YYYYMMDD_specific_change.sql`：三位编号、有效日历日期、非空的具体变更名称；拒绝无日期、无效日期及仅 fix/update/new 的泛化名称，描述是否准确仍需评审。
- 增加合法命名、历史豁免、遗漏日期、无效日期、泛化名称的回归，观察 RED→GREEN，并确保现有 CI 会执行；与该次 SQL 变更一同交付。
- 保持已执行脚本不可覆盖的规则，不改写六份历史 SQL，不为了命名检查自动执行业务库 SQL。
