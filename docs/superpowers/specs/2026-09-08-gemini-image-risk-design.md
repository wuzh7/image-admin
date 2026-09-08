# Gemini 图片侵权风险检测设计

## 1. 目标与边界

在现有图片去重和人工复核流程中加入 Gemini 图片侵权风险检测。员工确认图片与正式图库不重复后，图片立即进入现有正式入库流程并写入 Qdrant，同时创建独立的异步侵权检测任务。Gemini 结果不作为正式入库门禁；任何网络、配额、超时或解析失败都记录为“检测失败”，风险等级保持为空，绝不伪装成低风险。

本次实现包含主站任务管理、独立 AI Gateway、Gemini 调用、数据库状态、主站失败重试与恢复、风险结果展示、最小配置示例和本地自动化测试。不直接操作阿里云 ECS、1Panel、DNS、证书、安全组或真实生产密钥，也不建设 IP/TRO 知识库、搜索 Grounding、AI 结果缓存、Embedding、Redis 队列、AI 调用日志表、在线配置表、Kafka、Nacos 或 Kubernetes。

## 2. 已选方案与取舍

采用“同一仓库、两个独立 Spring Boot 应用”的生产形态：

- 主站继续运行现有 `wuzh-admin`，负责登录、审核、任务状态、重试、正式入库和前端 API。
- 新增轻量 AI Gateway 应用，负责静态服务令牌校验、单次 Gemini 调用和标准化响应，不连接业务数据库。
- 本地 Compose 可同时启动两个应用；生产时将 Gateway 单独部署到新加坡 ECS。
- 主站只使用 MySQL 作为任务唯一事实来源和工作队列，Redis 完全不参与侵权检测。现有图片后台任务已经采用数据库持久状态与定时 Worker，V1 直接复用该模式。

未采用的方案：

1. 主站直接调用 Gemini：代码最少，但 Gemini Key 会进入中国主站，违反文档的密钥与区域隔离要求。
2. HMAC 请求签名：安全性更强，但需要 timestamp、nonce、图片摘要、签名计算和防重放存储。V1 改为 HTTPS、安全组仅允许主 ECS EIP、一个静态服务令牌；若未来 Gateway 对多调用方开放，再升级为 HMAC 或 mTLS。
3. 主站与 Gateway 都进行自动重试：会产生重试次数相乘和重复计费。最终只允许主站 Worker 决定业务重试，Gateway 每个请求最多调用一次 Gemini。
4. 第一版直接强制 JSON Schema：解析简单，但可能改变客户已经认可的网页端行为。第一版固定使用客户原始文本 Prompt 并保存原始响应；经样本对照确有需要时再增加结构化输出。

## 3. 业务状态与数据模型

### 3.1 状态

侵权检测 item 状态：

- `PENDING`：等待 AI 检测。
- `PROCESSING`：Worker 已抢占并正在调用 Gateway。
- `SUCCESS`：成功解析为 `HIGH` 或 `LOW`。
- `RETRY`：技术失败，等待自动重试。
- `FAILED`：达到最大重试次数，页面显示“检测失败”。

图片入库状态与侵权检测状态完全分离。现有 `APPROVING` 继续表示正在执行 OSS 提升和 Qdrant 写入，`APPROVED` 表示正式入库完成；无论侵权检测最终是 `SUCCESS` 还是 `FAILED`，都不回退或阻止图片入库。

原图片批次在人工确认后继续进入现有 `CONFIRMING`，由入库 Worker 独立完成。侵权检测 task 单独维护 `PENDING / PROCESSING / COMPLETED / PARTIAL_FAILED / FAILED`；存在检测失败不会把已经成功入库的原图片批次改成失败。未选择图片仍进入现有 `DELETE_PENDING` 清理流程。

### 3.2 表

新增最小两表：

- `infringement_scan_task`：初次检测或批量重检任务、状态和一份 JSON 配置快照。不保存 pending、processing、success、high、low、failed 等实时计数。
- `infringement_scan_item`：每张图片的处理状态、风险结果、原始响应、尝试次数、下次重试时间和错误信息。

任务列表和详情页需要的总数、高风险、低风险、处理中和失败数，通过对 `infringement_scan_item` 的分组查询实时计算，避免每处理一张图片都更新 task 多个计数字段并处理计数一致性。

