# Coding Plan 展开详情值排列设计

## 目标

展开详情去掉字段名标签（PLAN、STATUS 等），只保留值，一行 FlowRow 排开，紧凑行已有的内容不重复。

## 设计

### 通用规则

- InfoGrid / QuotaInfoCell 不再用于 provider 展开详情
- 每个 provider 改为 FlowRow 排值，8dp 间距
- 仅保留 4 个值，和紧凑行不重复
- 用量条（QuotaGauge）保持不变
- 邮箱取 @ 前面部分

### 各 Provider 展开值

| Provider | 值1 | 值2 | 值3 | 值4 | 用量条 |
|----------|-----|-----|-----|-----|--------|
| DeepSeek | auth method | instance type | status | "Unlimited" | 无 |
| Claude | instanceName | email(user) | remainingDays + "days" | status | 5h/Wk/Mo |
| ChatGPT | email(user) | remainingDays + "days" | status | "Auto Renew" / chargeType | 5h/Wk |
| Mimo | sessionUsed | sessionTotal | usage% | auth method | 无 |
| Qwen | instanceName | remainingDays + "days" | chargeType | loginMethod | 5h/Wk/Mo |

### 改动文件

- `CodingPlanComponents.kt` — 新增 FlowRow 组件替代 InfoGrid
- `ClaudeCodingPlanContent.kt`
- `ChatGptCodingPlanContent.kt`
- `DeepSeekCodingPlanContent.kt`
- `MimoCodingPlanContent.kt`
- `QwenCodingPlanContent.kt`
