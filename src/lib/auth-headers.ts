import { supabase } from '@/integrations/supabase/client';

/**
 * Bearer header from the current session, sent explicitly so a server call never
 * goes out without it. Right after sign-in the stored session can lag, so wait
 * briefly for the auth client to report it before giving up.
 */
export async function authHeaders(): Promise<Record<string, string>> {
  const { data } = await supabase.auth.getSession();
  let token = data.session?.access_token;
  if (!token) {
    token = await new Promise<string | undefined>((resolve) => {
      const timer = setTimeout(() => {
        sub.subscription.unsubscribe();
        resolve(undefined);
      }, 3000);
      const { data: sub } = supabase.auth.onAuthStateChange((_event, session) => {
        if (!session?.access_token) return;
        clearTimeout(timer);
        sub.subscription.unsubscribe();
        resolve(session.access_token);
      });
    });
  }
  return token ? { Authorization: `Bearer ${token}` } : {};
}
