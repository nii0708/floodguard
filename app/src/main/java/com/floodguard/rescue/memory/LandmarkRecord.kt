package com.floodguard.rescue.memory

/**
 * A single semantically-meaningful location observed by Gemma.
 *
 * [position] is in local map-space metres: [x, y, z].
 * [keywords] is a pre-computed set of significant nouns/adjectives extracted
 * from [description] so that similarity checks avoid re-running the LLM.
 */
data class LandmarkRecord(
    val id: String,
    val description: String,
    val position: FloatArray,
    val timestamp: Long,
    val keywords: Set<String> = extractKeywords(description)
) {
    override fun equals(other: Any?): Boolean =
        other is LandmarkRecord && id == other.id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "this", "that",
            "with", "in", "on", "at", "to", "of", "and", "or", "it", "its"
        )

        fun extractKeywords(text: String): Set<String> =
            text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 3 && it !in STOP_WORDS }
                .toSet()
    }
}
