package de.stranger.vsdh.shader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Smaller shadow map sizes a pack itself offers, for when its chosen size does not fit the GPU. */
final class ShadowResolutionFallback {
    private ShadowResolutionFallback() {}

    /** The pack's listed shadowMapResolution values below {@code current}, largest first, without duplicates. */
    static List<Integer> smallerResolutions(List<String> listed, int current) {
        var smaller = new ArrayList<Integer>();
        for (String value : listed) {
            try {
                int parsed = (int) Math.round(Double.parseDouble(value.strip()));
                if (parsed > 0 && parsed < current && !smaller.contains(parsed)) smaller.add(parsed);
            } catch (NumberFormatException notANumber) {
                // a listed value that is no number cannot be a resolution
            }
        }
        smaller.sort(Comparator.reverseOrder());
        return smaller;
    }

    /**
     * A pack prepared before the graphics device existed (the configured pack at game start) could not be sized
     * against GPU memory. True when it now turns out too large: it is then prepared once more, with the device
     * known, so the smaller shadow map is found there instead of the pack failing at world join.
     */
    static boolean needsRecheck(boolean budgetKnown, long shadowBytes, long budgetNow) {
        return !budgetKnown && budgetNow > 0 && shadowBytes > budgetNow;
    }
}
