// SPDX-License-Identifier: Apache-2.0
package app.mixu.spoiler

import app.mixu.graph.Assertion
import app.mixu.graph.AssertionTransition
import app.mixu.graph.Entity
import app.mixu.graph.EntityName
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity

/**
 * Filter decision on whether an item is visible or redacted.
 */
sealed interface RedactionResult<out T> {
    data class Visible<T>(
        val item: T,
    ) : RedactionResult<T>

    data object Redacted : RedactionResult<Nothing>
}

/**
 * Search hit candidate with associated identifier and the reveal ordinal at which
 * the matching disclosure was made.
 */
data class CandidateSearchHit<T>(
    val targetId: String,
    val matchedRevealOrdinal: RevealOrdinal,
    val payload: T,
)

/**
 * Redaction policy evaluating visibility of entities, labels, relations, search hits, and counts.
 * Pure and deterministic: no I/O, clock, locale, or randomness.
 */
object RedactionPolicy {
    /**
     * Evaluates whether an entity is visible at the current horizon.
     * Returns [RedactionResult.Visible] with only revealed names, or [RedactionResult.Redacted]
     * if before horizon.
     */
    fun evaluateEntity(
        entity: Entity,
        names: List<EntityName>,
        horizon: Horizon,
    ): RedactionResult<RevealedEntity> {
        val viewOrdinal = horizon.graphViewPosition.value
        if (entity.firstReveal > viewOrdinal) {
            return RedactionResult.Redacted
        }

        val revealedNames =
            names.filter {
                it.entityId == entity.entityId && it.firstReveal <= viewOrdinal
            }
        val canonicalName =
            revealedNames.find { it.nameId == entity.canonicalNameId }
                ?: revealedNames.minByOrNull { it.firstReveal }

        return RedactionResult.Visible(
            RevealedEntity(
                entity = entity,
                canonicalName = canonicalName,
                allRevealedNames = revealedNames,
                viewOrdinal = viewOrdinal,
            ),
        )
    }

    /**
     * Evaluates whether an assertion/relation is visible and computes its effective epistemic status
     * as of the horizon's view ordinal.
     *
     * Invariants enforced:
     * 1. Assertion firstReveal <= viewOrdinal
     * 2. Subject entity is visible at viewOrdinal
     * 3. Object entity (if Entity ref) is visible at viewOrdinal
     * 4. Transitions later than viewOrdinal are ignored; transitions <= viewOrdinal determine status.
     */
    fun evaluateAssertion(
        assertion: Assertion,
        transitions: List<AssertionTransition>,
        subject: Entity,
        objectEntity: Entity?,
        horizon: Horizon,
    ): RedactionResult<RevealedAssertion> {
        val viewOrdinal = horizon.graphViewPosition.value

        if (assertion.firstReveal > viewOrdinal) {
            return RedactionResult.Redacted
        }

        if (subject.firstReveal > viewOrdinal) {
            return RedactionResult.Redacted
        }

        if (assertion.objectRef is ObjectRef.Entity) {
            if (objectEntity == null || objectEntity.firstReveal > viewOrdinal) {
                return RedactionResult.Redacted
            }
        }

        val validTransitions =
            transitions
                .filter {
                    it.assertionId == assertion.assertionId && it.revealOrdinal <= viewOrdinal
                }.sortedBy { it.revealOrdinal }

        val activeTransition = validTransitions.lastOrNull()
        val currentStatus = activeTransition?.status ?: assertion.epistemicStatus

        return RedactionResult.Visible(
            RevealedAssertion(
                assertion = assertion,
                currentStatus = currentStatus,
                activeTransition = activeTransition,
                viewOrdinal = viewOrdinal,
            ),
        )
    }

    /**
     * Filters search hits so that any match based on unrevealed text is redacted.
     */
    fun <T> filterSearchHits(
        candidates: List<CandidateSearchHit<T>>,
        horizon: Horizon,
    ): List<T> {
        val viewOrdinal = horizon.graphViewPosition
        return candidates
            .filter { it.matchedRevealOrdinal <= viewOrdinal }
            .map { it.payload }
    }

    /**
     * Computes the visible count of elements without leaking total pack size.
     */
    fun countVisible(
        ordinals: Iterable<Int>,
        horizon: Horizon,
    ): Int {
        val viewOrdinal = horizon.graphViewPosition.value
        return ordinals.count { it <= viewOrdinal }
    }
}
