// Lets the website know it runs inside the Windows app (e.g. to hide "Download for Windows").
const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('andamDesktop', { platform: 'windows' });
