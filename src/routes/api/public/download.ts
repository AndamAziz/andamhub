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

type Asset = { name?: string | undefined; browser_download_url?: string | undefined; size?: number | undefined };
type Release = { tag_name?: string | undefined; name?: string | undefined; body?: string | undefined; assets?: Asset[] | undefined };

/**
 * The latest Android release, asked of GitHub at most every 10 minutes. The apps ask HERE
 * (`?app=android&info=1`) rather than GitHub: GitHub allows 60 unauthenticated API calls an
 * hour per address, which viewers behind a shared VPN / carrier address used up ("Update
 * server answered 403").
 */
let releaseCache: { release: Release; at: number } | null = null;

async function latestRelease(): Promise<Release> {
  if (releaseCache && Date.now() - releaseCache.at < 10 * 60_000) return releaseCache.release;
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'andam-download' },
  });
  if (!res.ok) {
    if (releaseCache) return releaseCache.release; // keep answering with the last known release
    throw new Error(`GitHub ${res.status}`);
  }
  const data = (await res.json()) as Release;
  const release: Release = {
    tag_name: data.tag_name,
    name: data.name,
    body: (data.body ?? '').slice(0, 4000),
    assets: (data.assets ?? []).map((a) => ({ name: a.name, browser_download_url: a.browser_download_url, size: a.size })),
  };
  releaseCache = { release, at: Date.now() };
  return release;
}

async function latestApk(): Promise<string> {
  const apk = (await latestRelease()).assets?.find((a) => (a.name ?? '').toLowerCase().endsWith('.apk'));
  if (!apk?.browser_download_url) throw new Error('No APK in the latest release');
  return apk.browser_download_url;
}

const redirect = (to: string) =>
  new Response(null, { status: 302, headers: { Location: to, 'Cache-Control': 'no-store' } });

export const Route = createFileRoute('/api/public/download')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const params = new URL(request.url).searchParams;
        const app = (params.get('app') ?? '').toLowerCase();
        if (app === 'windows') return redirect(WINDOWS);
        // The Android app's update check: the release in GitHub's own JSON shape.
        if (app === 'android' && params.get('info') === '1') {
          try {
            return new Response(JSON.stringify(await latestRelease()), {
              headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=300' },
            });
          } catch (err) {
            console.error('[download] android release lookup failed', err);
            return new Response(JSON.stringify({ error: 'unavailable' }), {
              status: 503,
              headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
            });
          }
        }
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
