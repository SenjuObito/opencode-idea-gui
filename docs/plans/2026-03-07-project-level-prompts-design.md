# 项目级别提示词存储功能设计

## 概述

在现有全局提示词存储（`~/.codemoss/prompt.json`）的基础上，增加项目级别的提示词存储功能。项目提示词存储在项目根目录的 `.codemoss/prompt.json` 文件中，实现团队共享和项目特定的提示词管理。

## 需求分析

### 核心需求

1. **双层存储**：支持全局提示词和项目提示词并存
2. **独立管理**：全局和项目提示词完全独立，各自拥有完整的增删改查功能
3. **UI 分离展示**：Settings 页面中分两个区域展示全局和项目提示词
4. **聊天集成**：聊天输入框自动补全同时显示两种提示词，带标签区分
5. **多项目支持**：只显示当前活动项目的提示词

### 用户场景

- **全局提示词**：用户个人常用的提示词，跨所有项目使用
- **项目提示词**：项目特定的提示词，可通过 Git 与团队共享

## 技术方案

### 方案选择：抽象基类设计

采用抽象基类模式，提取公共逻辑，通过继承实现全局和项目两种管理器。

**优势**：
- 代码复用高，消除重复
- 职责清晰，易于维护
- 易于扩展（未来可支持团队级、云端提示词）
- 符合 SOLID 设计原则

## 架构设计

### 类层次结构

```
AbstractPromptManager (抽象基类)
├── GlobalPromptManager (全局提示词管理器)
└── ProjectPromptManager (项目提示词管理器)

PromptScope (枚举)
└── GLOBAL, PROJECT

PromptManagerFactory (工厂类)
└── 根据 scope 创建对应的 Manager
```

### 核心类职责

#### AbstractPromptManager

抽象基类，定义提示词管理的通用接口和实现。

**职责**：
- 定义模板方法：`getStoragePath()`, `ensureStorageDirectory()`
- 实现通用业务逻辑：增删改查、验证、冲突检测、批量导入
- 提供 Gson 和通用工具方法

**关键方法**：
```java
// 抽象方法（子类实现）
protected abstract Path getStoragePath() throws IOException;
protected abstract void ensureStorageDirectory() throws IOException;

// 通用实现
public List<JsonObject> getPrompts() throws IOException;
public void addPrompt(JsonObject prompt) throws IOException;
public void updatePrompt(String id, JsonObject updates) throws IOException;
public boolean deletePrompt(String id) throws IOException;
public Map<String, Object> batchImportPrompts(...) throws IOException;
```

#### GlobalPromptManager

全局提示词管理器，继承 `AbstractPromptManager`。

**实现**：
```java
@Override
protected Path getStoragePath() {
    return pathManager.getPromptFilePath(); // ~/.codemoss/prompt.json
}

@Override
protected void ensureStorageDirectory() throws IOException {
    pathManager.ensureConfigDirectory();
}
```

#### ProjectPromptManager

项目提示词管理器，继承 `AbstractPromptManager`。

**实现**：
```java
private final Project project;

@Override
protected Path getStoragePath() {
    if (project == null || project.getBasePath() == null) {
        throw new IllegalStateException("Project not available");
    }
    return Paths.get(project.getBasePath(), ".codemoss", "prompt.json");
}

@Override
protected void ensureStorageDirectory() throws IOException {
    Path dir = getStoragePath().getParent();
    if (!Files.exists(dir)) {
        Files.createDirectories(dir);
    }
}
```

#### PromptScope 枚举

```java
public enum PromptScope {
    GLOBAL("global"),
    PROJECT("project");

    private final String value;

    PromptScope(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static PromptScope fromString(String value) {
        for (PromptScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown scope: " + value);
    }
}
```

#### PromptManagerFactory 工厂类

```java
public class PromptManagerFactory {
    public static AbstractPromptManager create(
        PromptScope scope,
        Gson gson,
        ConfigPathManager pathManager,
        Project project
    ) {
        switch (scope) {
            case GLOBAL:
                return new GlobalPromptManager(gson, pathManager);
            case PROJECT:
                if (project == null) {
                    throw new IllegalArgumentException("Project required for PROJECT scope");
                }
                return new ProjectPromptManager(gson, project);
            default:
                throw new IllegalArgumentException("Unknown scope: " + scope);
        }
    }
}
```

### 代码重构计划

#### 1. PromptManager 重构

**现有**：
- `PromptManager` 类直接操作全局配置

**重构后**：
- 将 `PromptManager` 重命名为 `AbstractPromptManager` 并抽象化
- 提取路径相关方法为抽象方法
- 保持所有业务逻辑不变

