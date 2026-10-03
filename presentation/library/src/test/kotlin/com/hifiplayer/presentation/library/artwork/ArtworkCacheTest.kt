package com.hifiplayer.presentation.library.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The cover cache has to be bounded in bytes, not in entries (requirement 36).
 *
 * What these tests protect: the budget is respected, dropping a cover never leaves the accounting
 * wrong, and touching a cover keeps it (the LRU rule that makes scrolling back up free).
 */
class ArtworkCacheTest {

    /** Every value "weighs" what it says it weighs, so the assertions are readable. */
    private fun cache(maxBytes: Long) = ArtworkCache<Int>(maxBytes) { it.toLong() }

    @Test
    fun `it keeps what fits in the budget`() {
        val cache = cache(maxBytes = 300)

        cache.put("a", 100)
        cache.put("b", 150)

        assertThat(cache.sizeBytes).isEqualTo(250)
        assertThat(cache.get("a")).isEqualTo(100)
        assertThat(cache.get("b")).isEqualTo(150)
    }

    @Test
    fun `the oldest cover leaves when the budget is exceeded`() {
        val cache = cache(maxBytes = 300)

        cache.put("a", 100)
        cache.put("b", 100)
        cache.put("c", 150) // 350 > 300, so "a" has to go

        assertThat(cache.count).isEqualTo(2)
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isEqualTo(100)
        assertThat(cache.get("c")).isEqualTo(150)
        assertThat(cache.sizeBytes).isEqualTo(250)
    }

    @Test
    fun `a cover that was just used is the last one to go`() {
        val cache = cache(maxBytes = 300)
        cache.put("a", 100)
        cache.put("b", 100)
        cache.put("c", 100)

        // Using "a" makes it the most recent, so "b" is now the oldest.
        assertThat(cache.get("a")).isEqualTo(100)
        cache.put("d", 100)

        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isEqualTo(100)
        assertThat(cache.get("c")).isEqualTo(100)
        assertThat(cache.get("d")).isEqualTo(100)
    }

    @Test
    fun `replacing a cover does not double-count it`() {
        val cache = cache(maxBytes = 300)

        cache.put("a", 100)
        cache.put("a", 200)

        assertThat(cache.count).isEqualTo(1)
        assertThat(cache.sizeBytes).isEqualTo(200)
        assertThat(cache.get("a")).isEqualTo(200)
    }

    @Test
    fun `a cover bigger than the whole budget is dropped instead of freezing the cache`() {
        val cache = cache(maxBytes = 100)

        cache.put("huge", 500)

        assertThat(cache.get("huge")).isNull()
        assertThat(cache.sizeBytes).isEqualTo(0)
        // And the cache still works afterwards.
        cache.put("small", 80)
        assertThat(cache.get("small")).isEqualTo(80)
    }

    @Test
    fun `clearing empties both the entries and the accounting`() {
        val cache = cache(maxBytes = 300)
        cache.put("a", 100)

        cache.clear()

        assertThat(cache.count).isEqualTo(0)
        assertThat(cache.sizeBytes).isEqualTo(0)
    }
}
