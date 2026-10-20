# 工程实践：GHCR 镜像仓库

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

> <span style="color:red">**GHCR 是 CI 和 CD 之间的交接点：镜像推到这里长期存着，目标机器才能按地址拉走。**</span>

---

## 🎯 背景与问题

### 没有镜像仓库时是什么样

```java
本地 docker build 出镜像
      │
      ▼
docker save 打成 tar（350 MB）
      │
      ▼
scp 传到目标机器
      │
      ▼
docker load 解回去
      │
      ▼
docker run 起容器
```

### 核心痛点

| 痛点 | 表现 |
|------|------|
| 产物没有地址 | 只能靠人搬，机器越多搬的次数越多 |
| 无法追溯 | 目标机上那个镜像到底由哪次提交编出来，说不清 |
| 存储错位 | git 仓库为文本和 diff 设计，塞几百 MB 二进制会让 clone 变得很慢 |
| 无法回滚 | 上一个版本已被覆盖，想退回去只能重新构建 |
| 传输无声 | 传输出错没人知道，直到容器起不来 |

> 🔑 **镜像仓库要解决的核心问题是"给产物一个地址"**。有了地址，传输、共享、回滚、追溯全都变成寻址问题。

---

## 💡 核心目的

给构建产物一个**长期存活、可按地址寻址、可做权限控制**的存放点。

这三个词对应 CI 的三道坎：构建它的机器几分钟后就销毁（所以要长期存活）；产物得有唯一标识才能被别的机器找到（所以要可寻址）；不是所有人都该能推能拉（所以要权限控制）。

---

## ⚙️ 原理与机制

### ghcr.io 不是网站

它是一个 **Docker Registry v2 协议端点**，不是网页。浏览器打不开，只能用 docker 命令或 HTTP API 访问：

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" https://ghcr.io/v2/
# 401 —— 站点活着，只是在问"你的凭据呢"
```

> ⚠️ **所以"登录 GHCR 网站"这个说法不成立**：GHCR 没有自己的站点，也没有第二套账号。看镜像在 github.com，用现有 GitHub 账号；操作镜像用 docker 命令。

### 镜像地址的三段结构

```java
<registry> / <owner>        / <image>          : <tag>

docker.io  / library        / mysql            : 8.0        ← registry 可省，它是默认值
ghcr.io    / zzz-jjj-hhh    / navigation-menu  : 51e95b8fc  ← 不可省，省了就推到 Docker Hub
```

> 🔑 **镜像的完整名字本身就是地址**。这也解释了为什么 `docker/login-action` 需要显式 `registry:` 参数（登录跟具体镜像无关），而 `docker/build-push-action` 不需要——要推到哪里，已经写在 `tags:` 里了。

### tag 可变，digest 不可变

```java
   tag（可变，给人看）                  digest（不可变，给机器算）
┌──────────────┐
│   :latest    │──┐
└──────────────┘  │                  ┌──────────────────────┐
                  ├────────────────▶ │ sha256:dc6519034...  │
┌──────────────┐  │                  └──────────────────────┘
│ :51e95b8fc.. │──┘
└──────────────┘
```

同一个 tag 可以反复指向不同内容，`latest` 最典型。digest 是内容的哈希，一旦产生就永远指向那一份内容。

> ⚠️ **部署用 tag 定位，用 digest 校验**。只用 `latest` 的部署不可复现——"你现在跑的是什么"取决于"你什么时候拉的"。

### 镜像存在 GHCR，不存在 git 仓库里

```java
      源码在 git 仓库                       镜像在 GHCR
      ┌──────────────┐                     ┌──────────────┐
      │  src/        │                     │  :51e95b8fc  │
      │  pom.xml     │  ──── 构建 ────▶    │  :latest     │
      │  Dockerfile  │                     │  350 MB      │
      └──────────────┘                     └──────────────┘
             │                                    ▲
             │                                    │
             └── 工作流用 GITHUB_TOKEN 推送 ───────┘
                  两边自动建立关联
