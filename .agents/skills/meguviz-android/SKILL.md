---
name: meguviz-android
description: 在 Android 工程中接入 MeguvizAndroid 1.2.0（WindowSession、LifecycleAdapters、ViewScope、BoundedMailbox、ResourceScope、InsetsSlot、ScopedResourceLoader），或将手工 Activity/Fragment/Dialog 监听管理、无界 Handler.post 更新 View 与冲突的 WindowInsets/Callback 写法迁移至作用域化绑定。涉及原生界面树与声明式属性系统时转交 MeguvizUI，涉及可复用绘制配方与 FlightCache 时转交 MeguvizDrawable，涉及玻璃 blur/折射材质与动效时转交 LumenCoacervationEngine。
---

# MeguvizAndroid 接入与迁移

面向需要接入作用域化窗口会话与有界任务调度、或改造存量生命周期与异步回调泄漏的 Android 工程，按以下四个阶段完成盘点、选型、迁移与验证。

## 第 1 阶段：盘点工程现状

1. **检查构建配置与编译基线**
   - 读取 `settings.gradle(.kts)`、`build.gradle(.kts)`、`gradle/libs.versions.toml` 与模块 `AndroidManifest.xml`。
   - 确认工程满足基线：`compileSdk` 为 `37` 或更新版本，`minSdk` 为 `27` 或更高，Java 编译目标为 `17`（`JavaVersion.VERSION_17`）。
   - **Kotlin 元数据检查**：`meguviz-android-window` 包含 Kotlin `2.4.0` 元数据并依赖 `kotlin-stdlib:2.4.21`。Kotlin 消费方须使用 Kotlin `2.3` 及以上版本（推荐 `2.4.21`）；纯 Java 消费方无此编译版本限制，仅在运行时传递引入 `kotlin-stdlib:2.4.21`。
2. **检索存量调用点与风险**
   - 手工生命周期监听：在 `onStart`、`onStop`、`onDestroy` 中分散注册和移除回调，或 Fragment 误绑定自身生命周期而非 `viewLifecycleOwner`。
   - 跨线程更新 UI：后台线程直接调用 `Handler.post` / `View.post`，在 View 复用（如 RecyclerView）或页面销毁后写回旧数据。
   - 覆盖式系统槽位：直接调用 `ViewCompat.setOnApplyWindowInsetsListener`、`Window.setCallback` 或 `ViewTreeObserver.addOnPreDrawListener`，导致多个宿主互相覆盖或分离后漏释。
   - 动态资源与配置派生：手工调用 `Resources.addLoaders` 或未隔离局部主题的派生缓存。
3. **输出短评估后再改代码**
   - 列出待改造页面/宿主类型、所需窗口权限级别（独占窗口还是仅观察），以及 Kotlin / `compileSdk` 基线是否达标。

## 第 2 阶段：选择模块与接入依赖

确认构建脚本启用 `google()` 与 `mavenCentral()`。四个模块均已在 Maven Central 发布 `1.2.0`（`groupId` 均为 `io.github.jichuo1.meguviz`，`minSdk 27`），可直接写版本或导入 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`：

| 场景 | 选用模块 | 传递依赖与说明 |
| --- | --- | --- |
| Activity / Fragment / Dialog 自动或手动生命周期桥 | `meguviz-android-lifecycle` | 主入口；传递引入 `meguviz-android-window`、`androidx.lifecycle`、`androidx.appcompat` |
| 非 AndroidX `Activity`、窗口会话、`InsetsSlot`、Kotlin 扩展 | `meguviz-android-window` | 不引入 AppCompat/Lifecycle；传递引入 `meguviz-android-runtime` 与 `androidx.core` |
| 纯作用域、有界邮箱、帧调度、资源域、内存预算观察 | `meguviz-android-runtime` | 不引入 AndroidX；含 `MemoryBudgetObserver`（API 37.2 运行时探测） |
| API 30+ 动态从 APK 加载与替换资源 | `meguviz-android-resources-loader` | 可选；API 27–29 调用 `open` 返回 `Status.UNSUPPORTED` |

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle")
    // 仅在需要动态加载外部 APK 资源时按需加入：
    // implementation("io.github.jichuo1.meguviz:meguviz-android-resources-loader")
}
```

**边界与转交原则**：不接入未使用的桥接库。需要完整原生 View 树与声明式属性调度时，转交 `$meguviz-ui`（桥接模块 `meguviz-ui-android-bridge`）；需要跨 View/Compose 复用的绘制配方或 `FlightCache` 时，转交 `$meguviz-drawable`（`meguviz-drawable-meguviz-android`）；需要玻璃 blur/折射材质与手势动效时，转交 `$lumen-coacervation-engine`（`io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2`，独立发布版本，不在 `1.2.0` BOM 内）。

