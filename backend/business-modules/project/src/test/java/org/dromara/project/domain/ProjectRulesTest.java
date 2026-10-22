package org.dromara.project.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dev")
class ProjectRulesTest {
    @Test
    void registrationRequiresDraftAndPreventsDuplicateSubmission() {
        assertEquals("REGISTRATION_REVIEW", ProjectRules.submit("DRAFT", "DIRECT", "REGISTRATION"));
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.submit("REGISTRATION_REVIEW", "DIRECT", "REGISTRATION"));
    }

    @Test
    void directProjectCannotBidAndTenderMustWinBeforeInitiation() {
        assertThrows(
                RuntimeException.class, () -> ProjectRules.submit("REGISTERED", "DIRECT", "BID"));
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.submit("REGISTERED", "TENDER", "INITIATION"));
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.submit("BID_LOST", "TENDER", "INITIATION"));
        assertEquals("INITIATION_REVIEW", ProjectRules.submit("BID_WON", "TENDER", "INITIATION"));
    }

    @Test
    void returnedApplicationRestoresExactEligibleStage() {
        assertEquals(
                "DRAFT",
                ProjectRules.decide("REGISTRATION_REVIEW", "DIRECT", "REGISTRATION", false));
        assertEquals("REGISTERED", ProjectRules.decide("BID_REVIEW", "TENDER", "BID", false));
        assertEquals(
                "BID_WON", ProjectRules.decide("INITIATION_REVIEW", "TENDER", "INITIATION", false));
        assertEquals(
                "REGISTERED",
                ProjectRules.decide("INITIATION_REVIEW", "DIRECT", "INITIATION", false));
    }

    @Test
    void approvedInitiationIsTerminalAndCannotBeSubmittedAgain() {
        assertEquals(
                "ESTABLISHED",
                ProjectRules.decide("INITIATION_REVIEW", "DIRECT", "INITIATION", true));
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.submit("ESTABLISHED", "DIRECT", "INITIATION"));
        assertThrows(RuntimeException.class, () -> ProjectRules.requireTerminable("ESTABLISHED"));
        assertThrows(RuntimeException.class, () -> ProjectRules.requireTerminable("BID_REVIEW"));
        assertThrows(RuntimeException.class, () -> ProjectRules.requireTerminable("TERMINATED"));
    }

    @Test
    void staleCallbackCannotApproveADifferentStage() {
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.decide("REGISTERED", "TENDER", "BID", true));
    }

    @Test
    void reviewerMayReadButCannotEditOtherOwnersProject() {
        ProjectRules.requireReadable("owner", "reviewer", true);
        assertThrows(RuntimeException.class, () -> ProjectRules.requireOwner("owner", "reviewer"));
        assertThrows(
                RuntimeException.class,
                () -> ProjectRules.requireReadable("owner", "other", false));
        ProjectRules.requireOwner("owner", "owner");
    }
}
