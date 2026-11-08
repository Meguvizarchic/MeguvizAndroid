# Runtime 快路径：帧快照缓冲复用（host 验证）

本文件记录 2026-11-04 对 `android-runtime` 两处每帧分配的收窄改动。只描述已实现内容与已验证证据；
不承诺零分配，不声称 ART/真机收益。

## 改动内容

| 文件 | 改动 |
| --- | --- |
| `android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java` | `drain` 的每帧提取批次改用实例字段 `drainBatch`（包私有，供契约测试观察）；入口清空、`finally` 清空；重入时退让为局部列表 |
| `android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/FrameScheduler.java` | `onFrame` 的待决通道快照改用可增长 `BatchEntry[]`（包私有）；条目改为可重置；`finally` 中保持“先补挂未处理通道 pending、再释放条目 channel 引用、最后 schedule”的原控制流；`close` 整体置换数组引用 |

`FrameTick` 仍每帧新建：其不可变语义（“no platform frame object survives a callback”）是公开 API 契约，
不做复用。`offer` 路径的 `Item`/`WorkHandle`/`Submission` 每提交分配也未改动。

## 不变量（改动依赖的性质，均有测试锁定）

1. **重入退让与标志保存/恢复**：公开契约下 `onFrame`/`drain` 重入不可达（`FrameScheduler.schedule` 在 `draining` 时不请求帧；
   `FrameDriver` 契约为异步一次性源；投递有 owner 线程检查）。即便如此，两处均在入口检查 `draining`，
   重入时使用私有局部缓冲；且 `draining` 采用 save/restore（`wasDraining`）而非无条件复位——
   否则第一层重入退出即把标志置 false，外层仍在遍历实例缓冲时第二层重入会复用并清空该缓冲
   （丢 item）。该回归由 `consecutiveReentrantDrainsDoNotResetDrainingForTheOuterBatch` 锁定：
   公开基线 6099984 与修复版为绿，无条件复位版本为红。
2. **finally 清空**：实例缓冲在 `finally` 中清空，正常返回、异常逃逸（含 fatal 错误）均生效；
   `BoundedMailbox.drainBatch` 清空发生在内层 `finally` 的 `channel.requestFrame()` 之后，
   因此任何（契约外的）同步投递重入时外层 `draining` 仍为 true，观察到的是退让路径。
3. **轮转与配额保持**：快照仍在帧开始处一次性拷贝（通道可在回调中关闭/新增），`cursor` 轮转、
   `globalQuota` 逐通道分配、取消代次（`cancellation`）判定与改动前逐行等价。
4. **无闭包滞留**：`drainBatch` 清空即释放 `Item` 对调用方 action/release 的持有；
   `BatchEntry` 在 finally 释放 channel 引用；`FrameScheduler.close` 置换整个数组。
5. **公开 API 不变**：`javap -public` 对 `BoundedMailbox`/`FrameScheduler`/`FrameTick` 的输出与
   `api/android-runtime.txt` 逐字节一致（新增字段均为包私有，不进 `-public` 转储）。

## 复现方式

单元测试与 lint（JDK 21+；官方 `scripts/run-gradle.ps1 verifyHost` 要求 JDK 25/27，需自备对应环境）：

```powershell
./gradlew :android-runtime:testDebugUnitTest :android-runtime:lintDebug --no-daemon
```

新增契约测试：`android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/FrameBufferReuseContractTest.java`
（11 项，含白盒重入 drain、连续两次重入回归、帧中关/开通道场景与快照条目跨帧复用断言）。

Host 基准（纯 JVM，无 Android/Robolectric/设备；`benchmarks/` 不在根 Gradle 构建内）：

```powershell
# 复现环境使用 Gradle 托管的 Adoptium JDK 21（路径随 Gradle 安装而异，勿固化为唯一路径）；
# 官方 run-gradle.ps1 要求 JDK 25/27，放行前需在对应环境复跑。
$jdk = "$env:JAVA_HOME\bin"
& "$jdk\javac.exe" -nowarn -d build\host-core\classes `
  android-runtime\src\main\java\io\github\jichuo1\meguviz\android\runtime\core\*.java `
  benchmarks\host-core\FrameBufferBenchmark.java
& "$jdk\java.exe" -cp build\host-core\classes FrameBufferBenchmark 8 16 20000 5000 3
```

