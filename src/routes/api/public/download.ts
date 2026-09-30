import { createFileRoute } from '@tanstack/react-router';

/**
 * Stable download links for the apps, used by the website's "Get the app" buttons.
 *
 *   /api/public/download?app=android  → newest Andam-N.apk (GitHub "latest" release)
 *   /api/public/download?app=windows  → Andam-Setup.exe from the rolling "windows-latest" pre-release
 *
 * The Android file name changes with every build, so it is looked up (and cached briefly).
 */

const REPO = 'AndamAziz/andamhub';
const RELEASES = `https://github.com/${REPO}/releases`;
const WINDOWS = `${RELEASES}/download/windows-latest/Andam-Setup.exe`;

let apkCache: { url: string; at: number } | null = null;

async function latestApk(): Promise<string> {
  if (apkCache && Date.now() - apkCache.at < 10 * 60_000) return apkCache.url;
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'andam-download' },
  });
  if (!res.ok) throw new Error(`GitHub ${res.status}`);
  const data = (await res.json()) as { assets?: Array<{ name?: string; browser_download_url?: string }> };
  const apk = (data.assets ?? []).find((a) => (a.name ?? '').toLowerCase().endsWith('.apk'));
  if (!apk?.browser_download_url) throw new Error('No APK in the latest release');
  apkCache = { url: apk.browser_download_url, at: Date.now() };
  return apk.browser_download_url;
}

const redirect = (to: string) =>
  new Response(null, { status: 302, headers: { Location: to, 'Cache-Control': 'no-store' } });

export const Route = createFileRoute('/api/public/download')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const app = (new URL(request.url).searchParams.get('app') ?? '').toLowerCase();
        if (app === 'windows') return redirect(WINDOWS);
        if (app === 'android') {
          try {
            return redirect(await latestApk());
          } catch (err) {
            console.error('[download] android lookup failed', err);
            return redirect(`${RELEASES}/latest`);
          }
        }
        return redirect(RELEASES);
      },
    },
  },
});
