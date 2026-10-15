# 技术专题：Redis 缓存

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

> 菜单是低频变高频读的数据，加一层 Redis 缓存，命中就直接返回省掉整条责任链。

---

## 🎯 背景与问题

### 没有缓存时的链路

```java
请求
  │
  ▼
LegacyCheck (查 DB)
  │
  ▼
LoadMenu (查 DB)
  │
  ▼
FallbackMatch
  │
  ▼
PermissionFilter
  │
  ▼
TreeBuild
  │
  ▼
返回
```

每次请求都要 2 次 DB 查询 + 3 次内存计算。高峰期 DB 扛不住，而且大部分请求的结果是一样的——菜单一个月才改几次，但用户每分钟打开 APP 几十次。

### 核心痛点

| 痛点 | 具体表现 |
|------|---------|
| DB 压力大 | 每次请求都查 branch_config + menu_config 两张表 |
| 延迟高 | 完整链路走完要 300ms+，DB 是最大瓶颈 |
| 资源浪费 | 99% 的请求结果是重复的，但每次都重新算 |

### 数据特征分析（决定了缓存适用）

| 特征 | 说明 |
|------|------|
| 读多写少 | 用户每分钟拉几次菜单，运营可能一个月才改一次配置 |
| 计算成本高 | 2 次 DB + Fallback 匹配 + 权限过滤 + 组树 = 整条责任链 |
| 结果可预测 | 相同的 branchCode + userId -> 相同的菜单树，没有随机性 |

---

## 💡 核心目的

**用 Redis 在 Service 层前置拦截请求，缓存命中直接返回，miss 才走完整链。设计目标：**

1. **降低 DB 压力**：95%+ 请求不查 DB
2. **降低 P99 延迟**：从 300ms -> 50ms（Redis 命中 < 1ms + JSON 反序列化）
3. **对业务透明**：缓存逻辑在 Service 层，Handler 完全不知道缓存的存在

---

## ⚙️ 原理与机制

### 缓存在整体链路中的位置

```java
请求
  │
  ▼
Service.buildMenuTree
  │
  ▼
┌────────────────────────────────────┐
│ Redis.get                          │
│ key: menu:tree:branchCode:userId   │
└────────┬───────────────────────────┘
         │
    ┌────┴────┐
    │  命中？  │
    └────┬────┘
    ┌────┼────┐
    ▼         ▼
  命中       未命中
    │         │
    ▼         ▼
直接返回   HandlerChain.proceed
缓存菜单    走完整责任链
    │         │
    │         ▼
    │     拿到菜单树
    │         │
    │         ▼
    │     Redis.set
    │     回写缓存
    │         │
    └────┬────┘
         ▼
       返回
```

### 核心组件

| 组件 | 职责 | 代码位置 |
|------|------|---------|
| RedisTemplate | 操作 Redis 的核心 API | config/RedisConfig.java |
| MenuTreeCacheService | 封装菜单缓存的 get/put/evict | service/cache/MenuTreeCacheService.java |
| Service 层前置检查 | 缓存拦截 + 回写 | service/impl/MenuServiceImpl.java |

### Key 设计（最关键的决策）

`
menu:tree:{branchCode}:{userId}
`

**为什么必须是两级？**

```java
┌─── 只存 branchCode ❌ ──────────────────────────────┐
│                                                     │
│  SH001(有数字人民币) ──→ key: menu:tree:SH001        │
│  BJ001(没有)        ──→ key: menu:tree:SH001        │
│                         ↑                           │
│                    同一 key → 互相覆盖！              │
└─────────────────────────────────────────────────────┘

┌─── 只存 userId ❌ ──────────────────────────────────┐
│                                                     │
│  luxury_user(有黑卡) ──→ key: menu:tree:luxury_user  │
│  normal_user(没有)   ──→ key: menu:tree:luxury_user  │
│                           ↑                         │
│                      同一 key → 互相覆盖！            │
└─────────────────────────────────────────────────────┘

┌─── branchCode + userId ✅ ──────────────────────────┐
│                                                     │
│  SH001:luxury_user  →  key: menu:tree:SH001:luxury  │
│  SH001:normal_user  →  key: menu:tree:SH001:normal  │
│  BJ001:luxury_user  →  key: menu:tree:BJ001:luxury  │
│  BJ001:normal_user  →  key: menu:tree:BJ001:normal  │
│                                                     │
│  每个组合独立 key → 互不干扰                          │
└─────────────────────────────────────────────────────┘
```

