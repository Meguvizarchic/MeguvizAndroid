# BoundedMailbox 分配优化：队列结构与 setPaused 幂等

本文件记录 2026-11-05 至 2026-11-07 对 `android-runtime` 邮箱提交与终止路径的多轮分配优化，包括对应不变量与宿主 JVM 验证数据。

## 第五轮（2026-11-07）：`WorkHandle` 兼作队列项

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| `WorkHandle` 合并 `Item` / `Terminal` | `BoundedMailbox.java` | `WorkHandle` 直接携带 `slot`、`token`、`action`、`release` 并兼任队列节点，省去每次提交分配 `Item` 及终止时分配 `Terminal` |
| `terminateLocked` 返回 `WorkHandle` | 同上 | 终止时将 `slot`、`token`、`action` 置空，保留 `release` 至 `release(handle)` 执行；`WorkHandle.cancel()` 改为 `cancel(this)` |

公开 ABI 保持不变（`api/android-runtime.txt` 仅移除包私有的 `Item` / `Terminal` 空类段，无公开描述符变化）。

ABBA 交错测试（JDK 21，`FrameBufferBenchmark`，8 / 32 邮箱 × 16 slot × 20,000 帧）：

| 配置 | 改前 | 改后 |
| --- | --- | --- |
| 8 邮箱 | 3,880 B/帧 | 3,112 B/帧（-19.8%） |
| 32 邮箱 | 16,944 B/帧 | 12,336 B/帧（-27.2%） |

双方 `executed_ops` 一致（功能等价），隔离探针另测得单次 offer 逃逸分配由 `116 B` 降至 `73 B`。单元测试中 `:android-runtime:testDebugUnitTest`（77 项）与 `:android-window:testDebugUnitTest`（91 项）在 JDK 21 下通过，并由 `MailboxHandleItemContractTest`（4 项）锁定“返回句柄即队列项本身”、“`release` 恰好执行一次”与“终止后引用全部置空”。

## 第四轮（2026-11-05）：唤醒停摆修复与关闭路径加固

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 移除共享 `Signal` 按对象同一性删除 | `BoundedMailbox.post` / `cancelIdleWake` | 单例 `signal` 复用下，`owner.remove` 可能误删并发生产者刚重新投递的唤醒，造成 `signalPending == true` 但队列无回调而永久停摆；`Signal.run` 已重查标志，陈旧唤醒仅空跑一次 |
| 非 owner 线程失败延迟清理 | `terminateAll` / `ownerCleanup()` | 生产者线程失败时把 `channel.close()` 与 `scope.release` 投递回 owner 线程；`ownerCleanupDone` 保证与后续 scope 关闭幂等，投递被拒则回退至 scope close |
| 取消端检查优化 | `removeQueued` | 优先检查队头与队尾再线性扫描，避免生产者取消尾部刚提交项时从头遍历 |
| 帧回调失败合并 | `FrameScheduler.onFrame` | `channel.close()` 抛错不再掩盖原始 drain 失败，经 `Failures.combine` 合并上报 |

- **停摆复现门控探针**：基线复现为 `pending_after_race=1, queued_signals=0, has_frame=false`（后续再提交仍无帧），修复后 `pending_after_race=0, frames_after_race=1`（3,000 轮 × 2 生产者约 9,900 万次提交不加延迟时窗口极窄，但命中即不可自愈）。
- **取消端检查（单线程隔离，p50）**：队列深度 4,096 时尾部取消耗时从 `3,100 ns` 降至 `201 ns`（约 15×）；队头取消不受影响。（后续于 2026-11-07 在 `b9b82eb` 中将 `commands` / `extracted` / `stateQueue` 进一步改为侵入式双向链表，取消/替换摘链达 O(1)。）
- `:android-runtime:testDebugUnitTest`（71 项）与 `:android-window:testDebugUnitTest`（91 项）通过，API 基线未变。

## 第三轮（2026-11-05）：构造器通道泄漏加固

`BoundedMailbox` 构造器在 `scheduler.openChannel` 成功后通过 `scope.own` 登记清理；若并发 `requestClose()` 导致 `scope.own` 抛错，通道会残留在 `FrameScheduler.channels` 中无法关闭。调整后将 `scope.own` 纳入 `try` 块，失败时立即关闭通道并附带 `addSuppressed`，同时将 `channel.onClose` 注册移至 `scope.own` 成功之后。对应合同：`MailboxContractTest.closedScopeConstructorReleasesOpenedChannel`（`channelCount() == 0`）。窗口侧同类加固见 [Insets 分发与注册泄漏加固](WINDOW_DISPATCH.md)。

## 第一至二轮（2026-11-05）：容器替换、`Signal` 复用与 `stateQueue`

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| `latest` 改用 `IdentityHashMap` | `BoundedMailbox.java` | `Slot` 未重写 `equals`/`hashCode`，仅依赖对象同一性，移除冗余链表指针 |
| `extracted` 改为 `ArrayDeque` | 同上 | 保持插入与遍历顺序，FIFO 移除摊还 O(1) |
| `setPaused` 幂等判断 | 同上 | 目标状态一致时直接返回，跳过集合分配与 `channel.cancelPending()` |
| `states` 改为 `stateQueue` | 同上 | `Slot` 增加包私有 `queued` 标记，替换已排队项沿用原位（O(1)），drain 直接 `pollFirst()` |
| 单例 `Signal` 复用 | 同上 | 以 `signalPending` 标志配合单例唤醒并同步维护取消、暂停与终止状态 |

第一轮（容器与 `setPaused` 幂等，JDK 21.0.7，8 邮箱 × 16 slot × 20,000 帧，测量窗 15,000 帧 × 3 reps ABBA 交错）：改前每帧分配 `7,384 / 7,208 / 7,208 / 7,370 / 7,208 / 7,208 B`，改后 `5,014 / 4,968 / 4,968 / 5,026 / 4,968 / 4,968 B`（约 `-31%`），`executed_ops` 均为 `480,000`。

第二轮（`stateQueue` 与单例 `Signal`，同配置测量窗 5,000 帧）：改前 `5,028 / 4,968 / 4,968 B/帧`，改后稳定为 `3,880 B/帧`（约 `-21.9%`），`executed_ops` 均为 `480,000`，公开 ABI（`javap -public`）逐字节一致。

## 复现方式

```powershell
# 单元测试（需 JDK 21+；默认 JDK 17 下 Robolectric Android SDK 37 沙箱会失败）
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
.\gradlew.bat :android-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2

# 纯 JVM 基准（benchmarks/ 不在根 Gradle 构建内）
java -cp <out> FrameBufferBenchmark 8 16 20000 15000 3
```

## 残余风险与适用范围说明

- 若极端抢占窗口下 owner 队列出现重复 `Signal`，多余一次会因 `signalPending` 检查早退，不漏唤醒也不重复请求平台帧。
- 上述数据均采集自宿主 HotSpot JVM，不含 ART 与真机测量，不作为零分配或设备帧耗收益承诺；真机帧 pacing 与 GC 表现须通过设备测试单独验证。完整门禁 `verifyHost` 须在 JDK 25 或 JDK 27 环境下执行。
