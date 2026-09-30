# GYC Sound

Android、iOS 和 HarmonyOS 的单个短音效播放器：HTTPS 预加载、从头重播、宿主本地资源回退、准备状态和生命周期释放。音效开关、业务事件去重、奖励触发及具体音频文件均由宿主负责。组件没有内置 `reward_coin.wav`、业务接口或账号。

Maven `0.1.1` 已发布：[GitHub Release](https://github.com/gycrosskit/sound/releases/tag/0.1.1)，JitPack 状态 `ok`，独立消费的Android、iOS arm64/x64 编译、iOS Simulator Framework 链接、OHOS 编译通过。 HAR `0.1.0` 已通过 OHPM 审核并上架，正式 Registry 精确版本安装和独立 assembleHar 已通过；GitHub Release HAR 已远程下载、SHA-256 校验、安装到独立工程并 assembleHar 成功。OHPM 不支持此 HAR URL 直接依赖，验收使用下载缓存的 file 依赖，另已使用正式 Registry 版本重新验收安装与编译。

## 平台和 API

| 模块 | 平台 / 能力 |
| --- | --- |
| `sound-core` | KMP 公共 `SoundPlayer` / `SoundState`，Android `AndroidSoundPlayer`、iOS `IosSoundPlayer`；OHOS 仅共用契约 |
| `sound-kuikly` | OHOS 的 Kuikly Kotlin `SoundModule` |
| `ohos/sound-native` | 原生 ArkTS `SoundPlayer` 与 Kuikly `SoundModule`，HAR 包名 `@gycrosskit/sound` |
| `verification-consumer` | 无 project 依赖的独立 Maven 消费工程 |
| `verification-har` | 用打包后的本地 HAR 文件安装的独立 ArkTS 消费工程 |

Android 最低 API 24。iOS 使用 AVAudioPlayer，建议宿主最低 iOS 14。HAR 本轮使用 HarmonyOS API 26 SDK 构建，`compatibleSdkVersion` 为 API 22；API 22 真机兼容性尚未验收。许可证 Apache-2.0。抽离自接入项目已有 `RewardAudioPlatform`、Android/iOS 实现及 OHOS RewardAudioModule，保留原生播放器与取消机制；第三方依赖各自遵循原许可。

```kotlin
interface SoundPlayer {
    val state: StateFlow<SoundState>
    fun prepare(remoteUrl: String?)
    fun play()
    fun release()
}
```

状态为 `LOCAL_ONLY / PREPARING / REMOTE_READY / REMOTE_FAILED / RELEASED`，只描述远端准备和实例生命周期，不表示当前正在播放，也不证明本地资源可用。空地址清除远端；非 HTTPS、无 Host、包含空白或用户凭据的地址被拒绝。相同 URL 在准备中或已就绪时去重；失败后再次 `prepare` 可以重试；更换 URL 会取消旧准备并屏蔽迟到回调。

`play()` 在远端准备好时从头重播，准备中或远端失败时使用本地资源；远端实际播放失败也回退。准备完成本身不会自动播放。播放器使用单音效语义，重播替换当前本地声音，不提供混音、队列、持久磁盘缓存或业务事件去重。宿主应提供短小且系统可解码的音频（如 PCM WAV），并验证自己的设备与编码组合。

## Gradle 接入和版本

Maven 坐标如下；使用 JitPack。远程可用性以 [VERIFICATION.md](VERIFICATION.md) 的发布和独立消费结果为准：

```kotlin
repositories {
    maven("https://jitpack.io") {
        content { includeGroup("com.github.gycrosskit.sound") }
    }
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    google()
    mavenCentral()
    maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
}
commonMain.dependencies {
    implementation("com.github.gycrosskit.sound:sound-core:0.1.1")
}
ohosArm64Main.dependencies {
    implementation("com.github.gycrosskit.sound:sound-kuikly:0.1.1")
}
```

工具链 Kotlin `2.2.21-1.0.0`、coroutines `1.10.2-1.0.0`、Ktor `3.3.3-1.1.0-04`，Kuikly `2.28.0-2.0.21-ohos`；包含 OHOS target 的消费方需要匹配的厂商 Kotlin 工具链。只有 iOS 使用 Ktor Darwin HTTP 引擎；其他平台仅共用 Ktor URL 校验。

## Android

```kotlin
val sound = AndroidSoundPlayer(context, R.raw.host_sound)
sound.prepare("https://cdn.example.com/sound.wav")
sound.play()
// 页面或拥有音效的会话退出：
sound.release()
```

宿主提供有效的 `res/raw` 资源 ID。AAR Manifest 声明 `android.permission.INTERNET`，只保留 Application Context。远端使用 MediaPlayer 与 `USAGE_ASSISTANCE_SONIFICATION`，本地使用 MediaPlayer.create 默认音频属性；本地资源不可用时停止该次播放，不抛给业务。所有入口在主线程调用。

## iOS

```kotlin
val sound = IosSoundPlayer("host_sound", "wav")
// 第三个参数可传宿主自己的 NSBundle，默认 mainBundle。
sound.prepare("https://cdn.example.com/sound.wav")
sound.play()
sound.release()
```

宿主将文件加入对应 Bundle。Swift 直接调用时，将 `sound-core` 作为 `api` 依赖，并在宿主的 iOS `binaries.framework` 中 `export("com.github.gycrosskit.sound:sound-core:0.1.1")`；状态流观察可留在 Kotlin 层转给 Swift。不需要额外 Swift Package 或宿主 Swift 播放桥。组件不修改 AVAudioSession，全局音频会话、静音键、后台播放和与其他音频的交互由宿主决定。所有入口在主线程调用。本地文件缺失或不能播放时静默结束该次播放。

## HarmonyOS 原生与 Kuikly

独立 `verification-har` 已通过文件 HAR 安装后导入 `SoundPlayer` / `SoundState` / Kuikly `SoundModule`，并完成 `assembleHar`；消费模块只声明 `@gycrosskit/sound`，Kuikly render 由其传递依赖安装。可执行 `bash scripts/verify-har.sh` 复现（须先构建 SoundNative.har）。本地验证消费者的 file 依赖会触发打包警告，该消费者产物仅用于本地检查。

OHPM 安装坐标（发布状态以查询和独立安装验证为准）：

```shell
ohpm install @gycrosskit/sound@0.1.0
```

```typescript
import { SoundPlayer } from '@gycrosskit/sound';
const sound = new SoundPlayer(context.resourceManager, 'host_sound.wav', state => {
  // state.phase / state.remoteUrl
});
sound.prepare('https://cdn.example.com/sound.wav');
sound.play();
await sound.release();
```

宿主在自己的 `resources/rawfile` 放音频，并声明 `ohos.permission.INTERNET`。本地 rawfile 描述符按播放器串行释放后关闭；快速重播或销毁会取消本地准备。原生 `release()` 返回本地资源释放 Promise，远端 AVPlayer 异步释放已发起；状态立即变为 `RELEASED`。AVPlayer 使用 `STREAM_USAGE_GAME`。

Kuikly ArkTS 侧注册 HAR 导出的 `SoundModule.MODULE_NAME`（`GycSound`），Kotlin 侧注册 `io.github.gycrosskit.sound.kuikly.SoundModule("host_sound.wav")`，并把同一实例作为 `SoundPlayer` 使用。桥传递 URL、rawfile 名称和准备结果；业务事件由宿主触发。Kotlin 入口在 Kuikly 页面线程串行执行。宿主退出时显式调用 Kotlin `release()`，清除常驻回调；ArkTS `onDestroy()` 也关闭播放器。

## 生命周期、超时和大小边界

`release()` 永久关闭实例且可重复调用；之后 `prepare` / `play` 均无效。重新进入页面或会话应创建新实例。关闭时停止播放、取消计时器和下载任务，替换或关闭后旧回调不会修改新状态。

| 平台 | 准备和大小边界 |
| --- | --- |
| Android | MediaPlayer 原生网络预缓冲；5 秒未 prepared 即失败。没有完整下载保证或库内字节上限；播放仍可能依赖网络。 |
| iOS | Darwin 完整下载到内存再解码，连接/请求/读超时均为 5 秒。最大 2 MiB；流式最多读取 2 MiB + 1 字节判断超限，不信任缺失或偏小的 Content-Length。空体、HTTP 非成功、超限或解码失败均回退。 |
| OHOS | AVPlayer 原生网络预缓冲；5 秒未 prepared 即失败。本地资源准备也有 5 秒取消超时。没有完整下载保证或库内字节上限；播放仍可能依赖网络。 |

HTTPS 准入针对宿主传入 URL；重定向与 TLS 由各平台网络栈处理。宿主应使用最终 HTTPS CDN 地址，确保重定向链同样使用 HTTPS。5 秒是准备/下载期限，不是整个播放过程的网络断连期限。Android/OHOS 需由宿主 CDN 限制音频大小与时长；组件不提供三端离线播放保证。

## 本地验证和发布准备

```shell
# 配置 local.properties 的 Android sdk.dir 后：
bash scripts/verify.sh
# HAR（SDK 路径按本机调整）：
cd ohos
DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p module=SoundNative@default -p product=default assembleHar --no-daemon
```

运行 HAR 构建前先 `ohpm install --all`；将 DevEco 的 `tools/node/bin` 与 `tools/ohpm/bin` 加入 PATH。`verification/ohos-behavior.cjs` 使用 SDK TypeScript 转译器运行真实 ArkTS 源码与可控系统播放器替身，支持 `SOUND_TYPESCRIPT` 覆盖转译器路径。这是行为测试，不能代替真机音频播放验收。

Maven 全变体输出到 `build/maven`，独立消费工程默认使用 JitPack；本地验证显式传 `-PsoundMavenRepo="$PWD/build/maven"`，通过 Maven 坐标解析 Android/iOS/OHOS 产物。`scripts/verify.sh` 使用 `--max-workers=1` 限制编译资源。实际验证结果与命令见 [VERIFICATION.md](VERIFICATION.md)。

本地归档准备命令（仅生成本地文件）：

```shell
COPYFILE_DISABLE=1 tar -czf build/sound-maven.tar.gz -C build/maven .
shasum -a 256 build/sound-maven.tar.gz
```

`jitpack-install.sh` 和 `jitpack-metadata.py` 从 GY CrossKit `.github/templates` 同步；后者只在 JitPack 安装归档后移除已知会被重写到缺失文件的 Sources/Metadata 变体，平台 API/runtime 变体保留。

`jitpack.yml` / `jitpack-install.sh` 保留 macOS 预构建 Maven 归档安装入口。正式发布前，在匹配标签下发布 `sound-maven.tar.gz` 并把不可变标签与归档 SHA-256 写入 `release-checksums.txt`；没有校验值时安装脚本直接失败。还需核验远程 JitPack metadata/变体、干净远程 Maven 消费、ohpm prepublish 与上架后安装。远程 Maven 与 GitHub Release HAR 消费通过，OHPM Registry 安装与独立编译已通过。
