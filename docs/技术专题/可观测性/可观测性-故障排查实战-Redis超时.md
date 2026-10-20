# 可观测性：Redis 超时故障排查实战

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [四条证据线怎么汇合](#四条证据线怎么汇合)
  - [为什么不能从 traceId 开始](#为什么不能从-traceid-开始)
  - [指标窗口为什么会消失](#指标窗口为什么会消失)
- [📊 应用与对比](#应用与对比)
  - [故障现场](#故障现场)
  - [第 1 步：Grafana 看整体方向](#第-1-步grafana-看整体方向)
  - [第 2 步：Prometheus 确认错误和延迟](#第-2-步prometheus-确认错误和延迟)
  - [第 3 步：为什么邮箱没有收到告警](#第-3-步为什么邮箱没有收到告警)
  - [第 4 步：Splunk 找异常请求](#第-4-步splunk-找异常请求)
  - [第 5 步：traceId 还原完整请求](#第-5-步traceid-还原完整请求)
  - [第 6 步：读 ERROR 和 stackTrace](#第-6-步读-error-和-stacktrace)
  - [第 7 步：核对基础设施](#第-7-步核对基础设施)
  - [第 8 步：恢复并验证](#第-8-步恢复并验证)
  - [设计复盘](#设计复盘)
  - [完整排查模板](#完整排查模板)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**线上排查从“症状和影响范围”开始，traceId 是缩小到单次请求后的证据，不是起点。**</span>

---

## 🎯 背景与问题

Redis 被手动停止后，菜单接口 `POST /api/menu/tree` 返回 500，单次请求耗时约 3 秒。

如果只看到用户说“接口报错了”，手里只有一个时间点，排查需要依次回答：

1. 是单个接口还是全部接口？
2. 是错误、延迟还是资源问题？
3. 错误请求的 traceId 是什么？
4. 请求在哪一层失败？
5. 外部依赖当时是否正常？
6. 恢复后如何证明服务真的好了？

本项目使用四条证据线一起回答这些问题。

---

## 💡 核心目的

把一次 500 故障从“用户反馈”还原成可验证的时间线：

```java
现象 → 指标 → 告警状态 → 日志 → traceId → 调用栈 → 外部状态 → 恢复验证
```

最终不仅要说“Redis 挂了”，还要说清楚：

> Redis 不可用后，缓存读取等待 3 秒并超时，异常传播到全局异常处理器，接口返回 500，访问日志记录 3036ms。

---

## ⚙️ 原理与机制

### 四条证据线怎么汇合

```java
Grafana
  │  看 QPS / 5xx / P95 / JVM
  ▼
Prometheus
  │  确认错误率和延迟的时间线
  ▼
Alertmanager
  │  判断是否触发并发送通知
  ▼
Splunk
  │  找到 access 500，再拿 traceId 还原调用链
  ▼
Docker / 基础设施
  │  确认 Redis 进程和端口状态
  ▼
根因与恢复验证
```

| 数据 | 回答的问题 | 不能回答的问题 |
|:---|:---|:---|
| Grafana 指标 | 是否异常、影响多大、从何时开始 | 具体哪一行代码失败 |
| Alertmanager | 是否满足告警规则、通知是否发出 | 根因是什么 |
| Access 日志 | 哪个接口、状态码、耗时、traceId | 内部为什么失败 |
| ERROR 日志和 stackTrace | 哪一层、哪一行、什么异常 | 外部依赖进程是否真的存活 |
| 基础设施状态 | 容器、端口、网络是否正常 | 业务代码是否应该降级 |

### 为什么不能从 traceId 开始

真实线上故障开始时，通常没有人主动告诉你 traceId。正确顺序是：

```text
先找异常请求
      ↓
从 access 日志拿到 traceId
      ↓
再用 traceId 拉完整链路
```

直接让用户提供 traceId 只适合用户页面能显示请求编号、或者前端已经上报的场景。

### 指标窗口为什么会消失

这次故障发生在 `2026-10-20T18:49Z`，对应北京时间 `2026-10-21 02:49`。

排查时如果已经过去超过 5 分钟，再用“当前时刻”执行：

```promql
rate(http_server_requests_seconds_count[5m])
```

会得到 `0` 或 `NaN`，因为这次失败已经滑出当前 5 分钟窗口。

这不代表故障没发生。历史指标仍然在 Prometheus 中，需要把 Grafana 或查询时间调整到故障时间段。

---

## 📊 应用与对比

### 故障现场

| 项目 | 内容 |
|:---|:---|
| traceId | `cc8624a5e6b0473cabad688c9f260051` |
| 请求 | `POST /api/menu/tree` |
| 业务时间 | `2026-10-20T18:49:15.304Z` |
| ERROR 时间 | `2026-10-20T18:49:18.340Z` |
| 访问日志时间 | `2026-10-20T18:49:18.341Z` |
| HTTP 状态 | `500` |
| 总耗时 | `3036ms` |
| 异常 | `RedisCommandTimeoutException: Command timed out after 3 second(s)` |
| 代码位置 | `MenuTreeCacheService.get(MenuTreeCacheService.java:24)` |
| 外部状态 | `redis7` 容器已退出，`6379` 不可连接 |

### 第 1 步：Grafana 看整体方向

打开 `NavigationMenu Overview`，时间范围选故障附近：

| 面板 | 本应看到的异常 |
|:---|:---|
| QPS | 能看到是否有请求进入 |
| 5xx 错误率 | `/api/menu/tree` 出现一次 500 |
| Menu Tree P95 | 从正常几十毫秒升高到约 3 秒 |
| JVM Heap | 用于排除内存问题，本次不是主要证据 |

这一步的目标不是找根因，而是确认：

```text
异常集中在菜单接口
表现是 500 + 约 3 秒延迟
```

### 第 2 步：Prometheus 确认错误和延迟

查看业务接口的 5xx 增长：

```promql
sum by (uri, status) (
  increase(http_server_requests_seconds_count{
    outcome="SERVER_ERROR",
    uri!~"/actuator.*"
  }[5m])
)
```

查看菜单接口 P95：

```promql
1000 * histogram_quantile(
  0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{
      uri="/api/menu/tree"
    }[5m])
  )
)
```

故障发生时，应能看到：

```text
status=500 增加
P95 约为 3 秒
```

### 第 3 步：为什么邮箱没有收到告警

当前 `HighServerErrorRate` 规则不是“出现一个 500 就发邮件”，还要求：

- 5xx 比例大于 1%
- 5 分钟内业务请求数大于 3
- 条件持续 1 分钟

这次可能只有少量请求，没有通过业务请求量门槛；或者当前时间已经超出 5 分钟窗口。

> 🔑 **没有收到告警不等于没有故障。告警规则有阈值、流量门槛、`for` 持续时间，还要考虑通知链路。**

排查顺序仍然是：

1. Grafana 是否出现异常
2. Prometheus 规则在 `/alerts` 的状态
3. Alertmanager 是否收到 firing
4. 通知渠道是否发送成功

### 第 4 步：Splunk 找异常请求

先按时间和状态找 access 日志，而不是直接搜 traceId：

```spl
index=nav_menu event_type=access status>=500
| table _time traceId method path status durationMs
| sort - _time
```

从结果中拿到：

```text
traceId=cc8624a5e6b0473cabad688c9f260051
path=/api/menu/tree
status=500
durationMs=3036
```

### 第 5 步：traceId 还原完整请求

```spl
index=nav_menu traceId="cc8624a5e6b0473cabad688c9f260051"
| sort @timestamp
| table _time level event_type status durationMs logger message
```

关键事件：

```text
18:49:15.304Z  INFO   Controller 收到 getMenuTree
18:49:18.340Z  ERROR  GlobalExceptionHandler: system error
18:49:18.341Z  INFO   AccessLog: status=500, durationMs=3036
```

> ⚠️ **一次 500 通常有两条互补日志**：access 日志负责范围和耗时，ERROR 日志负责原因和堆栈。两者通过 traceId 关联。

### 第 6 步：读 ERROR 和 stackTrace

ERROR 日志：

```text
logger: com.example.navigation_menu.exception.GlobalExceptionHandler
message: system error
level: ERROR
```

堆栈关键行：

```text
io.lettuce.core.RedisCommandTimeoutException:
  Command timed out after 3 second(s)

at com.example.navigation_menu.service.cache.MenuTreeCacheService.get(
  MenuTreeCacheService.java:24
)
```

由此定位到：

- 不是 Controller 参数错误
- 不是 MySQL
- 不是责任链业务拒绝
- 是 Redis 读取超时

错误入口见 [GlobalExceptionHandler.java](../../../src/main/java/com/example/navigation_menu/exception/GlobalExceptionHandler.java)，具体失败点见 [MenuTreeCacheService.java](../../../src/main/java/com/example/navigation_menu/service/cache/MenuTreeCacheService.java)。

### 第 7 步：核对基础设施

```powershell
docker ps -a --filter "name=redis7"
Test-NetConnection localhost -Port 6379
```

实际状态：

```text
redis7 Exited (0)
6379 不可连接
```

与日志证据完全吻合。

根因链：

```java
Redis 容器停止
      ↓
Lettuce 等待 Redis 命令
      ↓
3 秒超时
      ↓
MenuTreeCacheService.get 抛异常
      ↓
GlobalExceptionHandler 记录 ERROR
      ↓
接口返回 500
      ↓
AccessLogFilter 记录 3036ms
```

### 第 8 步：恢复并验证

恢复依赖：

```powershell
docker start redis7
```

发送一条新请求并使用新的 traceId：

```powershell
curl.exe -s -o NUL -w "%{http_code}" `
  -X POST http://localhost:8080/api/menu/tree `
  -H "Content-Type: application/json" `
  -H "X-Trace-Id: redis-recovery-check-001" `
  --data-binary "@test/test_body.json"
```

验证四点：

1. HTTP 返回 `200`
2. Grafana 的 5xx 不再增长
3. P95 回到正常毫秒级
4. Splunk 能看到 `redis-recovery-check-001` 的 cache `hit/miss/set`

### 设计复盘

技术根因是 Redis 被停止，但更值得讨论的是：

> 缓存本来是性能优化，为什么 Redis 不可用会让菜单接口直接 500？

可选改进：

- 缓存读取失败时降级查数据库
- 设置合理的 Redis 命令超时和熔断
- 增加 Redis 可用性指标和告警
- 区分“缓存故障”和“核心数据库故障”
- 对降级路径做压测，确认数据库能承受流量

> ⚠️ 是否降级取决于业务一致性。如果缓存中的数据和数据库存在强一致约束，不能盲目 fail-open，需要结合业务规则设计。

### 完整排查模板

```text
1. Grafana：确定异常接口、状态、延迟、时间窗
2. Prometheus：确认错误率和 P95 时间线
3. Alertmanager：检查是否 firing、是否发送
4. Splunk access：按 status>=500 找到 traceId
5. Splunk traceId：还原完整请求时间线
6. ERROR stack：定位异常类型和代码行
7. 基础设施：核对 Redis / MySQL / 网络
8. 恢复：新 traceId 验证 200、延迟和日志
9. 复盘：根因、影响、修复、预防
```

---

## 🔥 面试要点

**Q1：线上接口突然出现 500，你怎么查？**

> **面试话术**：*"先从 Grafana 和 Prometheus 确认异常接口、开始时间、错误率和 P95，判断影响范围。然后去 Splunk 按时间和 status>=500 找 access 日志，拿到 traceId，再用 traceId 把这次请求的 Controller、缓存、数据库和异常堆栈拉出来。日志定位到依赖后，再核对 Redis、MySQL 等基础设施状态。恢复后用新 traceId 验证 200、延迟和缓存日志，最后做根因和降级复盘。"*

**Q2：为什么 ERROR 日志和 access 500 不能混在一起？**

> **面试话术**：*"它们回答不同问题。ERROR 日志带异常类型和 stackTrace，回答为什么失败；access 日志带接口、状态码和耗时，回答影响是什么。一次请求可能同时产生两类事件，通过 traceId 关联，而不是指望一条日志包含所有信息。"*

**Q3：缓存挂了，接口应该怎么办？**

> **面试话术**：*"缓存是可降级的性能组件时，可以捕获缓存异常、记录指标并回源数据库，同时做过载保护。但如果业务依赖缓存里的强一致数据，就不能盲目降级。关键是把依赖失败策略、超时、熔断和数据库容量提前设计并验证。"*

---

## 🧠 记忆口诀

```text
先看面 —— Grafana / Prometheus
再找点 —— access 500 和 traceId
后看栈 —— ERROR 堆栈和代码行
终核外 —— Redis / MySQL / 网络
恢复后 —— 新 traceId 验证闭环
```

---

## 🔄 知识闭环

```java
用户只看到 500
      │
      ▼
Grafana 确认影响范围和时间
      │
      ▼
Prometheus 确认错误率、P95、告警状态
      │
      ▼
Splunk access 日志找到异常请求
      │
      ▼
traceId 还原 Controller → 缓存 → 异常 → 响应
      │
      ▼
基础设施核对确认 Redis 停止
      │
      ▼
恢复后用新 traceId 验证 200
      │
      ▼
根因、降级、监控和预防进入复盘
```
