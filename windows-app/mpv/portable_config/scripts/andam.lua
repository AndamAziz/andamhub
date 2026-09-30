-- Andam Player: makes the on-screen ⏮ ⏭ buttons (and playlist keys) change channel / episode.
-- The Andam window loads every stream as: [andam://prev, <stream>, andam://next].
-- Reaching one of the markers tells the Andam window what to do, then stops so it can load
-- the next stream. A marker reached because the stream ended means "ended", not "next".
local last_reason = ""

mp.register_event("end-file", function(e)
    last_reason = e.reason or ""
end)

mp.add_hook("on_load", 10, function()
    local f = mp.get_property("stream-open-filename", "")
    if f ~= "andam://prev" and f ~= "andam://next" then return end
    local msg = nil
    if last_reason == "eof" then
        msg = "andam-ended"          -- the stream finished (episode over / live feed closed)
    elseif last_reason == "error" then
        msg = nil                    -- a failed stream is handled by the Andam window, never skipped
    elseif f == "andam://prev" then
        msg = "andam-prev"
    else
        msg = "andam-next"
    end
    last_reason = ""
    if msg then mp.commandv("script-message", msg) end
    mp.command("stop")
end)
