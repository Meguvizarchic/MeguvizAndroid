<div align="center">

<img src="docs/brand/MeguvizAndroid.svg" width="96" height="96" alt="MeguvizAndroid">

# MeguvizAndroid

**Android 生命周期、窗口、资源和有界调度基础库**

运行时 · 窗口 · 生命周期 · 资源加载

<br>

[![License](https://img.shields.io/badge/license-Apache--2.0-00AEEC?style=flat-square&labelColor=2d3a55)](LICENSE)
[![Language](https://img.shields.io/badge/lang-Java-DEA584?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizAndroid)
[![Issues](https://img.shields.io/github/issues/Meguvizarchic/MeguvizAndroid?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizAndroid/issues)

[GitHub Maven 消费](#github-maven-消费) · [本地 Maven 消费](#本地-maven-消费) · [验证](#验证)

</div>

---

<details>
<summary><b>目录</b></summary>

- [GitHub Maven 消费](#github-maven-消费)
- [本地 Maven 消费](#本地-maven-消费)
- [验证](#验证)

</details>

---

Android 生命周期、窗口、资源和有界调度基础库。当前源码版本为 `1.2.0`（尚未发布）。`1.0.0` 与 `1.1.0` 已发布到 Maven Central，Central 上每个文件都附带 `.asc` 签名（本仓库没有验证这些签名）；GitHub Release 另提供本地 Maven 仓库格式的制品。

模块：`android-runtime`、`android-window`、可选 `android-lifecycle` 与 `android-resources-loader`；`samples/` 是独立 Maven 消费者，`testkit/` 和 `benchmarks/` 服务于开发验证。核心模块不反向依赖 UI、反射或 Hook 框架。

## GitHub Maven 消费

`1.0.0` 也可从本仓库 `maven` 分支解析；这是 GitHub 托管的未签名制品，并非 Maven Central 发布。为避免同组的 UI/Ref 依赖被拦截，只对本项目四个模块使用独占仓库：

```kotlin
// settings.gradle.kts: dependencyResolutionManagement.repositories
exclusiveContent {
    forRepository { maven { url = uri("https://raw.githubusercontent.com/jichuo1/MeguvizAndroid/maven") } }
    filter {
        includeModule("io.github.jichuo1.meguviz", "meguviz-android-runtime")
        includeModule("io.github.jichuo1.meguviz", "meguviz-android-window")
        includeModule("io.github.jichuo1.meguviz", "meguviz-android-lifecycle")
        includeModule("io.github.jichuo1.meguviz", "meguviz-android-resources-loader")
    }
}
google()
mavenCentral()
```

## 本地 Maven 消费

将 GitHub Release 的 Maven 仓库 ZIP 解压到本地目录，在消费者的 `settings.gradle.kts` 中加入该目录、Google Maven 和 Maven Central，然后按需要选择模块：

```kotlin
// 在 dependencyResolutionManagement.repositories 中
maven { url = uri("path/to/extracted/maven-repo") }
google()
mavenCentral()

// 在消费者模块的 dependencies 中
implementation("io.github.jichuo1.meguviz:meguviz-android-window:1.2.0")
// 可选：meguviz-android-lifecycle 与 meguviz-android-resources-loader
```

本地仓库中的工件未作为签名发布上传到 Maven Central；使用前核对 GitHub Release 附带的 SHA-256。UI Android 桥单独管理，不包含在本项目的发布包内。

## 验证

使用 JDK 25 或 27、Android SDK API 37 与 build-tools 37.0.0；Windows 设置 `JAVA_HOME` 后执行：

```powershell
# 显式生成并核验本工具链的本地候选，仅绑定三个自家候选的精确校验值。
.\scripts\run-gradle.ps1 dumpApi
python scripts/verification.py consumer-pin
# 校验值准备改变了样例输入，完整门禁必须重新开始。
.\scripts\run-gradle.ps1 verifyHost
```

`verifyHost` 还会把 `dumpApi` 生成的公开 API（`android-runtime`、`android-window`、`android-lifecycle` 与 `android-resources-loader` 四个已发布模块）与 `api/legacy/<版本>/` 里取自 Maven Central 已发布制品的基线比较，要求每个已发布的公开类、成员和父类型仍然存在（只增不删）。`api/*.txt` 本身每次都由构建重写，不是基线。新发布版本后用 `python scripts/legacy_abi.py generate --version <版本> --artifacts-dir <Maven 布局的制品目录>` 生成基线；确需移除时在 `api/legacy/<版本>/allowed-removals.txt` 逐行写 `类名 :: 成员声明 # 理由`。该检查只覆盖公开 JVM 签名，不证明行为兼容。

`consumer-pin` 只接收本次发布记录中的精确坐标，并要求候选 AAR 与当前 release 输出逐字节一致；第三方依赖校验记录保持原样，不信任整个 group，也不关闭 strict 校验。单独执行 `verifyHost` 时，若样例固定的候选校验值与当前工具链重新构建的字节不一致，会按预期拒绝。完整门禁还会检查独立样例解析的模块集合与字节确实等于本次候选。

本仓库使用独立窗口测试 fixture。GitHub 发布的 Maven 仓库 ZIP 中包含四个模块的 `1.0.0` AAR、POM、Gradle metadata、sources 和 JVM API 签名参考；这些本地制品不代表已在 Maven Central 发布。首次在新检出环境运行时，以本次真实结果为准；不要把其他源码或其他环境的报告当作本仓库的通过证据。设备、物理性能和生产场景另行验证；GitHub Actions 仅支持手动触发。

当前生产准备、版本及未验证范围见 [开发状态](docs/DEVELOPMENT_STATUS.md)。源代码以 Apache-2.0 提供，依赖的许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
