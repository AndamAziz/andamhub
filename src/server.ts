import "./lib/error-capture";

import { consumeLastCapturedError } from "./lib/error-capture";
import { renderErrorPage } from "./lib/error-page";

type ServerEntry = {
  fetch: (request: Request, env: unknown, ctx: unknown) => Promise<Response> | Response;
};

// Keep the loaded module, never a shared promise: a Worker request cannot wait on a promise
// another request created (handle_cross_request_promise_resolution).
let serverEntry: ServerEntry | undefined;

async function getServerEntry(): Promise<ServerEntry> {
  if (serverEntry) return serverEntry;
  const m = await import("@tanstack/react-start/server-entry");
  serverEntry = (m.default ?? m) as ServerEntry;
  return serverEntry;
}

// h3 swallows in-handler throws into a normal 500 Response with body
// {"unhandled":true,"message":"HTTPError"} — try/catch alone never fires for those.
async function normalizeCatastrophicSsrResponse(response: Response): Promise<Response> {
  if (response.status < 500) return response;
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) return response;

  const body = await response.clone().text();
  if (!isH3SwallowedErrorBody(body)) return response;

  console.error(consumeLastCapturedError() ?? new Error(`h3 swallowed SSR error: ${body}`));
  return new Response(renderErrorPage(), {
    status: 500,
    headers: { "content-type": "text/html; charset=utf-8" },
  });
}

function isH3SwallowedErrorBody(body: string): boolean {
  try {
    const payload = JSON.parse(body) as { unhandled?: unknown; message?: unknown };
    return payload.unhandled === true && payload.message === "HTTPError";
  } catch {
    return false;
  }
}

/**
 * Google sign-in for the apps. Installed Android builds open
 * /~oauth/initiate?provider=google&redirect_uri=lovable://oauth-callback&state=… (the path the
 * Lovable broker used to serve). Hand that straight to Supabase Auth: it signs the user in with
 * Google and returns to redirect_uri with the session in the URL fragment, and the state rides
 * along in the query so the app can verify the answer. Supabase only returns to URLs on the
 * project's Redirect URLs list.
 */
const OAUTH_PROVIDERS = new Set(["google", "apple"]);
const OAUTH_RETURN = /^(lovable:\/\/oauth-callback|https:\/\/ip\.andam\.uk(\/|$))/;

function oauthInitiate(url: URL): Response {
  const provider = url.searchParams.get("provider") ?? "";
  const back = url.searchParams.get("redirect_uri") ?? "";
  const state = url.searchParams.get("state") ?? "";
  if (!OAUTH_PROVIDERS.has(provider) || !OAUTH_RETURN.test(back)) {
    return new Response("Unsupported sign-in request", { status: 400 });
  }
  const returnTo = new URL(back);
  if (state) returnTo.searchParams.set("state", state);
  const authorize = new URL(`${import.meta.env['VITE_SUPABASE_URL']}/auth/v1/authorize`);
  authorize.searchParams.set("provider", provider);
  authorize.searchParams.set("redirect_to", returnTo.toString());
  return new Response(null, {
    status: 302,
    headers: { Location: authorize.toString(), "Cache-Control": "no-store" },
  });
}

export default {
  async fetch(request: Request, env: unknown, ctx: unknown) {
    const url = new URL(request.url);
    if (url.pathname === "/~oauth/initiate") return oauthInitiate(url);
    try {
      const handler = await getServerEntry();
      const response = await handler.fetch(request, env, ctx);
      return await normalizeCatastrophicSsrResponse(response);
    } catch (error) {
      console.error(error);
      return new Response(renderErrorPage(), {
        status: 500,
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }
  },
};
