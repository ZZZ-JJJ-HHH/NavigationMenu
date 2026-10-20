# 可观测性：Actuator 与 Micrometer 指标链路

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [Actuator 是端点门面](#actuator-是端点门面)
  - [端点的两层开关](#端点的两层开关)
  - [Prometheus 端点为什么是带条件的](#prometheus-端点为什么是带条件的)
  - [Filter / Micrometer / Prometheus](#filter--micrometer--prometheus)
  - [liveness 与 readiness](#liveness-与-readiness)
- [📊 应用与对比](#应用与对比)
  - [本项目的端点](#本项目的端点)
  - [亲手验证](#亲手验证)
  - [常见误区](#常见误区)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**Actuator 负责把应用状态变成 HTTP 端点；Micrometer 负责在内存记账；Prometheus 负责把数字拉走并按时间保存。**</span>

---

## 🎯 背景与问题

只有健康检查时，你只能知道应用“活着还是死了”。

一个每秒 1000 个请求、30% 返回 500 的服务，`/actuator/health` 仍可能返回 `UP`。线上真正需要知道的是：

- 每秒多少请求
- 错误率多少
- P95 延迟多少
- JVM 内存是否上涨
- 数据库和 Redis 是否健康

这些能力由 Actuator、Micrometer 和 Prometheus 分工完成。

---

## 💡 核心目的

理解四层边界：

```java
Actuator  暴露端点
Micrometer 在内存中记录和翻译指标
Prometheus 拉取并保存时间线
Grafana    查询 Prometheus 并展示
```

避免把“有 health 端点”“有 `/actuator/prometheus`”“有 Prometheus 容器”混成同一件事。

---

## ⚙️ 原理与机制

### Actuator 是端点门面

`spring-boot-starter-actuator` 把应用内部状态暴露成一组统一挂在 `/actuator` 下的 HTTP 端点。

它本身不是监控系统：

- 不长期保存数据
- 不主动发送邮件
- 不负责画图

它只是让应用内部的运行状态可以被访问。

### 端点的两层开关

| 层 | 配置 | 回答 |
|:---|:---|:---|
| 启用 | `management.endpoint.<name>.enabled` | 这个端点是否存在 |
| 暴露 | `management.endpoints.web.exposure.include` | 这个端点是否对外可见 |

类比：第一层决定是否安装仪表，第二层决定是否开窗让人查看。

本项目当前只暴露需要的端点，`health.show-details` 也保持收敛，避免暴露依赖版本、路径和磁盘信息。

| 端点 | 用途 | 风险 |
|:---|:---|:---|
| `health` | 存活和依赖状态 | `always` 可能泄露内部细节 |
| `metrics` / `prometheus` | 指标数据 | 通常只给监控系统访问 |
| `loggers` | 运行时查看和修改日志级别 | 可能放大日志量 |
| `env` / `configprops` | 配置属性 | 口令和密钥可能泄露 |
| `beans` / `mappings` | Bean 和路由结构 | 暴露内部结构 |
| `threaddump` / `heapdump` | 线程和内存快照 | 可能包含敏感数据 |

> ⚠️ `loggers`、`env`、`heapdump` 不应直接暴露在公网业务端口。生产环境通常使用独立管理端口、内网访问和认证。

### Prometheus 端点为什么是带条件的

Actuator 端点分成两类：

| 类型 | 示例 | 额外条件 |
|:---|:---|:---|
| 自带端点 | `health`、`info`、`loggers` | 只需要暴露 |
| 条件端点 | `prometheus`、`metrics` | 还需要 registry 依赖 |

只有 `micrometer-registry-prometheus` 存在于 classpath，Spring Boot 才会注册 `/actuator/prometheus`。

### Filter / Micrometer / Prometheus

```java
ServerHttpObservationFilter
  │  请求开始时计时，结束时拿到 uri / status
  ▼
Micrometer
  │  在内存中维护 count / sum / bucket
  │  翻译成 Prometheus 文本格式
  ▼
/actuator/prometheus
  │  Prometheus 每 15 秒主动拉取
  ▼
Prometheus 时序数据库
```

| 角色 | 所在位置 | 职责 |
|:---|:---|:---|
| `ServerHttpObservationFilter` | Spring Framework，应用进程内 | 决定何时采、采什么 |
| Micrometer | 独立库，应用进程内 | 内存记账、指标格式转换 |
| Prometheus | 独立进程 | 拉取、存历史、提供查询 |

指标不是从日志统计出来的。应用重启后内存计数器归零；历史来自 Prometheus 保存的采样点。

### liveness 与 readiness

| 探针 | 失败后的动作 | 适用含义 |
|:---|:---|:---|
| liveness | 重启进程 | 进程卡死，重启可能恢复 |
| readiness | 从负载均衡摘除，不重启 | 依赖未就绪，重启没有意义 |

> ⚠️ 把依赖状态直接绑到 liveness，依赖抖动可能导致所有实例同时重启，放大故障。

---

## 📊 应用与对比

### 本项目的端点

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

`prometheus` 端点由以下两部分共同满足：

1. `pom.xml` 中有 `micrometer-registry-prometheus`
2. `management.endpoints.web.exposure.include` 中包含 `prometheus`

### 亲手验证

检查健康端点：

```powershell
curl.exe -s http://localhost:8080/actuator/health
```

检查指标端点和 Content-Type：

```powershell
curl.exe -s -D - -o NUL http://localhost:8080/actuator/prometheus
```

正常应包含：

```text
HTTP/1.1 200
Content-Type: text/plain;version=0.0.4;charset=utf-8
```

搜索项目指标：

```powershell
curl.exe -s http://localhost:8080/actuator/prometheus |
  Select-String "http_server_requests_seconds_count"
```

运行时查看日志级别：

```powershell
curl.exe -s http://localhost:8080/actuator/loggers/com.example.navigation_menu
```

### 常见误区

| 误区 | 正确理解 |
|:---|:---|
| health UP 就代表业务正常 | health 只代表进程和部分依赖状态 |
| target UP 就代表指标正确 | 可能抓到错误 Content-Type 或错误应用 |
| 指标来自日志 | 指标来自应用内存中的 Micrometer |
| 加了 include 就有 prometheus | 还需要 registry 依赖 |
| liveness 和 readiness 可以混用 | 两者失败动作完全不同 |

---

## 🔥 面试要点

**Q1：Actuator、Micrometer、Prometheus 怎么分工？**

> **面试话术**：*"Actuator 把应用内部状态暴露成端点；Micrometer 在应用内存里维护指标并翻译成 Prometheus 格式；Prometheus 是独立进程，定时拉取端点并保存时间线。应用负责产生和暴露，Prometheus 负责采集和存储。"*

**Q2：health 正常为什么业务还可能 500？**

> **面试话术**：*"health 主要判断进程和配置的依赖状态，业务错误率、局部接口失败和性能下降需要 HTTP 指标、日志和告警来判断。一个接口持续 500 时 health 仍可能是 UP。"*

**Q3：liveness 和 readiness 的区别？**

> **面试话术**：*"liveness 失败通常重启实例；readiness 失败只把实例从流量中摘掉。依赖暂时不可用时应该影响 readiness，而不是触发全量重启。"*

---

## 🧠 记忆口诀

```text
Actuator 开窗
Micrometer 记账
Prometheus 抄走并存历史
Grafana 画图
liveness 管重启
readiness 管流量
```

---

## 🔄 知识闭环

```java
请求进入应用
  → ObservationFilter 采集状态和耗时
  → Micrometer 在内存累计
  → /actuator/prometheus 暴露当前快照
  → Prometheus 定时拉取
  → 保存成时间线
  → Grafana 查询并展示
```
