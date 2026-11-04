# RetiringRegistry 读路径：Lease 快照免锁读（host 验证）

本文件记录 `RetiringRegistry.Lease.snapshot()` 的快路径改动、语义论证与 host 证据。只描述已实现内容与已验证证据；
不承诺 ART/真机收益，不设置性能门槛。

## 改动内容

| 文件 | 改动 |
| --- | --- |
| `android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/RetiringRegistry.java` | `Lease` 改为单一 `volatile AdapterRegistry<T> snapshot` 引用，既作读值又作开关哨兵；`snapshot()` 去掉 `synchronized`，一次 volatile 读后非 null 即返回；`close()` 在 `synchronized(state)` 内把 `slot`/`snapshot`/`state` 全部置 null |
| `android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/RetiringRegistryTest.java` | 新增 4 项确定性回归：关闭后字段全 null；保留已关闭 lease 字段全 null 且 forget 后 Slot 快照清空、注册表 map 不含该代际（强引用断开，不承诺 GC 时刻）；forget 释放 Slot 快照与注册表映射；latch 建立 happens-before 的跨线程 close 后新读抛错 |
| `benchmarks/host-core/RegistryReadBenchmark.java` | 新增差分 host 基准（`steady` 含 snapshot，`acquire` 不含；两者 tryAcquire/close 锁流量相同） |
| `docs/performance/REGISTRY_READ_PATH.md` | 本文件 |

`RetiringRegistry` 的公开 ABI 不变（`javap -public` 仅含构造器、`tryAcquire`/`isClosed`/`replace`/`close` 与
`Lease.snapshot`/`Lease.close`、`Retirement` 三方法；新增/修改均为私有字段）。`BoundedMailbox`、`FrameScheduler`、
共享 API baseline 与构建脚本未改动。

## 语义与不变量

1. **单引用双职责**：`Lease.snapshot` 是 volatile 引用。非 null 表示 lease 未关闭且值为 acquisition 时绑定的
   `AdapterRegistry`；null 表示已关闭。读路径只做一次 volatile 读。
2. **关闭线性化点**：`close()` 先 volatile 读 `state` 双检，再在 `synchronized(state)` 内完成
   `slot.leases--`、`activeLeases--`，并依次 `slot=null`、`snapshot=null`、`state=null`。同一 lease 的重复 close
   幂等。关闭路径的 refcount/代际语义与改动前一致。
3. **关闭即释放**：close 后 Lease 的 `snapshot`/`slot`/`state` 全为 null，保留已关闭 lease 不会强持旧快照、
   Slot 或 State；`Retirement.forget()` 之后 `Slot.snapshot` 被置空、注册表 `snapshots` 映射移除该代际，
   旧 `AdapterRegistry` 不再被注册表、lease 或 Retirement 强引用。测试用反射断言这些强引用断开；GC 回收时刻
   未被承诺（不在 unit gate 中跑 `System.gc()`/WeakReference 探测）。
4. **退休不失效**：`replace`/`close` 只退役 registry 的当前代际，不使存活 lease 失效；存活 lease 持
   `slot.leases>0`，`forget()` 因 `leases!=0` 拒绝释放，故其绑定的快照在 lease 存活期间不会被置空。
5. **不泄露/不抹错**：读路径只返回不可变 `AdapterRegistry`，不暴露 Slot/State/Lease；关闭后读抛
   `IllegalStateException`，不吞异常。

## JMM 论证

- **构造与发布**：构造器顺序为 `snapshot`（volatile）→ `slot` → `state`（volatile，最后写）。读线程对
  `snapshot` 的 volatile 读若观察到非 null，则与构造器对该字段的写建立 happens-before，返回的引用即为
  构造完成的 `AdapterRegistry`。`state` 的最后写保证 `close()` 在同步块内读到的 `slot` 已发布。
- **线性化点**：`snapshot()` 的 volatile 读即线性化点。读到非 null 时，该读在同步序中先于任何后续 `close()` 对
  `snapshot` 的写，因此返回 acquisition 时绑定的快照；读到 null 时抛错。`close()` 的线性化点保持在其
  `synchronized` 块内对 `snapshot`/`state` 的置 null 写。
- **重叠语义**：读与 close 重叠时返回旧快照是前线性化（读线性化在 close 的写之前），与监控版语义一致。调用方
  不得在一边 close lease 一边继续使用已返回引用所指向的 native 资源；已返回引用不能自动撤销。
