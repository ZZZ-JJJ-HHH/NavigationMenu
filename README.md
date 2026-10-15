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
$env:APP_PORT='8081'; docker compose up -d --wait
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

| 阶段 | 内容 |
|------|------|
| 编译 + 测试 | 复用 `docker-compose.yml` 起 MySQL 和 Redis，执行 `mvn verify` |
| 构建镜像 | 用 buildx 构建 `Dockerfile`，确认镜像能产出并缓存构建层 |

目前只做到 CI（每次提交自动构建、测试、产出镜像）。CD（自动把镜像送上运行环境）尚未接入，可选路线：

1. **本机 + 内网穿透**：`docker compose up -d` 起服务，用 Cloudflare Tunnel 暴露成公网地址。零成本，适合演示，关机即停。
2. **云服务器**：轻量应用服务器装 Docker，CI 把镜像推到 GHCR，CD 通过 SSH 执行 `docker compose pull && docker compose up -d`。最接近真实生产。
3. **托管 PaaS**：仓库直连平台，由平台负责构建和运行，不用自己运维主机。

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
└── 技术专题/                             深度技术分析
    ├── 技术专题-责任链模式.md
    ├── 技术专题-Redis缓存.md
    ├── 技术专题-Spring依赖注入详解.md
    ├── 技术专题-树形结构构建方案.md
    └── 技术专题-curl命令详解.md
```

---

## License

MIT
