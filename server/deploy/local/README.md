# 图片去重 Demo 本地运行手册

这套本地环境用于演示“上传图片 → Java 提取 SSCD 特征 → Qdrant 召回 → 低相似图片自动入库 / 高相似图片人工确认 → 正式图库”的完整流程。除 Docker Desktop、Docker Compose V2 和 Git 外，不需要在 Windows 单独安装 JDK、Maven、MySQL、Redis、Qdrant、Node.js 或 Nginx。

## 1. 本地会启动什么

| 容器 | 作用 | Windows 访问地址 | 数据是否持久化 |
|---|---|---|---|
| `image-admin-mysql` | 系统表、批次、图片元数据、原始向量 | `127.0.0.1:3306` | `mysql-data` 命名卷 |
| `image-admin-redis` | 系统登录、Token 和缓存 | `127.0.0.1:6379` | `redis-data` 命名卷 |
| `image-admin-qdrant` | SSCD 向量召回 | `http://127.0.0.1:6333` | `qdrant-data` 命名卷 |
| `image-admin-backend` | Spring Boot、DJL、图片任务 | `http://127.0.0.1:8080` | 图片使用 `image-files` 命名卷 |
| `image-admin-frontend` | React 构建产物和 Nginx 反向代理 | `http://127.0.0.1:8088` | 不保存业务数据 |

启动依赖顺序固定为：

```text
Docker Desktop
  ↓
MySQL + Redis + Qdrant（并行启动并等待健康）
  ↓
临时执行 image.sql 数据库迁移后自动删除
  ↓
Java 后端（校验模型、创建 Qdrant 集合、执行模型预热）
  ↓
React/Nginx 前端
```

关闭顺序相反：前端 → Java 后端 → Qdrant → Redis → MySQL。

## 2. 第一次启动前的检查

1. 打开 Docker Desktop，等左下角显示引擎已经运行。
2. 在 Docker Desktop 的 Settings → Resources 中给 Docker 至少 8 GB 内存，建议 10 GB。
3. 确认磁盘至少有 15 GB 可用空间。第一次会下载基础镜像和 Maven 依赖。
4. 确认 `3306`、`6379`、`6333`、`8080`、`8088` 没被其他程序占用。
5. SSCD 模型应位于：

   ```text
   image-admin/server/models/sscd_disc_mixup.torchscript.pt
   ```

启动脚本会直接校验该模型（模型二进制已忽略 Git）：

```text
SHA-256: 9F26BD4C848CC19B73D2AE92EEA6E04886F61A7B764CEB7A13AEEE62E6A6DB56
```

## 3. 一键启动

打开 PowerShell，执行：

```powershell
cd D:\AiWorkspaces\图片功能需求\image-admin\server\deploy\local
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
```

脚本会自动完成：

1. 检查 Docker Desktop 和 Compose V2。
2. 检查 Docker 内存和本机端口。
3. 创建 `.env.local` 本地配置。
4. 校验 SSCD 模型。
5. 构建 Java、React 和本地 MySQL 镜像。
6. 创建命名卷，初始化数据库；迁移脚本使用一次性容器执行后自动删除，再按依赖顺序启动后端和前端。
7. 等待模型预热与健康检查通过。

第一次构建通常需要数分钟，主要取决于网络和电脑性能。成功后打开：

```text
http://localhost:8088
```

默认登录信息：

```text
账号：admin
密码：admin123
```

登录后左侧菜单中进入“图片去重”。如果菜单刚初始化但浏览器仍显示旧菜单，退出后重新登录一次。

## 4. 实际使用流程

1. 在“批次上传”页填写批次名称。
2. 阈值默认 `0.60`，可在 `0.50` 到 `0.99` 之间调整。
3. 选择 JPEG、PNG 或 WebP 图片；单张不设大小和像素上限，一批最多 1000 张。
4. 点击“上传并开始检测”。前端会并发上传；单张失败只提示该文件，其余图片继续检测。
5. 提交后任务进入后台队列，页面每 2 秒刷新处理进度。可以关闭或离开页面，任务不会中断；稍后从“检测任务”重新打开。普通用户只看到自己创建的任务和图片，管理员可以查看并处理全部用户的数据。
6. 同批图片在 Java 内存中逐对进行双向精确比较；正式图库才通过 Qdrant Top30 召回并用 MySQL 原始向量精排。
7. 到达“待确认”后查看原图和最佳匹配图：
   - `EXACT_HASH` 表示 SHA-256 完全相同，分数为 1.0。
   - `SSCD` 表示模型召回并由 MySQL 原始 Float32 向量精排。
8. 非重复图片默认勾选；重复和失败图片默认不勾选。可以人工调整，但 `FAILED` 必须先重试成功。
9. 点击“确认入库”。只有批准的图片才移动到正式图库并写入 Qdrant。
   - 相似度严格大于 `95%` 或 SHA-256 完全重复的图片禁止选择和入库；后端接口也会强制校验。恰好 `95.00%` 仍允许人工选择。
10. 每张图片保存原始像素尺寸，但正式图库只展示常见比例分类，例如 `1080 × 1350` 显示为 `4:5`、`183 × 104` 显示为 `16:9`；不能可靠归类的显示为“其他”。

## 5. 日常操作命令

