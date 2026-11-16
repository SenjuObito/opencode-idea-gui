# Agent Instructions & Guidelines

本文档为 AI Agent 在本项目中协同开发时的指导准则与执行规范。

## 1. Git 提交规范 (Commit Message Conventions)

所有代码提交必须严格遵循以下格式规范：

### 格式要求
```text
<gitemoji> <中文描述>
```
例如：
- `✨ 启动页与徽标支持全息流动 Loading 动效`
- `🐛 修复长会话流式回复重复显示`
- `💄 优化状态胶囊悬浮阴影与毛玻璃模糊度`
- `⚡ 降低启动加载面板重绘频率以减少 CPU 开销`
- `♻️ 重构多会话隔离事件分发逻辑`

### 常用 Gitmoji 对照
| Gitmoji | 适用场景 |
| :--- | :--- |
| ✨ `:sparkles:` | 新增功能 (Feature) |
| 🐛 `:bug:` | 修复缺陷 (Bug Fix) |
| 💄 `:lipstick:` | 更新 UI 样式与视觉效果 (Style / UI) |
| ♻️ `:recycle:` | 代码重构 (Refactoring) |
| ⚡ `:zap:` | 性能优化 (Performance) |
| 📝 `:memo:` | 文档新增或更新 (Documentation) |
| 🔧 `:wrench:` | 构建工具或配置文件调整 (Configuration) |
| 🧪 `:test_tube:` | 增加或修改测试用例 (Testing) |
| 🚀 `:rocket:` | 部署或发布版本 (Release) |

---

## 2. 跨端一致性与架构守则
- **双端同步**：任何涉及 Webview / 启动层的变动，必须同时考量 VS Code 插件与 IntelliJ IDEA 插件（JCEF/Swing）的一致性与兼容性。
- **性能守则**：启动加载阶段避免重型 JS 阻塞，首帧尽量使用 CSS/SVG 原生硬件加速实现；Java 端 Swing Timer 确保在 `removeNotify` 时妥善销毁防泄漏。
