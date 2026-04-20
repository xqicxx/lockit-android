# 凭据搜索改进：模糊搜索与匹配排序

## 背景
当前搜索实现是简单的SQL LIKE查询，搜索name、service、type、key字段，存在以下限制：
- 不支持拼写容错（输入"qeen"无法匹配"qwen"）
- 不支持中文拼音搜索（输入"bai"无法匹配"百炼"）
- 结果无排序，不按匹配程度排列

## 设计方案

### 搜索流程
```
用户输入 → SQLite LIKE初步过滤 → 解密匹配的Entity → Kotlin二次处理 → 按匹配度排序 → 返回结果
```

LIKE先过滤减少数据量，然后只解密可能匹配的记录。

### 匹配评分算法
每个凭证计算匹配分数，按总分降序排列：

| 匹配类型 | 分数 | 说明 |
|---------|------|------|
| 完全匹配 | +100 | 字段值等于查询（忽略大小写） |
| 开头匹配 | +80 | 字段以查询开头 |
| 包含匹配 | +50 | 字段包含查询（LIKE已覆盖） |
| 拼音匹配 | +40 | 字段拼音包含查询（中文支持） |
| 拼写容错 | +30 | Levenshtein距离 ≤ 2 |

评分对象：name、service、type.displayName、key

### 新增依赖
```kotlin
implementation("com.github.promeg:tinypinyin:2.0.3")
```
TinyPinyin (~50KB) 用于中文转拼音。

### 新增文件
- `app/src/main/java/com/lockit/utils/SearchMatcher.kt`
  - `levenshteinDistance(a, b): Int` - 编辑距离计算
  - `toPinyin(text): String` - 中文转拼音
  - `matchScore(credential, query): Int` - 计算匹配分数

### 改动文件
- `VaultManager.kt` - `searchCredentials()`方法加入二次处理：
  - 解密LIKE返回的entities
  - 对每个credential计算matchScore
  - 按分数降序排序返回

### 性能考虑
- LIKE先过滤，减少解密数量
- 凭证数量预计几十到几百条，内存处理足够
- Levenshtein计算复杂度O(mn)，字段长度短时开销小

## 测试场景
1. 拼写容错：搜索"qeen"应匹配"qwen_bailian"
2. 拼音搜索：搜索"bai"应匹配"百炼"
3. 匹配排序：搜索"qwen"时，完全匹配"qwen"排在包含匹配"qwen_bailian"前
4. 混合匹配：中文凭证名如"阿里云"搜索"ali"应匹配拼音

## 实现顺序
1. 添加TinyPinyin依赖
2. 创建SearchMatcher.kt
3. 修改VaultManager.searchCredentials()
4. 测试验证