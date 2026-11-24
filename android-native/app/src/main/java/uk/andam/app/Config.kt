package uk.andam.app

object Config {
    /** The Andam web backend: every catalog call and every stream goes through it. */
    const val BASE_URL = "https://ip.andam.uk"

    /** Public (publishable) Supabase values, the same ones the website ships. */
    const val SUPABASE_URL = "https://dexbsvoftwhxhozdbdzf.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_od8kXad3VFniGsK0X61aiQ_drhRaX0T"

    const val OAUTH_CALLBACK = "lovable://oauth-callback"
    const val USER_AGENT = "AndamApp/2.0 (Android; ExoPlayer)"
}
