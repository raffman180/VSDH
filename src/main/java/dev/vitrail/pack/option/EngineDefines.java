package dev.vitrail.pack.option;

import dev.vitrail.pack.model.RenderStage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The symbols the engine has to define before a pack sees its own code.
 * <p>
 * Packs branch on these to decide what they can use, so a missing one does not fail loudly: it
 * quietly takes the pack down a fallback path meant for a renderer from ten years ago. The
 * table is ordered because it is emitted in order, and an ordered emission is a diffable one.
 * <p>
 * What the machine is is an argument rather than something read here, because nothing in this
 * package may name a graphics API and because the table has to be buildable in the harness with no
 * device at all. The classification of a vendor or a driver string is OptiFine's specification and
 * lives here; the strings themselves come from the caller.
 */
public final class EngineDefines {

	/**
	 * Iris feature flags the VSDH native host implements. A pack requiring anything else through
	 * {@code iris.features.required} is refused; each of these is also announced as a define.
	 * <ul>
	 * <li>HIGHER_SHADOWCOLOR: eight shadowcolor targets for a pack that declares it (TargetPlan),
	 * also as shadowcolorimgN in shadowcomp (Rethinking Voxels' interactive water).</li>
	 * <li>BLOCK_EMISSION_ATTRIBUTE: terrain carries the block's light in {@code at_midBlock.w}
	 * (ShaderTerrainVertex), which Bliss, Eclipse, Miniature and Rethinking Voxels read.</li>
	 * <li>ENTITY_TRANSLUCENT: gbuffers_entities_translucent / gbuffers_block_translucent are drawn
	 * for translucent entity and block-entity layers (EntityPackPlan).</li>
	 * </ul>
	 * Iris announces these unconditionally, so a pack's main path is the one written for them.
	 */
	public static final java.util.Set<String> FEATURES = java.util.Set.of("COMPUTE_SHADERS", "SSBO", "CUSTOM_IMAGES",
			"HIGHER_SHADOWCOLOR", "BLOCK_EMISSION_ATTRIBUTE", "ENTITY_TRANSLUCENT");

	/**
	 * Kept as OptiFine's packed form, major times 10000 plus minor times 100 plus patch. It is
	 * a Minecraft version rather than ours, because that is what packs compare against.
	 */
	public static final int DEFAULT_MC_VERSION = 12101;

	/**
	 * The Iris release whose behaviour this engine mirrors, 1.11.2 on the 26.1 branch, in the
	 * same packed form. Packs compare against it to pick between an old-Iris workaround and the
	 * behaviour the mirrored release fixed, so leaving it out sends every such test down the
	 * workaround: an undefined name reads as zero, which claims an Iris older than any release.
	 * The number comes alone: the capability symbols Iris posts beside it are promises, and each
	 * stays unposted until the capability is served, as the note under IS_IRIS says.
	 */
	private static final int IRIS_VERSION = 10814;

	/** The game's own default, four levels, until a caller says otherwise. */
	private static final int DEFAULT_MIPMAP_LEVEL = 4;

	/**
	 * The highest colour attachment a pack may name. Iris's number, and it is the one packs are
	 * written against rather than anything a device reports.
	 */
	private static final int MAX_COLOR_BUFFERS = 32;

	private static volatile Environment machine = Environment.of(DEFAULT_MC_VERSION);

	private EngineDefines() {
	}

	/**
	 * What the machine is. One value for the whole process, because there is one machine, and
	 * three readers that all have to be handed the same table: the preprocessor decides which
	 * branch of a pack is live, the translator writes the same symbols back out as {@code
	 * #define} lines, and the settings screen tests them. A pack read against one table and
	 * compiled against another names biomes the engine cannot answer with.
	 * <p>
	 * The default is the empty one, which is what the harness and the corpus measurements run
	 * against: nothing there has a registry to walk, so nothing there should see a
	 * {@code BIOME_} symbol either.
	 */
	public static void machine(Environment environment) {
		machine = environment;
	}

