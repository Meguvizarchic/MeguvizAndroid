# Insets 分发与注册泄漏加固

本文件记录对 `android-window` 窗口内边距分发分配与监听器/回调注册竞态泄漏的加固改动及验证情况。

## 改动内容

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 观察者写时拷贝与空列表早退 | `InsetsSlot.java` | `dispatchSnapshot` 对空观察者列表早退；使用不可变快照 `volatile List<Observer> observerSnapshot`（`List.copyOf(observers)`）替代每次分发重建 `ArrayList` |
| 零值 `InsetsValue` 复用 | `InsetsSnapshot.java` | 四边均为 `0` 的类型直接返回单例 `InsetsValue.ZERO` |
| 扁平化边值与位掩码存储 | `InsetsSnapshot.java` | 各类型边值打包存入 `long[]`（每类型 2 个 `long`），可见性存入 `int` 位掩码；`combined()` 直接按位解包计算，`types()` 仅在首次访问时物化并缓存 |
| 注册回滚统一顺序 | `InsetsSlot` / `ObserverRegistration` / `WindowCallbackLease` | 统一采用“先 `scope.own`，再注册系统回调，失败立即回滚”，避免并发 `requestClose()` 越过 `checkOpen` 后残留 listener、callback 或全局表项 |

## 语义与权衡

- **快照一致性与只读**：`List.copyOf` 保证单次分发内观察者集合不变；`InsetsSnapshot` 的不可变语义、`types()` 固定 9 项顺序及 `combined()` 取最大值语义均保持一致。
- **还原守卫**：`WindowCallbackLease` 仅在 `window.getCallback() == installed` 时还原旧回调，避免误覆盖后续组件。
- **读写权衡**：热路径 `dispatchSnapshot` 与 `combined()` 在无观察者或常见配置下显著减少对象分配；写路径 `observe()` / `close()` 在变更时调用 `List.copyOf`，对于个位数观察者场景开销极低，若宿主按帧高频注册/注销则变更成本从 O(1) 变为 O(n)。
- **探索性探针方向参考**：早期本地未留档 CSV 的 scratch 桩测试中，典型分发路径 `copy + combined` 分配从 `704 B` 降至 `304 B`（`copy` 单独从 `608 B` 降至 `297 B`），而调用全量 `types()` 因物化 9 个对象增至 `751 B`；对非零边距的 `combined` 分配略高于旧预建数组版，整体仍因 `copy` 降幅保持净节省。

## 验证与适用范围

- `:android-window:testDebugUnitTest`（91 项）与 `:android-runtime:testDebugUnitTest`（73 项）通过，`WindowPlatformTest` 锁定了 `types()` 顺序、`combined()` max 语义、非法掩码抛错及 `closedScopeClaimFailsAndSameViewIsClaimableAgain`（关闭 `scope A` 后可由 `scope B` 重新认领同一 View；并发竞态构造回滚分支目前仅通过代码逻辑保护，未额外注入故障桩复现）。
- `javap -public` 导出与 `api/android-window.txt`、`api/android-runtime.txt` 逐字节一致。
- 本文件测试均运行于宿主 JVM（Robolectric / 桩测试），IME 动画与真实设备上的帧分发表现须通过设备测试复测确认。
