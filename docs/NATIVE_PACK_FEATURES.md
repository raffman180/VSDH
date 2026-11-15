# Native pack features (0.26)

This page describes the Iris shaderpack contracts the VSDH host added in 0.26 beyond the
world chain of 0.5–0.15. Each section states what is implemented, which Iris behaviour it
follows and what is known not to be covered. Verification status is tracked in
`docs/MIGRATION_STATUS.md`; do not read this page as a claim that a pack renders correctly.

## Pack clouds (`gbuffers_clouds`)

`LevelRenderer.renderClouds` draws its `VertexBuffer` (POSITION_TEX_COLOR_NORMAL) through
`SkyPackPlan.Layout.CLOUDS`. Clouds are drawn after translucent terrain in 1.21.1, so the
program uses the post-deferred target halves like Iris. Both the depth-only and the colour draw
of fancy clouds are intercepted; the colour mask comes from the render type. `clouds=off/fast/fancy`
keeps working through `getCloudsType`. `SkyVertex` now binds `Normal` for this layout.

## Compute programs

* `PackProgramSource.prepareCompute` translates one `.csh` and reads `workGroups`,
  `workGroupsRender` and `local_size` with `PackProgram.dispatchOf` (Iris
  `ComputeProgram.getWorkGroups`: fixed count, else render size × scale ÷ local size).
* `ShaderChainPlan` attaches computes to their host pass (`deferred4_a` → `deferred4`) in
  frame order; hosts that draw nothing become compute-only passes. `setup`, `setup1` ... `setup99`
  (every numbered host present, in index order, Iris `ProgramSet.readProgramArray`) run once on the
  first frame of a chain after the initial clear (Iris runs it at pipeline build; clearing
  targets therefore keep setup output for one frame here). `shadowcomp` runs after the shadow
  pass and before `prepare`.
* `colorimgN` is a storage image on the half the host pass reads, the same snapshot its
  samplers read (Iris `CompositeRenderer`). Storage targets get `VK_IMAGE_USAGE_STORAGE_BIT`;
  the shader's format qualifier must equal the target format (Vulkan requires it).
