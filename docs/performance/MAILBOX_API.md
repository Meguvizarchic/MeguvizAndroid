# BoundedMailbox API（有界多线程邮箱）

`android-runtime` 的 `BoundedMailbox` 是**有界、多生产者**的 latest-state + FIFO-command 邮箱，
也是唯一的跨线程交接点：不引入执行器、无界队列或异步桥。任意线程可直接 `offer*`，
owner 线程负责帧调度、drain 与生命周期。

- 实现：`android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java`
- 词法/生命周期：`.../core/WorkToken.java`、`.../core/LifecycleScope.java`
- 帧调度：`.../core/FrameScheduler.java`
- 多生产者用法与语义：`docs/performance/MULTITHREADED_MAILBOX.md`

## 准入入口

每个入口都有两种形式：**完整形式**（带 `release`）与**简写形式**（省略 `release`；
`offerState` 为 3 参，`offerCommand`/`offerBatch` 为 2 参）。
简写形式是完整形式的纯委托别名，内部共用一个共享静态 no-op 作为 release，
因此调用方无需写空 releaser，也不改变任何准入与终止语义。

| 入口 | 完整形式 | 简写形式 | 语义 |
| --- | --- | --- | --- |
| 状态 | `offerState(Slot, WorkToken, Runnable, Runnable)` | `offerState(Slot, WorkToken, Runnable)` | 同 slot 合并为最新值，旧项 `SUPERSEDED` |
| 命令 | `offerCommand(WorkToken, Runnable, Runnable)` | `offerCommand(WorkToken, Runnable)` | FIFO，按准入顺序执行 |
| 批量 | `offerBatch(WorkToken, List<Runnable>, Runnable)` | `offerBatch(WorkToken, List<Runnable>)` | 整批原子准入、批内按序；调用方列表被快照 |

```java
// 简写：action 不需要释放调用方资源时。返回的 Submission 必须处理，不能忽略。
Submission state = box.offerState(slot, token, () -> applyLatest(value));
if (!state.accepted()) {
    // REJECTED_CAPACITY（背压）/ REJECTED_CLOSED / UNSUPPORTED（slot 不属于该邮箱）
    return;
}

Submission command = box.offerCommand(token, () -> runInOrder());
if (!command.accepted()) {
    // REJECTED_CAPACITY（背压）/ REJECTED_CLOSED / UNSUPPORTED，按调用方策略处理，不要吞掉状态
    return;
}

Submission batch = box.offerBatch(token, Arrays.asList(a, b, c));
if (!batch.accepted()) {
    return;
}

// 完整形式：action 分配了必须释放的资源时。被拒绝时邮箱不持有该项，release 不会被调用，
// 调用方必须立即自行清理。
NativeHandle handle = acquire();
Submission work = box.offerCommand(token, () -> use(handle), handle::release);
if (!work.accepted()) handle.release();
```

`Submission` 返回值不变：`admission()` 为 `ACCEPTED`/`REPLACED`/`REJECTED_CAPACITY`/
`REJECTED_CLOSED`/`UNSUPPORTED`，`handle()` 用于查询 `Outcome` 与 `cancel()`。
简写**不会**吞掉 `REJECTED_*`/`UNSUPPORTED` 状态。

## 语义

| 性质 | 行为 |
| --- | --- |
| 每 key latest | 同一 `Slot` 多次 `offerState`：旧项 `SUPERSEDED` 并立即释放，仅最后准入的值被应用一次 |
| command FIFO | `offerCommand`/`offerBatch` 按准入顺序执行；单生产者的相对顺序严格保持，批内按序 |
| 背压 | 新 key/命令超过 `stateCapacity`/`commandCapacity` 返回 `REJECTED_CAPACITY`；已存在 key 的替换永远允许（`REPLACED`） |
| 唤醒合并 | 任意数量跨线程提交只维持一个未决唤醒 |
| 释放 | 每个终止项的 release 恰好一次；简写使用共享 no-op，调用方无清理负担，但终止/释放路径与完整形式完全一致 |
| 异常 | action 抛出 → 该项 `FAILED`、邮箱关闭、其余已准入项全部释放且不执行；错误经 `ErrorSink` 上报 |
| 关闭 | owner 线程 `close()` 释放全部待决跨线程工作且不执行其 action |
| 词法 | `WorkToken` 过期（rebind/attach 失效）→ 项 `STALE`，action 不执行 |
| 所有权 | 非法 slot/token 返回 `UNSUPPORTED`，不转移所有权；`newSlot` 仍仅限 owner 线程 |

## 简写的前提

简写仅适用于 **action 不需要释放调用方资源** 的场景。它不改变：

- `WorkToken` 与 `Slot` 归属校验（`UNSUPPORTED` 仍返回）；
- 容量拒绝、`close` 后拒绝、`pause`、`STALE` 等状态机；
- release 的 exactly-once 语义（由共享 no-op 承担，调用方无可观察副作用）；
- producer 侧 release 的线程安全性要求（简写下由邮箱内部 no-op 执行）。

若 action 持有必须释放的资源（原生句柄、缓冲区、注册项等），必须使用完整形式并提供
线程安全的 release；不要把资源释放塞进 action 来绕过。

## 验证

- `android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/MailboxConvenienceContractTest.java`
  （10 项）逐项对照完整形式与简写：准入/合并/FIFO、容量与 close 拒绝、越权拒绝、多生产者单唤醒与
  每生产者 FIFO、批内顺序与部分失败、列表快照、stale、superseded/cancel、参数校验。
- `MailboxContractTest`（23 项）与 `MailboxBackgroundProducerExampleTest`（5 项）继续锁定完整形式语义。

```powershell
./gradlew :android-runtime:testDebugUnitTest --no-daemon
```
