import { supabase } from '@/integrations/supabase/client';

/** Bearer header from the current session, sent explicitly so a server call never goes out without it. */
export async function authHeaders(): Promise<Record<string, string>> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}