| 部分 | 作用 | 为什么需要 |
|------|------|-----------|
| menu:tree: | 前缀 | KEYS menu:* 能看到所有菜单缓存，方便排查 |
| {branchCode} | 分行编码 | Fallback 匹配结果取决于分支配置 |
| {userId} | 用户 ID | 权限过滤结果取决于用户权限 |

**边界处理**：userId 为 null（匿名用户）时用 "anonymous" 字符串替代。

### Value 设计

直接存 **完整菜单树 JSON**（MenuTreeVO 对象序列化后）。

| 方案 | 说明 | 我们选它的原因 |
|------|------|---------------|
| 完整菜单树 JSON | 一次序列化，命中直接返回 | 命中后零计算，省掉整条链 |
| 扁平列表 | 只存 matched + filtered，组树在缓存外 | 省空间但每次命中还要组树，白缓存了 |
| 分层缓存 | branch 级 + user 级权限缓存 | 太复杂，菜单种类不多没必要 |

### 序列化方案（踩过的坑）

**坑**：默认 JdkSerializationRedisSerializer 存进 Redis 的是 \xac\xed\x00\x05sr\x00... 二进制乱码，redis-cli 看不见，线上排查瞎了。

**解**：配 GenericJackson2JsonRedisSerializer——Spring 的 Jackson 库，存可读 JSON：

`java
// config/RedisConfig.java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

    template.setKeySerializer(stringSerializer);
    template.setValueSerializer(jsonSerializer);
    template.setHashKeySerializer(stringSerializer);
    template.setHashValueSerializer(jsonSerializer);
    template.afterPropertiesSet();
    return template;
}
`

redis-cli 里看到的就是：
`json
redis> GET menu:tree:SH001:normal_user
"{\"@class\":\"com.example.navigation_menu.model.vo.MenuTreeVO\",
  \"menuTree\":[{\"@class\":\"...MenuNodeVO\",\"nodeId\":\"home\",\"nodeName\":\"首页\",...}]}"
`

> **注意**：@class 字段是 Jackson 加的类型信息，反序列化时能正确还原成 MenuTreeVO 对象。

### TTL 设计：为什么是 10 分钟

| TTL | 适合什么场景 | 我们的情况 |
|-----|------------|-----------|
| 1 分钟 | 实时性高的（库存、价格） | 菜单不用这么快生效 |
| **10 分钟** | 低频变化的（菜单、配置） | 刚好——菜单改了等 10 分钟可以接受 |
| 1 小时 | 几乎不变的（字典表） | 菜单还是可能改的，1 小时太长 |

### 完整代码

**MenuTreeCacheService**：
`java
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuTreeCacheService {

    private static final String KEY_PREFIX = "menu:tree:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;

    public MenuTreeVO get(String branchCode, String userId) {
        String key = buildKey(branchCode, userId);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof MenuTreeVO vo) {
            log.info("cache hit, key={}", key);
            return vo;
        }
        log.info("cache miss, key={}", key);
        return null;
    }

    public void put(String branchCode, String userId, MenuTreeVO vo) {
        String key = buildKey(branchCode, userId);
        redisTemplate.opsForValue().set(key, vo, TTL);
        log.info("cache set, key={}, ttl={}", key, TTL);
    }

    public void evict(String branchCode, String userId) {
        String key = buildKey(branchCode, userId);
        redisTemplate.delete(key);
        log.info("cache evict, key={}", key);
    }

    private String buildKey(String branchCode, String userId) {
        String userKey = (userId != null && !userId.isEmpty()) ? userId : "anonymous";
        return KEY_PREFIX + branchCode + ":" + userKey;
    }
}
`

**在 Service 层的调用**：
`java
public MenuTreeVO buildMenuTree(MenuQueryDTO dto) {
    // 1. 先查缓存
    MenuTreeVO cached = cacheService.get(dto.getBranchCode(), dto.getUserId());
    if (cached != null) {
        return cached;
    }

    // 2. 缓存 miss -> 走责任链
    MenuContext context = new MenuContext();
    context.setQuery(dto);
    handlerChain.proceed(context);

    // 3. 回写缓存
    MenuTreeVO result = context.getResult();
    cacheService.put(dto.getBranchCode(), dto.getUserId(), result);
    return result;
}
`