	public static Environment machine() {
		return machine;
	}

	/** The operating systems OptiFine has a symbol for. Solaris has none, so it lands on other. */
	public enum Os {
		WINDOWS,
		MAC,
		LINUX,
		OTHER
	}

	/**
	 * The convention a resource pack says its material maps are drawn in, named in
	 * {@code optifine/texture.properties}, with the version of it where the file carries one.
	 * <p>
	 * What is carried is the text the resource pack wrote rather than what this engine makes of it:
	 * the version changes what the channels mean to the PACK and nothing on this side reduces or
	 * filters differently for it, but a pack branches on the symbol built from it.
	 *
	 * @param version null where the file names the convention without one
	 */
	public record TextureFormat(String name, String version) {
	}

	/**
	 * What the pack is being read for.
	 *
	 * @param vendorName      the device's vendor as the driver reports it, classified here
	 * @param rendererName    the driver's own description, classified here
	 * @param distantHorizons whether the far terrain of Distant Horizons is both there and reaching
	 *                        the pack, which is a promise and not an installed mod list; see the
	 *                        symbol below
	 * @param biomes          registered biome path to id, in the order the ids were handed out. It
	 *                        is the id a pack's biome comparison is written against, so the two
	 *                        have to come from the same place
	 * @param biomeCategories the category names, in ordinal order, which is what makes
	 *                        {@code CAT_DESERT} the number the biome value carries
	 * @param textureFormat   what the resource pack declares its material maps are drawn in, null
	 *                        where it declares nothing or names a convention this engine has no
	 *                        symbol for
	 * @param bufferBlending  whether the device lets two attachments of one pass blend differently.
	 *                        A device answer and not an engine one, which is why it travels with
	 *                        the vendor and the renderer rather than being read here
	 */
	public record Environment(int mcVersion, Os os, String vendorName, String rendererName,
			int mipmapLevel, boolean distantHorizons, Map<String, Integer> biomes,
			List<String> biomeCategories, TextureFormat textureFormat, boolean bufferBlending) {

		/** What a caller with no resource pack to ask hands over: {@link #of}, and the harness. */
		public Environment(int mcVersion, Os os, String vendorName, String rendererName,
				int mipmapLevel, boolean distantHorizons, Map<String, Integer> biomes,
				List<String> biomeCategories) {
			this(mcVersion, os, vendorName, rendererName, mipmapLevel, distantHorizons, biomes,
					biomeCategories, null, false);
		}

		public static Environment of(int mcVersion) {
			return new Environment(mcVersion, Os.WINDOWS, "", "", DEFAULT_MIPMAP_LEVEL, false,
					Map.of(), List.of());
		}
	}

	/** What a caller with no device to ask gets, which is the harness and the corpus measurements. */
	public static Map<String, String> table(int mcVersion) {
		return table(Environment.of(mcVersion));
	}

