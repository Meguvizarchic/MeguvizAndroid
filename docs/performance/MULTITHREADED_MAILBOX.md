# BoundedMailbox 多线程生产者用法

本文件说明 `android-runtime` 邮箱的多生产者安全用法。邮箱本身即为跨线程交接点，无需再额外包装线程池或无界异步队列。可编译示例见 `MailboxBackgroundProducerExampleTest.java`，随 `:android-runtime:testDebugUnitTest` 运行。

**模块独立性**：该能力纯属 `android-runtime` 内部实现，不依赖 `MeguvizUI` 或其他桥接库。独立使用时由宿主提供 `FrameDriver`，与 UI 体系联合使用时仅帧驱动来源不同，邮箱本身语义完全一致。

## 相关源码路径

- 邮箱：`android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java`
- 令牌与生命周期：`WorkToken.java`、`LifecycleScope.java`
- 帧调度：`FrameScheduler.java`
- 测试替身（owner 调度与手动帧驱动）：`ControlledRuntime.java`

## 典型模式

owner 线程（主线程）负责初始化 `LifecycleScope`、`FrameScheduler`、`BoundedMailbox`、`newSlot()`、控制 `setPaused()`、驱动帧回调与 `close()`。**任意线程**可直接调用 `offerState`、`offerCommand`、`offerBatch` 或线程独占的 `Offers`：

```java
// owner 线程：装配
OwnerDispatcher owner = new MainThreadDispatcher();
FrameScheduler scheduler = new FrameScheduler(owner, new ChoreographerDriver(owner), errors, 64);
LifecycleScope scope = new LifecycleScope(owner, errors);
scope.setAttached(true);
scope.setStarted(true);
WorkToken token = scope.lease(LifecycleScope.ALL).token();
BoundedMailbox box = new BoundedMailbox(scope, scheduler, errors, 64, 1024, 512);
BoundedMailbox.Slot slot = box.newSlot();          // slot 只能在 owner 线程创建

// 后台生产者线程：直接提交（若携带自持资源且被拒收，调用方需立即释放）
box.offerState(slot, token, () -> applyLatest(value), release);   // 同 key 自动合并为最新值
box.offerCommand(token, () -> runInOrder(), release);             // FIFO
box.offerBatch(token, List.of(a, b, c), release);                 // 整批原子入队、按序执行

// owner 线程：帧到来时由 FrameScheduler 驱动 drain
```

## 核心契约

| 性质 | 行为 |
| --- | --- |
| 每 key latest | 同一 `Slot` 多次 `offerState`：旧项转为 `SUPERSEDED` 并立即释放，仅执行最后接收的值 |
| command FIFO | `offerCommand` / `offerBatch` 按入队顺序执行；单生产者相对顺序严格保持，批内按序 |
| 唤醒合并 | 任意数量跨线程提交只维持一个未决唤醒（10,000 次并发提交测试中 `runtime.posts == 1`） |
| 背压控制 | 新 key 或命令超出容量上限返回 `REJECTED_CAPACITY`；已存在 key 替换始终允许（`REPLACED`） |
| 清理线程 | 每个已入队终止项的 `release` 仅执行一次；被替换项的 `release` **可能在生产者线程**执行，回调须线程安全且轻量 |
| 异常与关闭 | `action` 抛错只关闭当前邮箱并释放待决项；owner `close()` 释放全部待决工作且不执行 `action` |
| 令牌失效 | `WorkToken` 过期后项转为 `STALE`，`action` 不执行 |

## 约束边界

- `offerState` / `offerCommand` / `offerBatch` 多线程安全；`newSlot` / `setPaused` / `close` / `onClose` / `pending*Count` / `isClosed` 仅限 owner 线程（由 `checkOwner` 强制校验）。
- `release` 回调不得阻塞或抛出业务异常；较重的逻辑应保留在 owner 线程的 `action` 中处理。
- 不要在邮箱外层再套异步执行器或无界队列，以免破坏有界背压与单唤醒机制。

## 运行与适用范围

```powershell
./gradlew :android-runtime:testDebugUnitTest --no-daemon
```

`MailboxBackgroundProducerExampleTest`、`MailboxContractTest` 与 `MailboxConvenienceContractTest` 共同覆盖上述语义。Robolectric SDK 37 宿主单测需在 JDK 21 及以上运行（完整官方门禁 `scripts/run-gradle.ps1 verifyHost` 要求 JDK 25 或 JDK 27）；宿主测试通过不代表真机 ART 性能已验收。