---

## 📊 应用与对比

### 两次 curl 验证

**第一次请求（缓存 miss -> 走完整链）**：
`
17:04:14.688 INFO  buildMenuTree start
17:04:14.689 INFO  cache miss, key=menu:tree:SH001:normal_user
17:04:14.707 INFO  chain step: LegacyCheckHandler
17:04:14.707 INFO  chain step: LoadMenuHandler
17:04:14.708 INFO  chain step: FallbackMatchHandler
17:04:14.708 INFO  chain step: PermissionFilterHandler
17:04:14.710 INFO  chain step: TreeBuildHandler
17:04:14.710 INFO  chain terminated at TreeBuildHandler
17:04:14.729 INFO  cache set, key=menu:tree:SH001:normal_user, ttl=PT10M
`

**第二次请求（缓存 hit -> 直接返回，不走链）**：
`
17:04:19.861 INFO  buildMenuTree start
17:04:19.880 INFO  cache hit, key=menu:tree:SH001:normal_user
`

### redis-cli 观察

`ash
redis> GET menu:tree:SH001:normal_user
"{\"@class\":\"...MenuTreeVO\",\"menuTree\":[...,\"nodeName\":\"首页\",...]}"

redis> TTL menu:tree:SH001:normal_user
(integer) 543   # 大约 9 分钟

redis> KEYS menu:*
1) "menu:tree:SH001:normal_user"
2) "menu:tree:SH001:luxury_user"
3) "menu:tree:BJ001:normal_user"
`

### 设计决策复盘

| 决策点 | 选了什么 | 为什么 | 面试怎么讲 |
|--------|---------|--------|-----------|
| key 维度 | branchCode + userId | 分行差异 + 用户权限差异共同决定菜单 | "一开始想用全局 key，但发现 SH001 有数字人民币 BJ001 没有、luxury_user 有黑卡 normal_user 没有——两个维度共同决定最终结果" |
| 序列化 | GenericJackson2JsonRedisSerializer | 存可读 JSON，方便排查 | "默认 JDK 序列化存进去是 \\xac\\xed 二进制，redis-cli 看不见，线上排查必须 Jackson" |
| TTL | 10 分钟 | 低频变化，等 10 分钟可以接受 | "菜单运营改完等 10 分钟生效没问题，库存价格这种才需要 1 分钟" |
| value 内容 | 完整菜单树 JSON | 一次序列化，命中直接返回 | "存扁平列表还要每次组树，白缓存了" |
| 清除策略 | TTL 过期 + evict() 预留接口 | 不做双删，简单可靠 | "双删一致性保证不了，菜单改了等 TTL 自动过期就行，要立刻生效加 evict" |

### 和同类方案对比

| 方案 | 实现复杂度 | 一致性保证 | 适用场景 | 我们选它的原因 |
|------|-----------|-----------|---------|--------------|
| 简单 TTL + evict | 低 | 最终一致（等 TTL 或手动清） | 低频变化数据 | 菜单改了等 10 分钟可以接受，简单可靠 |
| Cache-Aside 双删 | 中 | 弱一致（删了再删有竞态） | 必须立刻生效的数据 | 我们的场景不需要立刻生效 |
| MQ 通知清缓存 | 高 | 一致（但有延迟） | 多实例部署、修改频繁 | 过度设计，菜单种类不多 |

### 未来可以加什么

| 增强 | 怎么做 | 什么时候需要 |
|------|--------|------------|
| 缓存预热 | 系统启动时预加载所有分行 x 所有用户类型的菜单 | 菜单种类不多（7 分行 x 2 用户类型 = 14 条），启动花几秒预加载 |
| 缓存空值 | 某个 branchCode + userId 确实没有菜单，也缓存一个空树（防穿透） | 有大量不存在的 branchCode 请求时 |
| 二级缓存 | 本地 Caffeine（LRU）+ Redis | 单机 QPS 特别高（> 10000/s）时 |
| 缓存更新 | 运营改菜单时发 MQ -> 消费端清缓存 | 不想等 TTL 过期时 |

