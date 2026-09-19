# Gemini 官方 Flex 与 OpenLux 双通道设计

日期：2026-09-14

## 目标

图片上传批次必须选择一种侵权检测模式。默认使用支持 Google Search 的 OpenLux 中转站；线上测试可切换为 Google 官方 Flex。一个批次创建后固定使用同一种模式，初次检测和失败项手动重检都沿用该模式。

本次同时依据 `docs/POD_TRO_Gemini_生产版_v2.md` 升级官方 Gemini 提示词和结构化输出，但不实现文档中的自动二次复核，保证每张图片每次任务最多调用一次 AI。

## 模式定义

- `RELAY`：OpenLux Gemini 原生 `generateContent`，上传页默认值。
- `OFFICIAL_FLEX`：Google 官方 Gemini `generateContent`，设置 `serviceTier=flex`。

两种模式默认都使用 `gemini-3.8-flash`。模型名称通过 Gateway 环境变量配置，不写死在业务代码。OpenLux 部署前使用实际 API Key 验证账号模型列表；不支持时明确报错，不静默切换模型。

## 数据模型

只在 `img_batch` 增加字段：

```sql
infringement_mode VARCHAR(20) NOT NULL DEFAULT 'RELAY'
```

历史批次按 `RELAY` 处理。批次创建接口校验模式只能是 `RELAY` 或 `OFFICIAL_FLEX`，创建成功后不提供修改接口。

侵权任务通过 `source_batch_id` 获取固定模式，不增加新表，也不在任务和明细表重复保存模式。

## 数据流

1. 上传页显示必选的侵权检测模式，默认选中“中转站”。
2. 创建批次时把模式写入 `img_batch`。
3. 图片继续走现有上传、SSCD 去重、自动入库和人工审核流程。
4. 正式入库图片生成侵权检测明细。
5. Worker 领取待处理明细，并根据来源批次取得检测模式。
6. 主站调用 Gateway 时通过内部请求头传递模式。
7. Gateway 选择 OpenLux 或 Google 官方客户端；一次调用完成后统一解析结果。
8. 成功保存风险等级、原因、描述、模型、提示词版本、原始模型 JSON 和实际搜索元数据；技术异常保存为检测失败。
9. 页面手动重检失败项时继续使用原批次模式。

## OpenLux 调用

请求使用 Gemini 原生格式：

```http
POST https://api.openlux.ai/v1beta/models/{model}:generateContent?key={OPENLUX_API_KEY}
Content-Type: application/json
```

请求包含图片、生产提示词、结构化 JSON 配置和：

```json
{"tools":[{"googleSearch":{}}]}
```

API Key 只存在新加坡 AI Gateway 环境变量中，不传给主站、浏览器或数据库。错误信息和日志不得包含完整请求 URL或 Key。

Gateway 从 `candidates[].content.parts[].text` 获取模型 JSON，从 `candidates[].groundingMetadata.webSearchQueries` 计算 `searchUsed` 和 `searchQueryCount`。

## Google 官方 Flex 调用

沿用 Google Gen AI Java SDK和现有结构化输出，增加 `serviceTier=flex`，同时保留 Google Search 工具。官方 Flex 不自动回退到 Standard。

Flex 请求允许排队，Gateway 和主站请求超时至少覆盖15分钟，Worker 的处理中超时回收设为20分钟。任何超时、限流、容量不足、网络失败或解析失败都只记录为检测失败。

## 提示词与返回结构

两条模式共用同一份生产提示词和同一套解析规则，避免模式切换造成结果口径差异。返回结构包含：

- `riskLevel`：`LOW`、`NEEDS_REVIEW` 或 `HIGH`
- `riskReason`
- `descriptionKeywords`
- `subjects`
- `scenes`
- `themes`
- `visualStyles`
- `colors`
- `detectedElements`
- `confidence`

`detectedElements` 和 `confidence` 先保存在现有 `raw_ai_response` 中，不增加数据库列。现有详情字段和旧格式继续兼容。

每次任务每张图片只进行一次 AI 调用：不自动二次复核、不自动重试、不因缺少搜索元数据重复调用。

## 错误处理

- 模式缺失或非法：创建批次失败，提示重新选择。
- OpenLux 模型不可用：检测失败，错误码区分模型不可用。
- Flex 429、503、超时或容量不足：检测失败，交给页面按钮手动重检。
- AI 返回空内容或非法 JSON：检测失败。
- 技术失败时清空风险等级、风险原因、描述和搜索状态，绝不写成 `LOW`。
- `searchUsed=true` 只由非空 `webSearchQueries` 推导，不能由“请求中启用了工具”推导。

## 页面设计

上传表单在图片类目附近增加带可见标签的单选项：

- 中转站（默认）：支持 Google Search，使用 OpenLux。
- 官方 Flex：约为标准推理费用的50%，可能等待1至15分钟。

默认值仍需随创建批次请求提交，后端执行独立校验。上传进度批次和侵权检测任务显示实际模式，便于线上对比和审计。

不增加新的页面、弹窗、依赖或全局状态管理。

## 配置

Gateway 使用以下环境变量：

- `OPENLUX_API_KEY`
- `OPENLUX_BASE_URL`，默认 `https://api.openlux.ai`
- `OPENLUX_MODEL`，默认 `gemini-3.8-flash`
- `OPENLUX_TIMEOUT_SECONDS`
- `GOOGLE_API_KEY`
- `GEMINI_MODEL`，默认 `gemini-3.8-flash`
- `GEMINI_TIMEOUT_SECONDS`

删除运行时全局提供商选择 `AI_PROVIDER`；一套 Gateway 同时持有两种客户端。环境示例不保存真实 Key。

## 验证标准

1. 创建批次不传或传非法模式时后端拒绝。
2. 页面初始默认中转站，创建请求包含 `RELAY`。
3. 批次模式在进度页和侵权检测页可见且不可修改。
4. OpenLux 请求使用 API Key 查询参数、Gemini 原生图片格式和 `googleSearch`。
5. 官方请求包含 `serviceTier=flex`、Google Search和统一结构化输出。
6. Worker 根据批次选择正确客户端，失败重检沿用原模式。
7. 两条链路都能解析风险结果和实际搜索次数。
8. 技术失败不会生成任何风险等级。
9. 前端检查、Gateway定向测试和主站图片模块定向测试通过。

## 非目标

- 不实现 Google Batch API。
- 不自动进行第二次AI复核。
- 不自动重试或自动回退 Standard。
- 不增加 Redis、消息队列、新表或新的部署服务。
- 不改动图片去重算法、阈值、OSS目录和人工审核规则。
