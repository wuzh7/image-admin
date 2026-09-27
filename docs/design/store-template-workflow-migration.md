# 店铺管理套图流程迁移设计

状态：方案已落地到本地实现，待业务数据和生产 OSS 配置验收。

## 1. 目标

将 `/Users/wuzh/Downloads/canvas-2d-mockup-demo/` 的功能完整迁移到现有图片管理系统，保留 demo 内容区域的视觉和交互流程，移除 iframe、IndexedDB 和前端写死业务数据。

本次流程为：

```text
背景模板校准
  -> 模板组
  -> 模板中心
  -> 正式图库右侧任务配置区
  -> 渲染结果
  -> 上架表格
```

`选图套图`不作为菜单；它的功能嵌入现有`正式图库`页面。`任务中心`不迁移。

## 2. 已确认的边界

### 2.1 菜单

`店铺管理`与`订单明细`同级，下面有五个子页面：

```text
店铺管理
├── 背景模板校准
├── 模板组
├── 模板中心
├── 渲染结果
└── 上架表格
```

现有的`图序套图工作台` iframe 菜单在新流程验收后移除。

### 2.2 视觉与系统边界

- demo 内容区域的视觉、卡片、按钮、流程提示和渲染预览保持 1:1。
- 顶部栏、左侧主菜单、登录、权限和路由仍使用现有系统。
- 不再嵌入独立 HTML 页面，不保留 demo 自己的侧边栏。

### 2.3 数据边界

- 模板图片上传 OSS。
- 模板配置、校准参数、图片 OSS key/URL、类目和用户归属保存后端。
- 模板中心只展示当前用户自己的数据，并按类目过滤。
- 模板中心类目筛选使用下拉框，不展示已删除数据。
- 未保存的背景校准只存在当前页面，刷新后丢失。
- 不设计独立校准版本、编辑已有校准、参数导入导出和背景管理。

### 2.4 渲染边界

- 统一使用后台 Node Worker 渲染，不在浏览器执行 Canvas 渲染。
- Node Worker 复用 demo 的 JavaScript 场景运行时，保证渲染结果一致。
- 后端保存批次和每张图片的渲染明细，Node Worker 通过租约领取明细。
- 当前页面显示“渲染中，请勿操作当前页面”，不进入任务中心。
- 页面关闭或刷新后，后台任务继续执行，重新打开页面可从后端恢复进度。
- 同一用户、模板组、模板、图片和相同渲染配置不重复渲染。

## 3. 页面设计

### 3.1 背景模板校准

保留：

- 背景图选择或上传
- 背景位置、尺寸、套图区域调整
- 类目选择
- 进入模板组创建
- 当前校准完成后的“验收并发布”操作

移除：

- 编辑已有校准
- 参数导入导出
- 背景管理
- 从模板中心进入的“校准新背景”
- 模板中心底部“新增背景可视化校准”整块卡片和入口
- 顶部“+ 新背景”
- 顶部“保存草稿”
- 独立校准版本

保存模板组时，把最终校准参数作为模板组配置的一部分写入后端。

### 3.2 模板组

模板组创建流程：

1. 选择类目。
2. 使用当前背景模板校准结果。
3. 上传一张或多张模板图片到 OSS。
4. 配置模板名称、尺寸、套图区域和展示信息。
5. 保存模板组及模板。

模板组卡片展示：

- 名称
- 类目
- 模板数量
- 缩略图
- 创建时间

模板组卡片只保留“使用此组”“编辑”“删除”等必要操作，不提供状态切换和配置导出。

### 3.3 模板中心

- 删除 demo 中的写死数组和本地初始化数据。
- 页面进入时读取后端数据。
- 类目筛选使用下拉框，不使用自由文本或卡片筛选。
- 类目过滤由后端和前端共同完成，后端必须强制限制用户范围。
- 不显示分页控件，模板卡片区域使用滚动布局。
- 建议使用虚拟列表，避免模板数量增加后一次性渲染过多 DOM。
- 不显示“已删除”数据。
- 移除模板中心顶部的 JSON 示例和导入说明区域。
- 移除“下载示例 JSON”。
- 移除“导入组 JSON”。
- 移除模板组卡片上的“停用”。
- 移除模板组卡片上的“导出配置”。
- 移除“校准新背景”入口及其整块引导卡片；背景校准只能从`背景模板校准`菜单进入，并在创建模板组时使用当前页面的临时结果。