---

## 🔥 面试要点

### 高频问题 + 标准答案

**Q: 为什么 Redis 缓存 key 要用 branchCode + userId 两个维度？**
> "一开始想用全局 key，但踩了两个坑。第一，SH001 分行有数字人民币菜单（Fallback 匹配命中了 SH001 专属配置），BJ001 没有——只存 branchCode 会把 SH001 的菜单错给 BJ001。第二，luxury_user 有黑卡权限，normal_user 没有——只存 userId 会权限泄漏。**两个维度共同决定最终看到的菜单**，所以 key 必须包含这俩。"

**Q: Redis 序列化为什么选 Jackson？默认的不行吗？**
> "默认的 JdkSerializationRedisSerializer 存进去是 \\xac\\xed\\x00\\x05sr\\x00... 二进制，redis-cli 完全看不到内容。线上出问题了想查缓存存的是什么都查不了。GenericJackson2JsonRedisSerializer 存可读 JSON，@class 字段是 Jackson 加的类型信息，反序列化能正确还原。"

**Q: 缓存和责任链是什么关系？**
> "缓存是责任链的**前置拦截器**。缓存命中了，直接返回，**完全不进链**。只有缓存 miss 才走完整责任链。两者解耦——Handler 完全不知道缓存的存在，缓存也不关心链内部怎么实现。Service 层是中间编排者。"

**Q: 缓存更新怎么保证一致性？**
> "我们选了简单 TTL + evict() 预留接口。菜单是低频变化数据，运营改完等 10 分钟生效可以接受。如果以后需要立刻生效，加个 MQ 通知清缓存就行。为什么不用双删？双删在并发场景下有竞态条件（删了又被老数据覆盖），一致性不好保证。"

### 关键词速记

`
核心关键词：
  RedisTemplate / GenericJackson2JsonRedisSerializer
  两级 key（branchCode + userId）
  TTL 10 分钟
  Cache-Aside 模式（先查缓存 -> miss 查 DB -> 写缓存）
  前置拦截器

量化表述：
  "95%+ 请求不查 DB"
  "P99 从 300ms -> 50ms"
  "命中 < 1ms，miss 走完整链 ~10ms"
`

---

## 🧠 记忆口诀

`
菜单缓存口诀：
  "两个维度决定菜，一级 Redis 挡在外，
   TTL 十分等一等，Jackson 存可读 JSON。"
`

---

## 🔄 知识闭环

### Redis 缓存与项目其他部分的关联

```java
前端请求
  │
  ▼
Controller 校验参数
  │
  ▼
Service.buildMenuTree
  │
  ▼
┌────────────────────────────────────┐
│ Redis.get                          │
│ key: menu:tree:branchCode:userId   │
└────────┬───────────────────────────┘
         │
    ┌────┴────┐
    │  命中？  │
    └────┬────┘
    ┌────┼────┐
    ▼         ▼
  命中       未命中
    │         │
    ▼         ▼
直接返回   HandlerChain.proceed
    │         │
    │         ▼
    │    LegacyCheckHandler
    │         │
    │         ▼
    │    LoadMenuHandler
    │         │
    │         ▼
    │    FallbackMatchHandler
    │         │
    │         ▼
    │    PermissionFilterHandler
    │         │
    │         ▼
    │    TreeBuildHandler
    │         │
    │         ▼
    │     拿到菜单树
    │         │
    │         ▼
    │     Redis.set(回写缓存)
    │         │
    └────┬────┘
         ▼
       返回
```

### 三个技术专题的闭环关系

| 专题 | 在链路中的位置 | 解决什么 |
|------|--------------|---------|
| 10-责任链模式 | Service 层内部 | 把 5 步硬编码流水线解耦成可插拔的 Handler |
| **11-Redis缓存（本篇）** | **Service 层前置** | **95%+ 请求直接拦截，不走链、不查库** |
| 13-树构建方案 | TreeBuildHandler 内部 | O(n) 单趟 idMap 法组树 |

**三者形成层层递进的优化链**：Redis 先挡掉大部分请求 -> 挡不住的走责任链 -> 责任链里的最后一步用最优树算法。整体效果：**单请求从 300ms+（2 次 DB + 完整链）-> 命中 < 1ms / miss ~10ms。**