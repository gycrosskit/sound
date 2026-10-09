import Foundation
import OpenKuiklyIOSRender
import UIKit

/// 宿主只装配已有 Shared framework 导出 handler 的方法引用。
public struct GycSoundHandler {
    let call: (String, String, @escaping (String) -> Void) -> Void
    let dispose: () -> Void

    public init(call: @escaping (String, String, @escaping (String) -> Void) -> Void,
                dispose: @escaping () -> Void) {
        self.call = call
        self.dispose = dispose
    }
}

@objc(GycSound)
public final class GycSoundModule: KRBaseModule {
    private static var makeHandler: (() -> GycSoundHandler)?
    private let lifecycleLock = NSRecursiveLock()
    private var invalidated = false
    private var handler: GycSoundHandler?

    /// 启动时在 Main 注册一次工厂；每个 Renderer 由真实 SDK 创建独占 Module。
    @MainActor
    public static func register(makeHandler: @escaping () -> GycSoundHandler) {
        self.makeHandler = makeHandler
        precondition(NSClassFromString("GycSound") == GycSoundModule.self)
    }

    public override func hrv_call(withMethod method: String, params: Any?, callback: KuiklyRenderCallback?) -> Any? {
        let work = { [weak self] in
            guard let self else { return }
            self.lifecycleLock.lock()
            defer { self.lifecycleLock.unlock() }
            guard !self.invalidated else { return }
            MainActor.assumeIsolated {
                if self.handler == nil {
                    let candidate = Self.makeHandler?()
                    if self.invalidated { candidate?.dispose(); return }
                    self.handler = candidate
                }
                guard let handler = self.handler else {
                    self.deliver("{\"status\":\"failed\"}", callback: callback)
                    return
                }
                handler.call(method, params as? String ?? "") { [weak self] result in self?.deliver(result, callback: callback) }
            }
        }
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
        return nil
    }

    private var isActive: Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return !invalidated
    }

    private func deliver(_ result: String, callback: KuiklyRenderCallback?) {
        guard isActive else { return }
        // Context 排队后再次检查，销毁不能把旧回执交给下一 Renderer。
        KuiklyRenderThreadManager.performOnContextQueue( { [weak self] in
            guard let self, isActive, hr_rootView != nil else { return }
            callback?(result)
        })
    }

    public override func invalidate() {
        lifecycleLock.lock()
        guard !invalidated else { lifecycleLock.unlock(); return }
        invalidated = true
        let closing = handler
        handler = nil
        lifecycleLock.unlock()
        // SDK dealloc 也调用 invalidate；只捕获资源，不能在析构期间重新 retain self。
        let cleanup: () -> Void = { MainActor.assumeIsolated {
            closing?.dispose()
        } }
        // SDK Context 可能正被 Main 等待，不同步等待 Main。
        if Thread.isMainThread { cleanup() } else { DispatchQueue.main.async(execute: cleanup) }
        super.invalidate()
    }
}
