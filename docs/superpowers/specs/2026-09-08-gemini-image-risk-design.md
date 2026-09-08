# Gemini 图片侵权风险检测设计

## 1. 目标与边界

在现有图片去重和人工复核流程中加入 Gemini 图片侵权风险检测。员工确认图片与正式图库不重复后，系统创建异步侵权检测任务；只有 Gemini 成功返回可解析的 `HIGH` 或 `LOW`，图片才进入正式图库并写入 Qdrant。任何网络、配额、超时或解析失败都不得被记录为低风险。

本次实现包含主站任务管理、独立 AI Gateway、Gemini 调用、HMAC 鉴权、数据库状态、失败重试与恢复、风险结果展示、配置示例和本地自动化测试。不直接操作阿里云 ECS、1Panel、DNS、证书、安全组或真实生产密钥，也不建设 IP/TRO 知识库、搜索 Grounding、AI 结果缓存、Embedding、Kafka、Nacos 或 Kubernetes。

## 2. 已选方案与取舍

采用“同一仓库、两个独立 Spring Boot 应用”的生产形态：

- 主站继续运行现有 `wuzh-admin`，负责登录、审核、任务状态、重试、正式入库和前端 API。
- 新增轻量 AI Gateway 应用，负责 HMAC 校验、单次 Gemini 调用和标准化响应，不连接业务数据库。
- 本地 Compose 可同时启动两个应用；生产时将 Gateway 单独部署到新加坡 ECS。
- 主站使用 MySQL 作为任务唯一事实来源和工作队列。项目虽然已有 Redis，但现有图片后台任务已经采用数据库持久状态与定时 Worker；V1 复用该模式可以避免再实现 Redis Stream 的消费确认、Pending reclaim 和数据库双写一致性。若实际吞吐证明 MySQL 抢占成为瓶颈，再迁移到 Redis Stream。

未采用的方案：

1. 主站直接调用 Gemini：代码最少，但 Gemini Key 会进入中国主站，违反文档的密钥与区域隔离要求。
2. 主站与 Gateway 都进行自动重试：会产生重试次数相乘和重复计费。最终只允许主站 Worker 决定业务重试，Gateway 每个请求最多调用一次 Gemini。
3. 第一版直接强制 JSON Schema：解析简单，但可能改变客户已经认可的网页端行为。第一版默认 `TEXT_COMPAT`，保留原始响应；结构化输出能力保留为后续经样本对照后启用的配置值，但本次不实现第二套解析链路。

## 3. 业务状态与数据模型

### 3.1 状态

图片状态新增：

- `INFRINGEMENT_PENDING`：人工确认通过，等待 AI 检测。
- `INFRINGEMENT_PROCESSING`：Worker 已抢占并正在调用 Gateway。
- `INFRINGEMENT_RETRY`：技术失败，等待下次重试。
- `MANUAL_REQUIRED`：达到最大重试次数，不能自动入库。

现有 `APPROVING` 仅表示 AI 检测已成功、正在执行 OSS 提升和 Qdrant 写入；`APPROVED` 仍表示正式入库完成。

批次在人工确认后进入 `INFRINGEMENT_CHECKING`。全部选中图片完成后：全部成功为 `COMPLETED`；存在 `MANUAL_REQUIRED` 或入库失败为 `PARTIAL_FAILED`。未选择图片仍进入现有 `DELETE_PENDING` 清理流程。

### 3.2 表

新增最小三表：

- `infringement_scan_task`：批次对应任务、汇总计数、状态和配置快照。
- `infringement_scan_item`：每张图片的处理状态、风险结果、原始响应、尝试次数、下次重试时间和错误信息。
- `ai_call_log`：每次 Gateway 调用的 requestId、模型、耗时、HTTP 状态、Token 和错误码。

第一版不新增可编辑的运行时配置表和 Prompt 表。模型、Prompt、thinking、resolution、超时、重试次数及并发通过环境配置加载，并在创建任务时序列化到 `infringement_scan_task.config_snapshot`。这满足“运行中任务配置不漂移”，同时避免为当前唯一 Prompt 和 Provider 增加六张配置表及管理页面。需要在线修改配置时再引入版本表。

`img_asset` 新增最终风险字段：风险等级、原因、英文图片描述、对应 scan item、检测时间。任务明细保存完整 AI 响应，正式图库只保存展示所需字段。

## 4. 主站流程

### 4.1 人工确认

`ImageService.confirm` 在同一数据库事务内：

1. 保留现有批次归属、失败图片和相似度上限校验。
2. 未选择图片写为 `DELETE_PENDING`。
3. 选择图片写为 `INFRINGEMENT_PENDING`。
4. 创建一个 task 和对应 items，并保存配置快照。
5. 批次写为 `INFRINGEMENT_CHECKING`，HTTP 立即返回。

重复提交继续由现有批次条件更新阻止；任务表对 `source_batch_id` 设置唯一约束。

### 4.2 Worker

定时 Worker 每次领取少量到期的 `PENDING/RETRY` item，通过条件更新将状态原子地改为 `PROCESSING`。并发由配置控制，初始值为 8。

单项处理流程：

