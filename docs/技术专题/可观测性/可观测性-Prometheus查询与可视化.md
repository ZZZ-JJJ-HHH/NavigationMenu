# 可观测性：Prometheus 查询与可视化

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [一条时间线的三要素](#一条时间线的三要素)
  - [四种指标类型](#四种指标类型)
  - [选择器与时间窗口](#选择器与时间窗口)
  - [五个够用的函数](#五个够用的函数)
- [📊 应用与对比](#应用与对比)
  - [页面怎么用](#页面怎么用)
  - [按问题查命令](#按问题查命令)
  - [本项目可直接用的查询](#本项目可直接用的查询)
  - [时区：为什么不是北京时间](#时区为什么不是北京时间)
  - [五个常见坑](#五个常见坑)
  - [告警规则怎么设计](#告警规则怎么设计)
  - [通知与看板的边界](#通知与看板的边界)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**PromQL 只做两件事：先"选出哪几条时间线"，再"在时间窗口上算一个数"。**</span>

---

## 🎯 背景与问题

应用已经把数字吐出来了，Prometheus 也把它们按时间存下来了。但打开界面你会发现：**它不给你现成的"QPS 图"和"错误率图"**。

它给你的是一堆原始时间线：

```
http_server_requests_seconds_count{uri="/api/menu/tree",status="200",method="POST"} 42
http_server_requests_seconds_count{uri="/api/menu/tree",status="500",method="POST"} 1
```

要回答"现在每秒多少次""错误率多少""P95 多慢"，得自己写查询。

| 痛点 | 表现 |
|:---|:---|
| 不知道从哪条指标下手 | 几十条指标名，看不出哪条对应自己的问题 |
| 把累计值当成"当前值" | 看到 `count=42` 以为是"现在 42 个并发" |
| 窗口和步长乱设 | `rate` 出来是锯齿、空值，或数字大得离谱 |
| 时区对不上 | 图表时间比日志慢 8 小时，怀疑数据错了 |

---

## 💡 核心目的

把"我想问的业务问题"翻译成一条查询：

```
业务问题                      PromQL
─────────────────────────────────────────────────────
现在流量多大？          →   sum(rate(...[5m]))
错误率高不高？          →   5xx 速率 ÷ 总速率
哪个接口最慢？          →   topk(3, histogram_quantile(...))
跟一小时前比涨还是跌？   →   同一查询换时间范围
```

---

## ⚙️ 原理与机制

### 一条时间线的三要素

```java
http_server_requests_seconds_count{ uri="/api/menu/tree", status="200" }   42
└──────────────┬──────────────────┘└──────────── 标签 ────────────┘   └┬┘
          指标名：什么量                              哪一类          采样值
```

**标签组合决定"有几条线"**：`uri` 或 `status` 只要有一处不同，就是另一条独立时间线，各自累加。

时间戳不在文本里 —— 每次抓取时由 Prometheus 打上，所以一行文本只代表**某一个采样点**。

### 四种指标类型

| 类型 | 例子 | 特性 | 怎么用 |
|:---|:---|:---|:---|
| **Counter** 计数器 | `http_server_requests_seconds_count` | 只增不减，重启归零 | **必须套 `rate()` / `increase()`** |
| **Gauge** 瞬时值 | `jvm_memory_used_bytes` | 可升可降 | 直接看，或 `avg_over_time()` |
| **Histogram** 直方图 | `..._seconds_bucket` | 分桶累计，配套 `_sum` / `_count` | 先 `rate` 再 `histogram_quantile()` |
| **Summary** 摘要 | 本项目没用 | 分位数由客户端算好 | 直接读 |

> ⚠️ **Counter 不能直接画趋势**。`count=42` 画出来是一条缓慢上升的斜线，看不出流量变化。**流量要看 `rate()`**。

> ⚠️ **Counter 也不能直接做比例。** `sum(5xx_count) / sum(all_count)` 算的是"从应用启动到现在的平均错误率"，会被整个历史稀释 —— 此刻 100% 的请求都失败，这个比例可能还停在 0.1%。要么两边都 `rate`，要么两边都 `increase`，**而且窗口必须一致**。

同一件事的两种写法（比例相同，念法不同）：

| 写法 | 念作 | 什么时候用 |
|:---|:---|:---|
| `sum(rate(a[5m])) / sum(rate(b[5m]))` | 每秒错几个 ÷ 每秒共几个 | 关心**速度** |
| `sum(increase(a[5m])) / sum(increase(b[5m]))` | 5 分钟错几次 ÷ 5 分钟共几次 | 关心**比例**，更好读 |

> 🔑 **`rate = increase ÷ 窗口秒数`**，分子分母同时约掉窗口长度，所以两种写法算出的比例相同。本项目告警规则选 `increase`，因为念出来就是"过去 5 分钟错了几次、占多少"。

### 选择器与时间窗口

```promql
http_server_requests_seconds_count                        # 所有线
http_server_requests_seconds_count{uri="/api/menu/tree"}  # 等值过滤
http_server_requests_seconds_count{uri=~"/api/.*"}        # 正则
http_server_requests_seconds_count{status!="200"}         # 排除
rate(http_server_requests_seconds_count[5m])              # 范围向量：喂给函数
```

| 写法 | 含义 |
|:---|:---|
| `{label="x"}` | 精确等值 |
| `{label=~"re"}` | 正则匹配 |
| `{label!="x"}` / `!~` | 排除 |
| `[5m]` | 取"过去 5 分钟"的采样点，**只能喂给函数**，不能单独画图 |

### 五个够用的函数

| 函数 | 干什么 | 典型用法 |
|:---|:---|:---|
| `rate(x[5m])` | 每秒速率（counter 专用） | 流量、错误率 |
| `increase(x[1h])` | 窗口内总增量 | "过去一小时一共多少次" |
| `sum by (标签) (x)` | 按标签分组求和 | 按接口、按状态码聚合 |
| `histogram_quantile(0.95, ...)` | 分位数 | P95 / P99 |
| `avg_over_time` / `max_over_time` | gauge 的时间平均/最大 | 内存、连接数 |

> 🔑 **`rate` 的窗口至少要有两个采样点**。抓取间隔 15 秒，`[5m]` 大约 20 个点，足够稳；`[10s]` 会经常算出空值。

---

## 📊 应用与对比

### 页面怎么用

| 地址 | 用途 |
|:---|:---|
| `/query` | **主战场**。输入 PromQL，Graph 看趋势、Table 看当前各条线的值（Prometheus 3 里 `/graph` 会重定向到这里） |
| `/targets` | 抓取任务体检：UP/DOWN、上次抓取、耗时、错误 |
| `/config` | 当前真正生效的配置（排查"我改了怎么没生效"） |
| `/status` | 运行时信息、TSDB 状态 |

操作顺序：**顶部选时间范围 → 输入查询 → Execute → Table 核对数字 → Graph 看趋势**。

### 按问题查命令

| 我想知道 | 查询 |
|:---|:---|
| 应用还在不在 | `up{job="navigation-menu"}` |
| 总 QPS | `sum(rate(http_server_requests_seconds_count[5m]))` |
| 各接口 QPS | `sum by (uri) (rate(http_server_requests_seconds_count[5m]))` |
| 各状态码 QPS | `sum by (status) (rate(http_server_requests_seconds_count[5m]))` |
| 5xx 错误率 | `sum(increase(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum(increase(http_server_requests_seconds_count[5m]))` |
| 某接口 P95 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/menu/tree"}[5m])))` |
| 最慢的三个接口 | `topk(3, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))))` |
| 过去一小时总请求数 | `increase(http_server_requests_seconds_count[1h])` |
| 抓取是否正常 | `scrape_samples_scraped` |
| JVM 堆内存 | `jvm_memory_used_bytes{area="heap"}` |
| 连接池活跃连接 | `hikaricp_connections_active` |

> ⚠️ **缓存命中率现在查不到**：命中/未命中只写在**日志**里（`eventType=cache, result=hit/miss`），没有做成 Counter 指标。要看曲线，得在 `MenuTreeCacheService` 里加一个 `Counter` —— 属于"业务指标"，还没做。

### 本项目可直接用的查询

```promql
# 流量：最近 5 分钟每秒请求数
sum(rate(http_server_requests_seconds_count{uri="/api/menu/tree"}[5m]))

# 错误率：5xx 占全部请求的比例（0.01 = 1%）
sum(increase(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
  / sum(increase(http_server_requests_seconds_count[5m]))

# 延迟：菜单接口的 P95（单位秒）
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/menu/tree"}[5m])))

# 看板降噪：排除 actuator 自身的抓取请求
sum(rate(http_server_requests_seconds_count{uri!~"/actuator/.*"}[5m]))
```

### 时区：为什么不是北京时间

**Prometheus 内部根本没有"时区"这个概念。** 它存的是 Unix 时间戳（从 1970 年起的秒数），全球统一。时区只存在于**显示层**。

Web 界面默认按 **UTC** 渲染，所以看起来比北京时间慢 8 小时。

| 位置 | 默认 | 怎么改成北京时间 |
|:---|:---|:---|
| Prometheus Web UI | UTC | 右上角**齿轮 → Global settings → 勾选 `Use local time`** |
| Grafana | 跟随浏览器 | 时间范围选择器 → Timezone → Browser time |
| API 返回的 `value[0]` | Unix 秒（无时区） | 自己转：`DateTimeOffset.FromUnixTimeSeconds()` |

> ⚠️ **不要在 PromQL 里做 `+8h` 这类偏移**。查询里的时间都是 UTC 基准，硬加 8 小时会破坏窗口和范围的语义 —— 该改的是显示层，不是数据层。

> 🔑 **判断数据对不对，别靠界面上的钟点**。用 `increase(...[1h])` 数出来的量和你实际打的请求数对得上，就说明抓取和数据都是对的，时区只是显示问题。

### 五个常见坑

| 坑 | 现象 | 正确做法 |
|:---|:---|:---|
| 把 counter 当当前值 | "现在 42 个请求" | 要看速率就 `rate()` |
| 告警挂累计值 | 报一次永不恢复 | 挂 `rate` 或比例 |
| `metric[5m]` 单独画图 | 报错或空图 | 必须套函数，如 `rate(metric[5m])` |
| 窗口太短 | 结果时有时无、锯齿 | 窗口至少是抓取间隔的 4 倍（15s → `[1m]` 起） |
| 忘了过滤 actuator | 看板里全是抓取请求 | `uri!~"/actuator/.*"` |

> ⚠️ **两类特殊 uri**：没匹配到 handler 的请求，`uri` 会退化成 `UNKNOWN`（405）或 `/**`（404）。做"各接口 QPS"看板时要单独处理，否则会混进正常接口的排名。

### 告警规则怎么设计

一条规则由四部分组成：

```yaml
groups:
  - name: navigation-menu
    rules:
      - alert: HighServerErrorRate
        expr: |                                     # ① 表达式：算出一个数，和阈值比
          sum(increase(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
          / sum(increase(http_server_requests_seconds_count[5m])) > 0.01
        for: 1m                                     # ② 持续时间：去抖
        labels:
          severity: critical                        # ③ 标签：分级、决定发给谁
        annotations:
          summary: "菜单服务 5xx 比例超过 1%"          # ④ 说明：写清"该做什么"
```

**五条设计原则**：

| 原则 | 为什么 |
|:---|:---|
| **挂速率或比例，不挂累计值** | 累计值永不回落，告警会一直响、永远不恢复 |
| **一定要写 `for`** | 没有它，一次瞬时超时就会半夜叫人 |
| **加最低流量门槛** | 凌晨只有 2 个请求，1 个失败就是 50% 错误率 —— 纯误报 |
| **告警必须可执行** | 每条都要能回答"我收到之后做什么"，答不出的就是噪音 |
| **不要按高基数标签分组** | 按 `uri` 分组会生成几十条告警；聚合，或只盯核心接口 |

带流量门槛的版本：

```promql
(sum(increase(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
 / sum(increase(http_server_requests_seconds_count[5m])) > 0.01)
and
(sum(increase(http_server_requests_seconds_count[5m])) > 3)
```

> 🔑 末尾的 `> 3` 读作"过去 5 分钟至少有 3 次业务请求"。本地演示用 3；生产上按真实流量定（比如正常 QPS 是 100，门槛可以设成 300 次/5 分钟这种量级）。

> 🔑 **先不用上 Alertmanager**。规则加进 Prometheus 后，`/alerts` 页面就能看到每条告警的状态流转：`inactive → pending → firing → inactive`。先用这个验证"阈值和 `for` 写得对不对"，通知渠道（邮件/钉钉/电话）是下一步的事。

**怎么验证一条规则**：故意制造故障（比如 `docker compose stop redis`），然后盯 `/alerts` 页面 —— 应该看到它先进入 `pending`（时间还没到 `for`），`for` 时间到了才变 `firing`；恢复 Redis 后自动回到 `inactive`。**三个状态都走一遍，规则才算验证过。**

**本项目实际落地的三条规则**（配置文件 `ops/prometheus/rules.yml`，`for` 都是 1 分钟）：

| 告警 | 判定 | 触发条件 |
|:---|:---|:---|
| `NavigationMenuTargetDown` | `up{job="navigation-menu"} == 0` | 抓不到应用，持续 1 分钟 |
| `HighServerErrorRate` | 业务接口 5xx 比例 > 1%，且带流量门槛 | 持续 1 分钟 |
| `HighMenuTreeLatency` | `/api/menu/tree` 的 P95 > 1 秒 | 持续 1 分钟 |

> ⚠️ **规则文件必须挂进容器**：`prometheus.yml` 里写 `rule_files: [/etc/prometheus/rules.yml]`，启动容器时挂载的是**整个目录**（`-v ".../ops/prometheus:/etc/prometheus:ro"`），而不是只挂 `prometheus.yml` —— 否则规则文件在容器里根本不存在，`/api/v1/rules` 会是空的。

**实测的状态流转**（2026-10-18，本地）：

```java
17:43:19 ~ 17:44:50   每 10 秒打一次失败请求（先把 Redis 停掉）
17:45:09   HighServerErrorRate       pending   value=1（100% 错误率）
17:45:29   HighServerErrorRate       firing

17:45:4x   停掉应用
17:46:30   NavigationMenuTargetDown  pending
17:47:30   NavigationMenuTargetDown  firing
```

> ⚠️ **第一次验证失败，原因值得单独记住**：只打了一波 5 个失败请求就停手，规则一直停在 `inactive`。因为 `rate()` 算的是**两个采样点之间的增长** —— 那 5 次发生在 Prometheus 拿到基线采样之前，之后计数不再变化，`rate` 就是 0。**验证告警要"持续制造变化"，不是"打一波就走"。** 改成每 10 秒一次、连续 90 秒，规则才正常进入 `pending → firing`。

> ⚠️ **验证时拿到 400 而不是 500，先别怀疑规则**。400 属于 `CLIENT_ERROR`，5xx 告警**本来就不该被它触发** —— 规则只统计 `outcome="SERVER_ERROR"`。这时候要查的是"请求本身对不对"，先去日志里搜 `unreadable request body`。

> ⚠️ **PowerShell 传 JSON 有个经典坑**：`curl.exe ... -d $body` 在 Windows PowerShell 5.1 里会把 JSON 的双引号吃掉，服务端收到 `{countryCode:CN,...}`，直接 400。**用文件传最稳**：把 body 放在 `test/test_body.json`，然后写 `-d "@test/test_body.json"`。日志里对应的报错是 `JSON parse error: Unexpected character ('c' ...): was expecting double-quote to start field name`。

### 通知与看板的边界

Prometheus 只负责判断规则是否成立，不负责分组、抑制、静默和通知。真实通知渠道、路由、抑制和静默见 [可观测性-Alertmanager告警治理](./可观测性-Alertmanager告警治理.md)。

Grafana 不采集和保存指标，只查询 Prometheus 并组织看板。数据源、首屏四面板和单位设置见 [可观测性-Grafana看板](./可观测性-Grafana看板.md)。

> 🔑 **Prometheus 负责“算”，Alertmanager 负责“发”，Grafana 负责“看”。三者不要混成一个概念。**

## 🔥 面试要点

**Q1：QPS 和错误率你们是怎么算的？**

> **面试话术**：*"HTTP 指标是 Boot 自动装配的 observation 记下来的，是 counter。QPS 用 `sum(rate(http_server_requests_seconds_count[5m]))`，按状态码细分就加 `by (status)`。错误率是 5xx 和总量的比值 —— 用 `increase` 更直观，读作'5 分钟错几次 ÷ 5 分钟共几次'；用 `rate` 也对，两者只差一个窗口系数。`outcome="SERVER_ERROR"` 这个标签是 Boot 帮忙归好类的。之所以不能直接拿 count 相除，是因为 count 只增不减，除出来是启动以来的平均错误率，会被历史稀释。"*

**Q2：P95 延迟怎么来的？**

> **面试话术**：*"靠直方图。应用侧把耗时按桶累计，Prometheus 存的是 `_bucket` 那条时间线，查询时先 rate 再 `histogram_quantile(0.95, ...)`。前提是应用侧开了直方图开关，否则只有 count 和 sum，算不出分位数。"*

**Q3：告警为什么不能直接用累计值？**

> **面试话术**：*"累计值只增不减，一旦超过阈值就永远满足条件，告警会一直响、永远不恢复。所以阈值必须挂在速率或比例上，比如'5 分钟内 5xx 比例超过 1%'。"*

---

## 🧠 记忆口诀

```
先选线，再算窗口 —— 这是 PromQL 的全部

  counter 只增不减 → 看速率用 rate
  gauge   可升可降 → 看当下直接读
  histogram 分桶   → 先 rate 再 quantile

  告警挂速率，不挂累计
  窗口别太短，至少四个抓取间隔
  时区是显示问题，不是数据问题
```

---

## 🔄 知识闭环

```java
应用内存里的数字（第 3、4 步埋的点）
      │  暴露成文本
      ▼
/actuator/prometheus
      │  Prometheus 每 15s 拉一次，打上时间戳
      ▼
时间线：指标名 + 标签 + 一串 (时间, 值)
      │  用 PromQL 选线、算窗口
      ▼
rate / increase / sum by / histogram_quantile
      │
      ├─ /query 页面：Table 看当前值，Graph 看趋势
      └─ 告警规则：同一套查询 + 阈值 + 持续时间
      │
      ▼
出口：从"应用有多少个数字"到"我能回答流量多大、错多少、多慢"。
```
