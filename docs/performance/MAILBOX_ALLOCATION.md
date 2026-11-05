# BoundedMailbox 分配收窄：latest/extracted 容器与 setPaused 幂等（host 验证）

本文件记录 2026-11-05 对 `android-runtime` 邮箱提交/终止路径的三处分配改动。只描述已实现内容与
已验证证据；不承诺零分配，不声称 ART/真机收益。

## 改动内容

| 文件 | 改动 |
| --- | --- |
| `android-runtime/src/main/java/io/github/jichuo1/meguviz/android/runtime/core/BoundedMailbox.java` | `latest` 由 `LinkedHashMap<Slot, Item>` 改为 `IdentityHashMap<Slot, Item>`（只做 identity 的 get/put/remove/size/clear，无顺序依赖，`Slot` 未重写 equals/hashCode） |
| 同上 | `extracted` 由 `LinkedHashSet<Item>` 改为 `ArrayDeque<Item>`（只 add/remove/isEmpty/迭代；常见移除顺序与 FIFO 一致，摊还 O(1)，乱序 cancel 为 O(quota)） |
| 同上 | `setPaused` 在 `closed` 检查后增加 `if (paused == value) return;`，同值重复调用不再分配集合并调 `channel.cancelPending()` |

`states` 仍为 `LinkedHashMap`（drain 队列，替换需保序）；`Terminal`/`WorkHandle` 未改。
公开 ABI 不变（`git diff -- api/` 为空），未引入依赖。

## 不变量

1. **identity 语义**：`Slot` 依赖对象同一性；`IdentityHashMap` 与 `LinkedHashMap` 对未重写
   `equals/hashCode` 的键行为一致，只是不再维护插入序与 before/after 指针。
2. **extracted 迭代顺序**：`ArrayDeque` 保持插入序，与 `LinkedHashSet` 一致；`setPaused`/
   `terminateAll` 的遍历顺序不变。
3. **容量与终止语义**：容量拒绝、supersede、stale、exactly-once release、异常关闭、关闭顺序不变。
4. **setPaused 幂等**：已处于目标状态时直接返回；待处理工作的帧请求由 `offer`/`drain` 自身路径保证，
   不会漏唤醒。

## 复现方式

单元测试（需 JDK 21；默认 `JAVA_HOME` 为 17 时 Robolectric 的 Android SDK 37 沙箱会失败）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
.\gradlew.bat :android-runtime:testDebugUnitTest --offline --no-daemon --max-workers=2
```

Host 基准（纯 JVM，无 Android/Robolectric/设备；`benchmarks/` 不在根 Gradle 构建内）：

```powershell
# 用 JDK 21 的 javac/java；before 用 `git show HEAD:...BoundedMailbox.java` 还原，after 用工作区文件，
# 各自编译到仓库外临时目录后运行 FrameBufferBenchmark，ABBA 交错。
java -cp <out> FrameBufferBenchmark 8 16 20000 15000 3
```

## 已验证数据（host JVM 21.0.7，非 ART、非真机；描述性统计）

8 邮箱 × 16 slot × 20000 帧（测量窗 15000 帧 × 3 reps），ABBA 交错：

| 轮次 | allocated_bytes_per_frame |
| --- | --- |
| 改前 #1 | 7384 / 7208 / 7208 |
| 改后 #1 | 5014 / 4968 / 4968 |
| 改后 #2 | 5026 / 4968 / 4968 |
| 改前 #2 | 7370 / 7208 / 7208 |

每帧分配由约 7208–7384 B 降至 4968–5026 B，约 **-31%**；`executed_ops` 每轮均为 480000
（功能等价）；GC 次数 0–1、`gc_ms` 0，无明显变化。耗时同步下降但属 JIT/噪声，不作为结论。

`MailboxContractTest`、`MailboxConvenienceContractTest`、`MailboxBackgroundProducerExampleTest`
全绿；`:android-runtime:testDebugUnitTest` 在 JDK 21 下通过。

## 残余风险与后续

- `extracted.remove(Object)` 对乱序 cancel 为 O(quota)；quota 极大且乱序 cancel 密集时需复测。
- `states` 仍为 `LinkedHashMap`，每次新键状态 offer 仍分配 Entry；审计曾评估 `ArrayDeque<Slot>`
  加 `queued` 标记的方案（有界循环约 -42%，但替换密集场景略亏），未实施。
- host 数据不构成 ART/真机结论；真机帧 pacing 与 GC 影响需独立设备验证。

## 2026-11-05（第二轮）：states 队列与 Signal 复用（host 验证）

第二轮继续收窄邮箱的每帧分配，仍只改 `BoundedMailbox.java` 的私有成员。

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| `states` → `ArrayDeque<Slot> stateQueue` | `BoundedMailbox.java` | `Slot` 增加包私有 `queued` 标记；替换已排队项接管原位置（O(1)），新项 `addLast`；drain 用 `pollFirst()` + `latest.get(slot)`，不再为每项分配迭代器与 Entry |
| `Signal` 单实例复用 | 同上 | 新增 `signalPending` 标志；`requestSignalLocked` 检查/置位并复用单例 `signal`；`post`/`Signal.run` 以标志判断 obsolete；`cancelIdleWake`/`setPaused`/`terminateAll` 同步维护 |

顺序语义保持：替换不移动队列位置、drain 后重发追加尾部、pause 重排队追加尾部、cancel/stale/close 按 slot 移除。`terminateAll` 先按 `stateQueue` 的 drain 序收集，再并上 `commands`/`extracted`，然后整队列 `clear()`，并对每项以 `removeStatePosition=false` 终止以避免 O(n²)；释放回调因此仍按确定的 drain 序，而不是 `IdentityHashMap` 迭代序。

ABBA 交错（JDK 21，8 邮箱 × 16 slot × 20000 帧，`FrameBufferBenchmark 8 16 20000 5000 3`）：

| 轮次 | allocated_bytes_per_frame |
| --- | --- |
| 改前 | 5028 / 4968 / 4968 |
| 改后 | 3880 / 3880 / 3880 |

稳态 4968 → 3880 B/帧，约 **-21.9%**；`executed_ops` 两侧均为 480000。`:android-runtime:testDebugUnitTest`（70 项）与 `:android-window:testDebugUnitTest` 通过；`javap -public` 全量对比 before/after 逐字节相同，`api/android-runtime.txt` 不变。

残余：`signalPending` 保证不漏唤醒、不多请求平台帧，但不保证 owner 队列里只有一份 `Signal`——门控 dispatcher 在「拿到 dispatch 后、`release()`/`owner.post` 前被抢占」的窗口下可能重复入队，多余的那份因标志早退（不会多请求帧，也不漏项）；`stateQueue.remove` 对「取消已排队状态项」为 O(n)（`capacity ≤ 256` 时在噪声内，1024 + 逆序密集取消约 2.5×，且 `WorkHandle.cancel()` 可从后台线程调用、持锁期间拖住 owner）；`extracted.remove` 对乱序 cancel 为 O(quota)。host 数据不构成 ART/真机结论。
