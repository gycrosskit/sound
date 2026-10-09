import SoundConsumer
import GYCSound

// 使用真实 Maven consumer framework 的生成 API；宿主只装配方法引用。
@MainActor
func registerSoundModuleFromShared() {
    GycSoundModule.register {
        let handler = IosSoundModuleHandler()
        return GycSoundHandler(call: handler.call, dispose: handler.dispose)
    }
}
