# Changelog

## Unreleased

### Fixed
- Players no longer lose their body in third person with shader packs that have no `gbuffers_entities_translucent`
  program (armour and held items were drawn, skin and 3D skin layers were not). Player skins, and other translucent
  entity layers, are drawn with the pack's `gbuffers_entities` program in that case, as Iris does; translucent block
  entities likewise fall back to `gbuffers_block`. They used to be drawn with the standard shader, which a pack that
  shades from its own colour, normal and material buffers does not show.
  `-Dvsdh.translucentEntityFallback=false` restores the old behaviour. Not yet checked in game.

- Frame rate with shader packs: "Low Latency Mode" (added earlier in this cycle) made every frame wait until the
  previous one was on screen. With a shader pack that adds the processor's and the graphics card's time per frame
  instead of letting them overlap, and with VSync rounds the sum up to whole refresh intervals (a third or a quarter
  of the refresh rate where the same scene otherwise ran much faster). The switch is removed from the video settings
  and a saved "on" is ignored; `-Dvsdh.lowLatency=true` still enables the mode for experiments. Not yet measured in
  game.

- The Shader page no longer logs "Cannot read the profiles of shader pack ..." each time it opens when the configured
  pack file was replaced by a newer version: profiles are read from the file that is actually used, and the log names
  that file (`Vulkan shaderpack ready: <file> (configured: <saved name>, file not found)`). The saved choice is not
  rewritten.

- Shadows of terrain the camera has not looked at yet appear without turning towards it. A hill, tree or building
  behind or beside the player was only built once it came into view, so after joining a world, switching the pack or
  turning around its shadow arrived seconds late (and a block change outside the view never updated the shadow).
  While a shader pack draws a shadow map, the renderer now also builds the waiting sections the light can see, after
  the visible ones and within the same per-frame budget. Off by default until it has been measured in game:
  `-Dvsdh.shadowCasterBuilds=true` turns it on.

- Shader packs that build their tables in numbered setup programs (`setup1.csh` ... `setup99.csh`) now get all of
  them run once, in order, as in Iris. Only `setup.csh` was run before, so Jurgens Shader v1.1 had empty cloud noise
  volumes (filled in `setup2`-`setup10`) and no multiple-scattering table (`setup1`): no volumetric clouds, a clear
  sky and a picture that was too bright. The log shows `Setup computes dispatched once: [...]`. Not yet compared in
  game.

- The camera follows the mouse more directly. The renderer waited for the graphics card and for the next screen
  image after Minecraft had already read the mouse, so the movement made during that wait only showed one frame
  later (up to a whole refresh interval with VSync). Both waits now happen before the mouse is read
  (`-Dvsdh.earlyFrameWait=false` restores the old order, `-Dvsdh.earlyAcquire=false` moves only the first one).
  Not yet measured in game.

- Terrain and block entities at the screen edge no longer appear late while turning: the visible-section set was only
  recomputed after the camera had turned 2 degrees or moved half a block, so a strip at the leading edge stayed
  empty until the next step. It is now recomputed whenever the camera or the culling frustum changes
  (`-Dvsdh.exactCullUpdates=false` restores the old thresholds). Checked in the test client; no measurable FPS cost.

