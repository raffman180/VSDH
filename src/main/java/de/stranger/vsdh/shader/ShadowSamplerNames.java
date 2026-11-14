package de.stranger.vsdh.shader;

/**
 * Which sampler names stand for a shadow image. Asked for every sampler of every geometry draw, so it is
 * plain character tests; the regular expression it replaces allocated a matcher per question.
 */
final class ShadowSamplerNames {
    private ShadowSamplerNames() {}

    /** Exactly {@code shadow|watershadow|shadowtex[01](HW)?|shadowcolor[0-7]?}. */
    static boolean matches(String name) {
        if (!name.startsWith("shadow")) return name.equals("watershadow");
        int length = name.length();
        if (length == 6) return true; // "shadow"
        if (name.startsWith("tex", 6)) {
            if (length != 10 && length != 12) return false;
            char side = name.charAt(9);
            if (side != '0' && side != '1') return false;
            return length == 10 || (name.charAt(10) == 'H' && name.charAt(11) == 'W');
        }
        if (name.startsWith("color", 6)) {
            if (length == 11) return true;
            if (length != 12) return false;
            char index = name.charAt(11);
            return index >= '0' && index <= '7';
        }
        return false;
    }
}
