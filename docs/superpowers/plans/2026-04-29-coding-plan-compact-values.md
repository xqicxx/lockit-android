# Coding Plan 展开详情值排列 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 展开详情去掉字段名标签，每个 provider 只显示 4 个值 FlowRow 排开，紧凑行已有的不重复。

**Architecture:** 在 CodingPlanComponents.kt 新增 ValueRow 组件，5 个 provider content 文件各自用 4 个精选值调用 ValueRow 替代原来的 InfoGrid。用量条和 StatusChip 原样保留。

**Tech Stack:** Kotlin, Jetpack Compose, Material3

---

### Task 1: 新增 ValueRow 组件

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/CodingPlanComponents.kt`

- [ ] **Step 1: 在 BoardDivider 之前添加 ValueRow**

在 `BoardDivider()` 函数上方插入：

```kotlin
@Composable
internal fun ValueRow(values: List<String>) {
    if (values.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/CodingPlanComponents.kt
git commit -m "feat: add ValueRow component for label-free value display"
```

---

### Task 2: Claude 展开值排列

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/ClaudeCodingPlanContent.kt`

- [ ] **Step 1: 替换 InfoGrid 为 ValueRow**

将整个函数体替换为：

```kotlin
@Composable
internal fun ClaudeCodingPlanContent(quota: CodingPlanQuota) {
    val emailUser = quota.accountEmail.substringBefore("@")

    val values = buildList {
        if (quota.instanceName.isNotBlank()) add(quota.instanceName)
        if (emailUser.isNotBlank()) add(emailUser)
        if (quota.remainingDays > 0) add("${quota.remainingDays} days")
        if (quota.status.isNotBlank()) add(quota.status.uppercase())
    }.take(4)

    ValueRow(values = values)

    // Claude has 5h + week + month windows, show usage + reset times
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, quota.monthResetsAt, true, true, Modifier.weight(1f))
    }
}
```

- [ ] **Step 2: 清理不再使用的 import**

删除不再使用的 `import androidx.compose.ui.res.stringResource` 和 `import com.lockit.R`

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/ClaudeCodingPlanContent.kt
git commit -m "feat: Claude expanded view shows 4 values without labels"
```

---

### Task 3: ChatGPT 展开值排列

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/ChatGptCodingPlanContent.kt`

- [ ] **Step 1: 替换 InfoGrid 为 ValueRow**

将整个函数体替换为：

```kotlin
@Composable
internal fun ChatGptCodingPlanContent(quota: CodingPlanQuota) {
    val emailUser = quota.accountEmail.substringBefore("@")
    val renewText = when {
        quota.autoRenewFlag -> stringResource(R.string.repos_quota_auto_renew)
        quota.chargeType.equals("subscription", ignoreCase = true) -> stringResource(R.string.repos_quota_manual_renew)
        quota.chargeType.isNotBlank() -> quota.chargeType.uppercase()
        else -> null
    }

    val values = buildList {
        if (emailUser.isNotBlank()) add(emailUser)
        if (quota.remainingDays > 0) add("${quota.remainingDays} days")
        if (quota.status.isNotBlank()) add(quota.status.uppercase())
        renewText?.let { add(it) }
    }.take(4)

    ValueRow(values = values)

    // Status chip
    if (quota.status.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val s = quota.status.uppercase()
            val isGood = s == "ACTIVE" || s == "VALID"
            StatusChip(text = s, color = if (isGood) IndustrialOrange else TacticalRed, filled = isGood)
        }
    }

    // ChatGPT only has 5h + weekly windows (no monthly quota)
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, false, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, false, true, Modifier.weight(1f))
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/ChatGptCodingPlanContent.kt
git commit -m "feat: ChatGPT expanded view shows 4 values without labels"
```

---

### Task 4: DeepSeek 展开值排列

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/DeepSeekCodingPlanContent.kt`

- [ ] **Step 1: 替换 InfoGrid 为 ValueRow**

将整个函数体替换为：

```kotlin
@Composable
internal fun DeepSeekCodingPlanContent(quota: CodingPlanQuota) {
    val values = buildList {
        if (quota.loginMethod.isNotBlank()) add(quota.loginMethod.uppercase())
        if (quota.instanceType.isNotBlank()) add(quota.instanceType.uppercase())
        if (quota.status.isNotBlank()) add(quota.status.uppercase())
        add("Unlimited")
    }.take(4)

    ValueRow(values = values)

    BoardDivider()
    Row(modifier = Modifier.fillMaxWidth()) {
        StatusChip(
            text = "ACTIVE",
            color = IndustrialOrange,
            filled = true,
        )
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/DeepSeekCodingPlanContent.kt
git commit -m "feat: DeepSeek expanded view shows 4 values without labels"
```

---

### Task 5: Mimo 展开值排列

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/MimoCodingPlanContent.kt`

- [ ] **Step 1: 替换 InfoGrid 为 ValueRow**

将整个函数体替换为：

```kotlin
@Composable
internal fun MimoCodingPlanContent(quota: CodingPlanQuota) {
    val values = buildList {
        if (quota.sessionUsed > 0) add(formatTokens(quota.sessionUsed))
        if (quota.sessionTotal > 0) add(formatTokens(quota.sessionTotal))
        val pct = if (quota.sessionTotal > 0) (quota.sessionUsed * 100L / quota.sessionTotal).toInt() else 0
        add("$pct%")
        if (quota.loginMethod.isNotBlank()) add(quota.loginMethod.uppercase())
    }.take(4)

    ValueRow(values = values)

    BoardDivider()
    Row(modifier = Modifier.fillMaxWidth()) {
        val active = quota.status.equals("ACTIVE", true) || quota.status.equals("VALID", true)
        StatusChip(
            text = if (active) "ACTIVE" else quota.status.uppercase().take(8),
            color = if (active) IndustrialOrange else TacticalRed,
            filled = active,
        )
    }
}
```

保留 `formatTokens` 函数不动。

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/MimoCodingPlanContent.kt
git commit -m "feat: Mimo expanded view shows 4 values without labels"
```

---

### Task 6: Qwen 展开值排列

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/repos/QwenCodingPlanContent.kt`

- [ ] **Step 1: 替换 InfoGrid 为 ValueRow**

将整个函数体替换为：

```kotlin
@Composable
internal fun QwenCodingPlanContent(quota: CodingPlanQuota) {
    val values = buildList {
        if (quota.instanceName.isNotBlank()) add(quota.instanceName)
        if (quota.remainingDays > 0) add("${quota.remainingDays} days")
        if (quota.chargeType.isNotBlank()) add(quota.chargeType.uppercase())
        if (quota.loginMethod.isNotBlank()) add(quota.loginMethod.uppercase())
    }.take(4)

    ValueRow(values = values)

    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (quota.autoRenewFlag) StatusChip(text = stringResource(R.string.repos_quota_auto_renew), color = IndustrialOrange)
        if (quota.chargeType.isNotBlank()) StatusChip(text = quota.chargeType.uppercase(), color = IndustrialOrange)
        if (quota.status.isNotBlank()) {
            val active = quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true)
            StatusChip(text = quota.status.uppercase(), color = if (active) IndustrialOrange else TacticalRed, filled = active)
        }
    }

    // Bailian has 5h + week + month windows, show usage counts
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, null, true, false, Modifier.weight(1f))
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lockit/ui/screens/repos/QwenCodingPlanContent.kt
git commit -m "feat: Qwen expanded view shows 4 values without labels"
```

---

### Task 7: 安装验证

- [ ] **Step 1: 编译安装到设备**

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: 手动验证**

打开 APP → 编程计划 → 依次展开 5 个 provider，确认每个只显示 4 个值，无字段标签，和紧凑行不重复。
