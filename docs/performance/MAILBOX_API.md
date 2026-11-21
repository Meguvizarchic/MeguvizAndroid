# BoundedMailbox API（有界多线程邮箱）

`android-runtime` 的 `BoundedMailbox` 是**有界、多生产者**的 latest-state + FIFO-command 邮箱，也是核心跨线程交接点，不引入额外执行器或无界队列。任意线程可直接调用 `offer*` 提交任务，owner 线程负责帧调度、drain 与生命周期管理。

- 实现：`android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java`
- 令牌与生命周期：`WorkToken.java`、`LifecycleScope.java`
- 帧调度：`FrameScheduler.java`
- 多生产者用法与契约：`docs/performance/MULTITHREADED_MAILBOX.md`

## 提交入口

每个入口都提供**完整形式**（带 `release` 清理回调）与**简写形式**（省略 `release`：`offerState` 为 3 参，`offerCommand` / `offerBatch` 为 2 参）。简写形式内部复用共享静态空操作 `NOOP_RELEASE`，与完整形式共用同一接收校验与终止释放路径。

| 入口 | 完整形式 | 简写形式 | 语义 |
| --- | --- | --- | --- |
| 状态 | `offerState(Slot, WorkToken, Runnable, Runnable)` | `offerState(Slot, WorkToken, Runnable)` | 同 `Slot` 合并为最新值，旧项状态转为 `SUPERSEDED` |
| 命令 | `offerCommand(WorkToken, Runnable, Runnable)` | `offerCommand(WorkToken, Runnable)` | FIFO，按接收顺序执行 |
| 批量 | `offerBatch(WorkToken, List<Runnable>, Runnable)` | `offerBatch(WorkToken, List<Runnable>)` | 整批原子入队、批内按序；入队时快照列表 |

```java
// 简写：action 不持有需显式释放的调用方资源时使用。必须检查返回的 Submission。
Submission state = box.offerState(slot, token, () -> applyLatest(value));
if (!state.accepted()) {
    // REJECTED_CAPACITY（背压）/ REJECTED_CLOSED / UNSUPPORTED（slot 不属于该邮箱）
    return;
}

Submission command = box.offerCommand(token, () -> runInOrder());
if (!command.accepted()) {
    return;
}

Submission batch = box.offerBatch(token, Arrays.asList(a, b, c));
if (!batch.accepted()) {
    return;
}

// 完整形式：action 持有必须释放的资源时使用。被拒收时邮箱不接管该项，release 不会执行，
// 调用方必须立即自行释放。
NativeHandle handle = acquire();
Submission work = box.offerCommand(token, () -> use(handle), handle::release);
if (!work.accepted()) handle.release();
```

`Submission` 提供 `admission()`（`ACCEPTED` / `REPLACED` / `REJECTED_CAPACITY` / `REJECTED_CLOSED` / `UNSUPPORTED`）与 `handle()`（用于查询 `Outcome` 与调用 `cancel()`），简写形式同样完整返回这些状态。需要一次无交错地提交多项状态/命令时，可使用生产者线程专属的 `BoundedMailbox.Offers`（Kotlin 端可通过 `offers.submitBatch { ... }` 内联调用）。

## 行为语义

| 性质 | 行为 |
| --- | --- |
| 每 key latest | 同一 `Slot` 多次 `offerState`：旧项 `SUPERSEDED` 并立即释放，仅最后接收的值被执行一次 |
| command FIFO | `offerCommand` / `offerBatch` 按入队顺序执行；单生产者的相对顺序严格保持，批内按序 |
| 背压控制 | 新 key 或命令超过 `stateCapacity` / `commandCapacity` 返回 `REJECTED_CAPACITY`；已存在 key 的替换始终允许（`REPLACED`） |
| 唤醒合并 | 任意数量跨线程提交仅维持一个未决唤醒（`signalPending`）；极端抢占下多余唤醒会早退，不漏项也不重复请求平台帧 |
| 资源释放 | 每个已入队终止项的 `release` 恰好执行一次；简写使用空操作不产生外部副作用 |
| 异常隔离 | `action` 抛出异常时该项标为 `FAILED`、邮箱关闭并释放其余待决项，错误经 `ErrorSink` 上报 |
| 关闭清理 | owner 线程 `close()` 释放全部待决跨线程工作且不再执行其 `action` |
| 令牌失效 | `WorkToken` 过期（rebind / attach 失效）后项转为 `STALE`，`action` 不执行 |
| 所有权校验 | 非法 `Slot` / `WorkToken` 返回 `UNSUPPORTED` 且不转移所有权；`newSlot()` 仅限 owner 线程 |

## 使用简写的前提

简写形式仅适用于 **`action` 不持有待释放调用方资源** 的场景。它不会绕过 `WorkToken` / `Slot` 归属校验、容量上限或 `close` / `pause` 状态机。若 `action` 持有原生句柄、缓冲区或系统注册项等必须回收的资源，请务必使用完整形式并提供线程安全的 `release`，不要把清理逻辑塞进 `action` 内部（否则在 `STALE` / `SUPERSEDED` / `CLOSED` 时将漏释）。

## 验证与适用范围

- `MailboxConvenienceContractTest` 逐项对照完整形式与简写形式的入队合并、FIFO、拒绝语义与终态行为。
- `MailboxContractTest`、`MailboxOffersEquivalenceTest` 与 `MailboxBackgroundProducerExampleTest` 覆盖并发投递、批量缓冲区等价性、背压及故障隔离。

```powershell
./gradlew :android-runtime:testDebugUnitTest --no-daemon
```

上述校验为宿主 JVM 单元测试，真机帧节奏与多线程内存表现须在目标设备上结合业务负载确认。
