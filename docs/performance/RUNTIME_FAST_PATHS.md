# Runtime 快路径：帧快照缓冲复用与帧预算接线

本文件记录对 `android-runtime` 每帧快照缓冲复用、`BudgetFrameDriver` 默认接线与队列拓扑复核的改动、不变量及宿主 JVM 验证记录。

## 帧快照缓冲复用（2026-11-04）

| 文件 | 改动 |
| --- | --- |
| `BoundedMailbox.java` | `drain` 每帧提取批次改为复用实例字段 `drainBatch`（包私有），入口与 `finally` 清空，重入时退让为局部列表 |
| `FrameScheduler.java` | `onFrame` 待决通道快照改用可增长实例数组 `BatchEntry[]`；条目可重置并在 `finally` 释放 `channel` 引用；`close` 整体置换数组 |

`FrameTick` 保持每帧新建以遵守不可变公开 API 契约。关键不变量包括：
1. **重入退让与标志保存/恢复**：在入口检查 `draining` 并采用 `wasDraining` 保存/恢复，防止连续两次重入时内层退出误清外层标志，已由 `consecutiveReentrantDrainsDoNotResetDrainingForTheOuterBatch` 回归测试锁定。
2. **`finally` 必清引用**：无论正常返回或异常退出，实例缓冲均清空内部元素，不滞留调用方闭包或已关闭通道引用。
3. **轮转、配额与 ABI 不变**：`cursor` 轮转、`globalQuota` 分配、`cancellation` 判定与公开 ABI（`api/android-runtime.txt`）均保持一致。

### 宿主 JVM ABBA 交错结果（JVM 21.0.7）

基线为提交 `6099984` 还原版，采用独立 JVM fork 的 ABBA 交错（32 邮箱 5 对，8 邮箱 3 对；测量窗从热身结束后重置）：

| 场景 | 指标 | 改前（`6099984`） | 改后（修复版） | 判定 |
| --- | --- | --- | --- | --- |
| 32 邮箱 × 16 slot × 9,000 帧 | 主线程分配均值 | 26,611 B/帧（10 次，26,572–26,678） | 24,686 B/帧（10 次，24,672–24,736） | **-7.2%，方差极小，稳定下降** |
| 32 邮箱 | 每帧耗时均值 | 22,232 ns | 21,431 ns | 均值略低（约 `-3.6%`），方差约 `±7%` |
| 8 邮箱 × 16 slot × 15,000 帧 | 主线程分配均值 | 8,267 B/帧（6 次，8,203–8,354） | 7,408 B/帧（6 次，7,352–7,463） | **-10.4%，方差极小，稳定下降** |
| 8 邮箱 | 每帧耗时均值 | 7,539 ns | 7,357 ns | 均值略低（约 `-2.4%`），方差约 `±8%` |

- 两侧 `executed_ops` 完全一致（8 邮箱 `480,000`，32 邮箱 `576,000`）；耗时差落在方差范围内，只将分配下降 `-7.2% ~ -10.4%` 记为稳定收益。
- 相关单测与新增 11 项 `FrameBufferReuseContractTest` 通过。`RetiringRegistry.Lease.snapshot()` 去锁优化见 [REGISTRY_READ_PATH.md](REGISTRY_READ_PATH.md)。

## 帧预算接线与异常回退加固（2026-11-07 至 2026-11-08）

- **非正 deadline 兜底**：`BudgetFrameDriver.VsyncDriver` 原将 `timeline.getDeadlineNanos()` 原样透传；当平台返回非正值时，下游 `UiScheduler.drain` 会计算出 0 工作预算导致卡帧。现经 `isUsableDeadline(long)` 过滤，非正数自动回退单参数回调（`deadline == null`，触发固定预算），与 `MeguvizUI` 的 `ChoreographerFrameDriver` 行为对齐。
- **默认接入 `BudgetFrameDriver`**：`WindowSession.open(...)` 默认从 `ChoreographerDriver` 切换为 `new BudgetFrameDriver(owner, true)`（API 33+ 读取 `postVsyncCallback` 的 `preferredFrameTimeline.deadlineNanos`，低版本内部回退单参数 `Choreographer` 路径）。
- **元数据异常隔离与放宽 presentation 检查**：`onVsync` 将 `getFrameTimeNanos`、`getPreferredFrameTimeline` 与 `getDeadlineNanos`/`getExpectedPresentationTimeNanos` 纳入同一 `try` 块，任意 `RuntimeException` 均安全回退为未知 deadline（防止异常逃逸导致 `FrameScheduler.scheduled` 卡在 `true` 停摆）；同时不再因仅作透传且下游未使用的 `expectedPresentationTimeNanos` 非正而丢弃有效 deadline。
- 相关回归 `BudgetFrameDriverVsyncFallbackTest`（6 例）与两模块共 175 项单测通过，Lint 零错误。

## 队列拓扑复核：tombstone 改造回退（2026-11-08）

经核实当前 `HEAD` 中 `commands`、`extracted` 与 `stateQueue` 已在 `b9b82eb` 改造为侵入式双向链表，取消、替换、暂停与关闭时的摘链操作均为 O(1)，不再存在早期 `ArrayDeque.remove(Object)` 锁内线性扫描。实验性将 `extracted` 改为批次墓碑的方案导致提交路径分配增加 `+256 B/帧`（`+8.2%`）且无明确收益，已完整回退。

## 复现方式

```powershell
# 单元测试与 Lint（需 JDK 21+）
.\gradlew.bat :android-runtime:testDebugUnitTest :android-runtime:lintDebug --no-daemon

# 纯 JVM 帧缓冲基准
$jdk = "$env:JAVA_HOME\bin"
& "$jdk\javac.exe" -nowarn -d build\host-core\classes `
  android-runtime\src\main\java\io\github\jichuo1\meguviz\android\runtime\core\*.java `
  benchmarks\host-core\FrameBufferBenchmark.java
& "$jdk\java.exe" -cp build\host-core\classes FrameBufferBenchmark 8 16 20000 5000 3
```

## 适用范围与验证说明

- `android-runtime` 通过 `aarMetadata` 将消费方最低 `compileSdk` 维持在 `37`，快路径与预算改动均未提高 SDK、依赖或公开 API 门槛。
- 上述基准数据与单测均在宿主 HotSpot JVM 上完成，不构成真机 ART 性能或设备帧 pacing 结论；`BudgetFrameDriver` 默认路径在各类设备上的 `postVsyncCallback` 兼容性须通过设备测试验证。官方 `scripts/run-gradle.ps1 verifyHost` 需在 JDK 25 或 JDK 27 环境下执行。
