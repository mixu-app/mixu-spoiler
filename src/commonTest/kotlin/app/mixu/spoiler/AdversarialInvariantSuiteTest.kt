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
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import app.mixu.graph.TransitionId
import app.mixu.graph.WorkId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_CHECKSUM =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/**
 * Adversarial invariant test suite validating knowledge-horizon filtering against
 * synthetic narrative edge-cases: retroactive correction, aliasing, and unreliable testimony.
 *
 * Implements verification for invariants:
 * - P-SPOILER-01 (Entity and name horizon filtering / Monotonic disclosure)
 * - P-SPOILER-02 (Retroactive contradiction preservation / Zero future leak)
 * - P-SPOILER-03 (Delayed identity merge isolation)
 * - P-SPOILER-04 (Cross-surface horizon parity)
 * - P-SPOILER-05 (Navigation and horizon state decoupling)
 * - P-SPOILER-06 (Metadata and layout side-channel prevention)
 */
class AdversarialInvariantSuiteTest {
    // -------------------------------------------------------------------------
    // Synthetic Fixture 1: false-witness (adversarial-retroactive)
    // -------------------------------------------------------------------------
    private val fwEdition = EditionId("urn:mixu:synthetic:false-witness")
    private val fwWork = WorkId("urn:work:false-witness")
    private val fwManifestEdition = app.mixu.graph.EditionId("urn:mixu:synthetic:false-witness")

    // Entities
    private val eBradley = EntityId("e_bradley")
    private val eJulian = EntityId("e_julian")
    private val locManor = EntityId("loc_manor")
    private val objChronometer = EntityId("obj_chronometer")
    private val eGable = EntityId("e_gable")
    private val eCole = EntityId("e_cole")
    private val locConservatory = EntityId("loc_conservatory")
    private val locWorkshop = EntityId("loc_workshop")
    private val objLogbook = EntityId("obj_logbook")

    // Names
    private val nBradley = NameId("n_bradley")
    private val nClockmaker = NameId("n_clockmaker")
    private val nGable = NameId("n_gable")
    private val nCole = NameId("n_cole")
    private val nJulianPrice = NameId("n_julian_price")

    // Assertions
    private val aTheftOccurred = AssertionId("a_theft_occurred")
    private val aGableClaimsColeFled = AssertionId("a_gable_claims_cole_fled")
    private val aGableClaimsColeKey = AssertionId("a_gable_claims_cole_key")
    private val aPriceHeldChronometer = AssertionId("a_price_held_chronometer")
    private val aPriceConfession = AssertionId("a_price_confession")
    private val aColeHoldingCell = AssertionId("a_cole_holding_cell")
    private val aGableConfessedFabrication = AssertionId("a_gable_confessed_fabrication")

    // Transitions
    private val tGableClaimContradicted = TransitionId("t_gable_claim_contradicted")
    private val tGableKeyRetracted = TransitionId("t_gable_key_retracted")

    private val fwEntities =
        listOf(
            Entity(
                entityId = eBradley,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                canonicalNameId = nBradley,
            ),
            Entity(
                entityId = eJulian,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                canonicalNameId = nJulianPrice,
            ),
            Entity(
                entityId = locManor,
                entityType = EntityType.LOCATION,
                firstReveal = 1,
            ),
            Entity(
                entityId = objChronometer,
                entityType = EntityType.OBJECT,
                firstReveal = 1,
            ),
            Entity(
                entityId = eGable,
                entityType = EntityType.PERSON,
                firstReveal = 2,
                canonicalNameId = nGable,
            ),
            Entity(
                entityId = eCole,
                entityType = EntityType.PERSON,
                firstReveal = 2,
                canonicalNameId = nCole,
            ),
            Entity(
                entityId = locConservatory,
                entityType = EntityType.LOCATION,
                firstReveal = 2,
            ),
            Entity(
                entityId = locWorkshop,
                entityType = EntityType.LOCATION,
                firstReveal = 3,
            ),
            Entity(
                entityId = objLogbook,
                entityType = EntityType.OBJECT,
                firstReveal = 4,
            ),
        )

    private val fwNames =
        listOf(
            EntityName(
                nameId = nBradley,
                entityId = eBradley,
                displayText = "Detective Bradley",
                firstReveal = 1,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nClockmaker,
                entityId = eJulian,
                displayText = "the Clockmaker",
                firstReveal = 1,
                nameKind = NameKind.DESCRIPTION,
            ),
            EntityName(
                nameId = nGable,
                entityId = eGable,
                displayText = "Mrs. Gable",
                firstReveal = 2,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nCole,
                entityId = eCole,
                displayText = "Thomas Cole",
                firstReveal = 2,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nJulianPrice,
                entityId = eJulian,
                displayText = "Julian Price",
                firstReveal = 3,
                nameKind = NameKind.CANONICAL,
            ),
        )

