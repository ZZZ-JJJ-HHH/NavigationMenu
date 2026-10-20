# 可观测性：日志采集与 Splunk

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [可观测性的数据链路](#可观测性的数据链路)
  - [采集方案对比](#采集方案对比)
- [📊 应用与对比](#应用与对比)
  - [Splunk 检索示例](#splunk-检索示例)
  - [本地搭一套能演示的链路](#本地搭一套能演示的链路)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**日志写在本地文件里，别人是搜不到的；要能被检索，中间必须有一条采集链路，把日志送进索引端。**</span>

当前项目已经跑通的完整链路：

```java
应用写 logs/app.log
      │
      ▼
Fluent Bit 3.1.10 读取并解析 JSON
      │  增加 env=local / collector=fluent-bit
      ▼
HTTPS HEC :8088
      │  Authorization: Splunk <token>
      ▼
Splunk nav_menu 索引
      │
      ▼
Search & Reporting 中按 traceId / event_type / status 检索
```

页面操作、SPL 语法和排查查询见 [可观测性-Splunk检索入门](./可观测性-Splunk检索入门.md)。

---

## 🎯 背景与问题

### "我加了日志"不等于"我能查线上"

JSON 日志写好、文件也落了盘，但打开浏览器什么都搜不到，因为文件在容器自己的文件系统里，没有第二个人知道它存在。

### 核心痛点

| 痛点 | 表现 |
|:---|:---|
| 日志躺在容器里 | 容器一删就没了，也没法跨容器查 |
| 没法集中检索 | 要看三个服务的日志得开三个终端 |
| 没法聚合 | QPS、错误率、P95 这类数字靠人眼看不出来 |
| 没有历史 | 线上出问题时，需要的是几天前的日志 |

> ⚠️ **最常见的认知断层在这里**：很多人以为"我加了日志"就等于"我能查线上"。其实日志只是整条链路的第 1 段。中间任何一段断了，你在页面上什么都搜不到。

---

## 💡 核心目的

让日志从"写在某个文件里"变成"能在浏览器里按字段查、能画成图、能挂告警"。

---

## ⚙️ 原理与机制

### 可观测性的数据链路

一个请求从产生到你能在页面上搜到它，中间要走五段：

```java
POST /api/menu/tree
      │
      ▼
┌──────────────┐      应用进程内部
│ 应用打日志    │      logback + traceId 注入
└──────┬───────┘
       │  写到 stdout（容器标准输出）/ 日志文件
       ▼
┌──────────────┐      采集侧
│ 采集转发      │      Fluent Bit / Universal Forwarder
└──────┬───────┘
       │  按行打包，打给索引端（HEC / 转发端口）
       ▼
┌──────────────┐      存储与索引
│ 索引端        │      解析字段，建倒排索引，保留 N 天
└──────┬───────┘
       │
       ▼
┌──────────────┐      使用侧
│ 检索 / 看板 / 告警 │  SPL 查询、仪表盘、阈值告警
└──────────────┘
```

### 采集方案对比

| 方案 | 形态 | 优点 | 代价 |
|:---|:---|:---|:---|
| **应用直推** | 应用内 SDK 直接打给索引端 | 无需额外组件 | 与业务进程耦合，网络抖动会影响应用 |
| **Docker 日志驱动** | 引擎层把 stdout 转给日志后端 | 零代码改动 | 依赖容器运行时，Windows / macOS 上受限 |
| **采集器读文件** | Fluent Bit / Universal Forwarder 读日志文件 | 最稳、最像传统企业形态、可本地缓冲 | 多一份组件和配置 |
| **旁路采集 stdout** | 采集器读容器日志目录 | 不用改应用写文件 | 依赖 Docker 数据目录的路径 |

> 🔑 **传统企业和银行的经典形态是"采集器读文件"**：应用只管往本地文件写，采集器负责传输，两边解耦。应用挂了不影响传输，网络断了采集器本地缓冲，恢复后续传。

> ⚠️ **应用直推为什么不推荐**：把传输逻辑塞进业务进程，网络一抖动就影响接口响应。日志是辅助信息，不该有能力拖垮主流程。

---

## 📊 应用与对比

### Splunk 检索示例

检索平台换成别的也一样，关键是**你会不会用查询回答业务问题**：

```
# 1. 每分钟请求量（QPS）
index=nav_menu event_type=access
| timechart span=1m count as QPS

# 2. 各接口错误率
index=nav_menu event_type=access
| stats count as total, count(eval(status>=500)) as err by path
| eval errRate = round(err/total*100, 2)

# 3. 各接口 P95 / P99 延迟
index=nav_menu event_type=access
| stats perc95(durationMs) as p95, perc99(durationMs) as p99 by path

# 4. 用 traceId 拉出一次请求的全链路（排查的标准动作）
index=nav_menu traceId="7f3a9c21e5b04d8a"
| sort @timestamp

# 5. 缓存命中率
index=nav_menu event_type=cache
| stats count(eval(result="hit")) as hit, count as total
| eval hitRate = round(hit/total*100, 2)

# 6. 慢请求 Top 20
index=nav_menu event_type=access durationMs>500
| top 20 path, branchCode
```

> 🔑 <span style="color:red">**第 4 条是面试最能加分的一条。它回答的是"你怎么排查一个具体用户的线上问题"：让用户报时间点，或者从错误信息里拿到 traceId，然后一条查询把这次请求的每一步拉出来。**</span>

字段名和 `event_type` 的取值从哪来，见 [可观测性-结构化日志](./可观测性-结构化日志.md)。

### 本地搭一套已跑通的 Splunk

本项目使用 Splunk Enterprise 试用版，实测版本为 `10.6.1.1`。Splunk Web 监听 `8000`，HEC 监听 `8088`，两者都只绑定宿主机 `127.0.0.1`，不直接暴露到局域网。

#### HEC 与端口分工

HEC 全称 **HTTP Event Collector**，是 Splunk 提供的 HTTP/HTTPS 日志投递入口。采集器不需要安装 Splunk Universal Forwarder，只要把 JSON 日志 POST 到 HEC 端点并携带 Token，就能把事件写入 Splunk 索引：

```text
logs/app.log
    │
    ▼
Fluent Bit（读取、解析 JSON）
    │  HTTPS POST + HEC Token
    ▼
Splunk HEC :8088/services/collector/event
    │
    ▼
nav_menu 索引 → SPL 检索
```

<span style="color:red">**HEC 是给采集器写日志的 API，不是一个可以在浏览器里正常打开的网页。**</span> 因此直接访问 `https://localhost:8088` 看不到管理页面，返回 404 或错误响应都正常；人看的界面在 Splunk Web 的 `8000` 端口。

| 端口 | 职责 | 谁访问 |
|:---|:---|:---|
| `8000` | Splunk Web 管理界面 | 人通过浏览器访问 |
| `8089` | Splunk REST API 和管理接口 | 管理工具、自动化脚本 |
| `8088` | HEC，接收日志事件 | Fluent Bit、应用或其他采集器 |

8088 是 HEC 的默认端口，不是协议本身。本项目的 HEC 启用了 HTTPS，所以 Fluent Bit 必须使用 `https://splunk-demo:8088`，并且携带 `SPLUNK_HEC_TOKEN`；把地址写成 `http://` 或漏掉 Token 都会被拒绝。

敏感配置放在 `ops/splunk/secrets/splunk.env`，该目录已加入 `.gitignore`：

```text
SPLUNK_PASSWORD=<本机管理密码>
SPLUNK_HEC_TOKEN=<本机 HEC Token>
SPLUNK_START_ARGS=--accept-license
SPLUNK_GENERAL_TERMS=--accept-sgt-current-at-splunk-com
```

启动命令：

```powershell
docker network create nav-observability

docker run -d --name splunk-demo `
  --network nav-observability `
  --hostname splunk-demo `
  --restart unless-stopped `
  -p 127.0.0.1:8000:8000 `
  -p 127.0.0.1:8088:8088 `
  --env-file ops/splunk/secrets/splunk.env `
  -v splunk-data:/opt/splunk/var `
  splunk/splunk:latest
```

首次启动要完成镜像下载和 Ansible 初始化。容器进入 `healthy` 后，创建业务索引：

```powershell
$password=(Get-Content ops/splunk/secrets/splunk.env |
  Where-Object { $_ -like 'SPLUNK_PASSWORD=*' }) -replace '^SPLUNK_PASSWORD=',''

docker exec -u splunk splunk-demo `
  /opt/splunk/bin/splunk add index nav_menu `
  -auth "admin:$password"
```

> ⚠️ **HEC 默认走 HTTPS，不是 HTTP**。本项目的健康检查地址是 `https://localhost:8088/services/collector/health/1.0`，本地自签名证书需要 `curl -k`。如果直接把 Fluent Bit 地址写成 `http://splunk:8088`，连接会失败。

HEC 写入测试：

```powershell
$token=(Get-Content ops/splunk/secrets/splunk.env |
  Where-Object { $_ -like 'SPLUNK_HEC_TOKEN=*' }) -replace '^SPLUNK_HEC_TOKEN=',''

$body='{"event":{"message":"splunk channel smoke test","event_type":"smoke","traceId":"splunk-smoke-001"},"sourcetype":"_json","index":"nav_menu"}'

curl.exe -k -s `
  -H "Authorization: Splunk $token" `
  -H "Content-Type: application/json" `
  -d $body `
  https://localhost:8088/services/collector/event
```

返回 `{"text":"Success","code":0}` 只代表 HEC 接收成功，还要用搜索确认真实入索引：

```powershell
docker exec -u splunk splunk-demo `
  /opt/splunk/bin/splunk search 'index=nav_menu traceId=splunk-smoke-001' `
  -auth "admin:$password"
```

本次实测结果：`nav_menu` 索引创建成功，`totalEventCount=1`，SPL 搜到了测试 JSON。Splunk Web 地址是 `http://localhost:8000`，登录用户为 `admin`，密码读取本地 `splunk.env`。

**实测要点**：

| 事项 | 结果 |
|:---|:---|
| Splunk 版本 | `10.6.1.1` |
| Web | `http://localhost:8000` |
| HEC | `https://localhost:8088` |
| 索引 | `nav_menu` |
| 首次初始化 | 约几分钟，健康状态为 `healthy` 后才能操作 |
| HEC 写入 | 返回 `code:0` |
| 搜索验证 | 能按 `traceId` 搜到测试事件 |
| 后续采集 | Fluent Bit 已通过 `nav-observability` 网络访问 `splunk-demo:8088` |

### Fluent Bit 采集与转发

Fluent Bit 读取宿主机 `logs/app.log`，解析单行 JSON，增加 `env=local` 和 `collector=fluent-bit`，再通过 HTTPS HEC 写入 `nav_menu`。

配置位置：

| 文件 | 作用 |
|:---|:---|
| `ops/fluent-bit/fluent-bit.conf` | 输入、过滤、Splunk 输出 |
| `ops/fluent-bit/parsers.conf` | 解析 JSON 与 `@timestamp` |
| `ops/fluent-bit/secrets/splunk.env` | 只保存 HEC Token，已被 Git 忽略 |

核心配置：

```ini
[INPUT]
    Name            tail
    Path            /app-logs/app.log
    Parser          navigation-menu-json
    Tag             navigation-menu.log
    Read_from_Head  Off
    DB              /var/lib/fluent-bit/tail.db
    Inotify_Watcher Off

[FILTER]
    Name    modify
    Match   navigation-menu.log
    Rename  eventType event_type
    Add     env local
    Add     collector fluent-bit

[OUTPUT]
    Name              splunk
    Match             navigation-menu.log
    Host              splunk-demo
    Port              8088
    TLS               On
    TLS.Verify        Off
    Splunk_Token      ${SPLUNK_HEC_TOKEN}
    Event_Index       nav_menu
    Event_Sourcetype  _json
```

`Read_from_Head Off` 表示第一次启动从文件末尾开始，只采新日志；`DB` 文件保存读取位置，容器重启后不会从头重复采集。

> ⚠️ **Splunk 有内置字段 `eventtype`，字段名不区分大小写**。JSON 里的 `eventType` 会被自动改名成 `extracted_eventType`，导致 `eventType=access` 搜不到。所以 Fluent Bit 在发送前统一改名为 `event_type`，检索时固定使用 `event_type=access`。

> ⚠️ **Windows 绑定目录建议关闭 inotify**。日志框架重建或轮转 `app.log` 后，inotify 可能继续监听旧 inode，表现为"本地文件明明有新日志，Splunk 却停在旧时间点"。`Inotify_Watcher Off` 改用文件状态轮询，更适合当前 Docker Desktop + Windows 目录挂载的演示环境。

> ⚠️ **Splunk 不是 `app.log` 的实时镜像**。Fluent Bit 读到一条日志后，Splunk 会保存自己的索引副本；之后再修改或删除 `app.log` 中的历史行，不会回写或删除已索引事件。要验证新日志，请追加唯一 traceId 并使用新时间窗，或者创建独立测试索引。

启动命令：

```powershell
docker run -d --name fluent-bit-demo `
  --network nav-observability `
  --restart unless-stopped `
  -p 127.0.0.1:2020:2020 `
  --env-file ops/fluent-bit/secrets/splunk.env `
  -v "C:/Users/30560/Desktop/my_project/NavigationMenu/logs:/app-logs:ro" `
  -v "C:/Users/30560/Desktop/my_project/NavigationMenu/ops/fluent-bit/fluent-bit.conf:/fluent-bit/etc/fluent-bit.conf:ro" `
  -v "C:/Users/30560/Desktop/my_project/NavigationMenu/ops/fluent-bit/parsers.conf:/fluent-bit/etc/parsers.conf:ro" `
  -v fluent-bit-state:/var/lib/fluent-bit `
  docker.m.daocloud.io/fluent/fluent-bit:3.1
```

> ⚠️ **版本差异会直接导致启动失败**：本次使用 Fluent Bit `3.1.10`，它的 Splunk 输出插件不支持 `Time_Key` / `Time_Key_Format`。时间字段必须放在 JSON Parser 里解析；新版 Fluent Bit 才支持把这两个参数放到输出插件。

时间字段的完整转换关系：

```java
日志 JSON 的 @timestamp
        → Fluent Bit Parser 解析
        → Fluent Bit 事件时间
        → HEC 事件信封 time
        → Splunk 内置 _time
```

`@timestamp` 仍保留在事件正文中，`_time` 是 Splunk 用于时间窗、排序和 `timechart` 的内置元数据。二者正常情况下表示同一时刻。

**本次链路实测**：

1. 发送一次 `POST /api/menu/tree`，请求头带 `X-Trace-Id: fluentbit-smoke-001`
2. Fluent Bit 读取这次请求产生的全部 JSON 日志
3. Splunk 按同一个 traceId 搜到 17 条事件，包括 MyBatis、责任链、缓存和访问日志
4. Splunk 自动识别 `status=200`、`durationMs=134`、`path=/api/menu/tree`
5. 事件 `_time` 来自日志里的 `@timestamp`，不是采集器收到日志的时间
6. `fluentbit_output_proc_records_total=19`，`fluentbit_output_errors_total=0`，`fluentbit_output_retries_total=0`

检索验证：

```spl
index=nav_menu traceId=fluentbit-smoke-001
| table _time event_type status durationMs path collector
| sort _time
```

**Fluent Bit 为什么能读到文件**：

```text
宿主机 C:/.../NavigationMenu/logs
        │  docker run -v ".../logs:/app-logs:ro"
        ▼
容器内 /app-logs
        │  Path /app-logs/app.log
        ▼
Fluent Bit tail input 监听 app.log
```

`-v` 不是复制文件，而是把宿主机目录挂载到容器路径。应用追加日志后，Fluent Bit 通过文件监听能力读到新增行；`DB=/var/lib/fluent-bit/tail.db` 记录读取位置，避免重启后重复采集。

**Fluent Bit 为什么能找到 `splunk-demo`**：

Splunk 和 Fluent Bit 都加入同一个 Docker 自定义网络 `nav-observability`。Docker 内置 DNS 会把服务名 `splunk-demo` 解析成容器 IP，本项目实测为 `172.19.0.2`，所以输出配置可以直接写：

```ini
Host splunk-demo
Port 8088
TLS  On
```

**HEC Token 的来源与存放位置**：

```text
ops/splunk/secrets/splunk.env
        │  SPLUNK_HEC_TOKEN
        ▼
Splunk 首次启动时由 splunk-ansible 创建/更新 HEC Token
        │
        ├─ 保存到 Splunk 配置：
        │  /opt/splunk/etc/apps/splunk_httpinput/local/inputs.conf
        │
        └─ 同一个 Token 复制到：
           ops/fluent-bit/secrets/splunk.env
                   │  通过 --env-file 注入 Fluent Bit
                   ▼
           Splunk_Token ${SPLUNK_HEC_TOKEN}
                   │
                   ▼
           HTTP Header: Authorization: Splunk <token>
```

> ⚠️ **HEC Token 是明文凭据，不是哈希值**。Splunk 需要对请求携带的 Token 做比对，所以不能像用户密码一样只存哈希。本项目通过 `.gitignore` 避免进入 Git，但 `docker inspect`、配置文件读取权限和 Docker socket 访问权限仍需控制。生产环境应使用密钥管理系统并定期轮换。

日志文件在容器里的落点和卷的关系，见 [可观测性-Docker容器与日志落盘](./可观测性-Docker容器与日志落盘.md)。

---

## 🔥 面试要点

**Q1：你们的日志架构是什么样的？**

> **面试话术**：*"应用侧打成结构化 JSON，同时输出到标准输出和本地文件；采集侧用采集器读文件，转发到集中日志平台；平台上按服务建索引，按固定字段和 event_type 做检索、看板和告警。选读文件而不是应用直推，是为了让应用和传输解耦，网络抖动不会影响接口，采集器还能在本地做缓冲。"*

**Q2：日志平台里你一般怎么排查问题？**

> **面试话术**：*"先按时间范围和接口缩小到具体请求，拿到 traceId，然后用这个 ID 把这次请求的全部日志按时间排出来。因为字段是结构化的，我还能同时看几个维度，比如这个分行的 P99 是不是整体偏高、是不是集中在某一步。定位到哪一层之后，再去看代码和那一层的指标。"*

**Q3：为什么不用 ELK 或者别的？**

> **面试话术**：*"工具本身是次要的，能按字段检索、能建看板告警就行。真正决定好不好用的，是字段设计和检索能力，也就是你的日志里有没有稳定、可聚合的字段。平台之间的差别主要在于查询语言、索引成本、以及和现有运维体系的集成。"*

---

## 🧠 记忆口诀

```
采集链路四段：应用、采集、索引、使用

  应用 —— 结构化 JSON，落到 stdout 和文件
  采集 —— 采集器读文件，本地缓冲，转发给索引端
  索引 —— 解析字段，建倒排，定保留周期
  使用 —— 检索、看板、告警

再记一句判断：
  日志只是第 1 段，中间断一段，页面上什么都搜不到
```

---

## 🔄 知识闭环

```java
问题：日志落在容器文件系统里，别人搜不到
      │
      ▼
链路：应用 → 采集 → 索引 → 检索 / 看板 / 告警
      │
      ▼
选型：采"采集器读文件"而不是应用直推
  理由：解耦，应用不被网络抖动拖累，采集器可缓冲
      │
      ▼
使用：字段设计决定能查什么，traceId 决定能查多细
      │
      ▼
出口：一条 traceId 查询还原一次请求，
      一组 stats 查询回答流量、错误率、延迟、命中率。
```