* `NativeComputePass` records outside render passes. A target that is sampled and stored by one
  dispatch is sampled in `GENERAL`. Every dispatch is followed by a global shader-write barrier
  (the Vulkan form of Iris' `glMemoryBarrier`). Zero work groups dispatch nothing.
* `ComputePipeline` (VulkanMod package) reuses `Pipeline`'s descriptor machinery with the
  compute bind point.
* `shadowcolorimgN` (shadowcomp storing into shadow colour target N) is bound from the shadow map;
  those targets get storage usage (Rethinking Voxels' interactive water).
* `indirect.<pass> = <bufferObject> <offset>`: the work group counts come from that storage buffer
  (`vkCmdDispatchIndirect`; the buffers carry `INDIRECT_BUFFER` usage, the barriers include
  `DRAW_INDIRECT`). Checked with a test pack in VSDH and Iris: both dispatch the four groups the
  first compute wrote (`packs-debug/zzIndirectProbe.zip`).
* Not covered: typed custom image views (an image read under a different data type than its
  directive). No tested pack uses them; such a pack is refused at preparation with the program
  named, it does not crash.

## Shader storage buffers (`bufferObject.N`)

* The compiler keeps the pack's index under the key `ssbo#N` and assigns the descriptor
  binding itself; storage blocks in dead preprocessor branches are left untouched.
* `NativeStorageBuffers` allocates every declared buffer per graph (relative sizes use the render
  size), zeroes them on the first frame and frees them with the graph. They persist across frames.
* `StorageBufferDescriptor` is a third descriptor kind in `Pipeline` (non-dynamic).
* Graphics programs that use storage buffers or store into images record a global shader-write
  barrier after their draw; this needs `fragmentStoresAndAtomics` and
  `vertexPipelineStoresAndAtomics`, which `DeviceManager` now enables when present.
* A program reading a buffer the pack never declares is refused when the session installs.

## Custom images (`image.<name>`)

* `NativeCustomImages` creates 1D, 2D (fixed or screen-relative) and 3D images with storage and
  sampled usage. They stay in `GENERAL` for their whole life. All start cleared; `clear=true`
  images are cleared again at the start of every frame.
* The image is a storage image under the directive's own name and a sampled image under its
  sampler name, in compute and graphics programs (`CustomImageBindings`).
* `WorldPackPlan.prepare` installs `CustomImages`/`CustomStorage` so the translator and the
  sampler classification know the pack's names.
* Not covered: typed views of another format (`CustomImageView` names), image arrays.

## Raw pack textures

* 2D `.dat` blobs (`RED/RG/RGB/RGBA` × `UNSIGNED_BYTE/BYTE/(UNSIGNED_)SHORT/HALF_FLOAT/FLOAT`)
  are widened to four channels (missing colour 0, missing alpha 1).
* 3D `.dat` volumes (Photon, Mellow) are real 3D images since 12.11.2026: the translator keeps the
  `sampler3D` and only renames it to `ofPackTexture_<name>` (`GlslTranslator.nativeVolumes`,
  set in `ShaderPackStartup`), `PackTexturePlan.Volume` keeps the blob's channel count (R8, RG16F
  ...; RGB widened to RGBA with alpha 1) and `NativeWorldTextures` uploads it once into a
  `VK_IMAGE_TYPE_3D` image with a 3D view. The hardware filters trilinearly with REPEAT/CLAMP
  in all three axes, as GL does under Iris. `-Dvsdh.flatVolumes=true` restores the former flat
  atlas (`VolumeAtlas`, two 2D taps and a mix per lookup, texels widened to four channels).
  Photon 1280×720: GPU 2.19 → 2.11 ms per frame; screenshots equal within run-to-run noise.

## Geometry stages

* A pass-through geometry stage is folded into the fragment stage (`dev.vitrail.glsl.GeometryFold`
  on the shaderc-preprocessed text). Eclipse's terrain stage is of this shape unless shader grass
  is enabled.
* Any other geometry stage is compiled natively. Outputs that share a name with an input array
  (flattened `in DATA {} data_in[]` / `out DATA {} data_out`) are renamed `ofGsOut_<name>`;
  `SpirvInterface` links vertex→geometry and geometry→fragment by name. `GraphicsShaderStages`
  accepts a geometry stage and `DeviceManager` enables `geometryShader` when present.
* Not covered: geometry stages of the entity shadow caster programs (entity identifier varyings
  are declared without array size), which fall back to the standard caster path with a warning.

Other compiler fixes: pack macros named `GL_*` (refused by glslang, e.g. Rethinking Voxels'
`GL_CAVE_FACTOR`) are renamed `ofGL_*`; `shadowtex0HW`/`shadowtex1HW` resolve to the shadow depth
images (compare sampler from `sampler2DShadow`); a custom noise texture honours its `.mcmeta`.

## Tessellation stages

* `.tcs`/`.tes` are compiled natively; `NativeGraphicsProgram` passes them to VulkanMod's existing
  tessellation path (`setTessellationSPIRVs`, patch list topology, `layout(vertices = N)` control
  points). `DeviceManager.isTessellationEnabled()` must be true.
* The translator flattens arrayed blocks in both tessellation stages like geometry inputs. The
  control stage's output arrays keep the input name through translation (so the varying analysis
  pairs them with the evaluation stage) and are renamed `ofTcOut_<block>_<member>` by the compiler
  at their declaration and at every element that is assigned; they get an explicit size because
  glslang sizes them only from a `layout(vertices)` it has already seen. Evaluation-stage outputs
  that collide with their input arrays are renamed like geometry outputs.
* `SpirvInterface.linkStages` links every adjacent pair of the chain
  vertex → control → evaluation → geometry → fragment by unprefixed name.
* Eclipse's terrain carries both stages (tessellation level 1 unless shader grass is enabled).

## Pack formula inputs and feature flags

* Pack formulas can read `gbufferProjectionInverse`, `gbufferModelView`, the previous matrices,
  `shadowProjection(Inverse)` and `shadowModelViewInverse`, and Iris' `CAT_*` biome categories.
* `EngineDefines.FEATURES` = `COMPUTE_SHADERS`, `SSBO`, `CUSTOM_IMAGES`: these are accepted in
  `iris.features.required` and announced as `IRIS_FEATURE_*` defines, which can switch packs onto
  paths that were dormant before. Every pack therefore needs re-verification after these changes.

## Other fixes in this step

* `#define X texture` aliasing the builtin is no longer renamed to `ofTexture` (Photon bloom);
  a pack's own `uniform sampler2D texture` is still renamed inside macros.
* A pack without `gbuffers_basic` (Miniature) no longer throws: the vanilla fallback draw lands
  in `colortex0` on the half the geometry steps write.

## Frame guard and descriptor reset (11.11.2026)

* Every per-frame entry of `WorldShaderSession` (frame start, terrain, deferred, composite, DH,
  sky/particles, entities) runs through a guard. A `RuntimeException` ends the shader frame
  (`abortFrame`: closes the render pass, falls back to the vanilla target, chat + log once) and keeps
  the world on the standard renderer until the window size or dimension changes; the graph is
  released at the next frame start. `-Dvsdh.debugFailFrame=N` forces one failure for testing
  (verified in game with Complementary).
* `NativeGraphicsProgram.renderDraw` now registers its pipeline with the renderer, so the
  descriptor ring is reset every frame. Before, it only grew; at uncapped FPS the set allocation
  ran out of stack space after roughly 20 seconds (found by the FPS benchmark).

## Fixes 11.11.2026 afternoon

