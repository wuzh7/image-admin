# Redis 上线架构建议

## 结论

Redis 用于服务端的可撤销登录态是成熟方案，适合本项目；它不保存用户、密码和权限的权威数据，这些仍保存在 MySQL。当前单台 ECS、约 10 名员工的阶段，使用一套受保护的 Redis 即可，不需要 Redis Sentinel、Cluster 或微服务。

当前前端把 Bearer Token 存于 `localStorage`，这不是推荐的生产形态。正式上线时应改为：服务端在 Redis 保存会话，浏览器只保存 `HttpOnly`、`Secure`、`SameSite=Lax` 的会话 Cookie。这样前端脚本不能直接读到登录凭证；登出、改密码、禁用账号和管理员强制下线时，服务端删除 Redis 会话即可立即失效。

## 本项目的分阶段方案

| 阶段 | 推荐配置 | 不做什么 |
| --- | --- | --- |
| 当前本地和首个单 ECS 生产环境 | 1 个 Redis 服务，AOF `everysec`、命名卷/云盘备份、密码/ACL、仅 Docker 内网访问 | 不上 Cluster，不为 10 个用户额外维护 3 台 Sentinel |
| 两台以上 Java 实例，或 Redis 故障不能接受 | 独立 Session Redis：1 主 2 副本 + 3 Sentinel；客户端启用 Sentinel 发现 | 不把登录会话与可驱逐缓存混用 |
| 缓存或会话容量、吞吐确实需要横向扩展 | Session 与 Cache 分别规划 Redis Cluster | 不把 Cluster 当作强一致数据库 |

## 单 ECS 上线底线

1. Redis 不映射公网端口，只允许 Java 容器通过 Docker 内网访问。
2. 使用强密码，生产环境进一步使用 Redis ACL 限制应用账号命令权限。
3. 打开 AOF `appendfsync everysec`，并定期备份 RDB/AOF；Redis 重启或极端故障后，少量会话丢失时让用户重新登录即可。
4. 登录会话设置固定过期时间和闲置过期时间；用户、角色或密码变更时清除相关会话。
5. Cookie 显式设置 `HttpOnly`、`Secure`、`SameSite=Lax`；HTTPS 由 Nginx 终止时正确传递转发协议头。
6. 登录态使用与普通缓存不同的 key 前缀、TTL 和监控。若后续缓存增长明显，再拆出第二个 Redis，避免缓存淘汰造成用户随机掉线。

## 为什么不使用纯 JWT

JWT 可以让服务端不查询 Redis，但无法天然实现立即注销、管理员踢下线、角色变更即时生效。若为 JWT 再维护撤销名单，仍需要 Redis，且实现更复杂。因此传统内部管理后台优先选 Redis 会话/令牌映射；只有对外 API、多个独立服务或第三方授权时，再评估短期 Access Token 加 Refresh Token 的 OAuth/OIDC 模式。

## 官方依据

- [Spring Session：Redis HttpSession 集成](https://docs.spring.io/spring-session/reference/http-session.html)
- [Spring Security：认证持久化与会话管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Redis：持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Redis：复制与一致性边界](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)
- [Redis：Sentinel 高可用](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)
- [Redis：内存淘汰策略](https://redis.io/docs/latest/develop/reference/eviction/)
- [OWASP：会话管理](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- [OWASP：JWT 使用建议](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html)