### 3.4 正式图库右侧任务配置区

修改现有 `web/src/pages/image/dedup/index.tsx` 的正式图库分支，在图库网格右侧增加固定宽度面板。

面板字段：

- 当前类目
- 模板组
- 模板
- 目标店铺
- 上架表格模板
- 输出格式
- 图片质量
- 已选图片数量
- 可生成模板数量
- 预计生成数量

点击开始后：

1. 前端校验图片均为正式图库图片且类目匹配。
2. 后端创建渲染批次和渲染明细。
3. 返回已完成、可复用、待处理和失败明细。
4. 后台 Node Worker 按明细处理图片。
5. 页面锁定当前面板，显示后端实时进度。
6. 全部完成后生成上架表格。

页面锁定只针对当前正式图库操作区，不阻塞用户退出登录等全局功能。

### 3.5 渲染结果

提供：

- 按批次、类目、模板组、模板筛选
- 查看原图和生成图
- 查看成功、跳过、失败状态
- 重新处理失败明细
- 下载单张、批次 ZIP 或批次全部结果

渲染结果页面只展示渲染产物及其状态，不复用上架表格页面的商品与 SKU 核对区域。

### 3.6 上架表格

上架模板准备按截图中的顺序操作：

1. 选择店铺。
2. 选择类目。
3. 上架模板表格；上传后识别 Excel 表头和 SPU/SKU 样板，并保存原文件。
4. 打开“生成配置 / 标题词库”，填写当前店铺、类目的生成参数。
5. 点击“保存生成配置”，将参数保存到后端；新渲染批次使用保存后的配置快照。

页面按店铺和类目展示已上传的模板，允许替换文件和删除；不显示“已删除”复选框，也不提供“停用”按钮。删除的模板不进入新任务选择，已生成的历史导出文件仍可查看和下载。

渲染完成后，根据结果生成 XLSX，保存导出记录，并提供 XLSX 或结果 ZIP 下载。

上架表格页面不展示逐张图片核对流程：

- 移除“商品与 SKU 核对”整块区域。
- 移除其中的 SKU 明细、原图 URL、轮播图和 SPU 详情展开内容。
- 移除批次摘要右侧的单张图片按钮列表。
- 不提供按图片切换核对入口。

页面只保留批次选择、批次摘要、生成文件卡片和下载操作。

表格生成必须读取后端保存的渲染结果，不能依赖当前浏览器页面是否还开着。

## 4. 领域数据设计

建议新增店铺套图模块，表名使用独立前缀，避免污染现有 `img_*` 图片入库表。

### 4.1 店铺

`store_shop`

- `id`
- `owner_id`
- `name`
- `platform`
- `status`
- `config_json`
- 审计字段

### 4.2 模板组

`store_template_group`

- `id`
- `owner_id`
- `category_id`
- `name`
- `calibration_config_json`
- `status`
- 审计字段

权限查询必须包含 `owner_id = 当前登录用户`，管理员查看全部时走明确的数据范围权限。

### 4.3 模板

`store_template`

- `id`
- `group_id`
- `owner_id`
- `category_id`
- `name`
- `oss_key`
- `image_url`
- `template_config_json`
- `width`
- `height`
- `status`
- 审计字段

数据库保存 OSS key 和业务 URL 元数据。读取时由后端根据现有 OSS 规则生成有效访问地址，不能把短期签名 URL 当成永久地址使用。

### 4.4 渲染批次

`store_render_batch`

- `id`
- `owner_id`
- `category_id`
- `status`
- `selected_count`
- `completed_count`
- `skipped_count`
- `failed_count`
- `listing_profile_json`
- `idempotency_key`
- 审计字段

### 4.5 渲染明细

`store_render_item`

- `id`
- `batch_id`
- `owner_id`
- `template_group_id`
- `template_id`
- `image_id`
- `render_config_hash`
- `status`
- `lease_until`
- `result_oss_key`
- `result_url`
- `result_sha256`
- `error_message`
- `started_at`
- `completed_at`

