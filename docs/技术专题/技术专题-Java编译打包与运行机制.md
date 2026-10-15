# 技术专题：class、jar 与 JVM 的关系

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

> **源码不是给 JVM 看的，字节码才是；jar 不是给 JVM 看的，classpath 才是。**

---

## 🎯 背景与问题

### 没有这套机制时是什么样

以 C 为例，源码经过编译器直接产出**目标平台的机器码**：

```java
main.c
  │
  │ gcc（编译 + 链接）
  ▼
main.exe / a.out        ← 已经是某个具体 CPU 架构的机器指令
  │
  ▼
只能在这类 CPU + 这个操作系统上跑
```

换一台机器，哪怕只是换个操作系统，产物就得重新编译一份。

### 核心痛点

| 痛点 | 表现 |
|------|------|
| 平台耦合 | 编译产物和 CPU 架构、操作系统绑死，换环境要重编 |
| 分发困难 | 一个项目几百个 `.class` 文件散落在目录树里，没法当"一个东西"传给别人 |
| 边界模糊 | 哪些是"我的代码"、哪些是第三方依赖，平铺在一起分不清 |
| 启动无标准 | 拿到一堆文件，不知道从哪个类开始跑 |

> ⚠️ **关键细节**：这四个痛点分别由四样东西解决——**.class** 解决平台耦合，**jar** 解决分发和边界，**MANIFEST.MF** 解决启动入口，**classpath** 解决"去哪儿找类"。它们是一条链上的四环，不是一个孤立的发明。

---

## 💡 核心目的

**这套机制要达成一个总目标：把「跑什么」和「在哪跑」拆开。**

拆解成三个子目标：

1. **让产物与平台解耦**——编译一次，到处运行
2. **让产物可整体分发**——几百个文件收成一个，带上版本信息
3. **让"从哪开始跑"有明确约定**——不靠口头约定，靠一份标准化的清单

> 🔑 **一句话：C 是"编译到底"，Java 是"编译到中间，运行前再走一步"。多出来的这一步，就是 JVM。**

---

## ⚙️ 原理与机制

### 第一层：源码 → .class（javac）

```java
NavigationMenu.java
      │
      │ javac 编译
      ▼
NavigationMenu.class          ← 字节码：不是机器码，是 JVM 的指令集
```

一个 `.java` 文件里有几个类，就产出几个 `.class` 文件。`.class` 的**目录结构必须对应包名**：

```java
包名 com.example.navigation_menu.config
      │
      │ 目录结构与包名一一对应
      ▼
com/example/navigation_menu/config/RedisConfig.class
```

> ⚠️ **这不是约定俗成，是硬规则**。类加载器找类时，是把包名翻译成路径去 classpath 里查的。路径对不上，类就找不到。这也是为什么 Java 源码必须放在与包名同构的目录下。

> 🔑 **关键洞察**：`.class` 里存的不是给 CPU 看的指令，是给 JVM 看的指令。JVM 在运行时把这些指令翻译成当前平台的机器码（即时编译，JIT）。**"跨平台"的代价，就发生在这里——多了一层运行时翻译。**

### 第二层：.class + 资源 → jar

jar 的本质**就是一个 zip**，只是后缀不同。用任何解压工具都能打开它。它和普通 zip 的唯一结构性区别，是内部多了 `META-INF/MANIFEST.MF`。

一个 jar 里能装什么，由打包的人决定，不挑：

| 装什么 | 举例 |
|--------|------|
| 字节码 | `com/example/navigation_menu/*.class` |
| 资源文件 | `application.yaml`、mapper XML、静态资源 |
| 元数据 | `META-INF/MANIFEST.MF`、`META-INF/maven/**/pom.xml` |
| 别的 jar | fat jar 的 `BOOT-INF/lib/` 里躺着 72 个依赖 jar |

**本项目实测**：`NavigationMenu-0.0.1-SNAPSHOT.jar` 共 246 个条目，内部结构是

```java
NavigationMenu-0.0.1-SNAPSHOT.jar（42.9 MB，246 条）
│
├── META-INF/            9 条    ← 元数据，含 480 字节的 MANIFEST.MF
│
├── BOOT-INF/          122 条
│   ├── classes/        47 条    ← 你自己的 .class + application.yaml
│   └── lib/            72 条    ← 72 个依赖 jar，嵌套在这里
│
└── org/               115 条    ← Spring Boot 自己的 JarLauncher 等
```

> ⚠️ **注意这个布局**：你自己的类**不在** `com/example/...` 这种标准位置，而是被挪进了 `BOOT-INF/classes/` 下面。这一点后面会引出一个关键问题。

### 第三层：jar → JVM（真正的入口是 classpath）

**JVM 不认识 jar。** JVM 的输入从来只有一个东西：**classpath**——一份"去哪儿找类"的清单。jar 只是这份清单上的一项。

