package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowSamplerNamesTest {
    private static final Pattern REFERENCE = Pattern.compile("shadow|watershadow|shadowtex[01](?:HW)?|shadowcolor[0-7]?");

    @Test
    void agreesWithTheRegularExpressionItReplaced() {
        String[] stems = {"", "shadow", "watershadow", "shadowtex", "shadowcolor", "shadowtex0", "shadowtex1", "shadowtex2",
                "shadowtex0HW", "shadowtex1HW", "shadowtex1Hw", "shadowtex0H", "shadowtex0HWx", "shadowcolor0", "shadowcolor7",
                "shadowcolor8", "shadowcolor00", "shadowcolorimg0", "shadows", "Shadow", "watershadow1", "colortex0", "depthtex1",
                "shadowte", "shadowco", "shadowtexHW", "shadowtex01", "noisetex", "gtexture", "dhDepthTex0", "shadowcolor1HW"};
        for (String name : stems)
            assertEquals(REFERENCE.matcher(name).matches(), ShadowSamplerNames.matches(name), name);
        // every name of up to two characters appended to the two stems
        String alphabet = "0123456789HWhw_x";
        for (String stem : new String[]{"shadowtex", "shadowcolor", "shadow"})
            for (char a : alphabet.toCharArray()) {
                assertEquals(REFERENCE.matcher(stem + a).matches(), ShadowSamplerNames.matches(stem + a), stem + a);
                for (char b : alphabet.toCharArray()) {
                    String name = stem + a + b;
                    assertEquals(REFERENCE.matcher(name).matches(), ShadowSamplerNames.matches(name), name);
                    for (char c : alphabet.toCharArray())
                        assertEquals(REFERENCE.matcher(name + c).matches(), ShadowSamplerNames.matches(name + c), name + c);
                }
            }
    }
}
