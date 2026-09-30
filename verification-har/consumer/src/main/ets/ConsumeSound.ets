import { resourceManager } from '@kit.LocalizationKit';
import { SoundPlayer, SoundModule, SoundState } from '@gycrosskit/sound';

/** 只编译宿主资源接线，不执行播放。 */
export function createSound(resources: resourceManager.ResourceManager): SoundPlayer {
  const sound = new SoundPlayer(resources, 'host_sound.wav', (state: SoundState) => {});
  sound.prepare('https://example.test/sound.wav');
  sound.play();
  sound.release().catch(() => {});
  return sound;
}

export function createKuiklyModule(): SoundModule { return new SoundModule(); }
export const moduleName: string = SoundModule.MODULE_NAME;
