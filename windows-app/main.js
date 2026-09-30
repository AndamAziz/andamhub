// Andam for Windows — a dedicated desktop window for ip.andam.uk.
// Auto-updates from the "windows-latest" GitHub release built by .github/workflows/windows.yml.
const { app, BrowserWindow, shell, dialog, Menu, session } = require('electron');
const path = require('path');
const fs = require('fs');

const HOME = 'https://ip.andam.uk/';
// Pages that may open inside the app (the site itself and its sign-in steps).
const INSIDE = [
  /(^|\.)andam\.uk$/i,
  /(^|\.)lovable\.app$/i,
  /(^|\.)lovable\.dev$/i,
  /(^|\.)supabase\.co$/i,
  /^accounts\.google\.com$/i,
  /(^|\.)google\.com$/i,
  /(^|\.)gstatic\.com$/i,
  /^appleid\.apple\.com$/i,
];
const isInside = (url) => {
  try {
    const u = new URL(url);
    if (u.protocol === 'about:' || u.protocol === 'data:' || u.protocol === 'file:') return true;
    return INSIDE.some((re) => re.test(u.hostname));
  } catch {
    return false;
  }
};

// Google refuses sign-in from browsers that announce "Electron"; look like plain Chrome.
app.userAgentFallback = app.userAgentFallback
  .replace(/\sElectron\/\S+/i, '')
  .replace(/\sandam-desktop\/\S+/i, '')
  .replace(/\sAndam\/\S+/i, '');

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

let win = null;
const stateFile = () => path.join(app.getPath('userData'), 'window.json');
const loadState = () => {
  try {
    return JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
  } catch {
    return { width: 1320, height: 820, maximized: false };
  }
};
const saveState = () => {
  if (!win || win.isDestroyed()) return;
  try {
    const b = win.getNormalBounds();
    fs.writeFileSync(stateFile(), JSON.stringify({ ...b, maximized: win.isMaximized() }));
  } catch {
    /* not critical */
  }
};

function showOffline(target) {
  if (!win || win.isDestroyed()) return;
  win.loadFile(path.join(__dirname, 'offline.html'), { query: { u: target || HOME } });
}

function createWindow() {
  const s = loadState();
  win = new BrowserWindow({
    x: s.x,
    y: s.y,
    width: s.width || 1320,
    height: s.height || 820,
    minWidth: 900,
    minHeight: 560,
    title: 'Andam',
    backgroundColor: '#0A0B0F',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      autoplayPolicy: 'no-user-gesture-required',
      spellcheck: false,
    },
  });
  Menu.setApplicationMenu(null);
  if (s.maximized) win.maximize();
  win.once('ready-to-show', () => win.show());
  win.on('close', saveState);

  // Links to other sites open in the normal browser; sign-in pop-ups stay in the app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isInside(url)) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520,
          height: 720,
          autoHideMenuBar: true,
          backgroundColor: '#0A0B0F',
          parent: win,
        },
      };
    }
    shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!isInside(url)) {
      e.preventDefault();
      shell.openExternal(url);
    }
  });

  // No connection: friendly page with a retry button instead of a blank window.
  win.webContents.on('did-fail-load', (_e, code, _desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3 /* aborted */) return;
    if (url && url.startsWith('file:')) return;
    showOffline(url);
  });

  // Keyboard: F11 full screen, F5 / Ctrl+R reload, Esc leaves full screen, Alt+Left back.
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const k = input.key;
    if (k === 'F11') {
      win.setFullScreen(!win.isFullScreen());
      e.preventDefault();
    } else if (k === 'F5' || (input.control && k.toLowerCase() === 'r')) {
      win.webContents.reload();
      e.preventDefault();
    } else if (k === 'Escape' && win.isFullScreen()) {
      win.setFullScreen(false);
    } else if (input.alt && k === 'ArrowLeft' && win.webContents.navigationHistory?.canGoBack()) {
      win.webContents.navigationHistory.goBack();
      e.preventDefault();
    }
  });

  win.loadURL(HOME);
}

function setupUpdates() {
  if (!app.isPackaged) return;
  let autoUpdater;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch {
    return;
  }
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('update-downloaded', async (info) => {
    const r = await dialog.showMessageBox(win, {
      type: 'info',
      buttons: ['Restart now', 'Later'],
      defaultId: 0,
      cancelId: 1,
      title: 'Andam update',
      message: `A new version of Andam (${info.version}) is ready.`,
      detail: 'Restart now to use it, or it will be installed the next time you close Andam.',
    });
    if (r.response === 0) autoUpdater.quitAndInstall();
  });
  autoUpdater.on('error', () => {
    /* offline or no release yet — try again later */
  });
  const check = () => autoUpdater.checkForUpdates().catch(() => {});
  check();
  setInterval(check, 6 * 60 * 60 * 1000);
}

app.on('second-instance', () => {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.focus();
});

app.whenReady().then(() => {
  // Keep the site's own permission prompts simple: allow full screen and media, nothing else.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) => {
    cb(['fullscreen', 'media', 'clipboard-sanitized-write'].includes(permission));
  });
  createWindow();
  setupUpdates();
});

app.on('window-all-closed', () => app.quit());
