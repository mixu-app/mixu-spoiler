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
import app.mixu.graph.computeConditionalIdentityClusters

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
 * Search hit candidate with associated identifier and the reveal boundary at which
 * the matching disclosure was made.
 */
data class CandidateSearchHit<T>(
    val targetId: String,
    val matchedRevealOrdinal: RevealOrdinal,
    val payload: T,
    val matchedRevealCharEnd: Int? = null,
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
        if (!horizon.isDisclosedAtView(entity.firstReveal, entity.revealCharEnd)) {
            return RedactionResult.Redacted
        }

        val revealedNames =
            names
                .filter {
                    it.entityId == entity.entityId &&
                        horizon.isDisclosedAtView(it.firstReveal, it.revealCharEnd)
                }.sortedWith(EntityName.STABLE_COMPARATOR)
        val canonicalName =
            revealedNames.find { it.nameId == entity.canonicalNameId }
                ?: revealedNames.minWithOrNull(EntityName.STABLE_COMPARATOR)

        return RedactionResult.Visible(
            RevealedEntity(
                entity = entity,
                canonicalName = canonicalName,
                allRevealedNames = revealedNames,
                viewOrdinal = viewOrdinal,
                viewCharOffset = horizon.viewCharOffset,
            ),
        )
    }

    /**
     * Evaluates whether an assertion/relation is visible and computes its effective epistemic status
     * as of the horizon's view cursor.
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

        if (!horizon.isDisclosedAtView(assertion.firstReveal, assertion.revealCharEnd)) {
            return RedactionResult.Redacted
        }

        if (!horizon.isDisclosedAtView(subject.firstReveal, subject.revealCharEnd)) {
            return RedactionResult.Redacted
        }

        if (assertion.objectRef is ObjectRef.Entity) {
            if (objectEntity == null ||
                !horizon.isDisclosedAtView(objectEntity.firstReveal, objectEntity.revealCharEnd)
            ) {
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
            if (resolvedSpeaker == null) {
                return RedactionResult.Redacted
            }
            val speakerDisclosed =
                horizon.isDisclosedAtView(
                    resolvedSpeaker.firstReveal,
                    resolvedSpeaker.revealCharEnd,
                )
            if (!speakerDisclosed) {
                return RedactionResult.Redacted
            }
        }

        val transitionOrder =
            compareBy<AssertionTransition>(
                { it.revealOrdinal },
                { it.revealCharEnd ?: 0 },
                { it.transitionId.value },
            )
        val validTransitions =
            transitions
                .filter {
                    it.assertionId == assertion.assertionId &&
                        horizon.isDisclosedAtView(it.revealOrdinal, it.revealCharEnd)
                }.sortedWith(transitionOrder)

        val activeTransition = validTransitions.lastOrNull()
        val currentStatus = activeTransition?.status ?: assertion.epistemicStatus

        return RedactionResult.Visible(
            RevealedAssertion(
                assertion = assertion,
                currentStatus = currentStatus,
                activeTransition = activeTransition,
                viewOrdinal = viewOrdinal,
                viewCharOffset = horizon.viewCharOffset,
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
        if (!horizon.isDisclosedAtView(link.firstReveal, link.revealCharEnd)) {
            return RedactionResult.Redacted
        }
        return RedactionResult.Visible(link)
    }

    /**
     * Resolves the canonical entity ID among collapsible linked personas at the current horizon.
     * Hypothesized/framed links (INV-3) and undisclosed links never collapse entities.
     */
    fun resolveCanonicalEntityId(
        entityId: EntityId,
        links: List<IdentityLink>,
        horizon: Horizon,
    ): EntityId {
        val collapsibleLinks =
            links.filter {
                horizon.isDisclosedAtView(it.firstReveal, it.revealCharEnd) && it.isCollapsible()
            }
        if (collapsibleLinks.none { it.entityIds.contains(entityId) }) {
            return entityId
        }

        val connected = mutableSetOf(entityId)
        val queue = ArrayDeque<EntityId>()
        queue.add(entityId)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            for (link in collapsibleLinks) {
                if (link.entityIds.contains(curr)) {
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

        val rawVisibleEntities = mutableListOf<RevealedEntity>()
        for (entity in pack.entities) {
            when (val eval = evaluateEntity(entity, pack.names, horizon)) {
                is RedactionResult.Visible -> rawVisibleEntities.add(eval.item)
                RedactionResult.Redacted -> Unit
            }
        }

        val visibleEntityIds = rawVisibleEntities.map { it.entity.entityId }.toSet()
        val combinedLinks =
            (pack.identityLinks + links)
                .distinctBy { it.linkId }
                .sortedWith(IdentityLink.STABLE_COMPARATOR)
        val visibleLinks =
            combinedLinks.filter { link ->
                horizon.isDisclosedAtView(link.firstReveal, link.revealCharEnd) &&
                    link.entityIds.all { it in visibleEntityIds }
            }
        val resolution =
            computeConditionalIdentityClusters(
                visibleEntities = rawVisibleEntities.map { it.entity },
                visibleLinks = visibleLinks,
            )

        val visibleEntities =
            rawVisibleEntities
                .map { revealed ->
                    revealed.copy(
                        resolvedEntityId =
                            resolution.resolvedByEntity[revealed.entity.entityId]
                                ?: revealed.entity.entityId,
                    )
                }.sortedWith(RevealedEntity.STABLE_COMPARATOR)

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
            viewCharOffset = horizon.viewCharOffset,
            identityLinks = visibleLinks,
            collapsibleIdentityLinks = resolution.collapsibleLinks,
            hypothesizedIdentityLinks = resolution.hypothesizedLinks,
            dsuClusters = resolution.dsuClusters,
        )
    }

    /**
     * Filters search hits so that any match based on unrevealed text is redacted.
     */
    fun <T> filterSearchHits(
        candidates: List<CandidateSearchHit<T>>,
        horizon: Horizon,
    ): List<T> =
        candidates
            .filter {
                horizon.isDisclosedAtView(it.matchedRevealOrdinal.value, it.matchedRevealCharEnd)
            }.map { it.payload }

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

    /**
     * Computes the visible count of (ordinal, revealCharEnd) pairs at the current view cursor.
     */
    fun countVisibleDisclosures(
        disclosures: Iterable<Pair<Int, Int?>>,
        horizon: Horizon,
    ): Int = disclosures.count { (ordinal, charEnd) -> horizon.isDisclosedAtView(ordinal, charEnd) }
}
