// SPDX-License-Identifier: Apache-2.0
package app.mixu.spoiler

import app.mixu.graph.isDisclosedAt
import kotlin.jvm.JvmInline

/**
 * Unique identifier for an edition (e.g. edition fingerprint or hash).
 * Editions are isolated; ordinals cannot be compared across different editions.
 */
@JvmInline
value class EditionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "EditionId cannot be blank" }
    }
}

/**
 * Monotonic narrative disclosure ordinal within an edition.
 */
@JvmInline
value class RevealOrdinal(
    val value: Int,
) : Comparable<RevealOrdinal> {
    init {
        require(value >= 0) { "RevealOrdinal must be non-negative, got: $value" }
    }

    override fun compareTo(other: RevealOrdinal): Int = value.compareTo(other.value)
}

/**
 * Stable, content-free deny reasons.
 */
enum class DenyReason {
    BEYOND_HORIZON,
    UNKNOWN_EDITION,
    MALFORMED_REGION,
    OVERLAY_HIDDEN,
    SCHEMA_UNSUPPORTED,
}

/**
 * Result of knowledge-horizon evaluation.
 */
sealed interface SpoilerDecision {
    data object Allow : SpoilerDecision

    data class Deny(
        val reason: DenyReason,
    ) : SpoilerDecision
}

/**
 * Reading horizon algebra maintaining reader position, unlocked knowledge horizon,
 * and historical graph view position.
 */
data class Horizon(
    val editionId: EditionId,
    val readingPosition: RevealOrdinal,
    val knowledgeHorizon: RevealOrdinal,
    val graphViewPosition: RevealOrdinal = knowledgeHorizon,
    val viewCharOffset: Int? = null,
) {
    init {
        require(graphViewPosition <= knowledgeHorizon) {
            "graphViewPosition ($graphViewPosition) cannot exceed knowledgeHorizon ($knowledgeHorizon)"
        }
        if (viewCharOffset != null) {
            require(viewCharOffset >= 0) {
                "viewCharOffset must be non-negative, got: $viewCharOffset"
            }
        }
    }

    companion object {
        fun initial(
            editionId: EditionId,
            initialOrdinal: RevealOrdinal = RevealOrdinal(0),
            viewCharOffset: Int? = null,
        ): Horizon =
            Horizon(
                editionId = editionId,
                readingPosition = initialOrdinal,
                knowledgeHorizon = initialOrdinal,
                graphViewPosition = initialOrdinal,
                viewCharOffset = viewCharOffset,
            )
    }

    /**
     * Explicitly advance the knowledge horizon forward.
     * Enforces monotonicity: the new horizon must be >= current knowledgeHorizon.
     */
    fun advanceKnowledgeHorizon(newHorizon: RevealOrdinal): Horizon {
        require(newHorizon >= knowledgeHorizon) {
            "Knowledge horizon must advance monotonically. Cannot regress from $knowledgeHorizon to $newHorizon"
        }
        return copy(
            knowledgeHorizon = newHorizon,
            readingPosition = maxOf(readingPosition, newHorizon),
            graphViewPosition =
                if (graphViewPosition ==
                    knowledgeHorizon
                ) {
                    newHorizon
                } else {
                    graphViewPosition
                },
        )
    }

    /**
     * Move reading position forward or backward without automatically altering the knowledge horizon.
     */
    fun navigateReadingPosition(newPosition: RevealOrdinal): Horizon =
        copy(readingPosition = newPosition)

    /**
     * Explicit backward jump for reader navigation.
     */
    fun rewindReadingPosition(newPosition: RevealOrdinal): Horizon {
        require(newPosition <= readingPosition) {
            "rewindReadingPosition expects position <= current readingPosition ($readingPosition), got: $newPosition"
        }
        return copy(readingPosition = newPosition)
    }

    /**
     * Explicit rewind of historical graph view position, bounded by knowledge horizon.
     */
    fun rewindGraphView(newViewPosition: RevealOrdinal): Horizon {
        require(newViewPosition <= graphViewPosition) {
            "rewindGraphView expects view position <= current graphViewPosition ($graphViewPosition), got: $newViewPosition"
        }
        return copy(graphViewPosition = newViewPosition)
    }

    /**
     * Explicit forward advance of graph view position, bounded by knowledge horizon.
     */
    fun advanceGraphView(newViewPosition: RevealOrdinal): Horizon {
        require(newViewPosition <= knowledgeHorizon) {
            "graphViewPosition cannot be advanced beyond knowledgeHorizon ($knowledgeHorizon), requested: $newViewPosition"
        }
        require(newViewPosition >= graphViewPosition) {
            "advanceGraphView expects view position >= current graphViewPosition ($graphViewPosition), got: $newViewPosition"
        }
        return copy(graphViewPosition = newViewPosition)
    }

    /**
     * Returns a copy with an explicit intra-ordinal character ceiling for INV-7 gating.
     */
    fun withViewCharOffset(offset: Int?): Horizon = copy(viewCharOffset = offset)

    /**
     * Checks whether a disclosure at ([itemOrdinal], [itemCharEnd]) is visible at the current
     * graph view cursor ([graphViewPosition], [viewCharOffset]).
     */
    fun isDisclosedAtView(
        itemOrdinal: Int,
        itemCharEnd: Int? = null,
    ): Boolean = isDisclosedAt(itemOrdinal, itemCharEnd, graphViewPosition.value, viewCharOffset)

    /**
     * Decides whether candidate information at ([targetReveal], [targetCharEnd]) within
     * [targetEdition] is permitted under the unlocked knowledge horizon.
     */
    fun evaluate(
        targetReveal: RevealOrdinal,
        targetEdition: EditionId,
        targetCharEnd: Int? = null,
    ): SpoilerDecision {
        if (targetEdition != editionId) {
            return SpoilerDecision.Deny(DenyReason.UNKNOWN_EDITION)
        }
        val permitted =
            isDisclosedAt(
                targetReveal.value,
                targetCharEnd,
                knowledgeHorizon.value,
                viewCharOffset,
            )
        if (!permitted) {
            return SpoilerDecision.Deny(DenyReason.BEYOND_HORIZON)
        }
        return SpoilerDecision.Allow
    }

    /**
     * Returns true if candidate information is permitted at current knowledge horizon.
     */
    fun isPermitted(
        targetReveal: RevealOrdinal,
        targetEdition: EditionId,
        targetCharEnd: Int? = null,
    ): Boolean = evaluate(targetReveal, targetEdition, targetCharEnd) is SpoilerDecision.Allow
}