建立唯一约束：

```text
(owner_id, template_group_id, template_id, image_id, render_config_hash)
```

已完成记录可被新批次复用；处理中但租约过期的记录可被恢复；失败记录允许重试。

### 4.6 上架模板和导出

`store_listing_template`

- `id`
- `owner_id`
- `shop_id`
- `category_id`
- `name`
- `oss_key`
- `original_filename`
- `status`
- 审计字段

`store_listing_profile`

- `id`
- `owner_id`
- `shop_id`
- `category_id`
- `config_json`（标题词库、SKU 编号、预览图和轮播图等生成参数）
- 审计字段

同一用户、店铺和类目只保留一份当前生成配置；渲染批次创建时复制为 `store_render_batch.listing_profile_json`，避免后续修改影响历史导出。

`store_listing_export`

- `id`
- `owner_id`
- `batch_id`
- `shop_id`
- `listing_template_id`
- `status`
- `oss_key`
- `error_message`
- 审计字段

## 5. 后端接口设计

接口统一使用现有认证上下文确定 `ownerId`，客户端不得提交用户 ID 作为数据范围依据。

### 5.1 类目和店铺

```text
GET  /store/catalog
GET  /store/shops
POST /store/shops
PATCH /store/shops/{shopId}
```

### 5.2 模板组和模板

```text
GET   /store/template-groups?categoryId={id}
GET   /store/template-groups/{groupId}
POST  /store/template-groups/upload-policy
POST  /store/template-groups
PATCH /store/template-groups/{groupId}
DELETE /store/template-groups/{groupId}
GET   /store/templates?groupId={id}&categoryId={id}
```

`POST /store/template-groups` 接收已经上传完成的 OSS 对象信息、类目、校准配置和模板配置。

### 5.3 渲染

```text
POST /store/render-batches
GET  /store/render-batches
GET  /store/render-batches/active
GET  /store/render-batches/{batchId}
GET  /store/render-batches/{batchId}/download?nameMode={mode}
POST /store/render-items/{itemId}/claim
POST /store/render-items/{itemId}/complete
POST /store/render-items/{itemId}/fail
GET  /store/render-results
```

`claim` 返回短租约，防止多个浏览器标签页重复处理同一明细。`complete` 必须提交结果 OSS key、文件摘要、尺寸和渲染配置哈希。

### 5.4 上架表格

```text
GET  /store/listing-templates
POST /store/listing-templates/upload-policy
POST /store/listing-templates
PATCH /store/listing-templates/{templateId}/file
DELETE /store/listing-templates/{templateId}
GET  /store/listing-profiles?shopId={id}&categoryId={id}
PUT  /store/listing-profiles?shopId={id}&categoryId={id}
POST /store/listing-exports
GET  /store/listing-exports
GET  /store/listing-exports/{exportId}/download
```

## 6. 渲染状态机

批次状态：

```text
CREATED -> RUNNING -> COMPLETED
                  -> PARTIAL_FAILURE
                  -> FAILED
```

明细状态：

```text
PENDING -> RUNNING -> SUCCEEDED
                  -> FAILED
PENDING -> SKIPPED
```

恢复规则：

1. 页面打开时读取当前用户最近的未完成批次。
2. `SUCCEEDED` 和 `SKIPPED` 不再执行。
3. `RUNNING` 且租约未过期的明细暂不抢占。
4. `RUNNING` 且租约已过期的明细重新进入可领取状态。
5. `FAILED` 明细由用户在渲染结果页面触发重试。

## 7. 前端模块拆分

```text
web/src/pages/store/
├── calibration/
├── template-group/
├── template-center/
├── render-result/
└── listing/
```

渲染引擎和 58 个场景资源作为纯前端渲染模块复用，不复用 demo 的页面状态、IndexedDB、MockService 和独立导航。

正式图库仍由：

```text
web/src/pages/image/dedup/index.tsx
```

负责图片选择和任务配置面板。

## 8. 菜单、权限和路由迁移

### 8.1 菜单

新增一个 `店铺管理` 目录菜单及五个子页面菜单，使用独立权限前缀 `store:*`。

