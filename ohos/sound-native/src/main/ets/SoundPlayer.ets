import { media } from '@kit.MediaKit';
import { url as urlUtils } from '@kit.ArkTS';
import { audio } from '@kit.AudioKit';
import { resourceManager } from '@kit.LocalizationKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

export interface SoundState { phase: string; remoteUrl: string; }

/** AVPlayer prepared 表示播放器已预缓冲可起播，不代表完整文件已离线缓存。 */
export class SoundPlayer {
  private remote: media.AVPlayer | null = null;
  private local: media.AVPlayer | null = null;
  private remoteGeneration: number = 0;
  private localGeneration: number = 0;
  private remoteReady: boolean = false;
  private remotePlaying: boolean = false;
  private localResources: resourceManager.ResourceManager | null = null;
  private localTask: Promise<void> = Promise.resolve();
  private cancelLocalPreparation: (() => void) | null = null;
  private destroyed: boolean = false;
  private prepareTimer: number = -1;

  private state: SoundState = { phase: 'LOCAL_ONLY', remoteUrl: '' };

  private readonly resources: resourceManager.ResourceManager;
  private readonly fallbackRawFile: string;
  private readonly onState: (state: SoundState) => void;

  constructor(resources: resourceManager.ResourceManager, fallbackRawFile: string,
    onState: (state: SoundState) => void = () => {}) {
    this.resources = resources;
    this.fallbackRawFile = fallbackRawFile;
    this.onState = onState;
    if (fallbackRawFile.trim().length === 0) { throw new Error('A host rawfile is required'); }
  }

  getState(): SoundState { return { phase: this.state.phase, remoteUrl: this.state.remoteUrl }; }

  private updateState(phase: string, url: string): void {
    this.state = { phase: phase, remoteUrl: url };
    this.onState(this.getState());
  }

