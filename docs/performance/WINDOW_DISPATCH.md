# Insets 分发与注册泄漏加固（host 验证）

本文件记录 2026-11-05 第三轮对 `android-window` 的分发分配与注册泄漏改动。只描述已实现内容与已验证证据；不声称设备收益。

## 改动

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 观察者 COW + 无观察者早退 | `InsetsSlot.java` | `dispatchSnapshot` 在既有 guard 后对空快照早退；用 `volatile List<Observer> observerSnapshot`（`List.copyOf(observers)`）替代每次 `new ArrayList<>(observers)` 的防御拷贝 |
| `InsetsSnapshot.copy` 零值复用 | `InsetsSnapshot.java` | 四边全零的 `InsetsValue` 直接返回 `InsetsValue.ZERO` |
| 注册泄漏加固 | `InsetsSlot` / `ObserverRegistration` / `WindowCallbackLease` | 统一为「先 `scope.own`，再做可失败副作用，失败回滚」，避免并发 `requestClose()` 打穿 `checkOpen` 后残留 listener/callback/表项 |

## 不变量

- 分发快照的起始时刻与重入 `observe`/`close` 语义不变（`List.copyOf` 是同一时刻的不可变快照）。
- `InsetsSnapshot` 的不可变语义、`types()` 的 9 项顺序与 `combined()` 的 max 语义不变。
- `WindowCallbackLease` 仅在 `window.getCallback() == installed` 时还原，避免误还原后继 owner。
- `observers` 只在 owner 线程修改，`volatile` 仅为防御。

## 验证

- `:android-window:testDebugUnitTest`（Robolectric，36 项）与 `:android-runtime:testDebugUnitTest`（70 项）通过。
- 新增 `WindowPlatformTest.closedScopeClaimFailsAndSameViewIsClaimableAgain`：关闭 scope A 后 `claimExclusive` 失败，再用 scope B 对同一 view 声明成功（契约守卫）。
- `javap -public` 与 `api/android-window.txt`、`api/android-runtime.txt` 逐字节一致。
- 分配收益需设备/插桩复测（IME 动画期间每帧 `dispatchSnapshot`），本轮未测量，不声称设备收益。
