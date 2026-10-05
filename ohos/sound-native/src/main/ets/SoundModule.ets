import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SoundPlayer, SoundState } from './SoundPlayer';

interface SoundParams { url?: string; rawfile?: string; }

/** rawfile 由宿主传入，模块只拥有当前页面的播放器。 */
export class SoundModule extends KuiklyRenderBaseModule {
  /** Kuikly 注册名，与 Kotlin 侧常量一致。 */
  static readonly MODULE_NAME: string = 'GycSound';
  private player: SoundPlayer | null = null;
  private callback: KuiklyRenderCallback | null = null;
  private destroyed: boolean = false;

  syncMode(): boolean { return false; }

  /** 页面线程异步入口；销毁后忽略请求与迟到回调，参数 JSON 属于输入边界。 */
  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) { return null; }
    if (method === 'release') {
      this.destroyed = true;
      this.callback = null;
      this.player?.release().catch(() => {});
      return null;
    }
    let args: SoundParams;
    try { args = JSON.parse(typeof params === 'string' ? params : '{}') as SoundParams; }
    catch (_) { callback?.({ status: 'failed' }); return null; }
    if (!args || (args.url !== undefined && typeof args.url !== 'string') ||
      (args.rawfile !== undefined && typeof args.rawfile !== 'string')) {
      callback?.({ status: 'failed' }); return null;
    }
    if (!this.player) {
      const context = this.controller?.getUIAbilityContext();
      if (!context || !args.rawfile || args.rawfile.trim().length === 0) {
        callback?.({ status: 'failed' }); return null;
      }
      this.player = new SoundPlayer(context.resourceManager, args.rawfile, (state: SoundState) => {
        if (state.phase === 'REMOTE_READY') { this.callback?.({ status: 'ready' }); }
        else if (state.phase === 'REMOTE_FAILED') { this.callback?.({ status: 'failed' }); }
      });
    }
    if (method === 'prepare') { this.callback = callback; this.player.prepare(args.url); }
    else if (method === 'play') { this.player.play(); }
    return null;
  }

  onDestroy(): void {
    this.destroyed = true;
    this.callback = null;
    this.player?.release().catch(() => {});
    super.onDestroy();
  }
}