删除或停用旧菜单：

```text
图序套图工作台 / mockup/index
```

具体菜单 ID 在实施时从现有 SQL 的空闲号段分配，并同步 `sys_role_menu`，不能只改前端菜单。

### 8.2 动态页面

在 `web/src/pages/dynamicPage.tsx` 增加五个真实 React 页面映射，并移除 `mockup/index` 映射和 iframe 页面。

### 8.3 权限

最低权限集合：

```text
store:calibration:list
store:calibration:save
store:template-group:list
store:template-group:add
store:template-group:edit
store:template-center:list
store:render:create
store:render:list
store:render:retry
store:listing-template:list
store:listing-template:save
store:listing:export
```

图片用户可以创建自己的模板、渲染自己的图库图片并导出自己的上架表格；管理员拥有跨用户查看权限。

## 9. 实施顺序

### 阶段一：契约和数据层

- 确认表结构、状态机和唯一约束。
- 接入当前用户和类目数据范围。
- 接通 OSS 上传策略和结果文件访问。

验证：后端单元测试、数据库初始化和权限查询测试通过。

### 阶段二：校准、模板组、模板中心

- 迁移 demo 校准交互和场景资源。
- 实现模板图片 OSS 上传。
- 替换所有写死数据为 API 数据。
- 实现用户和类目过滤、滚动模板展示。

验证：刷新后未保存校准丢失；保存后的模板组可以重新打开；其他用户看不到该模板组。

### 阶段三：正式图库任务配置区

- 在正式图库右侧增加任务面板。
- 接入模板组、模板、店铺和上架模板选择。
- 后端创建批次和明细。
- 接入后台 Node Worker 渲染。

验证：选中多张正式图库图片后可以创建批次，页面显示渲染进度，结果写入 OSS 和数据库。

### 阶段四：恢复、去重和结果

- 实现租约领取、完成、失败和恢复。
- 实现重复渲染跳过。
- 实现渲染结果页面和失败重试。

验证：刷新页面后能继续未完成明细；重复提交不会重复生成相同图片；并发标签页不会重复领取同一明细。

### 阶段五：上架表格和菜单切换

- 接入按店铺、类目上传和识别上架模板，再编辑并保存生成配置。
- 根据已完成结果生成并保存 XLSX。
- 新菜单验收后移除 iframe 菜单和静态生产入口。

验证：生成配置保存后刷新仍可读取；“停用”和“显示已删除”不出现；生成的 XLSX 可以下载；页面刷新后仍能查看导出记录；旧 `mockup` 路由不再出现在用户菜单中。

## 10. 明确不做的内容

- 不保留 iframe。
- 不保留 IndexedDB 作为业务数据源。
- 不新增选图套图菜单。
- 不新增任务中心。
- 不做独立背景管理。
- 不做已有校准编辑。
- 不做校准参数导入导出。
- 不做模板中心的已删除数据展示、示例 JSON 下载和组 JSON 导入。
- 不做模板中心的 JSON 示例和导入说明区域。
- 不做模板组卡片的“停用”和“导出配置”。
- 不做上架模板列表的“停用”和“显示已删除”。
- 不做上架表格页面的“商品与 SKU 核对”区域。
- 不做批次摘要右侧的单张图片按钮列表。
- 不做模板中心的“校准新背景”入口及引导卡片。
- 不做背景校准页的“+ 新背景”和“保存草稿”。
- 不做校准版本管理。
- 不复制一套图库。

## 11. 主要风险

1. demo 的场景资源体积较大，必须将渲染资源与页面代码解耦，避免每个页面重复加载。
2. Node Worker 与 Java 后端的运行时、依赖和场景资源需要固定版本，否则同一模板重试可能出现像素差异。
3. 上架模板如果包含复杂 Excel 样式和公式，需要在真实模板文件上验证现有 Excel 能力，不能只验证生成文件能打开。
4. 现有正式图库仍然分页，而模板中心按要求不分页；两者是不同的查询场景，不能共用同一个分页参数。

## 12. 渲染结果选择区的后端接入方案

### 12.1 结论

截图中的区域建议接入后端实现：