	@SuppressWarnings("EnumOrdinal")
	public static Map<String, String> table(Environment environment) {
		Map<String, String> defines = new LinkedHashMap<>();

		defines.put("MC_VERSION", Integer.toString(environment.mcVersion()));
		defines.put("IRIS_VERSION", Integer.toString(IRIS_VERSION));
		defines.put("MC_GL_VERSION", "460");
		defines.put("MC_GLSL_VERSION", "460");
		String os = osSymbol(environment.os());
		if (os != null) {
			defines.put(os, "");
		}
		defines.put(vendorSymbol(environment.vendorName()), "");
		defines.put(rendererSymbol(environment.rendererName()), "");
		defines.put("MC_RENDER_QUALITY", "1.0");
		defines.put("MC_SHADOW_QUALITY", "1.0");
		defines.put("MC_HAND_DEPTH", "0.125");
		defines.put("MC_MIPMAP_LEVEL", Integer.toString(environment.mipmapLevel()));
		// The renderer binds neutral material textures when the pack has no override.
		defines.put("MC_NORMAL_MAP", "");
		defines.put("MC_SPECULAR_MAP", "");

		// What the resource pack says its material maps mean, beside the two names above and where
		// Iris poses it (gl/shader/StandardMacros.java:107-112, built at
		// pbr/format/TextureFormat.java:18-33). A pack written against the symbol guards its labPBR
		// decode on it, and the version symbol tells such a pack which revision of the convention
		// it is reading. Posed only where a resource pack names a convention in
		// optifine/texture.properties, so an install where nothing names one sees neither symbol.
		TextureFormat format = environment.textureFormat();
		if (format != null) {
			String symbol = "MC_TEXTURE_FORMAT_"
					+ format.name().toUpperCase(Locale.ROOT).replace('-', '_');
			defines.put(symbol, "");
			if (format.version() != null) {
				defines.put(symbol + "_" + format.version().replace('.', '_').replace('-', '_'), "");
			}
		}

		defines.put("MAX_COLOR_BUFFERS", Integer.toString(MAX_COLOR_BUFFERS));

		// VSDH imports the pack language, not Vitrail's Minecraft 26.x renderer.
		// IS_IRIS selects that language's syntax; it does not announce an installed Iris mod.
		defines.put("IS_IRIS", "");
		// Only the IRIS_FEATURE_* contracts this backend implements; see FEATURES. Device support
		// alone is insufficient to promise SSBOs, images or blending.
		for (String feature : FEATURES) {
			defines.put("IRIS_FEATURE_" + feature, "");
		}

		// Straight off the enum the engine sets, so that the number a pack compares against and the
		// number the block carries cannot part company. The value IS the ordinal.
		for (RenderStage stage : RenderStage.values()) {
			defines.put(stage.symbol(), Integer.toString(stage.ordinal()));
		}

		// What a pack branches its far terrain on. Posed only where the terrain really reaches the
		// pack, which is what the name means to the packs that read it: what sits under it in the
		// corpus is written for a picture with a far terrain in its depth, its own blend states and
		// its own programs, and posing it over a depth that has none applies them to a screen they
		// were never meant for. Iris poses it on the mod being loaded AND its rendering answering,
		// gl/shader/StandardMacros.java:64; render/PackDefines asks the same two questions here.
		if (environment.distantHorizons()) {
			defines.put("DISTANT_HORIZONS", "");
		}

		// Distant Horizons block kinds. No pack declares them and several read them, so leaving
		// them out turns a working pack into a wall of undeclared identifiers.
		defines.put("DH_BLOCK_UNKNOWN", "0");
		defines.put("DH_BLOCK_LEAVES", "1");
		defines.put("DH_BLOCK_STONE", "2");
		defines.put("DH_BLOCK_WOOD", "3");
		defines.put("DH_BLOCK_METAL", "4");
		defines.put("DH_BLOCK_DIRT", "5");
		defines.put("DH_BLOCK_LAVA", "6");
		defines.put("DH_BLOCK_DEEPSLATE", "7");
		defines.put("DH_BLOCK_SNOW", "8");
		defines.put("DH_BLOCK_SAND", "9");
		defines.put("DH_BLOCK_TERRACOTTA", "10");
		defines.put("DH_BLOCK_NETHER_STONE", "11");
		defines.put("DH_BLOCK_WATER", "12");
		defines.put("DH_BLOCK_GRASS", "13");
		defines.put("DH_BLOCK_AIR", "14");
		defines.put("DH_BLOCK_ILLUMINATED", "15");

		defines.put("PPT_NONE", "0");
		defines.put("PPT_RAIN", "1");
		defines.put("PPT_SNOW", "2");

		// Empty until a caller has a registry to walk, which is the point: a biome symbol invented
		// here would be a number the engine cannot answer with.
		environment.biomes().forEach((path, id) ->
				defines.put("BIOME_" + path.toUpperCase(Locale.ROOT), Integer.toString(id)));

		List<String> categories = environment.biomeCategories();
		for (int ordinal = 0; ordinal < categories.size(); ordinal++) {
			defines.put("CAT_" + categories.get(ordinal).toUpperCase(Locale.ROOT),
					Integer.toString(ordinal));
		}

		return defines;
	}

