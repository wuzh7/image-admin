# render-worker

内部 Node 渲染进程。Java 是唯一业务后端，Worker 只调用以下接口并通过 `X-Render-Worker-Token` 鉴权：

- `POST /store/internal/render-items/claim` `{ workerId, limit }`
- `POST /store/internal/render-items/{itemId}/upload` multipart `workerId`, `leaseToken`, `file`
- `POST /store/internal/render-items/{itemId}/complete` `{ workerId, leaseToken, result }`
- `POST /store/internal/render-items/{itemId}/fail` `{ workerId, leaseToken, errorMessage }`

必需环境变量：`JAVA_API_URL`、`RENDER_WORKER_TOKEN`。可选：`RENDER_WORKER_CONCURRENCY`（默认 1）、`RENDER_WORKER_POLL_MS`（默认 2000）。

场景算法来自 canvas-2d-mockup-demo 生成的 `runtime/worker-source.mjs`，不把浏览器 Worker 或 demo 页面带入服务端。
