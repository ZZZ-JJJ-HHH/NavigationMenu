# 技术专题：Spring 依赖注入详解

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
- [📊 应用与对比](#应用与对比)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> **Spring 帮你 new 对象，还自动把你需要的对象塞进去。构造器注入（配 Lombok + final）是大厂标准写法。**

---

## 🎯 背景与问题

### 没有依赖注入时的痛点

| 痛点 | 具体表现 |
|------|---------|
| 手动 new 对象 | 每个依赖都要自己 `new`，代码硬编码耦合 |
| 生命周期混乱 | 单例要手写双检锁，多例不知道怎么管 |
| 测试困难 | 依赖硬编码在构造器里，无法 mock |
| 换实现要改代码 | 换个数据源/Mapper 实现，要改所有引用处 |
| AOP/事务无法用 | 手动 new 的对象不在 Spring 容器里，`@Transactional` 失效 |

### 传统写法 vs Spring 注入

| 没有注入 | 有注入 |
|----------|--------|
| 自己 `new` 对象 | Spring 容器管理所有对象 |
| 对象之间硬编码耦合 | 通过接口解耦，测试可以 mock |
| 单例要手写双检锁 | `@Service` 默认就是单例 |
| AOP/事务管理要手动代理 | `@Transactional` 一行搞定 |
| 换实现要改一堆代码 | 换个 Bean 配置就行 |

---

## 💡 核心目的

**Spring 的依赖注入要解决三个核心问题：**

1. **解耦**：调用方不关心实现怎么来的，只声明"我需要什么"
2. **可测试**：单元测试时直接 `new Service(mock1, mock2)`，不需要启动整个 Spring 容器
3. **可维护**：依赖清单一目了然（构造器参数就是依赖清单），循环依赖在启动时就能发现

---

## ⚙️ 原理与机制

### 1. 三种注入方式

#### 方式 1：字段注入

```java
@Service
public class OrderService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private OrderMapper orderMapper;
}
```

- **优点**：写起来最短
- **缺点**：字段不能 `final`、循环依赖运行时才炸、测试需要 Spring 容器
- **Spring 官方态度**：不推荐

#### 方式 2：Setter 注入

```java
@Service
public class OrderService {

    private UserMapper userMapper;
    private OrderMapper orderMapper;

    @Autowired
    public void setUserMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Autowired
    public void setOrderMapper(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }
}
```

- **优点**：可以设置可选依赖
- **缺点**：代码啰嗦，每个依赖一个 setter
- **适用场景**：依赖是可选的

#### 方式 3：构造器注入（推荐）

```java
@Service
public class OrderService {

    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final CacheService cacheService;

    public OrderService(UserMapper userMapper,
                        OrderMapper orderMapper,
                        CacheService cacheService) {
        this.userMapper = userMapper;
        this.orderMapper = orderMapper;
        this.cacheService = cacheService;
    }
}
```

- **优点**：字段可 `final`（不可变 + 线程安全）、循环依赖启动就报错、测试直接 `new`、依赖一目了然
- **Spring 4.3+**：只有一个构造器时，`@Autowired` 都不用写
- **Lombok 终极形态**：`@RequiredArgsConstructor` + `final` 字段，构造器代码都省了

```java
@RequiredArgsConstructor
@Service
public class OrderService {

    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final CacheService cacheService;
}
// ↑ Lombok 编译期自动生成上面的构造器，Spring 看到只有一个构造器自动注入
```

### 2. @Autowired 的本质

**`@Autowired` 是给 Spring 画箭头——"这个地方你要帮我塞对象进来"。**

Spring 对不同注入点的规则：

| 注入点 | @Autowired 能省？ | 条件 |
|--------|------------------|------|
| 构造器 | ✅ 能省 | 类只有一个构造器（Spring 4.3+） |
| 构造器 | ❌ 不能省 | 有多个构造器时必须指定 |
| Setter | ❌ 永远不能省 | Spring 分不清哪个是注入、哪个是业务方法 |
| 字段 | ❌ 永远不能省 | Spring 分不清字段是依赖还是业务字段 |

### 3. @Component 注入体系

```java
@Component           // 最通用的组件
  ├── @Service       // 业务层
  ├── @Controller    // Web 层
  │     └── @RestController  // 返回 JSON 的 Controller
  ├── @Repository    // 数据层
  └── @Configuration // 配置类
```

**一个 Bean 进容器的流程：**

```java
类上有 @Component/@Service/@Controller
        ↓
Spring Boot 扫描主类所在包及子包
        ↓
创建对象 → 放进 ApplicationContext
        ↓
其他类注入时从容器里拿
```

### 4. 同一接口多个实现怎么办

```java
public interface PaymentService { ... }

@Service
public class AlipayPaymentService implements PaymentService { ... }

@Service
public class WechatPaymentService implements PaymentService { ... }
```

三种解法：

```java
// 解法 1：@Qualifier + @Autowired
@Autowired
@Qualifier("wechatPaymentService")
private PaymentService paymentService;

// 解法 2：@Resource（按名称注入）
@Resource(name = "wechatPaymentService")
private PaymentService paymentService;

// 解法 3：@Primary（默认注入这个）
@Primary
@Service
public class WechatPaymentService implements PaymentService { ... }
```

---

## 📊 应用与对比

### 三种注入方式横向对比

| 维度 | 字段注入 | Setter 注入 | 构造器注入（推荐） |
|------|----------|------------|------------------|
| 代码量 | 最少 | 中等 | 最少（配 Lombok） |
| final 字段 | ❌ | ❌ | ✅ |
| 不可变性 | ❌ | ❌ | ✅ |
| 循环依赖检测 | 运行时炸 | 运行时炸 | 启动就炸 |
| 测试友好度 | ❌ 要容器 | ⭐ 还行 | ✅ 直接 new |
| 依赖可见性 | ❌ 隐藏 | ⭐ 一半 | ✅ 一目了然 |
| Spring 推荐 | ❌ | ⭐ | ✅ |
| 大厂使用率 | 低 | 中 | 极高 |

### @Autowired vs @Resource vs @Inject

| 注解 | 来源 | 默认匹配 | 适用场景 |
|------|------|---------|---------|
| `@Autowired` | Spring | 按类型（byType） | 绝大多数场景 |
| `@Resource` | JSR-250 | 按名称（byName） | 同一接口多个实现时 |
| `@Inject` | JSR-330 | 按类型 | 与 @Autowired 几乎一样 |

### 循环依赖：三种注入方式的不同表现

```java
@Service
public class ServiceA {
    @Autowired
    private ServiceB b;   // A 依赖 B
}

@Service
public class ServiceB {
    @Autowired
    private ServiceA a;   // B 依赖 A → 循环！
}
```

| 注入方式 | 循环依赖表现 | 原因 |
|----------|------------|------|
| 字段注入 | 可能能跑，不保证 | 三级缓存能解部分场景 |
| Setter 注入 | 能跑 | 对象先 new 出来，再 setter 填字段 |
| 构造器注入 | **启动直接报错** | 构造器参数必须全部就绪，A 要 B、B 要 A → 死锁 |

> **循环依赖是设计问题，不是注入方式的问题。构造器注入启动就炸，是帮你早发现早解决。**

---

## 🔥 面试要点

### 高频问题 + 标准答案

> **Q1: 为什么有些 Service 不写 @Autowired 也能注入？**
>
> 类上有 `@RequiredArgsConstructor`（Lombok），自动生成包含所有 `final` 字段的构造器。Spring 4.3 起，如果类只有一个构造器，Spring 会自动用这个构造器注入，不需要 `@Autowired`。

> **Q2: 字段注入、Setter 注入、构造器注入，你选哪个？为什么？**
>
> 构造器注入。原因有三：一是字段可以声明为 final，保证不可变性和线程安全；二是循环依赖在启动时就会报错，早发现早解决；三是单元测试友好，直接 new 就行，不需要 Spring 容器。配合 Lombok 的 `@RequiredArgsConstructor` 写起来也不啰嗦。

> **Q3: @Autowired 和 @Resource 有什么区别？**
>
> `@Autowired` 是 Spring 原生的，默认按类型注入；`@Resource` 是 JSR-250 标准，默认按名称注入。大部分场景用 `@Autowired` 就行，同一个接口有多个实现时用 `@Qualifier` 或 `@Resource(name="xxx")` 指定。

> **Q4: 什么是循环依赖？怎么解决？**
>
> A 依赖 B，B 又依赖 A。字段注入和 Setter 注入可能能跑过去，但构造器注入会启动报错。我认为循环依赖是设计问题，正确做法是抽取公共逻辑到第三个 Service，让 A 和 B 都依赖它。

---

## 🧠 记忆口诀

> **注入三兄弟，构造排第一。final 保不变，Lombok 省力气。循环要早爆，设计先分离。**

---

## 🔄 知识闭环

### Spring 注入在分层架构中的位置

```java
Controller（接入层）
    │
    │ @Autowired / 构造器注入
    ▼
Service（业务层）──────────────────────┐
    │                                  │
    ├── 构造器注入 → Mapper（数据层）    │
    ├── 构造器注入 → CacheService      │  Spring 容器统一管理
    ├── 构造器注入 → 其他 Service       │  所有 Bean 的生命周期
    └── 构造器注入 → 工具类组件         │
                                       │
                                  全部注入完毕 → 应用就绪
```

### 核心链路回顾

```java
@Component 标记
    │
    ▼
Spring 扫描并创建 Bean
    │
    ▼
放入 Spring 容器
    │
    ├── @Autowired 标注的字段 / Setter → 按类型从容器注入
    │
    └── 构造器参数 → Spring 4.3+ 自动注入
                      │
                      ▼
                 构造器注入 + final 字段
                 = 不可变 + 启动检查循环依赖
```

**依赖注入的本质**：让 Spring 替你管理对象创建和依赖关系，你只声明"我需要什么"。构造器注入之所以是最佳实践，不是因为它"更先进"，而是因为它在编译期就暴露了所有设计问题——依赖太多？构造器参数太长。循环依赖？启动就炸。这些不是注入方式的缺点，是帮你发现设计问题的信号。