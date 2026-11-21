# RetiringRegistry 读路径：Lease 快照免锁读

本文件记录 `RetiringRegistry.Lease.snapshot()` 的无锁读改动、Java 内存模型（JMM）论证与宿主 JVM 验证数据。

## 改动内容

| 文件 | 改动 |
| --- | --- |
| `android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/RetiringRegistry.java` | `Lease` 改为单一 `volatile AdapterRegistry<T> snapshot` 引用，兼作快照值与关闭哨兵；`snapshot()` 移除 `synchronized`，仅做一次 volatile 读后非 null 即返回；`close()` 在 `synchronized (state)` 内将 `slot`、`snapshot`、`state` 依次置为 `null` |
| `android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/RetiringRegistryTest.java` | 新增 4 项回归：关闭后字段全置 `null`、`forget` 清空 `Slot` 快照与注册表映射、`CountDownLatch` 建立 happens-before 后跨线程读取抛异常 |
| `benchmarks/host-core/RegistryReadBenchmark.java` | 新增差分宿主基准（`steady` 含 `snapshot()`，`acquire` 不含，两者 `tryAcquire`/`close` 锁流量一致） |

公开 ABI 保持不变（新增与修改均为私有字段），`BoundedMailbox`、`FrameScheduler` 与构建脚本未变动。

## 语义与不变量

1. **单引用双职责**：`Lease.snapshot` 为 `volatile` 引用，非 `null` 表示未关闭并保存绑定时的 `AdapterRegistry`，`null` 表示已关闭。读路径仅执行一次 `volatile` 读。
2. **关闭线性化点**：`close()` 先对 `state` 做 volatile 双检，再在 `synchronized (state)` 内递减引用计数并清空 `slot`、`snapshot`、`state`，重复关闭保持幂等。
3. **关闭即断开强引用**：`close()` 后 `Lease` 不再强持有快照或内部状态；`Retirement.tryRelease()` 完成后清空 `Slot` 快照并从注册表移除该代际。单测通过反射验证强引用断开（不依赖 `System.gc()`）。
4. **旧代际保留规则**：`replace` / `close` 仅使当前代际进入待回收状态，存活中的 `Lease` 在关闭前仍安全持有对应快照。
5. **异常语义不变**：只返回不可变 `AdapterRegistry`，关闭后读取仍抛出 `IllegalStateException`。

## JMM 论证

- **安全发布**：构造器按 `snapshot`（volatile）→ `slot` → `state`（volatile）顺序写入；读线程观察到非 `null` 的 `snapshot` 即与构造器建立 happens-before，保证看到已完整初始化的不可变 `AdapterRegistry`。
- **线性化与重叠调用**：`snapshot()` 的 volatile 读为读线性化点，`close()` 的 `synchronized` 内写 `null` 为关闭线性化点。读与关闭并发重叠时，前线性化读取返回旧快照属正常语义，但调用方仍须确保不在关闭 `Lease` 后继续使用底层外部资源。
- **不可变快照**：`AdapterRegistry` 构造后内部映射与描述符集合均不可修改，`candidateChecks` 仅在构造器写入，因此读路径无需加锁同步内容可见性。

## 复现方式

```shell
# 单元测试与 Lint（JDK 21+）
./gradlew :android-runtime:testDebugUnitTest :android-runtime:lintDebug --offline --no-daemon --max-workers=2

# 纯 JVM 基准（benchmarks/ 不在根构建内，JDK 17+）
javac -nowarn -d build/host-registry/classes \
  android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/*.java \
  benchmarks/host-core/RegistryReadBenchmark.java
java -cp build/host-registry/classes RegistryReadBenchmark steady 4 12000 2000 5 1
java -cp build/host-registry/classes RegistryReadBenchmark acquire 4 12000 2000 5 1
```

## 宿主基准测量（HotSpot JVM 21.0.7）

对照基线取自提交 `6099984` 与 `04af178` 还原的 `RetiringRegistry.java`，采用 4 对独立 JVM fork 的 ABBA 交错（`A B B A`），每组 5 次重复并舍去首轮 JIT 热身（n=32/组）：

| 组别 | 均值 ns/op | 各对均值（A B B A） |
| --- | --- | --- |
| A（基线）`steady` | 249.6 | 239.4 / 221.8 / 268.6 / 268.8 |
| B（候选）`steady` | 206.5 | 193.4 / 195.8 / 211.5 / 225.4 |
| A（基线）`acquire` | 156.3 | 149.0 / 148.1 / 159.0 / 169.1 |
| B（候选）`acquire` | 161.7 | 150.5 / 164.2 / 144.0 / 187.9 |

- **`steady` 4/4 对均有所下降**：均值由 `249.6` 降至 `206.5 ns/op`（约 `-17%`，对内比值 `0.79–0.88`）。
- **`acquire` 两版在噪声内持平**：证明锁流量未变，`steady` 降幅源自读路径去锁。
- 两版功能校验和一致，`RetiringRegistryTest`（10 项）与 `:android-runtime:lintDebug` 通过。

## 适用范围与验证说明

- 本文件仅陈述宿主 JVM 的描述性测量与并发语义校验，不设性能门槛，也不外推至 Android ART 或真机收益；跨线程关闭与资源生命周期边界仍由调用方保证。
- 官方 `scripts/run-gradle.ps1 verifyHost` 门禁需在 JDK 25 或 JDK 27 环境运行。
