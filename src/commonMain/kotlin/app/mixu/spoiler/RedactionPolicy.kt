// SPDX-License-Identifier: Apache-2.0
package app.mixu.spoiler

import app.mixu.graph.Assertion
import app.mixu.graph.AssertionTransition
import app.mixu.graph.Entity
import app.mixu.graph.EntityId
import app.mixu.graph.EntityName
import app.mixu.graph.GraphPack
import app.mixu.graph.IdentityLink
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import app.mixu.graph.RevealedGraphView

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
        speaker: Entity? = null,
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

        if (assertion.attribution.speakerId != null) {
            val resolvedSpeaker =
                if (assertion.attribution.speakerId == subject.entityId) {
                    subject
                } else {
                    speaker
                }
            if (resolvedSpeaker == null || resolvedSpeaker.firstReveal > viewOrdinal) {
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
     * Evaluates whether an identity link is visible at the current horizon.
     */
    fun evaluateIdentityLink(
        link: IdentityLink,
        horizon: Horizon,
    ): RedactionResult<IdentityLink> {
        val viewOrdinal = horizon.graphViewPosition.value
        if (link.firstReveal > viewOrdinal) {
            return RedactionResult.Redacted
        }
        return RedactionResult.Visible(link)
    }

    /**
     * Resolves the canonical entity ID among linked personas at the current horizon.
     * Before the link's firstReveal ordinal, the entities remain isolated and each resolves to itself.
     */
    fun resolveCanonicalEntityId(
        entityId: EntityId,
        links: List<IdentityLink>,
        horizon: Horizon,
    ): EntityId {
        val viewOrdinal = horizon.graphViewPosition.value
        val validLinks =
            links.filter {
                it.firstReveal <= viewOrdinal && it.entityIds.contains(entityId)
            }
        if (validLinks.isEmpty()) return entityId

        val connected = mutableSetOf(entityId)
        val queue = ArrayDeque<EntityId>()
        queue.add(entityId)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            for (link in links) {
                if (link.firstReveal <= viewOrdinal && link.entityIds.contains(curr)) {
                    for (other in link.entityIds) {
                        if (connected.add(other)) {
                            queue.add(other)
                        }
                    }
                }
            }
        }
        return connected.minByOrNull { it.value } ?: entityId
    }

    /**
     * Evaluates a full GraphPack under the given [Horizon], enforcing all spoiler firewall invariants.
     */
    fun evaluateGraphPack(
        pack: GraphPack,
        links: List<IdentityLink> = emptyList(),
        horizon: Horizon,
    ): RevealedGraphView {
        val viewOrdinal = horizon.graphViewPosition.value
        val entityMap = pack.entities.associateBy { it.entityId }

        val visibleEntities = mutableListOf<RevealedEntity>()
        for (entity in pack.entities) {
            when (val eval = evaluateEntity(entity, pack.names, horizon)) {
                is RedactionResult.Visible -> visibleEntities.add(eval.item)
                RedactionResult.Redacted -> Unit
            }
        }
        visibleEntities.sortWith(RevealedEntity.STABLE_COMPARATOR)

        val visibleAssertions = mutableListOf<RevealedAssertion>()
        for (assertion in pack.assertions) {
            val subject = entityMap[assertion.subjectId] ?: continue
            val objectEntity =
                (assertion.objectRef as? ObjectRef.Entity)?.let { entityMap[it.entityId] }
            val speaker = assertion.attribution.speakerId?.let { entityMap[it] }

            val eval =
                evaluateAssertion(
                    assertion = assertion,
                    transitions = pack.transitions,
                    subject = subject,
                    objectEntity = objectEntity,
                    horizon = horizon,
                    speaker = speaker,
                )
            when (eval) {
                is RedactionResult.Visible -> visibleAssertions.add(eval.item)
                RedactionResult.Redacted -> Unit
            }
        }
        visibleAssertions.sortWith(RevealedAssertion.STABLE_COMPARATOR)

        return RevealedGraphView(
            editionId = pack.manifest.editionId,
            viewOrdinal = viewOrdinal,
            entities = visibleEntities,
            assertions = visibleAssertions,
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