`java -jar app.jar` 这条命令里，真正干活的是 **java 启动器**：

```java
java -jar app.jar
      │
      ▼
java 启动器：打开 jar → 读 META-INF/MANIFEST.MF → 取 Main-Class
      │
      ▼
把 jar 塞进 classpath
      │
      ▼
启动 JVM，加载并调用 Main-Class 的 main()
```

> 🔑 **一句话：`java -jar` 是"启动器"的工作，不是 JVM 的工作。JVM 从头到尾只知道 classpath。**

### MANIFEST.MF：jar 的说明书

它不在项目目录里，在 **jar 内部**，而且**不是你手写的**——是构建工具现场生成的。证据是它自己的第一行：`Created-By: Maven JAR Plugin 3.4.2`。

本项目 fat jar 的清单内容：

```java
Manifest-Version: 1.0
Created-By: Maven JAR Plugin 3.4.2
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.example.navigation_menu.NavigationMenu
Spring-Boot-Classes: BOOT-INF/classes/
Spring-Boot-Lib: BOOT-INF/lib/
```

这里藏着两件容易踩坑的事：

**一、`Main-Class` 不是你的类。** 它指向 Spring Boot 的 `JarLauncher`。JVM 启动的是它，不是你写的 `NavigationMenu`。

**二、`Start-Class` 不是 Java 标准字段。** Java 规范里清单只有 `Main-Class`。`Start-Class` 是 Spring Boot 自己发明的私有属性，标准 JVM 和标准启动器看到它也当没看见。

> ⚠️ **关键细节**：清单的作用随用法而变。
> 当作 classpath 项用（`java -cp app.jar 你的主类`）→ **不需要清单**，没有也能跑。
> 用 `java -jar` 启动 → **必须有清单，且必须有 `Main-Class`**。
>
> 本项目旁边那个 thin jar（`.jar.original`）就验证了这一点：它的清单文件存在，但没有 `Main-Class` 那一行，所以 `-cp` 能用，`-jar` 会直接报 `no main manifest attribute`。

### Spring Boot 为什么多套一层 JarLauncher

对比同一个类在两个 jar 里的位置：

| | 顶层结构 | `NavigationMenu.class` 在哪 |
|:---|:---|:---|
| thin jar（53 条） | `com/`（45）`META-INF/`（7）`application.yaml`（1） | `com/example/navigation_menu/`  ← 标准位置 |
| fat jar（246 条） | `BOOT-INF/`（122）`org/`（115）`META-INF/`（9） | `BOOT-INF/classes/com/example/navigation_menu/`  ← 非标准 |

标准类加载器找类，是去 classpath 根目录下查 `com/example/navigation_menu/NavigationMenu.class`。而 fat jar 的根目录下**压根没有 `com/` 这个目录**。

所以 `JarLauncher` 不是装饰，是**来接管类加载的**：它得自己认识 `BOOT-INF/classes/` 和 `BOOT-INF/lib/*.jar` 这套布局，把嵌套在里面的 72 个依赖 jar 一并管起来。

启动链路完整展开：

```java
Main-Class = JarLauncher
      │
      ▼
JVM 启动，调用 JarLauncher.main()
      │
      ▼
JarLauncher 干活：
      ├── 读 Start-Class → com.example.navigation_menu.NavigationMenu
      ├── 建一个认识 BOOT-INF/ 布局的类加载器
      └── 反射调用 NavigationMenu.main(args)
      │
      ▼
NavigationMenu.main() → SpringApplication.run(...)
```

> 🔑 **为什么要自找麻烦？** 因为要把"你的类"和"依赖的 jar"在物理上分开。全平铺在根目录的话，几百个 class 和几百个 jar 混成一团，分不清归属，也没法做分层（Docker 镜像的 `layers.idx` 就靠这个做缓存）。

### 按需加载：不是一次性跑完一堆 class

"JVM 运行 jar 里的多个 class"这个说法容易让人误以为启动时把 246 个条目全读进来。实际不是，类加载是**惰性**的：

```java
JVM 启动
      │
      ▼
ClassLoader 手上只有一份 classpath 清单，什么都没加载
      │
      ▼
程序执行到「第一次用到某个类」
      │
      ▼
按「包名 → 目录」去 classpath 里查出那个 .class
      │
      ▼
加载 → 链接（验证 / 准备 / 解析）→ 初始化
      │
      ▼
用到下一个类 → 重复
```

本项目里，如果某次请求命中了 Redis 缓存，`buildMenuTree` 直接 return，那么 `MenuTreeBuilder` 这个类**可能从头到尾没被加载过**。

> ⚠️ **这解释了日常两个现象**：少一个依赖时可能启动很久之后才报 `NoClassDefFoundError`，因为那行代码第一次执行才触发加载；静态代码块 `static {}` 什么时候跑，取决于那个类什么时候第一次被碰到，而不是启动顺序。