#### 2. CodemossSettingsService 重构

**现有**：
```java
private final PromptManager promptManager;

public List<JsonObject> getPrompts() {
    return promptManager.getPrompts();
}
```

**重构后**：
```java
private final Gson gson;
private final ConfigPathManager pathManager;
private final Project project;

public AbstractPromptManager getPromptManager(PromptScope scope) {
    return PromptManagerFactory.create(scope, gson, pathManager, project);
}

public List<JsonObject> getPrompts(PromptScope scope) {
    return getPromptManager(scope).getPrompts();
}
```

## UI 设计

### Settings 页面布局

**当前结构**：
```
提示词库
├── 描述文字
├── 操作按钮（导出、导入、创建）
└── 自定义提示词列表
```

**新结构**：
```
提示词库
├── 描述文字
├── 全局提示词区域
│   ├── 标题："全局提示词"
│   ├── 操作按钮（导出、导入、创建）
│   └── 提示词列表
└── 项目提示词区域
    ├── 标题："项目提示词 - <项目名称>"
    ├── 操作按钮（导出、导入、创建、复制到全局）
    └── 提示词列表
```

### 组件设计

#### PromptSection 组件重构

**Props 扩展**：
```typescript
interface PromptSectionProps {
  globalPrompts: PromptConfig[];
  projectPrompts: PromptConfig[];
  projectName: string | null;  // null 表示无活动项目
  loading: boolean;
  onAdd: (scope: PromptScope) => void;
  onEdit: (prompt: PromptConfig, scope: PromptScope) => void;
  onDelete: (prompt: PromptConfig, scope: PromptScope) => void;
  onExport: (scope: PromptScope) => void;
  onImport: (scope: PromptScope) => void;
  onCopyToGlobal?: (prompt: PromptConfig) => void;
}
```

#### 新增组件：PromptScopeSection

独立的作用域提示词展示组件，复用于全局和项目两个区域。

```typescript
interface PromptScopeSectionProps {
  title: string;
  scope: PromptScope;
  prompts: PromptConfig[];
  loading: boolean;
  showCopyToGlobal?: boolean;
  onAdd: () => void;
  onEdit: (prompt: PromptConfig) => void;
  onDelete: (prompt: PromptConfig) => void;
  onExport: () => void;
  onImport: () => void;
  onCopyToGlobal?: (prompt: PromptConfig) => void;
}
```

### 聊天输入框自动补全增强

#### 数据模型扩展

```typescript
interface PromptItem {
  id: string;
  name: string;
  content: string;
  scope: 'global' | 'project';  // 新增字段
  createdAt?: number;
  updatedAt?: number;
}
```

#### 显示格式

```
提示词名称 [全局]
提示词名称 [项目]
```

#### promptProvider 重构

```typescript
class PromptProvider {
  async fetchPrompts(): Promise<PromptItem[]> {
    // 并行请求全局和项目提示词
    const [globalPrompts, projectPrompts] = await Promise.all([
      this.fetchPromptsForScope('global'),
      this.fetchPromptsForScope('project')
    ]);

    // 合并并添加 scope 标签
    return [
      ...projectPrompts.map(p => ({ ...p, scope: 'project' })),
      ...globalPrompts.map(p => ({ ...p, scope: 'global' }))
    ];
  }
}
```

## 后端消息处理

### PromptHandler 消息协议扩展

#### 现有消息类型

```
get_prompts
add_prompt
update_prompt
delete_prompt
export_prompts
import_prompts_file
save_imported_prompts
```

#### 扩展消息格式

所有消息增加 `scope` 参数：

```json
{
  "type": "get_prompts",
  "scope": "global"
}

{
  "type": "add_prompt",
  "scope": "project",
  "prompt": {
    "id": "xxx",
    "name": "项目提示词",
    "content": "..."
  }
}
```

#### 新增消息类型

```json
// 将项目提示词复制到全局
{
  "type": "copy_prompt_to_global",
  "promptId": "xxx"
}

// 获取当前项目信息
{
  "type": "get_project_info"
}
```

### 数据流设计

#### 获取提示词流程

```
前端加载 Settings
  ↓
并行发送两个请求
  ├─ sendToJava({ type: 'get_prompts', scope: 'global' })
  └─ sendToJava({ type: 'get_prompts', scope: 'project' })
  ↓
PromptHandler 处理
  ├─ 解析 scope 参数
  ├─ 调用 settingsService.getPrompts(scope)
  ├─ GlobalPromptManager → ~/.codemoss/prompt.json
  └─ ProjectPromptManager → <project>/.codemoss/prompt.json
  ↓
返回结果
  ├─ callJavaScript('updateGlobalPrompts', globalPrompts)
  └─ callJavaScript('updateProjectPrompts', projectPrompts)
```

