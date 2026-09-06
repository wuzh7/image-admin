# Wuzh 图片管理后台（后端）

Java 后端负责账号密码登录、用户/角色/菜单管理、图片上传、SSCD 特征提取、批次内去重、Qdrant 图库召回和人工确认入库。

## 技术栈

- JDK 21、Spring Boot、Maven
- MySQL、Redis、Qdrant
- DJL + SSCD TorchScript
- Docker Compose 本地部署

## 目录说明

```text
wuzh-admin       Spring Boot 启动入口和运行配置
wuzh-api         内部公共接口与数据对象
wuzh-common      通用基础能力，例如鉴权、数据库、Redis、日志和 Web
wuzh-modules     业务模块聚合目录
  wuzh-system    登录、用户、角色和菜单
  wuzh-image     图片上传、去重、审核和图库
deploy/local     Docker Compose、启动脚本和本地运行手册
models           Java 推理使用的 SSCD 模型文件目录
script/sql       数据库初始化脚本
```

`wuzh-modules` 只保留 `wuzh-system` 和 `wuzh-image` 两个业务模块，所有未启用的扩展、任务、AI、演示、代码生成和工作流源码已删除。

## 本地启动

本机只需要 Docker Desktop、Docker Compose V2 和 Git。SSCD 模型放在：

```text
models/sscd_disc_mixup.torchscript.pt
```

在 PowerShell 中执行：

```powershell
cd deploy\local
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
```

启动完成后访问 `http://localhost:8088`，默认账号为 `admin`，密码为 `admin123`。

详细的启动、停止、日志、重置和故障排查命令见 [本地运行手册](deploy/local/README.md)。

## 后端构建

```powershell
cd deploy\local
docker compose --env-file .env.local build backend
```

Docker 构建阶段执行 Maven，生成并打包：

```text
wuzh-admin/target/wuzh-admin.jar
```

最终得到本地镜像 `image-admin-backend:local`，不要求 Windows 单独安装 JDK 或 Maven。

## 当前功能边界

- 已实现：默认登录、用户/角色/菜单、图片批次上传、SSCD 去重、Qdrant 召回、人工审核和图库。
- 暂未实现：侵权检测、阿里云 OSS、完整角色矩阵、批量下载、ECS 正式发布和向量量化。

## 开源许可

项目包含基于开源代码演进的内容，原始版权声明保留在 `LICENSE` 中；产品名称、构建坐标、配置和用户界面均使用 Wuzh 标识。
