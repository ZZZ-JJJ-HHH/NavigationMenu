# 可观测性：链路追踪与 MDC

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [MDC 是什么](#mdc-是什么)
  - [什么样的值才配当 traceId](#什么样的值才配当-traceid)
  - [谁来生成 traceId](#谁来生成-traceid)
  - [traceId 的完整流转](#traceid-的完整流转)
- [📊 应用与对比](#应用与对比)
  - [落成代码](#落成代码)
  - [四个必须提前处理的坑](#四个必须提前处理的坑)
  - [traceparent 里装了什么](#traceparent-里装了什么)
  - [请求头用哪个名字](#请求头用哪个名字)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)
- [✅ 本步小结](#本步小结)

---

## 📌 一句话核心

> <span style="color:red">**traceId 让一次请求的所有日志能用同一个 ID 串起来，MDC 是承载它的容器。**</span>

---

## 🎯 背景与问题

### 没有 traceId 时怎么排查

用户报"我的菜单不对"。你在日志里搜用户 ID，搜出来几十条，跨了好几个请求；再搜菜单接口，几十个用户混在一起。你没法确定"这一次"到底发生了什么。

根本原因：**日志是按条存的，没有"这次请求"这个概念。**

### 核心痛点

| 痛点 | 表现 |
|:---|:---|
| 请求之间无法区分 | 同一个用户的多次请求混在一起 |
| 跨服务断链 | 前端、网关、后端、下游的日志各说各话 |
| 异步分支丢失 | 线程池里的日志和主流程对不上 |

---

## 💡 核心目的

给每一次请求发一个唯一 ID，让它**从进门到出门一直存在**，并且：

1. 出现在这个请求打的每一行日志里
2. 能被上游透传、向下游传递
3. 能直接回写给调用方，方便报障时对上号

---

## ⚙️ 原理与机制

### MDC 是什么

MDC（Mapped Diagnostic Context，映射诊断上下文）是 SLF4J 提供的一个 API，底层就是绑在**当前线程**上的 `Map<String, String>`。往里面塞一次键值对，之后这个线程打的每一行日志，日志框架都会自动把这个值渲染进去，业务代码不用在每条日志里手动传。

> 🔑 **它是给日志框架用的"隐式参数袋"**。为什么值得这么做，看两条路的对比：

| 做法 | 代价 |
|:---|:---|
| 直接改日志语句：`log.info("cache hit, traceId={}, key={}", traceId, key)` | 项目里几十条 log 全要改，traceId 还得沿方法签名一层层往下传，业务代码被污染 |
| 用 MDC：入口放一次，pattern 里写一次 | 业务代码零改动，之后所有日志自动带上 |

> ⚠️ **代价是隐式状态**。MDC 靠 ThreadLocal，只对当前线程有效。这既是它好用的原因，也是它所有坑的来源。

### 什么样的值才配当 traceId

一个很自然的偷懒是拿现成的东西当 traceId。下面四个都"看起来能凑合"，四个都不行：

| 想偷懒的写法 | 为什么不行 | 后果 |
|:---|:---|:---|
| `userId` | 它标识"一个人"，不是"一次请求" | 一个人一下午点 50 次菜单，50 次请求被串成一条 |
| 时间戳 | 毫秒级也会撞；它标识的是"一个时刻" | 高并发下同一毫秒内有多个请求，分不开 |
| 线程名 | Tomcat 线程池只有固定几个线程，成千上万个请求在复用 | 标识的是"干活的工人"，不是"这次的活儿" |
| `sessionId` | 它标识"一次会话"，一次会话里有几百个请求 | 粒度太粗，还是定位不到那一次 |

> 🔑 **判据只有一条：这个值是不是"一次请求一个"？** 不是，就不能当 traceId。所以它必须由请求入口生成，不能借用任何已有的业务概念。

### 谁来生成 traceId

规则不是"前端生成"，而是"**最外层、最先接到这次请求的那一跳生成**"。前端只是可能的某一跳，而且是最不牢靠的一跳。

> 🔑 **先说一句公道的**：在 W3C 标准里，`traceparent` 本来就是由"发起这次追踪的客户端"设置的。所以前端生成 traceId 在标准上完全合法，很多前后端同团队的 App 就是这么干的。问题不在于"能不能"，而在于"能不能指望它"。

| 请求从哪来 | 谁来生成 | 说明 |
|:---|:---|:---|
| App / 自研前端 SDK | 前端生成 | 常见做法，前提是前端能被统一改到（加一个统一拦截器） |
| 浏览器页面直接调接口 | 前端或网关都行 | 前端加个 axios 拦截器就能生成；但老页面、多团队维护的前端往往改不动，那就退到网关生成 |
| 网关 / BFF / 负载均衡 | 最稳的那一跳 | 所有流量都从这里进，生成点唯一，前端改不改都不影响 |
| 定时任务 / MQ 消费 | 任务触发方或消费端 | 这条路上压根没有前端 |
| 第三方系统调用 / 运维直接 curl | 后端自己生成 | 上游不会给，只能兜底 |

> ⚠️ **为什么不能把生成权交给前端**：调用方不止前端。批处理、消息消费、第三方系统、运维排障时手敲的 curl，全都没有前端这一跳。所以后端必须有自己的兜底生成逻辑 —— **上游给了就复用，没给就自己造**，这才是"入口生成"这四个字的完整意思。

> ⚠️ **前端真生成的话，要额外处理两件事**：一是跨域场景下自定义请求头要在 CORS 的 `allow-headers` 里放行，否则浏览器直接把请求拦掉；二是用户点一次按钮触发多个接口时，要让这几个请求共用同一个 traceId，否则"一次用户操作"在日志里又散成了好几条互不相干的链路。

落到本项目：`TraceIdFilter` 的三个来源优先级，就是这条规则的代码形态 —— `traceparent` → `X-Trace-Id` → 自己生成 UUID。最后那层兜底存在的唯一理由，就是**不能假设上游一定会给**。

### traceId 的完整流转

```java
上游请求头：traceparent / X-Trace-Id
        │
        ▼
┌────────────────┐   入口 Filter：有就复用，没有就生成
│ TraceIdFilter  │   放进 MDC，同时回写响应头
└───────┬────────┘
        │
        ▼
┌────────────────┐   业务代码只管 log.info()
│ MDC/ThreadLocal│   pattern / JSON 里用 %X{traceId} 带出来
└───────┬────────┘
        │
        ▼
┌────────────────┐   响应返回前 finally 清理，防止线程复用串号
│  clear MDC     │
└────────────────┘
```

---

## 📊 应用与对比

### 落成代码

三步：

```java
// 1. 入口放进去
MDC.put("traceId", traceId);

// 2. logback 的 pattern 里取出来（:-- 是"没有值时显示 -"的默认值）
//    %d{HH:mm:ss.SSS} [%thread] [%X{traceId:--}] %-5level %logger{36} - %msg%n

// 3. 请求结束清掉
MDC.remove("traceId");
```

放进本项目的 `TraceIdFilter`（`src/main/java/com/example/navigation_menu/filter/TraceIdFilter.java`）：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)          // 排在最前面，后面的日志才带得上 traceId
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTraceId(req);
        MDC.put(MDC_KEY, traceId);
        resp.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest req) {
        String fromTraceparent = fromTraceparent(req.getHeader("traceparent"));
        if (fromTraceparent != null) {
            return fromTraceparent;
        }
        String fromHeader = req.getHeader(HEADER_TRACE_ID);
        if (fromHeader != null && SAFE_TRACE_ID.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
```

**两个容易漏掉的细节**，都在上面这段代码里：

> ⚠️ **请求头是外部输入，不能原样写进日志**。`X-Trace-Id` 是调用方给的，直接 `MDC.put` 等于把日志内容交给外部控制 —— 塞个换行符就能伪造日志行，塞超长字符串就能撑大日志体积。所以要过白名单（只放行字母数字和短横线、限长），不合法就丢弃、改用自己生成的。拒绝时**只记长度、不记原值**，因为原值正是要拦下来的东西。

> ⚠️ **`traceparent` 要按规范解析，不能整个拿来用**。W3C 的格式是 `00-<32 位 trace-id>-<16 位 parent-id>-<01 flags>`，你要的是**第二段**；而第二段必须是 32 位小写十六进制，全 0 按规范视为无效。整个 header 当 traceId 用的话，日志里会带上一串带父节点信息的东西，跨系统对不上。

控制台的 pattern 里带了默认值 `:--`，这样启动阶段（还没有请求）的日志不会出现一对空括号：

```java
%d{HH:mm:ss.SSS} [%thread] [%X{traceId:--}] %-5level %logger{36} - %msg%n
//                             ↑ 没有 traceId 时显示 "-"
```

### 四个必须提前处理的坑

| 坑 | 后果 | 做法 |
|:---|:---|:---|
| 没在 `finally` 里清 MDC | Tomcat 线程复用，下一个请求带着上一个请求的 traceId | `try { ... } finally { MDC.clear(); }` |
| 异步 / 线程池 | `@Async`、`CompletableFuture` 里 MDC 丢失，日志整段没有 traceId | 配 `TaskDecorator` 把 MDC 复制给子线程，或用可继承的 ThreadLocal |
| 跨服务调用 | 下游日志和上游对不上，跨系统排查断链 | 出站时把 traceId 塞进请求头，下游 Filter 复用它而不是重新生成 |
| 响应式栈（WebFlux / Reactor） | 一个请求跨多个线程，MDC 里取不到值，日志整段没有 traceId | MDC 不适用于响应式，改用 Reactor Context 传递 |

> 🔑 **第一条是最容易踩的**。Tomcat 处理请求的线程从线程池复用，不清理的话，请求 A 的 traceId 会残留在线程上，请求 B 的日志就挂着 A 的 ID。排查时会出现"按这个 traceId 搜出来的是别人的请求"。

### traceparent 里装了什么

一个很自然的疑问：自定义一个 `X-Trace-Id` 就够了，上游给就复用、不给就自己生成，为什么还要多一套标准头？

> 🔑 **因为 `X-Trace-Id` 只解决"串起来"，不解决"谁调了谁"和"要不要采"。** 单服务内部用一个 ID 串日志完全够用；一旦跨服务、跨团队、跨厂商，缺的那两样东西就变成硬伤。

**把标准头拆开看**。W3C 的 `traceparent` 不是"一个 ID"，是四段信息拼起来的：

```
00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
│  │                                │                │
│  │                                │                └─ flags：采样标志（bit0=1 表示"这条链路要采"）
│  │                                └────────────────── parent-id：上一跳的 span id，调用树靠它拼
│  └─────────────────────────────────────────────────── trace-id：整条链路的身份，32 位小写十六进制
└────────────────────────────────────────────────────── version：格式版本号，留了演进空间
```

| 仅靠 `X-Trace-Id` 能做到 | 仅靠 `X-Trace-Id` 做不到 |
|:---|:---|
| 把同一次请求的日志拉到一起 | 还原调用树：A 调了 B、B 又调了 C，时间花在哪一段 |
| 报障时对上号 | 采样一致性：各服务各自按 10% 采，一个请求在这段被采、那段被丢，链路就断了 |
| 单服务排查 | 跨厂商互通：各家自定义头互不认识，N 家系统要写 N×N 个适配 |

> ⚠️ **采样标志必须由发起方定，不能各服务自己算**。如果每个服务独立按概率采样，同一次请求在不同服务上的采样决策会不一致，链路必然断成几截。所以正确做法是：**入口做一次决策，写进 flags，下游只看 flags 不再自己抛硬币。**

> 🔑 **标准头的真正价值是"免适配"**。用自定义头，两个系统对接就得写一层转换；用 W3C 标准头，任何遵守规范的组件（OpenTelemetry 采集器、Istio/Envoy 这类 mesh、云厂商的网关和负载均衡）接上就能用，零胶水代码。

**那本项目为什么还要认 `X-Trace-Id`？** 因为上游不止一种类型：

| 上游是谁 | 会带哪个头 | 为什么 |
|:---|:---|:---|
| 接了 OpenTelemetry 的新网关 / 云厂商网关 / Service Mesh | `traceparent` | 它们默认按标准注入 |
| 老网关、老前端、内部自定义的调用方 | `X-Trace-Id` | 企业里这类存量系统很多，标准推不动 |
| 定时任务、MQ 消费、第三方调用、运维手敲的 curl | 都不带 | 只能自己生成兜底 |

三条都存在，所以 `TraceIdFilter` 的优先级写成 `traceparent` → `X-Trace-Id` → 自己生成。**这不是"哪个更好"的选择，是"上游是谁"的兼容。**

> ⚠️ **"认标准头 + 认自定义头"覆盖不了所有上游，这是有意识的取舍**。如果对端用的是 Zipkin 的 `X-B3-TraceId`、Jaeger 的 `uber-trace-id`、AWS 的 `X-Amzn-Trace-Id` 这类第三方格式，Filter 认不出来，会自己生成新 ID —— 链路就在这一点断开。所以别指望"把所有头名都兼容一遍"，那些格式穷举不完。企业里的常规解法是**让网关统一注入一种头**，你的服务只跟网关约定一次，而不是面对每一个上游厂家。

> 🔑 **优先读 `traceparent` 是在排可信度**：标准头出现，说明上游做过埋点、链路已经建立；自定义头可能只是随手塞的一个值。所以顺序是"先信标准、再信约定、最后自己造"，而不是随便挑一个读。

> ⚠️ **本项目现在只取了 `traceparent` 的第二段，丢掉了 parent-id 和 flags —— 这是有意的简化**。单服务、没有下游调用、也没有采样策略，这两段暂时用不上。等到接了下游服务（权限服务、Legacy 系统那一批），就得把它们一起透传下去：出站时把整条 `traceparent` 往下带，并把自己这一跳的 span id 填进 `parent-id`，否则链路在你这儿就断了。

### 请求头用哪个名字

> 🔑 <span style="color:red">**行业现在倾向 W3C 标准 `traceparent`（配套 `tracestate`），老系统常见自定义的 `X-Trace-Id` / `X-Request-Id`。本项目可以两个都认：优先读 `traceparent`，其次是 `X-Trace-Id`，都没有才自己生成。**</span>

> ⚠️ **"标准"指的是行业共识，不是 HTTP 协议强制的**。HTTP 本身对头名不作任何规定，所以 `traceparent` 不会凭空出现 —— 上游不埋点、不发送，你这边就是收不到，这正是第三层兜底生成必须存在的原因。它是 W3C 的正式推荐标准（配套还有 `tracestate` 和 `baggage`），OpenTelemetry、Istio/Envoy 这类组件默认按它来发，所以它是目前**最通用**的那一个；但厂商自有的头（`X-B3-TraceId`、`x-datadog-trace-id` 之类）依然存在，只是大多同时兼容 W3C。

> ⚠️ **不要拿业务 ID 当 traceId**。理由见上文 [什么样的值才配当 traceId](#什么样的值才配当-traceid)。

> ⚠️ **别把 MDC 当业务传参通道**。名字里的 D 是 Diagnostic，它不参与业务逻辑，纯粹给日志诊断用。业务数据该走参数走参数，塞进 MDC 只会让日志和业务逻辑互相绑架。

MDC 里的值怎么变成 JSON 的顶层字段，见 [可观测性-结构化日志](./可观测性-结构化日志.md)。

---

## 🔥 面试要点

**Q1：一个请求跨了好几个服务，你怎么把日志串起来？**

> **面试话术**：*"靠请求级关联 ID。入口处生成或者从上游请求头复用，放进 MDC，日志框架会自动把它带在每一行日志上；出站调下游时再把它塞进请求头，下游的 Filter 复用它而不是重新生成。这样一次请求跨几个系统，日志里都是同一个 ID，一条查询就能全拉出来。"*

**Q2：为什么用 MDC，不直接在日志里加参数？**

> **面试话术**：*"直接加参数意味着改几十条日志语句，还要把 traceId 沿方法签名一层层往下传，业务代码就被污染了。MDC 是线程绑定的隐式上下文，入口放一次，日志框架渲染时自动取值，业务代码零改动。代价是隐式状态，所以必须在 finally 里清理。"*

**Q3：异步线程里日志丢了 traceId 怎么办？**

> **面试话术**：*"MDC 底层是 ThreadLocal，只对当前线程有效，所以提交到线程池的任务拿不到父线程的 MDC。解决办法是在提交任务时把 MDC 快照复制过去，Spring 里可以配一个 TaskDecorator 统一处理；如果是响应式栈，就不能用 MDC 了，要用 Reactor Context。"*

---

## 🧠 记忆口诀

```
traceId 记三步：进、带、清

  进 —— 入口 Filter 生成或透传，放进 MDC
  带 —— encoder 从 MDC 取值，自动渲染进每一行日志
  清 —— finally 里 remove，不然线程复用会串号

再记两条边界：
  异步和响应式要把上下文显式传过去
  traceId 标"这一次请求"，不标"这个人"
```

---

## 🔄 知识闭环

```java
问题：日志按条存，没有"这一次请求"的概念
      │
      ▼
解法：给每次请求发一个 traceId，让它全程存在
      │
      ▼
载体：MDC = 线程绑定的隐式参数袋
      │
      ├─ 好处：业务代码零改动，encoder 自动带出来
      └─ 代价：ThreadLocal 的坑，必须 finally 清理
      │
      ▼
延伸：跨线程要复制上下文，跨服务要透传请求头
      │
      ▼
出口：traceId 是"用一条查询还原一次请求"的前提，
      没有它，排查只能靠时间戳和用户 ID 猜。
```

---

## ✅ 本步小结

这一节把"第二步做了什么"固化成结论，格式和《可观测性-结构化日志》末尾的「本步小结」一致。

**目标**：让一次请求的所有日志能靠一个 ID 精确捞出来，而不是靠时间先后猜。

**这一步动了三处，各自管一段**

| # | 改哪 | 管什么 | 建议阅读顺序 |
|:---|:---|:---|:---|
| 1 | 新增 `src/main/java/com/example/navigation_menu/filter/TraceIdFilter.java` | **决定** traceId 是什么：上游有就复用，没有就生成，非法就拒绝 | ① 先看这个，60 行 |
| 2 | `src/main/resources/logback-spring.xml` 的 console pattern 加了 `[%X{traceId:--}]` | **渲染** traceId 到控制台那一路 | ③ 最后看，就一行 |
| 3 | `docs/项目规划/项目规划-全链路开发规范.md` 的包职责表加了 `filter` 一行 | 记录新包的归属 | 顺带 |

### 怎么串起来的：MDC 是唯一的交接点

```java
请求进门
    │
    │  ① TraceIdFilter          决定 ID，然后 MDC.put("traceId", id)
    ▼
业务代码 log.info(...)          ← 零改动，它根本不知道 traceId 存在
    │
    │  ② 生成 ILoggingEvent 的那一刻，MDC 快照被装了进去
    ▼
Encoder
    ├─ PatternLayoutEncoder ── ③ console pattern 里的 %X{traceId} 取值
    └─ LogstashEncoder      ── ④ 默认就把 MDC 拍成 JSON 顶层字段
    ▼
stdout / JSON 文件
    │
    │  ⑤ 一条查询：traceId = xxx
    ▼
这一次请求的全部日志
```

> 🔑 **三处改动各管一段，MDC 是唯一的交接点**。少了任何一处，链就断：
> - 没有 ① → 压根没有 ID
> - 没有 ② → ID 传不到日志框架手里（业务代码也不会替它传）
> - 没有 ③④ → ID 存进去了，但渲染不出来，日志里看不见

> ⚠️ **一个容易混的点：为什么只改了 console 的 pattern，JSON 那路没动？** 因为 `LogstashEncoder` **默认就把 MDC 输出成 JSON 顶层字段**，不用配；而 `PatternLayoutEncoder` 只打你在 pattern 里写了的东西，不写就没有。所以本地控制台要手动加，JSON 那路是白送的。

### 怎么验证这一步真的做到了

五组，每组证明一件事。前四组串行跑都能过，**只有第五组能暴露"`finally` 里漏了清理"这个 bug**：

| # | 怎么发请求 | 证明什么 | 实测结果 |
|:---|:---|:---|:---|
| ① | 不带任何请求头 | 服务能自己产生身份 | 生成了 32 位 ID |
| ② | 带 `X-Trace-Id: xxx` | 上游传下来的能接住 | 原样复用 |
| ③ | 带 W3C `traceparent` | 标准协议能识别（跨系统互通的前提） | 正确取出中间那 32 位 |
| ④ | 带非法值（含空格） | 外部输入进不来 | 拒绝并换成新 ID，只记长度不记原值 |
| ⑤ | **12 个请求并发** | 线程复用下不串号 | 12 个 ID、各 8 行、串号 0 个 |

**目的达成的那一刻** —— 一条查询捞出一次请求的完整故事：

```
$ traceId = req-from-gateway-9527

INFO  getMenuTree, params: MenuQueryDTO(..., userId=t2_upstream, ...)
INFO  cache miss, key=menu:tree:SH001:t2_upstream
INFO  loaded 25 active menus
INFO  after fallback match: 24 nodes
INFO  after permission filter: 23 nodes
INFO  tree built, root count: 7
INFO  chain terminated at TreeBuildHandler
INFO  cache set, key=menu:tree:SH001:t2_upstream, ttl=PT10M
```

八行，一步不缺。第 1 步的那 8 行是"按时间先后恰好排在一起"；这 8 行是"**带着同一个 ID，可以被一条等值查询精确捞出来**"。这就是第二步买到的东西。

> 🔑 <span style="color:red">**一句话结论：traceId 是索引，不是答案。它让你"精确找到那一次请求"；"那一次里为什么这么决策"要靠第 4 步（业务埋点）把细节写成 DEBUG 级日志。索引和内容缺一不可。**</span>

**这一步没做什么**（项目里暂时也没有对应场景）

- **异步传递**：项目里还没有 `@Async` / 线程池，MDC 的坑碰不到。等有异步任务时要配 `TaskDecorator`
- **出站透传**：项目还没调下游服务，所以没有"把 traceId 塞进出站请求头"这一步。等接了真实权限服务 / Legacy 转发时再做

两条都记在上文「四个必须提前处理的坑」里，到时候照着做。