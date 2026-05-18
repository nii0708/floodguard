package com.floodguard.rescue.memory

import kotlin.math.sqrt

/**
 * Thread-safe spatial + semantic store for observed landmarks.
 *
 * Proximity threshold: landmarks within [POSITION_RADIUS_M] metres AND
 * whose keyword sets share more than [SIMILARITY_THRESHOLD] Jaccard
 * similarity are considered the same physical location → revisit detected.
 */
class SpatialMemory {

    private val landmarks = mutableListOf<LandmarkRecord>()
    private val lock = Any()

    fun addLandmark(record: LandmarkRecord) = synchronized(lock) {
        landmarks.add(record)
    }

    /**
     * Returns the existing landmark if [position] + [description] match a
     * previously stored entry, otherwise null (= new area).
     */
    fun findRevisit(position: FloatArray, description: String): LandmarkRecord? =
        synchronized(lock) {
            val incomingKeywords = LandmarkRecord.extractKeywords(description)
            landmarks.firstOrNull { stored ->
                distance(stored.position, position) < POSITION_RADIUS_M &&
                        jaccardSimilarity(stored.keywords, incomingKeywords) >= SIMILARITY_THRESHOLD
            }
        }

    fun getAllLandmarks(): List<LandmarkRecord> = synchronized(lock) { landmarks.toList() }

    fun clear() = synchronized(lock) { landmarks.clear() }

    fun size(): Int = synchronized(lock) { landmarks.size }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val intersection = (a intersect b).size.toFloat()
        val union = (a union b).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }

    companion object {
        private const val POSITION_RADIUS_M = 1.8f   // metres
        private const val SIMILARITY_THRESHOLD = 0.35f
    }
}
