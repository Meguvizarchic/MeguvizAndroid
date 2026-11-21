# MeguvizAndroid API 与模块速查

本文件列出 `MeguvizAndroid` `1.2.0` 已发布制品的真实坐标、构建与工具链门槛、核心公开 API 签名，以及与 Meguviz 家族其他库的协作边界。

## 1. 制品坐标与环境要求

所有模块均已发布至 Maven Central（groupId：`io.github.jichuo1.meguviz`，版本：`1.2.0`），并受 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 管理。

| Artifact ID | minSdk | compileSdk | Java 字节码 | 传递依赖 | 核心职责 |
| --- | --- | --- | --- | --- | --- |
| `meguviz-android-runtime` | `27` | `37` | `17` | 无 AndroidX 依赖 | 作用域、生命周期代次、有界邮箱、帧调度、资源状态、`MemoryBudgetObserver` |
| `meguviz-android-window` | `27` | `37` | `17` | `meguviz-android-runtime`、`androidx.core:core:1.19.1`、`kotlin-stdlib:2.4.21` | `WindowSession`、`ViewScope`、`InsetsSlot`、`WindowCallbackLease`、`ObserverRegistration`、Kotlin 扩展 |
| `meguviz-android-lifecycle` | `27` | `37` | `17` | `meguviz-android-window`、`androidx.lifecycle:lifecycle-runtime:2.8.7`、`androidx.appcompat:appcompat:1.8.0` | `LifecycleAdapters`、`ManualWindowOwner` |
| `meguviz-android-resources-loader` | `27` | `37` | `17` | `meguviz-android-runtime` | `ScopedResourceLoader`（API 30+ 动态加载 APK 资源） |

> **工具链与元数据提示**：
> - `android-runtime` 内部以 `compileSdkMinor = 2` 编译 `MemoryBudgetApi37` 并以运行时完整版本检查保护，其 AAR `minCompileSdk` 仍声明为 `37`，消费方无需升级到 `37.2`。
> - 仅 `android-window` 包含 Kotlin 源码（Kotlin `2.4.21` 编译，携带 `2.4.0` 元数据）。Kotlin 消费方需 Kotlin `2.3+`；纯 Java 消费方无编译版本要求，但会传递引入 `kotlin-stdlib:2.4.21`。
> - 仓库内部 `testkit`（`ControlledExecutor`、`TestClock`）为测试专用模块，不随 Maven 制品发布。

## 2. 核心公开 API 签名速查

### 2.1 `android-lifecycle`（`io.github.jichuo1.meguviz.android.lifecycle`）

- `LifecycleAdapters`
  - `static <A extends Activity & LifecycleOwner> WindowSession openWindow(A activity, WindowSession.Permission permission)`
  - `static <A extends Activity & LifecycleOwner> WindowSession openWindow(A activity, String namespace, WindowSession.Permission permission)`
  - `static Registration bindWindow(LifecycleOwner owner, WindowSession session)`
  - `static Registration bindViewOwner(LifecycleOwner viewOwner, ViewScope scope)`
  - `static Registration bindFragmentView(Fragment fragment, ViewScope scope)`（必须在 `onCreateView` 之后调用，绑定 `fragment.getViewLifecycleOwner()`）
- `ManualWindowOwner implements AutoCloseable`
  - `ManualWindowOwner(WindowSession session)`
  - `onStart()` / `onStop()` / `onVisibilityChanged(boolean visible)` / `onDismiss()` / `close()`

### 2.2 `android-window`（`io.github.jichuo1.meguviz.android.window` 与 `.extensions`）

- `WindowSession implements AutoCloseable`
  - `enum Permission { OBSERVE, APPLY_LOCAL, OWN_WINDOW }`
  - `static WindowSession open(Window window, Context context, String namespace, Permission permission)`
  - `static WindowSession open(OwnerDispatcher owner, FrameScheduler scheduler, Window window, Context context, String namespace, Permission permission, ErrorSink errors)`
  - `ViewScope scope(View view)` / `ViewScope scope(View view, String resourceNamespace)`
  - `ResourceScope resources()` / `ResourceScope resourcesFor(View view)`
  - `void setStarted(boolean)` / `void setVisible(boolean)` / `void refreshConfiguration()`
  - `Registration own(Registration registration)` / `WindowFrameChannel openFrameChannel(Object identity, FrameScheduler.FrameWork callback, int quota)`
  - `LifecycleScope.CloseRequest requestClose()` / `void close()`
