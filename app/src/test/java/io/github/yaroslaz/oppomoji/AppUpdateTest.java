package io.github.yaroslaz.oppomoji;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdateTest {
    @Test public void comparesVersionsNumericallyWithPreReleasesFirst() {
        assertTrue(AppUpdate.compare("0.1.10-alpha", "0.1.9-alpha") > 0);
        assertTrue(AppUpdate.compare("0.1.9-alpha", "0.1.10-alpha") < 0);
        assertEquals(0, AppUpdate.compare("0.1.9-alpha", "0.1.9-alpha"));
        assertTrue(AppUpdate.compare("0.2.0", "0.2.0-alpha") > 0);
        assertTrue(AppUpdate.compare("0.2.0-beta", "0.2.0-alpha") > 0);
        assertTrue(AppUpdate.compare("1.0", "0.9.9") > 0);
        assertEquals(0, AppUpdate.compare("1.0", "1.0.0"));
    }

    @Test public void readsTheChecksumForTheExactApkName() {
        String a = "a".repeat(64), b = "B".repeat(64);
        String sums = a + "  OPPOMoji-0.1.10-alpha.apk.idsig\n" + b + " *OPPOMoji-0.1.10-alpha.apk\n";
        assertEquals("b".repeat(64), AppUpdate.checksum(sums, "OPPOMoji-0.1.10-alpha.apk"));
        assertNull(AppUpdate.checksum(sums, "OPPOMoji-0.1.9-alpha.apk"));
        assertNull(AppUpdate.checksum("xyz  OPPOMoji-0.1.10-alpha.apk", "OPPOMoji-0.1.10-alpha.apk"));
    }
}