- **volatile 可见性与确定性验证**：volatile 本身提供 synchronization visibility。跨线程 close 后的新读用
  `CountDownLatch` 建立操作先后（close 完成 → countDown → 读线程 await 返回），测试断言抛错；该确定性测试
  不依赖 wall-clock 随机交错。
- **不可变快照**：`AdapterRegistry` 构造后字段不可变（`providers`/`selected` 为不可修改映射，`Descriptor`
  集合不可修改），`candidateChecks` 仅在构造器内写。因此读路径无需再持有 monitor 来保证快照内容可见。

## 复现方式

单元测试与 lint（JDK 21；官方 `scripts/run-gradle.ps1 verifyHost` 要求 JDK 25/27，需自备对应环境）：

```shell
./gradlew :android-runtime:testDebugUnitTest :android-runtime:lintDebug --offline --no-daemon --max-workers=2
```

Host 基准（纯 JVM，无 Android/Robolectric/设备；`benchmarks/` 不在根 Gradle 构建内）：

```shell
# JDK 17+；javac/java 取自 JAVA_HOME 或 Gradle 托管 JDK
javac -nowarn -d build/host-registry/classes \
  android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/*.java \
  benchmarks/host-core/RegistryReadBenchmark.java
java -cp build/host-registry/classes RegistryReadBenchmark steady 4 12000 2000 5 1
java -cp build/host-registry/classes RegistryReadBenchmark acquire 4 12000 2000 5 1
```

基线定义：`RetiringRegistry.java` 在公开基线 `6099984` 与 `04af178` 逐字节相同（`git diff 6099984 04af178 --
<该文件>` 为空）。基线编译用 `git show 6099984:<该文件>` 还原后同法编译；候选版为 `04af178` + 本改动。
两版使用同一 JDK、同一输入、同一基准源码，仅 `RetiringRegistry.java` 不同。

ABBA 交错（独立 JVM fork，A=基线，B=候选，每轮 `A B B A`）：

```shell
for pair in 1 2 3 4 5 6; do
  for tag in A B B A; do
    if [ "$tag" = A ]; then cp=build/host-registry/baseline; else cp=build/host-registry/fixed; fi
    java -cp "$cp" RegistryReadBenchmark <mode> 4 12000 2000 5 1
  done
done
```

## 已验证数据（host JVM 21.0.7，非 ART、非真机；描述性统计，无 p99 声明）

差分口径：`steady`（tryAcquire + snapshot + close）与 `acquire`（tryAcquire + close）锁流量相同，
两者差值隔离 snapshot 读路径成本。每 worker 先热身再自行计时，报告 wall 取最慢 worker、CPU 取各 worker 之和；
每组 5 次重复，舍去第 1 次（JIT）。ABBA 4 对、每对 4 个独立 JVM、每 JVM 5 次重复（n=32/组）。

| 组 | 均值 ns/op | 对均值（A B B A） |
| --- | --- | --- |
| A（基线）steady | 249.6 | 239.4 / 221.8 / 268.6 / 268.8 |
| B（候选）steady | 206.5 | 193.4 / 195.8 / 211.5 / 225.4 |
| A（基线）acquire | 156.3 | 149.0 / 148.1 / 159.0 / 169.1 |
| B（候选）acquire | 161.7 | 150.5 / 164.2 / 144.0 / 187.9 |

- **steady 4/4 对候选更快**，均值 249.6 → 206.5 ns/op（约 -17%，对内比值 0.79–0.88）。
- **acquire 两版在噪声内相当**（156.3 vs 161.7，候选略慢但跨度重叠），说明 tryAcquire/close 锁流量未变，
  steady 的收益来自读路径，而非锁口径漂移。
- 两版 checksum 在同一 mode 下逐次一致（`steady` 与 `acquire` 各自固定），操作数与帧数自洽，行为保持由
  `RetiringRegistryTest` 全量 10 项（含 4 项新增回归）与 `:android-runtime:lintDebug` 通过佐证。
- 早期 final-snapshot 变体在另一轮 6 对 ABBA 中 steady 均值 207.4 → 163.6 ns/op（约 -21%），方向一致；
  该变体因 retention 问题已被 volatile 方案取代，此处仅作探索性记录。

## 残余风险与后续

- 跨线程 close 与 snapshot 重叠时返回旧快照属前线性化；调用方需自行保证不在 close 后继续使用已返回资源。
  本改动不改变该责任边界。
- host 数据不构成 ART/真机结论；真机帧 pacing 与 GC 影响需独立设备验证。
- 本候选不背负 performance/speedup 门槛承诺，只陈述上述描述性证据。