所有命令均在 `image-admin/server/deploy/local` 目录执行。

查看状态、模型哈希和 Qdrant Point 数量：

```powershell
powershell -ExecutionPolicy Bypass -File .\status-local.ps1
```

持续查看 Java 后端日志，按 `Ctrl+C` 只会退出日志查看，不会停止服务：

```powershell
powershell -ExecutionPolicy Bypass -File .\logs-local.ps1
```

停止全部服务但保留所有数据：

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-local.ps1
```

停止后再次启动，仍运行 `start-local.ps1`。Docker 会复用已下载镜像、构建缓存和命名卷。

彻底恢复空环境：

```powershell
powershell -ExecutionPolicy Bypass -File .\reset-local.ps1
```

脚本要求输入大写 `RESET` 二次确认。它会永久删除本项目的 MySQL、Redis、Qdrant 和图片命名卷，但保留模型文件和源码。

## 6. 数据在哪里

业务数据不直接写入 Windows 目录，而是保存在以下 Docker 命名卷：

```text
image-admin-local_mysql-data
image-admin-local_redis-data
image-admin-local_qdrant-data
image-admin-local_image-files
```

`docker compose stop`、Docker Desktop 重启和 Windows 重启不会删除这些卷。只有运行 `reset-local.ps1` 并确认，或手工删除卷，才会清空数据。

模型文件在：

```text
image-admin/server/models/sscd_disc_mixup.torchscript.pt
```

它不在 Docker 数据卷中，也不会被重置脚本删除。

## 7. 异常与恢复规则

- Java 在提交批次前先把 `QUEUED` 写入 MySQL，实际执行时改为 `CHECKING`；每完成一个微批次就落库进度，默认微批次为 1 张。
- Java 重启后会扫描 `QUEUED` 和 `CHECKING` 批次，从未完成图片继续处理。
- 后台每 5 秒重新调度尚未进入执行器的 `QUEUED` 批次，队列短暂满载不会丢任务。
- 图片批次默认按 FIFO 单队列处理（`IMAGE_TASK_WORKERS=1`），避免多批次同时抢占 CPU 导致整体更慢。`IMAGE_FEATURE_BATCH_SIZE` 默认为 `1`；只有在目标服务器用真实图片实测证明 `2` 或 `4` 更快后再调整。
- 续跑或重试时会为已处理图片重建查询特征，保证批内双向比较口径不因重启改变。
- DJL、模型文件或 Qdrant 异常时，图片进入 `FAILED`，不会被误判为非重复。
- Qdrant 恢复后，在页面点击“重试失败图片”。
- 确认入库时必须先成功写入 Qdrant，才会将图片标记为 `APPROVED`。不再执行每 6 小时全量 ID 核对；正式图库只检查当前页图片，正常时不显示向量提示，缺失 Point 的图片会显示红色“向量索引异常”，管理员可从异常卡片执行修复。
- 旧图片的比例字段只在升级后首次启动时归类；后续上传会实时保存，不产生周期性全表扫描。
- 拒绝、删除的图片保留 7 天，再由后端定时清理文件。

## 8. 常见错误

### Docker Desktop 尚未启动

错误信息会包含“Docker Desktop 尚未启动”。打开 Docker Desktop，等待引擎就绪后重试。

### 端口已占用

启动脚本会列出冲突端口。常见原因是本机已经安装 MySQL/Redis，或另一个项目占用 8080。先停止占用程序，再运行脚本；不要随意修改一半端口映射，因为前后端和文档使用的是固定端口。

### SSCD 模型不存在或哈希不匹配

不要跳过校验。确认模型文件名和来源正确；哈希不一致说明文件版本错误或复制损坏，必须换回已验证模型。

### 第一次 MySQL 长时间显示 `health: starting`

Windows Docker Desktop 首次创建数据文件并导入完整Wuzh数据库可能需要 2 至 4 分钟。脚本已预留 5 分钟初始化保护期。可以另开 PowerShell 查看：

```powershell
docker logs -f image-admin-mysql
```

看到 `ready for connections` 且初始化完成后会继续启动后端。

### 后端不健康

执行：

```powershell
docker logs --tail 200 image-admin-backend
```

重点查看错误是否来自 MySQL、Redis、Qdrant、模型哈希或模型加载。不能通过关闭模型校验来“绕过”启动失败。

### Qdrant 暂停后图片失败

这是预期保护行为。启动 Qdrant，确认 `status-local.ps1` 正常，然后在批次页面点击重试。失败记录不会自动变成非重复。

### Docker Hub 下载出现 `unexpected EOF`

通常是临时网络中断。再次运行 `start-local.ps1` 会复用已下载层继续构建，不需要重置数据。

### 页面能打开但接口报错

先用 `status-local.ps1` 确认后端和 Qdrant，再退出并重新登录。生产前端统一访问 `/prod-api/`，由 Nginx 转发到 Java 后端，不应直接在前端代码写 `localhost:8080`。

## 9. 本轮边界

本地 Demo 已实现图片去重主流程，但本轮不包含侵权检测、阿里云 OSS、完整角色矩阵、批量下载、ECS 正式发布和向量量化。Qdrant 保持两组 512 维全精度 Named Vectors，不启用 Scalar Quantization、Binary Quantization 或 PDQ。
