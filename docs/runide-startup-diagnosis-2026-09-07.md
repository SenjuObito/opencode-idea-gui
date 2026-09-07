# runIde 启动报错排查记录（2026-09-07）

> 排查对象：`gradlew runIde`（沙箱 IDE：IntelliJ IDEA Community 2024.3.1 / Build #IC-243.22562.145）
> 相关日志：`build/idea-sandbox/IC-2024.3.1/log/idea.log`（含 2026-09-06 与 2026-09-07 两次运行）

## 一、结论速览

| # | 现象 | 严重性 | 定性 |
|---|------|--------|------|
| 1 | `GradleJvmSupportMatrix` 组件初始化失败（`IllegalArgumentException: 25`） | SEVERE | **IDE 内置 Gradle 插件的已知 bug，非本项目问题**，仅产生日志噪音，不影响插件功能 |
| 2 | git "dubious ownership" → "Unknown repository state" / DETACHED 警告 | WARN | **本机 git 安全策略配置问题**，一行命令修复 |
| 3 | 其余（WSL 未安装、共享索引、preload 警告等） | WARN | 可忽略的环境噪音 |
| 4 | （9月6日历史）ai-bridge 找不到目录 + ToolWindow 超时 | SEVERE | **已在当前代码修复**，9月7日运行已正常 |

构建链路本身全部成功：webview 构建（10.3MB 单文件 HTML）、`ai-bridge.zip` 打包（0.24MB）、沙箱插件复制、IDE 启动，均无异常。

---

## 二、唯一真正的 SEVERE 错误：GradleJvmSupportMatrix（IDE 内置插件 bug）

### 错误链

```
SEVERE - #c.i.c.ComponentStoreImpl - Cannot init component state
         (componentName=GradleJvmSupportMatrix, componentClass=GradleJvmSupportMatrix)
         [Plugin: com.intellij.gradle]
Caused by: java.lang.IllegalArgumentException: 25
    at com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:308)
    at org.jetbrains.plugins.gradle.jvmcompat.IdeVersionedDataParser$Companion.parseVersion(IdeVersionedDataParser.kt:21)
    at org.jetbrains.plugins.gradle.jvmcompat.GradleJvmSupportMatrix.getCompatibilityRanges(GradleJvmSupportMatrix.kt:44)
    at org.jetbrains.plugins.gradle.jvmcompat.GradleJvmSupportMatrix.applyState(...)
    at org.jetbrains.plugins.gradle.jvmcompat.IdeVersionedDataStorage.loadState(...)
```

### 根因

IC-2024.3.1 内置的 Gradle 插件（`gradle.jar` 的 `org.jetbrains.plugins.gradle.jvmcompat` 包）在启动时加载其捆绑的 **JVM 兼容性数据**（IDE 版本 × 支持的 Java 版本区间，按 IDE 版本分发并持久化在沙箱 `system/` 下）。数据里包含了 **Java 25** 这个版本号，而 243 平台版本的 `com.intellij.util.lang.JavaVersion.parse` 无法解析 "25"，抛出 `IllegalArgumentException: 25`，导致该组件状态初始化失败。

关键点：**这是 IDE 内置 Gradle 插件的问题，不是 OpenCode Buddy 插件的问题**。报错中 `Plugin: com.intellij.gradle` 是沙箱 IDE 自带的捆绑插件，无法通过升级本项目依赖修复（本项目代码未使用该组件）。

### 关联 YouTrack issue

- **MPS-38662** — "Cannot init component state (componentName=GradleJvmSupportMatrix, componentClass=GradleJvmSupportMatrix)"，与本项目所见完全一致（https://youtrack.jetbrains.com/issue/MPS-38662）
- **IDEA-372251** — "IntelliJ IDEA 2023.3.8 crashes when opening Maven settings – internal JavaVersion.parse bug"（同一 `JavaVersion.parse` 解析 bug 家族）
- **IDEA-382057** — "Critical: IDE ignores idea.disable.gradle.jvm.compat.check=true in all configurations, causing startup crash"（证实存在官方逃生开关系统属性，但该 issue 标题称其被忽略；对 2024.3.1 是否生效需实测）

### 影响评估

- 不致命：IDE 正常启动，插件（opencode-buddy）功能不受影响（详见 9月7日日志中 bridge 解压成功记录）。
- 每次 `runIde` 都会在控制台/日志刷出这一段 SEVERE 堆栈，属于**日志噪音**。
- Gradle 相关面板功能（Gradle JVM 兼容性提示）可能降级，但本项目开发中不用该功能。

### 可选缓解（按成本排序）

1. **加系统属性（零成本，先实测）**：`build.gradle` 的 `runIde` 任务加 `-Didea.disable.gradle.jvm.compat.check=true`（IDEA-382057 提及的官方逃生开关）。注意该 issue 标题称此属性被忽略，实测一次即可知道是否有效。
2. **升级目标 IDE**：把 `build.gradle` 中 `intellijIdeaCommunity('2024.3.1')` 升到较新的 IC 版本（例如 2025.x），新版的 Gradle 插件数据/解析器已修复。
3. **什么都不做**：接受日志噪音（功能无影响）。

---

## 三、git dubious ownership → "Unknown repository state"（本机配置，一行修复）

### 现象

