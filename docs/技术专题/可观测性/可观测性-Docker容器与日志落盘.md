# 可观测性：Docker 容器与日志落盘

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [app 在项目里的三个身份](#app-在项目里的三个身份)
  - [root 和普通用户是 Linux 的概念](#root-和普通用户是-linux-的概念)
  - [容器里不只有你的应用](#容器里不只有你的应用)
  - [容器不是虚拟机](#容器不是虚拟机)
- [📊 应用与对比](#应用与对比)
  - [为什么必须先建目录并 chown](#为什么必须先建目录并-chown)
  - [为什么还要建组](#为什么还要建组)
  - [日志文件写在容器自己的文件系统里](#日志文件写在容器自己的文件系统里)
  - [卷：让日志活过容器](#卷让日志活过容器)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**容器是一个 Linux 进程加一层隔离，所以"进程以谁的身份跑、日志文件归谁所有"仍然是 Linux 权限模型在管，Docker 只是提供了写这些规则的地方。**</span>

---

## 🎯 背景与问题

### 加了文件日志之后，为什么写不进去

`logback-spring.xml` 里给日志配了一个文件出口，路径是 `/app/logs/app.log`。改完 Dockerfile 之后发现还必须加两行：

```dockerfile
RUN mkdir -p /app/logs && chown -R app:app /app
USER app
```

为什么不加就不行？这要从"容器到底是什么"说起。

### 一个常见的误解

> "Docker 不就是用来跑应用的吗，为什么要管用户和组？"

理解没错，但少了一件关键的事：**在 Linux 上"跑"这个动作，必然带一个身份问题**。任何进程都必须以某个用户身份运行，没有例外。Docker 没有发明"用户"这个概念，它只是把应用搬进了 Linux 原本就有的规则里。

---

## 💡 核心目的

把下面五件事一次说清，之后看任何 Dockerfile 的权限相关写法都不会再懵：

1. 容器里的"用户"是什么
2. 为什么默认是 root，为什么要改
3. 日志文件到底写在哪个文件系统里
4. 容器和虚拟机的区别在哪
5. 挂卷解决的是什么问题

---

## ⚙️ 原理与机制

### app 在项目里的三个身份

最容易晕的是命名撞车。同一个 `app` 出现在三个地方，指的是三件毫不相干的事：

| 出现的地方 | 是什么 | 作用 |
|:---|:---|:---|
| `docker-compose.yml` 里的 `services: app:` | **服务名**，Docker 据此生成容器名 `navigation-menu-app-1` | 只是标签，方便 `docker compose logs app` 指代 |
| `Dockerfile` 里的 `useradd -r -g app app` | **Linux 用户**，uid 999 | 决定容器里的进程以什么身份运行 |
| `/app` 目录、`app.jar` | **应用程序**本身 | 你的 Spring Boot 应用 |

> 🔑 **它们只是恰好同名**。容器叫什么名字，跟里面进程以什么用户运行毫无关系。换成 `services: backend:` 加 `useradd -r -g svc svc`，机制一模一样。真正决定身份的是镜像里的 `USER` 指令。

### root 和普通用户是 Linux 的概念

Linux 是多用户系统，每个进程必须以某个用户身份运行，每个用户有数字 ID（uid）。

| 用户 | uid | 能干什么 |
|:---|:---|:---|
| root | 0 | 超级管理员，能读写任何文件、改任何权限 |
| 普通用户（app、ubuntu） | 999 / 1000 | 只能动自己有权限的文件 |

如果把这句话翻译成 Windows 的语境，就是**"用普通用户身份运行程序"和"以管理员身份运行"的区别**。Dockerfile 里这三行做的就是前者：

```dockerfile
RUN ... && groupadd -r app && useradd -r -g app app
#   ↑ 在镜像里建一个普通用户 app（uid 999）

RUN mkdir -p /app/logs && chown -R app:app /app
#   ↑ 建日志目录，并把 /app 的所有权交给 app

USER app
#   ↑ 从这行起，容器里的进程以 app 身份运行，不再用 root
```

实测确认（`docker compose exec app id`）：

```
uid=999(app) gid=999(app) groups=999(app)
```

> 🔑 **为什么要费这个劲**：应用以 root 跑，一旦被攻破，攻击者直接拿到容器里的 root，能读写容器里的一切；如果挂载了宿主机目录，危害还会溢出容器。这是行业安全基线，也是面试能讲的点：**容器里不要用 root 跑应用**。

> ⚠️ **这是安全基线，不是运行必需**。不写这几行，容器照样跑起来。写它们是为了把一个真实风险关掉，代价是 Dockerfile 多几行。

### 容器里不只有你的应用

`ls /` 的真实结果：

```
app  bin  boot  dev  etc  home  lib  lib64  media  mnt  opt  proc
root run  sbin  srv  sys  tmp  usr  var  __cacert_entrypoint.sh
```

因为 `FROM eclipse-temurin:17-jre` 是 Ubuntu 底子的镜像，里面是一整套 Linux 用户空间：

| 里面有什么 | 是什么 |
|:---|:---|
| `/bin` `/usr` `/lib` `/etc` `/var` 等 | Ubuntu 的基础文件系统 |
| `/opt/java/openjdk` | JRE（temurin 17） |
| `/app/app.jar` | 你的应用，44MB |
| `/etc/passwd` | Linux 的用户表，app 用户登记在这里 |
| PID 1 | `java -XX:MaxRAMPercentage=75.0 -jar app.jar` |
| 环境变量 | `DB_URL`、`REDIS_HOST`、`LANG` 等，来自 compose 的 environment |

`/etc/passwd` 里确实登记着那个用户：

```
root:x:0:0:root:/root:/bin/bash
ubuntu:x:1000:1000:Ubuntu:/home/ubuntu:/bin/bash
app:x:999:999::/home/app:/bin/sh
```

字段依次是 `用户名:密码占位:uid:gid:备注:家目录:登录shell`。顺带一个细节：家目录字段写着 `/home/app`，但 `ls -la /home` 里**只有 ubuntu，没有 app**，因为 `useradd -r` 没配 `-m`，只登记不建目录。

### 容器不是虚拟机

这里有个常见的推论需要纠正：**容器不是"一台独立的小电脑"**。

正确的一半是：**文件系统是独立的**。每个容器有自己的 `/bin`、`/usr`、`/etc`、`/app`，别的容器看不见你的 `/app/logs`。

不正确的一半是：**容器没有独立的 Linux 内核**。所有容器共用宿主机的内核。证据是在容器里执行 `uname -a`：

```
Linux a73e61cccdf0 6.18.54.1-microsoft-standard-WSL2 #1 SMP PREEMPT_DYNAMIC ... x86_64 GNU/Linux
```

这个内核版本是**宿主机的 WSL2 内核**，不是容器自己的。容器只是看到了一个被隔离过的视图。

| | 虚拟机 | 容器 |
|:---|:---|:---|
| 隔离层级 | 硬件级，靠 Hypervisor | 进程级，靠 namespace / cgroup |
| 内核 | 每台 VM 一个独立内核 | 全部共享宿主机内核 |
| 启动 | 几十秒 | 毫秒到秒 |
| 体积 | GB 级 | MB 级 |
| 里面有什么 | 一整套操作系统 | 一整套**用户空间**，但没有自己的内核 |

那份用户空间也不是每个容器独占一份磁盘拷贝。实测 `docker system df`：

```
TYPE            TOTAL     ACTIVE    SIZE
Images          9         4         1.986GB
Containers      5         3         35.47kB
```

**5 个容器的可写层加起来只有 35 kB**，而镜像是 1.986GB。镜像层只读、被所有容器共享，只有每个容器自己改动的那部分才单独存一份，这叫写时复制。

---

## 📊 应用与对比

### 为什么必须先建目录并 chown

`WORKDIR /app` 建出来的 `/app` 是 root 拥有的。如果直接以 `USER app` 启动，logback 想创建 `/app/logs` 时会因为权限不足失败，JSON appender 起不来，日志文件一个都不会有。

> 🔑 **关键是所有权，不是目录本身**。`chown -R app:app /app` 把 /app 交给了 app，理论上运行时也能自己建目录；提前 `mkdir` 的价值是行为确定，而且**将来在 `/app/logs` 上挂命名卷时，Docker 会用镜像里这个目录的内容和属主来初始化空卷**，预先建好并 chown，挂载后 app 用户才写得进去。

### 为什么还要建组

Linux 的权限判断是三级模型。每个文件带"属主 + 属组"和一组权限位，进程访问时内核依次判断：

```java
-rw-r--r--   1  app  app   app.log
 ↑↑↑ ↑↑↑ ↑↑↑
 属主 属组  其他人
```

- 你是属主，用第一组权限位
- 不是属主但在文件的属组里，用第二组
- 都不是，用第三组

所以组的本质是**一组用户的集合，用来批量授权**。这个 Dockerfile 里它被用到三处：`useradd -g app` 需要它存在、`chown app:app` 的冒号后面是它、进程创建的文件属组默认也是它。

> 🔑 **给服务一个专属组，是为了以后能按组授权**。比如将来加一个只读日志的账号，把它加进 app 组就能读 `/app/logs`，不用改文件属主。如果所有服务都扔在 `users` 或 `nogroup` 里，权限就混成一锅粥。

一个诚实的补充：**显式 `groupadd` 在这个镜像上不是必须的**。实测容器里 `/etc/login.defs` 有 `USERGROUPS_ENAB yes`，`/etc/default/useradd` 的注释也写明默认行为是"没指定 `-n` 和 `-g` 时，创建一个与用户名同名的组"。所以只写 `useradd -r app` 也会自动建组。

显式写出来有两个好处：

| 原因 | 说明 |
|:---|:---|
| 不依赖默认值 | `USERGROUPS_ENAB` 是发行版配置，换成 Alpine、distroless 行为可能不同 |
| 可读性 | 一眼看到"这里要有一个 app 组"，不用去翻 login.defs |

### 日志文件写在容器自己的文件系统里

`logback-spring.xml` 里是 `${LOG_PATH:-logs}/app.log`，相对路径，而 Dockerfile 里是 `WORKDIR /app`，所以实际落在容器内的 `/app/logs/app.log`：

```java
你的电脑
 │
 └── Docker Desktop 里的 Linux 虚拟机
        │
        └── 容器 navigation-menu-app-1（服务名 app）
               └── 容器自己的文件系统
                      └── /app/logs/app.log   ← JSON 日志写在这里
```

- 它**不在**你 Windows 的 C: 盘里，资源管理器里翻不到
- 它**在容器内部**，容器删掉就跟着没了
- 容器外的东西（Fluent Bit、或者你自己）要读到它，必须挂一个卷

查看方式：

```powershell
docker compose exec app ls -l /app/logs
docker compose exec app cat /app/logs/app.log
docker compose logs -f app          # 这条看的是 stdout 那一路
```

> ⚠️ **Dockerfile 是图纸，不是成品**。改完必须重新 `docker compose build app` 并重建容器才生效。实测：当前运行的容器和本地 `navigation-menu:local` 镜像里都还没有 `/app/logs`。

### 卷：让日志活过容器

| 方式 | 是什么 | 容器删掉之后 |
|:---|:---|:---|
| 不挂卷 | 文件只存在于容器自己的文件系统里 | 一起没 |
| 命名卷 named volume | Docker 管的一块存储，挂进容器 | 还在 |
| 绑定挂载 bind mount | 把宿主机的一个目录挂进容器 | 还在，而且宿主机上能直接看到 |

给 app 服务加一个命名卷：

```yaml
  app:
    volumes:
      - app-logs:/app/logs
```

这样日志落到卷里，容器重建不丢，Fluent Bit 挂同一个卷就能读。采集链路怎么搭，见 [可观测性-日志采集与Splunk](./可观测性-日志采集与Splunk.md)。

---

## 🔥 面试要点

**Q1：为什么容器里要用非 root 用户运行应用？**

> **面试话术**：*"容器本质上就是一个 Linux 进程加一层隔离，所以进程以什么身份运行还是 Linux 的权限模型在管。容器默认以 root 启动，一旦应用被攻破，攻击者直接拿到容器内的 root，能读写容器里的一切，挂载了宿主目录的话危害还会溢出容器。所以我一般会在 Dockerfile 里建一个非 root 用户，把应用目录和日志目录 chown 给它，再用 USER 切过去。"*

**Q2：容器和虚拟机有什么区别？**

> **面试话术**：*"虚拟机是硬件级虚拟化，每台 VM 有自己的内核，启动慢、体积大。容器是进程级隔离，靠 namespace 和 cgroup，所有容器共享宿主机内核，只带自己的一份用户空间，所以启动是秒级、体积是 MB 级。可以理解为容器里有一套完整的用户空间，但没有自己的内核。"*

**Q3：容器里写的文件在哪？重启会丢吗？**

> **面试话术**：*"写在容器自己的文件系统里，也就是镜像层之上那层可写的容器层。这层的生命周期和容器绑定，容器删掉就没了。要让数据活过容器，必须挂卷，命名卷由 Docker 管理，绑定挂载直接映射宿主机目录。日志场景我一般用命名卷，因为采集器挂同一个卷读就行。"*

---

## 🧠 记忆口诀

```
容器三句话：
  Docker 管打包和隔离，Linux 管身份和权限
  文件在容器自己的文件系统里，删容器就没了
  要活过容器，只有挂卷

再记两个数字感：
  镜像 1.9GB，5 个容器的可写层才 35kB（共享只读层）
  uname 看到的是宿主机内核，不是容器自己的
```

---

## 🔄 知识闭环

```java
现象：日志配了文件出口，容器里却写不进去
      │
      ▼
根因：进程以 app 身份跑，但 /app 是 root 的，也没建 logs 目录
      │
      ▼
要解决的四个概念：
  ① 用户     进程必须有身份，默认 root，要换成非 root
  ② 组       chown 是"用户:组"，组用来批量授权
  ③ 文件系统  容器有自己的一套，删容器就没了
  ④ 卷       挂上去，文件才活得过容器
      │
      ▼
落到 Dockerfile 就是三行：
  groupadd + useradd → mkdir + chown → USER app
      │
      ▼
出口：日志文件有了确定的落点之后，
      下一步是让容器外的人能读到它，见《可观测性-日志采集与Splunk》。
```
