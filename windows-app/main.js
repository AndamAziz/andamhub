// Andam for Windows — a dedicated desktop window for ip.andam.uk.
// Auto-updates from the "windows-latest" GitHub release built by .github/workflows/windows.yml.
const { app, BrowserWindow, shell, dialog, Menu, session, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');
const net = require('net');
const { spawn } = require('child_process');

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
      additionalArguments: [`--andam-player=${hasPlayer() ? 1 : 0}`],
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

// ---------------------------------------------------------------------------
// Andam Player: a bundled mpv (resources/mpv/AndamPlayer.exe) that plays every
// stream and sound format (AC3, E-AC3, DTS...) natively. The website asks for it
// through window.andamDesktop.play(); we drive mpv over its JSON IPC pipe so the
// same player window switches channels instantly.
// ---------------------------------------------------------------------------
const MPV_DIR = app.isPackaged ? path.join(process.resourcesPath, 'mpv') : path.join(__dirname, 'mpv');
const MPV_EXE = path.join(MPV_DIR, 'AndamPlayer.exe');
const PIPE = `\\\\.\\pipe\\andam-player-${process.pid}`;
let mpv = null;
let sock = null;
let sockBuf = '';
let pending = [];

function hasPlayer() {
  return process.platform === 'win32' && fs.existsSync(MPV_EXE);
}
function notify(ev) {
  if (win && !win.isDestroyed()) win.webContents.send('player:event', ev);
}
function send(command) {
  const line = JSON.stringify({ command }) + '\n';
  if (sock && !sock.destroyed) sock.write(line);
  else pending.push(line);
}
function onLine(line) {
  let m;
  try {
    m = JSON.parse(line);
  } catch {
    return;
  }
  if (m.event === 'client-message' && Array.isArray(m.args)) {
    if (m.args[0] === 'andam-prev') notify({ type: 'prev' });
    if (m.args[0] === 'andam-next') notify({ type: 'next' });
    if (m.args[0] === 'andam-ended') notify({ type: 'ended' });
  }
  if (m.event === 'end-file' && m.reason === 'error') notify({ type: 'error', message: m.file_error || '' });
}
function connect(tries = 0) {
  const c = net.connect(PIPE);
  c.on('connect', () => {
    sock = c;
    pending.splice(0).forEach((l) => c.write(l));
  });
  c.on('data', (d) => {
    sockBuf += d.toString();
    let i;
    while ((i = sockBuf.indexOf('\n')) >= 0) {
      const line = sockBuf.slice(0, i);
      sockBuf = sockBuf.slice(i + 1);
      onLine(line);
    }
  });
  c.on('error', () => {
    if (!sock && mpv && tries < 60) setTimeout(() => connect(tries + 1), 100);
  });
  c.on('close', () => {
    if (sock === c) sock = null;
  });
}
// Loads a stream. Live channels and episodes get marker entries around them so the player's
// own ⏮ ⏭ buttons work (see mpv/portable_config/scripts/andam.lua).
function load(url, title, nav) {
  send(['set_property', 'force-media-title', title]);
  send(['loadfile', url, 'replace']);
  if (nav) {
    send(['loadfile', 'andam://prev', 'append']);
    send(['loadfile', 'andam://next', 'append']);
    send(['playlist-move', 1, 0]);
  }
  send(['set_property', 'pause', false]);
}

function startPlayer(url, title, nav) {
  sockBuf = '';
  pending = [];
  mpv = spawn(MPV_EXE, [`--input-ipc-server=${PIPE}`, '--idle=yes', `--force-media-title=${title}`], {
    cwd: MPV_DIR,
    stdio: 'ignore',
    windowsHide: false,
  });
  mpv.on('exit', () => {
    mpv = null;
    sock = null;
    pending = [];
    notify({ type: 'closed' });
  });
  mpv.on('error', () => {
    mpv = null;
    notify({ type: 'closed' });
  });
  load(url, title, nav);
  setTimeout(() => connect(), 150);
}

ipcMain.handle('player:play', (_e, o) => {
  if (!hasPlayer()) return { ok: false };
  const url = String((o && o.url) || '');
  // Only streams from the Andam API may be opened.
  // Andam API streams, plus the device route of providers that refuse the relay (see
  // DEVICE_DIRECT_HOSTS in src/routes/api/public/xtream.ts).
  const allowed =
    /^https:\/\/ip\.andam\.uk\/api\/public\//.test(url) ||
    /^https?:\/\/([a-z0-9-]+\.)*myrestreamer\.com(:\d+)?\//i.test(url);
  if (!allowed) return { ok: false };
  const title = String((o && o.title) || 'Andam').replace(/[\r\n]/g, ' ').slice(0, 200);
  try {
    const nav = Boolean(o && o.nav);
    if (mpv) load(url, title, nav);
    else startPlayer(url, title, nav);
    return { ok: true };
  } catch {
    return { ok: false };
  }
});
ipcMain.handle('player:stop', () => {
  if (!mpv) return;
  send(['quit']);
  const p = mpv;
  setTimeout(() => {
    try {
      if (p && p.exitCode === null) p.kill();
    } catch {
      /* already gone */
    }
  }, 800);
});
ipcMain.handle('player:focus', () => {
  if (!mpv) return;
  send(['set_property', 'ontop', true]);
  setTimeout(() => send(['set_property', 'ontop', false]), 400);
});
ipcMain.handle('player:command', (_e, args) => {
  if (mpv && Array.isArray(args) && args[0] === 'seek') send(['seek', Number(args[1]) || 0]);
});
app.on('before-quit', () => {
  try {
    if (mpv) mpv.kill();
  } catch {
    /* ignore */
  }
});

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