第一版不新增运行时配置表和 Prompt 表。主站只配置 Gateway URL、服务令牌、超时、最大重试次数和 Worker 并发；Gateway 只配置 Google Key、服务令牌、模型和 Prompt。创建任务时把模型、Prompt 版本和 Prompt 内容保存为一份 JSON 快照，保证运行中任务配置不漂移。需要网页在线修改配置时再增加配置表。

`img_asset` 新增当前侵权检测状态、风险等级、原因、英文图片描述、对应最新 scan item 和检测时间。任务明细保存每次完整 AI 响应与错误；正式图库只保存当前展示所需字段。失败时状态写为 `FAILED`，风险等级、原因和描述保持为空。

## 4. 主站流程

### 4.1 人工确认

`ImageService.confirm` 在同一数据库事务内：

1. 保留现有批次归属、失败图片和相似度上限校验。
2. 未选择图片写为 `DELETE_PENDING`。
3. 选择图片继续写为现有 `APPROVING`，立即进入 OSS 提升和 Qdrant 入库流程。
4. 同时创建一个初次检测 task 和对应 `PENDING` items，保存配置快照，并把图片当前侵权检测状态写为 `PENDING`。
5. 批次继续写为现有 `CONFIRMING`，HTTP 立即返回。

重复提交继续由现有批次条件更新阻止。入库和侵权任务创建处于同一个数据库事务，保证不会出现已经确认但没有检测任务的图片。

### 4.2 Worker

定时 Worker 每次领取少量到期且图片已为 `APPROVED` 的 `PENDING/RETRY` item，通过条件更新将状态原子地改为 `PROCESSING`。并发由配置控制，初始值为 8。图片入库失败时 item 保持 `PENDING`，待现有入库重试成功后再检测。

单项处理流程：

1. 从现有 `ImageStorage` 直接读取正式图库图片字节，不额外缩放、转码或生成第二份图片，避免增加 CPU、临时文件和画质损失。
2. 生成 requestId，通过 HTTPS multipart 请求携带图片、任务配置和静态服务令牌调用 Gateway。
3. Gateway 每个请求只调用一次 Gemini，不在 Gateway 内重试。
4. 严格解析 `高风险` 或 `低风险`；任何缺字段、歧义或超长内容都作为技术失败。
5. 成功时在事务内写 item 结果与 `img_asset` 当前风险字段，不修改图片的正式入库状态。
6. 可重试异常按照 `2s、5s、15s、60s、300s` 与随机抖动设置 `next_retry_at`；达到 5 次后把 item 和图片当前侵权检测状态写为 `FAILED`，风险等级保持为空。

定时恢复任务把超过 10 分钟的 `PROCESSING` item 改回 `RETRY`。状态更新必须带旧状态条件，确保多实例不会同时处理同一 item。

### 4.3 入库与检测一致性

人工确认不重复后，正式入库继续复用 `ImageAdmissionWorker` 的 OSS Copy、Qdrant upsert 和数据库条件更新，不等待 Gemini。侵权 Worker 只处理已经 `APPROVED` 的图片，因而始终从稳定的正式图库对象读取。入库失败仍使用 `APPROVAL_FAILED`，检测任务保持待处理；入库重试成功后会自动进入检测。检测失败只影响侵权状态，不影响图片的 `APPROVED` 状态、OSS 对象或 Qdrant 向量。

### 4.4 批量重新检测全部失败图片

页面按钮作用于当前登录用户所有历史任务中“当前侵权检测状态为 `FAILED`”的正式图片，不局限于当前任务。点击后，服务端在事务内重新查询符合权限的失败图片，创建一个新的批量重检 task 和 items，并把这些图片的当前侵权检测状态改为 `PENDING`。

历史失败 item 不修改、不删除；新 task 使用点击时的最新配置快照。连续点击通过重复提交保护和条件更新避免同一张图片同时进入多个重检任务。若当前用户没有失败图片，接口返回成功且创建数量为 0，不产生空任务或 Gemini 调用。

## 5. AI Gateway