- `ViewScope implements AutoCloseable`
  - `BindingLease binding(int resourceDependencies)`（传 `0`、`ResourceState.RESOURCES`、`ResourceState.THEME` 或 `ResourceState.ALL`）
  - `<T extends AutoCloseable> T ownBinding(T registration)` / `Registration own(Registration registration)`
  - `BoundedMailbox openMailbox(int stateCapacity, int commandCapacity, int quota)`
  - `void rebind()` / `LifecycleScope.CommitResult commit(BindingLease binding, Runnable callback)`
  - `void setOwnerStarted(boolean)` / `void setVisible(boolean)` / `void close()`
- `InsetsSlot implements AutoCloseable`
  - `static InsetsSlot claimExclusive(ViewScope scope)`
  - `Registration enableAnimationUpdatesInOwnedSlot()`
  - `Registration observe(Consumer<InsetsSnapshot> callback)`
  - `LocalLayout applyPaddingToOwnedContainer(int typeMask)` / `LocalLayout applyMarginsToOwnedContainer(int typeMask)`（需 `APPLY_LOCAL` 或以上权限；`LocalLayout.rebaseBaseline()`、`close()`）
  - `void handOffToExternalOwner()` / `void close()`
- `InsetsSnapshot`、`InsetsValue`、`InsetsBaseline`
  - `InsetsSnapshot.SUPPORTED_TYPES`、`List<TypeInsets> types()`、`InsetsValue combined(int typeMask)`
  - `InsetsValue(int left, int top, int right, int bottom)`、`ZERO`、`plus(...)`、`max(...)`
- `ObserverRegistration` 与 `WindowCallbackLease`
  - `static Registration onPreDraw(ViewScope scope, Runnable callback)`
  - `static WindowCallbackLease install(WindowSession session, UnaryOperator<Window.Callback> wrapper)`（需 `OWN_WINDOW` 权限）
- Kotlin 扩展（`io.github.jichuo1.meguviz.android.extensions`）
  - `MailboxExtensionsKt`: `inline fun BoundedMailbox.Offers.submitBatch(crossinline block: BoundedMailbox.Offers.() -> Unit): Int`（异常时先 `clear()` 再抛出；`return@submitBatch` 提交已暂存项）
  - `PlatformExtensionsKt`: `AndroidVersion`、`PackageInfo.versionCodeCompat`、`Configuration.isUiInNightMode`、`toPx`、`spToPx`、`toast`、`enableComponent`/`disableComponent`/`isActivityComponentEnabled`、`View.rootStatusBarInsetTopOrNull()`
  - `ViewExtensionsKt`: `TextView.textColor`、`textToString()`、`ViewGroup.child`/`childOrNull`/`firstChildOrNull`、`View.parentOrNull`/`removeSelf`/`updatePadding`/`updateMargins`/`setMargins`

### 2.3 `android-runtime`（`io.github.jichuo1.meguviz.android.runtime.*`）

- 核心作用域与令牌（`...runtime.core`）
  - `LifecycleScope`: `ATTACH`、`BINDING`、`ALL`；`State { CREATED, SUSPENDED, ACTIVE, CLOSED }`；`CommitResult { COMPLETED, STALE, INACTIVE, FAILED }`；`lease(int, EpochDomain...)`、`rebind()`、`commit(WorkToken, Runnable)`、`own(Registration)`、`requestClose()`、`close()`
  - `BindingLease`: `WorkToken token()`、`isClosed()`、`onClose(Runnable)`、`close()`
  - `WorkToken`: `boolean isCurrent()`、`boolean canCommit()`
  - `OwnerDispatcher` & `MainThreadDispatcher`: `isOwnerThread()`、`post(Runnable)`、`remove(Runnable)`、`checkOwner()`
  - `EpochDomain`、`Registration`、`ErrorSink`
