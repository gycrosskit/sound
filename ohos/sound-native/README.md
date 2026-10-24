# GYC Sound HAR

> 当前源码候选统一为 **1.0.0**：Maven（core/Kuikly）、iOS Pod 与 OHPM 使用同版；尚未发布，示例坐标须待发布后远程消费。本地验证使用显式源码/本地 HAR，Kuikly 基线为 2.29.1，真机与新版本远程消费另验。

适用版本：HAR `0.1.2`，配套 Maven `0.1.5`。完整功能与五入口限制见[功能与平台差异](https://github.com/gycrosskit/sound/blob/har-0.1.2/docs/功能与平台差异.md)；构建、固定 Release HAR 消费与 OHPM Registry 可安装性分别见[此版发布记录](https://github.com/gycrosskit/sound/releases/tag/har-0.1.2)。

原生 SoundPlayer 与 Kuikly SoundModule，宿主提供 rawfile。详见仓库根 README。
安装坐标 `@gycrosskit/sound@1.0.0`；发布状态以 OHPM 查询和独立安装验收为准。

```sh
ohpm install @gycrosskit/sound@1.0.0
```
