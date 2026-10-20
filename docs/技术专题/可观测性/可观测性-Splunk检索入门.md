# 可观测性：Splunk 检索入门

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [Search & Reporting 页面地图](#search--reporting-页面地图)
  - [四个必须认识的字段](#四个必须认识的字段)
  - [_time 和 @timestamp 的关系](#_time-和-timestamp-的关系)
  - [SPL 管道的思考方式](#spl-管道的思考方式)
  - [时间范围先于搜索语句](#时间范围先于搜索语句)
- [📊 应用与对比](#应用与对比)
  - [第一次搜索](#第一次搜索)
  - [本项目的真实排查查询](#本项目的真实排查查询)
  - [常用 SPL 命令](#常用-spl-命令)
  - [字段怎么发现](#字段怎么发现)
  - [时区怎么看](#时区怎么看)
  - [搜不到数据的排查顺序](#搜不到数据的排查顺序)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**Splunk 检索不是“搜关键词”，而是先用 index 和时间窗圈定数据，再用 SPL 管道把事件变成答案。**</span>

---

## 🎯 背景与问题

日志已经进入 Splunk，但打开页面看到空白搜索框，仍然不知道从哪里开始。

真实工作中，你不会漫无目的地搜一个词，而是带着问题来：

| 问题 | 需要的答案 |
|:---|:---|
| 用户反馈某个请求报错 | 这个 traceId 的完整执行过程 |
| 错误率为什么涨了 | 5xx 集中在哪个接口、哪个时间点 |
| 今天接口慢不慢 | 各接口的平均值、P95、最大值 |
| Redis 缓存是否正常 | `hit`、`miss`、`set` 各有多少 |

**Splunk 的职责是把原始日志事件变成这些可判断的答案。**

---

## 💡 核心目的

学会用固定套路完成线上排查：

```java
先缩小范围 → 找到异常请求 → 拿到 traceId → 还原完整链路 → 得出结论
```

不需要一开始背很多 SPL 命令。先掌握 `index`、时间范围、`stats`、`table`、`sort` 和 `timechart`，已经能回答大部分问题。

---

## ⚙️ 原理与机制

### Search & Reporting 页面地图

登录 `http://localhost:8000` 后，进入 `Search & Reporting`。页面主要分成五块：

```java
┌─────────────────────────────────────────────────────┐
│ ① 时间选择器        Last 24 hours / All time        │
├─────────────────────────────────────────────────────┤
│ ② 搜索栏            index=nav_menu traceId=...      │
├─────────────────────────────────────────────────────┤
│ ③ 结果区            Events / Patterns / Statistics  │
│                     Visualization                   │
├─────────────────────────────────────────────────────┤
│ ④ 左侧字段栏        Interesting fields              │
├─────────────────────────────────────────────────────┤
│ ⑤ 时间分布图        每个时间点有多少条日志          │
└─────────────────────────────────────────────────────┘
```

最常用的操作顺序：

1. 选时间范围
2. 输入基础搜索
3. 看左侧出现了哪些字段
4. 用 `stats` 或 `timechart` 聚合
5. 需要可视化时切换 `Visualization`

### 四个必须认识的字段

| 字段 | 含义 | 本项目示例 |
|:---|:---|:---|
| `index` | 数据放在哪个逻辑索引中 | `nav_menu` |
| `sourcetype` | Splunk 按哪套规则解析数据 | `_json` |
| `_time` | Splunk 用于搜索和排序的事件时间 | `2026-10-20 15:16:36.316` |
| `_raw` | 原始事件文本 | 一整行 JSON |

### `_time` 和 `@timestamp` 的关系

`app.log` 中没有 `_time`，只有业务日志里的：

```json
{"@timestamp":"2026-10-20T17:58:05.0766283Z"}
```

`_time` 是 Splunk 的内置事件时间字段，转换链路是：

```java
app.log 中的 @timestamp
        │
        ▼
Fluent Bit JSON Parser
        │  Time_Key    @timestamp
        │  Time_Format %Y-%m-%dT%H:%M:%S.%LZ
        ▼
Fluent Bit 事件时间
        │
        ▼
Splunk HEC 事件信封 time
        │
        ▼
Splunk 内部时间戳
        │
        ▼
检索中显示的 _time
```

关系可以概括为：

- `@timestamp`：日志正文里的业务字段，原样保留在 JSON 中
- `_time`：Splunk 从事件时间中提取的特殊元数据
- `sort - _time`：按 Splunk 事件时间倒序排列
- `timechart`：按 `_time` 分时间桶聚合
- 时间选择器、`earliest`、`latest`：搜索的也是 `_time`

可以同时显示两者做对照：

```spl
index=nav_menu earliest=0
| head 5
| table _time @timestamp traceId event_type
```

正常情况下，两者表示的是同一时刻，只是名称、格式和所在层不同。如果 `_time` 明显接近采集时间，而 `@timestamp` 是更早的业务时间，就要检查 Fluent Bit Parser 的 `Time_Key` 和 `Time_Format`，这说明时间没有解析成功，Splunk 回退到了接收时间。

业务字段来自 JSON Parser 和 Splunk 的 JSON 字段提取：

```text
traceId
event_type
level
status
durationMs
path
method
result
service
collector
```

> ⚠️ **修改或删除 `app.log` 不会修改已经进入 Splunk 的事件**。Splunk 保存的是采集时的副本，不是`app.log` 的实时镜像。只改写旧日志行，不会让历史 `status=500` 突然变成 200；删除本地文件也不会自动删除索引中的数据。验证采集应该追加一条带唯一 traceId 的新日志，或者使用新的时间窗口和独立索引。

> ⚠️ **原始 JSON 字段叫 `eventType`，检索字段叫 `event_type`**。Splunk 自带一个名为 `eventtype` 的内置字段，而且字段名不区分大小写；如果直接发送 `eventType`，Splunk 会把它自动改名为 `extracted_eventType`。因此 Fluent Bit 在发送前执行 `Rename eventType event_type`。切换前的旧数据仍可能是 `extracted_eventType`，迁移期可以写成 `(event_type=access OR extracted_eventType=access)`。

<span style="color:red">**先用 index 和时间窗缩小数据，再谈命令和图表；直接搜一个孤立关键词，最容易得到大量无关结果。**</span>

### SPL 管道的思考方式

SPL 从左到右执行，`|` 表示“把上一步结果交给下一步”：

```java
index=nav_menu event_type=access
    │  基础搜索：选出 access 事件
    ▼
| where durationMs > 200
    │  过滤：只保留慢请求
    ▼
| stats perc95(durationMs) as p95 by path
    │  聚合：按接口算 P95
    ▼
| sort - p95
    │  展示：最慢的接口排前面
    ▼
结果表
```

最重要的认知：**基础搜索负责“选事件”，管道命令负责“加工事件”。**

### 时间范围先于搜索语句

搜索界面右上角的时间范围是第一个过滤器。

- 第一次确认数据是否存在：选 `All time`
- 排查最近故障：选 `Last 15 minutes` 或自定义故障时间窗
- 做日报或趋势：选 `Last 24 hours`

如果数据明明进来了却搜不到，先检查时间范围，再检查 index 和字段拼写。

---

## 📊 应用与对比

### 第一次搜索

进入 Splunk 后，时间选择 `All time`，输入：

```spl
index=nav_menu
| head 10
```

含义：

- `index=nav_menu`：只搜业务日志索引
- `head 10`：只展示前 10 条，避免一次加载太多

点击某条事件后，展开左侧字段，可以看到 `traceId`、`event_type`、`status` 等字段。Splunk 已经完成了 JSON 字段提取。

### 本项目的真实排查查询

#### 1. 用 traceId 还原一次请求

这是本项目最重要的查询：

```spl
index=nav_menu traceId=fluentbit-smoke-001
| sort @timestamp
| table _time event_type level status durationMs path message
```

排查动作：

1. 从访问日志、错误日志或用户反馈中得到 `traceId`
2. 用 traceId 拉出这次请求的全部事件
3. 按 `@timestamp` 排序
4. 沿着 Controller → 责任链 → 缓存 → Mapper → AccessLog 的顺序阅读

> 🔑 <span style="color:red">**traceId 查询不是“多查一点日志”，而是把一个具体线上请求从入口到出口完整还原。**</span>

#### 2. 查看状态码分布

```spl
index=nav_menu event_type=access
| stats count by status
| sort - count
```

如果 `500` 数量突然升高，再拿 `500` 继续缩小：

```spl
index=nav_menu event_type=access status>=500
| table _time traceId method path status durationMs
| sort - _time
```

#### 3. 计算各接口延迟

```spl
index=nav_menu event_type=access
| stats avg(durationMs) as avgMs,
        perc95(durationMs) as p95Ms,
        max(durationMs) as maxMs
        by path
| sort - p95Ms
```

为什么同时看三个值：

- `avgMs`：整体平均情况
- `p95Ms`：95% 的请求不超过这个耗时
- `maxMs`：最慢的一次有多慢

#### 4. 看每分钟请求量

```spl
index=nav_menu event_type=access
| timechart span=1m count
```

执行后切到 `Visualization`，选择折线图或柱状图，就能看到流量随时间的变化。

#### 5. 找最慢的请求

```spl
index=nav_menu event_type=access durationMs>200
| table _time traceId method path status durationMs
| sort - durationMs
| head 20
```

拿到慢请求的 traceId 后，再执行第 1 条查询还原该请求。

#### 6. 查看缓存结果分布

```spl
index=nav_menu event_type=cache
| stats count by result
```

常见结果包括 `hit`、`miss`、`set`。如果连续请求仍是大量 `miss`，应检查缓存 key、TTL 和 Redis 连接。

#### 7. 查看错误日志

```spl
index=nav_menu level=ERROR
| table _time traceId logger message
| sort - _time
```

不要把 `500` 和 `ERROR` 当成同一个条件：

- `status>=500` 是 HTTP 响应状态
- `level=ERROR` 是应用日志级别

两者通常相关，但统计口径不同。

本项目真实例子：`traceId=4f636a264ddd4ad98b627f8108d593a7`

| 字段 | 内容 |
|:---|:---|
| 业务时间 | `2026-10-20 18:13:12.420Z` |
| `level=ERROR` 时间 | `18:13:15.438Z` |
| access 状态 | `status=500` |
| access 耗时 | `3028ms` |
| 异常 | `RedisCommandTimeoutException: Command timed out after 3 second(s)` |
| 抛出位置 | `MenuTreeCacheService.get(MenuTreeCacheService.java:24)` |

一次请求因此产生了两条不同用途的日志：

1. `GlobalExceptionHandler` 的 `level=ERROR`，带完整 `stackTrace`，回答“为什么失败”
2. `AccessLogFilter` 的 `event_type=access status=500`，回答“最终返回什么状态、耗时多少”

用同一个 traceId 可以把两条日志串起来：

```spl
index=nav_menu traceId=4f636a264ddd4ad98b627f8108d593a7
| sort @timestamp
| table _time level event_type status durationMs message logger stackTrace
```

在本项目中，`log.error()` 只出现在 `GlobalExceptionHandler` 的未预期系统异常兜底分支。Redis 超时、数据库连接失败、未捕获的 `NullPointerException` 等会进入这里；参数校验失败、JSON 格式错误和业务规则拒绝属于 4xx，代码使用 `log.warn()`，不会产生 `level=ERROR`。

完整排查过程见 [可观测性-故障排查实战-Redis超时](./可观测性-故障排查实战-Redis超时.md)。

### 常用 SPL 命令

| 命令 | 作用 | 例子 |
|:---|:---|:---|
| `table` | 只展示指定字段 | `table _time traceId status` |
| `fields` | 保留或排除字段 | `fields - _raw` |
| `sort` | 排序 | `sort - durationMs` |
| `head` | 只取前 N 条 | `head 20` |
| `dedup` | 按字段去重 | `dedup traceId` |
| `stats` | 分组聚合 | `stats count by status` |
| `timechart` | 按时间聚合 | `timechart span=1m count` |
| `top` | 统计最高频值 | `top path` |
| `where` | 对数值或计算结果过滤 | `where durationMs > 200` |
| `eval` | 创建计算字段 | `eval seconds=durationMs/1000` |

### 字段怎么发现

不要猜字段名。找到一条事件后：

1. 展开事件详情
2. 查看左侧 `Interesting fields`
3. 点击某个字段，查看它有哪些值
4. 把确认存在的字段写进 SPL

也可以查询字段分布：

```spl
index=nav_menu
| head 100
| fieldsummary
```

### 时区怎么看

Splunk 内部时间以时间戳保存，页面展示受用户时区影响。

- `docker exec ... splunk search` 的命令行结果可能显示 GMT
- Splunk Web 会按用户或浏览器时区展示
- 不要把命令行显示的 GMT 直接当成日志有问题

需要输出可读时间时，可以创建字段：

```spl
index=nav_menu
| head 10
| eval readableTime=strftime(_time, "%Y-%m-%d %H:%M:%S")
| table readableTime traceId event_type message
```

### 搜不到数据的排查顺序

按下面顺序检查，不要一开始就怀疑 Fluent Bit：

1. **时间范围**：先选 `All time`
2. **index 拼写**：确认是 `nav_menu`
3. **是否进入索引**：先用最宽的 `index=nav_menu` 搜
4. **字段是否存在**：点击一条事件看左侧字段
5. **条件是否过严**：去掉 `status=500`、`durationMs>200` 等过滤
6. **采集链路是否正常**：查看 Fluent Bit 输出错误和重试
7. **HEC 是否接收**：查看 HEC 健康状态和返回码

如果出现下面这种组合：

```text
app.log 里能找到 traceId
Splunk 里搜同一个 traceId 是 0 条
Fluent Bit 输出错误和重试都是 0
Splunk 最新 _time 明显落后于 app.log
```

说明问题通常不在 SPL、HEC Token 或网络，而是采集器没有继续读取新文件。Windows Docker 绑定目录下，日志文件被重建或轮转后，inotify 可能停留在旧 inode。本项目通过：

```ini
Inotify_Watcher Off
```

切换到文件状态轮询，重启 Fluent Bit 后即可重新读取当前 `app.log`。

一条完整的判断链：

```java
应用有日志？
   ├─ 无：查 logback 和 logs/app.log
   └─ 有：Fluent Bit 是否读取？
          ├─ 无：查挂载路径、tail DB、文件权限
          └─ 有：HEC 是否返回 code=0？
                 ├─ 无：查 Token、TLS、网络
                 └─ 有：Splunk 是否能按 index 搜到？
                        ├─ 无：查时间范围、索引、字段
                        └─ 有：链路正常
```

---

## 🔥 面试要点

**Q1：你在 Splunk 里怎么排查一个具体用户的问题？**

> **面试话术**：*"先从访问日志按时间窗和接口缩小范围，拿到 traceId，再用 traceId 拉出这次请求的全部结构化日志，按时间排序。这样能看到 Controller、责任链、缓存、数据库和最终响应状态，判断问题发生在哪一层。因为日志字段固定，还能同时按分行、接口和 event_type 聚合，不靠人眼逐条翻。"*

**Q2：你怎么用日志回答错误率和慢请求问题？**

> **面试话术**：*"访问日志里有 status 和 durationMs。错误率可以用 stats 统计 status 分布；延迟用 stats 计算 avg、perc95 和 max；趋势用 timechart 按分钟聚合。发现异常接口或慢请求后，再拿对应 traceId 回到完整链路日志定位原因。"*

**Q3：SPL 和 SQL 有什么区别？**

> **面试话术**：*"两者都能聚合，但 SPL 的出发点是事件流。先用 index、时间和字段筛选事件，再通过管道逐步 table、where、stats、timechart。SQL 更强调表和关系，SPL 更强调从原始事件中逐层提炼答案。"*

---

## 🧠 记忆口诀

```text
搜索三步：
  先圈数据  —— index + 时间窗
  再选事件  —— 字段过滤
  最后变答案 —— stats / timechart / table

排查四步：
  看现象 → 找 traceId → 排时间线 → 下结论
```

---

## 🔄 知识闭环

```java
问题：用户说一次请求失败/变慢
      │
      ▼
先用 index + 时间窗缩小范围
      │
      ▼
从 access 日志找到异常请求和 traceId
      │
      ▼
用 traceId 拉出完整调用链并按时间排序
      │
      ▼
定位到 Controller / 责任链 / 缓存 / Mapper / 响应
      │
      ▼
用 stats / timechart 判断是个例还是整体趋势
      │
      ▼
结论：发生了什么、影响多大、下一步查哪里
```
