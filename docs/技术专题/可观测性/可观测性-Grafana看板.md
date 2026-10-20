# 可观测性：Grafana 看板

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [Grafana 与 Prometheus 的分工](#grafana-与-prometheus-的分工)
  - [为什么一图一问](#为什么一图一问)
- [📊 应用与对比](#应用与对比)
  - [数据源配置](#数据源配置)
  - [首屏四面板](#首屏四面板)
  - [验证看板](#验证看板)
  - [常见问题](#常见问题)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**Grafana 不生产指标，它把 Prometheus 的时间线组织成一屏可判断的线上证据。**</span>

---

## 🎯 背景与问题

Prometheus 的 `/query` 适合临时写 PromQL，但不适合每天值班：

- 一次主要看一条查询
- 多接口和多指标不好排版
- 时间范围、单位和阈值不直观
- 无法快速分享固定排查视图

Grafana 解决的是“查询结果的界面组织”，不是数据采集。

---

## 💡 核心目的

让值班人员在一屏内回答四个问题：

1. 现在流量是否正常
2. 请求是否失败
3. 用户是否在等待
4. 应用是否出现内存压力

---

## ⚙️ 原理与机制

### Grafana 与 Prometheus 的分工

```java
应用 → Micrometer → /actuator/prometheus
                         ↓ Prometheus 拉取并保存
                    Prometheus
                         ↓ Grafana 查询
                      Grafana 看板
```

| 组件 | 职责 |
|:---|:---|
| Prometheus | 采集、存储时间线、执行 PromQL |
| Grafana | 配置数据源、发查询、画图、保存看板 |

Grafana 在容器里运行时，数据源不能写 `localhost:9090`，因为那是 Grafana 自己的 loopback。当前使用：

```text
http://host.docker.internal:9090
```

### 为什么一图一问

一个 Panel 可以放多条 Query，但前提是单位相同、回答同一个问题。

可以合并：

- 2xx / 4xx / 5xx 请求速率，单位都是 `req/s`
- 多个接口的 P95，单位都是毫秒
- 多实例 CPU 使用率，单位都是百分比

不建议合并：

- QPS 的 `req/s`
- 错误率的百分比
- 延迟的毫秒

不同单位共用一条 y 轴会让曲线无法比较；双轴又容易制造虚假的相关性。

---

## 📊 应用与对比

### 数据源配置

| 项 | 值 |
|:---|:---|
| 类型 | Prometheus |
| URL | `http://host.docker.internal:9090` |
| 访问方式 | Server |

验证标准：`Save & test` 显示 `Successfully queried the Prometheus API`。

### 首屏四面板

| 面板 | 查询核心 | 单位 |
|:---|:---|:---|
| Request Rate (QPS) | `sum(rate(http_server_requests_seconds_count[5m]))` | `req/s` |
| Server Error Rate | 5xx 速率 ÷ 总速率 | `%` |
| Menu Tree P95 | `histogram_quantile(0.95, sum by (le) (...))` | `ms` |
| JVM Heap Memory | `jvm_memory_used_bytes` / `committed_bytes` | `bytes(IEC)` |

QPS 排除监控请求：

```promql
sum(rate(http_server_requests_seconds_count{
  uri!~"/actuator.*"
}[5m]))
```

5xx 比例：

```promql
100 * (
  sum(rate(http_server_requests_seconds_count{
    outcome="SERVER_ERROR",
    uri!~"/actuator.*"
  }[5m])) or vector(0)
)
/
clamp_min(
  sum(rate(http_server_requests_seconds_count{
    uri!~"/actuator.*"
  }[5m])),
  0.000001
)
```

菜单接口 P95：

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

JVM 堆：

```promql
sum(jvm_memory_used_bytes{area="heap"})
```

```promql
sum(jvm_memory_committed_bytes{area="heap"})
```

不要直接对 `jvm_memory_max_bytes` 求和：G1 的部分内存池返回 `-1`，没有单独上限。

### 验证看板

发送 30 次菜单请求后，Prometheus 实测：

```text
QPS      0.105 req/s
P95      4.02 ms
5xx      0
```

QPS 偏低是因为 30 次请求被 `rate(...[5m])` 平均到了 5 分钟窗口。

### 常见问题

| 现象 | 原因 |
|:---|:---|
| 数据源连接失败 | 容器内使用了 `localhost` |
| QPS 为 0 | 窗口内没有业务请求 |
| P95 空白或 NaN | 窗口内没有直方图样本 |
| Legend 显示 Value | 写成了 `{{固定文字}}`，这不是引用标签 |
| JVM 单位不直观 | Unit 未设为 `bytes(IEC)` |
| 图中包含监控请求 | 没有排除 `/actuator.*` |

---

## 🔥 面试要点

**Q1：Grafana 和 Prometheus 的关系？**

> **面试话术**：*"Prometheus 负责采集和存储时序指标，并提供 PromQL；Grafana 只作为查询和可视化层，不保存原始时间线。看板上的每个 Panel 最终都是一次 Prometheus 查询。"*

**Q2：你的监控首屏为什么选这四个指标？**

> **面试话术**：*"QPS 判断流量，5xx 比例判断请求是否失败，菜单接口 P95 判断用户在不在等待，JVM Heap 判断内存压力。四个面板分别覆盖流量、正确性、延迟和资源，不把所有维度塞进一张图。"*

---

## 🧠 记忆口诀

```text
Prometheus 存和算
Grafana 查和画
一图一问
同单位才合并
```

---

## 🔄 知识闭环

```java
指标进入 Prometheus
  → Grafana 通过数据源查询
  → 四个 Panel 各自回答一个问题
  → 值班人员先判断影响范围
  → 再用 traceId 和日志继续定位原因
```
