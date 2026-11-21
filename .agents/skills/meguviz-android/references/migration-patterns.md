# MeguvizAndroid 迁移对照与排障

本文档提供存量 Android 代码迁移到 `MeguvizAndroid 1.2.0` 的前后写法对照、生命周期与所有权规则、高频错误排查，以及可逆回退方案。

## 1. 存量代码迁移范式

### 1.1 Activity 手工生命周期管理 -> `LifecycleAdapters.openWindow`

**迁移前**：Activity 自行维护布尔标志与待注销列表，在 `onStart`/`onStop`/`onDestroy` 手工转发并容易遗漏。

```java
// 迁移前：手工维护监听器集合，异常或分支返回容易漏释放
private final List<AutoCloseable> owned = new ArrayList<>();
@Override protected void onDestroy() {
    for (AutoCloseable c : owned) { /* 逐个关闭 */ }
    super.onDestroy();
}
```

**迁移后**（AndroidX `ComponentActivity` / `AppCompatActivity`）：

```java
WindowSession session;

@Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    session = LifecycleAdapters.openWindow(this, WindowSession.Permission.OWN_WINDOW);
    ViewScope rootScope = session.scope(findViewById(R.id.root));
    // 由 session 或 rootScope 托管注册项，随生命周期自动注销
    rootScope.own(ObserverRegistration.onPreDraw(rootScope, this::onPreDrawTick));
}
```

> 若宿主是原生 `android.app.Activity`（未实现 `LifecycleOwner`），参考 `samples/plain/PlainActivity.java` 在 `onCreate` 调用 `WindowSession.open(...)`，在 `onStart`/`onStop`/`onDestroy` 转发 `setStarted(true/false)` 与 `close()`，或交由 `ManualWindowOwner` 管理。

### 1.2 Fragment View 泄漏 -> `LifecycleAdapters.bindFragmentView`

**迁移前**：把 View 相关观察者或异步请求绑定到 `Fragment` 本身，在 ViewPager / 回退栈场景下 View 已销毁而回调仍尝试访问。

**迁移后**：绑定到 `Fragment` 的 `viewLifecycleOwner`，并在 `onDestroyView` 置空引用：

```java
private ViewScope viewScope;

@Override public void onViewCreated(View view, Bundle savedInstanceState) {
    WindowSession activitySession = requireHostSession();
    viewScope = activitySession.scope(view);
    LifecycleAdapters.bindFragmentView(this, viewScope);
}

@Override public void onDestroyView() {
    viewScope = null;
    super.onDestroyView();
}
```

### 1.3 Dialog 或局部浮窗 -> 独立 `WindowSession`

每个 `Dialog` 拥有独立 `Window`，应开启独立 `WindowSession` 并在 `onDismiss` 关闭，不与 Activity 会话混用：

```java
WindowSession dialogSession = WindowSession.open(
        dialog.getWindow(), dialog.getContext(),
        getPackageName() + ":dialog", WindowSession.Permission.OWN_WINDOW);
ManualWindowOwner owner = new ManualWindowOwner(dialogSession);
owner.onStart();
dialog.setOnDismissListener(d -> owner.onDismiss());
```

### 1.4 异步回调错位与无界 `Handler.post` -> `ViewScope.rebind` + `BoundedMailbox`

**迁移前**：后台线程每次收到数据就 `mainHandler.post(() -> textView.setText(...))`，列表滚动复用或快速切换后旧结果覆盖新内容，且高频投递堆积消息队列。

**迁移后**：用 `ViewScope.rebind()` 使旧 `BindingLease` 失效，跨线程通过 `BoundedMailbox` 合并同一 `Slot` 的状态：

```java
// 主线程初始化（如 ViewHolder 构造或首次绑定）
ViewScope rowScope = session.scope(itemView);
BoundedMailbox mailbox = rowScope.openMailbox(16, 32, 8);
BoundedMailbox.Slot titleSlot = mailbox.newSlot();
BindingLease currentLease = rowScope.binding(ResourceState.ALL);

// 列表项重绑（rebind 后新获取 lease）
void bindItem(ItemData item) {
    rowScope.rebind();
    currentLease = rowScope.binding(ResourceState.ALL);
    WorkToken token = currentLease.token();
    executor.execute(() -> {
        String rendered = formatHeavy(item);
        // 后台线程直接提交，同 slot 自动仅保留最新值
        BoundedMailbox.Submission sub = mailbox.offerState(titleSlot, token, () ->
                rowScope.commit(currentLease, () ->
                        ((TextView) rowScope.view()).setText(rendered)));
        if (!sub.accepted()) {
            // 若携带了原生句柄等需手动释放的对象，此处立即释放
        }
    });
}
```

对于需要一次提交多条状态或命令的场景，优先复用生产者线程持有的 `BoundedMailbox.Offers`（Kotlin 可配合 `offers.submitBatch { ... }`），避免并发交错并减少 `Submission` 分配。

### 1.5 `WindowInsets` 与 `Window.Callback` 冲突 -> `InsetsSlot` 与 `WindowCallbackLease`

**迁移前**：直接调 `ViewCompat.setOnApplyWindowInsetsListener` 会静默顶掉原有监听；多处累加 padding 导致界面偏移。

**迁移后**：

```java
InsetsSlot insetsSlot = InsetsSlot.claimExclusive(rootScope);
InsetsSlot.LocalLayout paddingLease = insetsSlot.applyPaddingToOwnedContainer(
        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
// 如宿主修改了基础 padding，通知基线重设避免重复叠加：
// paddingLease.rebaseBaseline();
// 如需把该 View 交还外部框架自行监听：
// insetsSlot.handOffToExternalOwner();
```

