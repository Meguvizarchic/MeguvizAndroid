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

## 环境事实

- 复现环境 JDK：Microsoft 17.0.20、Gradle 托管 Adoptium 21.0.7；未安装 JDK 25/27。
  `scripts/run-gradle.ps1` 强制 JDK 25/27（CI 用 temurin 25），上述命令用 JDK 21 直跑 `gradlew`
  并复刻脚本的 `JAVA_TOOL_OPTIONS`（unix domain tmpdir 工作区），属偏离，需在 JDK 25/27 环境复跑官方门禁。
- 公开基线 `6099984` 的 `android-runtime` 通过 `aarMetadata` 将消费者最低编译版本保持在 37.0。
  本候选保留 window / lifecycle / resources-loader 的 `compileSdk = 37`，不把内部 37.2 实现要求
  传播给消费者；编译 SDK、最低 SDK、依赖版本和验证规则均不因快路径改动提高要求。
  模块级测试与 lint 按上述命令独立验证；官方 `verifyHost` 的 JDK 25/27 环境复跑仍需另行完成。
