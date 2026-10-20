# 工程实践：CI/CD

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [概念层的对应关系](#概念层的对应关系)
  - [与 Jenkins 的对应关系](#与-jenkins-的对应关系)
  - [runner 是一台随叫随到的干净机器](#runner-是一台随叫随到的干净机器)
  - [GitHub Actions 的上下文机制](#github-actions-的上下文机制)
  - [镜像产出到底有什么用](#镜像产出到底有什么用)
  - [完整的 CD 链条](#完整的-cd-链条)
  - [镜像 tag 的双重设计](#镜像-tag-的双重设计)
- [📊 应用与对比](#应用与对比)
  - [本项目的完整流水线](#本项目的完整流水线)
  - [什么时候才需要跑流水线](#什么时候才需要跑流水线)
  - [失败了怎么办](#失败了怎么办)
  - [认知纠偏：学习路上的五个典型误区](#认知纠偏学习路上的五个典型误区)
  - [测试质量决定了 CI 的能力上限](#测试质量决定了-ci-的能力上限)
  - [踩坑记录（真机验证）](#踩坑记录真机验证)
  - [三种部署落点对比](#三种部署落点对比)
- [🔥 面试要点](#面试要点)
  - [高频问题 + 标准答案](#高频问题--标准答案)
  - [关键词速记](#关键词速记)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**CI 是每次提交后自动验证，CD 是验证通过后自动送达。分开看是两件事，合起来是一条从 `git push` 到服务跑起来的流水线。**</span>

---

## 🎯 背景与问题

### 没有 CI/CD 时是什么样

一个功能写完，要上线，手工流程是这样的：

```java
本地改完代码
      │
      ▼
手动跑一遍测试（跑没跑全看记性）
      │
      ▼
手动 mvn package 打包
      │
      ▼
手动 scp 传到服务器
      │
      ▼
手动 java -jar 起进程（旧的先 kill 掉）
      │
      ▼
手动 curl 验证接口通不通
      │
      ▼
出问题 → 回滚 → 上面全部再来一遍
```

### 核心痛点

| 痛点 | 表现 |
|------|------|
| 靠人记得 | 测试跑没跑全、参数对不对，全凭当时的状态 |
| 环境不一致 | 我的机器上编译通过，服务器上 JDK 版本不对 |
| 反馈慢 | 写完到发现错误隔了几天，定位成本极高 |
| 发布恐惧 | 每次上线都是一次手工操作，越怕越容易出错 |
| 不可复现 | 出了问题没法精确还原当时的操作序列 |

> ⚠️ **"手工会出错"不是 CI/CD 要解决的核心问题，"反馈太慢"才是**。手工操作慢，会导致人倾向于攒一批改动再一起发布，而改动攒得越多，出问题时越难定位。CI/CD 的价值在于把这个循环从"几天一轮"压缩到"几分钟一轮"。

---

## 💡 核心目的

### CI 要解决的是"集成"的痛，不是"测试"的痛

CI 全称 Continuous Integration，**Integration（集成）才是关键词**。

它来自一个真实的历史痛点：几个人各自拉分支改了两周，最后合并那天冲突爆炸、互相覆盖、谁也说不清哪儿坏了。这个过程有个专门的说法叫 integration hell。

CI 的思路是把这个痛苦摊薄：别攒两周，每次提交就立刻验证一次，让问题在你还能记清自己改了什么的时候就暴露。

所以 CI 的目的有两层：

1. **验证这次改动本身**——能编译、测试能过
2. **验证这次改动没有把原先好用的东西弄坏**——防回归

> 🔑 **第二层通常更值钱**。人工 review 很难发现"改 A 坏了 B"这类问题，因为 review 的人只看得到 diff，看不到影响面。

### CD 要解决的是"交付"的痛

CI 跑绿之后，代码还在仓库里，没有变成用户能访问的服务。中间那段——打包、传上去、重启、验证——就是 CD 的活。

---

## ⚙️ 原理与机制

### 概念层的对应关系

| 概念 | 是什么 | 在哪定义 |
|------|--------|---------|
| workflow | 一条流水线的定义 | `.github/workflows/*.yml` |
| 触发条件 | 什么时候跑 | `on:` |
| job | 流水线里的一个阶段 | `jobs:` 下的一个键 |
| step | job 里的一步操作 | `steps:` |
| runner | 真正执行 job 的机器 | `runs-on:` |
| environment | 部署环境，可挂审批和密钥 | `environment:` |

### 与 Jenkins 的对应关系

| GitHub Actions | Jenkins | 说明 |
|----------------|---------|------|
| workflow | Jenkins Job / Pipeline | 一条流水线 |
| `.github/workflows/*.yml` | Jenkinsfile | 流水线的定义 |
| runner | Agent / Node | 干活的机器 |
| `runs-on:` | agent 标签 | 指定任务落到哪台机器 |
| `on: push` | Poll SCM / webhook 触发器 | 什么时候触发 |
| secrets | Credentials | 敏感信息 |

> 🔑 **GitHub Actions 本身不做任何事**，它只是执行器。真正干活的是 yml 文件里那些命令。这跟 Jenkinsfile 是一回事——配置方式从"在网页上点"变成了"仓库里放一个文件"。

### runner 是一台随叫随到的干净机器

这是理解 workflow 写法的一把钥匙。默认的 `runs-on: ubuntu-latest` 意味着：

```java
每次触发
      │
      ▼
GitHub 开一台全新机器
      │
      ├── 没有你的 JDK
      ├── 没有你的 MySQL / Redis
      ├── 没有你本地的 ~/.m2 依赖缓存
      └── 没有上一轮跑过的任何痕迹
      │
      ▼
照 yml 跑命令
      │
      ▼
跑完销毁
```

**这一条解释了 workflow 里两个看起来多余的设计**：为什么 CI 里要专门 `docker compose up -d --wait mysql redis`（空机器上没有数据库），为什么到处都在配 cache（不缓存就要每次都重新下载整个依赖树）。

### GitHub Actions 的上下文机制

三个 job 里用的 `${{ github.sha }}` 为什么是同一个？因为它们来自**同一次 workflow run 的全局上下文**。

```java
你执行 git push → 触发了一次 workflow run
                        │
                        ▼
                GitHub 创建一个 "run" 对象
                这个 run 有一个全局唯一的 github 上下文
                        │
                        │  里面有个字段叫 github.sha
                        │  值就是你刚才 push 的那个 commit 的完整 hash
                        │  从 run 创建到结束，这个值**永不改变**
                        │
           ┌────────────┼────────────┐
           ▼            ▼            ▼
        test job    image job    deploy job
           │            │            │
        读同一个     读同一个     读同一个
       github.sha  github.sha  github.sha
```

**GitHub Actions 的上下文（context）是 run 级别的，不是 job 级别的。** 整个 workflow run 只有一个 `github` 对象，所有 job 共享它。

`github` 上下文是 GitHub 内置提供的，不需要你配置：

```yaml
github:
  sha: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"   # 触发本次 run 的 commit 完整 hash（40 位）
  ref: "refs/heads/main"                            # 触发的分支
  event_name: "push"                                # 触发事件
  actor: "zzz-jjj-hhh"                              # 谁 push 的
  repository: "zzz-jjj-hhh/NavigationMenu"          # 仓库名
  # ... 还有几十个字段
```

因为 `github.sha` 在 image job 和 deploy job 里读出来是同一个值，image job 打的 tag `navigation-menu:a1b2c3d` 和 deploy job 拉的 tag `navigation-menu:a1b2c3d` 严丝合缝地对上——不需要任何显式传递。

### 镜像产出到底有什么用

先说镜像是什么：**把「应用 + 它需要的运行时」打成一个只读的盒子**。

Jar 不是能独立运行的东西——它需要特定版本的 JVM，运行时还要连 MySQL 和 Redis。镜像把这些一起冻住，于是同样的镜像在任何装了 Docker 的机器上行为都一样。"在我机器上能跑"这个问题就是这么消掉的。

为什么不直接传 jar？可以，老式 Java 部署就是这么干的。代价是目标机器上你得自己保证有正确的 JVM、正确的版本、正确的启动参数。镜像把「应用 + 运行时」一起交付，目标机只需要有 Docker。

> ⚠️ **"构建了镜像"和"交付了镜像"是两件事**。前者只是验证 Dockerfile 还能用，镜像构建完就丢了；后者需要把镜像推到镜像仓库，别的机器才拉得到。很多人以为 CI 里出现 `docker build` 就算有 CD 了，其实链条还断在半路。

### 完整的 CD 链条

```java
代码
      │  CI：编译 + 测试 + 构建镜像
      ▼
镜像
      │  推送到镜像仓库（GHCR / Docker Hub）
      ▼
镜像仓库
      │  目标机器拉取
      ▼
目标机器运行
```

四段缺一不可。本项目当前的实现正好把四段都走通了。

### 镜像 tag 的双重设计

`ci.yml` 里 image job 同时打了两个 tag：

```yaml
tags: |
  ghcr.io/zzz-jjj-hhh/navigation-menu:${{ github.sha }}   # 精确版本
  ghcr.io/zzz-jjj-hhh/navigation-menu:latest              # 方便性
```

| tag | 值举例 | 谁在用 | 特点 |
|---|---|---|---|
| `${{ github.sha }}` | `a1b2c3d4e5f6` | **deploy job** | 唯一、不可变、可追溯 |
| `latest` | 永远指向最近一次 push | 没人用（部署时故意不用） | 方便手动 pull，可变 |

deploy job 只用 commit sha：

```yaml
env:
  APP_IMAGE: ghcr.io/zzz-jjj-hhh/navigation-menu:${{ github.sha }}
```

**为什么不用 latest 部署？** 因为 latest 是可变标签，并发推送时会产生歧义：

```
时间线（假设只用 latest）：
  10:00  你 push commit A → latest 指向 A → image job 推送成功
  10:05  你 push commit B → latest 改为 B → 第二个 image job 推送成功
  10:06  deploy job 开始拉 latest
         → 拉到 A？还是 B？
         → 取决于第二个 image job 的 "latest 覆盖" 操作在 deploy 开始前有没有完成
         → 不确定 ❌
```

而 commit sha 是 git 产生的**全局唯一标识符**，一次 push 对应一个、永不改变。image job 打了 tag `abc123`，deploy job 就拉 `abc123`——不存在"还没改过来"这种情况。

latest tag 保留的理由：
- **手动排查问题时**：`docker run ghcr.io/...:latest` 快速拉最新版跑一下
- **文档示例**：`docker pull ghcr.io/...:latest` 比写一串 hash 好理解

**黄金规则：自动化部署永远不要用 latest，它只该出现在文档和手动调试里。**

**回滚的便利性也是 commit sha 的额外好处：** 旧镜像不会被删除（`docker compose pull` 只拉新的、不删旧的），所以回滚就是把 `APP_IMAGE` 改成上一个好的 commit sha 然后 `docker compose up -d`。如果只用 latest，你回滚时只能说"我要拉昨天那个版本"，却说不清具体是哪一个。

---

## 📊 应用与对比

### 本项目的完整流水线

`.github/workflows/ci.yml` 里三个 job：

| job | 跑在哪 | 做什么 | 触发条件 |
|-----|--------|--------|---------|
| `test` | `ubuntu-latest` | 起 MySQL / Redis，跑 `mvn verify`（43 个测试） | push + PR |
| `image` | `ubuntu-latest` | buildx 构建镜像并推到 GHCR，同时打 `:<commit-sha>` 和 `:latest` | push + PR |
| `deploy` | `[self-hosted, navigation-menu-deploy]` | `docker compose pull app` → `up -d --wait --no-build`，只重建应用容器 | **仅 push 到 main** |

```java
git push
      │
      ▼
┌─────────────┐   ubuntu-latest
│ job: test   │   起 MySQL/Redis → mvn verify
└─────────────┘
      │ needs
      ▼
┌─────────────┐   ubuntu-latest
│ job: image  │   buildx → ghcr.io/zzz-jjj-hhh/navigation-menu:<commit-sha>
└─────────────┘
      │ needs
      ▼
┌─────────────┐   本机 self-hosted runner（zhan-pc）
│ job: deploy │   docker compose pull app
└─────────────┘   docker compose up -d --wait --no-build
      │           curl.exe -fsS /actuator/health
      ▼
服务在 8080 上跑起来
```

`deploy` 只跑在 `push` 到 main，**不跑在 PR 上**。这一条是刻意的：runner 就在你本机，如果 PR 也能触发它，等于把本机的执行权开放给了提 PR 的人。

整条流水线**没有配置任何 secret**。推送镜像用的是 GitHub 运行时自动注入的临时凭据 `GITHUB_TOKEN`，用完即废、不需要人工维护；拉取公开包连登录都不需要（见「坑四」）。少一套凭据要管，就少一处可能泄露的地方——这是选 GHCR 而不是 Docker Hub 的一个实际好处。

### 什么时候才需要跑流水线

**不是每次 push 都值得开一条流水线。** 改一篇文档既不影响任何测试结果，也不改变镜像内容，让三个 job 空转四分钟纯属浪费。所以触发条件里加了路径过滤：

```yaml
on:
  push:
    paths-ignore:
      - 'docs/**'
      - '**/*.md'
```

只改文档的提交**根本不触发流水线**。注意这不是"触发了再跳过"——是压根没有这次运行：机器不用开、依赖不用下、镜像不用构建。

不过有个概念值得单独拎出来，正是它决定了"什么改动算值得跑"：**tag 变了不等于镜像内容变了**。

- **tag** 是贴在镜像上的字符串标签，换 tag 不改变镜像内容
- **镜像 ID** 是镜像配置对象的 sha256 摘要——由"各层的内容"加"运行时配置（ENV / ENTRYPOINT / USER 等）"共同算出，是内容的指纹

每次部署，`APP_IMAGE` 指向的 tag 都会变成新的 commit SHA，所以 tag 一定在变，但内容不一定：

| 改动 | 镜像 ID 会变吗 | 为什么 |
|------|---------------|--------|
| `src/main/**` Java 代码 | 会 | `COPY src ./src` 那一层的内容变了 |
| `pom.xml` 依赖或版本 | 会 | 同上，而且要重新编译打包 |
| `Dockerfile` 任一行 | 会 | 层内容或运行时配置变了 |
| `src/test/**` 测试代码 | **会** | 有点反直觉：`COPY src ./src` 把测试也拷了进去。虽然 `-DskipTests` 不执行它们，但文件内容变了层就变了 |
| `docs/**`、`*.md`、`.github/**` | 不会 | 被 `.dockerignore` 排除在构建上下文之外 |
| 换个 tag | 不会 | tag 不参与内容计算 |

> 🔑 **把判断放在触发这一层，比放在部署里更省**——不触发就没有机器要开、没有依赖要下。反过来，如果在 deploy 里比对镜像 ID 再决定要不要 `up -d`，虽然也能省下一次重启，但会让 `docker-compose.yml` 的改动被一起跳过：Compose 判断"要不要重建容器"依赖服务配置的哈希，而直接跳过 `up` 就等于把配置改动也吞掉了。**该省的要省，但不能省到悄悄不生效。**

顺带说明一点：`docs/` 和 `*.md` 之外的改动都会触发流水线，其中包括只改了 `.github/` 的情况——镜像内容没变，但 tag 变了，容器仍会被重建一次。这类情况很少，不值得为它再加一层判断。

### 失败了怎么办

前面画的是一条成功路径。真正决定这套流水线好不好用的，是它出错时的表现。

**三种失败点，后果完全不同**

| 挂在哪 | 对你的服务的影响 |
|--------|-----------------|
| `test` | 没有影响，`image` 和 `deploy` 被 `needs` 跳过 |
| `image` | 没有影响，`deploy` 被跳过 |
| `deploy` 的**拉取**那一步 | 没有影响，替换还没开始 |
| `deploy` 的**重启**那一步 | **容器已经被换成新镜像了** |

`needs` 保证了前两种情况下链条断在前面，线上什么都不变。deploy 内部还要再分一次：**失败在拉取阶段是安全的**（镜像还没落地，容器原封不动），**失败在重启阶段才会真的改变线上状态**。

这恰好说明了 `deploy` 里步骤的先后顺序是有意设计的——先把镜像完整拉到本地、确认拿得到，再去动正在运行的容器。把"下载"和"替换"拆成两步，就是为了让网络问题倒在这道墙外。

**deploy 失败时的实际表现**

```java
docker compose pull app        ──▶ 新镜像下载到本地
      │
      ▼
docker compose up -d --wait    ──▶ 先把应用容器替换成新镜像
      │                                 │
      │                                 ▼
      │                            等 healthcheck 通过
      │                                 │
      │                                 ✗ 超时
      ▼
job 失败，但旧容器已经没了
```

关键在这个顺序：**替换发生在健康检查之前**。所以 deploy 失败不是"什么都没发生"，而是"服务已经从能用的旧版，变成了不健康的新版"。`--wait` 只能让流水线知道坏了，拦不住替换发生。

**回滚靠手工，但命令很短**

旧镜像这时还在本地（`docker compose pull` 只拉新的，不删旧的），所以直接指回上一个 commit 的 SHA 即可：

```powershell
$env:APP_IMAGE='ghcr.io/zzz-jjj-hhh/navigation-menu:<上一个好的 commit-sha>'
docker compose up -d --wait --no-build
```

前提是你知道"上一个好的 SHA"是哪个。这就是**部署用 commit SHA 而不是只用 `latest` 的第二个理由**：SHA 让回滚变成一个确定的、可查的操作；`latest` 只能让你回滚到"某个更早的时刻"，却说不清是哪一个。

**现在缺的两块，以及为什么先不做**

一是没有自动回滚。完整的做法是部署失败后自动重试上一个版本的 SHA，但判断"新版本真的坏了"需要比单次健康检查更可靠的信号——一次 `--wait` 超时可能只是数据库在初始化，未必代表镜像有问题。判断错了会把本来好的版本也退掉。

二是没有失败通知。deploy 挂了只有去 Actions 页面才看得到。对本机部署影响不大，因为服务就装在自己电脑上，起没起来一眼能看见。

> ⚠️ **一个不报错的失败：runner 离线。** 如果 `run.cmd` 那个进程没在跑，job 不会失败，而是一直排在队列里等 runner，直到超时才被取消。表现是"推了代码，什么都没发生，也没有任何红叉"。而这个进程是隐藏窗口起的后台进程，**重启电脑就没了**——这是整套自动化里最脆弱的一环。

**并且加了并发控制**

同一分支连着推两次会产生两条流水线，两个 `deploy` job 都指向同一台 runner。runner 一次只接一个任务，所以它们排队——但**排队顺序没有保证**，先推的那次如果后跑，最后落地的就是旧镜像。`ci.yml` 顶部因此加了：

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

新推送会取消同一分支上还在跑的旧流水线，保证结束状态一定对应最新的那次提交。

### 认知纠偏：学习路上的五个典型误区

这一节记录的是概念阶段最容易走偏的地方，每一条都是实际卡过的。

| 误区 | 纠正 |
|------|------|
| "CI 就是 GitHub Actions" | CI 是一种做法，Actions 是实现它的工具之一。Jenkins、GitLab CI 干的是同一件事 |
| "CI 的目的是验证代码有没有 bug" | 更准确说是**验证集成**：这次改动有没有把原先好用的东西弄坏。名字里的 Integration 才是重点 |
| "流水线里出现 `docker build` 就等于有 CD 了" | 构建 ≠ 交付。镜像不推到仓库，目标机器永远拉不到 |
| "下载依赖/镜像很费 token" | 下载本身不消耗 token，字节不经过模型；**消耗 token 的是命令打印的日志**进入上下文 |
| "自托管 runner 是 GitHub 连进来控制我的电脑" | 方向是 outbound，runner 主动连出去领任务。但这**不代表更安全**——权限是一样的 |

> ⚠️ **最后一条特别容易误解**。不需要公网 IP 只是因为它主动向外连，不改变"任务内容来自谁、以什么身份执行"。任务来自 GitHub 上的 workflow 文件，执行时用的是你启动 runner 的那个用户身份。

### 测试质量决定了 CI 的能力上限

> 🔑 **CI 的验证能力，完全取决于你让它验证什么。**

本项目最初只有 `contextLoads()` 一个测试。它只能回答一个问题：**代码能编译、Spring 容器能启动**。

它回答不了"菜单匹配逻辑还对不对"。如果把 `FallbackMatchHandler` 里四维通配的匹配顺序写反了，`contextLoads` 照样是绿的——它只是启动了容器，压根没调用过业务逻辑。

后来补齐到 43 个测试后，CI 才真的在把关。判断一条测试有没有价值，问一句：**我把这段代码故意改坏，有几条测试会变红？** 如果一个改动没有任何测试变红，那段代码就是没被保护的。

### 踩坑记录（真机验证）

这五条都是实际跑出来的，不是假设。

**坑一：job 级 `env` 不能引用 `env` 上下文**

```yaml
# ❌ 整个 workflow 被判为非法
jobs:
  deploy:
    env:
      IMAGE: ${{ env.IMAGE_NAME }}
```

GitHub 的上下文可用性规则里，job 级 `env` 只放行 `github、needs、strategy、matrix、vars、secrets、inputs`，**不包含 `env`**。改成字面量即可。

教训：推送前本地校验一遍 YAML，能省一次失败的流水线。`pip install pyyaml` 然后 `yaml.safe_load` 就能查出语法和结构问题。

**坑二：大文件下载会被静默截断**

runner 压缩包真实大小 99 MB，第一次只下到 14.9 MB。文件存在、大小不为零，但解压报 `End of Central Directory record could not be found`。

解法是断点续传加循环重试：

```powershell
curl.exe -sL -C - --max-time 600 -x http://127.0.0.1:12334 -o $zip $url
```

教训：**下完要校验大小或文件头**（zip 的前两字节是 `PK`），不要假设"文件存在"等于"下载完整"。

**坑三：`Expand-Archive` 对大包会失败**

改用 .NET 的接口反而更稳：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dir, $true)
```

**坑四：把"不继承可见性"读成了"默认私有"**

官方文档的原话是"包只继承仓库的访问权限，**不继承可见性**"。我据此推断推上去的包默认私有，于是给 deploy job 也补了一步 `docker login ghcr.io`。

后来实测发现这个包**是公开的**——匿名就能取到 pull token 并列出 tag。推断本身越界了：文档说的是"不继承"，并没有说"默认私有"。

> ⚠️ **涉及默认值的行为，别凭文档断言，跑一条命令验证**。推断错的代价是双向的：多加了不必要的登录步骤；或者哪天真变私有而你没加，报错会指向"镜像不存在"，把人往错的方向带。

详见 [工程实践-GHCR镜像仓库](./工程实践-GHCR镜像仓库.md)。

**坑五：部署失败可能只是网络抖动**

一次只改了文档的提交触发了部署，结果挂了，日志是：

```
app Warning Head "https://ghcr.io/v2/zzz-jjj-hhh/navigation-menu/manifests/bf60bd97...": EOF
Error response from daemon: Head "...": EOF
```

原因是你本机到 ghcr.io 的连接断了一下。同一时段 `git push` 也报过 `TLS ... unexpected eof`，`curl https://ghcr.io/v2/_catalog` 返回过 000。**和代码、镜像都没关系。**

处理方式是什么都不用改，重跑失败的那个 job：

```bash
gh run rerun <run-id> --failed
```

它只跑失败的那一段，不会重新走测试和构建。

教训：看到 `EOF`、`connection reset`、`TLS` 这类字眼，先判断是不是网络问题，别急着怀疑镜像或配置。反过来，如果同样的错误发生在 `up -d` 阶段，影响就完全不同了——见前面「失败了怎么办」。

> 🔑 这个坑还有个副产品：它验证了失败表里那个结论。这次挂掉时容器**原封不动**，还是旧版本、还是 healthy，因为错误发生在拉取阶段。**"部署失败"和"服务被换坏"不是一回事。**

### 三种部署落点对比

| 方案 | 需要什么 | 适合什么 |
|------|---------|---------|
| 本机 + GitHub 托管 runner | 一台常开的电脑 | 零成本闭环，适合学习和演示 |
| 本机 + self-hosted runner | 同上，外加注册一个 runner | 需要把部署真正落到本机执行 |
| 云服务器 + SSH 部署 | 一台云主机 + 公网 IP | 需要真实公网服务，能接受付费 |

> ⚠️ **公开仓库 + self-hosted runner 是官方明确不建议的组合，而本项目当前正是这个组合。**
>
> 直觉上的缓解——把 deploy job 限定在 `push` 到 main——**并不够**。它只覆盖了我们自己写的那个 job；攻击者可以在自己的 fork 里另写一个 workflow，直接写 `runs-on: [self-hosted, navigation-menu-deploy]`，然后提 PR，根本不需要碰我们的 deploy job。官方原话是 "self-hosted runners should almost never be used for public repositories"。
>
> 现状是已知并暂时接受的风险。完整的风险分析、当前生效的防护，以及两条退出路径（转私有 / 去掉 runner 改本机定时拉取），都记录在 [工程实践-self-hosted-runner](./工程实践-self-hosted-runner.md) 的「本项目当前状态」一节。

---

## 🔥 面试要点

### 高频问题 + 标准答案

> **Q1：CI 和 CD 分别是什么？区别在哪？**
>
> CI 是持续集成：每次提交后自动编译、跑测试，目的是尽早发现这次改动有没有把主干弄坏。它的关键词是 Integration，解决的是"改动攒太久、合并时爆炸"这个痛。
>
> CD 是持续交付/部署：把验证通过的产物自动送到运行环境。CI 管"代码是不是好的"，CD 管"好的代码怎么变成能访问的服务"。
>
> 区别在于 CI 不需要运行环境，CD 必须要——这也是新人卡住的地方。

> **Q2：GitHub Actions 是什么？和 Jenkins 有什么区别？**
>
> GitHub Actions 是 GitHub 自带的流水线执行器，本质和 Jenkins 一样：照着一份配置文件跑命令。映射关系是：workflow ↔ Jenkins Job，yml 文件 ↔ Jenkinsfile，runner ↔ Agent，`runs-on` ↔ agent 标签。
>
> 主要区别：Jenkins 需要自己维护服务器和插件，Actions 的机器由 GitHub 提供、按分钟计费；Jenkins 在网页上点配置，Actions 把配置放进仓库里跟着代码走版本管理。
>
> 要注意 Actions 本身不做任何事，它只是执行器，真正干活的是 yml 里的命令。

> **Q3：为什么你的 CI 里要专门起 MySQL 和 Redis？**
>
> 因为默认的 runner 是一台**全新的干净机器**，跑完就销毁。它没有我的 JDK 版本、没有本地数据库、也没有 `~/.m2` 里攒的依赖。我的集成测试是 `@SpringBootTest`，会真的把 Spring 容器启动起来，必须连得上真实的数据库和缓存。
>
> 所以我在 workflow 里直接复用项目自己的 `docker-compose.yml` 起依赖——顺带还验证了 compose 文件和 SQL 初始化脚本是好的。这也是为什么流水线里到处都在配 cache：不缓存就要每次重下整个依赖树。

> **Q4：Docker 镜像在 CI/CD 里扮演什么角色？**
>
> 镜像是**交付单元**。Jar 不是能独立运行的东西，它需要特定版本的 JVM，运行时还要连数据库和缓存；镜像把应用和它的整个运行时一起冻住，任何装了 Docker 的机器上行为都一致。
>
> 在流水线里它承担两个职责：一是**验证**，每次提交都构建一次，能及早发现 Dockerfile 悄悄腐烂（比如升级了 Java 版本但基础镜像没改）；二是**交付**，推到镜像仓库后目标机器才能拉取。
>
> 只构建不推送，等于只有验证没有交付，链条是断的。

> **Q5：self-hosted runner 是什么？有什么风险？**
>
> 自托管 runner 是你自己的机器注册到 GitHub 上领任务，相当于 Jenkins Agent。连接方向是 outbound，所以不需要公网 IP、不用开端口。
>
> **但这不代表更安全**。方向只决定谁先发起连接，不改变权限：任务内容来自仓库里的 workflow 文件，执行时用的是启动 runner 的那个用户身份。
>
> 核心风险在公开仓库——任何人 fork 后改掉 workflow 提 PR，就能在维护者的真实电脑上执行任意命令。缓解手段：仓库转私有、用 runner group 限制可派发的工作流、收紧 fork PR 的审批开关、不给 runner 管理员权限、更彻底地跑在隔离的虚拟机里。

> **Q6：为什么你的镜像同时打 commit SHA 和 latest 两个 tag？部署时用哪个？**
>
> commit SHA tag 是**精确版本标识**，它来自 git 的 commit hash，全局唯一、永不改变。image job 打了 tag `navigation-menu:a1b2c3d`，deploy job 就拉同一个 tag——因为它们读的是同一个 `github.sha` 上下文变量，严丝合缝对上。并发推送时 SHA 不会有歧义，还能精确追溯和回滚。
>
> latest tag 是**方便性标签**，只在手动调试和文档示例里用，自动化部署**绝不能用它**——它是可变的，谁也不知道当前 latest 指向的到底是哪个 commit。
>
> 黄金规则：自动化部署只用精确 tag（commit SHA），latest 留给人手动用。

### 关键词速记

| 关键词 | 一句话 |
|--------|--------|
| Integration | CI 的重点在"集成"，不在"测试" |
| runner | 随叫随到的干净机器，跑完即销毁 |
| `runs-on` | 唯一的开关，决定任务落在哪台机器 |
| `needs` | job 间的依赖，串成流水线 |
| 镜像仓库 | CI 和 CD 的交接点，镜像推到这里才算交付 |
| 防回归 | CI 比"验证本次改动"更值钱的作用 |
| 上下文 | run 级别的全局对象，所有 job 共享同一个 `github.sha` |
| 精确 tag | 自动化部署只用 commit SHA，绝不用 latest |
| `paths-ignore` | 在触发层过滤，比"触发了再跳过"省下一整条流水线 |
| `concurrency` | 同分支串行化，避免两次推送的部署乱序 |

---

## 🧠 记忆口诀

```java
记一个分界：
  CI 不需要运行环境 → 编译 + 测试 + 构建镜像
  CD 必须要运行环境 → 推送 + 拉取 + 启动

记四个环节（缺一不可）：
  代码 → 镜像 → 镜像仓库 → 目标机器

记 runner 的本质：
  随叫随到的干净机器
  「干净」两个字，决定了 workflow 里为什么到处要重装环境和配缓存

记一条最容易误解的：
  下载不消耗 token，命令打印的日志才消耗
```

---

## 🔄 知识闭环

### 从一次 push 到服务上线的完整链路

```java
本地改完代码
      │  git push
      ▼
GitHub Actions 被触发
      │
      │  github.sha = "a1b2c3d..."  ← run 级全局上下文，三个 job 读同一个
      │
      ├── job: test    ──▶ docker compose up mysql redis
      │                     mvn verify（43 个测试）
      │                     ↑ 沙箱机器，跑完销毁
      │
      ├── job: image   ──▶ buildx 构建镜像
      │                     tags: navigation-menu:${{ github.sha }}  ← a1b2c3d
      │                           navigation-menu:latest
      │                     push ghcr.io
      │                     ↑ CI 与 CD 的交接点
      │
      └── job: deploy  ──▶ 本机 self-hosted runner（只有 push 到 main 才走）
                            APP_IMAGE = ghcr.io/.../navigation-menu:${{ github.sha }}
                                        ↑ a1b2c3d，和上面那个是同一个值
                            docker compose pull app
                            docker compose up -d --wait --no-build
                            curl.exe -fsS /actuator/health
      │
      ▼
服务在 8080 上跑起来
```

### 与其他技术专题的关系

| 专题 | 在链路中的位置 | 解决什么 |
|------|--------------|---------|
| [工程实践-Java编译打包与运行机制](./工程实践-Java编译打包与运行机制.md) | `mvn verify` 与 `docker build` 的内部 | 从源码到进程发生了什么 |
| **本篇：CI/CD** | **把上面这些环节串成自动流水线** | **什么时候自动跑、跑在哪、产物送到哪** |
| [工程实践-GHCR镜像仓库](./工程实践-GHCR镜像仓库.md) | image job 的产物落点 | 镜像存在哪、怎么寻址、谁能拉 |
| [工程实践-self-hosted-runner](./工程实践-self-hosted-runner.md) | deploy job 的执行环境 | 任务在谁的机器上跑 |
| [工程实践-Mockito单元测试](./工程实践-Mockito单元测试.md) | test job 的内部 | 测试怎么写才有价值 |
| [业务架构-责任链模式](../业务架构/业务架构-责任链模式.md) | 服务启动之后 | 请求进来怎么处理 |
| [业务架构-Redis缓存](../业务架构/业务架构-Redis缓存.md) | 责任链的前置 | 挡住大部分请求 |
| [业务架构-树形结构构建方案](../业务架构/业务架构-树形结构构建方案.md) | 责任链内部 | O(n) 把平铺数据组树 |

### 一句话串起整条链

**CI 让"代码是好的"这件事有了自动化证据，CD 让"好的代码"自动变成"能访问的服务"。** 中间那个交接点是一个镜像——它既是构建的产物，也是部署的输入。

而整条链最容易被低估的一环是测试：流水线跑得再顺，如果测试只验证"容器能启动"，它给出的绿灯就是空的。**CI 的能力上限，等于你写进测试里的断言的上限。**
