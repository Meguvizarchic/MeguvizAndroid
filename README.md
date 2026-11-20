<div align="center">
<img src="docs/brand/MeguvizAndroid.svg" width="96" height="96" alt="MeguvizAndroid">

# MeguvizAndroid

**Android 窗口、生命周期、资源与任务调度基础库**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz/meguviz-android-window) · [示例](samples) · [许可证](LICENSE)

</div>

MeguvizAndroid 为窗口和 View 提供明确的生命周期及资源作用域。可以把监听、资源更新和帧任务绑定到具体所有者，并在窗口关闭或 View 重新绑定时释放旧工作。

## 快速开始

在 Gradle 的仓库配置中启用 `google()` 和 `mavenCentral()`。Android 项目使用 `compileSdk` 37 或更新版本，设备最低 API 27。以下示例通过 AndroidX 将窗口会话绑定到 Activity 生命周期。

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle:1.2.0")
}
```

```java
import io.github.jichuo1.meguviz.android.lifecycle.LifecycleAdapters;
import io.github.jichuo1.meguviz.android.window.WindowSession;

// 在 ComponentActivity 或 AppCompatActivity 的 onCreate 中调用。
WindowSession session = LifecycleAdapters.openWindow(
        this, WindowSession.Permission.OWN_WINDOW);
```

会话跟随 Activity 启停，销毁时自动关闭。请在主线程打开和管理会话。同一窗口同时只允许一个 `OWN_WINDOW` 会话。直接使用 Android `Activity` 时，可参考示例用 `WindowSession.open` 手动管理会话。

## 按需选择模块

下表制品使用同一 groupId `io.github.jichuo1.meguviz`。

| 制品 | 用途 |
| --- | --- |
| `meguviz-android-runtime` | 生命周期与资源作用域、任务队列和帧调度 |
| `meguviz-android-window` | 窗口会话、View 作用域与窗口内边距 |
| `meguviz-android-lifecycle` | Activity 和 Fragment View 的 AndroidX 生命周期绑定 |
| `meguviz-android-resources-loader` | 动态加载额外的 Android 资源 |

## 文档

- [样例工程](samples)包括 XML 布局、Fragment View、Dialog 和自定义界面接入。
- [GitHub Releases](https://github.com/Meguvizarchic/MeguvizAndroid/releases)提供已归档的离线 Maven 制品。
- 源码遵循 [Apache License 2.0](LICENSE)，第三方依赖见 [许可声明](THIRD_PARTY_NOTICES.md)。
