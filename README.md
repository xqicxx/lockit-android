# Lockit Android

Android 凭据管理器 App，遵循 Technical Brutalism 设计系统。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose
- **存储**: Room (SQLite)
- **加密**: AES-256-GCM + Argon2id (BouncyCastle)
- **架构**: MVVM

## 设置

### 1. 下载字体文件

从 Google Fonts 下载以下 TTF 文件到 `app/src/main/res/font/`:

- [Inter Regular](https://fonts.google.com/specimen/Inter) → `inter_regular.ttf`
- [Inter Bold](https://fonts.google.com/specimen/Inter) → `inter_bold.ttf`
- [Inter ExtraBold](https://fonts.google.com/specimen/Inter) → `inter_extrabold.ttf`
- [JetBrains Mono Regular](https://fonts.google.com/specimen/JetBrains+Mono) → `jetbrains_mono_regular.ttf`
- [JetBrains Mono Medium](https://fonts.google.com/specimen/JetBrains+Mono) → `jetbrains_mono_medium.ttf`

```bash
mkdir -p app/src/main/res/font/
# 下载后放入上述目录
```

### 2. 在 Android Studio 中打开

```
File → Open → 选择 lockit-android/ 目录
```

等待 Gradle 同步完成。

### 3. 构建

```bash
./gradlew assembleDebug
```

### 4. 运行

在 Android Studio 中点击 Run，或：

```bash
./gradlew installDebug
```

## 屏幕

| 屏幕 | 说明 |
|------|------|
| Vault Unlock | 主密码输入 / 创建 vault |
| Vault Explorer | 凭据列表 + 搜索 + 卡片 |
| Secret Details | 凭据详情 + 复制/显示/删除 |
| Add Credential | 添加新凭据 (14 种类型) |
| Config | 设置 + 安全信息 + 锁库 |

## 设计系统

- 0px 圆角 (RectangleShape)
- 1px 黑色边框
- 2px 纯黑偏移阴影
- Inter (UI) + JetBrains Mono (数据)
- 主色: 黑 #000000 / 橙 #B34700 / 红 #A30000

## 与 CLI 兼容

加密格式与 Rust CLI (`lockit/crypto.rs`) 完全兼容:
- AES-256-GCM
- `[12-byte nonce][ciphertext + 16-byte GCM tag]`
- Argon2id 密钥派生 (memory=64MB, iterations=3, parallelism=4)
