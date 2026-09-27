import { authHeaders } from '@/lib/auth-headers';
import { createFileRoute, Outlet, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';

import { AdminShell } from '@/components/admin/AdminShell';
import { syncMyAccount } from '@/lib/account.functions';

export const Route = createFileRoute('/_authenticated/admin')({
  component: AdminLayout,
  errorComponent: ({ error }) => (
    <div className="dark flex min-h-screen items-center justify-center bg-background p-6 text-center text-foreground">
      <div>
        <h1 className="text-xl font-semibold">Admin panel unavailable</h1>
        <p className="mt-2 text-sm text-muted-foreground">{error.message}</p>
      </div>
    </div>
  ),
  notFoundComponent: () => (
    <div className="dark flex min-h-screen items-center justify-center bg-background text-foreground">
      Section not found
    </div>
  ),
});

function AdminLayout() {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['my-account'],
    queryFn: async () => syncMyAccount({ data: { recordLogin: false }, headers: await authHeaders() }),
    staleTime: 60_000,
  });

  // Server functions re-verify the role on every call; this only hides the UI.
  useEffect(() => {
    if (!isLoading && (isError || (data && data.role !== 'admin'))) {
      navigate({ to: '/', replace: true });
    }
  }, [isLoading, isError, data, navigate]);

  if (isLoading || !data || data.role !== 'admin') {
    return (
      <div className="dark flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">
        Checking permissions…
      </div>
    );
  }

  return (
    <AdminShell email={data.email}>
      <Outlet />
    </AdminShell>
  );
}
