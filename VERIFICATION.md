# 本地验证记录

日期：2026-09-30。所有源码、消费工程与产物位于本组件独占目录；Gradle 使用 `--max-workers=1`，没有提交、推送、Tag、远程建仓库或外部发布。

## 已完成

| 检查 | 结果 |
| --- | --- |
| Android Debug Kotlin 编译、公共状态/URL 单测 | 成功；4 个测试，0 失败 |
| iOS arm64 / x64 Kotlin 编译 | 成功 |
| iOS Simulator arm64 测试二进制链接和执行 | 成功；公共 4 个 + iOS 6 个测试，0 失败 |
| OHOS arm64 core / Kuikly KLIB 编译 | 成功 |
| HarmonyOS HAR `assembleHar` | 成功；API 26 SDK，compatible API 22；产物 `ohos/sound-native/build/default/outputs/default/SoundNative.har` |
| 独立打包后 HAR 消费 | `verification-har` 使用 file `SoundNative.har` 安装成功；只声明 sound 包，传递安装 Kuikly render 2.28.0；import SoundPlayer/SoundState/SoundModule 后 `assembleHar` 成功，30 个任务执行，0 错误 |
| 真实 ArkTS 源码 + 系统播放器替身行为检查 | 成功：HTTPS/Host/凭据准入、替换预加载、URL 去重、重播、本地回退、描述符关闭、准备超时、永久释放、迟到回调 |
| 本地 Maven 全平台产物生成 | 成功；输出 `build/maven`；8 个 module metadata，含 Android AAR、iOS 3 个目标和 OHOS core/Kuikly |
| Maven metadata 引用与 SHA-256 检查 | 成功；检查 available-at、component redirect、文件存在、长度、校验值及目标变体 |
| 独立 Maven consumer | Android Debug、iOS arm64/x64、OHOS Kotlin 编译与 Simulator arm64 Framework 链接成功；无 project/source 依赖 |
| 导出 Framework 的 Swift API | `swiftc -typecheck` 成功，构造 `IosSoundPlayer` 并调用 `SoundPlayer` 协议；无运行时播放 |
| Git/text 检查 | `git diff --check` 与未跟踪文本的 `git diff --no-index --check` 成功；生成的 BuildProfile、构建产物和本机路径均忽略 |
| JitPack 模板 | 两个文件与 GY CrossKit `.github/templates` 副本一致；Shell 语法通过；空校验值使安装脚本在下载前按预期拒绝未发布版本 |

iOS 下载单测使用 MockEngine：2 MiB 边界、缺失 Content-Length、空响应/HTTP 失败、声明超限、持续超限流、低报 Content-Length；还检查永久释放与重复 release。公共测试覆盖空值、HTTP/无 Host/空白/凭据拒绝、准备与就绪去重、失败重试和释放后不接受准备。

## 实际命令

```shell
bash gradlew :sound-core:compileDebugKotlinAndroid :sound-core:testDebugUnitTest \
  :sound-core:compileKotlinIosArm64 :sound-core:compileKotlinIosX64 \
  :sound-core:iosSimulatorArm64Test :sound-core:compileKotlinOhosArm64 \
  :sound-kuikly:compileKotlinOhosArm64 publishAllPublicationsToStagingRepository \
  --max-workers=1 --offline --console=plain
# 前述源码/测试成功，离线 Maven metadata 缓存不足后在线补齐：
bash gradlew publishAllPublicationsToStagingRepository --max-workers=1 --console=plain
python3 verification/check-maven.py
bash gradlew -p verification-consumer compileDebugKotlinAndroid \
  compileKotlinIosArm64 compileKotlinIosX64 linkDebugFrameworkIosSimulatorArm64 \
  compileKotlinOhosArm64 --max-workers=1 --offline --console=plain
node verification/ohos-behavior.cjs
xcrun swiftc -typecheck -target arm64-apple-ios14.0-simulator \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework verification/SwiftConsumer.swift
# 在 ohos/，DevEco node/ohpm 已加入 PATH：
ohpm install --all
DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p module=SoundNative@default -p product=default assembleHar --no-daemon
```

HAR 消费验证命令（在 `verification-har/`，同上 SDK 与 PATH）：

```shell
ohpm install --all
DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p module=SoundConsumerNative@default -p product=default assembleHar --no-daemon
```

