// Bridge between the website and the Windows app (window.andamDesktop).
const { contextBridge, ipcRenderer } = require('electron');

const player = process.argv.includes('--andam-player=1');

contextBridge.exposeInMainWorld('andamDesktop', {
  platform: 'windows',
  // True when the bundled Andam Player (mpv) is installed with the app.
  player,
  play: (opts) => ipcRenderer.invoke('player:play', opts),
  stop: () => ipcRenderer.invoke('player:stop'),
  focus: () => ipcRenderer.invoke('player:focus'),
  command: (args) => ipcRenderer.invoke('player:command', args),
  onEvent: (cb) => {
    ipcRenderer.removeAllListeners('player:event');
    ipcRenderer.on('player:event', (_e, data) => cb(data));
  },
});
