# 开发状态

当前仓库已实现生命周期作用域、资源/视图作用域、窗口 Insets 与监听器所有权、`BoundedMailbox` 有界任务调度，以及可选帧预算、动态资源加载和扩展释放。

## 发布与版本基线

- **Maven Central 状态**：`1.0.0`、`1.1.0` 与 `1.2.0` 均已发布至 Maven Central（含 `.asc` 签名），并由家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 统一对齐版本号。`1.2.0` 在 `1.1.0`（新增 API 37.2 `MemoryBudgetObserver` 观察桥）的基础上纳入了共享唤醒停摆修复、邮箱结构与关闭/取消路径加固。
- **家族同版本状态**：MeguvizUI（含 `meguviz-ui-android-bridge` 与 `meguviz-ui-ref-bridge`）、MeguvizRef、MeguvizDrawable（含 `meguviz-drawable-meguviz-android`）、MeguvizHook 与 DexMeguviz 的 `1.2.0` 制品均已发布至 Maven Central；`LumenCoacervationEngine`（含 `lumen-bridge-meguviz-android`）`1.6.2` 也已发布至 Maven Central（`meguviz-ui-lumen-bridge` 与 `meguviz-drawable-lumen` 暂未进 Central，仅支持源码构建）。
- **Kotlin 工具链与元数据**：自 `1.2.0` 起，仓库根 `build.gradle.kts` 通过 KGP 类路径锚点使用 Kotlin `2.4.21` 编译（不再采用 AGP 默认的 `2.2.10`）。唯一包含 Kotlin 源码的 `android-window` 因此携带 Kotlin `2.4.0` 元数据并依赖 `kotlin-stdlib:2.4.21`；`android-runtime` 与 `android-lifecycle` 为纯 Java 模块。Kotlin `2.2` 编译器可读取已发布的 `1.0.0` / `1.1.0`，无法读取 `1.2.0` 的 `android-window`（需 Kotlin `2.3` 及以上版本）。纯 Java 消费方不受元数据版本限制，仅会传递引入 `kotlin-stdlib:2.4.21`。
- **编译兼容性说明**：当前组合为 Gradle `9.8.2`、AGP `9.4.1` 与 KGP `2.4.21`，已在本机编译通过，但超出 Kotlin 官方兼容表中 KGP `2.4.21` 的完全支持上限（Gradle `9.7.0`、AGP `9.3.1`），具体环境适配可参考 [Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)。

## 源码快照与校验记录

- 本快照对窗口测试夹具做了中性包名脱敏处理，并在根构建脚本中维持公开 API 边界检查与签名准备入口（缺少真实密钥时本地签名准备任务按预期阻塞）。
- 样例工程中固定的部分本地候选校验值分别构建于 JDK `25.0.5` 与 JDK `21.0.12`；纯 Java 模块（`android-runtime`、`android-lifecycle`）在两者之间产出一致，而含 Kotlin 产物的 `android-window` 在重钉版本前以 JDK `21.0.12` 构建。
- 本仓库提供 `scripts/run-gradle.ps1 verifyHost`（要求 JDK `25` 或 `27`）及 `scripts/verify_central_local.py` 用于复现宿主验证与制品元数据核验。

## 适用范围与验证说明

- 仓库文档中记录的历史宿主单测、隔离消费者（R8 / 依赖图）与模拟器数据，反映对应提交节点在受测环境下的执行快照，不随当前脱敏快照附带原始日志，也不替代目标真机上的实际兼容与性能验收。
- 新接入或修改源码后，应以当前工程与目标设备重新执行的 `verifyHost` / `verifyDevice` 与业务集成测试结果为准。
