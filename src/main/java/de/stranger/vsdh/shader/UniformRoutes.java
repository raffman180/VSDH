package de.stranger.vsdh.shader;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Which provider fills a uniform member, decided once per program instead of once per member and write.
 *
 * <p>{@link WorldUniforms} asks its providers in a fixed order (pack formulas, pack world values, built-in world
 * values, Iris standard values, zero-filled, own values); each of them first looks the member's name up in its own
 * name set. Those sets do not change while a pack runs, so the first provider that knows a name is always the same
 * one. A route only names that provider: the provider's own write still runs and still decides, and a write that
 * unexpectedly declines falls back to the full order. {@code -Dvsdh.uniformRoutes=false} routes everything through
 * the full order (A/B checks).
 */
final class UniformRoutes {
    static final boolean ENABLED = !"false".equals(System.getProperty("vsdh.uniformRoutes"));

    /** The full provider order, as before. */
    static final byte GENERIC = 0;
    static final byte CUSTOM = 1, PACK = 2, BUILTIN = 3, STANDARD = 4, ZERO = 5, OWN = 6;

    private UniformRoutes() {}

    /** The first provider, in the order of the write loop, whose name set holds {@code name}. */
    static byte route(String name, Predicate<String> custom, Set<String> pack, Set<String> builtin,
                      Set<String> standard, Set<String> zeroFilled) {
        if (custom.test(name)) return CUSTOM;
        if (pack.contains(name)) return PACK;
        if (builtin.contains(name)) return BUILTIN;
        if (standard.contains(name)) return STANDARD;
        if (zeroFilled.contains(name)) return ZERO;
        return OWN;
    }
}
