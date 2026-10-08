# 更新记录

## HAR 0.1.2（2026-10-08）

- OHOS 远端就绪判断复用 SoundState.phase，删除重复状态；公开 API 与生命周期合同不变。
- CI 缓存完整 Native 工具链，增加仅 main 可执行的预热；保留原平台 required checks。
- Maven 0.1.5 沿用，不重复发布 Android/iOS 制品。

## 0.1.5（2026-10-08）

- iOS AVPlayer流式远程准备；补A/i/O重入、准备代次与终态释放，保留本地回退。
- 更新功能、测试覆盖与平台差异文档；设备业务验收范围保持明确。

## 0.1.3（候选，未发布）

- iOS prepare/play/release 自动串行切回 Main；后台 release 以 RELEASED 状态确认执行完成。
- 调度独立于预载 scope，释放后排队请求不能复活实例；HAR 源码与版本保持 0.1.0。

## 0.1.0（本地开发，未发布）

- 抽离 Android/iOS/OHOS 的短音效预加载、重播与宿主本地资源回退。
- 提供准备状态、永久释放与迟到回调取消，保留各平台超时和大小边界。
