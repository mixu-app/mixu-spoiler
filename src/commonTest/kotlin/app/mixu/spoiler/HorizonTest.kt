// SPDX-License-Identifier: Apache-2.0
package app.mixu.spoiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HorizonTest {
    private val defaultEdition = EditionId("urn:mixu:edition:synthetic-001")
    private val foreignEdition = EditionId("urn:mixu:edition:synthetic-002")

    @Test
    fun testInitialState() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(10))
        assertEquals(defaultEdition, horizon.editionId)
        assertEquals(RevealOrdinal(10), horizon.readingPosition)
        assertEquals(RevealOrdinal(10), horizon.knowledgeHorizon)
        assertEquals(RevealOrdinal(10), horizon.graphViewPosition)
    }

    @Test
    fun testMonotonicAdvanceKnowledgeHorizon() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(10))
        val advanced = horizon.advanceKnowledgeHorizon(RevealOrdinal(25))

        assertEquals(RevealOrdinal(25), advanced.knowledgeHorizon)
        assertEquals(RevealOrdinal(25), advanced.readingPosition)
        assertEquals(RevealOrdinal(25), advanced.graphViewPosition)

        // Attempting to regress knowledge horizon must fail loudly
        val exception =
            assertFailsWith<IllegalArgumentException> {
                advanced.advanceKnowledgeHorizon(RevealOrdinal(20))
            }
        assertTrue(exception.message!!.contains("monotonic"))
    }

    @Test
    fun testEditionBindingDeniesForeignEdition() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(100))

        val permittedLocal = horizon.evaluate(RevealOrdinal(50), defaultEdition)
        assertEquals(SpoilerDecision.Allow, permittedLocal)
        assertTrue(horizon.isPermitted(RevealOrdinal(50), defaultEdition))

        val deniedForeign = horizon.evaluate(RevealOrdinal(50), foreignEdition)
        assertIs<SpoilerDecision.Deny>(deniedForeign)
        assertEquals(DenyReason.UNKNOWN_EDITION, deniedForeign.reason)
        assertFalse(horizon.isPermitted(RevealOrdinal(50), foreignEdition))
    }

    @Test
    fun testHorizonBoundaryDeniesFutureReveals() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(42))

        assertTrue(horizon.isPermitted(RevealOrdinal(0), defaultEdition))
        assertTrue(horizon.isPermitted(RevealOrdinal(42), defaultEdition))

        val deniedFuture = horizon.evaluate(RevealOrdinal(43), defaultEdition)
        assertIs<SpoilerDecision.Deny>(deniedFuture)
        assertEquals(DenyReason.BEYOND_HORIZON, deniedFuture.reason)
        assertFalse(horizon.isPermitted(RevealOrdinal(43), defaultEdition))
    }

    @Test
    fun testReadingPositionNavigationDoesNotMutateKnowledgeHorizon() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(50))

        // Jump backward in book
        val backward = horizon.rewindReadingPosition(RevealOrdinal(20))
        assertEquals(RevealOrdinal(20), backward.readingPosition)
        assertEquals(RevealOrdinal(50), backward.knowledgeHorizon)

        // Jump forward beyond current reading position but still <= horizon
        val forwardNav = backward.navigateReadingPosition(RevealOrdinal(35))
        assertEquals(RevealOrdinal(35), forwardNav.readingPosition)
        assertEquals(RevealOrdinal(50), forwardNav.knowledgeHorizon)

        // Even navigating past knowledge horizon does not mutate knowledge horizon without explicit unlock
        val pastNav = forwardNav.navigateReadingPosition(RevealOrdinal(70))
        assertEquals(RevealOrdinal(70), pastNav.readingPosition)
        assertEquals(RevealOrdinal(50), pastNav.knowledgeHorizon)
        // Information at 60 is still blocked
        assertFalse(pastNav.isPermitted(RevealOrdinal(60), defaultEdition))
    }

    @Test
    fun testGraphViewRewindAndAdvance() {
        val horizon = Horizon.initial(defaultEdition, RevealOrdinal(50))

        // Rewind historical graph view to ordinal 15
        val rewound = horizon.rewindGraphView(RevealOrdinal(15))
        assertEquals(RevealOrdinal(15), rewound.graphViewPosition)
        assertEquals(RevealOrdinal(50), rewound.knowledgeHorizon)

        // Advance graph view forward up to horizon
        val advancedView = rewound.advanceGraphView(RevealOrdinal(40))
        assertEquals(RevealOrdinal(40), advancedView.graphViewPosition)

        // Graph view cannot exceed knowledge horizon
        assertFailsWith<IllegalArgumentException> {
            advancedView.advanceGraphView(RevealOrdinal(51))
        }

        // Invalid rewind forward or invalid advance backward must fail
        assertFailsWith<IllegalArgumentException> {
            rewound.rewindGraphView(RevealOrdinal(30))
        }
    }

    @Test
    fun testValueClassInvariants() {
        assertFailsWith<IllegalArgumentException> {
            RevealOrdinal(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            EditionId("")
        }
        assertFailsWith<IllegalArgumentException> {
            EditionId("   ")
        }
    }
}