- “选择下载批次”由后端返回当前用户可见的渲染批次。
- “图片命名方式”只作为下载参数，不在浏览器本地保存业务状态。
- “下载本批次成品 ZIP”由后端按权限读取渲染结果并生成 ZIP。
- 页面中的批次数量、完成数量和文件状态由后端返回，前端只负责展示。

这样可以避免浏览器一次性读取全部结果文件，也不会把 OSS 地址、用户范围和文件命名规则暴露给前端自行决定。

### 12.2 不直接复用现有图片批次

现有 `img_batch` 和 `/image/batches` 表示图片上传、去重、人工审核批次；截图中的批次表示模板渲染批次。两者状态、明细和生命周期不同，不应给 `img_batch` 增加渲染字段，也不应让渲染结果混入图片审核流程。

可以复用的现有能力：

- 登录用户和数据范围校验。
- `PageQuery` / `PageResult` 分页约定。
- 正式图库图片的元数据和原图访问接口。
- 现有 OSS 客户端、签名 URL 和文件存储配置。
- 现有 ZIP 流式下载和安全文件名处理方式。

不能直接复用的能力：

- `/image/batches` 的批次列表。
- `ImageService.exportApproved`，它导出的是正式图库原图，不是渲染成品。

### 12.3 接口

```text
GET  /store/render-batches?status=COMPLETED,PARTIAL_FAILURE
GET  /store/render-batches/{batchId}
GET  /store/render-batches/{batchId}/download?nameMode=ORIGINAL_NAME
GET  /store/render-results?batchId={id}
```

`nameMode` 只允许：

```text
ORIGINAL_NAME
IMAGE_ID
```

下载接口执行以下检查：

1. 当前登录用户是否有权访问该渲染批次。
2. 批次是否存在可下载的 `SUCCEEDED` 明细。
3. 渲染结果 OSS 对象是否仍然存在。
4. 文件名是否经过安全清洗并解决重复名称。

### 12.4 ZIP 生成

统一由 Node Worker 异步生成 ZIP：

1. 查询批次中的成功渲染明细。
2. 关联原图的原始文件名或图片 ID。
3. 从 OSS 流式读取渲染结果。
4. 写入 ZIP 临时文件并上传 OSS，不把全部文件加载到 Java 或浏览器内存。

文件名规则由后端统一执行：

```text
{原文件名或图片ID}_{模板序号}-{模板编码}.{扩展名}
```

同名文件自动追加序号，ZIP 根目录使用批次目录，避免不同批次文件混在一起。

前端等待归档完成后获取签名下载地址。所有数据量都走同一条链路，不保留同步下载分支。

### 12.5 前端行为

- 批次下拉框只显示当前用户有权限查看的渲染批次。
- 默认选择最近一个有成功结果的批次。
- 文件命名方式改变时只改变下一次下载，不改变数据库数据。
- 没有成功结果时禁用下载按钮。
- 下载期间显示 loading，避免重复点击。
- 页面刷新后重新从接口读取批次和完成数量。
- 渲染结果图片使用后端返回的有效访问地址，不使用 demo 的 `demo-oss.invalid` 地址。

## 13. 对抗式审查：当前不能直接承诺“无缝接入”的地方

### 13.1 正式图库目前没有图库图片选择状态

现有正式图库页面只有分页查询和单图操作，没有“跨分页选择图片”的状态。右侧任务配置区接入前需要增加：

- 图片卡片勾选状态。
- 跨分页保留已选图片 ID。
- 切换筛选条件时显示已选数量。
- 启动前把图片 ID 列表提交后端，由后端重新校验图片仍是正式图库图片、用户可见且类目匹配。
- demo 的真实限制是 `选中原图数量 × 模板数量 <= 64` 个渲染明细，但这是浏览器演示限制，不作为生产前端限制。生产环境由后端按资源和租约配置控制，前端不写死输入图片或成品数量上限；明细过多时后端分块写入同一个批次。

前端只保存尚未提交的选择快照；刷新页面后未提交的选择丢失。批次创建后，后端批次明细成为唯一事实来源。

### 13.2 demo 渲染运行时还没有证明可以直接进入后台 Node Worker

