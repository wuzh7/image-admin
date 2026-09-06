# Wuzh 图片管理后台（前端）

React 管理后台，提供登录、用户/角色/菜单管理、图片批次上传、检测进度、相似图片对照审核和正式图库页面。

## 技术栈

- React 19
- TypeScript
- Ant Design
- Umi Max
- pnpm

## 本地开发

```powershell
pnpm install
pnpm dev
```

开发地址默认是 `http://localhost:8000`。

## 质量检查

```powershell
pnpm lint
pnpm build:prod
```

## Docker 环境

完整环境由后端仓库的 `deploy/local` 统一编排，启动完成后访问 `http://localhost:8088`。前端容器只负责提供静态文件，并通过 Nginx 将 `/prod-api/` 转发到 Java 后端。
