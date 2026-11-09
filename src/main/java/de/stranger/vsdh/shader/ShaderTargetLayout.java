package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetPlan;

import java.util.ArrayList;
import java.util.List;

/** Device-independent allocation preflight, including both ping-pong halves and every mip level. */
public record ShaderTargetLayout(List<Target> targets, long bytes, int screenWidth, int screenHeight) {
    public record Target(int index, TargetFormat format, int width, int height, int levels,
                         boolean doubled, boolean clears, boolean fogClear, TargetDirectives.Colour clearColour) {
        public long bytes() {
            long pixels = 0;
            for (int level = 0; level < levels; level++) pixels = Math.addExact(pixels, (long) Math.max(1, width >> level) * Math.max(1, height >> level));
            return Math.multiplyExact(Math.multiplyExact(pixels, format.bytesPerPixel()), doubled ? 2 : 1);
        }
    }
    public ShaderTargetLayout { targets = List.copyOf(targets); }

    public static ShaderTargetLayout of(TargetPlan plan, int width, int height, int maxDimension, long budgetBytes) {
        if (width <= 0 || height <= 0 || maxDimension <= 0 || budgetBytes <= 0)
            throw new IllegalArgumentException("Positive image dimensions and allocation budget required");
        var targets = new ArrayList<Target>();
        long bytes = 0;
        for (int index : plan.ordered()) {
            var directives = plan.directives();
            int targetWidth = directives.size(index).width(width);
            int targetHeight = directives.size(index).height(height);
            if (targetWidth > maxDimension || targetHeight > maxDimension)
                throw new IllegalArgumentException("colortex" + index + " exceeds device image limit: " + targetWidth + "x" + targetHeight);
            int levels = directives.mipmapped().contains(index)
                    ? 32 - Integer.numberOfLeadingZeros(Math.max(targetWidth, targetHeight)) : 1;
            var target = new Target(index, directives.format(index).used(), targetWidth, targetHeight, levels,
                    plan.schedule().doubled().contains(index), directives.clears(index),
                    index == 0 && !directives.declaresClearColour(index), clearColour(directives, index));
            targets.add(target);
            bytes = Math.addExact(bytes, target.bytes());
            if (bytes > budgetBytes) throw new IllegalArgumentException("Shader color targets require " + bytes + " bytes; budget=" + budgetBytes);
        }
        return new ShaderTargetLayout(targets, bytes, width, height);
    }

    private static TargetDirectives.Colour clearColour(TargetDirectives directives, int index) {
        var colour = directives.clearColour(index);
        return directives.format(index).alphaAdded()
                ? new TargetDirectives.Colour(colour.r(), colour.g(), colour.b(), 1) : colour;
    }
}
