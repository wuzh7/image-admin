# Gemini Batch 全局队列实施计划

状态：已完成，待生产发布验证

日期：2026-09-15

1. 为 `ImageStorage` 增加指定有效期的只读签名 URL 能力，OSS 实现 72 小时签名；保留现有页面短期预览 URL。
2. 将主站 Batch Gateway 契约从 Multipart 图片字节改为 JSON URL 列表，每组最多 200 张。
3. 新增短事务队列领取服务：锁定最早的 `PENDING` Batch 明细、检查全局 2 个运行槽、按同一任务最多领取 200 张，并在事务外提交 Gemini。
4. Gateway 使用 `BatchJobSource.inlinedRequests` 与 `Part.fromUri` 创建作业，保留旧作业结果文件读取兼容，仅删除本次被 URL 流程取代的上传临时状态。
5. 补充全局满槽不领取、FIFO/单任务边界、200 张边界、签名 URL、成功与失败结果落库测试。
6. 运行目标测试、模块完整测试、Gateway 完整测试和构建，并检查 Surefire XML 与最终差异。

约束：不增加 Redis、不新增业务表、不自动重试、不改变中转站和官方 Standard 流程、不执行 Git 提交或生产发布。
