import { supabase } from '@/integrations/supabase/client';

const OAUTH_STATE_KEY = 'andam-native-oauth-state';
const NATIVE_CALLBACK = 'lovable://oauth-callback';

type NativeOAuthProvider = 'google' | 'apple';

type CapacitorWindow = Window & {
  Capacitor?: {
    isNativePlatform?: () => boolean;
  };
};

function isNativeApp() {
  if (typeof window === 'undefined') return false;
  return Boolean((window as CapacitorWindow).Capacitor?.isNativePlatform?.());
}

function randomState() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function callbackParams(url: string) {
  const parsed = new URL(url);
  const query = parsed.searchParams;
  const hash = new URLSearchParams(parsed.hash.replace(/^#/, ''));
  return {
    state: query.get('state') ?? hash.get('state'),
    accessToken: query.get('access_token') ?? hash.get('access_token'),
    refreshToken: query.get('refresh_token') ?? hash.get('refresh_token'),
    error: query.get('error_description') ?? hash.get('error_description') ?? query.get('error') ?? hash.get('error'),
  };
}

export function isAndamNativeApp() {
  return isNativeApp();
}

export async function startNativeOAuth(provider: NativeOAuthProvider) {
  if (!isNativeApp()) return false;

  const state = randomState();
  window.sessionStorage.setItem(OAUTH_STATE_KEY, state);
  const params = new URLSearchParams({
    provider,
    redirect_uri: NATIVE_CALLBACK,
    state,
  });
  const { Browser } = await import('@capacitor/browser');
  await Browser.open({
    url: `${window.location.origin}/~oauth/initiate?${params.toString()}`,
    presentationStyle: 'popover',
  });
  return true;
}

async function finishNativeOAuth(url: string) {
  if (!url.startsWith(NATIVE_CALLBACK)) return;

  const params = callbackParams(url);
  const expectedState = window.sessionStorage.getItem(OAUTH_STATE_KEY);
  window.sessionStorage.removeItem(OAUTH_STATE_KEY);

  const { Browser } = await import('@capacitor/browser');
  await Browser.close().catch(() => undefined);

  if (!expectedState || params.state !== expectedState) {
    window.dispatchEvent(
      new CustomEvent('andam:native-auth-error', { detail: 'The sign-in response could not be verified. Please try again.' }),
    );
    return;
  }
  if (params.error) {
    window.dispatchEvent(new CustomEvent('andam:native-auth-error', { detail: params.error }));
    return;
  }
  if (!params.accessToken || !params.refreshToken) {
    window.dispatchEvent(
      new CustomEvent('andam:native-auth-error', { detail: 'The sign-in provider did not return a complete session.' }),
    );
    return;
  }

  const { error } = await supabase.auth.setSession({
    access_token: params.accessToken,
    refresh_token: params.refreshToken,
  });
  if (error) {
    window.dispatchEvent(new CustomEvent('andam:native-auth-error', { detail: error.message }));
    return;
  }
  window.dispatchEvent(new CustomEvent('andam:native-auth-complete'));
}

export function installNativeOAuthListener() {
  if (!isNativeApp()) return () => undefined;

  let disposed = false;
  let removeListener: (() => Promise<void>) | undefined;

  void import('@capacitor/app').then(async ({ App }) => {
    const listener = await App.addListener('appUrlOpen', ({ url }) => {
      if (!disposed) void finishNativeOAuth(url);
    });
    removeListener = () => listener.remove();

    const launch = await App.getLaunchUrl();
    if (!disposed && launch?.url) void finishNativeOAuth(launch.url);
  });

  return () => {
    disposed = true;
    void removeListener?.();
  };
}