* Full-screen passes draw a 0..1 quad (two triangles, `NativeFullscreenPass`, `UV0` from
  `gl_VertexIndex`) like Iris, not one oversized triangle. Packs shrink `gl_Position` to render
  part of the screen (Bliss half-resolution volumetric fog); the shrunk triangle covered twice the
  area and overwrote pixels Iris leaves untouched. Bliss `composite3` 0.404 → 0.216 ms.
* Geometry stages of entity programs: the engine's entity values (`entityColor`, `entityId`,
  `blockEntityId`, `currentRenderedItemId`) become array inputs read through corner 0 and are
  written to `ofGsOut_<name>` before every `EmitVertex()` (`VulkanProgramCompiler.forwardEngineVaryings`).
  Rethinking Voxels' entity shadow casters now compile natively instead of falling back.
* The generated `noisetex` filters linearly with repeat (Iris `NoiseTexture`).
* `MC_GL_VENDOR_*`/`MC_GL_RENDERER_*` come from the Vulkan device (`ShaderPackStartup.describeMachine`).

## Iris parity fixes 11.11.2026 evening

Found with a probe pack that prints ~16 uniforms as colour bars, run in VSDH and Iris 1.8.12 on the
same scene (`build/reference/packs-debug/zzUniformProbe.zip`, test material only, never shipped):

* **GL orientation of the pack chain.** VSDH used to draw every pack pass top-down and published
  `gbufferProjection[1][1] < 0` to compensate. Inside a pass that was consistent, but packs that read
  the matrix as a number broke: `fovScale = gbufferProjection[1][1] / 1.37` (AO in BSL, Solas,
  Insanity; refraction in Complementary and Rethinking Voxels; depth of field in Mellow) became
  negative, and `texcoord.y`/`gl_FragCoord.y` grew downwards. Now the game's projection is
  published unchanged, pack images hold their rows bottom-up as in OpenGL, vanilla draws into the
  pack scene use the unflipped viewport (`Renderer.setInvertedViewport`, `setMainTargetRowsBottomUp`),
  front faces are flipped for those targets (`PipelineState.getCurrentPipelineState(pass, true)`,
  DH passes `VK_FRONT_FACE_CLOCKWISE`), FINAL draws into an intermediate image and one mirrored blit
  writes it into the top-down main target. DH's depth reader flips the MC depth it samples when the
  depth is bottom-up (`dh_depth_read.frag`). Probe: all compared values equal Iris except `near`
  (0.1 vs 0.05, VulkanMod's effective near plane, see WORLD_UNIFORMS.md).
* **`vignette` / `underwaterOverlay`.** Iris turns the game's vignette and under-water screen
  texture off while a pack is active unless the pack sets them true; every tested pack writes
  `vignette=false`. VSDH ignored both, which darkened the screen edges by 5–10 % in every pack.
  Read in `ShaderProperties`, applied by `WorldShaderOverlayMixin`/`WorldShaderWaterOverlayMixin`.
* **Feature flags** `HIGHER_SHADOWCOLOR`, `BLOCK_EMISSION_ATTRIBUTE` and `ENTITY_TRANSLUCENT` are now
  announced (each is implemented: eight shadow colour targets incl. `shadowcolorimgN`, block light
  in `at_midBlock.w`, translucent entity/block-entity programs). Packs take the main path written
  for Iris: Rethinking Voxels' interactive water, Bliss/Eclipse voxel light colours, Complementary's
  reflection resolution.
* **Shadow programs reading shadow targets** they do not write (Rethinking Voxels' waving water reads
  `shadowcolor2`) are bound; every `shadowcolorN` some program samples is allocated.
* **Terrain programs per layer** like Iris: `gbuffers_terrain_solid` (Photon, Mellow) for solid,
  `gbuffers_terrain_cutout` (Eclipse) for both cutout layers, `gbuffers_water` for translucent, each
  through the fallback tree down to `gbuffers_textured`/`gbuffers_basic`.
* Not covered: a pack without any terrain program (pure post-processing packs). Iris draws the world
  with built-in programs there; VSDH refuses such a pack at startup with a clear message and keeps the
  standard renderer.
* `frustum.culling=false` (AstraLex, BSL) and `beacon.beam.depth` are not read; the first only
  matters for packs that draw off-screen geometry, the second for beacon beams in the depth buffer.

Checked after these changes (11.11.2026 evening, RTX 4070, DH 3.3.3): all 14 test packs start
without error lines and match the Iris references by eye; averages of sky, island, water and
near field agree within a few levels now that the vignette is gone. Two leftover differences were
traced to the scene, not the renderer: the near water texture of Complementary/Rethinking Voxels
changes with the vanilla water animation frame (contrast 14.8 → 11.7 within 13 ticks of the same
build), and Miniature's water glints differed the same way before these changes. Smoke 3/3.