1. 从现有 `ImageStorage` 读取审核图片。
2. 生成 AI 检测副本：修复方向、移除非必要元数据、最长边不超过 2048、JPEG 质量 85；若原图已在限制内且格式受支持，可直接发送原始字节，避免重复转码损失。
3. 计算图片 SHA-256 并生成 requestId、timestamp、nonce 和 HMAC。
4. multipart 调用 Gateway。
5. 严格解析 `高风险` 或 `低风险`；任何缺字段、歧义或超长内容都作为技术失败。
6. 成功时在事务内写 item 结果与 `img_asset` 风险字段，并把图片改为 `APPROVING`，由现有 `ImageAdmissionWorker` 完成 OSS 提升和 Qdrant 写入。
7. 可重试异常按照 `2s、5s、15s、60s、300s` 与随机抖动设置 `next_retry_at`；达到 5 次后写为 `MANUAL_REQUIRED`。

定时恢复任务把超过 10 分钟的 `PROCESSING` item 改回 `RETRY`。状态更新必须带旧状态条件，确保多实例不会同时处理同一 item。

### 4.3 入库一致性

Gemini 成功不等于正式入库成功。AI 成功后图片才进入现有 `APPROVING`，继续复用 `ImageAdmissionWorker` 的 OSS Copy、Qdrant upsert 和数据库条件更新。入库失败仍使用 `APPROVAL_FAILED`，不会覆盖已经保存的风险结果；用户可以从任务页面重试入库或重新检测。

## 5. AI Gateway

Gateway 提供 `POST /internal/v1/image-risk/analyze`，接收 multipart 图片以及只含 requestId 和配置快照必要字段的 metadata。它不接收用户、订单或其他个人信息。

HMAC canonical string 固定为：

```text
timestamp + "\n" + nonce + "\n" + requestId + "\n" + sha256(image)
```

Gateway 校验时间偏差不超过 5 分钟、nonce 格式、图片大小和 MIME、签名常量时间比较。第一版 nonce 防重放使用进程内带过期时间的集合；生产仍依赖安全组只允许主 ECS EIP。若部署多个 Gateway 实例，再把 nonce 存储迁移到共享 Redis。

Gemini Key 只从 Gateway 环境变量 `GOOGLE_API_KEY` 读取。日志不得输出 Key、共享密钥、签名、图片内容或完整 AI 响应。

Gateway 使用固定版本的 Google Gen AI Java SDK，模型默认为 `gemini-3.8-flash`，thinking 为 `MEDIUM`，media resolution 为 `HIGH`。它对单个请求只调用一次模型，并返回统一结果或明确错误：认证失败、限流、上游超时、上游 5xx、不可解析响应和不可重试客户端错误。

## 6. API 与前端

主站新增：

- 任务分页与汇总查询。
- 单任务明细分页，支持 HIGH、LOW、处理中、失败筛选。
- 对 `MANUAL_REQUIRED` item 发起重新检测；保留历史 item，不覆盖旧结果。
- 图片详情返回风险等级、原因、描述、模型、Prompt 版本和检测时间。

新增“图片侵权检测”动态菜单页面，并同步加入 `web/src/pages/dynamicPage.tsx`。页面展示任务总数、完成数、高低风险数、失败数、进度和明细图片。不会在浏览器中暴露 Gateway 地址、Google Key 或 HMAC Secret。

## 7. 配置与密钥

主站环境变量只包含 Gateway URL 和共享 HMAC Secret；Gateway 环境变量包含 Google Key 和同一 HMAC Secret。仓库只提交 `.env.example` 占位符，不提交真实值。

用户在当前会话公开的 API Key 不用于代码、测试、命令或日志。开始联调前必须在 Google AI Studio 撤销该 Key 并创建新 Key，然后由用户自行放入本地未跟踪的环境变量。

## 8. 测试与成功标准

后端最小自动化验证：

- 确认批次会创建任务，未选择图片仍进入删除流程，选择图片不会提前入库。
- HMAC 正常、过期、篡改和重复 nonce。
- HIGH/LOW 文本解析及歧义、缺字段、超长响应失败。
- 429、5xx、超时按计划重试；技术失败绝不写 LOW；第五次失败进入 `MANUAL_REQUIRED`。
- 超时 `PROCESSING` 可以恢复。
- 成功结果先写风险再进入 `APPROVING`，最终复用现有入库链路。
- 多次调度不会重复处理或重复正式入库。
- Gateway 测试使用假 Gemini Client，不需要真实 Key。

前端运行 `pnpm lint` 和生产构建。后端运行相关模块测试与 `wuzh-admin` clean test/package。数据库、菜单和容器配置变更后重建本地容器，并验证任务页可见、创建任务立即返回、模拟 HIGH/LOW/失败链路均符合状态机。

真实 Gemini 联调不作为自动化测试前提；用户提供新 Key 后只做一张无敏感信息测试图的手工烟雾测试。100～300 张网页/API 一致性和 500～1000 张稳定性测试属于上线验收，不伪装成本次本地开发已经完成。

## 9. 实施顺序

1. 数据库、状态枚举和任务领域模型。
2. 主站确认流程、任务 Worker、Gateway Client 和解析器测试。
3. 独立 Gateway、HMAC 与 Gemini Client 适配器测试。
4. 任务查询 API 和前端页面。
5. Compose 与环境变量示例。
6. 全量构建、本地容器回归和一张图片的可选真实联调。
