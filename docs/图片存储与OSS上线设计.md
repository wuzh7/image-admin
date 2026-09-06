# 图片存储与 OSS 上线设计

## 1. 目标

本地环境继续使用 Docker Volume 中的文件目录；生产环境使用阿里云 OSS 私有 Bucket。员工上传原图时不经过 ECS 公网带宽，Spring Boot 只负责登录鉴权、签发短时上传 URL、校验上传结果、保存对象 Key 和调度后续任务。

## 2. 固定链路

```text
浏览器请求短时 PUT URL
  -> 浏览器直接上传 OSS review/{batchId}/{uuid}.{ext}
  -> 浏览器通知 Spring Boot 上传完成
  -> Spring Boot 用 HeadObject 校验对象并保存 Key
  -> 后台通过 OSS 内网 Endpoint 下载临时文件并执行 SHA-256、SSCD、Qdrant 检索
  -> 所有有效图片进入 WAIT_REVIEW
  -> 人工提交保留/拒绝决定，接口立即返回
  -> 后台 CopyObject 到 library/{yyyy}/{MM}/{hashPrefix}/{uuid}.{ext}
  -> 写入 Qdrant，最后把 MySQL 状态改为 APPROVED
  -> 异步删除 review 对象
```

严格大于 95% 或 SHA-256 完全重复的图片禁止入库。任何 OSS Copy、Qdrant 或数据库提交失败都不得形成 `APPROVED` 记录。

## 3. 存储接口

`ImageStorage` 是图片业务唯一依赖的存储边界：

- `LocalImageStorage`：处理本地 Multipart 上传、缩略图文件和本地文件复制。
- `OssImageStorage`：签发 PUT/GET URL、HeadObject 校验、下载临时文件、CopyObject 和删除对象。
- OSS 模式不保存实体缩略图；列表直接返回包含 `x-oss-process` 的短时签名 URL。

MySQL 继续只保存对象 Key、文件元数据和向量，不保存图片二进制。

## 4. 配置

```yaml
image:
  storage:
    type: oss
    oss:
      endpoint: ${OSS_ENDPOINT}
      public-endpoint: ${OSS_PUBLIC_ENDPOINT}
      region: ${OSS_REGION}
      bucket: ${OSS_BUCKET}
      role-name: ${OSS_ROLE_NAME:image-admin-prod-ecs-role}
```

`OSS_ENDPOINT` 使用 `https://s3.oss-cn-hangzhou-internal.aliyuncs.com`；`OSS_PUBLIC_ENDPOINT` 使用 `https://s3.oss-cn-hangzhou.aliyuncs.com`，两者都不要包含 Bucket 名称，客户端会自动拼接。Bucket 为 `image-admin-prod-ww`，地域为 `cn-hangzhou`，必须保持私有。默认从 ECS RAM 角色获取自动刷新的临时凭证，不需要长期 AccessKey。

## 5. 安全和运维

- PUT URL 默认 15 分钟失效，GET URL默认 10 分钟失效。
- URL 只授权后端生成的单个 `review/{batchId}/...` Key，并绑定 Content-Type、Content-Length 和禁止覆盖请求头。
- 完成上传时再次验证批次归属、Key 前缀、实际大小和 Content-Type。
- OSS CORS 只允许正式后台域名，方法允许 PUT、GET、HEAD，请求头允许 Content-Type、x-oss-forbid-overwrite。
- RAM 身份只授予目标 Bucket 的 GetObject、PutObject、DeleteObject 权限；不得使用主账号 AccessKey。
- 不要给整个 `review/` 设置定时删除生命周期，避免误删长时间待审核的有效图片。图片申请上传前已登记；超过24小时未提交检测的批次由数据库状态驱动清理。

## 6. 上线门禁

上线前必须用真实 Bucket 完成一次：直传、后台读取、相似度检测、签名缩略图、人工确认、OSS Copy、Qdrant 写入、拒绝删除、服务重启恢复。任一步未验证通过时继续保持 `local`，不得把生产配置切换为 `oss`。

详细部署、故障边界与实测记录见同目录《OSS部署与验收手册.md》。切换配置不会自动迁移原有本地图库，旧数据迁移须另行核验。
