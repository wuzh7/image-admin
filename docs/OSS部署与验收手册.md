# OSS 部署与验收手册

更新：2026-09-06。本手册对应本次代码，不表示整个系统已经上线。

## 一、已确认的环境

| 项目 | 值 |
| --- | --- |
| ECS | i-bp10t7y63rvasdu34imd，杭州，2核，约3.4GiB可见内存 |
| Bucket / 地域 | image-admin-prod-ww / cn-hangzhou |
| ECS角色 | image-admin-prod-ecs-role |
| Docker网络 | image-admin-net |
| MySQL容器 | 1Panel-mysql-DX2o |
| Redis容器 | 1Panel-redis-Zc5j |
| Qdrant容器 | image-admin-qdrant |
| 正式域名 | 尚未提供 |

本次不会重复安装、删除或重建这些基础服务。当前服务器容量小于原计划：先升级容量或进行单检测工作线程的内存/耗时实测，不承诺现配置能承载10人同时批量检测。业务构建在开发机完成，不在这台小内存ECS上执行完整Maven/Node构建。

## 二、先理解代码变化

本地仍用 LocalImageStorage 和 Docker Volume；生产用 OssImageStorage。两者通过 ImageStorage 接口切换，通用 OSS SDK 层复用已有模块。

浏览器 → Java申请单对象短时PUT URL → 浏览器直传OSS → Java内网HEAD校验 → 提交检测 → Java内网下载临时文件 → SSCD/Qdrant → 人工审核 → 后台复制与入库。

上传和浏览器预览不经ECS转发原图；后台检测仍需下载到ECS临时文件，处理后关闭并删除，并非完全不占ECS磁盘。OSS请求、处理及公网流量仍按云产品计费。

### 图片与批次状态

| 图片状态 | 含义 |
| --- | --- |
| UPLOADING | 已申请上传，未通知完成 |
| UPLOADED / CHECKING | 已上传 / 检测中 |
| WAIT_REVIEW | 有效检测结果，等待用户决定 |
| APPROVING | 已提交保留决定，后台入库中 |
| APPROVAL_FAILED | 入库失败，可从任务页重试 |
| APPROVED | 复制、向量写入和数据库提交已完成 |
| DELETE_PENDING / REJECTED | 拒绝待清理 / 已清理 |

批次入库时为CONFIRMING；接口只返回“已提交”，不显示“已入库”。任务页每2秒刷新入库或检测状态。非重复图片默认勾选，但所有有效结果都要人工提交。SHA完全重复或相似度严格大于95%不可入库，恰好95%可选择。

后台顺序：复制原图 → 校验大小与ETag → 写Qdrant → 事务提交MySQL APPROVED及正式Key → 异步删除review源对象。正式Key固定为 `library/{yyyy}/{MM}/{sha前2位}/{imageId}.{ext}`，重试沿用同一目标。

### 异常边界

- 检测或入库异常不能判成“不重复”或“入库成功”。源文件保留供重试。
- 单张入库持久化进度，服务重启继续CONFIRMING批次；部分成功时已成功图片可见，其余明确失败，不伪装成跨系统的整批原子事务。
- Qdrant写入后数据库失败会尝试删除Point；回滚也失败时可能暂存孤立Point，召回候选还须满足MySQL有效APPROVED状态。管理员索引修复与入库互斥，避免误删提交中的Point。
- 数据库提交结果不确定时先查库，已提交成功不能再删除向量。
- 复制成功但后续失败可能同时保留源与固定目标，重试不会再生成新Key；长期不重试的失败图片仍可能占两份空间，不擅自清理。
- 已入库源文件清理失败，待清理Key保存于数据库，自动重试。
- 超过24小时仍未提交检测的上传批次进入清理队列，需要重新上传；不提供断点续传和过期地址续签。
- **不要给整个review前缀加删除生命周期**，否则待审图片也可能被删除。
- 拒绝与图库删除异步删除对象，本次没有回收站恢复界面。
- 当前为单Java业务实例，不能直接扩成多个实例竞争执行任务。MySQL与Qdrant不是跨系统ACID事务，不能承诺永不不一致。

