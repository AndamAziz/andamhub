import { authHeaders } from '@/lib/auth-headers';
import { createFileRoute, useNavigate, Link } from '@tanstack/react-router';
import { Apple, Chrome, Eye, EyeOff, LoaderCircle } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';

import { supabase } from '@/integrations/supabase/client';
import { lovable } from '@/integrations/lovable/index';

import { syncMyAccount } from '@/lib/account.functions';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import authCinemaBackdrop from '@/assets/auth-cinema-backdrop.jpg';
import { isAndamNativeApp, startNativeOAuth } from '@/lib/native-auth';

export const Route = createFileRoute('/auth')({
  head: () => ({
    meta: [
      { title: 'Sign in to Andam' },
      {
        name: 'description',
        content: 'Sign in or create your Andam account to watch live TV, movies and shows.',
      },
      { property: 'og:title', content: 'Sign in to Andam' },
      {
        property: 'og:description',
        content: 'Access your Andam live TV providers, movies and shows from any device.',
      },
      { property: 'og:type', content: 'website' },
      { name: 'twitter:card', content: 'summary_large_image' },
    ],
  }),
  component: AuthPage,
});

type Mode = 'signin' | 'signup' | 'forgot';

function AuthPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const finishSignIn = useCallback(async (recordLogin: boolean, token?: string) => {
    const headers = token ? { Authorization: `Bearer ${token}` } : await authHeaders();
    const account = await syncMyAccount({ data: { recordLogin }, headers });
    if (account.suspended) {
      await supabase.auth.signOut();
      throw new Error('This account has been suspended. Contact the administrator.');
    }
    navigate({ to: account.role === 'admin' ? '/admin' : '/', replace: true });
  }, [navigate]);

  useEffect(() => {
    supabase.auth.getSession().then(async ({ data }) => {
      if (!data.session) return;
      try {
        await finishSignIn(false, data.session.access_token);
      } catch (err) {
        // Stale session: stay on the sign-in form instead of crashing.
        console.warn('[auth] account sync failed', err);
      }
    });
    const onComplete = () => {
      setBusy(true);
      void finishSignIn(true)
        .catch((err) => setError(err instanceof Error ? err.message : 'Sign-in failed'))
        .finally(() => setBusy(false));
    };
    const onNativeError = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail;
      setBusy(false);
      setError(detail || 'Sign-in failed');
    };
    window.addEventListener('andam:native-auth-complete', onComplete);
    window.addEventListener('andam:native-auth-error', onNativeError);
    return () => {
      window.removeEventListener('andam:native-auth-complete', onComplete);
      window.removeEventListener('andam:native-auth-error', onNativeError);
    };
  }, [finishSignIn]);

  async function signInWith(provider: 'google' | 'apple') {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      if (isAndamNativeApp()) {
        await startNativeOAuth(provider);
        return;
      }
      const result = await lovable.auth.signInWithOAuth(provider, {
        redirect_uri: window.location.origin,
      });
      if (result.error) {
        setError(result.error.message ?? `${provider} sign-in failed`);
        return;
      }
      if (result.redirected) return;
      await finishSignIn(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : `${provider} sign-in failed`);
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: React.FormEvent) {

    e.preventDefault();
    setError('');
    setNotice('');
    setBusy(true);
    try {
      if (mode === 'forgot') {
        const { error: err } = await supabase.auth.resetPasswordForEmail(email, {
          redirectTo: `${window.location.origin}/reset-password`,
        });
        if (err) throw err;
        setNotice('Password reset link sent. Check your inbox.');
        return;
      }

      let token = '';
      if (mode === 'signup') {
        const { data, error: err } = await supabase.auth.signUp({
          email,
          password,
          options: { emailRedirectTo: window.location.origin },
        });
        if (err) throw err;
        if (!data.session) {
          setNotice('Account created. Check your email to confirm before signing in.');
          return;
        }
        token = data.session.access_token;
      } else {
        const { data, error: err } = await supabase.auth.signInWithPassword({ email, password });
        if (err) throw err;
        token = data.session?.access_token ?? '';
      }

      // Use the token sign-in just returned: session storage may not be readable yet.
      const headers = token ? { Authorization: `Bearer ${token}` } : await authHeaders();
      await finishSignIn(true, headers.Authorization?.replace('Bearer ', ''));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="dark min-h-[100dvh] bg-background text-foreground lg:grid lg:grid-cols-[minmax(0,1.15fr)_minmax(420px,0.85fr)]">
      <section className="relative h-44 overflow-hidden sm:h-56 lg:h-[100dvh]" aria-label="Andam cinema">
        <img
          src={authCinemaBackdrop}
          alt="Dark cinema illuminated by ember and teal lights"
          width={1024}
          height={1536}
          className="absolute inset-0 h-full w-full object-cover object-center opacity-80 lg:opacity-90"
        />
        <div className="absolute inset-0 bg-background/35" />
        <Link
          to="/"
          className="absolute left-5 top-[max(1.25rem,env(safe-area-inset-top))] font-heading text-2xl font-bold text-primary sm:left-8 sm:text-3xl lg:left-12 lg:top-10"
        >
          ANDAM
        </Link>
        <p className="absolute bottom-5 left-5 max-w-xs font-heading text-lg font-semibold text-foreground sm:left-8 lg:bottom-12 lg:left-12 lg:text-3xl">
          Your screen. Your stories.
        </p>
      </section>

      <section className="mx-auto flex w-full max-w-md flex-col justify-center px-5 pb-[max(1.5rem,env(safe-area-inset-bottom))] pt-6 sm:px-8 lg:min-h-[100dvh] lg:py-10">
        <div>
          <h1 className="font-heading text-3xl font-bold sm:text-4xl">
            {mode === 'signin' ? 'Welcome back' : mode === 'signup' ? 'Create account' : 'Reset password'}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {mode === 'forgot'
              ? 'We will email you a secure reset link.'
              : 'Live TV, movies and shows in one place.'}
          </p>
        </div>

        <form onSubmit={submit} className="mt-5 space-y-3">
          <div className="space-y-2">
            <Label htmlFor="email" className="text-xs font-semibold uppercase text-muted-foreground">Email address</Label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@example.com"
              className="h-12 border-input bg-secondary/60 px-4 shadow-none focus-visible:border-primary focus-visible:ring-primary/40"
            />
          </div>

          {mode !== 'forgot' && (
            <div className="space-y-2">
              <Label htmlFor="password" className="text-xs font-semibold uppercase text-muted-foreground">Password</Label>
              <div className="relative">
                <Input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
                  required
                  minLength={6}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="h-12 border-input bg-secondary/60 px-4 pr-12 shadow-none focus-visible:border-primary focus-visible:ring-primary/40"
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  onClick={() => setShowPassword((visible) => !visible)}
                  className="absolute right-1.5 top-1.5 h-9 w-9 text-muted-foreground"
                >
                  {showPassword ? <EyeOff /> : <Eye />}
                </Button>
              </div>
            </div>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}
          {notice && <p className="text-sm text-accent">{notice}</p>}

          <Button type="submit" disabled={busy} className="h-12 w-full text-base font-bold active:scale-[0.98]">
            {busy && <LoaderCircle className="animate-spin" />}
            {busy
              ? 'Please wait…'
              : mode === 'signin'
                ? 'Sign in'
                : mode === 'signup'
                  ? 'Sign up'
                  : 'Send reset link'}
          </Button>
        </form>

        <div className="my-5 flex items-center gap-3">
          <span className="h-px flex-1 bg-border" />
          <span className="text-xs font-semibold uppercase text-muted-foreground">or continue with</span>
          <span className="h-px flex-1 bg-border" />
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => signInWith('google')} className="h-12 gap-2 font-semibold">
            <Chrome /> Google
          </Button>
          <Button type="button" variant="outline" disabled={busy} onClick={() => signInWith('apple')} className="h-12 gap-2 bg-background font-semibold">
            <Apple /> Apple
          </Button>
        </div>

        <div className="mt-5 flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-sm text-muted-foreground">
          {mode !== 'signin' && (
            <Button type="button" variant="link" className="h-11 px-1 text-muted-foreground" onClick={() => setMode('signin')}>
              Back to sign in
            </Button>
          )}
          {mode === 'signin' && (
            <>
              <Button type="button" variant="link" className="h-11 px-1 text-muted-foreground" onClick={() => setMode('signup')}>Create account</Button>
              <Button type="button" variant="link" className="h-11 px-1 text-muted-foreground" onClick={() => setMode('forgot')}>Forgot password?</Button>
            </>
          )}
        </div>
      </section>
    </main>
  );
}
