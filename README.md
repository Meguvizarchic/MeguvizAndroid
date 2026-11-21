<div align="center">
<img src="docs/brand/MeguvizAndroid.svg" width="96" height="96" alt="MeguvizAndroid">

# MeguvizAndroid

**Android 窗口、生命周期、资源与任务调度基础库**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz/meguviz-android-window) · [AI 接入与迁移技能](.agents/skills/meguviz-android/SKILL.md) · [示例](samples) · [许可证](LICENSE)

</div>

MeguvizAndroid 为窗口和 View 提供明确的生命周期及资源作用域，可将监听、资源更新与帧任务绑定到具体所有者，并在窗口关闭或 View 重新绑定时释放旧工作。

## 快速开始

在 Gradle 仓库配置中启用 `google()` 与 `mavenCentral()`。Android 工程需使用 `compileSdk 37` 或更新版本，设备最低 API 27（`meguviz-android-window` 含 Kotlin 2.4.0 元数据，Kotlin 消费方需 Kotlin 2.3 及以上版本；纯 Java 消费方无此编译门槛，仅会传递引入 `kotlin-stdlib:2.4.21`）。四个核心制品均已在 Maven Central 发布 `1.2.0`，支持单独声明版本或配合家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 对齐版本。

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle")
    // 也可直接声明版本号：implementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle:1.2.0")
}
```

```java
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters;
import io.github.jichuo1.meguviz.android.window.WindowSession;

// 在 ComponentActivity 或 AppCompatActivity 的 onCreate 中调用。
WindowSession session = LifecycleAdapters.openWindow(
        this, WindowSession.Permission.OWN_WINDOW);
```

会话跟随 Activity 启停并在销毁时自动关闭，请始终在主线程打开和管理会话。同一窗口同时只允许一个 `OWN_WINDOW` 会话；直接使用原生 Android `Activity` 时，可参考示例用 `WindowSession.open` 手动管理会话。

## 按需选择模块

下表制品均已发布至 Maven Central，使用同一 `groupId`：`io.github.jichuo1.meguviz`，当前版本 `1.2.0`。

| 制品 | 用途 |
| --- | --- |
| `meguviz-android-runtime` | 生命周期与资源作用域、有界任务队列和帧调度（不引入 AndroidX） |
| `meguviz-android-window` | 窗口会话、View 作用域、窗口内边距与 Kotlin 扩展 |
| `meguviz-android-lifecycle` | Activity、Dialog 与 Fragment View 的 AndroidX 生命周期绑定 |
| `meguviz-android-resources-loader` | 在 API 30 及以上设备动态加载额外的 Android 资源 |

## 文档与工具

- [AI 接入与迁移技能](.agents/skills/meguviz-android/SKILL.md)：包含工程盘点、模块选型、API 签名速查、存量代码迁移范式与排障清单。
- [样例工程](samples)：涵盖 XML 布局、Fragment View、Dialog 与嵌入式宿主接入。
- [开发状态](docs/DEVELOPMENT_STATUS.md) · [Central 本地构建核验](docs/release/CENTRAL_PREPARATION.md) · [性能记录](docs/performance/RUNTIME_FAST_PATHS.md)
- [GitHub Releases](https://github.com/Meguvizarchic/MeguvizAndroid/releases) 提供已归档的离线 Maven 制品。
- 源码遵循 [Apache License 2.0](LICENSE)，第三方依赖见 [许可声明](THIRD_PARTY_NOTICES.md)。
