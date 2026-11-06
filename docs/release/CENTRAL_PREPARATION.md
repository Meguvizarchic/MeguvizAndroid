# Central 本地生产准备

当前工程只配置本机 `Candidate` Maven 仓库，没有 Central 上传端点；本说明不代表已发布或批准上传。四个模块的 release publication 各包含 AAR、POM、Gradle metadata、sources JAR 和 `javadoc` classifier。`javadoc` JAR 是由**本次 release AAR 的 classes.jar**经 JDK `javap -public` 生成的 JVM 可见签名参考，附带 AAR SHA、类数和签名摘要；它不是完整 Kotlin KDoc 或行为说明，也可能包含 Kotlin `internal` 或合成的 JVM 可见成员。

默认 host、GitHub Release 和本地候选构建不要求签名密钥。仅显式指定 `-Pmeguviz.centralPrepare=true` 才会使用 Gradle 内置 signing 插件为 publication 准备签名；`prepareCentralLocal` 在未显式启用时返回 BLOCKED，启用后仍要求非 SNAPSHOT 版本及有效签名配置。当前根构建默认是 `1.2.0`（`1.0.0` 与 `1.1.0` 均已发布，不可变）；显式启用 Central 模式时仍须同时提供 `-Pmeguviz.releaseVersion=1.2.0` 等不可变版本。自 `1.1.0` 起 `android-runtime` 以 `compileSdkMinor = 2` 编译，但 AAR 元数据的最低编译版本保持 37.0（`aarMetadata.minCompileSdk`），API 37.2 类型只在 `MemoryBudgetApi37` 内、受运行时完整版本检查保护。已在隔离目录用 `1.0.0-rc.2` 实际生成四模块 POM 和 Gradle metadata，分别核对 API/runtime 变体的模块内版本；这些是元数据检查，不是签名或发布证明。签名配置通过安全的 Gradle/GPG 环境提供，不应写入仓库、命令行日志或测试回执；本工程脚本不读取、保存或打印凭据。

在确认本机候选仓库、非 SNAPSHOT 版本及 JDK 路径后，可只读核验：

```powershell
python scripts/verify_central_local.py --repo <本地Maven仓库> --version <不可变版本> --javap <JDK的javap路径>
```

核验器检查四模块实际 AAR、sources、POM/Gradle metadata、非空签名参考与本次 AAR 的 SHA，并用 `javap` 对每个模块抽查实际公开声明；它只检查 `.asc` 文件是否存在，不读取密钥或验证签名信任。缺少签名时返回 `BLOCKED_MISSING_SIGNATURES`；即使签名文件齐全也返回 `BLOCKED_SIGNATURES_UNVERIFIED`，还需使用已确认的发布公钥独立验证签名和 Central bundle。缺失或损坏的文档、制品及元数据返回失败，不视为通过。

依赖闭包、独立 Maven/R8 消费、实际桥、升级与设备范围仍需对应的真实验证。对外上传需要单独授权；用户选择延后生产设备验证并不意味着原设备门槛已有通过回执。

本地验证：四模块 `1.0.0-rc.1` 的 AAR、sources、JVM 签名参考、POM、Gradle metadata 和校验和均已实际核对；四个 AAR 与此前受测制品字节相同。独立负例已验证错误 POM 版本、错误 runtime 变体与额外文档条目会被拒绝。使用明确不存在的测试密钥路径时，签名任务按预期失败；没有读取真实密钥或上传。上述证据与原始日志保存在工作区的本地验证目录，不自动随本仓库提供。
