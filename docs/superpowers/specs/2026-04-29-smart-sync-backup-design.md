# Smart Sync + Auto Backup Design

## Overview

四层同步系统：自动备份（本地 3 天）→ 自动同步（云端 3 月）→ 一键 SYNC → 手动 push/pull。增/删/改凭证自动触发备份+同步，可选择历史备份恢复。

## Architecture

```
Credential CRUD
  └─→ VaultAutoSync.onCredentialChanged()
        ├─→ VaultBackupManager.snapshot()     // 本地备份
        └─→ VaultSyncEngine.push(LastWriteWins)  // 云端同步（后台）
```

### Components

| 组件 | 类型 | 职责 |
|------|------|------|
| `VaultBackupManager` | NEW | 本地快照：snapshot / list / restore / cleanup(3d) |
| `CloudBackupStore` | NEW (interface) | 云端备份接口：uploadBackup / listBackups / deleteBackup / cleanupOld(3mo) |
| `VaultAutoSync` | NEW | 监听 VaultManager CRUD 事件 → 触发备份+同步 |
| `VaultSyncEngine` | MODIFY | 加 sync()、push/pull(strategy)、ResolveStrategy、SyncOutcome |
| `GoogleDriveBackend` | MODIFY | 实现 CloudBackupStore |
| `WebDavBackend` | MODIFY | 实现 CloudBackupStore |
| `ConfigScreen` | MODIFY | SYNC 按钮 + 备份列表 + 恢复 |

### Dependencies

```
VaultAutoSync
  ├── VaultBackupManager      (interface — 可替换存储实现)
  ├── VaultSyncEngine         (concrete)
  └── CloudBackupStore        (interface — 可替换云端实现)

VaultSyncEngine
  ├── SyncBackend
  ├── SyncKeyManager
  ├── SyncStateStore
  └── VaultFileProvider
```

## Interfaces

### VaultBackupManager

```kotlin
interface VaultBackupManager {
    suspend fun snapshot(): Result<BackupMeta>      // 创建快照
    fun list(): List<BackupMeta>                     // 列出所有备份（已排序）
    suspend fun restore(backupId: String): Result<Unit>  // 恢复到指定备份（先备份当前）
    fun cleanup(maxAge: Duration = 3.days)           // 删除过期备份
}

data class BackupMeta(
    val id: String,           // timestamp-based
    val timestamp: Instant,
    val entryCount: Int,
    val sizeBytes: Long,
)
```

### CloudBackupStore

```kotlin
interface CloudBackupStore {
    suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit>
    suspend fun listBackups(): Result<List<CloudBackupMeta>>
    suspend fun deleteBackup(backupId: String): Result<Unit>
    suspend fun cleanupOld(maxAge: Duration = 90.days): Result<Unit>
}
```

## Smart Sync API

### VaultSyncEngine additions

```kotlin
// One-click smart sync
suspend fun sync(): Result<SyncOutcome>

// Push/pull with auto-resolve strategy
suspend fun push(strategy: ResolveStrategy): Result<Unit>
suspend fun pull(strategy: ResolveStrategy): Result<Unit>

// Strict push/pull — conflict throws (existing, unchanged)
suspend fun push(): Result<Unit>
suspend fun pull(): Result<Unit>

enum class ResolveStrategy { KeepLocal, KeepCloud, LastWriteWins }
enum class SyncOutcome { AlreadyUpToDate, Pushed, Pulled, LocalWon, CloudWon }
```

### sync() logic

```
getSyncStatus()
  UpToDate     → AlreadyUpToDate
  NeverSynced  → forcePush (no conflict possible)
  LocalAhead   → push(LastWriteWins)
  CloudAhead   → pull(LastWriteWins)
  Conflict     → compare timestamps:
                   local.newer → push(LastWriteWins) → LocalWon
                   cloud.newer → pull(LastWriteWins) → CloudWon
  Error        → failure
```

## Retention Rules

| | 本地 | 云端 |
|------|------|------|
| 位置 | `lockit/backups/vault_{ts}.db` | `lockit-sync/backups/vault_{ts}.enc` |
| 保留期 | 3 天 | 3 个月 |
| 触发 | 每次 CRUD | 每次 push 附带 |
| 清理 | 同步时检查 | 每次 push 后清理 |

## UI

### Config Screen layout

```
┌──────────────────────────┐
│   SYNC KEY CONFIG        │
├──────────────────────────┤
│       SYNC               │  ← 一键智能同步
├──────────────────────────┤
│  PUSH          PULL      │  ← 手动精细控制
├──────────────────────────┤
│   RESTORE BACKUPS        │
│   04-29 14:32  23 items  │  ← 选择恢复
│   04-29 10:15  22 items  │
│   04-28 22:01  20 items  │
├──────────────────────────┤
│      RESTORE SELECTED    │
└──────────────────────────┘
```

### Restore flow

1. 列出所有本地备份（时间戳 + 条目数）
2. 用户选择一个
3. 确认对话框（显示时间、条目数）
4. 自动备份当前状态 → 替换 vault.db → 提示完成

## Error Handling

- 自动备份失败 → 日志，不阻塞 CRUD
- 自动同步失败 → 静默，下次 CRUD 重试
- 恢复失败 → 回滚到备份前的状态
- 云端不可达 → sync() 返回 Error，不崩溃
- 同步密匙未配 → 自动备份照常，同步跳过

## Testability

- `VaultBackupManager` 接口可 mock 存储
- `CloudBackupStore` 接口可 mock 云端
- `ConflictDetector` 纯逻辑已是 object，可直接单测
- `VaultAutoSync` 注入 mock，可验证 CRUD → 备份+同步 调用链
