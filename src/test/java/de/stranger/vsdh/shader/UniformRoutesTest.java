package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A route must name the provider the full order of the uniform write loop would have reached first. */
class UniformRoutesTest {
    private static final Set<String> CUSTOM = Set.of("a", "ab", "abc", "abcd", "abcde");
    private static final Set<String> PACK = Set.of("ab", "b", "bc", "bcd", "bcde");
    private static final Set<String> BUILTIN = Set.of("abc", "bc", "c", "cd", "cde");
    private static final Set<String> STANDARD = Set.of("abcd", "bcd", "cd", "d", "de");
    private static final Set<String> ZERO = Set.of("abcde", "bcde", "cde", "de", "e");

    /** The order of the loop before routes existed: every provider is asked until one accepts. */
    private static byte fullOrder(String name) {
        if (CUSTOM.contains(name)) return UniformRoutes.CUSTOM;
        if (PACK.contains(name)) return UniformRoutes.PACK;
        if (BUILTIN.contains(name)) return UniformRoutes.BUILTIN;
        if (STANDARD.contains(name)) return UniformRoutes.STANDARD;
        if (ZERO.contains(name)) return UniformRoutes.ZERO;
        return UniformRoutes.OWN;
    }

    @Test
    void firstProviderInLoopOrderWins() {
        var names = List.of("a", "ab", "abc", "abcd", "abcde", "b", "bc", "bcd", "bcde", "c", "cd", "cde", "d", "de",
                "e", "gbufferModelView", "");
        for (String name : names)
            assertEquals(fullOrder(name), UniformRoutes.route(name, CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO), name);
        assertEquals(UniformRoutes.CUSTOM, UniformRoutes.route("abcde", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
        assertEquals(UniformRoutes.PACK, UniformRoutes.route("bcde", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
        assertEquals(UniformRoutes.BUILTIN, UniformRoutes.route("cde", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
        assertEquals(UniformRoutes.STANDARD, UniformRoutes.route("de", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
        assertEquals(UniformRoutes.ZERO, UniformRoutes.route("e", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
        assertEquals(UniformRoutes.OWN, UniformRoutes.route("far", CUSTOM::contains, PACK, BUILTIN, STANDARD, ZERO));
    }

    @Test
    void genericIsTheDefaultOfAnUnsetRoute() {
        assertEquals(0, UniformRoutes.GENERIC); // a fresh byte[] routes everything through the full order
    }
}
