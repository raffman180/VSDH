package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the game sky's four mesh layouts to legacy GLSL inputs. Each bound
 * attribute keeps its vertex-format location even if SPIR-V optimization removes
 * an unused input. Sky meshes have no lightmap, overlay or normal, so the legacy
 * attributes use full light and a viewer-facing normal.
 */
public final class SkyVertex {

	/**
	 * Every name any sky format may carry, which is what a pack may therefore not use for something
	 * of its own.
	 * <p>
	 * The union and not the bound format, on purpose, and it is the one place the union is right:
	 * this list decides which of the pack's own symbols are renamed out of the way, and renaming one
	 * the bound format happens not to carry costs nothing, while missing one that it does carry is a
	 * redefinition at file scope that refuses the stage.
	 */
	public static final List<String> ATTRIBUTES = List.of("Position", "Color", "UV0");

	private SkyVertex() {
	}

	/**
	 * The head of a sky vertex stage: the elements the bound format carries, the names a pack reads
	 * made out of them, and constants for everything the sky has not got.
	 *
	 * @param bound       the elements of the format this pass actually binds, in the format's own
	 *                    order. Exactly these are declared, and no others
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 */
	public static List<String> prologue(List<String> bound, Set<String> used,
			Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (int location = 0; location < bound.size(); location++) {
			String attribute = bound.get(location);
			lines.add("layout(location = " + location + ") in "
					+ VertexPrologue.elementType(attribute) + " " + attribute + ";");
		}

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		// The modulator, times the element where the format has one, and this is the one line of the
		// prologue that decides a picture rather than guarding one.
		//
		// The game does not put the sky's colour in the mesh; it puts it in the colour modulator of
		// its dynamic transforms, one value for the whole draw, and under OptiFine that value is
		// what a pack reads as gl_Color. White is not a neutral stand in for it: packs recognise
		// vanilla's stars by exactly that shape, a colour whose three channels are equal and above
		// nought, and Body Camera and Sildur's both then take their star branch and paint the whole
		// sky disc flat.
		//
		// The two MULTIPLY where both exist, because that is what the game's own shaders do with
		// them: core/position_color.fsh ends on fragColor = color * ColorModulator, and so does
		// core/position_tex. The one element of the sky whose format carries a colour is the sunrise
		// band, and there the mesh is white at the centre fading to a transparent white at the rim,
		// ARGB.white(1.0F) and ARGB.white(0.0F) in SkyRenderer.buildSunriseFan: every bit of the
		// band's actual colour is in the modulator, so keeping the element alone draws a white band
		// at sunset. Dropping the element instead would take the fade with it and paint a disc.
		lines.add(bound.contains("Color")
				? "#define of_Color (Color * of_PassColour)"
				: "#define of_Color of_PassColour");

		// The middle of the sprite where the format has no texture coordinate, which is the four
		// elements drawn from bare positions. The corner instead would send a pack that samples
		// gtexture there to one texel of the atlas rather than to the one the name stands for.
		String texture = bound.contains("UV0") ? "vec4(UV0, 0.0, 1.0)" : "vec4(0.5, 0.5, 0.0, 1.0)";
		lines.add("#define of_MultiTexCoord0 " + texture);

		// The light map, which no sky format carries. Both channels at the top of the range, in the
		// raw coordinates a pack expects to divide down itself, which is the pair the references hand
		// every format without a light map. Block light at nought instead reads as a place no lamp
		// reaches, and a pack that writes the coordinate straight into a colour target, as Body Camera
		// does over the whole sky, puts the bottom of the range there rather than the top.
		for (int unit = 1; unit <= 2; unit++) {
			lines.add("#define of_MultiTexCoord" + unit + " vec4(240.0, 240.0, 0.0, 1.0)");
		}

		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