  prepare(remoteUrl?: string): void {
    if (this.destroyed) { return; }
    const url = remoteUrl?.trim() ?? '';
    if (url === this.state.remoteUrl &&
      (this.state.phase === 'PREPARING' || this.state.phase === 'REMOTE_READY')) { return; }
    this.releaseRemote();
    if (url.length === 0) { this.updateState('LOCAL_ONLY', ''); return; }
    try {
      const parsed = urlUtils.URL.parseURL(url);
      if (!/^https:\/\/[^/?#@\s]+(?:[/?#]|$)/i.test(url) || /\s/.test(url) ||
        parsed.protocol !== 'https:' || parsed.hostname.length === 0 ||
        parsed.username.length > 0 || parsed.password.length > 0) { throw new Error('Invalid sound URL'); }
    } catch (_) { this.updateState('REMOTE_FAILED', url); return; }
    this.updateState('PREPARING', url);
    const generation = this.remoteGeneration;
    this.prepareTimer = setTimeout(() => this.failRemote(generation), 5000);
    this.prepareRemote(url, generation).catch(() => this.failRemote(generation));
  }

  private async prepareRemote(url: string, generation: number): Promise<void> {
    const player = await media.createAVPlayer();
    if (this.destroyed || generation !== this.remoteGeneration) { await player.release(); return; }
    this.remote = player;
    player.on('error', () => this.failRemote(generation));
    player.on('stateChange', (state: media.AVPlayerState) => {
      if (generation !== this.remoteGeneration || this.destroyed) { return; }
      if (state === 'initialized') {
        try {
          player.audioRendererInfo = { usage: audio.StreamUsage.STREAM_USAGE_GAME, rendererFlags: 0 };
          player.prepare().catch(() => this.failRemote(generation));
        } catch (_) { this.failRemote(generation); }
      } else if (state === 'prepared') {
        this.clearPrepareTimer();
        this.remoteReady = true;
        this.updateState('REMOTE_READY', url);
      } else if (state === 'completed') {
        this.remotePlaying = false;
      }
    });
    player.url = url;
  }

  play(): void {
    if (this.destroyed) { return; }
    const player = this.remote;
    if (!this.remoteReady || !player) { this.playLocal(); return; }
    const generation = this.remoteGeneration;
    const playback = ++this.localGeneration;
    this.cancelLocalPreparation?.();
    this.cancelLocalPreparation = null;
    this.localTask = this.localTask.then(async () => {
      await this.releaseLocal();
      if (this.destroyed || generation !== this.remoteGeneration || playback !== this.localGeneration) { return; }
      this.remotePlaying = true;
      player.seek(0);
      await player.play();
    }).catch(() => this.failRemote(generation));
  }

  private failRemote(generation: number): void {
    if (generation !== this.remoteGeneration || this.destroyed) { return; }
    const fallback = this.remotePlaying;
    this.releaseRemote();
    this.updateState('REMOTE_FAILED', this.state.remoteUrl);
    if (fallback) { this.playLocal(); }
  }

  private playLocal(): void {
    const generation = ++this.localGeneration;
    this.cancelLocalPreparation?.();
    this.cancelLocalPreparation = null;
    // 串行关闭 rawfile 描述符，避免快速重播或 release 后重建时误关新播放器的文件。
    this.localTask = this.localTask.then(async () => {
      await this.releaseLocal();
      if (this.destroyed || generation !== this.localGeneration) { return; }
      const player = await media.createAVPlayer();
      if (this.destroyed || generation !== this.localGeneration) { await player.release(); return; }
      this.local = player;
      const resources = this.resources;
      const descriptor = resources.getRawFdSync(this.fallbackRawFile);
      this.localResources = resources;
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Local audio preparation timeout')), 5000);
        this.cancelLocalPreparation = () => {
          clearTimeout(timeout);
          reject(new Error('Local audio preparation cancelled'));
        };
        player.on('error', (error: BusinessError) => {
          clearTimeout(timeout);
          reject(error);
          this.localTask = this.localTask.then(async () => {
            if (this.local === player) { await this.releaseLocal(); }
          });
        });
        player.on('stateChange', (state: media.AVPlayerState) => {
          if (state === 'initialized') {
            try {
              player.audioRendererInfo = { usage: audio.StreamUsage.STREAM_USAGE_GAME, rendererFlags: 0 };
              player.prepare().catch((error: BusinessError) => { clearTimeout(timeout); reject(error); });
            } catch (error) { clearTimeout(timeout); reject(error); }
          } else if (state === 'prepared') {
            clearTimeout(timeout);
            this.cancelLocalPreparation = null;
            resolve();
          } else if (state === 'completed' && this.local === player) {
            this.localTask = this.localTask.then(async () => {
              if (this.local === player) { await this.releaseLocal(); }
            });
          }
        });
        player.fdSrc = { fd: descriptor.fd, offset: descriptor.offset, length: descriptor.length };
      });
      if (this.destroyed || generation !== this.localGeneration) { await this.releaseLocal(); return; }
      await player.play();
    }).catch(async () => {
      if (!this.destroyed && generation === this.localGeneration) {
        hilog.warn(0x30, 'GycSound', 'Local sound unavailable');
      }
      this.cancelLocalPreparation = null;
      await this.releaseLocal();
    });
  }

  private clearPrepareTimer(): void {
    if (this.prepareTimer >= 0) { clearTimeout(this.prepareTimer); this.prepareTimer = -1; }
  }

  private releaseRemote(): void {
    this.remoteGeneration++;
    this.clearPrepareTimer();
    const player = this.remote;
    this.remote = null;
    this.remoteReady = false;
    this.remotePlaying = false;
    if (player) {
      player.off('stateChange');
      player.off('error');
      player.release().catch(() => {});
    }
  }

  private async releaseLocal(): Promise<void> {
    const player = this.local;
    const resources = this.localResources;
    this.local = null;
    this.localResources = null;
    if (player) {
      player.off('stateChange');
      player.off('error');
      try { await player.release(); } catch (_) {}
    }
    if (resources) {
      try { resources.closeRawFdSync(this.fallbackRawFile); } catch (_) {}
    }
  }

  /** 永久关闭；本地 rawfile 描述符在播放器释放后关闭。 */
  release(): Promise<void> {
    if (this.destroyed) { return this.localTask; }
    this.destroyed = true;
    this.releaseRemote();
    this.localGeneration++;
    this.cancelLocalPreparation?.();
    this.cancelLocalPreparation = null;
    this.localTask = this.localTask.then(() => this.releaseLocal());
    this.updateState('RELEASED', '');
    return this.localTask;
  }
}