需要包装 `Window.Callback` 时使用 `WindowCallbackLease.install(session, previous -> new MyCallback(previous))`，关闭时仅在当前回调仍为本租约安装实例时才还原，不会误覆写后继组件。

### 1.6 动态 APK 资源加载 -> `ScopedResourceLoader`

**迁移前**：主线程直接解析 APK 并 `Resources.addLoaders`，未追踪配置/主题失效或旧 `ResourcesProvider` 未关闭。

**迁移后**（API 30+ 生效，API 27–29 安全返回 `UNSUPPORTED`）：

```java
ScopedResourceLoader.OpenResult openResult =
        ScopedResourceLoader.open(session.resources(), getResources());
if (openResult.status == ScopedResourceLoader.Status.OPENED) {
    ScopedResourceLoader loader = openResult.loader;
    // 在后台线程解析 APK，再回主线程安装
    executor.execute(() -> {
        try (ParcelFileDescriptor pfd = openApkPfd()) {
            ScopedResourceLoader.PreparedApk prepared = loader.prepareApk(pfd);
            mainHandler.post(() -> {
                try {
                    loader.putPrepared("plugin-theme", prepared);
                } catch (RuntimeException ignored) {
                    prepared.close(); // 若未成功交接（如 loader 已关闭），由调用方释放
                }
            });
        } catch (IOException ignored) { }
    });
}
```

## 2. 生命周期、线程与所有权规则汇总

| 组件 / 操作 | 允许线程 | 关键生命周期与所有权规则 |
| --- | --- | --- |
| `WindowSession` / `ViewScope` | 仅 owner（主）线程 | 一个 `Window` 至多一个 `OWN_WINDOW`；宿主与子模块优先用 `OBSERVE` 或 `APPLY_LOCAL` |
| `LifecycleScope` / `BindingLease` | 仅 owner 线程（`requestClose()` 线程安全） | `rebind()` 使 `BINDING` 代次递增；View detach 使 `ATTACH` 代次递增；两者均导致旧 `WorkToken` 变为 `STALE` |
| `BoundedMailbox.offer*` / `Offers` | 任意线程 | 多生产者安全；被替换、取消、邮箱关闭时 `release` 必执行恰好一次（可能在生产者线程执行，须线程安全且轻量）；被拒绝时**不执行** `release` |
| `BoundedMailbox.newSlot` / `setPaused` / `close` | 仅 owner 线程 | `setPaused(true)` 保留已入队最新状态，丢弃新增命令为 `INACTIVE` |
| `ResourceScope` / `ResourceState` | 仅 owner 线程 | `applyStyle` 必须显式调 `invalidateTheme()`；不同命名空间互不混用 |
| `ScopedResourceLoader` | `prepareApk` 任意线程，其余仅主线程 | `putPrepared` 前发生异常或关闭，由调用方负责 `PreparedApk.close()` |

## 3. 高频错误与排障对照表

| 异常 / 现象 | 常见原因 | 解决方案 |
| --- | --- | --- |
| `IllegalStateException: Window already has an owner` | 对同一个 `Window` 打开了第二个 `Permission.OWN_WINDOW` 会话 | 保持页面唯一 `OWN_WINDOW` 会话，或子组件改用 `OBSERVE` / `APPLY_LOCAL` |
| `IllegalStateException: Activity destroyed` | 在 Activity 已销毁后调用 `LifecycleAdapters.openWindow` | 移至 `onCreate` 等存活阶段调用 |
| `IllegalStateException: Operation requires the owner thread` | 在子线程调用了 `WindowSession`/`ViewScope`/`newSlot`/`commit` | 仅在子线程调用 `BoundedMailbox.offer*` 或 `prepareApk`，其余操作留在主线程 |
| Kotlin 编译报错 `Module was compiled with an incompatible version of Kotlin` | Kotlin 编译器低于 `2.3` 且依赖了 `meguviz-android-window:1.2.0` | 升级工程 Kotlin 插件至 `2.3+`（推荐 `2.4.21`） |
| `IllegalStateException: Insets listener slot already owned` | 对同一个 View 重复调用 `InsetsSlot.claimExclusive` | 复用已有 `InsetsSlot`，或前任所有者先调用 `handOffToExternalOwner()` |
| `BoundedMailbox` 提交后资源泄漏 | `offerState`/`offerCommand` 返回 `REJECTED_CAPACITY` 或 `REJECTED_CLOSED` 后未清理自持资源 | 检查 `submission.accepted()`，返回 `false` 时调用方立即释放资源 |
| `ScopedResourceLoader.OpenFailure` | API 30+ 附着或清理阶段异常，抛出含 `recovery` 的异常 | 对 `failure.recovery` 重试 `close()` 直到成功 |

## 4. 安全回退方案

1. **按页面/组件局部回退**：将目标页面从 `OWN_WINDOW` 降级为不接入或移除 `WindowSession` 成员变量即可恢复原生生命周期调用；`InsetsSlot` 通过 `close()` 或 `handOffToExternalOwner()` 自动还原监听槽位（`LocalLayout` 关闭时恢复接管前的 padding/margin 基线）。
2. **版本回退**：若已升级至 `1.2.0` 但因特殊依赖冲突需临时回退到已发布的 `1.1.0`（带 Kotlin 2.2 元数据），去掉 `meguviz-bom:1.2.0` 约束并在 Gradle 中对四个模块显式声明 `1.1.0`；注意 `1.1.0` 不含 `1.2.0` 的 `BoundedMailbox` 共享唤醒停摆修复与关闭加固，不建议长期停留。
