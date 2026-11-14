package com.braffolk.dhvulkan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DhSupportTest {
    @Test void withoutDhTheAdapterIsOffAndNothingIsWrong() {
        var result = DhSupport.evaluate(null, false, false);
        assertEquals(DhSupport.Status.ABSENT, result.status());
        assertFalse(result.active());
    }

    @Test void everySupportedVersionWithPlainNamesRunsUnchanged() {
        for (String version : new String[]{"3.2.0-b", "3.3.1", "3.3.2", "3.3.3"}) {
            var result = DhSupport.evaluate(version, false, false);
            assertEquals(DhSupport.Status.ACTIVE, result.status(), version);
            assertTrue(result.active());
            assertEquals(version, result.version());
        }
    }

    @Test void dh330RunsOnlyWhenVsdhWasAdaptedToItsTwins() {
        assertEquals(DhSupport.Status.ACTIVE, DhSupport.evaluate("3.3.0", true, true).status());
        // The nested adapter refused an unknown 3.3.0 file or did not load: clear message, adapter off.
        var refused = DhSupport.evaluate("3.3.0", true, false);
        assertEquals(DhSupport.Status.ADAPTER_MISMATCH, refused.status());
        assertFalse(refused.active());
        assertTrue(refused.message().contains("3.3.0") && refused.message().contains("stay off"), refused.message());
        // A file that claims 3.3.0 but has no twins must not run against rewritten classes either.
        assertEquals(DhSupport.Status.ADAPTER_MISMATCH, DhSupport.evaluate("3.3.0", false, true).status());
        // Structural, not by version: twins in another supported version are honoured the same way.
        assertEquals(DhSupport.Status.ADAPTER_MISMATCH, DhSupport.evaluate("3.3.1", true, false).status());
    }

    @Test void unknownVersionsAreRefusedWithTheSupportedList() {
        for (String version : new String[]{"3.3.4", "3.4.0", "3.2.0", "3.2.0-a", "2.4.5-b", "3.3.0-dev", "unknown", ""}) {
            for (boolean twins : new boolean[]{false, true}) {
                var result = DhSupport.evaluate(version, twins, twins);
                assertEquals(DhSupport.Status.UNSUPPORTED_VERSION, result.status(), version);
                assertFalse(result.active());
                for (String supported : DhSupport.SUPPORTED_VERSIONS) assertTrue(result.message().contains(supported), result.message());
            }
        }
    }

    @Test void theAdaptedProbeIsTheRewrittenClassName() {
        assertFalse(DhSupport.adapted(), "unit tests load VSDH's classes unchanged");
        assertFalse(DhSupport.adapted("com.seibel.distanthorizons.core.config.Config"));
        assertTrue(DhSupport.adapted("com.seibel.distanthorizons.core.config.Config_neoforge"));
    }

    @Test void modsTomlAcceptsExactlyTheSupportedVersionsAsAnOptionalDependency() throws Exception {
        String toml = Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml"));
        var dependency = Pattern.compile("modId=\"distanthorizons\"\\s+type=\"(\\w+)\"\\s+versionRange=\"([^\"]+)\"").matcher(toml);
        assertTrue(dependency.find());
        assertEquals("optional", dependency.group(1));
        Set<String> accepted = new TreeSet<>();
        for (String part : dependency.group(2).split(",")) {
            assertTrue(part.startsWith("[") && part.endsWith("]"), "exact versions only: " + part);
            accepted.add(part.substring(1, part.length() - 1));
        }
        assertEquals(new TreeSet<>(DhSupport.SUPPORTED_VERSIONS), accepted);
    }
}
