# RELAY 多阶段 Prompt 配置

独立 Gateway 将提示词统一放在服务器的 `/opt/image-admin-ai-gateway/prompts`，整个目录只读挂载到容器中完全相同的路径。
所有 RELAY 模型共用现有 `gemini_prompt.txt` 作为 Prompt1，共用 `prompt2.txt` 作为 Prompt2；不需要另配 Doubao Prompt1。官方调用逻辑不变。
`prompt2.txt` 原样迁入原代码的 `CASE_LOOKUP_PROMPT` 文本。官方补查仍使用原代码常量，不改变官方行为。

配置示例（直接填写服务器文件路径，容器内路径与之相同）：

```dotenv
GEMINI_PROMPT_FILE=/opt/image-admin-ai-gateway/prompts/gemini_prompt.txt
RELAY_PROMPT2_FILE=/opt/image-admin-ai-gateway/prompts/prompt2.txt
RELAY_STAGE2_MODEL=doubao-seed-2-0-lite-260215
```

Prompt2 路径为空或文件内容仅空白：所有 stage 均不执行补查，不自动回退到固定补查指令。
配置文件缺失/不可读，或 Prompt1 为空：明确配置失败，不静默跳过 Doubao。
每个新请求只读取一次共用的两份 Prompt 快照，修改文本后下一请求实时生效；修改模型、协议、路径配置需要重建/重启 Gateway。
默认模型列表依次执行 Gemini、Doubao；若仅需 Gemini，从 `relay.stages` 列表移除 Doubao。
Doubao 协议默认为 `CHAT_COMPLETIONS`，不需要在 `.env` 中重复填写。
独立 Gateway 首次部署时，将原 Prompt1 或 `gemini_prompt.txt.example` 的内容放到上述 `gemini_prompt.txt` 路径，将本目录 `prompt2.txt` 放到上述 Prompt2 路径；不要只复制 README。
从旧配置迁移时保留旧文件，将原内容复制到新目录，并更新 `.env` 和 Compose；下次部署重建容器后生效。在此之前，运行中的容器仍使用旧路径。
目录挂载能支持编辑器采用“新文件替换旧文件”的保存方式；不要改回单文件挂载。只修改目录内文本无需重启，改 `.env`、挂载或模型配置则需要重建容器。

## 请求和返回约定

所有 stage 使用 `OPENLUX_BASE_URL`（根地址，不带 `/v1`）和 `OPENLUX_API_KEY`。
`GEMINI` 协议保持现有 `/v1beta/models/{model}:generateContent`、图片 inlineData 和 googleSearch。
`CHAT_COMPLETIONS` 使用 `/v1/chat/completions`、Bearer 鉴权、messages、image_url Base64 data URL、非流式响应。
Chat 不默认注入 Gemini 的 googleSearch，也不把缺少搜索证据的响应标记为已联网。
参考：https://doc.openlux.ai/reference/v1?op=post-v1-chat-completions&leaf=232421949
用户提供的模型目录和截图确认 OpenLux 列有 `doubao-seed-2-0-lite-260215`，端点为 `/v1/chat/completions`；`https://api.openlux.ai/pricing?provider=Doubao` 不是 API 调用地址。
复用现有 OpenLux 密钥，不配置豆包官方密钥。现有令牌对该模型的权限及实际图片输入、联网检索能力仍需真实接口验证，不能用模拟测试替代。

Stage1 Prompt1 按现有完整 JSON 契约返回图像描述、标签和风险字段。
Stage2 及以后使用同一 Prompt1；即使返回完整 JSON，也只合并 `riskLevel`、`riskReason`、`CaseNumber`。
其输入包含上一 stage 的 `prompt1Result`、`prompt2Result`（未调用为 null）、`mergedResult`。
补查 Prompt2 返回案号字符串数组，或 `{"riskReason":null,"CaseNumber":[]}` 形状的 JSON 对象。
LOW 补查若要识别新的疑点，应返回对象；仅返回数组不能提供新的 riskReason。
风险等级接受 HIGH、LOW、needs_review，案号必须为字符串数组；不可解析的结果是技术失败，不写成 LOW。

## stage 内唯一状态转换

先执行 Prompt1（后续 stage 仅白名单合并三个风险字段），再判断 Prompt2：

| 当前状态 | 是否补查（Prompt2 已配置时） | 补查结果 | 合并行为 |
| --- | --- | --- | --- |
| LOW | 是；优先于疑似风险分支 | 案号非空，不论理由是否为空 | HIGH，更新案号；有新理由时更新，否则保留原理由 |
| LOW | 是 | 案号空、理由非空 | NEEDS_REVIEW，更新理由，案号保持 |
| LOW | 是 | 案号空、理由空 | 完全保持 Prompt1 结果 |
| 非 HIGH，案号空、理由非空 | 是 | 案号非空 | HIGH，更新案号，原理由保持 |
| 非 HIGH，案号空、理由非空 | 是 | 案号空 | 完全保持 Prompt1 结果 |
| 其他状态（含 HIGH） | 否 | — | 保持 |

每个 stage 最多执行一次 Prompt2。后续模型可以通过 Prompt1 重新评定风险，包括降级；补查规则本身只升级。
原伪代码 `length < 0` 按为空理解，`riskLevel = 'low'` 按比较理解。
最终结果标记 `relay_pipeline_v1`，后端不再追加旧 CASE_LOOKUP 或未联网 LOW 重试。
官方逻辑仍使用旧标记和旧处理；旧 RELAY 请求记录/补查队列继续兼容。
既有 DTO 和数据库字段复用，不需要数据库迁移。逐调用模型/原始响应保存在 rawProviderResponse，模型主字段仍保留 stage1。
逐调用审计记录增加 `promptName`，保存本次实际使用的 Prompt 文件名（不是路径或文本）；成功和收到上游错误响应的调用均记录。
页面 RELAY 详情按实际调用记录去重展示提示词名称，不展示内部 `relay_pipeline_v1`。旧审计没有文件名时按 PROMPT1/PROMPT2 映射默认名称，并明确注明历史映射；无有效记录不猜测。
总耗时可能达到多次上游调用；启用多 stage 前核实后端 `AI_GATEWAY_TIMEOUT_SECONDS` 与中转站超时设置。

## 扩展 stage3

在外部 Spring 配置的 `relay.stages` 列表中追加模型、protocol，自动共用两份 Prompt，无须修改状态机/provider 分支。
也可使用标准 Spring 环境变量 `RELAY_STAGES_2_MODEL`、`RELAY_STAGES_2_PROTOCOL`，Compose 需显式传入容器。
采用环境变量覆盖列表时，应完整提供所有 stage 的配置，避免 Spring 列表替换丢失前两阶段。

测试（server 目录）：

```powershell
.\mvnw.cmd -pl wuzh-ai-gateway -am test '-Plocal' '-Dmaven.test.skip=false' '-DskipTests=false'
```
