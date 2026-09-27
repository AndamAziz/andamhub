import { useCallback, useEffect, useRef } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";

import { supabase } from "@/integrations/supabase/client";
import { syncMyAccount } from "@/lib/account.functions";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Andam — Live TV, Movies & Shows" },
      {
        name: "description",
        content:
          "Andam streaming home: live matches, trending movies, popular shows, IPTV channels, prayer times and Quran.",
      },
      { property: "og:title", content: "Andam — Live TV, Movies & Shows" },
      {
        property: "og:description",
        content:
          "Live matches, trending movies, popular shows and your IPTV channels in one cinematic home.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Index,
});

function useSessionBridge(frame: React.RefObject<HTMLIFrameElement | null>) {
  const { data } = useQuery({
    queryKey: ["home-session"],
    queryFn: async () => {
      const { data: sessionData } = await supabase.auth.getSession();
      const session = sessionData.session;
      if (!session?.user) return { signedIn: false, role: "guest" as string, token: null };
      // Send the token we already hold, so a race in the global attacher can't drop it;
      // a failed sync (stale session) must never blank the homepage.
      const account = await syncMyAccount({
        data: { recordLogin: false },
        headers: { Authorization: `Bearer ${session.access_token}` },
      }).catch((err) => {
        console.warn("[home] account sync failed", err);
        return null;
      });
      // Same-origin iframe only: the token lets the homepage read this user's
      // own watch history and any privately granted providers.
      return { signedIn: true, role: account?.role ?? "user", token: session.access_token };
    },
    staleTime: 60_000,
  });

  const post = useCallback(() => {
    const win = frame.current?.contentWindow;
    if (!win || !data) return;
    win.postMessage({ type: "andam:session", ...data }, window.location.origin);
  }, [data, frame]);

  useEffect(() => {
    post();
    const onMessage = (e: MessageEvent) => {
      if ((e.data as { type?: string } | null)?.type === "andam:ready") post();
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [post]);
}

function Index() {
  const frame = useRef<HTMLIFrameElement>(null);
  useSessionBridge(frame);

  return (
    <>
      <iframe
      ref={frame}
      src="/andam.html"
      title="Andam streaming homepage"
      style={{
        position: "fixed",
        inset: 0,
        width: "100%",
        height: "100%",
        border: 0,
        background: "#08090C",
        }}
      />
    </>
  );
}