改前基线取自公开基线 `6099984`：用 `git show 6099984:<两个 core 文件>` 还原后同法编译对比。
基准源码与本文件均位于独立公开 worktree，不依赖任何本地脏文件。

ABBA 交错复测（独立 JVM fork；把 `<baseline-out>`/`<fixed-out>` 换成两次编译的输出目录）：

```powershell
foreach ($p in 1..5) {
  foreach ($tag in 'A1','B1','B2','A2') {
    $cp = if ($tag -in 'A1','A2') { '<baseline-out>' } else { '<fixed-out>' }
    & "$env:JAVA_HOME\bin\java.exe" -cp $cp FrameBufferBenchmark 32 16 12000 3000 1
  }
}
```

## 已验证数据（host JVM 21.0.7，非 ART、非真机；描述性统计，无 p99 声明）

复测方法：ABBA 交错、独立 JVM fork（A=公开基线 6099984，B=修复版），同输入同 JDK；
32 邮箱 5 对、8 邮箱 3 对。原始记录：`build/perf-abba/abba-32.txt`、`build/perf-abba/abba-8.txt`
（`build/` 被 gitignore，仅本地证据，不随提交分发）。早期顺序跑曾显示“32 邮箱 +8.6% 劣化”，交错复测
**未稳定复现**该劣化；运行次序漂移是可能解释之一，但本实验无法确定原因，不作因果断言。
本文件以交错结果描述现状。

| 场景 | 指标 | 改前（6099984） | 改后（修复版） | 判定 |
| --- | --- | --- | --- | --- |
| 32 邮箱 × 16 slot × 9000 帧 | 主线程分配均值 | 26611 B/帧（10 次，26572–26678） | 24686 B/帧（10 次，24672–24736） | **-7.2%，方差极小，确定性收益** |
| 32 邮箱 | 每帧耗时均值 | 22232 ns | 21431 ns | 方向有利（约 -3.6%，5 对中 4 对更快），但方差约 ±7%，**不构成 speedup 结论** |
| 8 邮箱 × 16 slot × 15000 帧 | 主线程分配均值 | 8267 B/帧（6 次，8203–8354） | 7408 B/帧（6 次，7352–7463） | **-10.4%，方差极小，确定性收益** |
| 8 邮箱 | 每帧耗时均值 | 7539 ns | 7357 ns | 方向有利（约 -2.4%，3 对全部更快），但方差约 ±8%，**不构成 speedup 结论** |

- 执行操作数改前改后完全一致且与帧数自洽：8 邮箱 480000（=15000 帧 × 8 邮箱 × 4 提交），
  32 邮箱 576000（=9000 帧 × 全局配额 64，饱和场景）。行为保持由 android-runtime 全量单元测试佐证
  （含 11 项缓冲复用契约测试与 5 项多线程示例测试）。
  注：早期 harness 的 `executed_ops` 累计含热身段（8 邮箱曾显示 640000 = 20000 帧全程），
  与“15000 帧测量窗口”不同口径；已改为仅统计测量窗口，上表为新口径。
- **结论**：分配下降 -7.2% ~ -10.4% 为可复现的确定性收益；耗时无统计意义上的变化
  （交错复测方向有利但被方差掩盖，不能声称加速；早期顺序跑的“+8.6% 劣化”在交错复测中
  未稳定复现，可能解释包括运行次序漂移，但无法由本实验确定）。本候选**不背负
  performance/speedup 承诺**，只陈述分配证据；真机帧 pacing 结论仍需独立设备验证。
- 测量窗口在 warmup 后的帧边界重置（分配/耗时/操作数同口径）；分配用
  `ThreadMXBean.getThreadAllocatedBytes`，GC 统计跳过计数为 -1 的收集器。

## 残余风险与后续候选

