# 可观测性：Alertmanager 告警治理

---

## 📑 目录

- [📌 一句话核心](#一句话核心)
- [🎯 背景与问题](#背景与问题)
- [💡 核心目的](#核心目的)
- [⚙️ 原理与机制](#原理与机制)
  - [路由、分组、抑制、静默](#路由分组抑制静默)
  - [抑制和静默的区别](#抑制和静默的区别)
- [📊 应用与对比](#应用与对比)
  - [当前路由配置](#当前路由配置)
  - [TargetDown 抑制规则](#targetdown-抑制规则)
  - [创建维护静默](#创建维护静默)
  - [验证抑制生效](#验证抑制生效)
  - [验证严重级别路由](#验证严重级别路由)
  - [常用运维命令](#常用运维命令)
- [🔥 面试要点](#面试要点)
- [🧠 记忆口诀](#记忆口诀)
- [🔄 知识闭环](#知识闭环)

---

## 📌 一句话核心

> <span style="color:red">**告警治理的目标是让一条通知代表一个需要处理的问题，而不是代表所有相关异常。**</span>

---

## 🎯 背景与问题

应用整体不可用时，Prometheus 可能同时算出：

```text
NavigationMenuTargetDown
HighServerErrorRate
HighMenuTreeLatency
```

这三个告警可能来自同一个根因，但如果全部发送出去，值班人员会收到多封邮件，需要自己判断它们是否有关。

常见问题是：

- 告警风暴：一次故障刷出大量通知
- 根因和现象混在一起
- warning 和 critical 使用相同提醒频率
- 发布期间无法临时关闭指定告警
- 恢复后没有通知，值班人员不知道服务是否恢复

---

## 💡 核心目的

让 Alertmanager 在通知之前完成分诊：

```java
Prometheus 判断告警是否成立
        ↓
Alertmanager 决定通知谁、通知几次、是否抑制、是否静默
```

本项目要解决三件事：

1. TargetDown 时，抑制同一实例的错误率和延迟告警
2. 发布维护时，用静默临时屏蔽已知告警
3. critical 和 warning 路由到不同接收器，并使用不同重复间隔

---

## ⚙️ 原理与机制

### 路由、分组、抑制、静默

| 能力 | 作用 | 本项目配置 |
|:---|:---|:---|
| Route | 按标签决定由哪个 receiver 接收 | `severity=critical` / `severity=warning` |
| Group | 把相关告警合并为一封通知 | `group_by: ['alertname', 'severity']` |
| Inhibit | 某条根因告警成立时，抑制相关衍生告警 | TargetDown 抑制错误率和延迟 |
| Silence | 在指定时间窗内屏蔽匹配的告警 | 维护窗口使用 10 分钟静默 |
| Repeat interval | 同一告警未恢复时多久提醒一次 | critical 5 分钟，warning 15 分钟 |

### 抑制和静默的区别

| 对比 | 抑制 Inhibition | 静默 Silence |
|:---|:---|:---|
| 触发方式 | 由另一条告警自动触发 | 人手动创建或由系统 API 创建 |
| 典型场景 | TargetDown 时屏蔽衍生告警 | 发布、迁移、维护窗口 |
| 是否临时 | 只要源告警存在就持续 | 有明确开始和结束时间 |
| 是否由 Alertmanager 配置 | 是 | 运行时数据，不写在 YAML |
| 适合处理 | 根因与衍生告警关系 | 已知的人为操作 |

> 🔑 **抑制是“我看到了根源，别重复提醒症状”；静默是“我已经知道这段时间会告警，先别发”。**

---

## 📊 应用与对比

### 当前路由配置

公开示例配置：

[alertmanager.example.yml](../../../ops/alertmanager/alertmanager.example.yml)

实际运行文件 `ops/alertmanager/alertmanager.yml` 由配置脚本生成，包含真实邮箱地址，已被 `.gitignore` 排除。

路由结构：

```yaml
route:
  receiver: qq-email-warning
  routes:
    - matchers:
        - severity="critical"
      receiver: qq-email-critical
      repeat_interval: 5m
    - matchers:
        - severity="warning"
      receiver: qq-email-warning
      repeat_interval: 15m
```

两个 receiver 都发送到 QQ 邮箱，但邮件主题带不同前缀：

```text
[CRITICAL] ...
[WARNING]  ...
```

这样可以在邮箱里直接判断严重程度。

### QQ 邮箱通知渠道

Alertmanager 通过 `smtp.qq.com:465` 和隐式 TLS 发送真实邮件。

关键配置：

```yaml
email_configs:
  - to: '<收件地址>'
    from: '<发件 QQ 邮箱>'
    smarthost: 'smtp.qq.com:465'
    auth_username: '<发件 QQ 邮箱>'
    auth_password_file: '/etc/alertmanager/secrets/qq_smtp_auth_code'
    require_tls: true
    force_implicit_tls: true
    send_resolved: true
```

授权码存放和配置生成使用：

[configure-qq-email.ps1](../../../ops/alertmanager/configure-qq-email.ps1)

```powershell
powershell -ExecutionPolicy Bypass `
  -File ops/alertmanager/configure-qq-email.ps1 -SendTest
```

> ⚠️ SMTP 授权码等价于发信密码。`.gitignore` 只能防止误提交，密钥文件仍是明文；生产环境应使用 Vault、Docker Secret、Kubernetes Secret 或云密钥管理服务。

本次实测邮件通知：

```text
alertmanager_notifications_total{integration="email"}        = 3
alertmanager_notifications_failed_total{integration="email"} = 0
```

### TargetDown 抑制规则

```yaml
inhibit_rules:
  - source_matchers:
      - alertname="NavigationMenuTargetDown"
    target_matchers:
      - alertname=~"HighServerErrorRate|HighMenuTreeLatency"
    equal: ['job', 'instance']
```

读取方式：

- 源告警：`NavigationMenuTargetDown`
- 被抑制：错误率和延迟告警
- 只有 `job` 和 `instance` 都相同才抑制

`equal` 很重要。A 实例不可用，不能抑制 B 实例的错误率告警。

### 创建维护静默

创建一条只匹配测试实例的 10 分钟静默：

```powershell
docker exec alertmanager-demo amtool silence add `
  alertname=HighMenuTreeLatency `
  instance=silence-demo:8080 `
  --duration=10m `
  --author=Codex `
  --comment="Demo maintenance window" `
  --alertmanager.url=http://localhost:9093
```

本次实测静默：

```text
id: bcb754ab-c821-4170-98e5-92e715980ddd
createdBy: Codex
comment: Demo maintenance window
duration: 10m
status: active
```

发送匹配告警后：

```text
alertname: HighMenuTreeLatency
state: suppressed
silencedBy: bcb754ab-c821-4170-98e5-92e715980ddd
```

### 验证抑制生效

使用独立测试实例，避免影响真实 `host.docker.internal:8080`：

```powershell
docker exec alertmanager-demo amtool alert add `
  NavigationMenuTargetDown severity=critical `
  job=navigation-menu instance=inhibit-demo:8080 `
  --annotation=summary="inhibit source demo" `
  --alertmanager.url=http://localhost:9093

docker exec alertmanager-demo amtool alert add `
  HighServerErrorRate severity=critical `
  job=navigation-menu instance=inhibit-demo:8080 `
  --annotation=summary="inhibit target demo" `
  --alertmanager.url=http://localhost:9093
```

Alertmanager API 查询结果：

| alertname | state | inhibitedBy |
|:---|:---|:---|
| `NavigationMenuTargetDown` | `active` | 空 |
| `HighServerErrorRate` | `suppressed` | `cac0770c0c7f9977` |

说明同一实例上的错误率告警已经被 TargetDown 抑制。

### 验证严重级别路由

```powershell
docker exec alertmanager-demo amtool config routes test `
  --config.file=/etc/alertmanager/alertmanager.yml `
  alertname=HighServerErrorRate severity=critical
```

输出：

```text
qq-email-critical
```

```powershell
docker exec alertmanager-demo amtool config routes test `
  --config.file=/etc/alertmanager/alertmanager.yml `
  alertname=HighMenuTreeLatency severity=warning
```

输出：

```text
qq-email-warning
```

### 常用运维命令

检查配置：

```powershell
docker exec alertmanager-demo `
  amtool check-config /etc/alertmanager/alertmanager.yml
```

重载配置：

```powershell
curl.exe -X POST http://localhost:9093/-/reload
```

查看告警：

```powershell
curl.exe -s http://localhost:9093/api/v2/alerts
```

查看静默：

```powershell
docker exec alertmanager-demo amtool silence query `
  --alertmanager.url=http://localhost:9093
```

提前结束静默：

```powershell
docker exec alertmanager-demo amtool silence expire `
  <silence-id> `
  --alertmanager.url=http://localhost:9093
```

> ⚠️ **静默测试必须限定 `instance` 或 `alertname`**。不要随意创建只匹配 `severity=critical` 的测试静默，否则可能把真实生产告警一起屏蔽。

---

## 🔥 面试要点

**Q1：告警太多怎么处理？**

> **面试话术**：*"先区分根因告警和衍生告警。TargetDown 这类根因成立时，用 inhibition 抑制同实例的错误率和延迟告警；发布维护则用有明确结束时间的 silence。再用 route 和 group_by 控制接收人、分组和重复频率，避免同一故障产生多封重复邮件。"*

**Q2：抑制和静默有什么区别？**

> **面试话术**：*"抑制是自动规则，由另一条更明确的告警触发，适合根因和症状的关系；静默是人工创建的维护窗口，适合发布、迁移和已知变更。抑制依赖标签匹配，静默有明确开始结束时间。"*

**Q3：为什么不直接关掉错误率告警？**

> **面试话术**：*"错误率告警本身有价值，只是 TargetDown 时它属于衍生噪音。不能永久关闭，而是用抑制规则在根因告警存在期间临时屏蔽，根因恢复后错误率告警继续正常工作。"*

---

## 🧠 记忆口诀

```text
路由决定发给谁
分组决定合成几封
抑制让根因压住症状
静默让人工屏蔽已知变更
恢复通知负责闭环
```

---

## 🔄 知识闭环

```java
Prometheus 同时算出 TargetDown + 错误率 + 延迟
      │
      ▼
Alertmanager 按 severity 路由
      │
      ├─ critical → qq-email-critical
      └─ warning  → qq-email-warning
      │
      ▼
TargetDown 成立
      │
      └─ 抑制同 job + instance 的衍生告警
      │
      ▼
发布维护
      │
      └─ 创建限定范围的 10 分钟 silence
      │
      ▼
告警恢复 → resolved 邮件 → 值班闭环
```