新增接口：`GET /image/storage`、`POST /image/batches/{id}/upload-tickets`、`POST /image/batches/{id}/upload-complete`、`GET /images/{id}/access?thumbnail=true`。原有批次、审核、图库接口保留。

## 三、OSS控制台配置

1. 确认Bucket地域杭州，读写权限为私有，不要为方便预览改成公共读。
2. 确认ECS绑定上述RAM角色，业务策略仅授权目标Bucket的必要对象读写删除；复制需要读源和写目标权限。
3. 不将长期AccessKey写入源码、镜像或前端。SDK从ECS角色刷新临时凭证。本机Workbench只是运维工具，其授权与业务角色不同。[ECS角色官方说明](https://help.aliyun.com/zh/ecs/user-guide/attach-an-instance-ram-role-to-an-ecs-instance)
4. 本次对象操作权限实测可用；CORS/ACL管理读取返回403，可由管理员在控制台检查，不需为业务角色扩大管理权限。

项目使用AWS S3兼容协议，按[阿里云官方配置](https://help.aliyun.com/zh/oss/developer-reference/use-aws-sdks-to-access-oss)使用下列地址，而非原生OSS SDK地址：

```ini
OSS_BUCKET=image-admin-prod-ww
OSS_REGION=cn-hangzhou
OSS_ROLE_NAME=image-admin-prod-ecs-role
OSS_ENDPOINT=https://s3.oss-cn-hangzhou-internal.aliyuncs.com
OSS_PUBLIC_ENDPOINT=https://s3.oss-cn-hangzhou.aliyuncs.com
```

**Endpoint不加Bucket名**，客户端自动拼接；公网地址用于浏览器，内网地址用于同地域ECS。

### CORS规则

OSS控制台 → Bucket → 权限控制 → 跨域设置：

| 设置 | 值 |
| --- | --- |
| 来源 | `https://你的正式后台域名`，含协议，不含页面路径 |
| 方法 | PUT、GET、HEAD |
| 允许请求头 | Content-Type、x-oss-forbid-overwrite |
| 暴露响应头 | ETag、x-oss-request-id |
| 预检缓存 | 600秒 |

浏览器自动发送OPTIONS预检。不要把后台Token加入OSS请求。需要本地联调时可临时添加 `http://localhost:8088`，之后移除。正式域名未提供，当前没有猜测域名或配置来源通配符。

OSS模式不保存实体缩略图。短时签名中包含 `x-oss-process=image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82`。不能对已签名原图URL再追加参数。访问URL不入库，前端预览失败可重试。

## 四、部署顺序

### 1. 准备目录与配置

在ECS准备 `/opt/image-admin/prod`，放入 `server/deploy/prod/compose.yml`。从 `.env.oss.example` 复制生成 `.env.prod`，在服务器填写真实数据库业务用户、密码、Redis密码、健康检查随机密码、镜像版本、已有订单接口配置。不要提交或分享 `.env.prod`。

模型放在 `/opt/image-admin/models/sscd_disc_mixup.torchscript.pt`。项目仍是全Java业务，模型文件不能删除。

Compose只启动backend/frontend，复用image-admin-net基础服务。示例中的容器名按本次只读检查填写，1Panel重建容器后要重新核对。

### 2. 数据库与旧图库

全新业务库需要 `wuzh_vue.sql`、`image.sql`、当前订单模块的 `order.sql`；已有库先备份再执行适用增量，不要盲目重跑初始化SQL。

本次新增图片字段 `cleanup_key`、`cleanup_thumbnail_key`，增量语句在image.sql末尾，按列是否存在检查后添加。

**切换storage.type不会自动迁移旧Docker Volume图片。** 新生产空图库无需迁移；若保留旧图库，需单独上传原图、映射Key并核对大小/哈希/数量，迁移完才能切换。此次没有执行旧图库全量迁移，不能把存有本地Key的原库原样切成OSS。

### 3. 构建镜像并启动

开发机测试、构建后给镜像打版本标签，通过镜像仓库或docker save/load导入ECS。在服务器业务目录运行：

```bash
docker network inspect image-admin-net
docker compose --env-file .env.prod config --quiet
docker compose --env-file .env.prod up -d backend
docker compose --env-file .env.prod ps
docker compose --env-file .env.prod logs --tail 100 backend
# 后端健康后
docker compose --env-file .env.prod up -d frontend
curl -I http://127.0.0.1:8088/
docker stats --no-stream
```

配置缺失时拒绝退回本地存储。`-Xmx`只限制Java堆，不限制DJL/PyTorch原生内存；必须看容器总内存。当前配置默认1个检测工作线程，仍需验证模型内存峰值。

容器需能访问IMDSv2。若宿主机角色正常而容器获取失败，检查到 `100.100.100.200` 的路由、防火墙和ECS元数据响应跳数；不要关闭IMDSv2或硬编码长期密钥。

### 4. 1Panel域名与HTTPS

域名解析到ECS并满足备案等适用上线条件。在1Panel创建反向代理站点，目标 `http://127.0.0.1:8088`，申请证书并开启HTTPS。若OpenResty不使用宿主网络，须调整目标为实际可达地址，不能把容器localhost当宿主机。

安全组不开放MySQL、Redis、Qdrant、Java8080和前端8088；只开放必要业务端口，1Panel管理端口限制来源。公网代理应禁止 `/prod-api/actuator/`，健康检查仅限内部。

### 5. 更新、日志、回滚

```bash
docker compose --env-file .env.prod ps
docker compose --env-file .env.prod logs --tail 100 -f backend
# 先备份、确认维护时间，再更新.env.prod镜像版本
docker compose --env-file .env.prod up -d --no-deps backend frontend
```

保留上一版支持OSS的镜像供回滚，不要在已写入OSS后回滚成仅Local版本。禁止 `down -v`。本次没有停止或改写ECS基础服务。

## 五、验证记录与上线门禁

2026-09-06已验证：Workbench连接ECS；角色真实OSS协议测试（公网签名PUT、禁止覆盖409、内网HEAD/GET、签名缩略图、复制校验、删除）通过，测试对象均清理；项目实际Java OSS实现分别在ECS宿主机、image-admin-net网络中的非root临时容器运行通过，验证了容器IMDSv2角色凭证、上传、下载、签名缩略图、复制校验与删除；前端lint、TypeScript、生产构建通过。

本地新版本已使用 `compose.yml + compose.oss-verify.yml` 启动，MySQL、Redis、Qdrant、后端、前端均健康，模型校验和预热完成。验证镜像复用了本机已有运行环境（Docker Hub认证地址连接超时），生产正式镜像仍需在网络正常时完成标准构建及漏洞检查。ECS只运行临时OSS测试，没有部署完整业务或停改基础服务。

本轮后端local标签全链路构建的Surefire报告共69项：67通过、2项需要额外基线配置的模型测试跳过，失败/错误为0。图片模块41项中39通过、2跳过。新增接口回归测试曾复现预览URL误入msg字段的问题，现改为R.data返回并通过测试。

浏览器本地测试记录：`2096483392090771458`（test_green.jpg，检测完成待审核，相似度0.82989）；`2096483837836234753`（程序生成的oss-local-smoke-20260906.png，检测后人工确认成功）。后者图片ID为 `2096483839706894337`，MySQL为APPROVED、批次COMPLETED，Qdrant Point存在，暂存源清理Key已清空。两条本地测试批次保留供复核，没有操作其他历史待审核批次。

ECS Java测试包临时保存在 `/tmp/image-admin-java-oss-20260906`，是一次性验证文件，不是已部署的业务服务；所有 `oss-smoke` OSS测试对象均已删除。

仍需验证：正式域名/CORS/私有ACL确认；完整Java业务接入生产数据；模型内存和批量并发；浏览器直传→去重→审核→图库→删除；生产备份恢复。上述小文件协议测试不等于这些已通过。

验收入库必须检查任务完成、MySQL为APPROVED、OSS正式对象可读、Qdrant存在Point，再用新批次验证召回；不能只看接口“已提交”就认定成功。
