import Foundation
import SoundConsumer

// 仅校验导出的构造函数和协议，无运行时音频或网络操作。
func soundFromPublishedFramework() -> SoundPlayer {
    let sound = IosSoundPlayer(fallbackResourceName: "host_sound", fallbackResourceExtension: "wav", bundle: Bundle.main)
    sound.prepare(remoteUrl: nil)
    sound.play()
    sound.release()
    return sound
}
