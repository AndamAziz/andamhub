import { createFileRoute } from '@tanstack/react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { Panel } from '@/components/admin/AdminShell';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  clearContentOverride,
  getFeaturedChannels,
  getOverrides,
  getProviderItems,
  getProviders,
  removeFeaturedChannel,
  saveContentOverride,
  saveFeaturedChannel,
} from '@/lib/admin.functions';
import type { OverrideKind } from '@/lib/overrides.server';

export const Route = createFileRoute('/_authenticated/admin/content')({
  head: () => ({
    meta: [
      { title: 'Andam content controls' },
      { name: 'description', content: 'Reorder, hide and re-logo live channels, movies and series per provider.' },
      { property: 'og:title', content: 'Andam content controls' },
      { property: 'og:description', content: 'Reorder, hide and re-logo live channels, movies and series per provider.' },
      { property: 'og:type', content: 'website' },
      { name: 'twitter:card', content: 'summary_large_image' },
    ],
  }),
  component: ContentPage,
});

const KINDS: Array<{ key: OverrideKind; label: string }> = [
  { key: 'live', label: 'Live channels' },
  { key: 'category', label: 'Categories' },
  { key: 'vod', label: 'Movies' },
  { key: 'series', label: 'Series' },
];

function ContentPage() {
  const qc = useQueryClient();
  const [sourceId, setSourceId] = useState('');
  const [kind, setKind] = useState<OverrideKind>('live');
  const [search, setSearch] = useState('');
  const [message, setMessage] = useState('');

  const providers = useQuery({ queryKey: ['admin', 'providers'], queryFn: () => getProviders() });

  useEffect(() => {
    const first = providers.data?.[0];
    if (!sourceId && first) setSourceId(first.id);
  }, [providers.data, sourceId]);

  const items = useQuery({
    queryKey: ['admin', 'items', sourceId, kind],
    queryFn: () => getProviderItems({ data: { sourceId, kind } }),
    enabled: Boolean(sourceId),
    staleTime: 5 * 60_000,
  });

  const overrides = useQuery({
    queryKey: ['admin', 'overrides', sourceId],
    queryFn: () => getOverrides({ data: { sourceId } }),
    enabled: Boolean(sourceId),
  });

  const save = useMutation({
    mutationFn: (input: {
      itemId: string;
      label?: string;
      hidden?: boolean;
      sortOrder?: number | null;
      logoUrl?: string | null;
    }) => saveContentOverride({ data: { sourceId, kind, ...input } }),
    onSuccess: () => {
      setMessage('Saved — live for all viewers.');
      qc.invalidateQueries({ queryKey: ['admin', 'overrides', sourceId] });
    },
    onError: (e: Error) => setMessage(e.message),
  });

  const reset = useMutation({
    mutationFn: (itemId: string) => clearContentOverride({ data: { sourceId, kind, itemId } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'overrides', sourceId] }),
    onError: (e: Error) => setMessage(e.message),
  });

  const ruleFor = (itemId: string) =>
    (overrides.data ?? []).find((o) => o.kind === kind && o.item_id === itemId);

  const filtered = (items.data ?? [])
    .filter((i) => i.name.toLowerCase().includes(search.toLowerCase()))
    .slice(0, 300);

  return (
    <div>
      <h1 className="mb-6 text-2xl font-semibold">Content</h1>
      {message && <p className="mb-4 text-sm text-accent">{message}</p>}

      <FeaturedPanel />

      <Panel title="Provider & section">
        <div className="flex flex-wrap gap-2">
          {(providers.data ?? []).map((p) => (
            <Button
              key={p.id}
              size="sm"
              variant={p.id === sourceId ? 'default' : 'secondary'}
              className="min-h-11"
              onClick={() => setSourceId(p.id)}
            >
              {p.name}
            </Button>
          ))}
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          {KINDS.map((k) => (
            <Button
              key={k.key}
              size="sm"
              variant={k.key === kind ? 'default' : 'secondary'}
              className="min-h-11"
              onClick={() => setKind(k.key)}
            >
              {k.label}
            </Button>
          ))}
        </div>
        <div className="mt-4 max-w-sm space-y-2">
          <Label>Search</Label>
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter by name"
            className="min-h-11"
          />
        </div>
      </Panel>

      <Panel
        title="Numbering, visibility and logos"
        description="Set a channel number to reorder, hide an item to block it for all users, or paste a logo URL to replace a broken icon."
      >
        {items.isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        ) : items.isError ? (
          <p className="text-sm text-destructive">Could not load items from this provider.</p>
        ) : (
          <div className="space-y-2">
            {filtered.map((item) => {
              const rule = ruleFor(item.id);
              return (
                <ItemRow
                  key={item.id}
                  item={item}
                  hidden={kind !== 'category' && Boolean(rule?.hidden)}
                  order={rule?.sort_order ?? item.num ?? null}
                  logo={rule?.logo_url ?? ''}
                  showLogo={kind === 'live'}
                  canHide={kind !== 'category'}
                  onSave={(patch) => save.mutate({ itemId: item.id, label: item.name, ...patch })}
                  onReset={() => reset.mutate(item.id)}
                />
              );
            })}
            {filtered.length === 0 && (
              <p className="text-sm text-muted-foreground">No items match your search.</p>
            )}
          </div>
        )}
      </Panel>
    </div>
  );
}