```

`git clone` 下来不会有镜像——两套完全独立的存储。git 仓库存文本，registry 存二进制，支持分层存储与按 tag/digest 寻址。

### 包名由 tags 决定，不由仓库推断

**这是最容易误解的一点**：决定镜像挂在哪个包下的，是你在 `tags:` 里写的地址。

```yaml
tags: ghcr.io/zzz-jjj-hhh/navigation-menu:${{ github.sha }}
#     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
#     registry + owner + 包名，全部写死在这里
```

写成 `ghcr.io/zzz-jjj-hhh/whatever` 就是另一个包。仓库页面的 Packages 侧边栏，本质是"凡是用本仓库 `GITHUB_TOKEN` 推的包，都在这建一个索引入口"——关联由推送动作自动建立，但**名字是你指定的**。

### 推送与拉取的认证模型

| 动作 | 用什么凭据 | 说明 |
|------|-----------|------|
| 推送 | 工作流里的 `GITHUB_TOKEN` | 需 `permissions: packages: write`，无需人工配置 |
| 拉取（公开包） | 不需要 | 匿名取 token 即可 |
| 拉取（私有包） | `docker login ghcr.io` | 用 PAT，或 `GITHUB_TOKEN`（需 `packages: read`） |

工作流结束后 `docker/login-action` 会自动登出，日志里能看到：

```java
Post 登录 GHCR
[command]/usr/bin/docker logout ghcr.io
Removing login credentials for ghcr.io
```

托管 runner 是"用完即焚"的环境，不会残留凭据。

---

## 📊 应用与对比

### 本项目怎么用

| 环节 | 命令 / 配置 |
|------|-----------|
| 推送 | `tags: ghcr.io/zzz-jjj-hhh/navigation-menu:${{ github.sha }}` |
| 拉取 | `APP_IMAGE=ghcr.io/...:${{ github.sha }} docker compose pull app` |
| 本地改回自构建 | 不设 `APP_IMAGE`，回落到 `navigation-menu:local` |

用 commit SHA 而不是只用 `latest` 作 tag，是为了让任何运行中的容器都能被追溯到确切的提交。

### 查看镜像的三个层次

**第一层：账号级页面**

```
https://github.com/<用户名>?tab=packages
```

列出该账号名下**全部** package，不区分来源仓库，可按类型（Container / npm / Maven 等）筛选。仓库页面的 Packages 侧边栏是**仓库视角**的入口，只显示从这一个仓库推上去的包——多仓库场景要用账号级入口。组织则是 `github.com/orgs/<组织名>/packages`。

**第二层：REST API**

```bash
gh api "/user/packages?package_type=container"
gh api "/users/<用户名>/packages?package_type=container"
```

实测需要 `read:packages` scope，缺少会返回：

```
gh: You need at least read:packages scope to list packages. (HTTP 403)
```

补上要走一次浏览器授权：`gh auth refresh -h github.com -s read:packages`。

> 🔑 **推送不受这个影响**。推送用的是工作流里临时发放的 `GITHUB_TOKEN`，它有 `packages: write`。缺 scope 的只是本地 gh 凭据。

**第三层：registry 协议——走不通**

```java
https://ghcr.io/v2/          → 401      活着，只是在要凭据
https://ghcr.io/v2/_catalog  → 无响应    GHCR 没有实现这个端点
```

`_catalog` 在 Docker Registry v2 规范里是**可选**的，GHCR 选择不实现。所以在镜像仓库这一层没法枚举自己有哪些镜像，只能绕到 GitHub 那一侧。GHCR 也不存在"浏览全站镜像"的入口——镜像仓库不是公开索引。

### 可见性：一次实测纠偏

「包只继承仓库的访问权限，不继承可见性」——这句话本身来自官方文档，但它**推不出"包默认私有"**。

本项目实测结果是**公开的**，匿名就能取到 token 并列出 tag：

```powershell
$tok = (curl.exe -s "https://ghcr.io/token?scope=repository:zzz-jjj-hhh/navigation-menu:pull&service=ghcr.io" | ConvertFrom-Json).token
curl.exe -s -H "Authorization: Bearer $tok" "https://ghcr.io/v2/zzz-jjj-hhh/navigation-menu/tags/list"
# {"name":"zzz-jjj-hhh/navigation-menu","tags":["4862518bedbb...","latest","51e95b8fc93d..."]}
```

> ⚠️ **这条纠正了一个真实踩过的坑**。按"默认私有"的推断，会给 deploy job 加一步不必要的登录；反过来，哪天真变私有而你没加，报错信息会指向"镜像不存在"，很容易往错的方向排查。**涉及默认值的行为，别凭文档断言，跑一条命令验证。**

副作用也要知道：包公开意味着镜像内容（jar 和里面的 `application.yaml`）任何人可下载。本项目 `application.yaml` 里有 `root123` / `redis123` 这类本地开发默认口令，风险很低但确实对外可见。

### provenance：镜像自带的出身证明

`docker/build-push-action` 默认附加 SLSA provenance 证明，可以直接从 GHCR 拉下来：

```json
"vcs:source":   "https://github.com/ZZZ-JJJ-HHH/NavigationMenu",
"vcs:revision": "51e95b8fc93db73e384b43ec57a0369976cb739f"
```

同一份证明里还记录基础镜像：

```json
{ "uri": "pkg:docker/eclipse-temurin@17-jre?platform=linux%2Famd64" }
```

这解释了一个看着奇怪的现象：**两次构建产出的镜像内容完全相同（同一个镜像 ID），manifest digest 却不同**。因为证明里嵌了本次运行的 run id 和时间戳，两次运行的 index 内容不一样。镜像本体相同，manifest 不同。

### 与 Docker Hub 对比

| 维度 | GHCR | Docker Hub |
|------|------|-----------|
| 地址 | `ghcr.io/owner/name`，不可省略 | `docker.io/owner/name`，默认值可省 |
| 认证 | 工作流内用 `GITHUB_TOKEN`，无需额外账号 | 需单独注册并配 secret |
| 与代码的关系 | 包与仓库自动关联，权限可继承 | 无关联 |
| 公开包费用 | 免费，传入流量也免费 | 免费额度有限制 |
| 国内拉取 | 实测直连可达 | 常需镜像加速器 |

### 容量与计费

官方口径：GitHub Packages **对 public package 完全免费，传入流量也免费**；私有包才吃存储和流量配额。另有一条对 CI 有利：用 `GITHUB_TOKEN` 从 Actions 里下载包，不计入仓库所有者的流量额度。

---

## 🔥 面试要点

### 高频问题 + 标准答案

> **Q1：镜像仓库是干什么的？为什么 CI 里构建完还要推一次？**
>
> 镜像仓库解决的是"给产物一个地址"。CI 的机器是随叫随到的干净机器，跑完就销毁，构建出的镜像会跟着一起消失。推送到镜像仓库后，产物有了长期存活的位置和一个唯一地址，别的机器才能按地址拉走。
>
> 只构建不推送，等于只有验证没有交付——链条断在半路。

> **Q2：tag 和 digest 有什么区别？为什么生产不建议只用 latest？**
>
> tag 是可变的人类标签，同一个 tag 可以反复指向不同内容；digest 是内容的哈希，一旦产生就永远指向那一份内容。
>
> 只用 `latest` 的部署不可复现——"你现在跑的是什么"取决于"你什么时候拉的"，回滚和排查都没有锚点。实践是**推送时同时打 commit SHA 和 latest 两个 tag**，部署用 SHA 精确定位，人看起来也方便。

> **Q3：怎么保证线上跑的就是某一次提交编出来的镜像？**
>
> 两层。第一层，tag 用 commit SHA，从镜像名就能反查提交。第二层，`docker/build-push-action` 默认附加 SLSA provenance 证明，里面记录 `vcs:source`（仓库地址）和 `vcs:revision`（commit SHA），可以直接从 registry 拉下来验证。这是"出身证明"，不靠约定俗成。

> **Q4：GHCR 和 Docker Hub 有什么区别？**
>
> 两者都是符合 Docker Registry v2 协议的镜像仓库，差别在权限模型和运维成本。Docker Hub 需要单独注册账号、把凭据存成 secret；GHCR 可以直接用工作流里自动注入的 `GITHUB_TOKEN` 推送，包还会自动关联到仓库并继承权限。GHCR 对公开包免费。
>
> 另一个实践差异：Docker Hub 在国内经常需要镜像加速器，而 GHCR 实测直连可达。

### 关键词速记

| 关键词 | 一句话 |
|--------|--------|
| 交接点 | 镜像仓库是 CI 和 CD 之间唯一必需的中转 |
| 三段地址 | `registry/owner/image:tag`，registry 是"去哪"，tag 是"哪一份" |
| 不可省 | `docker.io` 能省是因为它是默认值，`ghcr.io` 不能省 |
| tag vs digest | tag 给人看、可变；digest 给机器算、不可变 |
| `_catalog` | GHCR 未实现，没法在协议层枚举镜像 |
| provenance | 镜像自带的出身证明，记录来源仓库和 commit |

---

## 🧠 记忆口诀

```java
记一个定位：
  GHCR 是 CI 和 CD 的交接点
  「构建它的机器几分钟后消失」——这就是它存在的理由

