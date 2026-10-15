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

```bash
git clone <repo-url>
cd NavigationMenu

# 1. 初始化数据库
mysql -u root -p < sql/init_branch.sql
mysql -u root -p < sql/init_data.sql

# 2. 修改 application.yaml 中的数据库和 Redis 连接

# 3. 启动
mvn spring-boot:run
```

验证 →

```bash
curl.exe -s -X POST http://localhost:8080/api/menu/tree -H "Content-Type: application/json" -d "@test/test_body.json"
```

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