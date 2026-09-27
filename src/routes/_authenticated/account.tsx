import { authHeaders } from '@/lib/auth-headers';
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { supabase } from '@/integrations/supabase/client';
import { syncMyAccount } from '@/lib/account.functions';
import { getMyAccess, redeemMyCode } from '@/lib/access.functions';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

export const Route = createFileRoute('/_authenticated/account')({
  head: () => ({
    meta: [
      { title: 'Your Andam account' },
      { name: 'description', content: 'Manage your Andam password, role and provider access.' },
      { property: 'og:title', content: 'Your Andam account' },
      { property: 'og:description', content: 'Manage your Andam password, role and provider access.' },
      { property: 'og:type', content: 'website' },
      { name: 'twitter:card', content: 'summary_large_image' },
    ],
  }),
  component: AccountPage,
});

function AccountPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [code, setCode] = useState('');
  const [codeMsg, setCodeMsg] = useState('');
  const [codeOk, setCodeOk] = useState(false);

  const account = useQuery({
    queryKey: ['my-account'],
    queryFn: async () => syncMyAccount({ data: { recordLogin: false }, headers: await authHeaders() }),
  });

  const access = useQuery({ queryKey: ['my-access'], queryFn: () => getMyAccess() });

  const LABELS: Record<string, string> = { live: 'Live TV', movies: 'Movies', series: 'Shows' };
  const unlocked = (access.data?.sections ?? []).map((s) => LABELS[s] ?? s);
  const unlockedLabel = access.data?.admin
    ? 'As an admin you can open every section.'
    : unlocked.length
      ? `Unlocked: ${unlocked.join(', ')}.`
      : 'Live TV, Movies and Shows are locked — redeem an activation code to unlock them.';

  async function redeem(e: React.FormEvent) {
    e.preventDefault();
    const res = await redeemMyCode({ data: { code } });
    setCodeOk(res.ok);
    setCodeMsg(res.message);
    if (res.ok) {
      setCode('');
      qc.invalidateQueries({ queryKey: ['my-access'] });
    }
  }


  async function changePassword(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setMessage('');
    const { error: err } = await supabase.auth.updateUser({ password });
    if (err) setError(err.message);
    else {
      setPassword('');
      setMessage('Password updated.');
    }
  }

  async function signOut() {
    await qc.cancelQueries();
    qc.clear();
    await supabase.auth.signOut();
    navigate({ to: '/auth', replace: true });
  }

  return (
    <div className="dark min-h-screen bg-background text-foreground">
      <div className="mx-auto max-w-lg px-5 py-12">
        <Link to="/" className="text-sm text-muted-foreground underline">
          ← Back to Andam
        </Link>
        <h1 className="mt-6 text-2xl font-semibold">Account settings</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {account.data?.email} · role: {account.data?.role ?? '—'}
        </p>

        {account.data?.role === 'admin' && (
          <Link
            to="/admin"
            className="mt-4 inline-flex min-h-11 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground"
          >
            Open admin panel
          </Link>
        )}

        <form onSubmit={changePassword} className="mt-8 space-y-3">
          <Label htmlFor="new-password">New password</Label>
          <Input
            id="new-password"
            type="password"
            minLength={6}
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="min-h-11"
          />
          {error && <p className="text-sm text-destructive">{error}</p>}
          {message && <p className="text-sm text-accent">{message}</p>}
          <Button type="submit" className="min-h-11">
            Change password
          </Button>
        </form>

        <section className="mt-10 rounded-lg border border-border p-4">
          <h2 className="text-base font-semibold">Section access</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            IPTV is always available. {unlockedLabel}
          </p>
          <form onSubmit={redeem} className="mt-4 space-y-3">
            <Label htmlFor="activation-code">Activation code</Label>
            <Input
              id="activation-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="ABCD-EFGH-JKLM"
              className="min-h-11 font-mono"
            />
            {codeMsg && (
              <p className={`text-sm ${codeOk ? 'text-accent' : 'text-destructive'}`}>{codeMsg}</p>
            )}
            <Button type="submit" variant="secondary" className="min-h-11">
              Redeem code
            </Button>
          </form>
        </section>

        <Button variant="secondary" className="mt-8 min-h-11 w-full" onClick={signOut}>
          Log out
        </Button>
      </div>
    </div>
  );
}
