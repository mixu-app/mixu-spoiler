// SPDX-License-Identifier: Apache-2.0
package app.mixu.spoiler

import app.mixu.graph.Assertion
import app.mixu.graph.AssertionId
import app.mixu.graph.AssertionTransition
import app.mixu.graph.Attribution
import app.mixu.graph.AttributionKind
import app.mixu.graph.Entity
import app.mixu.graph.EntityId
import app.mixu.graph.EntityName
import app.mixu.graph.EntityType
import app.mixu.graph.EpistemicStatus
import app.mixu.graph.EvidenceAnchorId
import app.mixu.graph.NameId
import app.mixu.graph.NameKind
import app.mixu.graph.ObjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedactionPolicyTest {
    private val editionId = EditionId("urn:mixu:test:scarlet")
    private val holmes = EntityId("entity:holmes")
    private val watson = EntityId("entity:watson")
    private val lestrade = EntityId("entity:lestrade")

    private val holmesCanonical = NameId("name:holmes:canonical")
    private val holmesEarlyAlias = NameId("name:holmes:stranger")

    private val holmesEntity =
        Entity(
            entityId = holmes,
            entityType = EntityType.PERSON,
            firstReveal = 2,
            canonicalNameId = holmesCanonical,
        )

    private val holmesNames =
        listOf(
            EntityName(
                nameId = holmesEarlyAlias,
                entityId = holmes,
                displayText = "The Eccentric Chemist",
                firstReveal = 2,
                nameKind = NameKind.DESCRIPTION,
            ),
            EntityName(
                nameId = holmesCanonical,
                entityId = holmes,
                displayText = "Sherlock Holmes",
                firstReveal = 5,
                nameKind = NameKind.CANONICAL,
            ),
        )

    @Test
    fun entityBeyondHorizonIsRedacted() {
        val horizon = Horizon.initial(editionId, RevealOrdinal(1))
        val result = RedactionPolicy.evaluateEntity(holmesEntity, holmesNames, horizon)
        assertIs<RedactionResult.Redacted>(result)
    }

    @Test
    fun entityAtHorizonOnlyExposesRevealedNames() {
        // Horizon 3: entity revealed (firstReveal=2), early alias revealed (firstReveal=2), canonical name hidden (firstReveal=5)
        val horizon = Horizon.initial(editionId, RevealOrdinal(3))
        val result = RedactionPolicy.evaluateEntity(holmesEntity, holmesNames, horizon)
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedEntity>>(result)

        val revealed = result.item
        assertEquals(holmes, revealed.entity.entityId)
        assertEquals(1, revealed.allRevealedNames.size)
        assertEquals(holmesEarlyAlias, revealed.allRevealedNames.first().nameId)
        // Canonical name must NOT be leaked
        assertEquals(holmesEarlyAlias, revealed.canonicalName?.nameId)
    }

    @Test
    fun entityPastCanonicalHorizonExposesCanonicalName() {
        // Horizon 6: canonical name revealed
        val horizon = Horizon.initial(editionId, RevealOrdinal(6))
        val result = RedactionPolicy.evaluateEntity(holmesEntity, holmesNames, horizon)
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedEntity>>(result)

        val revealed = result.item
        assertEquals(holmesCanonical, revealed.canonicalName?.nameId)
        assertEquals(2, revealed.allRevealedNames.size)
    }

    @Test
    fun assertionRedactedIfEndpointsAreHidden() {
        val assertionId = AssertionId("assertion:flatmates")
        val assertion =
            Assertion(
                assertionId = assertionId,
                subjectId = holmes,
                predicate = "introduced_to",
                objectRef = ObjectRef.Entity(watson),
                attribution = Attribution(kind = AttributionKind.NARRATOR),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:3"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            )

        val watsonEntity =
            Entity(
                entityId = watson,
                entityType = EntityType.PERSON,
                firstReveal = 4, // Disclosed after Holmes!
            )

        // At horizon 3, Holmes is revealed (2), Assertion is revealed (3), but Watson is NOT (4)
        val horizon3 = Horizon.initial(editionId, RevealOrdinal(3))
        val result3 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertion,
                transitions = emptyList(),
                subject = holmesEntity,
                objectEntity = watsonEntity,
                horizon = horizon3,
            )
        assertIs<RedactionResult.Redacted>(result3)

        // At horizon 4, Watson is revealed -> Assertion becomes visible
        val horizon4 = Horizon.initial(editionId, RevealOrdinal(4))
        val result4 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertion,
                transitions = emptyList(),
                subject = holmesEntity,
                objectEntity = watsonEntity,
                horizon = horizon4,
            )
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedAssertion>>(result4)
        assertEquals(assertionId, result4.item.assertion.assertionId)
    }

    @Test
    fun assertionMaintainsHistoricalStatusUnderTransition() {
        val assertionId = AssertionId("assertion:suspect")
        val assertion =
            Assertion(
                assertionId = assertionId,
                subjectId = lestrade,
                predicate = "suspects",
                objectRef = ObjectRef.Entity(holmes),
                attribution = Attribution(kind = AttributionKind.WITNESS_CLAIM),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:2"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            )

        val transition1 =
            AssertionTransition(
                transitionId = app.mixu.graph.TransitionId("trans:1"),
                assertionId = assertionId,
                revealOrdinal = 5,
                status = EpistemicStatus.CONTRADICTED,
            )

        val lestradeEntity =
            Entity(entityId = lestrade, entityType = EntityType.PERSON, firstReveal = 1)

        // At horizon 3: transition at 5 must NOT leak; status remains CLAIMED
        val horizon3 = Horizon.initial(editionId, RevealOrdinal(3))
        val result3 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertion,
                transitions = listOf(transition1),
                subject = lestradeEntity,
                objectEntity = holmesEntity,
                horizon = horizon3,
            )
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedAssertion>>(result3)
        assertEquals(EpistemicStatus.CLAIMED, result3.item.currentStatus)

        // At horizon 5: transition takes effect -> CONTRADICTED
        val horizon5 = Horizon.initial(editionId, RevealOrdinal(5))
        val result5 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertion,
                transitions = listOf(transition1),
                subject = lestradeEntity,
                objectEntity = holmesEntity,
                horizon = horizon5,
            )
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedAssertion>>(result5)
        assertEquals(EpistemicStatus.CONTRADICTED, result5.item.currentStatus)
    }

    @Test
    fun searchHitsFilterUnrevealedDisclosures() {
        val candidates =
            listOf(
                CandidateSearchHit("hit:1", RevealOrdinal(2), "early hit"),
                CandidateSearchHit("hit:2", RevealOrdinal(5), "future spoiler hit"),
            )

        val horizon = Horizon.initial(editionId, RevealOrdinal(3))
        val results = RedactionPolicy.filterSearchHits(candidates, horizon)

        assertEquals(1, results.size)
        assertEquals("early hit", results.first())
    }

    @Test
    fun countsNeverLeakPackTotals() {
        val ordinals = listOf(1, 2, 4, 8, 10)
        val horizon = Horizon.initial(editionId, RevealOrdinal(3))

        val count = RedactionPolicy.countVisible(ordinals, horizon)
        assertEquals(2, count)
        assertTrue(count < ordinals.size)
    }
}