	/**
	 * OptiFine's symbol for the system, or null on a Mac, where no system symbol is posed at all.
	 * <p>
	 * <strong>A divergence, and on purpose.</strong> OptiFine and Iris pose {@code MC_OS_MAC} on a
	 * Mac (Iris at {@code gl/shader/StandardMacros.java:253-261}, the {@code OSX} case), and there
	 * they run on Apple's OpenGL driver. That driver is what the symbol means to a
	 * pack: every test of it in Complementary Reimagined r5.9.3 switches off something that driver
	 * lacks. The pack turns off its coloured lighting and the image stores it is built on
	 * ({@code lib/common.glsl:33}, {@code shaders.properties:72}), and paints a full screen error
	 * over the picture where the player asked for it anyway ({@code program/final.glsl:24}). It
	 * turns off the rain that needs a blend of its own per buffer
	 * ({@code shaders.properties:149}), and drops samplers from {@code composite1} to stay under a
	 * limit of eight ({@code program/composite1.glsl:23}).
	 * <p>
	 * None of those limits is this engine's. On a Mac it draws through MoltenVK on Metal, with
	 * compute and storage images, a blend per attachment where the device says so (the
	 * {@code bufferBlending} answer above), and a stage past sixteen samplers given an argument
	 * buffer by {@code WideSamplerSets}. Posing the symbol would have a pack switch those off on a
	 * machine that has them. Nothing is posed in its place: OptiFine's {@code MC_OS_UNKNOWN} would
	 * say the system is not one it knows, which is not true either.
	 */
	private static String osSymbol(Os os) {
		return switch (os) {
			case MAC -> null;
			case LINUX -> "MC_OS_LINUX";
			case WINDOWS -> "MC_OS_WINDOWS";
			case OTHER -> "MC_OS_UNKNOWN";
		};
	}

	/** OptiFine's list, tested by prefix and in this order. */
	private static String vendorSymbol(String vendorName) {
		String vendor = vendorName.toLowerCase(Locale.ROOT);
		if (vendor.startsWith("ati")) {
			return "MC_GL_VENDOR_ATI";
		} else if (vendor.startsWith("intel")) {
			return "MC_GL_VENDOR_INTEL";
		} else if (vendor.startsWith("nvidia")) {
			return "MC_GL_VENDOR_NVIDIA";
		} else if (vendor.startsWith("amd")) {
			return "MC_GL_VENDOR_AMD";
		} else if (vendor.startsWith("x.org")) {
			return "MC_GL_VENDOR_XORG";
		}

		return "MC_GL_VENDOR_OTHER";
	}

	/** As above: a closed list of prefixes, and everything else is other. */
	private static String rendererSymbol(String rendererName) {
		String renderer = rendererName.toLowerCase(Locale.ROOT);
		if (renderer.startsWith("amd") || renderer.startsWith("ati") || renderer.startsWith("radeon")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("gallium")) {
			return "MC_GL_RENDERER_GALLIUM";
		} else if (renderer.startsWith("intel")) {
			return "MC_GL_RENDERER_INTEL";
		} else if (renderer.startsWith("geforce") || renderer.startsWith("nvidia")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("quadro") || renderer.startsWith("nvs")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("mesa")) {
			return "MC_GL_RENDERER_MESA";
		} else if (renderer.startsWith("apple")) {
			return "MC_GL_RENDERER_APPLE";
		}

		return "MC_GL_RENDERER_OTHER";
	}
}