- 有界邮箱与帧调度（`...runtime.core` & `...runtime.platform`）
  - `BoundedMailbox`:
    - `enum Admission { ACCEPTED, REPLACED, REJECTED_CAPACITY, REJECTED_CLOSED, UNSUPPORTED }`
    - `enum Outcome { PENDING, RUNNING, COMPLETED, CANCELLED, SUPERSEDED, STALE, INACTIVE, FAILED, CLOSED }`
    - `enum Cancellation { CANCELLED, ALREADY_RUNNING, ALREADY_TERMINAL }`
    - `Slot newSlot()`（仅 owner 线程）
    - 完整/简写入口：`offerState(Slot, WorkToken, Runnable[, Runnable])`、`offerCommand(WorkToken, Runnable[, Runnable])`、`offerBatch(WorkToken, List<Runnable>[, Runnable])`
    - 生产者独占批量缓冲：`Offers offers()`（`state(...)`、`command(...)`、`submit()`、`admission(int)`、`handle(int)`、`clear()`）
    - 控制：`setPaused(boolean)`、`pendingStateCount()`、`pendingCommandCount()`、`isClosed()`、`onClose(Runnable)`、`close()`
  - `FrameScheduler`: `openChannel(Object identity, FrameWork callback, int quota)`、`hasScheduledFrame()`、`close()`
  - `BudgetFrameDriver`（`WindowSession.open` 默认使用，API 33+ 读取 `FrameTimeline.deadlineNanos`，更低版本内部回退）与 `ChoreographerDriver`
  - `AdapterRegistry<T>` 与 `RetiringRegistry<T>`（`tryAcquire()` 返回无锁读 `Lease<T>`，`replace(...)`/`close()` 返回 `Retirement<T>`）
- 资源域与内存观察（`...runtime.resources` & `...runtime.platform`）
  - `ResourceScope`: `state()`、`owns(Resources)`、`refreshConfiguration()`、`invalidateTheme()`、`invalidateResources()`、`applyStyle(int, boolean)`、`spToPixels(float)`、`static ResourceSnapshot capture(Context)`
  - `ResourceState`: `RESOURCES`、`THEME`、`ALL`、`dependencies(int)`、`token(int)`、`isCurrent(Token)`、`resolve(...)`
  - `MemoryBudgetObserver`: `static MemoryBudgetObserver observeProcess(Context, Looper, LongConsumer)`（低于 API 37.2 返回 `null`）、`Snapshot snapshot()`、`close()`

### 2.4 `android-resources-loader`（`io.github.jichuo1.meguviz.android.resourcesloader`）

- `ScopedResourceLoader implements AutoCloseable`
  - `static OpenResult open(ResourceScope scope, Resources resources)`（主线程，`scope.owns(resources)` 必须为真；API 27–29 返回 `Status.UNSUPPORTED` 且 `loader == null`；附着失败且清理需重试时抛含 `recovery` 字段的 `OpenFailure`）
  - `void putApk(String slot, ParcelFileDescriptor descriptor) throws IOException`（主线程加载并安装）
  - `PreparedApk prepareApk(ParcelFileDescriptor descriptor) throws IOException`（可在后台线程解析 APK；未通过 `putPrepared` 安装前由调用方负责 `close()`）
  - `void putPrepared(String slot, PreparedApk prepared)`（主线程安装）
  - `boolean remove(String slot)` / `void invalidateExternalChange()` / `int ownedProviderCount()` / `void close()`

## 3. 与家族兄弟库的配合与桥接边界

| 需求方向 | 负责项目 | 桥接坐标与接入边界 |
| --- | --- | --- |
| 统一管理家族 `1.2.0` 制品版本号 | `MeguvizBom` | `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` |
| 强类型 Kotlin View 树、布局参数与属性绑定 | `MeguvizUI` | 若需将 `UiSession` 接到已有 `WindowSession` / `ViewScope`，接入 `io.github.jichuo1.meguviz:meguviz-ui-android-bridge:1.2.0` |
| 不可变绘制配方、圆角/渐变/状态色、`FlightCache` | `MeguvizDrawable` | 若需用 `LifecycleScope`/`ResourceState`/`BoundedMailbox` 驱动绘制槽位，接入 `io.github.jichuo1.meguviz:meguviz-drawable-meguviz-android:1.2.0` |
| 玻璃 blur/折射材质、弹性手势与页面过渡 | `LumenCoacervationEngine` | 接入 `io.github.jichuo1:lumen-bridge-meguviz-android:1.6.2`（需显式写版本 `1.6.2`，未进 `1.2.0` BOM） |