function FeaturedPanel() {
  const qc = useQueryClient();
  const [pattern, setPattern] = useState('');
  const [order, setOrder] = useState('');

  const list = useQuery({ queryKey: ['admin', 'featured'], queryFn: () => getFeaturedChannels() });
  const invalidate = () => qc.invalidateQueries({ queryKey: ['admin', 'featured'] });

  const save = useMutation({
    mutationFn: (input: { pattern: string; sortOrder: number }) =>
      saveFeaturedChannel({ data: input }),
    onSuccess: () => {
      setPattern('');
      setOrder('');
      invalidate();
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => removeFeaturedChannel({ data: { id } }),
    onSuccess: invalidate,
  });

  return (
    <Panel
      title="Featured priority (hero & Live now)"
      description="Channel name patterns that lead the homepage hero and the Live now row, lowest number first. Matching is case-insensitive and partial, e.g. 'bein sports' matches 'beIN Sports 1 HD'."
    >
      <div className="mb-4 flex flex-wrap items-end gap-2">
        <div className="space-y-2">
          <Label>Name contains</Label>
          <Input
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            placeholder="bein sports"
            className="min-h-11 w-56"
          />
        </div>
        <div className="space-y-2">
          <Label>Priority</Label>
          <Input
            value={order}
            onChange={(e) => setOrder(e.target.value)}
            placeholder="10"
            className="min-h-11 w-24"
          />
        </div>
        <Button
          className="min-h-11"
          disabled={!pattern.trim() || save.isPending}
          onClick={() => save.mutate({ pattern: pattern.trim(), sortOrder: Number(order) || 100 })}
        >
          Add / update
        </Button>
      </div>

      {list.isLoading ? (
        <Skeleton className="h-24 w-full" />
      ) : (
        <div className="space-y-2">
          {(list.data ?? []).map((row) => (
            <div
              key={row.id}
              className="flex items-center gap-3 rounded-lg border border-border p-3"
            >
              <span className="w-12 font-mono text-xs text-muted-foreground">{row.sort_order}</span>
              <span className="min-w-0 flex-1 truncate text-sm">{row.pattern}</span>
              <Button
                size="sm"
                variant="ghost"
                className="min-h-11"
                onClick={() => remove.mutate(row.id)}
              >
                Remove
              </Button>
            </div>
          ))}
          {(list.data ?? []).length === 0 && (
            <p className="text-sm text-muted-foreground">
              No priority channels yet — the hero shows any live channel.
            </p>
          )}
        </div>
      )}
    </Panel>
  );
}

function ItemRow({
  item,
  hidden,
  order,
  logo,
  showLogo,
  canHide = true,
  onSave,
  onReset,
}: {
  item: { id: string; name: string; num?: number; logo?: string };
  hidden: boolean;
  order: number | null;
  logo: string;
  showLogo: boolean;
  /** Categories always follow the provider: they can be renumbered but not hidden. */
  canHide?: boolean;
  onSave: (patch: { hidden?: boolean; sortOrder?: number | null; logoUrl?: string | null }) => void;
  onReset: () => void;
}) {
  const [num, setNum] = useState(order ?? '');
  const [logoUrl, setLogoUrl] = useState(logo);

  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border p-3">
      <span className="w-14 font-mono text-xs text-muted-foreground">#{order ?? '—'}</span>
      <span className={`min-w-0 flex-1 truncate text-sm ${hidden ? 'text-muted-foreground line-through' : ''}`}>
        {item.name}
      </span>
      <Input
        value={String(num)}
        onChange={(e) => setNum(e.target.value)}
        placeholder="No."
        className="min-h-11 w-20"
      />
      {showLogo && (
        <Input
          value={logoUrl}
          onChange={(e) => setLogoUrl(e.target.value)}
          placeholder="Logo URL override"
          className="min-h-11 w-48 font-mono text-xs"
        />
      )}
      <Button
        size="sm"
        className="min-h-11"
        onClick={() =>
          onSave({
            sortOrder: num === '' ? null : Number(num),
            ...(showLogo ? { logoUrl: logoUrl || null } : {}),
          })
        }
      >
        Save
      </Button>
      {canHide && (
        <Button size="sm" variant={hidden ? 'default' : 'secondary'} className="min-h-11" onClick={() => onSave({ hidden: !hidden })}>
          {hidden ? 'Show' : 'Hide'}
        </Button>
      )}
      <Button size="sm" variant="ghost" className="min-h-11" onClick={onReset}>
        Reset
      </Button>
    </div>
  );
}
