# ChatGPT / Claude / Coding Plan 认证机制调研

> Issue #118 文档整理

## 一、竞品项目参考

### 1. CodexBar（steipete/codexbar）

macOS 菜单栏 app，覆盖 16+ 平台。核心发现：

- 每个 provider 有多套认证路径（OAuth / Web cookies / CLI RPC / CLI PTY / API token）
- 按优先级自动 fallback：OAuth → CLI → Web
- 用量数据统一为 **primary（session）+ secondary（weekly）+ tertiary（model 独立）** 三层
- 支持 **Credits 余额** 追踪

### 2. caut（coding_agent_usage_tracker）

Rust CLI，16+ providers。定义了 caut.v1 JSON Schema：

```json
{
  "usage": {
    "primary": { "usedPercent": 28, "remainingPercent": 72, "windowMinutes": 180, "resetsAt": "..." },
    "secondary": { "usedPercent": 59, "remainingPercent": 41, "windowMinutes": 10080, "resetsAt": "..." },
    "tertiary": null,
    "identity": { "accountEmail": "...", "loginMethod": "..." }
  },
  "credits": { "remaining": 112.4 }
}
```

---

## 二、各平台认证机制

### 2.1 ChatGPT / Codex (OpenAI)

**认证路径（4 种）：**

| 路径 | 说明 | 优先级 |
|------|------|--------|
| OAuth API | `~/.codex/auth.json`，JWT token | 默认 |
| CLI RPC | `codex -s read-only`，JSON-RPC | 1 |
| CLI PTY | 发 `/status` 命令，解析终端输出 | 2（fallback） |
| Web Dashboard | WebView + cookie | 可选 |

**用量 API：**
```
GET https://chatgpt.com/backend-api/wham/usage
Headers: Authorization: Bearer <token>, ChatGPT-Account-Id: <id>
```

**返回字段：**
- `daily_limit` → 映射到 sessionUsed/sessionTotal
- `weekly_limit` → 映射到 weekUsed/weekTotal

### 2.2 Claude (Anthropic)

**认证路径（3 种）：**

| 路径 | 说明 |
|------|------|
| OAuth API | `GET https://api.anthropic.com/api/oauth/usage` |
| Web API | Cookie `sessionKey=sk-ant-sid01-xxx` + orgId |
| CLI PTY | `claude` CLI 发 `/usage` |

**Web API 端点：**
```
GET https://claude.ai/api/organizations/{orgId}/usage
GET https://claude.ai/api/organizations/{orgId}/overage_spend_limit
GET https://claude.ai/api/account
```

**返回字段（Claude Max）：**
- `five_hour` → sessionUsed/sessionTotal
- `seven_day` → weekUsed/weekTotal
- `seven_day_sonnet` → modelQuotas["Sonnet"]
- `seven_day_opus` → modelQuotas["Opus"]
- `extra_usage` → extraUsageSpent/extraUsageLimit

### 2.3 百炼 (Alibaba)

**认证方式：**
| 方式 | 说明 |
|------|------|
| Web cookies | 百炼控制台 cookie → Console RPC |
| API Key | 直接 API 调用 |

**已实现解析字段：**
- `per5HourUsedQuota` / `per5HourTotalQuota` → sessionUsed/sessionTotal
- `perWeekUsedQuota` / `perWeekTotalQuota` → weekUsed/weekTotal
- `perBillMonthUsedQuota` / `perBillMonthTotalQuota` → monthUsed/monthTotal
- `instanceName`, `instanceType`, `status`
- `remainingDays`, `chargeAmount`, `autoRenewFlag`

---

## 三、2026 年平台限制对比

| 计划 | Session | Weekly | Model独立 | Credits | 价格 |
|------|---------|--------|----------|---------|------|
| ChatGPT Plus | 3h ~160msg | 每日+每周 | o3/o4-mini独立 | — | $20/mo |
| Claude Pro | 5h 10-40 | 40-80h | — | — | $20/mo |
| Claude Max 5x | 5h 50-200 | 140-280h | Opus 15-35h | Extra $50 | $100/mo |
| Kimi Andante | 5h 200限流 | 1,024 req | — | — | ¥49/mo |
| 百炼 CodingPlan | 5h | 周 | 月 | — | ¥99/mo |

---

## 四、现有实现状态

### 已完成（PR #160）

- ✅ `CodingPlanQuota` 数据模型扩展（支持 modelQuotas, credits, reset times）
- ✅ `QwenCodingPlan` 百炼实现
- ✅ `ChatGPTCodingPlan` ChatGPT 实现
- ✅ `ClaudeCodingPlan` Claude 实现
- ✅ WebView Auth Clients（百炼/ChatGPT/Claude）

### 待优化

1. **Claude 多模型配额解析**：需适配 Sonnet/Opus 独立周配额
2. **凭据过期检测**：各平台凭据有效期不同
3. **Kimi / Gemini 支持**：国内平台，新增 provider

---

## 五、参考项目

| 项目 | 平台 | 语言 | GitHub |
|------|------|------|--------|
| CodexBar | macOS | Swift | steipete/codexbar |
| caut | 跨平台 | Rust | Dicklesworthstone/coding_agent_usage_tracker |
| Claude-Code-Usage-Monitor | 跨平台 | Python | Maciek-roboblog/Claude-Code-Usage-Monitor |
| claude-code-limit-tracker | Claude Code | Python | TylerGallenack/claude-code-limit-tracker |