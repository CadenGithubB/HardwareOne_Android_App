package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * One unsolicited notification card pushed by the device (firmware sink `NSINK_APP`, the
 * companion-app peer of the G2 lens sink). Contract: sec. 9 of the firmware's
 * `docs/APP_JSON_CONTRACT.md`.
 *
 * The device writes a single line on the ordinary command-reply lane:
 *
 *     #NOTIF {"kind":"peer_online","level":"info","title":"Mesh","msg":"Peer online: kitchen","ms":2000}
 *
 * Three properties of that line matter to us:
 *
 *  - **Already filtered, per logged-in user.** Cards only reach an authenticated session,
 *    and only after passing that user's device policy, admin gating, importance floor
 *    (`notifylevel`) and personal mutes (`notifyusermute`). We render what arrives; we do
 *    not re-filter. Corollary: cards belong to whoever was logged in when they arrived, so
 *    they must be dropped on logout.
 *  - **Best-effort.** If the link is busy the device drops the card rather than queueing it
 *    (visible as `app-drop` in `notifstats`). A card is never the only record of anything —
 *    which is why the ViewModel also mirrors every card into the console log.
 *  - **Truncated, not wrapped.** The whole line is capped at 195 bytes (one Secure Channel
 *    frame) and [msg] is escaped into whatever room the other fields leave. Escaping can
 *    expand one byte to six, so a long or escape-heavy body arrives VISIBLY SHORTENED.
 *    Truncation stops on a clean character boundary, so the JSON stays well-formed — but
 *    never assume [msg] is a complete sentence.
 */
data class DeviceNotification(
    /** Stable event-kind name (numbers are firmware-internal). Feeds `notifyusermute`. */
    val kind: String,
    val level: Level,
    /** Event family, for grouping — the same title the G2 lens card uses. */
    val title: String,
    /** One-liner body. May be truncated; see the class docs. */
    val msg: String,
    /** Device's suggested banner duration, verbatim. Clamp at display, not here. */
    val durationMs: Int,
    val receivedAt: Long,
) {
    enum class Level {
        INFO, SUCCESS, WARNING, ERROR;

        companion object {
            /** Unknown/absent levels degrade to [INFO] — new firmware levels must not drop cards. */
            fun from(raw: String?): Level =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: INFO
        }
    }

    companion object {
        /**
         * The sentinel that makes a card distinguishable from reply text. Load-bearing: an
         * off-console capture only *starts* on a fragment beginning with '{', so leading with
         * '#' means a card can never be mistaken for the reply someone is waiting on.
         */
        const val PREFIX = "#NOTIF "

        /** Used when the device omits `ms`. */
        const val DEFAULT_DURATION_MS = 3000

        /**
         * Parse one line. Returns null for anything that isn't a well-formed card, so the
         * caller can let the raw text fall through to the console rather than swallowing it.
         *
         * Deliberately strict about the prefix — the line must *start* with it, with no
         * leading whitespace. Console output that merely contains `#NOTIF ` (a file listing,
         * a `fileread` of something quoting this contract) stays console output.
         */
        fun parse(line: String, nowMs: Long = System.currentTimeMillis()): DeviceNotification? {
            if (!line.startsWith(PREFIX)) return null
            val body = line.substring(PREFIX.length).trim()
            if (!body.startsWith("{")) return null
            return runCatching {
                val o = JSONObject(body)
                val kind = o.optString("kind").trim()
                val msg = o.optString("msg").trim()
                // Nothing to show and nothing to act on — treat as malformed so the raw line
                // reaches the console instead of rendering an empty card.
                if (kind.isEmpty() && msg.isEmpty()) return null
                DeviceNotification(
                    kind = kind,
                    level = Level.from(o.optString("level")),
                    title = o.optString("title").trim().ifEmpty { "Device" },
                    msg = msg,
                    durationMs = o.optInt("ms", DEFAULT_DURATION_MS),
                    receivedAt = nowMs,
                )
            }.getOrNull()
        }

        /** Result of [split]: the cards found, the text left for the console/capture, and how
         *  many `#NOTIF`-prefixed lines were unreadable. */
        data class Split(
            val cards: List<DeviceNotification>,
            val remainder: String,
            val malformed: Int,
        )

        /**
         * Separate notification cards from ordinary reply text.
         *
         * Since the device caps a card at one BLE frame, a card is always exactly one line and
         * the common payload is a single card or none at all. Splitting is defensive: the
         * plaintext receive path hands over a whole GATT notification value, which carries no
         * guarantee of being line-split.
         *
         * A `#NOTIF`-prefixed line we cannot parse is counted but LEFT in the remainder — a
         * malformed card is a bug worth seeing in the console, not something to swallow.
         */
        fun split(text: String, nowMs: Long = System.currentTimeMillis()): Split {
            // Cheap reject for the hot path. Correctness comes from the per-line startsWith
            // below, so console text that merely *contains* the sentinel is untouched.
            if (!text.contains(PREFIX)) return Split(emptyList(), text, 0)

            val cards = mutableListOf<DeviceNotification>()
            val kept = StringBuilder()
            var malformed = 0
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.startsWith(PREFIX)) {
                    val card = parse(line, nowMs)
                    if (card != null) {
                        cards += card
                        continue
                    }
                    malformed++
                }
                if (kept.isNotEmpty()) kept.append('\n')
                kept.append(raw)
            }
            return Split(cards, kept.toString(), malformed)
        }
    }
}
