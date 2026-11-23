package com.jujin.jeno.resolve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins Maven's ordering on the shapes Central actually ships, plus the
 * major-extraction rule the cross-major conflict stands on.
 */
class VersionsTest {

    @Test
    void numericTokensCompareAsNumbers() {
        assertTrue(Versions.compare("1.2.3", "1.2.10") < 0, "1.2.3 < 1.2.10");
        assertTrue(Versions.compare("1.10.0", "1.9.0") > 0, "1.10.0 > 1.9.0");
        assertEquals(0, Versions.compare("1.0", "1.0.0"), "absent tokens pad as zero");
        assertEquals(0, Versions.compare("1.01", "1.1"), "leading zeros parse as numbers");
    }

    @Test
    void knownQualifiersRideMavensLadder() {
        assertTrue(Versions.compare("1.0-alpha", "1.0-beta") < 0);
        assertTrue(Versions.compare("1.0-beta", "1.0-milestone") < 0);
        assertTrue(Versions.compare("1.0-milestone", "1.0-rc1") < 0);
        assertTrue(Versions.compare("1.0-rc1", "1.0-snapshot") < 0);
        assertTrue(Versions.compare("1.0-snapshot", "1.0") < 0, "a pre-release is below the release");
        assertTrue(Versions.compare("1.0", "1.0-sp1") < 0, "sp is above the release");
        assertEquals(0, Versions.compare("1.0-rc1", "1.0-cr1"), "rc and cr are the same rung");
        assertEquals(0, Versions.compare("1.0-ga", "1.0"), "ga is the release");
    }

    @Test
    void unknownStringsSortBetweenSnapshotAndRelease() {
        assertTrue(Versions.compare("1.0-unknown", "1.0") < 0, "an unknown string is pre-release");
        assertTrue(Versions.compare("1.0-unknown", "1.0-rc1") > 0, "unknown beats known pre-releases");
        assertTrue(Versions.compare("30.0-android", "30.0-jre") < 0, "strings compare lexically");
    }

    @Test
    void numbersOutrankStringsAtTheSamePosition() {
        assertTrue(Versions.compare("1.0.1", "1.0.alpha") > 0);
        assertTrue(Versions.compare("2023.01", "2023.02") < 0);
    }

    @Test
    void lettersFlowIntoDigitsAsSeparateTokens() {
        assertTrue(Versions.compare("1.0-alpha1", "1.0-alpha2") < 0);
        assertEquals(0, Versions.compare("1.0-alpha-1", "1.0-alpha1"));
    }

    @Test
    void majorOfIsBestEffortForCentral() {
        assertEquals(1, Versions.majorOf("1.2.3"));
        assertEquals(30, Versions.majorOf("30.0-jre"));
        assertEquals(2023, Versions.majorOf("2023.01"));
        assertNull(Versions.majorOf("alpha-1"), "no leading digits — no major");
    }

    @Test
    void nativeSemverIsExact() {
        assertTrue(Versions.isNativeSemver("1.2.3"));
        assertTrue(Versions.isNativeSemver("1.2.3-rc1"));
        assertTrue(!Versions.isNativeSemver("1.2"));
        assertTrue(!Versions.isNativeSemver("1.2.3.4"));
        assertTrue(!Versions.isNativeSemver("v1.2.3"));
    }
}
