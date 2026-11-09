/*
 * EggPlay: local, privacy-friendly "For you" ranking for the Home feed.
 * Everything is computed on the device from the user's own history; nothing is sent anywhere.
 */

package org.schabi.newpipe.fragments.list.kiosk

import android.content.Context
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

object HomeRecommender {

    private const val PREFS = "eggplay_home_reco"
    private const val KEY_IMPRESSIONS = "impressions"
    private const val MAX_IMPRESSIONS = 400
    private const val PROFILE_TTL_MS = 60_000L
    private const val HISTORY_LIMIT = 300
    private const val HALF_LIFE_DAYS = 14.0

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "this", "that", "video", "official", "full",
        "yang", "dan", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "atau", "pada",
        "live", "part", "episode", "eps", "ep", "hd", "4k", "new", "baru", "terbaru"
    )

    private class Profile(
        val channelAffinity: Map<String, Double>,
        val keywordAffinity: Map<String, Double>,
        val searchTokens: Set<String>,
        val subscribedChannels: Set<String>,
        val finishedUrls: Set<String>,
        val resumeUrls: Set<String>,
        val watchedUrls: Set<String>
    ) {
        val isEmpty get() = channelAffinity.isEmpty() && subscribedChannels.isEmpty() &&
            searchTokens.isEmpty()
    }

    @Volatile private var cachedProfile: Profile? = null

    @Volatile private var cachedAt = 0L

    /** Live streams are shown only in the Live tab, never in the Home feed. */
    @JvmStatic
    fun isLive(item: StreamInfoItem): Boolean {
        return item.streamType == StreamType.LIVE_STREAM
            || item.streamType == StreamType.AUDIO_LIVE_STREAM
    }

    @JvmStatic
    fun withoutLive(items: List<StreamInfoItem>): List<StreamInfoItem> {
        return items.filterNot { isLive(it) }
    }

    /** Call when the user watched something so the next Home load reflects it immediately. */
    @JvmStatic
    fun invalidateProfile() {
        cachedAt = 0L
    }

    /**
     * Re-ranks [items] (already without live streams). Must be called off the main thread
     * because it reads the local database.
     *
     * @param firstPage true for the first page of a (re)load: counts impressions so videos the
     *   user keeps scrolling past are shown less often, like YouTube's "not interested" signal.
     */
    @JvmStatic
    fun rank(
        context: Context,
        items: List<StreamInfoItem>,
        firstPage: Boolean
    ): List<StreamInfoItem> {
        if (items.size < 2) return items
        val profile = try {
            loadProfile(context.applicationContext)
        } catch (e: Exception) {
            return items // never break the feed because of the recommender
        }
        val impressions = readImpressions(context)
        val rnd = Random(System.nanoTime())

        class Scored(val item: StreamInfoItem, var score: Double)

        val scored = items.mapIndexed { index, item ->
            var s = 1.0 / (1.0 + 0.05 * index) // trust the source's own order a bit
            val channel = item.uploaderUrl
            if (channel != null) {
                if (channel in profile.subscribedChannels) s += 1.2
                s += min(1.6, profile.channelAffinity[channel] ?: 0.0)
            }
            val tokens = tokenize(item.name)
            if (tokens.isNotEmpty()) {
                s += min(1.2, tokens.sumOf { profile.keywordAffinity[it] ?: 0.0 })
                s += min(0.8, 0.35 * tokens.count { it in profile.searchTokens })
            }
            val url = item.url
            when {
                url in profile.finishedUrls -> s -= 3.0 // already watched

                url in profile.resumeUrls -> s += 0.6 // started but not finished

                url in profile.watchedUrls -> s -= 0.8
            }
            s -= min(1.5, 0.5 * (impressions[url] ?: 0)) // seen before, not clicked
            s += (rnd.nextDouble() - 0.5) * 0.3 // small exploration noise
            Scored(item, s)
        }

        // Greedy pick with a diversity penalty so one channel does not fill the screen.
        val remaining = scored.toMutableList()
        val result = ArrayList<StreamInfoItem>(items.size)
        val recent = ArrayDeque<String>()
        while (remaining.isNotEmpty()) {
            var best = remaining[0]
            var bestValue = Double.NEGATIVE_INFINITY
            for (c in remaining) {
                val same = recent.count { it == c.item.uploaderUrl }
                val value = c.score - 0.7 * same
                if (value > bestValue) {
                    bestValue = value
                    best = c
                }
            }
            remaining.remove(best)
            result.add(best.item)
            recent.addLast(best.item.uploaderUrl ?: "")
            if (recent.size > 4) recent.removeFirst()
        }

        if (firstPage) {
            recordImpressions(context, result.take(12).map { it.url })
        }
        return result
    }

    private fun loadProfile(context: Context): Profile {
        val now = System.currentTimeMillis()
        cachedProfile?.let { if (now - cachedAt < PROFILE_TTL_MS) return it }

        val db = NewPipeDatabase.getInstance(context)
        val history = db.streamHistoryDAO().getStatistics().blockingFirst()
            .sortedByDescending { it.latestAccessDate }
            .take(HISTORY_LIMIT)

        val channelRaw = HashMap<String, Double>()
        val keywordRaw = HashMap<String, Double>()
        val finished = HashSet<String>()
        val resume = HashSet<String>()
        val watched = HashSet<String>()
        val nowDate = OffsetDateTime.now()

        for (e in history) {
            val st = e.streamEntity
            val days = max(0L, ChronoUnit.DAYS.between(e.latestAccessDate, nowDate)).toDouble()
            val recency = exp(-ln(2.0) * days / HALF_LIFE_DAYS)
            val durationMs = st.duration * 1000
            val completion = if (durationMs > 0) {
                (e.progressMillis.toDouble() / durationMs).coerceIn(0.0, 1.0)
            } else {
                0.5
            }
            // watching a long part of a video is a stronger "like" than a quick click-away
            val engagement = 0.4 + 0.6 * completion
            val weight = recency * engagement * (1.0 + ln(max(1L, e.watchCount).toDouble()))

            st.uploaderUrl?.let { channelRaw.merge(it, weight, { a, b -> a + b }) }
            tokenize(st.title).forEach { keywordRaw.merge(it, weight, { a, b -> a + b }) }

            watched.add(st.url)
            if (durationMs > 0 && (completion >= 0.9 || durationMs - e.progressMillis < 60_000)) {
                finished.add(st.url)
            } else if (completion in 0.05..0.9) {
                resume.add(st.url)
            }
        }

        val searches = try {
            db.searchHistoryDAO().getUniqueEntries(30).blockingFirst()
        } catch (e: Exception) {
            emptyList<String>()
        }
        val subs = try {
            db.subscriptionDAO().getAll().blockingFirst().mapNotNull { it.url }.toSet()
        } catch (e: Exception) {
            emptySet<String>()
        }

        val profile = Profile(
            channelAffinity = normalize(channelRaw, 1.6),
            keywordAffinity = normalize(keywordRaw, 0.5),
            searchTokens = searches.flatMap { tokenize(it) }.toSet(),
            subscribedChannels = subs,
            finishedUrls = finished,
            resumeUrls = resume,
            watchedUrls = watched
        )
        cachedProfile = profile
        cachedAt = now
        return profile
    }

    /** Scales values so the strongest entry equals [maxValue]. */
    private fun normalize(raw: Map<String, Double>, maxValue: Double): Map<String, Double> {
        val top = raw.values.maxOrNull() ?: return emptyMap()
        if (top <= 0.0) return emptyMap()
        return raw.mapValues { it.value / top * maxValue }
    }

    private fun tokenize(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS && !it.all(Char::isDigit) }
            .toSet()
    }

    // region impressions (persisted, small LRU)

    private fun readImpressions(context: Context): Map<String, Int> {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IMPRESSIONS, emptySet()) ?: emptySet()
        val map = HashMap<String, Int>()
        for (entry in set) {
            val i = entry.lastIndexOf('|')
            if (i > 0) map[entry.substring(0, i)] = entry.substring(i + 1).toIntOrNull() ?: 0
        }
        return map
    }

    @Synchronized
    private fun recordImpressions(context: Context, urls: List<String>) {
        val map = LinkedHashMap(readImpressions(context))
        for (u in urls) map[u] = (map[u] ?: 0) + 1
        // forget the oldest entries so the store stays small
        while (map.size > MAX_IMPRESSIONS) map.remove(map.keys.first())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_IMPRESSIONS, map.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    // endregion
}