## 第 3 阶段：执行最小迁移与生命周期对齐

### 1. 核心骨架：窗口会话、绑定租约与有界邮箱

```java
// 1. 在 ComponentActivity / AppCompatActivity 的 onCreate 中打开并自动绑定窗口会话
WindowSession session = LifecycleAdapters.openWindow(
        this, WindowSession.Permission.OWN_WINDOW);

// 2. 为目标 View 建立作用域、绑定租约与有界邮箱
ViewScope rowScope = session.scope(targetView);
BindingLease lease = rowScope.binding(ResourceState.ALL);
BoundedMailbox mailbox = rowScope.openMailbox(16, 32, 8);
BoundedMailbox.Slot slot = mailbox.newSlot(); // newSlot 必须在主线程调用

// 3. 后台线程安全提交最新状态；rebind() 会立即作废旧 WorkToken
BoundedMailbox.Submission sub = mailbox.offerState(slot, lease.token(), () ->
        rowScope.commit(lease, () -> renderOnMainThread(rowScope.view())));
if (!sub.accepted()) {
    // 邮箱拒收（容量满或已关闭）时不会触发 release，如有自持资源须在此立即释放
}
```

### 2. Fragment View 与非 AndroidX Dialog 对齐

```java
// Fragment：必须绑定到 viewLifecycleOwner，并于 onDestroyView 置空引用
ViewScope fragmentScope = hostSession.scope(view);
LifecycleAdapters.bindFragmentView(this, fragmentScope);

// Dialog 或原生 Activity：使用独立会话与 ManualWindowOwner
WindowSession dialogSession = WindowSession.open(
        dialog.getWindow(), dialog.getContext(),
        getPackageName() + ":dialog", WindowSession.Permission.OWN_WINDOW);
ManualWindowOwner manualOwner = new ManualWindowOwner(dialogSession);
manualOwner.onStart();
dialog.setOnDismissListener(d -> manualOwner.onDismiss());
```

### 3. 线程、权限与回退铁律

1. **主线程约束**：`openWindow`、`WindowSession.open`、`scope`、`rebind`、`commit`、`close`、`newSlot`、`setPaused` 等均只能在 owner（主）线程调用；仅 `BoundedMailbox.offer*`、`Offers.submit` 与 `ScopedResourceLoader.prepareApk` 允许后台线程调用。
2. **窗口权限边界**：同一 `Window` 同时只允许一个 `Permission.OWN_WINDOW` 会话；插件宿主或只读观察方必须使用 `OBSERVE` 或 `APPLY_LOCAL`。
3. **槽位接管与释放**：`InsetsSlot.claimExclusive` 独占监听器，交还外部接管前先调用 `handOffToExternalOwner()`；`WindowCallbackLease` 与 `Registration` 均遵循幂等 `close()`。
4. **低版本回退**：`ScopedResourceLoader.open` 在 API 27–29 返回 `Status.UNSUPPORTED` 且 `loader == null`，`MemoryBudgetObserver.observeProcess` 在低于 API 37.2 时返回 `null`，调用方须显式分支降级。

> **按需深入阅读**：完整模块坐标、公开 API 签名与家族桥接说明见 [API 与模块速查](references/api-and-modules.md)；存量写法迁移对照、常见异常排障与回退方案见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方工程验证**
   - 依赖对齐校验：`./gradlew :app:dependencies --configuration debugRuntimeClasspath`，确认全部 `meguviz-android-*` 制品解析为 `1.2.0` 且无 `kotlin-stdlib` 冲突。
   - 编译、Lint 与混淆构建：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。
   - 生命周期与并发行为核对：覆盖屏幕旋转重建、列表项 `rebind()` 后迟到结果丢弃、Fragment `onDestroyView` 释放、Dialog 关闭、窗口销毁后后台线程 `offer*` 返回 `REJECTED_CLOSED`。
2. **MeguvizAndroid 仓库源码自检（维护本库时执行）**
   - 完整宿主门禁（要求环境变量 `JAVA_HOME` 指向 JDK 25 或 JDK 27）：`pwsh -File scripts/run-gradle.ps1 verifyHost`（覆盖四模块单测、Lint、`dumpApi`、独立 `samples` 构建与负例门禁）。
   - 单模块调试运行（Robolectric SDK 37 需 JDK 21+）：`./gradlew :android-runtime:testDebugUnitTest :android-window:testDebugUnitTest :android-lifecycle:testDebugUnitTest :android-resources-loader:testDebugUnitTest --no-daemon`。
3. **回退检查与交付摘要**
   - 核对所有自注册 `Registration`、`WindowSession` 与 `ScopedResourceLoader` 在异常或退出路径均可到达 `close()`。
   - 交付摘要须明确：接入模块与版本、改动文件与权限级别、实测通过的验证命令及范围，以及按页面独立回退步骤。