---

## 📊 应用与对比

### thin jar vs fat jar

同一个项目，Maven 一次打包产出两个东西：

| 维度 | thin jar（`.jar.original`） | fat jar（`NavigationMenu-0.0.1-SNAPSHOT.jar`） |
|------|---------------------------|---------------------------------------------|
| 条目数 | 53 | 246 |
| 大小 | 只有自己的类 | 42.9 MB |
| 顶层结构 | `com/` `META-INF/` `application.yaml` | `BOOT-INF/` `org/` `META-INF/` |
| 依赖 | 一个都没有 | 72 个，嵌在 `BOOT-INF/lib/` |
| 有无 `Main-Class` | ❌ 没有 | ✅ 有，指向 `JarLauncher` |
| 能 `java -jar` | ❌ 报 no main manifest attribute | ✅ 能 |
| 能 `java -cp` | ✅ 能（但会缺依赖报错） | ❌ 类在非标准位置 |

> 🔑 **thin jar 是打包过程中的中间产物**，Maven 打完它之后，Spring Boot 插件再把它 repackage 成 fat jar，原来的留在旁边加个 `.original` 后缀。正常你只需要关心 fat jar。

### 四种运行方式对比

| 方式 | 产物 | 依赖从哪来 | 适用场景 |
|------|------|-----------|---------|
| `java -cp target/classes 主类` | 编译目录，不打包 | classpath 参数指定 | 本地调试 |
| `java -jar xx.jar`（thin） | thin jar | 无，会报错 | 不适用 |
| `java -jar xx.jar`（fat） | fat jar | 内嵌的 `BOOT-INF/lib/` | 传统部署 |
| `docker run 镜像` | 镜像 | 镜像里连 JVM 一起打包 | 现代部署 |

### 常见报错及排查

| 报错 | 原因 | 解法 |
|------|------|------|
| `no main manifest attribute` | 清单里没有 `Main-Class` | 用 fat jar；或检查打包插件配置 |
| `ClassNotFoundException` | classpath 里没有这个类 | 确认依赖是否打进包、包名与目录是否对应 |
| `NoClassDefFoundError` | 类加载过或用过了，但依赖缺失 | 检查 `BOOT-INF/lib/` 里有没有那个 jar |
| `NoSuchMethodError` | 依赖版本冲突，加载到了旧版本 | `mvn dependency:tree` 查冲突 |
| `UnsupportedClassVersionError` | JVM 版本低于编译时的目标版本 | 换 JRE，或降低 `--release` |

> ⚠️ **`ClassNotFoundException` 和 `NoClassDefFoundError` 的区别值得记住**：前者是"第一次找就没找到"，后者是"找是找到过/不找了，但初始化或链接阶段出了问题"。排查思路完全不同。

---

## 🔥 面试要点

### 高频问题 + 标准答案

> **Q1：Java 源码是怎么变成能跑起来的东西的？**
>
> 三步走。`javac` 把每个类编译成一个 `.class` 字节码文件；打包工具把 `.class` 和资源文件收进一个 jar（本质是 zip，附带一份 MANIFEST.MF）；运行时由 java 启动器读清单找到入口类，JVM 加载并执行。
>
> 关键在中间那步的产物是**字节码**而不是机器码——这正是 Java 能跨平台的原因。

> **Q2：JVM 是怎么运行一个 jar 的？**
>
> 严格说 JVM **不认识 jar**。JVM 的输入只有 classpath，jar 只是 classpath 上的一项。`java -jar` 里头干活的是 java 启动器：打开 jar、读 MANIFEST.MF 取 `Main-Class`、把 jar 塞进 classpath，然后才启动 JVM。
>
> 反证很直接：清单里少了 `Main-Class` 那一行，`java -jar` 立刻报 `no main manifest attribute`。同一个 JVM、同样合法的 jar，就是起不来。

> **Q3：Spring Boot 打的 jar 为什么能直接跑？它和你写的 main 类是什么关系？**
>
> Spring Boot 在清单里做了手脚：把 `Main-Class` 指向自己的 `JarLauncher`，把你真正的启动类写进它自定义的 `Start-Class`。JVM 启动的是 `JarLauncher`，再由它反射调用你的 main 类。
>
> 多这一层是因为 Spring Boot 把类和依赖挪到了 `BOOT-INF/` 下面，标准类加载器找不到，所以必须自带一个认识这套布局的类加载器。

> **Q4：JVM 启动时会把 jar 里所有 class 都加载进来吗？**
>
> 不会。类加载是**惰性**的，用到哪个才加载哪个，加载后还要走链接（验证、准备、解析）和初始化，才轮得到执行。
>
> 所以少一个依赖时，可能启动很久之后才报 `NoClassDefFoundError`——因为那行代码第一次被执行才触发加载。静态代码块什么时候执行，同样取决于类什么时候第一次被碰到。

