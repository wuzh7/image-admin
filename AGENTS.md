# AGENTS.md

本文件适用于整个 `image-admin` 仓库。

## 项目定位

- 这是图片管理系统的全 Java 实现，面向可上线的小团队 MVP。
- 后端位于 `server/`：JDK 21、Spring Boot、Maven、MySQL、Redis、Qdrant、DJL/SSCD。
- 前端位于 `web/`：React 19、TypeScript、Ant Design、Umi Max、pnpm。
- 主要业务模块是 `wuzh-system`、`wuzh-image` 和 `wuzh-order`；Spring Boot 启动模块是 `wuzh-admin`。
- 本地完整环境由 `server/deploy/local/` 中的 Docker Compose 和 PowerShell 脚本统一管理。

## 工作原则

1. 编码前先读取相关代码、调用方和现有测试；说明假设、权衡和成功标准。
2. 只修改完成需求所必需的文件，不顺手重构、格式化或清理无关代码。
3. 优先复用仓库现有模式和已安装依赖；不为未提出的需求增加抽象、开关或兼容层。
4. 保留用户的未提交改动。修改前后都检查 `git status --short` 和 `git diff`，不覆盖或撤销无关变更。
5. Bug 修复应先复现，再在最小的共享根因处修复，并留下能防止回归的测试。
6. 不提交密钥、真实账号、`.env.local`、`.env.prod`、业务数据、模型二进制文件或构建产物。

## 业务与实现约束

- 图片上传后先进入暂存/审核流程；只有人工确认的图片才进入正式图库和 Qdrant。
- 相似度逻辑必须可解释，不得将模型失败当成“不重复”，不得绕过人工确认。
- 新增或移动动态页面时，同步检查 `web/src/pages/dynamicPage.tsx` 的 `migratedPages` 映射。
- 用户联系方式等账号规则需同时覆盖自助注册与管理员新增/修改路径，包括 DTO 校验、服务端唯一性检查和持久化。
- 不要在前端硬编码 `localhost:8080`；容器环境通过 `/prod-api/` 由 Nginx 转发。
- 登录加密改动必须同时检查前端环境、前端 Dockerfile、Compose 与后端 `application-local.yml`，保证 `VITE_APP_ENCRYPT` 和 `api-decrypt.enabled` 一致。
- 订单明细导出保持“导出当前筛选条件下的全部结果”，不要与页面分页绑定。

## 常用命令

前端命令在 `web/` 执行：

```powershell
pnpm install
pnpm dev
pnpm lint
pnpm build:prod
```

后端命令在 `server/` 执行：

```powershell
mvn -pl wuzh-admin -am clean test "-Plocal,djl-linux"
mvn -pl wuzh-admin -am clean package "-Plocal,djl-linux" "-DskipTests"
```

如本机没有 JDK/Maven，使用 `server/deploy/local/` 的 Docker 构建。删除或移动 Java 类后必须使用 `clean package`，避免旧 `target/classes` 残留。

本地完整环境命令在 `server/deploy/local/` 执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
powershell -ExecutionPolicy Bypass -File .\status-local.ps1
powershell -ExecutionPolicy Bypass -File .\logs-local.ps1
powershell -ExecutionPolicy Bypass -File .\stop-local.ps1
```

`reset-local.ps1` 会删除本项目的 MySQL、Redis、Qdrant 和图片数据卷。只有用户明确要求清空数据时才能执行。

## 验证与交付

- 按改动范围运行最小相关测试；后端逻辑至少运行对应 Maven 测试，前端改动至少运行 `pnpm lint`。
- 改动数据库、动态菜单、路由、环境变量或容器时，不能只看源码/构建结果；需重建并重建容器，然后在实际页面验证请求、数据和可见状态。
- 测试以退出码和 Surefire 报告的 `failures/errors` 为准；Mockito 动态 agent 警告本身不是失败。
- 交付时说明改了什么、运行了哪些验证、哪些未验证，以及是否存在与本次任务无关的工作区改动。