#### 聊天自动补全流程

```
promptProvider.fetchPrompts()
  ↓
并行请求
  ├─ sendToJava({ type: 'get_prompts', scope: 'global' })
  └─ sendToJava({ type: 'get_prompts', scope: 'project' })
  ↓
合并结果，添加 scope 标签
  ↓
返回给自动补全组件
```

## 国际化

### 新增翻译 Key

```json
{
  "settings.prompt.global": "全局提示词",
  "settings.prompt.project": "项目提示词",
  "settings.prompt.projectScope": "项目提示词 - {projectName}",
  "settings.prompt.copyToGlobal": "复制到全局",
  "settings.prompt.noProject": "无活动项目",
  "settings.prompt.scopeLabel.global": "全局",
  "settings.prompt.scopeLabel.project": "项目"
}
```

## 边界情况处理

### 1. 无活动项目

- Settings 页面：项目提示词区域显示"无活动项目"占位符
- 聊天自动补全：只显示全局提示词

### 2. 项目路径不存在

- `ProjectPromptManager.getStoragePath()` 抛出 `IllegalStateException`
- UI 捕获异常，显示友好错误信息

### 3. 多项目场景

- 始终只显示当前活动项目的提示词
- 切换项目时，自动刷新项目提示词列表

### 4. 权限问题

- 创建 `.codemoss` 目录失败：捕获 IOException，提示用户检查权限
- 读写 `prompt.json` 失败：降级为空列表，不中断用户操作

### 5. ID 冲突

- 全局和项目提示词的 ID 可以相同（因为存储路径不同）
- 导入导出时保持现有冲突策略（SKIP/OVERWRITE/DUPLICATE）

## 测试策略

### 单元测试

- `AbstractPromptManager` 通用逻辑测试
- `GlobalPromptManager` 路径解析测试
- `ProjectPromptManager` 路径解析测试
- `PromptManagerFactory` 工厂创建测试
- `PromptScope` 枚举解析测试

### 集成测试

- 全局提示词增删改查
- 项目提示词增删改查
- 导入导出功能（全局和项目）
- 复制到全局功能

### E2E 测试

- Settings 页面加载全局和项目提示词
- 创建、编辑、删除操作
- 导入导出操作
- 聊天输入框自动补全显示

## 实施计划

### Phase 1：后端重构（2 天）

1. 创建 `PromptScope` 枚举
2. 将 `PromptManager` 重构为 `AbstractPromptManager`
3. 实现 `GlobalPromptManager` 和 `ProjectPromptManager`
4. 实现 `PromptManagerFactory`
5. 重构 `CodemossSettingsService`
6. 更新 `PromptHandler` 消息处理

### Phase 2：前端实现（1.5 天）

1. 扩展 TypeScript 类型定义
2. 创建 `PromptScopeSection` 组件
3. 重构 `PromptSection` 组件
4. 更新 `usePromptManagement` hook
5. 更新 `promptProvider`
6. 添加国际化翻译

### Phase 3：测试和优化（1 天）

1. 编写单元测试
2. 编写集成测试
3. 手动测试边界情况
4. 性能优化
5. 代码审查和重构

### Phase 4：文档和发布（0.5 天）

1. 更新用户文档
2. 更新 CHANGELOG
3. 提交代码并创建 PR

**总计：约 5 个工作日**

## 风险评估

| 风险 | 影响 | 缓解措施 |
|-----|------|---------|
| 重构引入 Bug | 高 | 充分的单元测试和集成测试 |
| 多项目场景复杂 | 中 | 简化设计，只显示当前活动项目 |
| 权限问题 | 中 | 完善错误处理和用户提示 |
| UI 布局复杂 | 低 | 复用现有组件，渐进式增强 |

## 未来扩展

1. **团队级提示词**：支持团队共享的提示词存储
2. **云端同步**：将提示词同步到云端账号
3. **提示词模板市场**：社区共享的提示词模板
4. **智能推荐**：基于项目类型推荐相关提示词
5. **版本控制**：提示词的历史版本管理

## 参考资料

- 现有 PromptManager 实现：`src/main/java/com/github/claudecodegui/settings/PromptManager.java`
- Settings UI：`webview/src/components/settings/PromptSection/index.tsx`
- 聊天自动补全：`webview/src/components/ChatInputBox/providers/promptProvider.ts`
