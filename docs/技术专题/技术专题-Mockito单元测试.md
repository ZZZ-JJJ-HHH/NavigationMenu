# 技术专题：Mockito 单元测试

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [三个断言为什么一个都不能少](#三个断言为什么一个都不能少)
  - [void 方法怎么表达成功/失败](#void-方法怎么表达成功失败)
  - [完整测试流程拆解](#完整测试流程拆解)
- [📊 应用与对比](#应用与对比)
  - [四个测试用例覆盖两条代码路径](#四个测试用例覆盖两条代码路径)
  - [Mockito vs 其他测试框架](#mockito-vs-其他测试框架)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)
  - [单元测试在整体链路中的位置](#单元测试在整体链路中的位置)
  - [与其他技术专题的闭环关系](#与其他技术专题的闭环关系)
  - [三种测试手段的互补关系](#三种测试手段的互补关系)
  - [E2E 的两种语境](#e2e-的两种语境)
  - [真实的防回归案例](#真实的防回归案例)
  - [单元测试 vs 重启 Spring Boot + Postman 的关系](#单元测试-vs-重启-spring-boot--postman-的关系)

---

## 📌 一句话核心

> **单元测试 = 用 Mock 隔离外部依赖 + 用断言锁定行为边界，让每个方法独立、可重复、快速地被验证。**

---

## 🎯 背景与问题

### 没有单元测试时是什么样

你的 `MenuServiceImpl` 有两个依赖：`MenuTreeCacheService`（Redis）和 `MenuHandlerChain`（责任链，背后又是 5 个 Handler，每个 Handler 又依赖 DB）。

如果不用 Mock，想测试 `buildMenuTree` 的一条逻辑路径，你需要：

```java
// 假设不用 Mock，直接 new 真实对象
MenuServiceImpl menuService = new MenuServiceImpl(
    new RedisCacheService(...),      // 需要真实 Redis 连接
    new MenuHandlerChain(
        new LegacyCheckHandler(branchMapper),   // 需要 DB
        new LoadMenuHandler(menuMapper),        // 需要 DB
        new FallbackMatchHandler(fallbackService),
        new PermissionFilterHandler(permissionFilter),
        new TreeBuildHandler(treeBuilder)
    )
);
```

| 问题 | 具体表现 |
|------|---------|
| **启动成本高** | 要跑 Spring 容器 + 起 Redis + 起 MySQL，一条测试用例启动就要 10 秒+ |
| **测试慢** | 每个方法调用都走真实 Redis 和 DB，单测耗时几百毫秒 |
| **不稳定** | 环境变了（Redis 挂了、DB 数据被改了）测试就莫名失败 |
| **隔离不了** | 想测"缓存命中时不走责任链"——但真实 Redis 里有没有数据、真实 Chain 会不会报错，你控制不了 |

### 核心痛点

> **"我只想测这一个方法的逻辑，不想把整个项目拉起来陪跑。"**

---

## 💡 核心目的

**用 Mock 隔离外部依赖，用断言锁定行为边界。设计目标：**

1. **隔离**：被测方法只和 Mock 对象交互，不碰 Redis、不碰 DB、不启 Spring
2. **快速**：单测一条 10-50ms，跑完整个测试类不到 1 秒
3. **稳定**：测试结果只和 Mock 打桩有关，和环境无关，今天跑、明年跑结果一样
4. **精确**：能精确断言"缓存命中时责任链**完全没被调用**"这种交互行为

---

## ⚙️ 原理与机制

### 三个断言为什么一个都不能少

以 `MenuServiceImplTest.returnsCachedResultWithoutRunningChain()` 为例：

```java
@Test
void returnsCachedResultWithoutRunningChain() {
    MenuTreeVO cached = new MenuTreeVO(List.of(), null);
    when(cacheService.get(BRANCH, USER)).thenReturn(cached);

    MenuTreeVO result = menuService.buildMenuTree(query());

    assertThat(result).isSameAs(cached);              // ①
    verifyNoInteractions(handlerChain);                // ②
    verify(cacheService, never()).put(any(), any(), any());  // ③
}
```

三个断言**各自独立、互不重叠**，分别从三个维度锁定"缓存命中"这条路径的正确行为。

#### 断言① `assertThat(result).isSameAs(cached)` —— 返回值正确性

```java
// 被测代码路径：
MenuTreeVO cached = cacheService.get(...);
if (cached != null) {
    return cached;   // ← 这里应该直接返回缓存对象本身
}
```

`isSameAs` 用的是 `==`（引用相等），不是 `equals`（值相等）。验证的是：方法有没有**直接透传**缓存对象，而不是偷偷 new 了一个副本、或者返回了 null。

如果被测代码被改成这样：

```java
// 假设同事改坏了：
if (cached != null) {
    return new MenuTreeVO(cached.getTree(), null);  // 错误：new 了新对象
}
```

这时断言①会失败：`expected: same instance but was: different instance`。

#### 断言② `verifyNoInteractions(handlerChain)` —— 没有多余计算

这是**核心性能保护**。"缓存命中就不走责任链"是这条路径的**核心业务语义**——责任链里涉及 DB 查询、树形构建、权限过滤，全是昂贵操作。

如果被测代码被改成这样：

```java
// 假设同事不小心加了一行：
if (cached != null) {
    handlerChain.proceed(...);  // ← 多余！缓存命中不该走链
    return cached;
}
```

这时：
- 断言① `isSameAs(cached)` → ✅ 通过（返回值还是对的）
- 断言② `verifyNoInteractions(handlerChain)` → ❌ **失败！**

这就是为什么断言②不能省——它防的是**"返回值对了，但内部多做了不该做的事"**这类隐性 bug。

#### 断言③ `verify(cacheService, never()).put(any(), any(), any())` —— 没有多余写入

缓存里已经有了，当然不需要再写一次。这条断言防的是：

```java
// 假设同事改坏了：
if (cached != null) {
    cacheService.put(...);  // ← 多余！不该覆盖有效缓存
    return cached;
}
```

多余的 Redis 写不仅浪费性能，还可能用旧数据覆盖新数据（缓存穿透、缓存击穿场景下很危险）。

#### 三个断言的防回归矩阵

| 被测代码被改成... | 断言① | 断言② | 断言③ |
|---|---|---|---|
| 返回 new 的副本 | ❌ 失败 | ✅ 通过 | ✅ 通过 |
| 缓存命中还是调了 handlerChain | ✅ 通过 | ❌ 失败 | ✅ 通过 |
| 缓存命中还是写了 cacheService.put | ✅ 通过 | ✅ 通过 | ❌ 失败 |
| 三个多余操作都做了 | ❌ 先失败，测试中断 | 不会执行 | 不会执行 |

**一个断言只防一类 bug，三个一起才能完整覆盖这条路径的所有行为边界。**

### void 方法怎么表达成功/失败

测试方法签名是 `void returnsCachedResultWithoutRunningChain()`——没有返回值。那"成功还是失败"怎么表达？

答案：**靠抛异常。**

#### JUnit 测试方法的真相

```
JUnit 框架调用 testMethod()
    │
    ├── 所有断言都通过？
    │       → 方法正常 return（void）
    │       → JUnit 标记 ✅ 测试通过
    │
    └── 任何一个断言失败？
            → 抛出 AssertionError / MockitoAssertionError
            → JUnit 捕获异常
            → 标记 ❌ 测试失败 + 输出错误消息
```

#### 三个断言各自抛什么异常

| 断言 | 失败时抛出 | 典型消息 |
|---|---|---|
| `assertThat(result).isSameAs(cached)` | `AssertionError` | `expected: same instance but was: different instance` |
| `verifyNoInteractions(handlerChain)` | `MockitoAssertionError` | `No interactions wanted here, but found this interaction: handlerChain.proceed(...)` |
| `verify(cacheService, never()).put(...)` | `MockitoAssertionError` | `Wanted but not invoked... Actually, was invoked 1 time` |

**所以 void 方法不代表"没有结果"——它的"结果"就是：正常走完 = 通过，抛异常 = 失败。** 这是 JUnit 所有测试方法的共同协议。

### 完整测试流程拆解

以 `returnsCachedResultWithoutRunningChain` 为例，完整走一遍：

```java
// ===== 阶段 1：准备 =====
MenuTreeCacheService cacheService = mock(MenuTreeCacheService.class);
MenuHandlerChain handlerChain = mock(MenuHandlerChain.class);
MenuServiceImpl menuService = new MenuServiceImpl(cacheService, handlerChain);

// ===== 阶段 2：打桩（模拟缓存命中）=====
MenuTreeVO cached = new MenuTreeVO(List.of(), null);
when(cacheService.get("SH001", "normal_user")).thenReturn(cached);

// ===== 阶段 3：执行 =====
MenuTreeVO result = menuService.buildMenuTree(query());

// ===== 阶段 4：断言 =====
assertThat(result).isSameAs(cached);   // 结果对不对
verifyNoInteractions(handlerChain);     // 责任链没被碰
verify(cacheService, never()).put(...);  // 没多写缓存
```

对应被测代码的实际执行流：

```
menuService.buildMenuTree(query())
    │
    ├── cacheService.get("SH001", "normal_user")  ← mock 返回 cached 对象 ✅
    │
    └── cached != null → 直接 return cached       ← 到这里就结束了
           ↑
     handlerChain.proceed(...) 没被调用 ✅
     cacheService.put(...)      没被调用 ✅
```

---

## 📊 应用与对比

### 四个测试用例覆盖两条代码路径

`MenuServiceImpl.buildMenuTree` 是典型的 **Cache-Aside** 模式，整个方法只有两条路径：

```java
public MenuTreeVO buildMenuTree(MenuQueryDTO dto) {
    MenuTreeVO cached = cacheService.get(...);
    if (cached != null) {
        return cached;                    // ← 路径 A：缓存命中
    }

    handlerChain.proceed(context);        // ← 路径 B：缓存未命中
    MenuTreeVO result = context.getResult();
    if (result == null) throw new ...;    // ← 路径 B 的边界
    cacheService.put(..., result);
    return result;
}
```

四个测试用例把两条路径的各种边界情况都覆盖了：

| 测试用例 | 覆盖路径 | Mock 打桩 | 核心断言 |
|---|---|---|---|
| `returnsCachedResultWithoutRunningChain` | **A（缓存命中）** | `cacheService.get → cached` | `isSameAs` + `verifyNoInteractions(chain)` + `never put` |
| `runsChainOnCacheMissAndCachesResult` | **B（缓存未命中，正常）** | `cacheService.get → null` + `handlerChain → 设置 result` | `isSameAs` + `verify put` |
| `throwsWhenChainProducesNoResult` | **B（缓存未命中，边界）** | `cacheService.get → null` + `handlerChain → 不设 result` | `assertThatThrownBy` + `never put` |
| `passesQueryIntoChainContext` | **B（参数透传）** | 捕获传入 `handlerChain.proceed` 的 `MenuContext` | `context.getQuery()` 里的 branch/user 对不对 |

### Mockito vs 其他测试框架

| 维度 | Mockito（我们用的） | JMockit | Spring Boot Test |
|---|---|---|---|
| **隔离程度** | 纯 Mock，零真实依赖 | 字节码级 Mock，更强大但复杂 | 启动 Spring 容器，部分真实依赖 |
| **启动速度** | 单测 10-50ms | 同 Mockito | 1-5 秒（启容器） |
| **适合场景** | 单元测试（测单个方法逻辑） | 需要 Mock 静态方法/私有方法时 | 集成测试（测多层协作） |
| **学习曲线** | 低——`when/thenReturn`、`verify` | 高——需要理解字节码操作 | 中等——需要理解 Spring Test 注解 |
| **我们的选择** | ✅ 主要测试手段 | 没用到 | `NavigationMenuApplicationTests`（集成测试用） |

### 单元测试 vs 集成测试

| 维度 | 单元测试（我们主要写的） | 集成测试 |
|---|---|---|
| **测什么** | 一个类的一个方法的逻辑 | 多层组件协作（Service + Mapper + DB） |
| **依赖** | 全 Mock | 尽量用真实（Redis、DB 用 testcontainers 或 H2） |
| **速度** | 10-50ms/条 | 1-30 秒/条 |
| **稳定性** | 只和打桩有关，极其稳定 | 依赖环境，偶尔莫名失败 |
| **谁写** | 开发自己写 | 开发自己写 + 可能有专门的 QA |
| **我们项目里的** | `MenuServiceImplTest`、`MenuTreeBuilderTest` 等（11 个测试类） | `NavigationMenuApplicationTests`（Spring Boot 启容器测） |

---

## 🔥 面试要点

### 高频问题 + 标准答案

**Q: 你写单元测试怎么隔离依赖？**
> "用 Mockito。在 `MenuServiceImplTest` 里，`MenuTreeCacheService` 和 `MenuHandlerChain` 都是 mock 出来的，被测对象直接 `new MenuServiceImpl(cacheService, handlerChain)`，不启 Spring、不碰 Redis、不碰 DB。一条测试 30ms 就跑完。"

**Q: Mockito 的 `when/thenReturn` 和 `verify` 分别干嘛？**
> "`when/thenReturn` 是**打桩**——告诉 mock 对象某个方法被调用时返回什么，模拟特定场景（比如 `cacheService.get() → null` 模拟缓存未命中）。`verify` 是**验证交互**——断言某个方法有没有被调用、调了几次、调时参数对不对。我最常用的三个：`verify(mock).method()` 验证调了一次、`verify(mock, never()).method()` 验证没调、`verifyNoInteractions(mock)` 验证从头到尾完全没碰过。"

**Q: 为什么一个测试要写三个断言？不能只看返回值吗？**
> "不够。`returnsCachedResultWithoutRunningChain` 这个测试有三个断言：① `isSameAs(cached)` 验证返回值对不对；② `verifyNoInteractions(handlerChain)` 验证责任链没被碰（核心性能保护）；③ `verify(cacheService, never()).put(...)` 验证没多写缓存。只看返回值的话，如果同事不小心在缓存命中路径里多调了一次 `handlerChain.proceed()`，返回值还是对的，但性能已经崩了——单靠返回值断言根本测不出来。**行为正确 ≠ 内部实现正确**，交互断言是必须的。"

**Q: 测试方法是 void，怎么知道成功还是失败？**
> "靠抛异常。所有断言（AssertJ 的 `assertThat` 和 Mockito 的 `verify`）失败时都会抛异常——`AssertionError` 或 `MockitoAssertionError`。JUnit 框架捕获异常来判断：正常 return = 通过，抛异常 = 失败。这是 JUnit 的核心协议，从 JUnit 3 到现在一直是这个机制。"

**Q: `isSameAs` 和 `isEqualTo` 有什么区别？**
> "`isSameAs` 用 `==`，验证**引用相等**——必须是同一个对象实例。`isEqualTo` 用 `equals()`，验证**值相等**。测缓存命中的返回值必须用 `isSameAs`——因为我要确认方法是直接 `return cached` 了缓存对象本身，而不是 `return new MenuTreeVO(cached.getTree(), null)` 复制了一份。后者值相等但引用不同，`isSameAs` 能揪出来，`isEqualTo` 不行。"

**Q: `verifyNoInteractions` 和 `verify(mock, never()).xxx` 有什么区别？**
> "`verifyNoInteractions(mock)` 是**全方位**验证——从头到尾这个 mock **任何方法**都没被碰过。`verify(mock, never()).xxx` 是**定向**验证——只验证某个特定方法没被调，其他方法（比如 mock 内部的初始化方法）可能碰了也不管。在我的测试里，`handlerChain` 应该完全不被碰，所以用 `verifyNoInteractions`；`cacheService` 的 `get` 是应该被调的（打桩时就会被调），只有 `put` 不该被调，所以用 `verify(cacheService, never()).put(...)`。"

### 关键词速记

```
核心关键词：
  Mockito / AssertJ / JUnit 5
  when().thenReturn() 打桩
  verify / verifyNoInteractions 交互验证
  isSameAs 引用相等 vs isEqualTo 值相等
  void 方法靠抛 AssertionError 表达失败

可量化的表述：
  "11 个测试类" → "覆盖所有 Service 和 Handler 的主要路径"
  "30ms 一条" → "整个测试类 1 秒内跑完"
  "4 个用例" → "Cache-Aside 的两条路径 + 两个边界"
```

---

## 🧠 记忆口诀

```
单元测试口诀：
  "依赖全 mock 隔离，when/thenReturn 造场景，
   三个断言锁边界，void 方法靠异常。"
```

---

## 🔄 知识闭环

### 单元测试在整体链路中的位置

回看你项目的完整请求链路：

```
前端请求
    │
    ▼
Controller（参数校验）
    │
    ▼
Service（缓存检查）
    │
    ├── 缓存命中 → 直接返回  ← 单元测试测这条路径不碰责任链 ✅
    │
    └── 缓存未命中 → 责任链
        │
        ├── LegacyCheckHandler @Order(1)
        ├── LoadMenuHandler @Order(2)
        ├── FallbackMatchHandler @Order(3)
        ├── PermissionFilterHandler @Order(4)
        └── TreeBuildHandler @Order(5)
    │
    ▼
Service（缓存回写）
    │
    ▼
Controller（返回 JSON）
```

单元测试**不跑**这条真实链路——它把 `CacheService` 和 `HandlerChain` 都 mock 掉，只测 `Service` 本身的编排逻辑。但它的价值在于：**每一次你改了 `Service` 的一行代码，跑一下测试就知道两条路径还正不正常**。

### 与其他技术专题的闭环关系

| 专题 | 和单元测试的关系 |
|---|---|
| **技术专题-责任链模式** | `MenuHandlerChain` 和 5 个 Handler 是单元测试的**被测对象**或**mock 对象**。`MenuHandlerChainTest` 专门测 Chain 的 @Order 排序和自动终止逻辑 |
| **技术专题-Redis缓存** | `MenuTreeCacheService` 是单元测试的**核心 mock 对象**。测试通过打桩 `cacheService.get → null` 来模拟缓存未命中，验证 Cache-Aside 的两条路径 |
| **技术专题-树形结构构建方案** | `MenuTreeBuilder` 有独立的单元测试 `MenuTreeBuilderTest`，验证 O(n) 组树算法的正确性 |
| **技术专题-Spring依赖注入** | 单元测试**故意不用** Spring——直接 `new MenuServiceImpl(mockCache, mockChain)`，绕开容器，这正是"理解了 DI 才能更好地绕开 DI" |

### 三种测试手段的互补关系

```
┌─────────────────────────────────────────────────────────────────────┐
│                        测试金字塔                                   │
│                                                                     │
│                     ╱  E2E 测试  ╲          ← 测完整用户旅程（少）      │
│                    ╱   （前端→后端→DB）  ╲                          │
│                   ╱───────────────────────╲                         │
│                  ╱   集成测试               ╲     ← 测多层协作（中）   │
│                 ╱  Spring Boot Test          ╲                      │
│                ╱─────────────────────────────╲                     │
│               ╱                                 ╲                  │
│              ╱      单元测试（我们主要写的）       ╲                 │
│             ╱   Mockito + AssertJ + JUnit 5      ╲                 │
│            ╱───────────────────────────────────────╲                │
│                                                                     │
│  我们项目的比例：单元测试 90%+ 集成测试 10%                           │
└─────────────────────────────────────────────────────────────────────┘
```

> 🔑 **关键洞察**：单元测试是金字塔的**基座**——它跑得最快、覆盖最广、保护最密。集成测试是中间层——测多层协作。E2E 测试在你们项目里暂时不需要，但它是**验收环节的终极保障**。

### E2E 的两种语境

E2E = End-to-End（端到端），在不同场景下含义不同：

#### 语境一：测试领域（金字塔顶）

```
浏览器 ──▶ 网关 ──▶ Controller ──▶ Service ──▶ DB/Redis
每一层都是真实的，不 mock 任何东西
```

E2E 测试测的是**完整用户旅程**——比如 Cypress、Playwright 这类工具，模拟真实用户在浏览器里点按钮、填表单、看结果。测试金字塔顶的那层就是它，量最少但价值最高——通过了就说明整个系统对用户来说是能工作的。

| 维度 | 单元测试 | 集成测试 | E2E 测试 |
|------|---------|---------|---------|
| mock 吗？ | 大部分 mock | 部分 mock | **完全不 mock** |
| 启容器吗？ | 不启 Spring | 启 Spring | Spring + DB + Redis + 前端 |
| 测一次耗时 | 10-50ms | 1-5 秒 | 10-60 秒 |
| 覆盖比例 | 70%+ | 20% | 10% |
| 你们项目里有吗？ | 有（11 个测试类） | 有（ApplicationTests） | **暂时没有** |

#### 语境二：业务领域——E2E Token（链路追踪的核心）

这是在微服务架构里更常听到的概念。一个请求经过多个服务时，需要能在**所有服务的日志里都看到同一条记录**：

```
用户浏览器发请求，请求头里带着: X-Trace-Id: abc123xyz
    │
    ▼
网关（Gateway）── 看到 X-Trace-Id: abc123xyz
    │
    ▼
服务 A（订单）── 透传给服务 B，日志里打 [abc123xyz]
    │
    ▼
服务 B（支付）── 透传给服务 C，日志里打 [abc123xyz]
    │
    ▼
服务 C（库存）── 日志里也打 [abc123xyz]
```

排查问题时，搜这个 ID 就能看到完整调用链——**没有 E2E Token，排查一个跨 5 个服务的问题要半小时起步；有了，3 分钟定位**。

常见叫法不同，东西是同一个：

| 叫法 | 来源 |
|------|------|
| **E2E Token** | 老派说法，强调"端到端透传" |
| **Trace ID** | SkyWalking / Jaeger 等链路追踪框架的标准叫法 |
| **Request ID** | Spring Cloud Gateway 里叫 `request-id` |

你们项目现在只有单体服务，E2E Token 暂时用不上；以后拆成微服务、接 SkyWalking 时，它就是核心概念。

### 真实的防回归案例

假设某天你重构 `MenuServiceImpl`，顺手把缓存查询的两个参数顺序写反了：

```java
// 改前：
MenuTreeVO cached = cacheService.get(dto.getBranchCode(), dto.getUserId());

// 改后（手滑改坏了参数顺序）：
MenuTreeVO cached = cacheService.get(dto.getUserId(), dto.getBranchCode());
//                                                                   ↑ 反了！
```

跑一下 `mvn test`，30 秒后：

```
❌ returnsCachedResultWithoutRunningChain FAILED
   java.lang.IllegalStateException: menu handler chain produced no result

❌ throwsWhenChainProducesNoResult PASSED       ← 也挂了（不该挂的）
   （因为这次真的有 result 了，之前是抛异常的）
```

为什么是这两个错误？推导一下：

```
测试打桩：when(cacheService.get("SH001", "normal_user")).thenReturn(cached)
实际调用：cacheService.get("normal_user", "SH001")    ← 参数反了，打桩没命中
mock 默认返回 null → cached == null → 走责任链
handlerChain 没打桩 → proceed 后 context.result 还是 null
Service 抛 IllegalStateException！
```

**单元测试在你提交代码前就拦住了这个低级 bug**——这就是持续保护的价值。

### 单元测试 vs 重启 Spring Boot + Postman 的关系

你说得对，真实开发流程里大家确实是改完代码重启一下、Postman 测一测。那单元测试和这个是什么关系？

| 维度 | 重启 + Postman | 单元测试 |
|------|---------------|---------|
| **测什么** | 端到端的完整链路（Controller → Service → DB → Redis） | 单个类的单个方法 |
| **能测什么** | 只有"路径 A 对不对"这种粗粒度问题 | 能精确测"缓存命中时责任链**完全没被调用**"这种细粒度交互 |
| **测一次要多久** | 重启 Spring Boot 5-15 秒 + Postman 发请求 1 秒 = **十几秒** | 跑完整个 `MenuServiceImplTest` **不到 1 秒** |
| **发现 bug 的时间** | 改完代码 → 重启 → 测 → 发现不对 → 查日志 → 定位大概要 **几分钟** | 改完代码 → `mvn test` → 报错直接告诉你 **哪个方法、哪行、什么断言** |
| **能覆盖多少场景** | 每次 Postman 只能测**一条请求路径**，边界场景（缓存 miss + 责任链没产出）很少手动测 | 4 个测试用例覆盖了 **Cache-Aside 的所有边界路径**，每个改动都自动全量回归 |
| **每次改完都要做吗** | 不会——改了一个 if 判断、改了参数顺序，你大概率懒得重启去测"缓存命中路径"，只测自己改的那部分 | 跑 `mvn test` 是一行命令的事，**每次改完都自动全量回归** |
| **能防什么回归** | 防"接口整体挂了"这种大问题 | 防"返回值对了但多做了不该做的事""参数顺序反了""边界条件丢了"这种**细微但致命**的回归 |

**两者不是替代关系，是互补关系**：

```
你的开发日常：

  1. 改了 MenuServiceImpl 的一行参数顺序
  2. IDE 里 Run → mvn test（0.5 秒跑完）
     ├── ❌ 两个测试挂了！
     ├── 看错误：IllegalStateException + 参数反了
     └── 30 秒内定位并修复 ← 单元测试拦住
  
  3. 重启 Spring Boot（10 秒）
  4. Postman 发一个请求（1 秒）
     └── 验证接口整体 OK ← Postman 做的事
```

如果没有单元测试，你不会重启 Postman 去测"缓存命中路径"——你改的是参数顺序，心想"缓存 miss 路径应该还是对的"，测个 Postman 看返回值对了就提交了。然后晚上线上用户缓存命中时，参数顺序反了导致 Redis key 对不上、一直走 miss、持续打 DB……单元测试就是在你**懒得手动测**的那些路径上给你站岗。