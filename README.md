# image-admin · 图片管理与店铺套图系统

面向小团队的图片管理全栈系统：上传图片 → SSCD 去重 → 人工复核 → 正式图库 → AI 图片分析 → 店铺套图渲染 → 上架表格导出。后端为 Java 单体多模块，前端为 React 管理后台，本地与生产全部由 Docker Compose 编排。

---

## 核心能力

| 模块 | 能力 |
| --- | --- |
| 图片上传与去重 | 批次上传（OSS 浏览器直传）→ 后端完整解码，以真实格式记录 MIME/尺寸/SHA-256 → SSCD 特征提取 → Qdrant + 本批次召回 → 相似度低于阈值自动入库，达到阈值转人工复核 |
| 人工复核 | 完全相同文件禁止重复入库；每张高相似图片保留自己的 Top30 精排候选，审核页按页展示候选图 |
| 正式图库 | 入库图片写入 Qdrant 正式集合，支持类目、状态、分析状态筛选与预览 |
| 图片 AI 分析 | 经火山方舟 Responses API 生成英文描述与五类中文标签；侵权风险等级由三方接口更新。**分析失败绝不当作"低风险"**，也不阻塞已确认图片入库 |
| 订单明细 | 外部接口接入、后台查询与导出（导出当前筛选条件下的全部结果，不绑定分页） |
| 店铺套图 | 背景模板校准、模板组/模板维护、渲染批次与渲染明细、可恢复渲染（已完成明细可复用、租约过期可续跑）、按店铺上架配置生成上架表格 |
| 系统管理 | 公司/部门树、用户、角色、动态菜单与权限点、用户图片处理配置 |

关键业务术语见 [CONTEXT.md](CONTEXT.md)，Agent 协作约定见 [AGENTS.md](AGENTS.md)。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | JDK 21、Spring Boot 3、Maven、MyBatis、Redisson |
| 前端 | React 19、TypeScript、Ant Design、Umi Max 4、pnpm |
| 存储 | MySQL 8.4（业务数据）、Redis 8.4（会话与分布式锁）、Qdrant（SSCD 向量）、阿里云 OSS（图片对象存储） |
| 推理 | DJL + PyTorch，SSCD TorchScript 模型（约 94 MB） |
| AI | 火山方舟（图片分析），单次调用，超时较长 |
| 渲染 | Node.js render-worker，多副本，通过令牌鉴权调用后端内部接口 |
| 部署 | Docker Compose（本地全栈 / 生产）、Nginx（前端静态资源 + `/prod-api/` 反代） |

---

## 目录结构

```text
server/                     Java 后端（Maven 多模块）
  wuzh-admin                启动模块与运行配置（application-local.yml / application-prod.yml）
  wuzh-api                  内部公共接口与数据对象
  wuzh-common               鉴权、数据库、Redis、日志、Web 等基础能力
  wuzh-modules/
    wuzh-system             登录、用户、部门、角色、菜单
    wuzh-image              上传、SSCD、Qdrant、审核、图库、AI 分析任务
    wuzh-order              订单明细与外部接口
  render-worker             Node 渲染进程（像素级套图渲染）
  deploy/local              本地全栈 Compose、启动脚本、MySQL 配置
  deploy/prod               生产 Compose、Nginx、发布脚本、示例 env
  script/sql/migrations     按文件名时间顺序执行的迁移脚本
  models/                   SSCD 模型（不进 Git）
web/                        React 管理后台
deploy/preview-static       纯 UI 静态预览产物（无后端）
docs/                       设计、调研、接口与上线文档
```

---

## 快速开始（本地全栈）

前置：Docker Desktop + Docker Compose V2。SSCD 模型不在版本库中，启动脚本会自动下载并校验 SHA-256；下载失败时手动放置到 `server/models/sscd_disc_mixup.torchscript.pt`。

```bash
# macOS / Linux
bash server/deploy/local/start-local.sh
```

```powershell
# Windows PowerShell
cd server\deploy\local
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
```

脚本会拉取基础镜像、构建镜像、执行数据库迁移、等待健康检查，完成后访问：

- 地址：`http://localhost:8088`
- 默认账号：`admin` / `admin123`

常用命令（`server/deploy/local/`）：

| 目的 | 命令 |
| --- | --- |
| 查看状态 | `powershell -ExecutionPolicy Bypass -File .\status-local.ps1` |
| 查看日志 | `logs-local.ps1` |
| 停止 | `stop-local.ps1` |
| 清空数据（**危险**，仅用户明确要求） | `reset-local.ps1` |

