# Central 本地生产准备

当前工程仅配置本机 `Candidate` Maven 仓库，未配置 Maven Central 上传端点；启用本流程只会在本地生成候选包，不代表自动上传。四个模块的 release publication 各包含 AAR、POM、Gradle metadata、sources JAR 与 `javadoc` classifier。`javadoc` JAR 由本次 release AAR 的 `classes.jar` 经 JDK `javap -public` 生成，记录 JVM 可见公开声明摘要与 AAR SHA-256，并非完整 KDoc 文档，也可能包含 Kotlin `internal` 或合成的 JVM 可见成员。

## 构建与签名规则

- **无密钥默认构建**：日常宿主验证、GitHub Release 与本地候选构建均不要求 GPG 签名密钥。
- **按需启用签名**：仅当显式传入 `-Pmeguviz.centralPrepare=true` 时，Gradle `signing` 插件才会为 publication 签名；未开启时执行 `prepareCentralLocal` 会按预期阻塞。
- **不可变版本约束**：`1.0.0`、`1.1.0` 与 `1.2.0` 均已发布至 Maven Central，已发布的版本号不可覆盖。准备新的发布候选时，必须同步指定新的不可变非 SNAPSHOT 版本（例如 `-Pmeguviz.releaseVersion=1.2.1`）。
- **编译与最低 SDK 声明**：自 `1.1.0` 起，`android-runtime` 使用 `compileSdkMinor = 2` 编译 API 37.2 的 `MemoryBudgetApi37` 内部实现，并以运行时完整版本检查隔离；其 AAR 元数据（`aarMetadata.minCompileSdk`）保持为 `37`，消费方无需升级至 `37.2`。
- **凭据安全**：签名密钥与口令须通过安全的本机 Gradle/GPG 环境提供，禁止写入仓库文件、命令行日志或验证回执。

## 本地只读核验

在确认本机候选仓库路径、不可变版本号及 JDK 路径后，可执行只读校验：

```powershell
python scripts/verify_central_local.py --repo <本地Maven仓库> --version <不可变版本> --javap <JDK的javap路径>
```

校验脚本会检查四模块的 AAR、sources、POM/Gradle metadata 与签名参考 JAR 的完整性，并用 `javap` 抽查公开声明：
- 若缺少 `.asc` 文件，返回 `BLOCKED_MISSING_SIGNATURES`；
- 脚本仅核验 `.asc` 文件是否存在而不读取私钥或校验信任链，文件齐全时返回 `BLOCKED_SIGNATURES_UNVERIFIED`，正式发布前仍须使用发布公钥独立验签；
- 缺失或损坏的文档、制品与元数据均判定失败。

## 适用范围与验证说明

- 本地候选构建与只读核验覆盖静态制品格式、元数据及声明边界；消费方依赖闭包、R8 混淆消费、跨库桥接与设备运行表现仍需通过独立的消费者与设备测试验证。
- 历史 `1.0.0-rc.1` / `1.0.0-rc.2` 的本地构建核验与假密钥负例证据存放于工作区本地验证目录，不随本仓库分发。
