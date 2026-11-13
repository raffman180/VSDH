package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/**
 * Descriptors for the {@code image.<name>} resources a program declares: storage images under the
 * directive's own name, sampled images under its sampler name, both in GENERAL layout and both
 * resolved from the current graph's {@link NativeCustomImages}.
 */
final class CustomImageBindings {
    private static final Pattern FORMAT = Pattern.compile("layout\\(\\s*(\\w+)\\s*,[^)]*binding\\s*=\\s*(\\d+)\\s*\\)");

    record Result(List<ImageDescriptor> descriptors, Set<String> names, boolean stores) {}

    private CustomImageBindings() {}

    /** Whether a declared opaque name is one of the pack's custom images (either spelling). */
    static boolean handles(String name) {
        var images = NativeCustomImages.current();
        return images != null && images.has(dev.vitrail.pack.texture.CustomImages.originalName(name));
    }

    static Result of(String program, TranslatedProgram translated, VulkanProgramCompiler.Compiled compiled, Set<Integer> active) {
        var images = NativeCustomImages.current();
        var descriptors = new ArrayList<ImageDescriptor>();
        var names = new java.util.HashSet<String>();
        boolean stores = false;
        if (images == null) return new Result(descriptors, names, false);
        var formats = new HashMap<Integer, String>();
        for (String source : compiled.sources().values()) {
            var matcher = FORMAT.matcher(source);
            while (matcher.find()) formats.putIfAbsent(Integer.parseInt(matcher.group(2)), matcher.group(1));
        }
        for (var sampler : translated.samplers()) {
            Integer binding = compiled.bindings().get(sampler.name());
            if (binding == null || !active.contains(binding)) continue;
            String original = dev.vitrail.pack.texture.CustomImages.originalName(sampler.name());
            if (!images.has(original)) continue;
            if (!original.equals(sampler.name()))
                throw new IllegalArgumentException("Typed custom image views are not implemented: " + program + "/" + sampler.name());
            if (sampler.declaration().contains("["))
                throw new IllegalArgumentException("Custom image arrays are not implemented: " + sampler.declaration());
            String name = sampler.name();
            VulkanImage image = images.image(name);
            boolean storage = sampler.type().matches("[iu]?image\\w+");
            if (storage) {
                if (!images.storage(name))
                    throw new IllegalArgumentException("Custom image " + name + " is declared as an image under its sampler name in " + program);
                Integer format = formats.get(binding) == null ? null : NativeComputePass.vulkanFormat(formats.get(binding));
                if (format == null || format != image.format)
                    throw new IllegalArgumentException("Custom image " + name + " format " + formats.get(binding) + " does not match its directive in " + program);
                descriptors.add(new ImageDescriptor(binding, sampler.type(), name, 0, true) {
                    @Override public VulkanImage getImage() { return images.image(this.name); }
                    @Override public boolean usesVanillaTextureBinding() { return false; }
                });
                stores = true;
            } else {
                if (!sampler.type().matches("[iu]?sampler(1D|2D|3D)"))
                    throw new IllegalArgumentException("Unsupported custom image sampler " + sampler.declaration() + " in " + program);
                var descriptor = new ImageDescriptor(binding, sampler.type(), name, 0) {
                    @Override public VulkanImage getImage() { return images.image(this.name); }
                    @Override public boolean usesVanillaTextureBinding() { return false; }
                    @Override public long getSampler(VulkanImage image) { return image.getSampler(); }
                };
                descriptor.setLayout(VK_IMAGE_LAYOUT_GENERAL);
                descriptors.add(descriptor);
            }
            names.add(name);
        }
        return new Result(descriptors, names, stores);
    }
}