    private val fwAssertions =
        listOf(
            Assertion(
                assertionId = aTheftOccurred,
                subjectId = eJulian,
                predicate = "stole_chronometer",
                objectRef = ObjectRef.Entity(locManor),
                attribution = Attribution(kind = AttributionKind.OBSERVATION),
                firstReveal = 1,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:1"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aGableClaimsColeFled,
                subjectId = eGable,
                predicate = "claimed_cole_fled_towards_conservatory",
                objectRef = ObjectRef.Entity(locConservatory),
                attribution =
                    Attribution(
                        kind = AttributionKind.WITNESS_CLAIM,
                        speakerId = eGable,
                    ),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:2"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = aGableClaimsColeKey,
                subjectId = eGable,
                predicate = "claimed_cole_threw_key",
                objectRef = ObjectRef.Entity(locConservatory),
                attribution =
                    Attribution(
                        kind = AttributionKind.WITNESS_CLAIM,
                        speakerId = eGable,
                    ),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:3"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = aPriceHeldChronometer,
                subjectId = eJulian,
                predicate = "hid_chronometer_at_workshop",
                objectRef = ObjectRef.Entity(locWorkshop),
                attribution = Attribution(kind = AttributionKind.OBSERVATION),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:4"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aPriceConfession,
                subjectId = eJulian,
                predicate = "confessed_acted_alone",
                objectRef = ObjectRef.Entity(locManor),
                attribution = Attribution(kind = AttributionKind.LATER_CONFIRMATION),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:5"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aColeHoldingCell,
                subjectId = eCole,
                predicate = "held_in_station_custody",
                objectRef = ObjectRef.Entity(objLogbook),
                attribution = Attribution(kind = AttributionKind.OBSERVATION),
                firstReveal = 4,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:6"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aGableConfessedFabrication,
                subjectId = eGable,
                predicate = "confessed_fabricating_accusation",
                objectRef = ObjectRef.Entity(locManor),
                attribution = Attribution(kind = AttributionKind.LATER_CONFIRMATION),
                firstReveal = 4,
                evidenceAnchorId = EvidenceAnchorId("anchor:fw:7"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
        )

    private val fwTransitions =
        listOf(
            AssertionTransition(
                transitionId = tGableClaimContradicted,
                assertionId = aGableClaimsColeFled,
                revealOrdinal = 4,
                status = EpistemicStatus.CONTRADICTED,
            ),
            AssertionTransition(
                transitionId = tGableKeyRetracted,
                assertionId = aGableClaimsColeKey,
                revealOrdinal = 4,
                status = EpistemicStatus.RETRACTED,
            ),
        )

    private val fwForbidden =
        mapOf(
            1 to
                setOf(
                    "a_cole_holding_cell",
                    "a_gable_claims_cole_fled",
                    "a_gable_claims_cole_key",
                    "a_gable_confessed_fabrication",
                    "a_price_confession",
                    "a_price_held_chronometer",
                    "e_cole",
                    "e_gable",
                    "loc_conservatory",
                    "loc_workshop",
                    "n_cole",
                    "n_gable",
                    "n_julian_price",
                    "obj_logbook",
                    "t_gable_claim_contradicted",
                    "t_gable_key_retracted",
                ),
            2 to
                setOf(
                    "a_cole_holding_cell",
                    "a_gable_confessed_fabrication",
                    "a_price_confession",
                    "a_price_held_chronometer",
                    "loc_workshop",
                    "n_julian_price",
                    "obj_logbook",
                    "t_gable_claim_contradicted",
                    "t_gable_key_retracted",
                ),
            3 to
                setOf(
                    "a_cole_holding_cell",
                    "a_gable_confessed_fabrication",
                    "obj_logbook",
                    "t_gable_claim_contradicted",
                    "t_gable_key_retracted",
                ),
            4 to emptySet(),
        )

    private fun falseWitnessPack(): GraphPack =
        GraphPack(
            manifest =
                GraphPackManifest(
                    graphSchemaVersion = 1,
                    workId = fwWork,
                    editionId = fwManifestEdition,
                    graphVersion = "0.1.0",
                    revealAnchorMapVersion = 1,
                    checksumSha256 = TEST_CHECKSUM,
                    packageFormat = "sqlite+zstd",
                    publicationPolicy = "AUTHORIZED_PUBLIC",
                    sourceExcerptPolicy = "LOCATOR_ONLY",
                    createdAt = "2026-11-08T00:00:00Z",
                    copyrightBasis = "SYNTHETIC_ORIGINAL",
                    provenance = Provenance(validatorVersion = "1.0.0"),
                ),
            entities = fwEntities,
            names = fwNames,
            assertions = fwAssertions,
            transitions = fwTransitions,
        )

    // -------------------------------------------------------------------------
    // Synthetic Fixture 2: silent-courier (delayed-identity)
    // -------------------------------------------------------------------------
    private val scEdition = EditionId("urn:mixu:synthetic:silent-courier")
    private val scWork = WorkId("urn:work:silent-courier")
    private val scManifestEdition = app.mixu.graph.EditionId("urn:mixu:synthetic:silent-courier")

    private val eClara = EntityId("e_clara")
    private val eMiller = EntityId("e_miller")
    private val eCourier = EntityId("e_courier")
    private val locWatchhouse = EntityId("loc_watchhouse")
    private val objCylinder = EntityId("obj_cylinder")
    private val eVance = EntityId("e_vance")
    private val locInfirmary = EntityId("loc_infirmary")
    private val objLedger = EntityId("obj_ledger")
    private val objMantle = EntityId("obj_mantle")

    private val nClara = NameId("n_clara")
    private val nMiller = NameId("n_miller")
    private val nCourier = NameId("n_courier")
    private val nVance = NameId("n_vance")

    private val aCourierDelivered = AssertionId("a_courier_delivered")
    private val aVanceAlibi = AssertionId("a_vance_alibi")
    private val aLedgerFound = AssertionId("a_ledger_found")
    private val aVanceConfessed = AssertionId("a_vance_confessed")

    private val lCourierIsVance = LinkId("l_courier_is_vance")
    private val tVanceAlibiContradicted = TransitionId("t_vance_alibi_contradicted")

    private val scEntities =
        listOf(
            Entity(
                entityId = eClara,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                canonicalNameId = nClara,
            ),
            Entity(
                entityId = eMiller,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                canonicalNameId = nMiller,
            ),
            Entity(
                entityId = eCourier,
                entityType = EntityType.PERSONA,
                firstReveal = 1,
                canonicalNameId = nCourier,
            ),
            Entity(
                entityId = locWatchhouse,
                entityType = EntityType.LOCATION,
                firstReveal = 1,
            ),
            Entity(
                entityId = objCylinder,
                entityType = EntityType.OBJECT,
                firstReveal = 1,
            ),
            Entity(
                entityId = eVance,
                entityType = EntityType.PERSON,
                firstReveal = 2,
                canonicalNameId = nVance,
            ),
            Entity(
                entityId = locInfirmary,
                entityType = EntityType.LOCATION,
                firstReveal = 2,
            ),
            Entity(
                entityId = objLedger,
                entityType = EntityType.OBJECT,
                firstReveal = 3,
            ),
            Entity(
                entityId = objMantle,
                entityType = EntityType.OBJECT,
                firstReveal = 4,
            ),
        )

    private val scNames =
        listOf(
            EntityName(
                nameId = nClara,
                entityId = eClara,
                displayText = "Inspector Clara",
                firstReveal = 1,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nMiller,
                entityId = eMiller,
                displayText = "Constable Miller",
                firstReveal = 1,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nCourier,
                entityId = eCourier,
                displayText = "the silent courier",
                firstReveal = 1,
                nameKind = NameKind.DESCRIPTION,
            ),
            EntityName(
                nameId = nVance,
                entityId = eVance,
                displayText = "Dr. Vance",
                firstReveal = 2,
                nameKind = NameKind.CANONICAL,
            ),
        )

    private val scAssertions =
        listOf(
            Assertion(
                assertionId = aCourierDelivered,
                subjectId = eCourier,
                predicate = "delivered_cylinder",
                objectRef = ObjectRef.Entity(locWatchhouse),
                attribution = Attribution(kind = AttributionKind.OBSERVATION),
                firstReveal = 1,
                evidenceAnchorId = EvidenceAnchorId("anchor:sc:1"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aVanceAlibi,
                subjectId = eVance,
                predicate = "claimed_stayed_at_ward",
                objectRef = ObjectRef.Entity(locInfirmary),
                attribution =
                    Attribution(
                        kind = AttributionKind.WITNESS_CLAIM,
                        speakerId = eVance,
                    ),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:sc:2"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = aLedgerFound,
                subjectId = eCourier,
                predicate = "provided_hospital_ledger",
                objectRef = ObjectRef.Entity(locInfirmary),
                attribution = Attribution(kind = AttributionKind.OBSERVATION),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:sc:3"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aVanceConfessed,
                subjectId = eVance,
                predicate = "confessed_as_courier",
                objectRef = ObjectRef.Entity(locWatchhouse),
                attribution =
                    Attribution(
                        kind = AttributionKind.WITNESS_CLAIM,
                        speakerId = eVance,
                    ),
                firstReveal = 4,
                evidenceAnchorId = EvidenceAnchorId("anchor:sc:4"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
        )

    private val scIdentityLinks =
        listOf(
            IdentityLink(
                linkId = lCourierIsVance,
                entityIds = listOf(eCourier, eVance),
                kind = IdentityLinkKind.CONFIRMED,
                firstReveal = 4,
            ),
        )

    private val scTransitions =
        listOf(
            AssertionTransition(
                transitionId = tVanceAlibiContradicted,
                assertionId = aVanceAlibi,
                revealOrdinal = 4,
                status = EpistemicStatus.CONTRADICTED,
            ),
        )

    private val scForbidden =
        mapOf(
            1 to
                setOf(
                    "a_ledger_found",
                    "a_vance_alibi",
                    "a_vance_confessed",
                    "e_vance",
                    "l_courier_is_vance",
                    "loc_infirmary",
                    "n_vance",
                    "obj_ledger",
                    "obj_mantle",
                    "t_vance_alibi_contradicted",
                ),
            2 to
                setOf(
                    "a_ledger_found",
                    "a_vance_confessed",
                    "l_courier_is_vance",
                    "obj_ledger",
                    "obj_mantle",
                    "t_vance_alibi_contradicted",
                ),
            3 to
                setOf(
                    "a_vance_confessed",
                    "l_courier_is_vance",
                    "obj_mantle",
                    "t_vance_alibi_contradicted",
                ),
            4 to emptySet(),
        )

    private fun silentCourierPack(): GraphPack =
        GraphPack(
            manifest =
                GraphPackManifest(
                    graphSchemaVersion = 1,
                    workId = scWork,
                    editionId = scManifestEdition,
                    graphVersion = "0.1.0",
                    revealAnchorMapVersion = 1,
                    checksumSha256 = TEST_CHECKSUM,
                    packageFormat = "sqlite+zstd",
                    publicationPolicy = "AUTHORIZED_PUBLIC",
                    sourceExcerptPolicy = "LOCATOR_ONLY",
                    createdAt = "2026-11-08T00:00:00Z",
                    copyrightBasis = "SYNTHETIC_ORIGINAL",
                    provenance = Provenance(validatorVersion = "1.0.0"),
                ),
            entities = scEntities,
            names = scNames,
            assertions = scAssertions,
            transitions = scTransitions,
        )

    // -------------------------------------------------------------------------
    // Synthetic Fixture 3: locked-stair (delayed-identity & retroactive conflict)
    // -------------------------------------------------------------------------
    private val lsEdition = EditionId("urn:mixu:synthetic:locked-stair")
    private val lsWork = WorkId("urn:work:locked-stair")
    private val lsManifestEdition = app.mixu.graph.EditionId("urn:mixu:synthetic:locked-stair")

    private val eIshida = EntityId("e_ishida")
    private val eGrey = EntityId("e_grey")
    private val eKeeper = EntityId("e_keeper")
    private val ePorter = EntityId("e_porter")
    private val locGuesthouse = EntityId("loc_guesthouse")

    private val nIshida = NameId("n_ishida")
    private val nGrey = NameId("n_grey")
    private val nPorter = NameId("n_porter")

    private val aSigned = AssertionId("a_signed")
    private val aGreyOnStair = AssertionId("a_grey_on_stair")
    private val aIshidaStayed = AssertionId("a_ishida_stayed")
    private val aGreyLeft = AssertionId("a_grey_left")

    private val lGreyIsIshida = LinkId("l_grey_is_ishida")
    private val tStayedContradicted = TransitionId("t_stayed_contradicted")

    private val lsEntities =
        listOf(
            Entity(
                entityId = eIshida,
                entityType = EntityType.PERSON,
                firstReveal = 1,
                canonicalNameId = nIshida,
            ),
            Entity(
                entityId = eKeeper,
                entityType = EntityType.PERSON,
                firstReveal = 1,
            ),
            Entity(
                entityId = locGuesthouse,
                entityType = EntityType.LOCATION,
                firstReveal = 1,
            ),
            Entity(
                entityId = eGrey,
                entityType = EntityType.PERSONA,
                firstReveal = 2,
                canonicalNameId = nGrey,
            ),
            Entity(
                entityId = ePorter,
                entityType = EntityType.PERSON,
                firstReveal = 4,
                canonicalNameId = nPorter,
            ),
        )

    private val lsNames =
        listOf(
            EntityName(
                nameId = nIshida,
                entityId = eIshida,
                displayText = "Mr. Ishida",
                firstReveal = 1,
                nameKind = NameKind.CANONICAL,
            ),
            EntityName(
                nameId = nGrey,
                entityId = eGrey,
                displayText = "the man in the grey coat",
                firstReveal = 2,
                nameKind = NameKind.DESCRIPTION,
            ),
            EntityName(
                nameId = nPorter,
                entityId = ePorter,
                displayText = "the porter",
                firstReveal = 4,
                nameKind = NameKind.CANONICAL,
            ),
        )

    private val lsAssertions =
        listOf(
            Assertion(
                assertionId = aSigned,
                subjectId = eIshida,
                predicate = "signed_register",
                objectRef = ObjectRef.Entity(locGuesthouse),
                attribution = Attribution(kind = AttributionKind.NARRATOR),
                firstReveal = 1,
                evidenceAnchorId = EvidenceAnchorId("anchor:ls:1"),
                epistemicStatus = EpistemicStatus.OBSERVED,
            ),
            Assertion(
                assertionId = aGreyOnStair,
                subjectId = eGrey,
                predicate = "was_on_stair",
                objectRef = ObjectRef.Entity(locGuesthouse),
                attribution = Attribution(kind = AttributionKind.WITNESS_CLAIM),
                firstReveal = 2,
                evidenceAnchorId = EvidenceAnchorId("anchor:ls:2"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = aIshidaStayed,
                subjectId = eIshida,
                predicate = "stayed_in_room",
                objectRef = ObjectRef.Entity(locGuesthouse),
                attribution = Attribution(kind = AttributionKind.WITNESS_CLAIM),
                firstReveal = 3,
                evidenceAnchorId = EvidenceAnchorId("anchor:ls:3"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
            Assertion(
                assertionId = aGreyLeft,
                subjectId = eGrey,
                predicate = "left_at_midnight",
                objectRef = ObjectRef.Entity(locGuesthouse),
                attribution = Attribution(kind = AttributionKind.WITNESS_CLAIM),
                firstReveal = 4,
                evidenceAnchorId = EvidenceAnchorId("anchor:ls:4"),
                epistemicStatus = EpistemicStatus.CLAIMED,
            ),
        )

    private val lsIdentityLinks =
        listOf(
            IdentityLink(
                linkId = lGreyIsIshida,
                entityIds = listOf(eIshida, eGrey),
                kind = IdentityLinkKind.CONFIRMED,
                firstReveal = 3,
            ),
        )

    private val lsTransitions =
        listOf(
            AssertionTransition(
                transitionId = tStayedContradicted,
                assertionId = aIshidaStayed,
                revealOrdinal = 4,
                status = EpistemicStatus.CONTRADICTED,
            ),
        )

    private val lsForbidden =
        mapOf(
            1 to setOf("e_grey", "n_grey", "l_grey_is_ishida", "a_grey_on_stair"),
            2 to setOf("l_grey_is_ishida", "a_ishida_stayed", "e_porter"),
            3 to setOf("e_porter", "a_grey_left", "t_stayed_contradicted"),
            4 to emptySet(),
        )

    private fun lockedStairPack(): GraphPack =
        GraphPack(
            manifest =
                GraphPackManifest(
                    graphSchemaVersion = 1,
                    workId = lsWork,
                    editionId = lsManifestEdition,
                    graphVersion = "0.1.0",
                    revealAnchorMapVersion = 1,
                    checksumSha256 = TEST_CHECKSUM,
                    packageFormat = "sqlite+zstd",
                    publicationPolicy = "AUTHORIZED_PUBLIC",
                    sourceExcerptPolicy = "LOCATOR_ONLY",
                    createdAt = "2026-11-08T00:00:00Z",
                    copyrightBasis = "SYNTHETIC_ORIGINAL",
                    provenance = Provenance(validatorVersion = "1.0.0"),
                ),
            entities = lsEntities,
            names = lsNames,
            assertions = lsAssertions,
            transitions = lsTransitions,
        )

    // -------------------------------------------------------------------------
    // Naive Unfiltered Stub (Negative Invariant Probe)
    // Simulates naive implementations that bypass horizon filtering.
    // -------------------------------------------------------------------------
    private object NaiveHorizonStub {
        fun evaluateEntity(
            entity: Entity,
            names: List<EntityName>,
        ): RevealedEntity {
            val entityNames = names.filter { it.entityId == entity.entityId }
            val canonicalName =
                entityNames.find { it.nameId == entity.canonicalNameId }
                    ?: entityNames.firstOrNull()
            return RevealedEntity(
                entity = entity,
                canonicalName = canonicalName,
                allRevealedNames = entityNames,
                viewOrdinal = Int.MAX_VALUE,
            )
        }

        fun evaluateAssertion(
            assertion: Assertion,
            transitions: List<AssertionTransition>,
        ): RevealedAssertion {
            val latest =
                transitions
                    .filter { it.assertionId == assertion.assertionId }
                    .maxByOrNull { it.revealOrdinal }
            return RevealedAssertion(
                assertion = assertion,
                currentStatus = latest?.status ?: assertion.epistemicStatus,
                activeTransition = latest,
                viewOrdinal = Int.MAX_VALUE,
            )
        }

        fun resolveCanonicalEntityId(
            entityId: EntityId,
            links: List<IdentityLink>,
        ): EntityId {
            val connected = mutableSetOf(entityId)
            val queue = ArrayDeque<EntityId>()
            queue.add(entityId)
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                for (link in links) {
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
    }

    // =========================================================================
    // Scenario 1: Retroactive Correction
    // Protected Invariant: P-SPOILER-02 (Retroactive contradiction preservation)
    // =========================================================================

    @Test
    fun testScenario1_retroactiveCorrectionPreservation_PSPOILER02() {
        val assertionFled = fwAssertions.first { it.assertionId == aGableClaimsColeFled }
        val assertionKey = fwAssertions.first { it.assertionId == aGableClaimsColeKey }
        val gableEntity = fwEntities.first { it.entityId == eGable }
        val conservatoryEntity = fwEntities.first { it.entityId == locConservatory }

        // Horizon 1: Mrs. Gable and conservatory not revealed yet -> Redacted
        val h1 = Horizon.initial(fwEdition, RevealOrdinal(1))
        val evalH1Fled =
            RedactionPolicy.evaluateAssertion(
                assertionFled,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h1,
            )
        val evalH1Key =
            RedactionPolicy.evaluateAssertion(
                assertionKey,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h1,
            )
        assertIs<RedactionResult.Redacted>(evalH1Fled)
        assertIs<RedactionResult.Redacted>(evalH1Key)

        // Horizon 2: Gable introduces claim as CLAIMED; transitions at 4 must not leak
        val h2 = Horizon.initial(fwEdition, RevealOrdinal(2))
        val evalH2Fled =
            RedactionPolicy.evaluateAssertion(
                assertionFled,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h2,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH2Fled)
        assertEquals(EpistemicStatus.CLAIMED, evalH2Fled.item.currentStatus)
        assertNull(evalH2Fled.item.activeTransition)

        val evalH2Key =
            RedactionPolicy.evaluateAssertion(
                assertionKey,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h2,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH2Key)
        assertEquals(EpistemicStatus.CLAIMED, evalH2Key.item.currentStatus)
        assertNull(evalH2Key.item.activeTransition)

        // Horizon 3: Price confesses, but Gable's claim still has status CLAIMED
        val h3 = Horizon.initial(fwEdition, RevealOrdinal(3))
        val evalH3Fled =
            RedactionPolicy.evaluateAssertion(
                assertionFled,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h3,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH3Fled)
        assertEquals(EpistemicStatus.CLAIMED, evalH3Fled.item.currentStatus)
        assertNull(evalH3Fled.item.activeTransition)

        // Horizon 4: Logbook revealed, Gable confesses -> transitions take effect
        val h4 = Horizon.initial(fwEdition, RevealOrdinal(4))
        val evalH4Fled =
            RedactionPolicy.evaluateAssertion(
                assertionFled,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h4,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH4Fled)
        assertEquals(EpistemicStatus.CONTRADICTED, evalH4Fled.item.currentStatus)
        assertNotNull(evalH4Fled.item.activeTransition)
        assertEquals(tGableClaimContradicted, evalH4Fled.item.activeTransition?.transitionId)

        val evalH4Key =
            RedactionPolicy.evaluateAssertion(
                assertionKey,
                fwTransitions,
                gableEntity,
                conservatoryEntity,
                h4,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH4Key)
        assertEquals(EpistemicStatus.RETRACTED, evalH4Key.item.currentStatus)
        assertNotNull(evalH4Key.item.activeTransition)
        assertEquals(tGableKeyRetracted, evalH4Key.item.activeTransition?.transitionId)
    }

    @Test
    fun testScenario1_negativeStubFailsInvariant_PSPOILER02() {
        val assertionFled = fwAssertions.first { it.assertionId == aGableClaimsColeFled }

        // Naive stub retroactively applies transition at all horizons!
        val naiveResult = NaiveHorizonStub.evaluateAssertion(assertionFled, fwTransitions)
        assertEquals(EpistemicStatus.CONTRADICTED, naiveResult.currentStatus)
        assertNotNull(naiveResult.activeTransition)

        // Invariant check enforcing P-SPOILER-02 FAILS against naive stub
        val failsInvariant =
            runCatching {
                val currentHorizon = 2
                require(naiveResult.currentStatus == EpistemicStatus.CLAIMED) {
                    "P-SPOILER-02 violated: leaked CONTRADICTED at ordinal $currentHorizon"
                }
                require(naiveResult.activeTransition == null) {
                    "P-SPOILER-02 violated: transition leaked before reveal ordinal"
                }
            }
        assertTrue(failsInvariant.isFailure)
    }

    // =========================================================================
    // Scenario 2: Multi-name Aliasing & Delayed Disclosure
    // Protected Invariant: P-SPOILER-01 (Entity and name horizon filtering)
    // =========================================================================

    @Test
    fun testScenario2_multinameAliasingDelayedDisclosure_PSPOILER01() {
        val julianEntity = fwEntities.first { it.entityId == eJulian }

        // At horizon 1 & 2: only "the Clockmaker" is disclosed; "Julian Price" is hidden
        for (ordinal in 1..2) {
            val h = Horizon.initial(fwEdition, RevealOrdinal(ordinal))
            val eval = RedactionPolicy.evaluateEntity(julianEntity, fwNames, h)
            assertIs<RedactionResult.Visible<RevealedEntity>>(eval)

            val revealed = eval.item
            assertEquals(1, revealed.allRevealedNames.size)
            assertEquals("the Clockmaker", revealed.allRevealedNames.first().displayText)
            assertEquals(nClockmaker, revealed.canonicalName?.nameId)
            assertEquals("the Clockmaker", revealed.canonicalName?.displayText)

            // True canonical civil name must NOT leak in canonicalName or allRevealedNames
            assertFalse(revealed.allRevealedNames.any { it.displayText == "Julian Price" })
            assertFalse(revealed.allRevealedNames.any { it.nameId == nJulianPrice })
            assertNotEquals("Julian Price", revealed.canonicalName?.displayText)
        }

        // At horizon 3: "Julian Price" is revealed and takes over as canonical name
        val h3 = Horizon.initial(fwEdition, RevealOrdinal(3))
        val eval3 = RedactionPolicy.evaluateEntity(julianEntity, fwNames, h3)
        assertIs<RedactionResult.Visible<RevealedEntity>>(eval3)

        val revealed3 = eval3.item
        assertEquals(2, revealed3.allRevealedNames.size)
        assertEquals(nJulianPrice, revealed3.canonicalName?.nameId)
        assertEquals("Julian Price", revealed3.canonicalName?.displayText)
        assertTrue(revealed3.allRevealedNames.any { it.displayText == "the Clockmaker" })
        assertTrue(revealed3.allRevealedNames.any { it.displayText == "Julian Price" })
    }

    @Test
    fun testScenario2_negativeStubFailsInvariant_PSPOILER01() {
        val julianEntity = fwEntities.first { it.entityId == eJulian }

        // Naive stub resolves canonicalName from raw canonicalNameId, leaking "Julian Price"!
        val naiveResult = NaiveHorizonStub.evaluateEntity(julianEntity, fwNames)
        assertEquals("Julian Price", naiveResult.canonicalName?.displayText)
        assertEquals(2, naiveResult.allRevealedNames.size)

        // Invariant check at horizon 1 must FAIL against naive stub
        val failsInvariant =
            runCatching {
                val currentHorizon = 1
                require(naiveResult.canonicalName?.displayText != "Julian Price") {
                    "P-SPOILER-01 violated: 'Julian Price' leaked at horizon $currentHorizon"
                }
                require(naiveResult.allRevealedNames.none { it.firstReveal > currentHorizon }) {
                    "P-SPOILER-01 violated: names with firstReveal > $currentHorizon exposed"
                }
            }
        assertTrue(failsInvariant.isFailure)
    }

    // =========================================================================
    // Scenario 3: Unreliable Witness Claim
    // Protected Invariants: P-SPOILER-01 (Speaker gating) & P-SPOILER-02 (Claim preservation)
    // =========================================================================

    @Test
    fun testScenario3_unreliableWitnessClaimIntegrity_PSPOILER02_PSPOILER01() {
        val assertionFled = fwAssertions.first { it.assertionId == aGableClaimsColeFled }
        val gableEntity = fwEntities.first { it.entityId == eGable }
        val conservatoryEntity = fwEntities.first { it.entityId == locConservatory }

        // Horizon 1: Speaker Gable has firstReveal=2. At r=1, assertion must be Redacted.
        val h1 = Horizon.initial(fwEdition, RevealOrdinal(1))
        val evalH1 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertionFled,
                transitions = fwTransitions,
                subject = gableEntity,
                objectEntity = conservatoryEntity,
                horizon = h1,
                speaker = gableEntity,
            )
        assertIs<RedactionResult.Redacted>(evalH1)

        // If speaker entity is provided and has firstReveal > viewOrdinal, Redacted
        val unrevealedSpeaker =
            Entity(
                entityId = EntityId("e_mystery_witness"),
                entityType = EntityType.PERSON,
                firstReveal = 3,
            )
        val assertionWithMysterySpeaker =
            assertionFled.copy(
                attribution =
                    Attribution(
                        kind = AttributionKind.WITNESS_CLAIM,
                        speakerId = unrevealedSpeaker.entityId,
                    ),
            )
        val evalMystery =
            RedactionPolicy.evaluateAssertion(
                assertion = assertionWithMysterySpeaker,
                transitions = fwTransitions,
                subject = gableEntity,
                objectEntity = conservatoryEntity,
                horizon = Horizon.initial(fwEdition, RevealOrdinal(2)),
                speaker = unrevealedSpeaker,
            )
        assertIs<RedactionResult.Redacted>(evalMystery)

        // Horizon 2: Gable revealed; testimony evaluates as CLAIMED without leaking ground truth
        val h2 = Horizon.initial(fwEdition, RevealOrdinal(2))
        val evalH2 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertionFled,
                transitions = fwTransitions,
                subject = gableEntity,
                objectEntity = conservatoryEntity,
                horizon = h2,
                speaker = gableEntity,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH2)
        assertEquals(EpistemicStatus.CLAIMED, evalH2.item.currentStatus)
        assertEquals(AttributionKind.WITNESS_CLAIM, evalH2.item.assertion.attribution.kind)

        // Horizon 3: Julian confesses to acting alone; testimony still preserves status CLAIMED
        val h3 = Horizon.initial(fwEdition, RevealOrdinal(3))
        val evalH3 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertionFled,
                transitions = fwTransitions,
                subject = gableEntity,
                objectEntity = conservatoryEntity,
                horizon = h3,
                speaker = gableEntity,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH3)
        assertEquals(EpistemicStatus.CLAIMED, evalH3.item.currentStatus)

        // Horizon 4: Retraction transition is unlocked only after holding cell logbook
        val h4 = Horizon.initial(fwEdition, RevealOrdinal(4))
        val evalH4 =
            RedactionPolicy.evaluateAssertion(
                assertion = assertionFled,
                transitions = fwTransitions,
                subject = gableEntity,
                objectEntity = conservatoryEntity,
                horizon = h4,
                speaker = gableEntity,
            )
        assertIs<RedactionResult.Visible<RevealedAssertion>>(evalH4)
        assertEquals(EpistemicStatus.CONTRADICTED, evalH4.item.currentStatus)
    }

    @Test
    fun testScenario3_negativeStubFailsInvariant_PSPOILER02_PSPOILER01() {
        val assertionFled = fwAssertions.first { it.assertionId == aGableClaimsColeFled }

        // Naive stub ignores speaker firstReveal gating and eagerly reveals contradictory status
        val naiveResult = NaiveHorizonStub.evaluateAssertion(assertionFled, fwTransitions)

        val failsInvariantAtH2 =
            runCatching {
                require(naiveResult.currentStatus == EpistemicStatus.CLAIMED) {
                    "Unreliable witness claim prematurely transitioned to CONTRADICTED"
                }
            }
        assertTrue(failsInvariantAtH2.isFailure)
    }

    // =========================================================================
    // Scenario 4: Delayed Identity Merge
    // Protected Invariant: P-SPOILER-03 (Delayed identity merge isolation)
    // =========================================================================

    @Test
    fun testScenario4_delayedIdentityMergeIsolation_PSPOILER03() {
        // --- Fixture 2: silent-courier ---
        val linkCourierVance = scIdentityLinks.first()

        for (ordinal in 1..3) {
            val h = Horizon.initial(scEdition, RevealOrdinal(ordinal))

            val evalLink = RedactionPolicy.evaluateIdentityLink(linkCourierVance, h)
            assertIs<RedactionResult.Redacted>(evalLink)

            val canonicalCourier =
                RedactionPolicy.resolveCanonicalEntityId(eCourier, scIdentityLinks, h)
            val canonicalVance =
                RedactionPolicy.resolveCanonicalEntityId(eVance, scIdentityLinks, h)

            assertEquals(eCourier, canonicalCourier)
            assertEquals(eVance, canonicalVance)
            assertNotEquals(canonicalCourier, canonicalVance)
        }

        // At ordinal 4: identity link is revealed and confirmed
        val h4 = Horizon.initial(scEdition, RevealOrdinal(4))
        val evalLinkH4 = RedactionPolicy.evaluateIdentityLink(linkCourierVance, h4)
        assertIs<RedactionResult.Visible<IdentityLink>>(evalLinkH4)

        val mergedCourier =
            RedactionPolicy.resolveCanonicalEntityId(eCourier, scIdentityLinks, h4)
        val mergedVance =
            RedactionPolicy.resolveCanonicalEntityId(eVance, scIdentityLinks, h4)
        assertEquals(mergedCourier, mergedVance)

        // --- Fixture 3: locked-stair ---
        val linkGreyIshida = lsIdentityLinks.first()

        for (ordinal in 1..2) {
            val h = Horizon.initial(lsEdition, RevealOrdinal(ordinal))
            assertIs<RedactionResult.Redacted>(
                RedactionPolicy.evaluateIdentityLink(linkGreyIshida, h),
            )

            val cIshida =
                RedactionPolicy.resolveCanonicalEntityId(eIshida, lsIdentityLinks, h)
            val cGrey =
                RedactionPolicy.resolveCanonicalEntityId(eGrey, lsIdentityLinks, h)
            assertEquals(eIshida, cIshida)
            assertEquals(eGrey, cGrey)
            assertNotEquals(cIshida, cGrey)
        }

        val hLs3 = Horizon.initial(lsEdition, RevealOrdinal(3))
        assertIs<RedactionResult.Visible<IdentityLink>>(
            RedactionPolicy.evaluateIdentityLink(linkGreyIshida, hLs3),
        )
        val mergedIshida =
            RedactionPolicy.resolveCanonicalEntityId(eIshida, lsIdentityLinks, hLs3)
        val mergedGrey =
            RedactionPolicy.resolveCanonicalEntityId(eGrey, lsIdentityLinks, hLs3)
        assertEquals(mergedIshida, mergedGrey)
    }

    @Test
    fun testScenario4_negativeStubFailsInvariant_PSPOILER03() {
        val naiveMergedCourier =
            NaiveHorizonStub.resolveCanonicalEntityId(eCourier, scIdentityLinks)
        val naiveMergedVance =
            NaiveHorizonStub.resolveCanonicalEntityId(eVance, scIdentityLinks)

        assertEquals(naiveMergedCourier, naiveMergedVance)

        val failsInvariant =
            runCatching {
                val currentHorizon = 2
                require(naiveMergedCourier != naiveMergedVance) {
                    "P-SPOILER-03 violated: personas merged prematurely at ordinal $currentHorizon"
                }
            }
        assertTrue(failsInvariant.isFailure)
    }

    // =========================================================================
    // Property Tests Over Arbitrary / Random Horizons
    // Validating P-SPOILER-01..06 and Fixture Forbidden Assertions
    // =========================================================================

    @Test
    fun testPropertyMonotonicityAcrossAllHorizons_PSPOILER01_06() {
        val testPacks = listOf(falseWitnessPack(), silentCourierPack(), lockedStairPack())

        for (pack in testPacks) {
            val maxOrdinal =
                maxOf(
                    pack.entities.maxOf { it.firstReveal },
                    pack.assertions.maxOf { it.firstReveal },
                    pack.names.maxOf { it.firstReveal },
                    pack.transitions.maxOfOrNull { it.revealOrdinal } ?: 0,
                )

            var prevEntityCount = 0
            var prevAssertionCount = 0
            var prevVisibleCount = 0

            for (r in 0..maxOrdinal + 2) {
                val horizon =
                    Horizon.initial(EditionId(pack.manifest.editionId.value), RevealOrdinal(r))
                val view = RedactionPolicy.evaluateGraphPack(pack = pack, horizon = horizon)

                assertTrue(
                    view.entities.size >= prevEntityCount,
                    "P-SPOILER-01 violated: entity count decreased at ordinal $r",
                )
                assertTrue(
                    view.assertions.size >= prevAssertionCount,
                    "P-SPOILER-02 violated: assertion count decreased at ordinal $r",
                )

                val countVisible =
                    RedactionPolicy.countVisible(pack.entities.map { it.firstReveal }, horizon)
                assertTrue(
                    countVisible >= prevVisibleCount,
                    "P-SPOILER-04 violated: visible count decreased at ordinal $r",
                )

                prevEntityCount = view.entities.size
                prevAssertionCount = view.assertions.size
                prevVisibleCount = countVisible
            }
        }
    }

    @Test
    fun testPropertyZeroFutureLeakAndForbiddenIsolation_PSPOILER01_02_03() {
        data class FixtureTestSpec(
            val pack: GraphPack,
            val links: List<IdentityLink>,
            val forbiddenMap: Map<Int, Set<String>>,
        )

        val specs =
            listOf(
                FixtureTestSpec(falseWitnessPack(), emptyList(), fwForbidden),
                FixtureTestSpec(silentCourierPack(), scIdentityLinks, scForbidden),
                FixtureTestSpec(lockedStairPack(), lsIdentityLinks, lsForbidden),
            )

        for (spec in specs) {
            val maxOrdinal = spec.forbiddenMap.keys.maxOrNull() ?: 4

            for (r in 1..maxOrdinal) {
                val horizon =
                    Horizon.initial(EditionId(spec.pack.manifest.editionId.value), RevealOrdinal(r))
                val view =
                    RedactionPolicy.evaluateGraphPack(
                        pack = spec.pack,
                        links = spec.links,
                        horizon = horizon,
                    )

                val visibleEntityIds = view.entities.map { it.entity.entityId.value }.toSet()
                val visibleNameIds =
                    view.entities
                        .flatMap { it.allRevealedNames.map { n -> n.nameId.value } }
                        .toSet()
                val visibleAssertionIds =
                    view.assertions.map { it.assertion.assertionId.value }.toSet()
                val visibleTransitionIds =
                    view.assertions.mapNotNull { it.activeTransition?.transitionId?.value }.toSet()
                val visibleLinkIds =
                    spec.links
                        .filter {
                            RedactionPolicy.evaluateIdentityLink(it, horizon) is
                                RedactionResult.Visible
                        }.map { it.linkId.value }
                        .toSet()

                val allVisibleIds =
                    visibleEntityIds +
                        visibleNameIds +
                        visibleAssertionIds +
                        visibleTransitionIds +
                        visibleLinkIds

                // 1. Verify fixture forbidden set: NOTHING forbidden is visible!
                val forbiddenForR = spec.forbiddenMap[r] ?: emptySet()
                for (forbiddenId in forbiddenForR) {
                    assertFalse(
                        allVisibleIds.contains(forbiddenId),
                        "Zero future leak violated: '$forbiddenId' present at ordinal $r",
                    )
                }

                // 2. Invariant: no entity, name, assertion, or transition exceeds ordinal r
                for (entity in view.entities) {
                    assertTrue(
                        entity.entity.firstReveal <= r,
                        "P-SPOILER-01 violated: entity firstReveal > horizon $r",
                    )
                    for (name in entity.allRevealedNames) {
                        assertTrue(
                            name.firstReveal <= r,
                            "P-SPOILER-01 violated: name firstReveal > horizon $r",
                        )
                    }
                    val canonical = entity.canonicalName
                    if (canonical != null) {
                        assertTrue(
                            canonical.firstReveal <= r,
                            "P-SPOILER-01 violated: canonicalName firstReveal > horizon $r",
                        )
                    }
                }

                for (assertion in view.assertions) {
                    assertTrue(
                        assertion.assertion.firstReveal <= r,
                        "P-SPOILER-02 violated: assertion firstReveal > horizon $r",
                    )
                    val transition = assertion.activeTransition
                    if (transition != null) {
                        assertTrue(
                            transition.revealOrdinal <= r,
                            "P-SPOILER-02 violated: active transition revealOrdinal > horizon $r",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testPropertyCrossSurfaceParity_PSPOILER04() {
        val candidates =
            listOf(
                CandidateSearchHit("hit:clockmaker", RevealOrdinal(1), "the Clockmaker"),
                CandidateSearchHit("hit:gable_scene", RevealOrdinal(2), "Mrs. Gable"),
                CandidateSearchHit("hit:workshop_pass", RevealOrdinal(3), "Southwark workshop"),
                CandidateSearchHit("hit:julian_civil", RevealOrdinal(3), "Julian Price"),
                CandidateSearchHit("hit:alibi_logbook", RevealOrdinal(4), "holding-cell logbook"),
            )

        for (r in 1..4) {
            val horizon = Horizon.initial(fwEdition, RevealOrdinal(r))
            val filteredHits = RedactionPolicy.filterSearchHits(candidates, horizon)
            val visibleCount =
                RedactionPolicy.countVisible(
                    candidates.map { it.matchedRevealOrdinal.value },
                    horizon,
                )

            assertEquals(
                filteredHits.size,
                visibleCount,
                "P-SPOILER-04 violated: search hits size does not match countVisible at ordinal $r",
            )

            for (candidate in candidates) {
                if (candidate.matchedRevealOrdinal.value > r) {
                    assertFalse(
                        filteredHits.contains(candidate.payload),
                        "P-SPOILER-04 violated: candidate leaked at horizon $r",
                    )
                } else {
                    assertTrue(
                        filteredHits.contains(candidate.payload),
                        "P-SPOILER-04 violated: candidate missing at horizon $r",
                    )
                }
            }
        }
    }

    @Test
    fun testPropertyNavigationDecoupling_PSPOILER05() {
        val horizon = Horizon.initial(fwEdition, RevealOrdinal(4))

        val rewoundReading = horizon.rewindReadingPosition(RevealOrdinal(1))
        assertEquals(RevealOrdinal(1), rewoundReading.readingPosition)
        assertEquals(RevealOrdinal(4), rewoundReading.knowledgeHorizon)
        assertEquals(RevealOrdinal(4), rewoundReading.graphViewPosition)

        val historicalView = rewoundReading.rewindGraphView(RevealOrdinal(2))
        assertEquals(RevealOrdinal(1), historicalView.readingPosition)
        assertEquals(RevealOrdinal(4), historicalView.knowledgeHorizon)
        assertEquals(RevealOrdinal(2), historicalView.graphViewPosition)

        val viewAt2 =
            RedactionPolicy.evaluateGraphPack(falseWitnessPack(), emptyList(), historicalView)
        assertFalse(viewAt2.entities.any { it.canonicalName?.displayText == "Julian Price" })
    }

    @Test
    fun testPropertyMetadataSideChannelPrevention_PSPOILER06() {
        val pack = falseWitnessPack()
        val totalEntityCount = pack.entities.size

        for (r in 0..2) {
            val horizon = Horizon.initial(fwEdition, RevealOrdinal(r))
            val visibleCount =
                RedactionPolicy.countVisible(pack.entities.map { it.firstReveal }, horizon)

            assertTrue(
                visibleCount < totalEntityCount,
                "P-SPOILER-06 violated: count leaked total pack count at ordinal $r",
            )
        }
    }

    @Test
    fun testPropertyRandomizedHorizonFuzzing() {
        val random = Random(42)
        val packs = listOf(falseWitnessPack(), silentCourierPack(), lockedStairPack())

        for (pack in packs) {
            for (trial in 1..50) {
                val randomOrdinal = random.nextInt(0, 100)
                val horizon =
                    Horizon.initial(
                        EditionId(pack.manifest.editionId.value),
                        RevealOrdinal(randomOrdinal),
                    )

                val view = RedactionPolicy.evaluateGraphPack(pack = pack, horizon = horizon)

                for (entity in view.entities) {
                    assertTrue(entity.entity.firstReveal <= randomOrdinal)
                    for (name in entity.allRevealedNames) {
                        assertTrue(name.firstReveal <= randomOrdinal)
                    }
                }
                for (assertion in view.assertions) {
                    assertTrue(assertion.assertion.firstReveal <= randomOrdinal)
                    val transition = assertion.activeTransition
                    if (transition != null) {
                        assertTrue(transition.revealOrdinal <= randomOrdinal)
                    }
                }
            }
        }
    }

    // =========================================================================
    // Explicit Negative Test: Stub Invariant Verification
    // Acceptance criterion: Every test fails against a stub that ignores the horizon
    // =========================================================================

    @Test
    fun testNaiveStubFailsAllInvariantAssertions() {
        // 1. Naive stub fails P-SPOILER-01 (aliasing / civil identity leak)
        val julianEntity = fwEntities.first { it.entityId == eJulian }
        val naiveEntity = NaiveHorizonStub.evaluateEntity(julianEntity, fwNames)
        assertFails {
            require(naiveEntity.canonicalName?.displayText != "Julian Price")
        }

        // 2. Naive stub fails P-SPOILER-02 (retroactive transition leak)
        val gableAssertion = fwAssertions.first { it.assertionId == aGableClaimsColeFled }
        val naiveAssertion = NaiveHorizonStub.evaluateAssertion(gableAssertion, fwTransitions)
        assertFails {
            require(naiveAssertion.currentStatus == EpistemicStatus.CLAIMED)
        }

        // 3. Naive stub fails P-SPOILER-03 (premature identity merge)
        val naiveMergedCourier =
            NaiveHorizonStub.resolveCanonicalEntityId(eCourier, scIdentityLinks)
        val naiveMergedVance =
            NaiveHorizonStub.resolveCanonicalEntityId(eVance, scIdentityLinks)
        assertFails {
            require(naiveMergedCourier != naiveMergedVance)
        }

        // 4. Naive stub fails forbidden item assertions for all 3 fixtures at ordinal 1
        val naiveFwIds =
            fwEntities.map { it.entityId.value }.toSet() +
                fwNames.map { it.nameId.value }.toSet() +
                fwAssertions.map { it.assertionId.value }.toSet() +
                fwTransitions.map { it.transitionId.value }.toSet()

        val forbiddenAt1 = fwForbidden[1]!!
        val leakedForbidden = naiveFwIds.intersect(forbiddenAt1)
        assertTrue(
            leakedForbidden.isNotEmpty(),
            "Naive stub unexpectedly passed forbidden check! Leaked items expected.",
        )
    }
}