```
WARN - #git4idea.repo.GitRepositoryReader - detected dubious ownership in repository at 'I:/source/repos/opencode-idea-gui'
'I:/source/repos/opencode-idea-gui' is owned by:
    BUILTIN/Administrators (S-1-5-32-544)
but the current user is:
    OBITOSTUDIO/10049 (S-1-5-21-1879502060-3514112040-4247518294-1001)
```

随后连锁产生：`Unknown repository state`、`Couldn't identify neither current branch nor current revision`、`Current revision is null in DETACHED state. isFresh: true` 等警告。

### 根因与修复

仓库目录属主是 `BUILTIN/Administrators`（I 盘常见，目录由管理员组创建），与当前用户 SID 不一致，git ≥2.35.2 的 safe.directory 检查拒绝操作。git4idea 拿不到任何仓库信息，才出现上述连锁警告。仓库本身完好（`.git/HEAD` 正常指向 `refs/heads/main`，与 origin/main 有共同历史）。

修复（在任意终端执行一次即可）：

```
git config --global --add safe.directory I:/source/repos/opencode-idea-gui
```

执行后重启 runIde，上述 git 警告全部消失。

---

## 四、9月6日历史问题（已修复，佐证当前状态健康）

9月6日 21:33 的沙箱日志里曾出现本项目插件自己的 SEVERE：

```
SEVERE - #com.opencodebuddy.bridge.BridgeDirectoryResolver - [BridgeResolver] Failed to find valid ai-bridge directory...
SEVERE - #com.opencodebuddy.ui.toolwindow.ClaudeSDKToolWindow - [ToolWindow] ai-bridge preparation timeout
```

根因：当时 `BridgeArchiveLocator` 按旧目录名（`idea-claude-code-gui`）和 plugin id（`com.senjuobito.opencode-buddy`）找 `ai-bridge.zip`，均落空——实际沙箱目录名是 `opencode-buddy-jetbrains`（取自 `rootProject.name`）。

当前代码（`BridgeArchiveLocator.addPluginRootCandidates`，`BridgeArchiveLocator.java:112-127`）已加入**枚举所有已安装插件目录**的兜底逻辑，9月7日运行日志证实已正常：

```
[BridgeResolver] No extracted ai-bridge found, starting extraction:
  I:\...\plugins\opencode-buddy-jetbrains\ai-bridge.zip
[BridgeResolver] Successfully extracted using system unzip command
[BridgeResolver] Unzip completed, extractedDir exists: true
```

结论：bridge 解析问题已自愈，无需再处理。

---

## 五、可忽略噪音清单（无需处理）

| 警告 | 原因 |
|------|------|
| `Failed to run wsl.exe --list --verbose`（stdout 为 GBK 乱码） | 本机未安装 WSL 发行版，IDE 启动时探测失败，正常 |
| `Bundled shared index is not found at ...jdk-shared-indexes` | 缓存的 IDE 分发包未捆绑共享索引，正常 |
| `Conflicting registry key definition ... kotlin.mpp.tests.force.gradle` | Kotlin 插件内部注册表键重复定义，JetBrains 自身问题 |
| `preload=NOT_HEADLESS/TRUE must be used only for core services`（Kotlin/Maven/CodeWithMe 共 7 条） | 捆绑插件的预加载配置不符合新规范，JetBrains 自身问题 |
| `WARNING: A command line option has enabled the Security Manager`（3 条 JVM 警告） | IntelliJ 243 仍使用 Security Manager，Java 21 提示弃用，正常 |
| `Archived non-system classes are disabled...`（CDS 警告） | 自定义 ClassLoader 与 CDS 不兼容，正常 |
| `'ToolwindowTitle' toolbar manual update is ignored`（堆栈含 `TerminalMonitorService.java:131`） | 内部模式（`idea.is.internal=true`）下框架的诊断信息，非错误；TerminalMonitorService 通过 getContentManager 触发 Terminal 工具窗创建属正常启动流程 |
| `detected dubious ownership`（git4idea 多次） | 见第三节，safe.directory 修复后消失 |

---

## 六、代码卫生建议（非本次修改，仅记录）

1. `src/main/java/com/opencodebuddy/action/vcs/GenerateCommitMessageAction.java:170` 使用已弃用的字符串键查找：
   ```java
   return (CommitMessage) e.getDataContext().getData("CommitMessage");
   ```
   建议改用类型安全的 `e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)`。
2. `OpenCodeMessageHandler.java` 也使用了已过时 API（编译警告，见构建输出）。
3. 根目录的 `hs_err_pid23696.log` 是 2026-09-06 00:55 Gradle **守护进程**（`-Xmx512m`，构建沙箱用）native 内存分配失败（OOM）的 JVM 崩溃转储，与 IDE 启动无关，可删除。后续如频繁出现，可在 `gradle.properties` 加 `org.gradle.jvmargs=-Xmx1g` 缓解。

---

## 七、后续行动建议（按优先级）

1. **执行 git 修复**（一次搞定，消除最大一块警告噪音）：
   `git config --global --add safe.directory I:/source/repos/opencode-idea-gui`
2. **删除根目录 `hs_err_pid23696.log`**（无用崩溃转储）。
3. GradleJvmSupportMatrix：若日志噪音困扰开发，实测 `-Didea.disable.gradle.jvm.compat.check=true`；无效再考虑升级 targetIde。不处理也不影响功能。
