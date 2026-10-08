# 更新记录

## 0.1.2（2026-10-08）

- 远端可播放判断复用 SoundState.phase，删除重复 remoteReady 状态；保留 generation、重播、回退和释放保护。
- Kotlin/Maven 继续使用 0.1.5；公开 API 不变。

## 0.1.1（2026-10-08）

- iOS AVPlayer流式远程准备；补A/i/O重入、准备代次与终态释放，保留本地回退。

## 0.1.0

- 抽离短音效 HTTPS 预缓冲、重播、宿主 rawfile 回退和 Kuikly 接入。