- 32 邮箱场景 host 耗时方差不构成结论（ABBA 交错下方向有利但未达统计显著）：若真机复测出现
  持续劣化，最小取舍为仅保留 `BoundedMailbox.drain` 复用、回退 `FrameScheduler.onFrame` 快照复用
  （两处独立，可分别取舍，契约测试已分别锁定）。
- 快照数组只增长不收缩（通道数峰值决定容量）；关闭时整体置换。极端“峰值后长期低频”场景会保留
  一个小数组，可接受；如确需收缩，应在 close 路径统一处理并补测试。
- 已实施（`96819a2`）：`RetiringRegistry.Lease.snapshot()` 免锁读改为单一 `volatile snapshot`
  引用，读路径一次 volatile 读；测量与契约见 [REGISTRY_READ_PATH.md](REGISTRY_READ_PATH.md)。
- 后续候选（未实施）：`BoundedMailbox` 双映射（`states`/`latest`）职责复核、UI 侧 `UiScheduler`
  组轮转 O(n²) 与 Batch 分配（由 UI 负责人实施）。

## 2026-11-07：deadline 上报加固与帧预算接线缺口（host 验证）

### 已实施：非正 deadline 当作未知上报

`BudgetFrameDriver.VsyncDriver` 原先把 `timeline.getDeadlineNanos()` 原样传给回调。非正数表示平台
拿不到 deadline（例如 SurfaceFlinger 重启后），下游（`UiScheduler.drain` 的
`min(maxFrameWorkNanos, deadline - started - 1ms)`）会据此推出 0 工作预算并卡住该帧。现在非正
deadline 改走单参数回调（下游收到 `null`，保持固定预算），与 MeguvizUI 的
`ChoreographerFrameDriver.VsyncDriver` 的 `takeIf { it > 0 }` 对齐。校验抽成包级
`isUsableDeadline(long)`，测试覆盖 `0 / -1 / Long.MIN_VALUE` 为假、`1` 与当前纳秒时间为真。

验证：`:android-runtime:testDebugUnitTest` + `:android-window:testDebugUnitTest` 170 项通过；
lint 0 error。真机行为未测。

### 调研发现（2026-11-07，未实施）

用 Exa 调研 Android 17 DeliQueue、Choreographer 同帧批处理、JCTools/Agrona/Disruptor、
coalescing/背压、FrameMetrics/JankStats 后，可落地与不适用的结论如下（完整报告见会话研究副本）：

- **默认集成拿不到 deadline**：`WindowSession.open(...)` 用 `new ChoreographerDriver(owner)`，
  而 `BudgetFrameDriver` 在测试与基准之外没有生产调用点。也就是说 `UiScheduler` 的 deadline
  感知预算在默认 Android 桥路径上永远不会生效（`FrameBudget.estimated` 恒为 true）。是否改成
  默认需要设备行为门禁，本轮未改。
- **JCTools/Agrona/Disruptor 不适用于 `BoundedMailbox`**：它是 owner 独占 + 同槽合并 + 复杂
  状态机，无锁队列的直接移植收益为负。可借鉴的是 DeliQueue 的「锁内不做 O(n) 移除」——
  `BoundedMailbox.cancel()`/supersede 会在 producer 线程持锁做 `ArrayDeque.remove(Object)`，
  与 DeliQueue 要解决的 priority inversion 同构；`removeQueued` 已有 head/tail 快路径，
  中段移除的 O(n) 仍在锁内。
- `MainThreadDispatcher` 保持同步 `Handler` 是有意设计（类注释明确「不绕过 traversal 屏障」），
  不改。
- `ChoreographerDriver` 每请求一个 `FrameCallback`，与 `FrameScheduler` 单 pending 的契约一致，
  不存在重复请求 vsync。

## 2026-11-08：默认接线 BudgetFrameDriver（R1，host 验证）

上一节的调研发现「默认集成拿不到 deadline」——`UiScheduler` 的 deadline 感知帧预算在默认 Android 桥路径上实际是死代码。本轮把它接起来：

