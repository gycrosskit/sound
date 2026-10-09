import Foundation
import UIKit
import OpenKuiklyIOSRender
@preconcurrency import GYCSound

private final class Bridge: NSObject, TDFBridgeDelegate {
    weak var rootView: UIView?
    var pageName: String? { nil }
    var bridgeType: TDF_BRIDGE_TYPE { .KUIKLY }
    var hippyBridge: AnyObject? { nil }
    func send(withEvent event: String, data: [AnyHashable: Any]?) {}
    func module(withName moduleName: String) -> Any? { nil }
    func view(withTag tag: Int) -> UIView? { nil }
    func performCallback(_ callbackId: NSNumber, params: Any) {}
}

private final class Box: @unchecked Sendable {
    var value: GycSoundModule?
    init(_ value: GycSoundModule) { self.value = value }
}

@MainActor
private func runChecks() {
    let rootView = UIView()
    func attached() -> GycSoundModule {
        let receiver = GycSoundModule()
        let bridge = Bridge()
        bridge.rootView = rootView
        receiver.delegate = bridge
        receiver.setValue(rootView, forKey: "hr_rootView")
        return receiver
    }
    func drainContext() {
        let done = DispatchSemaphore(value: 0)
        KuiklyRenderThreadManager.performOnContextQueue { done.signal() }
        precondition(done.wait(timeout: .now() + 2) == .success)
    }
    var created = 0
    var called = 0
    var disposed = 0
    var replies: [(String) -> Void] = []
    var delivered = 0
    let callback: KuiklyRenderCallback = { _ in
        precondition(KuiklyRenderThreadManager.isContextQueue())
        delivered += 1
    }
    GycSoundModule.register(makeHandler: {
        created += 1
        return GycSoundHandler(call: { _, _, reply in
            replies.append(reply)
            precondition(Thread.isMainThread)
            called += 1
        }, dispose: {
            precondition(Thread.isMainThread)
            disposed += 1
        })
    })
    let module = attached()
    _ = module.hrv_call(withMethod: "test", params: "{}", callback: callback)
    replies[0]("normal")
    drainContext()
    precondition(delivered == 1, "live reply must return on SDK Context")
    let contextBlocked = DispatchSemaphore(value: 0)
    let resumeContext = DispatchSemaphore(value: 0)
    KuiklyRenderThreadManager.performOnContextQueue {
        contextBlocked.signal()
        precondition(resumeContext.wait(timeout: .now() + 2) == .success)
    }
    precondition(contextBlocked.wait(timeout: .now() + 2) == .success)
    replies[0]("queued before invalidate")
    module.invalidate()
    resumeContext.signal()
    drainContext()
    module.invalidate()
    _ = module.hrv_call(withMethod: "test", params: "{}", callback: callback)
    replies[0]("late")
    precondition(delivered == 1)
    precondition(created == 1 && called == 1 && disposed == 1, "invalidate must be permanent and idempotent")

    weak var released: GycSoundModule?
    autoreleasepool {
        let natural = attached()
        released = natural
        _ = natural.hrv_call(withMethod: "test", params: "{}", callback: callback)
        natural.setValue(nil, forKey: "hr_rootView")
        replies[1]("root detached")
        drainContext()
        precondition(delivered == 1, "detached Renderer cannot receive results")
    }
    precondition(released == nil && disposed == 2, "real SDK dealloc must dispose without retaining self")

    let queued = attached()
    let queuedWork = DispatchGroup()
    queuedWork.enter()
    DispatchQueue.global().async {
        _ = queued.hrv_call(withMethod: "test", params: "{}", callback: nil)
        queuedWork.leave()
    }
    precondition(queuedWork.wait(timeout: .now() + 2) == .success)
    queued.invalidate()
    precondition(created == 2, "background work must wait for Main")

    let box = Box(attached())
    _ = box.value?.hrv_call(withMethod: "test", params: "{}", callback: nil)
    weak var releasedOffMain = box.value
    let backgroundRelease = DispatchGroup()
    backgroundRelease.enter()
    DispatchQueue.global().async {
        autoreleasepool {
            box.value = nil
        }
        backgroundRelease.leave()
    }
    precondition(backgroundRelease.wait(timeout: .now() + 2) == .success)
    precondition(releasedOffMain == nil, "queued cleanup must not retain module")
    DispatchQueue.main.async {
        precondition(created == 3 && called == 3 && disposed == 3, "queued work must be dropped and detached resources released once")
        print("KUIKLY_MODULE_LIFECYCLE_PASS")
        fflush(stdout)
        exit(0)
    }
}

@main
private final class CheckApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Task { @MainActor in runChecks() }
        return true
    }
}
