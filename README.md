# NavigationMenu

> 导航菜单平台——基于责任链模式 + Redis 缓存的多维菜单匹配与 O(n) 树构建方案。

---

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.4.4 |
| MyBatis-Plus | 3.5.7 |
| Redis | spring-boot-starter-data-redis |
| MySQL | 8.x |
| Java | 17 |

---

## 快速开始

### 方式一：Docker Compose（推荐）

一条命令拉起 MySQL、Redis 和应用。首次启动时数据卷为空，会自动按文件名顺序执行 `sql/` 下的脚本建库建表并灌入数据。

```bash
git clone https://github.com/ZZZ-JJJ-HHH/NavigationMenu.git
cd NavigationMenu
docker compose up -d --wait
```

验证 →

```bash
curl.exe -s -X POST http://localhost:8080/api/menu/tree -H "Content-Type: application/json" -d "@test/test_body.json"
curl.exe -s http://localhost:8080/actuator/health
```

停止并清空数据 →

```bash
docker compose down -v
```

MySQL、Redis 只绑定在本机回环上（宿主端口 3307 / 6380，避开本地已有的 3306 / 6379）。端口被占用时用环境变量错开：

```powershell
$env:APP_PORT='8090'; docker compose up -d --wait
```

### 方式二：本地直跑

```bash
# 1. 初始化数据库（00_schema 负责建库建表，必须最先执行）
mysql -u root -p                       < sql/00_schema.sql
mysql -u root -p navigation_menu       < sql/init_branch.sql
mysql -u root -p navigation_menu       < sql/init_data.sql

# 2. 启动（连接信息优先读环境变量，未设置则用 application.yaml 里的本地默认值）
mvn spring-boot:run
```

验证 →

```bash
curl.exe -s -X POST http://localhost:8080/api/menu/tree -H "Content-Type: application/json" -d "@test/test_body.json"
```

可覆盖的环境变量：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。

---

## 核心设计

```
请求进入 → Controller → Service
                            ├─ 缓存命中 → 直接返回
                            └─ 缓存未命中 → 责任链处理
                                              │
                          ┌───────────────────┘
                          ▼
              ┌─────────────────────┐
              │ ① LegacyCheck      │ 查旧系统 → 有数据则短路
              ├─────────────────────┤
              │ ② LoadMenu         │ 从 DB 拉菜单配置
              ├─────────────────────┤
              │ ③ FallbackMatch    │ 四维通配匹配（国家·实体·渠道·网点）
              ├─────────────────────┤
              │ ④ PermissionFilter │ 权限过滤
              ├─────────────────────┤
              │ ⑤ TreeBuild        │ HashMap 索引 O(n) 组树
              └─────────────────────┘
                          │
                          ▼
                      写入缓存 → 返回
```

| 技术点 | 说明 |
|--------|------|
| 责任链 | `@Order` 注解自动排序，Handler 内部可中断（短路） |
| 四维匹配 | 国家码 → 实体 → 渠道 → 网点，支持通配符 fallback |
| Redis 缓存 | 四级 key 设计，缓存命中直接返回 |
| O(n) 树构建 | HashMap 建立 ID→Node 索引，一次遍历完成父子挂载 |

---

## 自动化部署

流水线定义在 [.github/workflows/ci.yml](.github/workflows/ci.yml)，push 到 `main` 或提交 PR 时触发：

| Job | 跑在哪 | 做什么 |
|-----|--------|--------|
| 编译 + 测试 | GitHub 托管 runner | 复用 `docker-compose.yml` 起 MySQL 和 Redis，执行 `mvn verify` |
| 构建并推送镜像 | GitHub 托管 runner | 构建 `Dockerfile`，推送到 GHCR，tag 用 commit SHA |
| 部署到本机 | 本机 self-hosted runner | 拉取本次提交的镜像，`docker compose up -d --wait` 滚动重启并验证健康 |

于是完成一个闭环：**push 一次代码，本机正在运行的容器会自动更新到这次提交对应的镜像。**

镜像地址是 `ghcr.io/zzz-jjj-hhh/navigation-menu:<commit-sha>`。用 commit SHA 而不是只用 `latest` 作 tag，是为了让任何正在运行的容器都能被追溯到确切的那次提交。

### self-hosted runner

本机装了一个 GitHub Actions runner，位于 `%USERPROFILE%\actions-runner`，标签 `navigation-menu-deploy`。它**主动向外连** GitHub 拉任务，所以不需要公网 IP、端口映射或 SSH 密钥。

```powershell
# 当前以隐藏进程运行，重启电脑后需要重新启动
cd $env:USERPROFILE\actions-runner
.\run.cmd
```

要开机自启，用管理员身份执行 `.\svc.cmd install`，把它装成 Windows 服务。

### 安全约束

deploy job 带 `if: github.event_name == 'push'`，只在推送到 `main` 时触发。这个限制是必须保留的：**self-hosted runner 绝不能执行来自 fork 的 PR**，否则任何人提交一个 PR 就能在你的电脑上执行任意代码。本机 runner 只服务仓库所有者本人推送到 main 的提交。

健康检查端点 `GET /actuator/health` 是这条链路的验收标准——最后一步拉不通它，部署就算失败。

健康检查端点：`GET /actuator/health`，容器与流水线都依赖它判断服务是否就绪。

---

## 文档

详细设计文档、面试准备材料 → [项目手册](docs/项目规划/项目规划-项目手册.md)

```
docs/
├── 项目规划/                             项目级规范和总览
│   ├── 项目规划-项目手册.md               文档入口
│   ├── 项目规划-业务分析与设计思路.md      业务背景、5 步流水线
│   ├── 项目规划-开发路线图.md             四阶段进度
│   ├── 项目规划-全链路开发规范.md         分层职责、命名规范
│   ├── 项目规划-文档风格与AI协作规范.md    AI 协作规范
│   └── 项目规划-简历.md                  面试要点
│
└── 技术专题/                             深度技术分析，按线分三类
    ├── 可观测性/                         日志 · 指标 · 链路（8 篇）
    │   ├── 可观测性-总览.md
    │   ├── 可观测性-结构化日志.md
    │   ├── 可观测性-日志框架与管辖边界.md
    │   ├── 可观测性-日志级别与成本控制.md
    │   ├── 可观测性-链路追踪与MDC.md
    │   ├── 可观测性-日志采集与Splunk.md
    │   ├── 可观测性-Prometheus查询与可视化.md
    │   └── 可观测性-Docker容器与日志落盘.md
    ├── 业务架构/                         责任链 · 缓存 · 树 · DI（4 篇）
    │   ├── 业务架构-责任链模式.md
    │   ├── 业务架构-Redis缓存.md
    │   ├── 业务架构-树形结构构建方案.md
    │   └── 业务架构-Spring依赖注入详解.md
    └── 工程实践/                         CI/CD · 构建 · 测试 · 工具（6 篇）
        ├── 工程实践-CI-CD.md
        ├── 工程实践-GHCR镜像仓库.md
        ├── 工程实践-self-hosted-runner.md
        ├── 工程实践-Java编译打包与运行机制.md
        ├── 工程实践-Mockito单元测试.md
        └── 工程实践-curl命令详解.md
```

---

## License

MIT