Gateway 提供 `POST /internal/v1/image-risk/analyze`，接收 multipart 图片以及只含 requestId 和任务配置必要字段的 metadata。它不接收用户、订单或其他个人信息。

Gateway 仅校验 `X-Gateway-Token` 是否与环境变量中的静态令牌一致，并限制图片大小和 MIME。生产必须使用 HTTPS，Gateway 443 安全组只允许主 ECS 固定 EIP；应用端口不开放公网。这里不实现 timestamp、nonce、图片摘要、HMAC、防重放缓存或 Redis。若 Gateway 以后需要服务多个调用方，再升级鉴权方式。

Gemini Key 只从 Gateway 环境变量 `GOOGLE_API_KEY` 读取。日志不得输出 Key、服务令牌、图片内容或完整 AI 响应。

Gateway 使用固定版本的 Google Gen AI Java SDK，模型默认为 `gemini-3.8-flash`。第一版不暴露 thinking、media resolution、response mode 等额外开关，使用模型默认值。它对单个请求只调用一次模型，并返回统一结果或明确错误：认证失败、限流、上游超时、上游 5xx、不可解析响应和不可重试客户端错误。

## 6. API 与前端

主站新增：

- 任务分页与汇总查询。
- 单任务明细分页，支持 HIGH、LOW、处理中、失败筛选。
- “重新检测全部失败”接口：批量纳入当前登录用户所有历史任务中当前状态为 `FAILED` 的正式图片，并返回新 taskId 和图片数量。
- 图片详情返回风险等级、原因、描述、模型、Prompt 版本和检测时间。

新增“图片侵权检测”动态菜单页面，并同步加入 `web/src/pages/dynamicPage.tsx`。页面展示任务总数、完成数、高低风险数、失败数、进度和明细图片。不会在浏览器中暴露 Gateway 地址、Google Key 或服务令牌。

## 7. 配置与密钥

主站环境变量只包含 Gateway URL、静态服务令牌、超时、最大重试次数和 Worker 并发；Gateway 环境变量只包含 Google Key、同一静态服务令牌、模型和 Prompt。仓库只提交 `.env.example` 占位符，不提交真实值。

用户在当前会话公开的 API Key 不用于代码、测试、命令或日志。开始联调前必须在 Google AI Studio 撤销该 Key 并创建新 Key，然后由用户自行放入本地未跟踪的环境变量。

## 8. 测试与成功标准

后端最小自动化验证：

- 确认批次会同时创建检测任务和启动正式入库；未选择图片仍进入删除流程。
- Gateway 服务令牌正确、缺失和错误场景。
- HIGH/LOW 文本解析及歧义、缺字段、超长响应失败。
- 429、5xx、超时按计划重试；技术失败绝不写 LOW；第五次失败进入 `FAILED`，图片仍保持正式入库。
- 超时 `PROCESSING` 可以恢复。
- AI 成功或失败都不会修改 `APPROVED` 状态、OSS 正式对象或 Qdrant 向量。
- 批量重检覆盖当前用户所有历史失败图片，保留旧 item，并阻止并发重复创建。
- 入库和检测多次调度都不会重复处理或重复正式入库。
- Gateway 不自动重试；同一请求只触发一次假 Gemini Client 调用。
- Gateway 测试使用假 Gemini Client，不需要真实 Key。

前端运行 `pnpm lint` 和生产构建。后端运行相关模块测试与 `wuzh-admin` clean test/package。数据库、菜单和容器配置变更后重建本地容器，并验证任务页可见、创建任务立即返回、模拟 HIGH/LOW/失败链路均符合状态机。

真实 Gemini 联调不作为自动化测试前提；用户提供新 Key 后只做一张无敏感信息测试图的手工烟雾测试。100～300 张网页/API 一致性和 500～1000 张稳定性测试属于上线验收，不伪装成本次本地开发已经完成。

## 9. 实施顺序

1. 数据库、状态枚举和任务领域模型。
2. 主站确认流程、任务 Worker、Gateway Client 和解析器测试。
3. 独立 Gateway、静态服务令牌与 Gemini Client 适配器测试。
4. 任务查询 API 和前端页面。
5. Compose 与环境变量示例。
6. 全量构建、本地容器回归和一张图片的可选真实联调。