- `WindowSession.open(...)` 默认改用 `new BudgetFrameDriver(owner, true)`：API 33+ 读 `postVsyncCallback` 的 `preferredFrameTimeline.deadlineNanos`，低版本在驱动内部回退到单参数 `Choreographer` 路径。
- `BudgetFrameDriver.VsyncDriver.onVsync` 把三处平台读取（`getFrameTimeNanos`、`getPreferredFrameTimeline`、`getDeadlineNanos`/`getExpectedPresentationTimeNanos`）放进同一 `try`：任何 `RuntimeException` 都回退单参数回调（下游收到 `deadline == null` → `FrameBudget.estimated = true` → 固定预算），不再把元数据异常抛回 Choreographer——异常逃逸会让 `FrameScheduler.scheduled` 保持 `true` 而停摆。`Error` 仍上抛，与 `UiScheduler.apply`/`checkPending` 的约定一致。
- 放宽 presentation 检查：`expectedPresentationTimeNanos` 全链路没有消费者，要求它为正只会白白丢掉可用的 deadline；它现在只是原样透传。校验收敛为 `isUsableDeadline`（非正 deadline 才回退）。

验证：`:android-runtime:testDebugUnitTest` + `:android-window:testDebugUnitTest` 175 项通过（新增 `BudgetFrameDriverVsyncFallbackTest` 6 例，反射构造 API 37 的 `FrameData`/`FrameTimeline`）；lint 0 error。

残余风险：该改动让**默认路径**从「无 deadline」变成「有 deadline」——常规刷新率下预算仍被 clamp 到 2ms 不变，late frame 预算收缩是预期；但 `postVsyncCallback` 的真机兼容性与帧节奏未测（本机无 instrumentation 设备）。对抗复核（`34-adversarial-round3.md`）另指出：若 `postVsyncCallback` 在某些设备上不可用，`WindowSession.open` 会直接失败，需真机确认。

## 2026-11-08（R5 复核）：tombstone 改造无必要，已回退

本轮按 DeliQueue 教训评估「`BoundedMailbox` 锁内不做 O(n) 移除」（R5），结论是**不需要改**，尝试已回退：

- 核实 `HEAD` 的三条队列已经全是**侵入式双向链表**（`commands`/`extracted` 是 `HandleQueue`，`stateQueue` 是 `SlotQueue`），`cancel()`/supersede/pause/close 的摘链都是 O(1)；`ArrayDeque.remove(Object)` 的线性路径在 **2026-11-07 的 `b9b82eb`（WorkHandle 即队列项）** 就已经消除。
- 该轮调研的「现状 O(n) 路径」表描述的是 11-07 之前的旧实现，基线判断有误。
- 尝试把 `extracted` 改成「批次墓碑」（不再逐项摘链、最外层 drain 末尾整体清空）并给 `HandleQueue.remove` 加 no-op 防护，实测 **offer 路径分配 +256 B/帧（+8.2%）**，却没有换来可证明的收益，因此整体回退。
- 真正值得留意的成本是**持锁执行**本身（producer 阻塞与 priority inversion），而不是移除复杂度——`MAILBOX_ALLOCATION.md` 已记录 1024 容量 + 逆序密集取消约 2.5× 的实测，那是后续若要做队列拓扑改造时的基线。

## 环境事实

- 复现环境 JDK：Microsoft 17.0.20、Gradle 托管 Adoptium 21.0.7；未安装 JDK 25/27。
  `scripts/run-gradle.ps1` 强制 JDK 25/27（CI 用 temurin 25），上述命令用 JDK 21 直跑 `gradlew`
  并复刻脚本的 `JAVA_TOOL_OPTIONS`（unix domain tmpdir 工作区），属偏离，需在 JDK 25/27 环境复跑官方门禁。
- 公开基线 `6099984` 的 `android-runtime` 通过 `aarMetadata` 将消费者最低编译版本保持在 37.0。
  本候选保留 window / lifecycle / resources-loader 的 `compileSdk = 37`，不把内部 37.2 实现要求
  传播给消费者；编译 SDK、最低 SDK、依赖版本和验证规则均不因快路径改动提高要求。
  模块级测试与 lint 按上述命令独立验证；官方 `verifyHost` 的 JDK 25/27 环境复跑仍需另行完成。
