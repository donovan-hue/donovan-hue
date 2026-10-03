package com.hifiplayer.presentation.library.artwork

/**
 * Decoded covers, kept under a memory budget (requirement 36).
 *
 * The budget is in *bytes*, not in entries, and that matters: a 44 px list cover and the 320 px
 * artwork of Now Playing differ by a factor of fifty. Counting entries would let a shelf of large
 * covers take hundreds of megabytes while a list of small ones used a fraction of a megabyte.
 *
 * It is a plain class with no Android types so the eviction rules are unit-tested rather than
 * trusted: [sizeOf] is supplied by the caller (bitmap width × height × 4 bytes in the real one).
 */
class ArtworkCache<V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {

    /** Access-ordered map: the least recently *used* entry is the first to go. */
    private val entries = LinkedHashMap<String, V>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private var bytes = 0L

    val sizeBytes: Long get() = bytes

    val count: Int get() = entries.size

    fun get(key: String): V? = entries[key]

    fun put(key: String, value: V) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        entries[key] = value
        bytes += sizeOf(value)
        evict()
    }

    fun clear() {
        entries.clear()
        bytes = 0L
    }

    /**
     * Drops the least recently used entries until the budget fits.
     *
     * A single entry larger than the whole budget is dropped too: keeping it would mean never being
     * able to hold anything else, which is worse than showing one empty cover.
     */
    private fun evict() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