- The Vulkan pipeline cache (`config/vulkanmod_pipeline_cache.bin`) is now also saved a few seconds after new
  pipelines were built (after a shader pack's first frames, a pack switch, a new dimension), not only when the game
  closes normally, so a crash or a killed game no longer loses it. It is written through a temporary file on a
  background thread, skipped when nothing changed, and a file from another GPU or driver version, or a damaged one,
  is ignored instead of being handed to the driver. The log shows `Pack pipelines of <pack>: N created in X ms
  (cache: Y KiB loaded)`. `-Dvsdh.pipelineCacheSave=false` restores saving at exit only. Not yet measured in game.

### Changed
- Fewer automatic chunk builder threads on small processors when playing on a server: with "Chunk Builder Threads"
  on Auto the renderer never starts more builders than Sodium does on the same processor (4 logical processors: 1
  instead of 2, 6: 2 instead of 3, 8: 2 instead of 4, 10: 4 instead of 5). That leaves more of a small CPU to the
  game, the collector and other mods; terrain may fill in a little slower there. Single-player and processors with 12
  or more threads are unchanged, and a number set by hand is used as it is. `-Dvsdh.builderThreadCap=false` restores
  the old numbers. Not yet measured in game.
- The two Vulkan present extensions of the experimental low latency mode are only requested from the driver when
  that mode is asked for (`-Dvsdh.lowLatency=true`), no longer for every player.
- Switching shader packs is much faster. A pack's programs are now translated and compiled side by side on several
  processor cores instead of one after another, and the compiled programs are kept in `config/vsdh-shadercache`, so a
  pack that was used before (same file, profile and options) is read back instead of compiled again, also after a
  restart. Measured without the game on a 16-core processor: Jurgens Shader v1.2 22.0 s before, 5.0 s on first use, 1.0
  to 2.0 s afterwards; Eclipse 21.1 s, 7.2 s, 1.9 to 2.9 s; Solas 18.2 s, 4.8 s, 0.5 to 1.7 s. The prepared result is
  identical in all cases. Each of the two stores in that folder is capped at 256 MiB; the folder may be deleted at any
  time.
  `-Dvsdh.prepareThreads=0` restores the old one-by-one preparation, `-Dvsdh.spirvCache=false` and
  `-Dvsdh.translationCache=false` turn the two stores off. Not yet timed in game; the terrain is still rebuilt after a
  change inside a world (an opt-in that keeps it between packs with the same block table,
  `-Dvsdh.keepTerrainOnSwitch=true`, is untested).
- Distant Horizons' shared quad index buffer lives in GPU memory like the renderer's own index buffers (was host memory;
  no measurable frame-time change, `-Dvsdh.dhIndexHostMemory=true` restores it).

### Added
- Experimental `-Dvsdh.lowLatency=true` (Java argument, not a video setting; see Fixed): a frame starts only once
  the previous one is on screen (`VK_KHR_present_wait`), borderless and exclusive fullscreen only. It lowers the
  frame rate whenever processor plus graphics card time of a frame exceed one refresh interval.

### Added (diagnostics, off by default)
- `-Dvsdh.inputTrace=true` logs `INPUT_TRACE` every five seconds: time from the input poll to the present call.
- `-Dvsdh.vmaTrace=true` logs every GPU memory block the allocator takes or returns.
- `-Dvsdh.gpuSpikes=true` also logs GPU idle gaps of 5 ms or more between frames (`GPU_IDLE`).
- `-Dvsdh.swapchainImages=N` sets the swapchain image count (default 3, clamped to what the surface allows).
- `-Dvsdh.separateUploadSubmit=true` submits the frame's upload batch on its own (to tell queue and swapchain waits apart).
- "Skipping shader frame with invalid camera input" is now also logged at the 10th, 100th, 1000th ... skipped frame with
  the running count, so a pack picture that keeps falling back to the standard renderer shows in the log (one such
  line at a world join is harmless).

## 0.27.0-beta (2026-11-14)

First public beta.

### Added
- Shader packs prepared once in a session are kept (three by default, `-Dvsdh.packCache=N`): switching back to a
  pack with the same file, profile and options no longer waits for its preparation. Opening the Shader page
  prepares up to two previously used packs ahead on a low-priority worker.
- Shader pack changes apply inside a loaded world; the terrain is rebuilt once. `-Dvsdh.liveShaderSwitch=false`
  restores the old behaviour (apply when leaving the world).
- One JAR for Distant Horizons 3.2.0-b and 3.3.0–3.3.3 (DH 3.3.0 is adapted at load time).
- Shadow maps that do not fit GPU memory use the pack's next smaller shadow resolution.
- A configured shader pack whose file was replaced by a newer version uses the newer file.
- Title-screen notice for installed mods that are known to cause display errors.
- Unsupported GL calls of other mods are reported once in the log.

### Changed
- The bundled renderer's mod id is now `vsdh_renderer` (was `vulkanmod`); a separately installed VulkanMod is refused
  with a clear message.
- Frame diagnostics no longer write to the log unless `-Dvsdh.chunkDiag=true` is set.
- Shadow memory budget follows the GPU's memory instead of a fixed 512 MiB.

### Fixed
- Water, reflections and shadows slid along while walking with a shader pack (not while looking around or flying):
  Minecraft puts view bobbing into the projection, and packs rebuild positions from a pure projection. While a pack is
  active the bob is now applied to the model view instead, as Iris does (`-Dvsdh.modelViewBobbing=false` restores
  the old behaviour).
- Distant Horizons terrain and water followed neither view bobbing nor the field-of-view change when starting to
  walk, so the far water slid against the near water while moving. The DH projection is now built from the camera's
  (`-Dvsdh.dhFollowCamera=false` restores the old one).
- After joining a world or switching shader packs the near ground filled in ring by ring over several seconds. The
  visibility walk now passes through sections that are not built yet (`-Dvsdh.openUncompiled=false`), and while many
  visible sections are missing more are built and uploaded per frame (`-Dvsdh.chunkCatchUp=false`).
- Water and shadows that seemed to move with the player (flat, light wedges on the water): chunk sections were
  built only when the camera first looked at them, so for about a second after turning or walking the nearby ground
  was missing and Distant Horizons' far water showed through. Sections the camera has not seen yet are now built
  ahead while nothing visible waits (`-Dvsdh.prebuildHidden=false` turns it off).
- Borderless fullscreen presents through the Vulkan swapchain like exclusive fullscreen (it used the DXGI flip
  path of ordinary windows and had about twice as many long frames). Video Settings > Window Mode: Window,
  Borderless Fullscreen, Exclusive Fullscreen; F11 uses the chosen kind. `-Dvsdh.borderlessPresent=dxgi` restores
  the old path.
- A screenshot taken while the game was minimised in exclusive fullscreen (or right after) could crash the
  graphics driver: the swapchain images were destroyed but still handed out. Texture ids of the swapchain no
  longer leak on every resize.
- Map and minimap mods that upload their own textures (Raffman's Detailed Map) showed blank tiles.
- With ImmediatelyFast, the in-world pause menu showed only its first button.
- Borderless Window: the present path now follows the real window mode.
- Unbounded memory growth of a view cache while a shader pack was active.
- Several crashes on unusual texture uploads; they now leave the texture blank with a warning.

### Performance
- Terrain layers that hold no geometry are no longer prepared in a shader frame: Eclipse lost two full-screen
  snapshot copies per frame (feedback copies 0.19-0.22 ms -> 0.12 ms at 1440p). `-Dvsdh.skipEmptyTerrainLayers=false`
  restores the old path.
- Measured against Iris 1.8.12 + Sodium 0.6.13 on the same 30 s flight (exclusive fullscreen, 1440p, DH in both):
  average FPS Eclipse +5-10 %, Complementary +13-16 %, no shader +14-27 %; the rarest long frames are on par or
  slightly worse (docs/PERFORMANCE.md).
- Biome lookups on the render thread remember the chosen biome cell per position (about a third of the render
  thread's busy time in a 128-mod pack went to repeated lookups). `-Dvsdh.biomeCornerCache=false` turns it off.
- Fewer allocations per frame in the shader host and pipeline state.
- Biome lookups for positions that are not remembered reuse the per-cell part of the distance arithmetic
  (ambient-particle probes, map and tint sweeps). `-Dvsdh.biomeFiddleCache=false` turns it off.
- Animated textures stage only the frame that is uploaded instead of the whole animation sheet each tick.
  `-Dvsdh.tightSpriteUpload=false` restores the old copy.
- Shader uniforms: the provider of each uniform is resolved once per program instead of on every write.
  `-Dvsdh.uniformRoutes=false` restores the full lookup order.
- Shader programs that read a colour target they also draw into (the water programs of Eclipse and Bliss) sample
  the attachment directly where the GPU supports `VK_EXT_attachment_feedback_loop_layout`, as Iris does, instead of
  copying the full-screen image before each such pass (Eclipse 1440p: copies 0.12 ms -> 0, translucent pass
  0.74 -> 0.58 ms). `-Dvsdh.feedbackLoops=false` restores the copies. The "water moving with the camera" that once
  looked like a side effect came from view bobbing and late sections and is fixed.
  Distant-water programs keep reading the stable opaque Distant Horizons depth: reading the live depth while the
  same pass writes it made distant water flicker (Eclipse). `-Dvsdh.dhDepthFeedbackLoop=true` opts in.
- The shadow pass no longer walks every chunk section each frame; it keeps a list of the drawable ones.
  `-Dvsdh.shadowSectionList=false` restores the full walk.
- The tooltip of the frame-rate limit explains how a cap removes presentation hitches.

## 0.26.0-dev

Development builds; see `docs/MIGRATION_STATUS.md`.