demo 是独立 Vite 工程，包含生成的 Worker 源码和 58 个场景目录。后台 Node Worker 不复用浏览器打包产物，而是提取可在 Node 运行的场景模块和 Canvas 运行时。

实施前必须做一个最小技术验证：

1. 在 Node Worker 中加载一个真实场景和 Canvas 运行时。
2. 从现有正式图库获取一张图片和模板配置。
3. 完成渲染并上传结果到 OSS。
4. 通过现有页面显示结果并生成 ZIP。

这个验证通过后，再迁移其余场景。验证不通过时，需要调整 Node 运行时兼容层，不能先用浏览器 Worker 作为临时生产路径。

### 13.3 OSS 直传环境需要验证浏览器读取能力

当前 OSS 模式下，`/images/{id}/content` 不直接返回文件，而是要求使用签名 URL。Node Worker 读取原图和上传结果需要使用签名 URL 或内部 OSS 客户端，不依赖浏览器 CORS。

因此正式方案必须在实际 OSS 配置下验证：

- Node Worker 能读取原图签名 URL。
- Node Worker 能上传渲染结果。
- 页面能读取结果签名 URL。
- 签名过期后能重新获取，而不会把短期 URL 写死到业务配置。

### 13.4 后台任务的可恢复性

Node Worker 领取明细时写入租约和心跳；进程退出或机器故障后，过期租约会被其他 Worker 重新领取。浏览器只负责提交任务、显示进度和下载结果，不参与渲染，因此关闭页面不会中断任务。

### 13.5 数据删除必须保留历史引用

模板组、模板和上架模板删除后，历史渲染批次和导出文件不能依赖当前列表数据。批次创建时需要保存模板配置、店铺配置和上架生成配置快照，删除只影响新任务选择。

### 13.6 当前的完美结合标准

在以下验收门槛通过前，只能称为“设计上可结合”，不能称为已完美接入：

- 正式图库跨分页选择 20 张图片并成功创建渲染批次。
- 至少一个真实场景完成 Worker 渲染、OSS 上传、结果回显和 ZIP 下载。
- 刷新页面后能恢复未完成明细，已完成明细不重复渲染。
- 两个浏览器标签页不会领取同一个渲染明细。
- 普通用户无法读取其他用户的模板、批次和结果。
- 模板删除后，历史结果仍可查看和下载。

## 14. 大数据量和多账号并发方案

### 14.1 先明确压力模型

渲染工作量近似为：

```text
原图数量 × 模板数量 × 店铺导出数量
```

用户数量增加后，数据库写入、OSS 读取、Worker 占用和进度查询会同时增加。统一后台执行后，扩容只需要增加 Node Worker 实例，不需要维护两套渲染逻辑。

### 14.2 单一后台执行链路

所有任务都走同一条链路，不区分小批次和大批次：

1. 正式图库提交图片 ID、模板组、店铺和输出配置。
2. Java API 校验权限、幂等键和配置快照，在一个 `render_batch` 中批量写入明细。
3. Node Worker 从数据库领取待处理明细，执行渲染、上传 OSS、写回结果。
4. 前端只读取批次聚合进度；浏览器关闭不影响任务。
5. 全部明细完成后，Node Worker 生成 ZIP/XLSX 并上传 OSS，前端获取下载地址。

不引入浏览器 Worker、消息队列框架或父子批次模型。明细过多时只在后端分块写入同一个批次，前端无感知。

### 14.3 多账号隔离与限流

所有店铺套图表必须包含 `owner_id`，查询和写入都由登录上下文产生，不能信任前端传入用户 ID。

至少增加以下保护：

- 每个用户同时运行的批次数上限。
- 每个用户同时领取的渲染明细数上限。
- 全局 Worker 并发上限。
- 创建批次和重试接口的限流。
- 单用户 OSS 存储和每日渲染量统计。
- 管理员查看全部数据时使用单独的数据范围分支。

MySQL 是任务状态的唯一事实来源；现有 Redis 只用于限流和唤醒 Worker，不把 Redis 中的任务状态当成最终结果。没有必要新增消息队列。

### 14.5 数据库和查询策略

必须在首版表结构中加入以下索引：

