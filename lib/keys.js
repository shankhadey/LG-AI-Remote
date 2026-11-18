// The app's common button vocabulary. Each driver maps these to its TV's own codes.
export const KEYS = [
  'POWER', 'POWER_ON', 'VOL_UP', 'VOL_DOWN', 'MUTE', 'CH_UP', 'CH_DOWN',
  'UP', 'DOWN', 'LEFT', 'RIGHT', 'OK', 'BACK', 'HOME', 'MENU', 'INFO', 'INPUT',
  'PLAY', 'PAUSE', 'PLAY_PAUSE', 'STOP', 'REW', 'FF',
  ...Array.from({ length: 10 }, (_, i) => `NUM_${i}`),
];
