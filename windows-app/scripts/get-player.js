// Downloads the latest mpv build for Windows (shinchiro/mpv-winbuild-cmake), installs it as
// mpv/AndamPlayer.exe with the Andam icon. Runs in CI before electron-builder.
// If anything fails the app is still built; it then plays inside the window as before.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const OUT = path.join(__dirname, '..', 'mpv');
const REPO = 'shinchiro/mpv-winbuild-cmake';

async function main() {
  const headers = { 'User-Agent': 'andam-build', Accept: 'application/vnd.github+json' };
  if (process.env.GH_TOKEN) headers.Authorization = `Bearer ${process.env.GH_TOKEN}`;
  const rel = await (await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers })).json();
  const asset = (rel.assets || []).find(
    (a) => /^mpv-x86_64-/i.test(a.name) && !/^mpv-x86_64-v3-/i.test(a.name) && /\.7z$/i.test(a.name),
  );
  if (!asset) throw new Error('No mpv x86_64 build found in ' + (rel.tag_name || 'latest release'));
  console.log('mpv build:', asset.name);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mpv-'));
  const archive = path.join(tmp, asset.name);
  const res = await fetch(asset.browser_download_url, { headers: { 'User-Agent': 'andam-build' } });
  if (!res.ok) throw new Error('Download failed: HTTP ' + res.status);
  fs.writeFileSync(archive, Buffer.from(await res.arrayBuffer()));

  const sevenZip = ['7z', 'C:\\Program Files\\7-Zip\\7z.exe'].find((c) => {
    try {
      execFileSync(c, ['i'], { stdio: 'ignore' });
      return true;
    } catch {
      return false;
    }
  });
  if (!sevenZip) throw new Error('7-Zip not found');
  const dir = path.join(tmp, 'x');
  execFileSync(sevenZip, ['x', archive, `-o${dir}`, '-y'], { stdio: 'ignore' });

  // mpv.exe may sit at the top of the archive or in a sub-folder.
  const find = (d) => {
    for (const f of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, f.name);
      if (f.isFile() && f.name.toLowerCase() === 'mpv.exe') return d;
      if (f.isDirectory()) {
        const r = find(p);
        if (r) return r;
      }
    }
    return null;
  };
  const root = find(dir);
  if (!root) throw new Error('mpv.exe missing from the archive');
  fs.mkdirSync(OUT, { recursive: true });
  for (const f of fs.readdirSync(root)) {
    const low = f.toLowerCase();
    if (low === 'mpv.exe') fs.copyFileSync(path.join(root, f), path.join(OUT, 'AndamPlayer.exe'));
    else if (low.endsWith('.dll')) fs.copyFileSync(path.join(root, f), path.join(OUT, f));
  }
  const exe = path.join(OUT, 'AndamPlayer.exe');
  if (!fs.existsSync(exe)) throw new Error('mpv.exe missing from the archive');

  const rcedit = require('rcedit');
  await rcedit(exe, {
    icon: path.join(__dirname, '..', 'build', 'icon.ico'),
    'version-string': { FileDescription: 'Andam Player', ProductName: 'Andam Player', CompanyName: 'Andam' },
  });
  console.log('Andam Player ready:', exe, Math.round(fs.statSync(exe).size / 1e6) + ' MB');
}

main().catch((err) => {
  console.warn('::warning::Andam Player (mpv) was not bundled: ' + err.message);
});
