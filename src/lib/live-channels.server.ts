/**
 * Curated Live TV channel list (`iptv_channels`).
 *
 * Live TV normally reads straight from the provider's player_api. When a
 * provider's channels have been written here — either by the "Refresh Live TV
 * channels" button in the admin panel (which rebuilds the list from the stored
 * credentials and relay) or by importing a list the provider submitted through
 * their portal — this table becomes the list viewers see for that provider.
 *
 * Stream URLs stay server-side: they are sealed into opaque tokens before they
 * reach the browser, exactly like the live player_api path.
 */
import { supabaseAdmin } from '@/integrations/supabase/client.server';
import { liveStreamUrl, playerApi, type Source } from '@/lib/xtream';

export type LiveChannel = {
  key: string;
  num: number;
  name: string;
  logo: string;
  group: string;
  url: string;
  mediaKind: string;
};

type Row = {
  channel_key: string;
  num: number;
  name: string;
  logo: string | null;
  group_title: string;
  url: string;
  media_kind: string;
};

const toChannel = (r: Row): LiveChannel => ({
  key: r.channel_key,
  num: r.num,
  name: r.name,
  logo: r.logo ?? '',
  group: r.group_title,
  url: r.url,
  mediaKind: r.media_kind,
});

/** Every curated channel for a provider, ordered the way viewers see them. */
export async function listLiveChannels(sourceId: string): Promise<LiveChannel[]> {
  // Read page by page: the database answers at most 1000 rows per request, and a
  // provider can have tens of thousands of channels. Every one of them is returned.
  const PAGE = 1000;
  const all: LiveChannel[] = [];
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await supabaseAdmin
      .from('iptv_channels')
      .select('channel_key, num, name, logo, group_title, url, media_kind')
      .eq('source_id', sourceId)
      .order('num', { ascending: true })
      .order('channel_key', { ascending: true })
      .range(from, from + PAGE - 1);
    if (error) throw new Error(error.message);
    const rows = data ?? [];
    for (const r of rows) all.push(toChannel(r as Row));
    if (rows.length < PAGE) break;
  }
  return all;
}

/** One curated channel, used when minting a playback token. */
export async function findLiveChannel(
  sourceId: string,
  key: string,
): Promise<LiveChannel | null> {
  const { data, error } = await supabaseAdmin
    .from('iptv_channels')
    .select('channel_key, num, name, logo, group_title, url, media_kind')
    .eq('source_id', sourceId)
    .eq('channel_key', key)
    .maybeSingle();
  if (error) throw new Error(error.message);
  return data ? toChannel(data as Row) : null;
}

export async function countLiveChannels(sourceId: string): Promise<number> {
  const { count, error } = await supabaseAdmin
    .from('iptv_channels')
    .select('id', { count: 'exact', head: true })
    .eq('source_id', sourceId);
  if (error) throw new Error(error.message);
  return count ?? 0;
}

export async function clearLiveChannels(sourceId: string): Promise<void> {
  const { error } = await supabaseAdmin.from('iptv_channels').delete().eq('source_id', sourceId);
  if (error) throw new Error(error.message);
}

/** Replaces a provider's whole curated list in one go. */
export async function writeLiveChannels(
  sourceId: string,
  channels: Array<Omit<LiveChannel, 'num'> & { num?: number }>,
): Promise<number> {
  await clearLiveChannels(sourceId);
  const rows = channels
    .filter((c) => c.url && c.name)
    .map((c, i) => ({
      source_id: sourceId,
      channel_key: c.key || String(i + 1),
      num: c.num ?? i + 1,
      name: c.name.slice(0, 300),
      logo: c.logo || null,
      group_title: c.group || 'Uncategorised',
      url: c.url,
      media_kind: c.mediaKind || 'auto',
    }));

  // Written in batches: a full provider list can run to tens of thousands of rows.
  for (let i = 0; i < rows.length; i += 500) {
    const { error } = await supabaseAdmin.from('iptv_channels').insert(rows.slice(i, i + 500));
    if (error) throw new Error(error.message);
  }
  return rows.length;
}

/**
 * Rebuilds the curated list from the provider's stored credentials and relay.
 *
 * MPEG-TS URLs are stored because several providers bind HLS segment tokens to
 * the IP that fetched the playlist, which the relay cannot satisfy.
 */
export async function refreshLiveFromProvider(
  source: Source,
): Promise<{ channelCount: number; groupCount: number }> {
  const streams = await playerApi<
    Array<{ stream_id: number; name: string; stream_icon?: string; num?: number; category_id?: string }>
  >(source, { action: 'get_live_streams' });
  const list = Array.isArray(streams) ? streams : [];
  if (list.length === 0) throw new Error('The provider returned no live channels');

  const categories = await playerApi<Array<{ category_id: string; category_name: string }>>(
    source,
    { action: 'get_live_categories' },
  ).catch(() => []);
  const names = new Map(
    (Array.isArray(categories) ? categories : []).map((c) => [
      String(c.category_id),
      c.category_name,
    ]),
  );

  const channels = list.map((s, i) => ({
    key: String(s.stream_id),
    num: Number(s.num) || i + 1,
    name: s.name,
    logo: s.stream_icon || '',
    group: names.get(String(s.category_id ?? '')) || 'Uncategorised',
    url: liveStreamUrl(source, s.stream_id, 'ts'),
    mediaKind: 'ts',
  }));

  const channelCount = await writeLiveChannels(source.id, channels);
  return { channelCount, groupCount: new Set(channels.map((c) => c.group)).size };
}
