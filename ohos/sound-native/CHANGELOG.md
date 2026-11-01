# 更新记录

## Native/HAR 1.0.1

- iOS Git Pod 使用 `native-1.0.1`，HAR/OHPM 使用 `har-1.0.1`；Maven 保留 `1.0.0`，不重发未修改的 KMP 产物。
- Renderer 版本由宿主选择：Pod 声明 `>= 2.29.1, < 3.0`，HAR 声明 `^2.29.1`；范围不代替实际消费验证。
- 旧 Tag/归档/摘要保持不变；发布与精确公开消费状态见对应 Release，设备验收单独记录。

## 1.0.0（未发布）

- Maven、iOS Pod 与 OHPM 统一版本为 1.0.0；同仓模块使用同版，Kuikly 配套保持 2.29.1。
- 本地源码/产物消费与设备、远程发布验收分别记录；不覆盖旧标签或修改历史发布字节。

## 0.1.2（2026-10-08）

- 远端可播放判断复用 SoundState.phase，删除重复 remoteReady 状态；保留 generation、重播、回退和释放保护。
- Kotlin/Maven 继续使用 0.1.5；公开 API 不变。

## 0.1.1（2026-10-08）

- iOS AVPlayer流式远程准备；补A/i/O重入、准备代次与终态释放，保留本地回退。

## 0.1.0

- 抽离短音效 HTTPS 预缓冲、重播、宿主 rawfile 回退和 Kuikly 接入。