> **Q5：MANIFEST.MF 是干什么的？必须要有吗？**
>
> 它描述"这个包该怎么被使用"：入口类是哪个、依赖在哪、版本是什么。
>
> 是否必须有，看你怎么用它。当 classpath 项用（`java -cp`），不需要；用 `java -jar` 启动，必须有，而且必须有 `Main-Class`。

### 关键词速记

| 关键词 | 一句话 |
|--------|--------|
| 字节码 | JVM 的指令集，不是 CPU 的指令集 |
| jar | 本质是 zip，靠 MANIFEST.MF 区别于普通压缩包 |
| classpath | JVM 唯一认识的输入，一份"去哪找类"的清单 |
| Main-Class | 启动入口，JVM 只认这一个字段 |
| Start-Class | Spring Boot 私有的自定义字段，不是 Java 标准 |
| JarLauncher | Spring Boot 自带的类加载器接管者，为 `BOOT-INF/` 布局而生 |
| 惰性加载 | 用到才加载，不是启动时全量加载 |

---

## 🧠 记忆口诀

```java
记三层：
  javac   →  .class        （字节码，不是机器码）
  打包装  →  jar           （本质是 zip）
  启动    →  classpath     （JVM 唯一认的东西）

记两句话：
  JVM 不认识 jar，只认识 classpath
  没有 Main-Class，java -jar 直接报错

记一个坑：
  Main-Class ≠ 你的启动类（Spring Boot 里是 JarLauncher）
  Start-Class = 你的启动类（Spring Boot 私有字段）
```

---

## 🔄 知识闭环

### 从源码到运行在本项目中的完整链路

```java
NavigationMenu.java
      │  mvn package
      │  （javac 编译 → maven-jar-plugin 打包 → spring-boot 插件 repackage）
      ▼
target/NavigationMenu-0.0.1-SNAPSHOT.jar       42.9 MB / 246 条
      │  Dockerfile: COPY --from=build ... app.jar
      ▼
镜像 navigation-menu:local                      351 MB
      │  docker compose up -d --wait
      ▼
容器主进程：java -XX:MaxRAMPercentage=75.0 -jar app.jar
      │  JarLauncher 读清单 → 反射调用
      ▼
NavigationMenu.main() → SpringApplication.run()
      │
      ▼
MenuController.buildMenuTree() → 缓存 / 责任链 → 返回菜单树
```

### 核心链路回顾

```java
源码 .java
      │ javac
      ▼
.class 字节码  ──┐
资源文件       ──┼──►  jar（zip + MANIFEST.MF）
依赖 jar       ──┘
      │ java 启动器读清单
      ▼
Main-Class（本项目是 JarLauncher）
      │
      ▼
JVM + ClassLoader 按需加载 .class
      │  （加载 → 链接 → 初始化）
      ▼
你的 main() 开始执行
```

### 与其他技术专题的关系

| 专题 | 在链路中的位置 | 解决什么 |
|------|--------------|---------|
| [技术专题-Spring依赖注入详解](./技术专题-Spring依赖注入详解.md) | `SpringApplication.run()` 内部 | 谁创建对象、谁注入依赖 |
| [技术专题-责任链模式](./技术专题-责任链模式.md) | 容器启动完成之后 | 解耦 5 步硬编码流水线 |
| [技术专题-Redis缓存](./技术专题-Redis缓存.md) | Service 层前置 | 挡住大部分请求 |
| [技术专题-树形结构构建方案](./技术专题-树形结构构建方案.md) | TreeBuildHandler 内部 | O(n) 把平铺数据组树 |
| [技术专题-Mockito单元测试](./技术专题-Mockito单元测试.md) | 开发阶段 | 验证上面这些逻辑 |
| **本篇：编译打包与运行机制** | **承载上面所有东西的地基** | **从源码到进程，中间发生了什么** |

> 🔑 **本篇是地基**：上面那五个专题讲的是"程序跑起来之后怎么组织"，本篇讲的是"程序怎么才能跑起来"。字节码负责跨平台，jar 负责打包分发，MANIFEST 负责指定入口，classpath 负责定位类，类加载器负责按需装载——四环扣死，最后一环交到 `main()` 手上，另外五个专题才有机会登场。

**这套设计的本质**：所有环节都在做同一件事——**把「跑什么」和「在哪跑」拆开**。字节码拆开了平台，JVM 拆开了操作系统，jar 拆开了散落的文件，Docker 镜像又拆开了运行环境。每拆一层，产物就更可搬运一点，代价是多一层要理解的东西。你从 `docker run` 一路往下钻到 `.class`，看到的其实是同一条主线上的四层拆解。
