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
import app.mixu.graph.GraphPack
import app.mixu.graph.GraphPackManifest
import app.mixu.graph.IdentityLink
import app.mixu.graph.IdentityLinkKind
import app.mixu.graph.LinkId
import app.mixu.graph.NameId
import app.mixu.graph.NameKind
import app.mixu.graph.ObjectRef
import app.mixu.graph.Provenance
import app.mixu.graph.WorkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    @Test
    fun charOffsetGatesDisclosuresWithinSameOrdinal_INV7() {
        val earlyName =
            EntityName(
                nameId = NameId("name:alias:early"),
                entityId = holmes,
                displayText = "Lodger",
                firstReveal = 2,
                nameKind = NameKind.DESCRIPTION,
                revealCharEnd = 100,
            )
        val lateName =
            EntityName(
                nameId = NameId("name:alias:late"),
                entityId = holmes,
                displayText = "Consulting Detective",
                firstReveal = 2,
                nameKind = NameKind.CANONICAL,
                revealCharEnd = 450,
            )
        val entity = holmesEntity.copy(revealCharEnd = 90, canonicalNameId = lateName.nameId)
        val beforeLate = Horizon.initial(editionId, RevealOrdinal(2), viewCharOffset = 200)
        val resBefore =
            RedactionPolicy.evaluateEntity(
                entity,
                listOf(earlyName, lateName),
                beforeLate,
            )
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedEntity>>(resBefore)
        assertEquals("Lodger", resBefore.item.canonicalName?.displayText)
        assertEquals(listOf(earlyName), resBefore.item.allRevealedNames)

        val atLate = beforeLate.withViewCharOffset(450)
        val resAtLate = RedactionPolicy.evaluateEntity(entity, listOf(earlyName, lateName), atLate)
        assertIs<RedactionResult.Visible<app.mixu.graph.RevealedEntity>>(resAtLate)
        assertEquals("Consulting Detective", resAtLate.item.canonicalName?.displayText)
        assertEquals(2, resAtLate.item.allRevealedNames.size)

        val hitBefore =
            CandidateSearchHit("hit:1", RevealOrdinal(2), "early", matchedRevealCharEnd = 120)
        val hitAfter =
            CandidateSearchHit("hit:2", RevealOrdinal(2), "late", matchedRevealCharEnd = 300)
        val filtered = RedactionPolicy.filterSearchHits(listOf(hitBefore, hitAfter), beforeLate)
        assertEquals(listOf("early"), filtered)
        assertFalse(beforeLate.isPermitted(RevealOrdinal(2), editionId, targetCharEnd = 300))
        assertTrue(beforeLate.isPermitted(RevealOrdinal(2), editionId, targetCharEnd = 120))
    }

    @Test
    fun conditionalDsuIgnoresClaimedAndFramedLinks_INV3() {
        val claimed =
            IdentityLink(
                linkId = LinkId("link:claimed"),
                entityIds = listOf(holmes, watson),
                kind = IdentityLinkKind.CANDIDATE,
                firstReveal = 3,
                epistemicStatus = EpistemicStatus.CLAIMED,
            )
        val framed =
            IdentityLink(
                linkId = LinkId("link:framed"),
                entityIds = listOf(watson, lestrade),
                kind = IdentityLinkKind.CONFIRMED,
                firstReveal = 3,
                epistemicStatus = EpistemicStatus.OBSERVED,
                frameId = "frame:hyp-1",
            )
        val observed =
            IdentityLink(
                linkId = LinkId("link:observed"),
                entityIds = listOf(holmes, lestrade),
                kind = IdentityLinkKind.CONFIRMED,
                firstReveal = 5,
                epistemicStatus = EpistemicStatus.OBSERVED,
            )

        val links = listOf(claimed, framed, observed)
        val h4 = Horizon.initial(editionId, RevealOrdinal(4))
        assertEquals(holmes, RedactionPolicy.resolveCanonicalEntityId(holmes, links, h4))
        assertEquals(watson, RedactionPolicy.resolveCanonicalEntityId(watson, links, h4))

        val h5 = Horizon.initial(editionId, RevealOrdinal(5))
        assertEquals(holmes, RedactionPolicy.resolveCanonicalEntityId(lestrade, links, h5))
        assertEquals(watson, RedactionPolicy.resolveCanonicalEntityId(watson, links, h5))
    }

    @Test
    fun evaluateGraphPackEnforcesTruncationEquivalence_PSpoiler09() {
        val gEdition = app.mixu.graph.EditionId(editionId.value)
        val checksum = "0123456789abcdef".repeat(4)
        val manifest =
            GraphPackManifest(
                graphSchemaVersion = 1,
                workId = WorkId("urn:mixu:work:trunc-test"),
                editionId = gEdition,
                graphVersion = "1.0.0",
                revealAnchorMapVersion = 1,
                checksumSha256 = checksum,
                packageFormat = "sqlite+zstd",
                publicationPolicy = "AUTHORIZED_PUBLIC",
                sourceExcerptPolicy = "LOCATOR_ONLY",
                createdAt = "2026-11-25T00:00:00Z",
                copyrightBasis = "SYNTHETIC",
                provenance = Provenance(validatorVersion = "1.0.0"),
            )
        val watsonNameId = NameId("name:watson")
        val lestradeNameId = NameId("name:lestrade")
        val eA =
            Entity(
                entityId = holmes,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                revealCharEnd = 50,
                canonicalNameId = holmesEarlyAlias,
            )
        val eB =
            Entity(
                entityId = watson,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                revealCharEnd = 100,
                canonicalNameId = watsonNameId,
            )
        val eFuture =
            Entity(
                entityId = lestrade,
                entityType = EntityType.PERSON,
                firstReveal = 6,
                canonicalNameId = lestradeNameId,
            )
        val names =
            listOf(
                EntityName(
                    nameId = holmesEarlyAlias,
                    entityId = holmes,
                    displayText = "Stranger-A",
                    firstReveal = 1,
                    nameKind = NameKind.DESCRIPTION,
                    revealCharEnd = 50,
                ),
                EntityName(
                    nameId = holmesCanonical,
                    entityId = holmes,
                    displayText = "Late Reveal",
                    firstReveal = 1,
                    nameKind = NameKind.CANONICAL,
                    revealCharEnd = 400,
                ),
                EntityName(
                    nameId = watsonNameId,
                    entityId = watson,
                    displayText = "Companion-B",
                    firstReveal = 1,
                    nameKind = NameKind.CANONICAL,
                    revealCharEnd = 100,
                ),
                EntityName(
                    nameId = lestradeNameId,
                    entityId = lestrade,
                    displayText = "Future Inspector",
                    firstReveal = 6,
                    nameKind = NameKind.CANONICAL,
                ),
            )
        val aEarly =
            Assertion(
                assertionId = AssertionId("assertion:early"),
                subjectId = holmes,
                predicate = "met",
                objectRef = ObjectRef.Entity(watson),
                attribution = Attribution(AttributionKind.OBSERVATION),
                firstReveal = 1,
                revealCharEnd = 150,
                evidenceAnchorId = EvidenceAnchorId("anchor:1"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            )
        val aLate =
            Assertion(
                assertionId = AssertionId("assertion:late"),
                subjectId = holmes,
                predicate = "suspects",
                objectRef = ObjectRef.Entity(lestrade),
                attribution = Attribution(AttributionKind.NARRATOR),
                firstReveal = 6,
                evidenceAnchorId = EvidenceAnchorId("anchor:2"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            )
        val futLink =
            IdentityLink(
                linkId = LinkId("link:fut"),
                entityIds = listOf(holmes, lestrade),
                kind = IdentityLinkKind.CONFIRMED,
                firstReveal = 6,
            )
        val fullPack =
            GraphPack(
                manifest = manifest,
                entities = listOf(eA, eB, eFuture),
                names = names,
                assertions = listOf(aEarly, aLate),
                identityLinks = listOf(futLink),
            )
        val truncatedPack =
            GraphPack(
                manifest = manifest,
                entities = listOf(eA, eB),
                names =
                    names.filter {
                        it.firstReveal <= 1 &&
                            (it.revealCharEnd == null || it.revealCharEnd!! <= 200)
                    },
                assertions = listOf(aEarly),
            )

        val horizon = Horizon.initial(editionId, RevealOrdinal(1), viewCharOffset = 200)
        val fullView = RedactionPolicy.evaluateGraphPack(fullPack, horizon = horizon)
        val truncatedView = RedactionPolicy.evaluateGraphPack(truncatedPack, horizon = horizon)

        assertEquals(truncatedView, fullView)
        val holmesView = fullView.entities.find { it.entity.entityId == holmes }
        assertNull(holmesView?.allRevealedNames?.find { it.nameId == holmesCanonical })
    }
}