> `local` profile 下图片存储使用本地磁盘，不依赖 OSS；未配置 `ARK_API_KEY` 时仅"图片 AI 分析"不可用，其余能力完整可跑。

### 一键公网演示

在一台 Linux 主机（建议 ≥ 4 核 8 GB，安全组放行 80 端口）上执行：

```bash
bash server/deploy/local/start-public.sh
```

脚本自动安装 Docker、按内存选择 JVM 堆与并发档位、生成随机强口令的 `.env.local`、复用本地启动流程、通过 `compose.public.yml` 暴露前端 80 端口并自检。仅建议临时演示，不要导入真实业务数据。

---

## 生产部署

生产部署目录：`server/deploy/prod/`（`compose.yml`、`nginx.conf`、`publish-prod.sh`、`publish-prod.ps1`、`.env.oss.example`）。

```bash
cp server/deploy/prod/.env.oss.example /data/app/image-admin/.env   # 填写后 chmod 600
bash server/deploy/prod/publish-prod.sh
```

发布脚本流程：preflight 校验（迁移结果、提示词非空）→ 构建 amd64 镜像 → sha256 校验上传 → 服务端串行锁 → 加载镜像 → `up -d --wait` 等待健康检查 → 失败自动回滚 → 清理历史镜像（保留 `latest` 与 `rollback`）。

要点：

- 数据库迁移必须按文件名时间顺序执行 `server/script/sql/migrations/*.sql`，preflight 会校验关键迁移是否落地。
- SSCD 模型与 `server/deploy/image_analysis_prompt.txt` 需自行放到服务器；提示词修改必须重新发布后端镜像。
- 生产环境 `QDRANT_URL`、`OSS_*`、`ARK_*`、三方 `*_API_KEY` 在 Compose 中均为必填（`${VAR:?}`），缺失则容器拒绝启动。
- 前端 `VITE_*` 变量在**构建时**内联，运行时修改无效；`VITE_APP_BASE_API` 必须与 Nginx location（`/prod-api`）一致，`VITE_APP_ENCRYPT` 必须与后端 `api-decrypt.enabled` 一致。
- 部署形态是完整全栈服务，不是静态站点：需常驻 Linux 主机，建议 8 核 16 GB 起步（低配调优参数见 [docs/生产上线手册.md](docs/生产上线手册.md)）。

---

## 开发命令

前端（`web/`）：

```bash
pnpm install
pnpm dev        # http://localhost:8000
pnpm lint
pnpm build:prod
```

后端（`server/`）：

```bash
mvn -pl wuzh-admin -am clean test "-Plocal,djl-linux"
mvn -pl wuzh-admin -am clean package "-Plocal,djl-linux" "-DskipTests"
```

删除或移动过 Java 类必须使用 `clean package`，避免 `target/classes` 残留旧类。没有本机 JDK/Maven 时可用 `server/deploy/local/` 的 Docker 构建。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/项目总览.md](docs/项目总览.md) | 目标与边界、主流程、数据表、配置与验证清单 |
| [CONTEXT.md](CONTEXT.md) | 组织授权、图片分析、模板生产、套图流程的业务语言 |
| [docs/生产上线手册.md](docs/生产上线手册.md) | 部署路径选择、环境变量、操作步骤、故障排查、安全待办 |
| [docs/对外接口文档.md](docs/对外接口文档.md) | 对外接口说明 |
| [docs/订单明细外部接口文档.md](docs/订单明细外部接口文档.md) | 订单明细外部接口 |
| [docs/TRO信息外部接口文档.md](docs/TRO信息外部接口文档.md) | TRO 外部接口 |
| [docs/图片存储与OSS上线设计.md](docs/图片存储与OSS上线设计.md) | 对象存储与 OSS 上线设计 |
| [server/README.md](server/README.md) | 后端模块与本地启动 |
| [web/README.md](web/README.md) | 前端开发与构建 |
| [server/render-worker/README.md](server/render-worker/README.md) | 渲染 worker 接口与环境变量 |

---

## 注意事项

- 密钥与 `.env*` 不进版本库；任何 AI/三方 API Key 不得写入代码、文档或前端产物。
- SSCD 模型（约 94 MB）因体积未纳入 Git，由脚本下载或手动上传。
- 新增或移动前端页面时，需同步 `web/src/pages/dynamicPage.tsx` 的 `migratedPages` 映射。
- 前端不要硬编码 `localhost:8080`；容器环境统一走 `/prod-api/`。
