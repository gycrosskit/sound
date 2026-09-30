# GY CrossKit Sound

Android、iOS 和 HarmonyOS 的单个短音效播放器，支持 HTTPS 预加载、从头重播、宿主本地资源回退、准备状态和生命周期释放。音频文件、开关和业务事件由宿主提供。

## 平台与模块

| 模块 | 平台与要求 |
| --- | --- |
| `sound-core` | Android API 24+ / iOS（建议宿主 iOS 14+）；`SoundPlayer` / `SoundState` 和原生实现 |
| `sound-kuikly` | OHOS Kotlin Module，Kuikly `2.28.0-2.0.21-ohos` |
| `@gycrosskit/sound` | HarmonyOS HAR，兼容 API 22；ArkTS 播放器与 Kuikly Renderer Module |

KMP 基线为 OpenHarmony Kotlin `2.2.21-1.0.0` / JDK 17 / Gradle 8.11.1 / AGP 8.10.1，coroutines `1.10.2-1.0.0`、Ktor `3.3.3-1.1.0-04`。iOS 编译/链接需 macOS / Xcode。Core 的 OHOS 变体仅提供公共契约，实际播放需原生 HAR。HAR 使用 API 26 SDK 构建，API 22 真机兼容尚未验收。

## 安装

在项目的 `settings.gradle.kts` 中配置：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}
```

共享模块 `build.gradle.kts`：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.github.gycrosskit.sound:sound-core:0.1.1")
        }
    }
}
```

HarmonyOS 原生宿主：

```sh
ohpm install @gycrosskit/sound@0.1.0
```

插件仓库、Kuikly 依赖及注册见[接入指南](docs/接入指南.md)。Maven `0.1.1` 与 HAR `0.1.0` 分别版本化。

## 快速使用

Android 在 `androidMain` 创建播放器，音频文件放入宿主 `res/raw`：

```kotlin
import io.github.gycrosskit.sound.AndroidSoundPlayer

val sound = AndroidSoundPlayer(context, R.raw.host_sound)
sound.prepare("https://cdn.example.com/sound.wav")
sound.play()
// 页面或拥有播放器的会话退出时：
sound.release()
```

iOS 使用 `IosSoundPlayer("host_sound", "wav")`，文件放入宿主 Bundle；ArkTS 使用 `SoundPlayer(resourceManager, "host_sound.wav", onState)`，文件放入宿主 `resources/rawfile`。详细示例见接入指南。

`prepare()` 异步准备远端且不会自动播放。`play()` 从头重播，远端未就绪或播放失败时使用本地资源；本地不可用则静默结束。实例只播放一个短音效，不提供混音或队列。

`state` 只表示 `LOCAL_ONLY / PREPARING / REMOTE_READY / REMOTE_FAILED / RELEASED`，不表示正在播放。所有 Kotlin 原生入口在主线程调用，Kuikly 使用页面线程。`release()` 永久关闭且可重复调用，再次使用需新实例；ArkTS 的释放包含异步资源清理。

iOS 远端完整下载上限 2 MiB；Android/OHOS 使用原生网络预缓冲且无库内字节上限，不保证离线播放。宿主限制文件大小、时长及 HTTPS 重定向链，iOS 音频会话由宿主管理。

## 文档与支持

- [接入指南](docs/接入指南.md)：资源、Swift 导出、Kuikly、状态及超时边界。
- [开发与验证](docs/开发与验证.md)、[历史验证记录](VERIFICATION.md)：构建和独立消费范围。
- [GitHub Releases](https://github.com/gycrosskit/sound/releases)：Maven / HAR 版本和归档。
- [GitHub Issues](https://github.com/gycrosskit/sound/issues)：提供平台、版本、音频格式和最小复现。

现有记录覆盖远程 Maven / OHPM 产物编译、iOS Simulator 链接、Swift typecheck 和行为替身测试；真机播放、音频会话及 API 22 设备兼容仍待宿主验收。

自有源码使用 [Apache-2.0](LICENSE)，第三方依赖遵循各自许可。
