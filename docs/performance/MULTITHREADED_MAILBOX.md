# BoundedMailbox 多线程生产者示例（host 验证）

本文件记录 `android-runtime` 邮箱现成的多生产者安全用法。**不新增任何生产接口、执行器或无界异步桥**：
邮箱本身就是唯一的跨线程交接点。可编译示例与断言见
`android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/MailboxBackgroundProducerExampleTest.java`
（5 项测试，随 `:android-runtime:testDebugUnitTest` 编译执行）。

**独立性**：本用法是 `android-runtime` 的纯内部能力，不引用、不依赖任何 UI/桥接模块，
也不要求为联合验证安装其他库。脱离 UI 体系单独运行时即获得全部下述语义；在体系中只是帧源不同
（独立运行时由宿主自选 `FrameDriver`，体系中可由桥驱动），邮箱语义不变。

## 正式模块路径

- 邮箱：`android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java`
- 词法/生命周期：`.../core/WorkToken.java`、`.../core/LifecycleScope.java`
- 帧调度：`.../core/FrameScheduler.java`
- 测试替身（owner 调度 + 手动帧）：`android-runtime/src/test/java/io/github/jichuo1/meguviz/android/runtime/core/ControlledRuntime.java`

## 模式

owner 线程（主线程）负责：创建 `LifecycleScope`/`FrameScheduler`/`BoundedMailbox`、`newSlot()`、
`setPaused()`、`close()`、`pendingStateCount()`/`pendingCommandCount()`、驱动帧与 drain。
**任意线程**可直接调用 `offerState`/`offerCommand`/`offerBatch`，无需 post、handler 或队列：

```java
// owner 线程：装配
OwnerDispatcher owner = new MainThreadDispatcher();
FrameScheduler scheduler = new FrameScheduler(owner, new ChoreographerDriver(owner), errors, 64);
LifecycleScope scope = new LifecycleScope(owner, errors);
scope.setAttached(true); scope.setStarted(true);
WorkToken token = scope.lease(LifecycleScope.ALL).token();
BoundedMailbox box = new BoundedMailbox(scope, scheduler, errors, 64, 1024, 512);
BoundedMailbox.Slot slot = box.newSlot();          // slot 只能在 owner 线程创建

// 后台生产者线程：直接 offer
box.offerState(slot, token, () -> applyLatest(value), release);   // 同 key 自动合并为最新值
box.offerCommand(token, () -> runInOrder(), release);             // FIFO
box.offerBatch(token, List.of(a, b, c), release);                 // 整批原子准入、按序执行

// owner 线程：帧到来即 drain（由 FrameScheduler 驱动），无需生产者同步
```

## 语义（均有上述测试或既有契约测试锁定）

| 性质 | 行为 |
| --- | --- |
| 每 key latest | 同一 `Slot` 多次 `offerState`：旧项 `SUPERSEDED` 并立即释放，仅最后准入的值被应用一次 |
| command FIFO | `offerCommand`/`offerBatch` 按准入顺序执行；单生产者的相对顺序严格保持，批内按序 |
| 唤醒合并 | 任意数量跨线程提交只维持一个未决唤醒（`runtime.posts == 1`，10k 提交实测） |
| 背压 | 新 key/命令超过 `stateCapacity`/`commandCapacity` 返回 `REJECTED_CAPACITY`；已存在 key 的替换永远允许（`REPLACED`） |
| 清理 | 每个终止项释放回调恰好一次；superseded 项的释放**可能在生产者线程**执行，release 必须线程安全且不做重活 |
| 异常 | action 抛出 → 该项 `FAILED`、邮箱关闭、其余已准入项全部释放且不执行；错误经 `ErrorSink` 上报 |
| 关闭 | owner 线程 `close()` 释放全部待决跨线程工作且不执行其 action |
| 词法 | `WorkToken` 过期（rebind/attach 失效）→ 项 `STALE`，action 不执行 |

## 约束（不要越界）

- `offerState/offerCommand/offerBatch` 多线程安全；`newSlot/setPaused/close/onClose/pending*Count/isClosed`
  仅限 owner 线程（内部 `checkOwner` 强制）。
- release 回调不得阻塞或抛业务异常（抛出会并入失败上报）；重活请放回 owner 线程的 action。
- 不要为了“异步”再包执行器/无界队列：邮箱已提供有界合并与单唤醒；无界桥正是本设计要避免的。

## 运行

```powershell
./gradlew :android-runtime:testDebugUnitTest --no-daemon
```

`MailboxBackgroundProducerExampleTest` 5 项与 `MailboxContractTest`（23 项，含 `backgroundStorm…`
单唤醒/有界存储、`rejectedDispatcher…` 派发器拒绝、`callbackFailureClosesOnlyAffectedMailbox`）
共同覆盖上表。JDK 环境说明见 `docs/performance/RUNTIME_FAST_PATHS.md`（官方脚本要求 JDK 25/27）。