记三段地址：
  registry / owner / image : tag
  docker.io 可省（默认值）
  ghcr.io  不可省

记两个身份：
  tag    可变，给人看
  digest 不可变，给机器校验

记两套存储：
  git 仓库  —— 文本：源码、Dockerfile、配置
  registry  —— 二进制：镜像，按 tag / digest 寻址

记一条实测原则：
  涉及默认值的行为，别凭文档断言，跑一条命令验证
```

---

## 🔄 知识闭环

### 一次 push 之后镜像的完整旅程

```java
git push
      │
      ▼
job: image（GitHub 托管 runner）
      │
      ├── buildx 构建  ──▶  350 MB 镜像
      │
      ├── docker login ghcr.io（GITHUB_TOKEN）
      │
      └── push ghcr.io/zzz-jjj-hhh/navigation-menu:51e95b8fc...
                        │
                        ▼
        ┌───────────────────────────────┐
        │  GHCR —— 长期存活，可按地址寻址  │
        │  tag    : 51e95b8fc / latest   │
        │  digest : sha256:dc6519034...  │
        │  附带 provenance：来源仓库+提交  │
        └───────────────────────────────┘
                        │
                        ▼
job: deploy（本机 self-hosted runner）
      │
      └── docker compose pull app
          docker compose up -d --wait
          curl /actuator/health
                        │
                        ▼
                服务在 8080 上跑起来
      │
      ▼
runner 机器跑完销毁，镜像留在 GHCR 里
```

### 与其他技术专题的关系

| 专题 | 在链路中的位置 | 解决什么 |
|------|--------------|---------|
| [工程实践-Java编译打包与运行机制](./工程实践-Java编译打包与运行机制.md) | 镜像内部 | 从源码到进程发生了什么 |
| [工程实践-CI/CD](./工程实践-CI-CD.md) | 整条流水线 | 什么时候自动跑、产物送到哪 |
| **本篇：GHCR 镜像仓库** | **CI 与 CD 的交接点** | **镜像存在哪、怎么寻址、谁能拉** |
| [工程实践-self-hosted-runner](./工程实践-self-hosted-runner.md) | deploy job 的执行环境 | 任务在谁的机器上跑 |

### 一句话串起整条链

**CI 让"代码是好的"有了自动化证据，CD 让"好的代码"自动变成能访问的服务——而两者之间必须有一个镜像仓库，因为构建它的那台机器几分钟后就会消失。**
