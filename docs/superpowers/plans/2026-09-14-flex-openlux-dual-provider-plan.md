# Gemini 官方 Flex 与 OpenLux 双通道实施计划

1. 先补后端和 Gateway 回归测试：批次模式校验与透传、OpenLux 原生请求、官方 Flex 配置、搜索元数据解析。
2. 增加 `img_batch.infringement_mode` 并贯通 DTO、服务、Mapper 和 Worker，不增加新表或重复字段。
3. 将 Gateway 从全局提供商切换改为按请求路由；用 OpenLux API Key 查询参数替换旧 `/chat/completions` 客户端。
4. 按生产文档统一提示词和结构化字段，保留单次调用和人工重检语义。
5. 上传页增加默认中转站的必选单选项，并在批次列表和侵权任务中显示实际模式。
6. 更新本地/生产/Gateway 配置示例和部署脚本变量，移除 `AI_PROVIDER` 与旧 `LARGEEAPI_*`。
7. 运行 Gateway、图片模块定向测试、前端 lint/build，并检查本次 diff 未包含无关修改。