```text
store_render_batch(owner_id, status, update_time)
store_render_item(batch_id, status, id)
store_render_item(owner_id, status, lease_until, id)
store_render_item(owner_id, template_group_id, template_id, image_id, render_config_hash)
store_template_group(owner_id, category_id, status, update_time)
store_listing_template(owner_id, shop_id, category_id, status)
```

- 创建明细使用批量插入，不逐条提交事务。
- 领取任务使用带状态条件的原子更新或 `SELECT ... FOR UPDATE SKIP LOCKED`，防止多账号、多标签页重复领取。
- 大列表使用服务端分页，结果页按原图分页并按模板顺序返回，不返回全部成品。
- 配置 JSON 只保存渲染参数，不把可查询字段全部塞进 JSON。
- 进度使用批次计数聚合，不让前端逐条轮询所有明细。

### 14.6 文件和下载策略

- 原图、模板图、成品、缩略图和 XLSX 全部放 OSS，数据库只保存 key 和元数据。
- 图片列表只返回缩略图签名 URL，点击查看时再申请原图 URL。
- 所有 ZIP 和 XLSX 都由 Node Worker 异步生成并上传 OSS，前端等待归档完成后下载。
- OSS 对象按用户、批次和结果类型分层命名，配合生命周期清理和引用检查。

### 14.7 必须保留后端快照

这里需要对“不做版本管理”作一个边界修正：可以不做用户可见的版本列表和版本操作，但多账号并发、失败重试和历史结果必须保存不可变快照。

批次创建时至少快照：

- 模板组配置和模板文件 key。
- 渲染引擎版本和场景标识。
- 原图 ID、原图版本或文件哈希。
- 店铺和上架生成配置。
- 输出格式和质量。

否则模板被编辑后，旧批次重试可能得到不同图片，无法证明结果一致。

### 14.8 容量验收梯度

按梯度验证，不直接宣称支持无限数据：

1. 1 个账号、64 个渲染明细，验证完整链路。
2. 5 个账号并发，验证租约、幂等和权限隔离。
3. 20 个账号并发，逐步增加 Node Worker 实例，验证吞吐和恢复。
4. 10,000 张原图和 50 个模板，验证查询、分页、去重和归档压力。

每一档都要记录数据库耗时、OSS 流量、Worker 内存、失败重试率和队列等待时间。

## 15. Java 与 Node Worker 部署方式

### 15.1 不是两个业务后端

Java 仍是唯一对前端提供业务 API 的后端，负责登录、权限、模板组、批次、状态、幂等和数据库事务。Node Worker 是内部执行进程，只负责：

- 领取渲染明细。
- 执行 JavaScript/CANVAS 渲染。
- 上传成品和归档文件到 OSS。
- 回写成功、失败和进度。

Node Worker 不提供公网 API，不重复实现用户、模板和订单业务。

### 15.2 首期部署

同一台服务器、同一套 Docker Compose，增加一个服务：

```text
Nginx
  -> Java API
       -> MySQL / Redis / OSS
       <- render-worker（仅 Docker 内网）
```

前端只访问 Nginx 和 Java API；Node Worker 使用 Docker 内网地址调用 Java 的内部接口，例如领取明细、完成明细、失败重试和创建归档。Node 不暴露端口，也不直接接受浏览器请求。

这样不需要第二套登录、网关或部署平台，也不需要让 Node 直接修改业务表。Java 的内部接口负责原子领取和状态变更，数据库仍只有一个事实来源。

### 15.3 资源与扩容

- Java 和 Node 使用独立容器、独立日志和独立健康检查。
- Node 设置 CPU、内存和并发上限，避免渲染挤占 Java API 资源。
- 单机容量不足时，只增加 `render-worker` 副本；Java、MySQL 和 OSS 不必一起扩容。
- Node 版本、Canvas 依赖和场景资源固定在 Worker 镜像中，Java 镜像不携带 Node 运行时。

### 15.4 失败处理

Node 进程退出后，Java 通过租约超时把明细恢复为待处理，其他 Worker 可以继续领取。部署升级时先停止领取新任务，等待已领取任务完成或租约过期，再替换 Worker 镜像；不影响用户登录和模板管理。