输入为打包后的 `SoundNative.har` 文件，无项目/源码目录依赖。安装 lockfile 的 resolved 指向该文件，编译日志的导入源位于消费者 `oh_modules/.ohpm/@gycrosskit+sound@...`。消费源为 `verification-har/consumer/src/main/ets/ConsumeSound.ets`，输出 `verification-har/consumer/build/default/outputs/default/SoundConsumerNative.har`。只做编译和打包，没有执行播放器。消费者因本地 file 依赖有打包警告，此消费者 HAR 不用于远程发布。

日志在忽略的 `build/`：`final-maven.log`、`publish-online.log`、`consumer.log`、`har-final.log`、`ohos-behavior.log`、`har-consumer-install.log`、`har-consumer.log`。JUnit XML 位于 `sound-core/build/test-results`。

## 已解决的构建问题

- 初次离线构建缺 Ktor 适配仓库筛选；添加 `io.ktor` 厂商源匹配后通过。全平台 Maven metadata 生成另缺 3 个已公开的 iOS Simulator metadata JAR，在线下载补齐后本地产物生成成功。
- HAR 的旧模板缺 `AppScope`，且本机安装的是 API 26 SDK；补齐独立 HAR 工程、使用对应 SDK 目录与 API 26 配置。ArkTS 禁止构造函数声明字段，改为显式字段赋值后成功。
- 消费样例首次各源集均叫 `Consumer.kt`，JVM 顶层文件名冲突；分别使用 Common/Android/Ios/Ohos 文件名后通过。
- HAR 仍有 Kuikly 依赖的 strict typing 警告、系统媒体能力和异常提示，以及 HAR 无 signingConfig 提示；没有编译错误，未将它们等同于设备支持保证。

## 未执行

- Android/iOS/HarmonyOS 真机播放、编码兼容、静音/音频焦点、网络断连、API 22 真机兼容性和连续重播压力验收。
- 真实 CDN 下载与 HTTPS 重定向链验证；iOS HTTP 行为测试使用可控响应。
- iOS 真机 Framework 链接、签名或归档，Android 宿主应用安装，OHOS HAP 签名与安装。
- 远程 GitHub/JitPack/ohpm 发布与远程消费，ohpm prepublish。`0.1.0` 仅为本地开发版本；空 `release-checksums.txt` 没有发布校验值。

Android/OHOS 的 prepared 只是原生预缓冲，大小由宿主 CDN 控制；iOS 完整内存下载最大 2 MiB。三端均不承诺持久离线缓存。资源文件与业务触发仍由宿主提供，原应用未接入该组件。


## 本次远程发布候选验证

工作目录为本聊天独占 Worktree `codex/sound-remote-release`，原始源码初始提交 `44bd582` 已经用户授权。没有修改音频逻辑；候选为 0.1.0。

- 重新执行 Android 4 个单测、iOS Simulator 10 个单测、Android/iOS/OHOS 编译、OHOS 实际 ArkTS mock 行为测试和全 Maven staging，全部通过。
- 8 个 Module Metadata / 20 个产物文件引用存在、大小/SHA256 检查通过。
- 独立正式 group staging consumer：Android、iOS arm64/x64、OHOS 编译和 iOS Simulator Framework 链接通过（串行 worker1/1GB/no-daemon）。消费者默认 JitPack，本地使用 soundMavenRepo 显式属性。
- HAR 最终 assembleHar 与独立打包 HAR 消费成功，ohpm prepublish 成功。
- 首次独立 consumer 缺 sdk.dir，补忽略的本机配置后通过；资源拥挤的排队构建由本任务取消并改为串行验证。
- 产物位于 build/release，SHA256SUMS 与 release-checksums.txt 可核验。远程发布与远程消费尚待完成。
- 真机音效、API22兼容、CDN和业务宿主接入仍未验收；不新增奖励触发业务。

## 2026-09-30 远程发布修正

Maven 候选改为 0.1.1，组件逻辑不变。JitPack Linux 实际错误为将 macOS AppleDouble `._*.module` 读取为 JSON。`release-pack.py` 用 Python tarfile 打包当前版本，排除 AppleDouble；归档逐项 JSON 与文件引用校验通过。旧 Release/标签不覆盖。重新发布全部声明平台产物成功，远程消费继续验证。

HAR 的 OHPM 版本保持原版本；Registry 要求的作者 URL、仓库 URL 与安装命令已补齐（如适用）。提交已被 Registry 接受，审核中；尚不能称为上架或远程安装成功。
