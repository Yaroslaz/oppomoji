package io.github.yaroslaz.oppomoji;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class EmojiCoverageTest {
    @Test public void cachedCoverageRoundTrips() {
        EmojiCoverage partial = EmojiCoverage.decode(new EmojiCoverage(3712, 3953, Arrays.asList("❤️‍🔥", "🙂‍↕️")).encode());
        assertEquals(3712, partial.supported); assertEquals(3953, partial.total);
        assertEquals(Arrays.asList("❤️‍🔥", "🙂‍↕️"), partial.missing); assertFalse(partial.complete());
        EmojiCoverage full = EmojiCoverage.decode(new EmojiCoverage(3953, 3953, Collections.emptyList()).encode());
        assertTrue(full.complete()); assertTrue(full.missing.isEmpty());
    }

    @Test public void rejectsDamagedCache() {
        assertNull(EmojiCoverage.decode(null)); assertNull(EmojiCoverage.decode("3953"));
        assertNull(EmojiCoverage.decode("x|3953|"));
    }
}
