package uk.andam.app.ui

import java.text.Normalizer

/**
 * Smarter on-device search for films, series and channels (the same ideas as the website's
 * Movies / Shows search):
 *  - case, accents and punctuation do not matter; Arabic / Persian / Kurdish letter forms are
 *    unified (ي/ی, ك/ک, ة/ه, أ/إ/آ/ا, ۆ/و, ێ/ی …) and tashkeel / tatweel are ignored;
 *  - the words may come in any order ("man iron" finds "Iron Man");
 *  - a year in the query ("batman 2022") keeps titles from that year;
 *  - best matches first: exact, then starts with, then every word at a word start,
 *    then every word anywhere, then extra fields (genre, group);
 *  - nothing found that way: titles that read like the query (typos: "intersteller", "spidrman").
 */
object SmartSearch {
    private val MARKS = Regex("[\\p{Mn}\\u0640]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val YEAR_END = Regex("^(.*?)[\\s,(]*\\(?((?:19|20)\\d{2})\\)?$")
    private val YEAR_START = Regex("^\\(?((?:19|20)\\d{2})\\)?\\s+(.+)$")
    private val LETTERS = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ێ' to 'ی', 'ك' to 'ک', 'ة' to 'ه', 'ە' to 'ه', 'ۀ' to 'ه',
        'أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا', 'ؤ' to 'و', 'ۆ' to 'و', 'ۇ' to 'و',
        'ڕ' to 'ر', 'ڵ' to 'ل', 'ڤ' to 'ف',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4', '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
    )

    /** lower case, accents / tashkeel removed, letter forms unified, punctuation -> spaces */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "").lowercase()
        val sb = StringBuilder(n.length)
        // ئ / ء only carry a vowel at a word start in Kurdish; spellings drop them freely.
        for (c in n) if (c != 'ئ' && c != 'ء') sb.append(LETTERS[c] ?: c)
        return sb.toString().replace("&", " and ").replace(NON_WORD, " ").trim()
    }

    data class Query(val text: String, val words: List<String>, val year: String?)

    fun parse(raw: String): Query {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        var text = t
        var year: String? = null
        YEAR_END.find(t)?.let { m -> if (m.groupValues[1].isNotBlank()) { text = m.groupValues[1]; year = m.groupValues[2] } }
        if (year == null) YEAR_START.find(t)?.let { m -> if (m.groupValues[2].isNotBlank()) { text = m.groupValues[2]; year = m.groupValues[1] } }
        val f = fold(text)
        return Query(f, f.split(' ').filter { it.isNotEmpty() }, year)
    }

    /** Letter pairs of a folded string as ints (a space is added at both ends). */
    private fun pairs(s: String): IntArray {
        val t = " $s "
        return IntArray(t.length - 1) { (t[it].code shl 16) or t[it + 1].code }
    }

    /** Letter-pair similarity, 0..1 (Dice coefficient), of two pair lists. */
    private fun dice(x: IntArray, y: IntArray): Double {
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val a = x.copyOf().also { it.sort() }
        val b = y.copyOf().also { it.sort() }
        var i = 0; var j = 0; var hit = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { hit++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return 2.0 * hit / (a.size + b.size)
    }

    fun similarity(a: String, b: String): Double = dice(pairs(a), pairs(b))

    private fun stripThe(s: String) = s.removePrefix("the ")

    /**
     * A list prepared once for searching (titles folded up front), so each keystroke only compares.
     * [title] is the name, [year] the item's year (may be blank), [extra] other searchable text.
     */
    class Index(
        val size: Int,
        title: (Int) -> String,
        private val year: (Int) -> String = { "" },
        private val extra: (Int) -> String = { "" },
    ) {
        private val folded = Array(size) { fold(title(it)) }
        private val words = Array(size) { folded[it].split(' ').filter { w -> w.isNotEmpty() } }

        private fun yearOk(i: Int, y: String?): Boolean {
            if (y == null) return true
            val own = year(i)
            return if (own.isNotBlank()) own.startsWith(y) else folded[i].contains(y)
        }

        /** Indices that match [raw], best first (original order inside a tier). */
        fun rank(raw: String): List<Int> {
            val q = parse(raw)
            if (q.words.isEmpty() && q.year == null) return (0 until size).toList()
            val text = stripThe(q.text)
            val tiers = Array(5) { ArrayList<Int>() }
            for (i in 0 until size) {
                if (!yearOk(i, q.year)) continue
                if (q.words.isEmpty()) { tiers[0].add(i); continue }
                val t = folded[i]
                val bare = stripThe(t)
                val tw = words[i]
                val tier = when {
                    bare == text -> 0
                    bare.startsWith(text) -> 1
                    q.words.all { w -> tw.any { it.startsWith(w) } } -> 2
                    q.words.all { w -> t.contains(w) } -> 3
                    else -> {
                        val more = fold(extra(i))
                        if (more.isNotEmpty() && q.words.all { w -> more.contains(w) || t.contains(w) }) 4 else -1
                    }
                }
                if (tier >= 0) tiers[tier].add(i)
            }
            val direct = tiers.flatMap { it }
            if (direct.isNotEmpty() || q.words.isEmpty()) return direct

            // Typos: every query word close to some title word, the whole title close to the query,
            // or the query run together close to the title's start ("spidrman" → "Spider-Man …").
            val qp = pairs(text)
            val qWords = q.words.map { pairs(it) }
            val joined = text.replace(" ", "")
            val jp = pairs(joined)
            val scored = ArrayList<Pair<Int, Double>>()
            for (i in 0 until size) {
                if (!yearOk(i, q.year)) continue
                val t = folded[i]
                val whole = dice(pairs(stripThe(t)), qp)
                val tw = words[i].map { pairs(it) }
                val perWord = qWords.map { w -> tw.maxOfOrNull { dice(it, w) } ?: 0.0 }
                val byWord = if (perWord.all { it >= 0.6 }) perWord.average() else 0.0
                val head = stripThe(t).replace(" ", "").take(joined.length + 1)
                val start = if (joined.length >= 5) dice(pairs(head), jp) else 0.0
                val score = maxOf(whole, byWord, start)
                if (score >= 0.6) scored.add(i to score)
            }
            return scored.sortedByDescending { it.second }.map { it.first }
        }
    }

    /** One-off search without keeping an index. */
    fun rank(
        size: Int,
        raw: String,
        title: (Int) -> String,
        year: (Int) -> String = { "" },
        extra: (Int) -> String = { "" },
    ): List<Int> = Index(size, title, year, extra).rank(raw)
}
