# VSDH

User target: Minecraft 1.21.1, NeoForge 21.1.264, Java 21,
VulkanMod Reforged. First milestone: native DH rendering. First shader target:
Complementary Reimagined. Broad future compatibility is a goal, not a claim.

The sole port reference is Braffolk/distant-horizons-vulkanmod commit
a72ba645188c5fab5f9d48254b6703b659543f0c. Renderer reference:
TrulyRin/VulkanMod-Reforged commit 0618965fbedb71ca2ee2e5d4528fcedc4fb27551.
Do not silently substitute Fabric VulkanMod APIs for Reforged APIs. Preserve
upstream credits and license headers. No decompiled references in deliverables.

Pin and validate dependency versions. Keep render code on the physical client.
All Vulkan resource operations belong on the render thread; worker threads may
only stage copied buffers and enqueue work. Preserve world data and user settings.
Do not advertise untested version combinations or unfinished shader support.
Report code review, compilation, unit tests and in-game rendering separately.
Keep docs/MIGRATION_STATUS.md and docs/HANDOFF.md accurate.

User on 2026-11-08 instructed continuing here to completion after the fork
recommendation; use the recommended Reforged fork. Renderer sources are now
part of this project; do not also install the original Reforged JAR. Keep
6699fe6 / dist/0.1 artifacts as the baseline. Complementary test reference:
r5.9.3, SHA-1 838139b54cddb56b2e83cd260d8efd960ac536d6; never bundle the pack.


Current 0.3 adds ShaderChainPlan, NativeShaderChain, NativeColorTargets and
NativeWorldDepth. Synthetic stage/history/mip/depth/stencil GPU checks pass;
all 9 Complementary fullscreen Vk pipelines are prewarmed. Full world geometry,
material/uniform/shadow/DH integration is still missing. Read docs/RENDER_GRAPH.md
and docs/verification/0.3.0-dev.json; do not call the synthetic inputs a world shader.

0.4 adds the 44-byte native terrain format, immutable material tables and native
indexed terrain draws. Four lighting contracts and 36 real Complementary terrain/
water/shadow pipelines are checked; the complete world chain still lacks runtime
uniforms, shadows and other geometry/DH integration. Read docs/TERRAIN_FORMAT.md.
Keep /build/ anchored in .gitignore: render/chunk/build contains required source.
DrawBuffers stride and bias belong to each area's immutable format snapshot;
never restore the old static stride. Recreate synchronous builders with workers.

0.5 adds WorldShaderSession, the explicit live-world MainPass host. Own diagnostic
pack runs on actual chunks and the existing DH composite. 28 tests pass, including
infinite far projection/history and shader alpha variants. Two native-world GPU
checks cover 100% and 50% scale plus actual FINAL output. Full Complementary still
lacks 59 world values/resources and other geometry/shadows/DH-pack programs. See
docs/WORLD_SHADER_HOST.md and docs/verification/0.5-complementary-requirements.json.
Keep docs/NEXT_AI_PROMPT.md current; user explicitly requests a usable handoff
prompt before ending the current work. Do not infer renderer completion from the
own diagnostic pack. Invalid startup matrices retain the normal frame target.

0.6 adds active SPIR-V uniform-member reflection and 18 actual MC-1.21.1 providers.
Keep original std140 offsets; unknown alias/block paths require all members.
An unused declaration is not a missing active value. Complementary audit now lists
17/14/12 missing active values (Overworld/Nether/End); old 59 counted declarations.
Read docs/WORLD_UNIFORMS.md. Diagnostic MRT alpha must be one: data in alpha would
be blended on translucent terrain and corrupt the measurement. Extra values are
encoded at adjacent even/odd pixels. GPU probes/readbacks remain smoke-only.

Current 0.9 supersedes the historical projection assumption in 0.5: Reforged's
Matrix4fM forces forward Vulkan 0..1. ForwardDepthProjection converts rowZ to
2*rowZ-rowW before publishing legacy matrices, then one native .5z+.5w epilogue.
Shader mode uses Minecraft's finite getRenderDistance()*4 far plane; default
Reforged mode stays infinite. Infinity epsilon reversed actual sky rays: GPU
probe read +0.871 before and -0.871 after the fix. Keep this regression test.
Pack formulas/held light, actual native world shadows and predecoded PNG/noise
resources are implemented. Real Complementary world execution is checked, but
other geometry families, per-attachment blend, hand timing and native pack DH
programs remain incomplete. Do not set DISTANT_HORIZONS until the latter work.
56 tests passed in 0.9. Read docs/WORLD_SHADOWS.md and the latest verification report.

0.10 adds an experimental real DH path for the original Complementary programs.
Enable the define per pack only where both terrain and water use this path.
The full and opaque DH depth images must stay distinct from each other and from
Minecraft depth. See docs/DISTANT_PACK.md. Complementary disables DH shadows
in this profile. Other geometry families and the visible water transition remain open.

0.11 draws the vanilla sky meshes with native pack programs. Keep stable vertex
locations when unused inputs are optimized away. Draw transforms and the
shader colour are local to each sky draw. Finalize before the vanilla hand
depth clear so depthtex0 still carries the rendered world. See WORLD_SKY.md.

0.12 adds particles and weather with the existing Minecraft PARTICLE format.
Follow the pack's before/mixed/after ordering on both NeoForge render calls.
Keep weather after deferred. Verify real rain texture and both particle batches.

0.13 prepares vanilla dimension programs off-thread and switches the graph on
ClientLevel changes. Keep one material table and terrain format across the
dimensions. Test all three live, return to Overworld, then a standard world.

0.14 adds opt-in startup selection. Read and compile the selected pack on a
worker, then install it on the render thread only while no level is loaded.
Keep invalid or blank settings on the standard renderer. Never bundle the pack.

0.15 keeps vanilla culling for shader translucent terrain when backFaceCulling
is enabled. Liquid meshes carry both face windings. Preserve the standard
renderer and confirm upward water normals in the live GPU probe.

0.26 is a measured performance step; read docs/PERFORMANCE.md. Profile before
optimising (-Dvsdh.gpuProfile, -Dvsdh.syncTrace, JFR). ShadowCuller mirrors
Iris's caster selection and must stay monotone. depthtex1/2 copy the depth
plane only. Render-thread uploads share one graphics-queue batch per frame;
flush it before any other graphics submission, never add a CPU fence for it.
Do not claim an advantage over Iris/OpenGL without a same-scene comparison.
Pack DH passes cull back faces with CCW front faces like DH itself (Bliss
showed white water tiles otherwise). dhNearPlane/dhFarPlane/dhRenderDistance
are always provided, as in Iris. Compare pack bugs against the Iris client in
build/reference/iris-client with the same seed, camera and DH config.
WorldUniforms writes a program's frame-constant members once per capture and
DrawSpace; later draws refresh only PER_DRAW members. A new value that can
change between two draws of one program must be added to PER_DRAW.
Under NeoForge Reforged's Matrix4fM does not apply (JOML probe m32=-0.1); the
`near` uniform must follow the published projection, not a constant 0.05.
Pack sky programs get a closed horizon (SkyHorizon) like other loaders.
Never write DH config from code: a DH-API switch-off once persisted
rendererMode=DISABLED in the test client and silently removed every LOD.
The harness scripts reset it; check "DH land draws" in bench summaries.
Full-screen passes are a 0..1 quad (6 vertices), never one oversized triangle:
packs shrink gl_Position (Bliss half-res fog). The vanilla renderLevel clear
must not clear colour into pack targets (WorldShaderLevelMixin.vsdh$worldClear).
Compare GPU per pass with run-iris-gpu-profile.ps1 before optimising.
The pack chain is GL-oriented: publish the game's projection unflipped
(gbufferProjection[1][1] > 0), pack images store rows bottom-up, vanilla draws
into the pack scene use the unflipped viewport (Renderer.setMainTargetRowsBottomUp),
FINAL goes through one mirrored blit. Never reintroduce the Y-negated matrices.
Honour pack directives Iris honours (vignette, underwaterOverlay default off).
Compare uniforms against Iris with build/reference/packs-debug/zzUniformProbe.zip;
test screenshots hide the GUI in both clients (vanilla vignette skews colours).
Persistent pack targets swap halves instead of being copied back only when unblended
fullscreen passes alone write them (NativeShaderChain.swappable); keep that rule, and
look images up per frame (views keyed by image ids, geometry passes via Supplier).
Depth snapshots (depthtex1/2, dhDepthTex1) exist only when a program reads them;
smoke runs force them with -Dvsdh.allDepthSnapshots=true. Windowed Vulkan present on
NVIDIA costs ~1 ms GPU per frame at 1440p (driver copy); fullscreen does not. Measure
both before claiming a lead over Iris (run-vsdh-026-bench.ps1 -HighResolution -Fullscreen).
The frame's upload batch rides as the first command buffer of the frame's own vkQueueSubmit
(GraphicsQueue.detachBatch); a separate submit cost a 0.12 ms GPU gap. The remaining gap
between frames is driver presentation (GPU_GAP, -Dvsdh.debugCpuDelayUs): do not chase it again.
Pack .dat volumes are real 3D images (GlslTranslator.nativeVolumes, PackTexturePlan.Volume);
the 2D atlas stays only as the -Dvsdh.flatVolumes=true fallback.
DXGI flip presentation (net.vulkanmod.vulkan.present): D3D12 interop is the default in a window on
NVIDIA (-Dvsdh.dxgiPresent=auto; Photon 1440p 159-163 vs 149-153 FPS); exclusive fullscreen keeps the
Vulkan swapchain (Vulkan 166-171 vs D3D12 145-150), SwapChain switches on every recreate. D3D11
(=d3d11) and a D3D12 copy queue (device removed, access denied) were slower or invalid. COM slots
come from the Windows SDK headers; never guess them, and count d3d12.h C vtables with one #if branch
only (struct-returning methods appear twice). Never change the player's driver settings from code.
Players pick packs on the Shader page of the video settings (ShaderSettingsPage): it saves
config/vsdh.json (profile/options of other packs stay in otherPacks) and ShaderPackStartup.request
swaps the world host only while no level is loaded (uninstall, then install); a failed preparation
keeps the installed host. Test both paths: -PtestSysProps=vsdh.releaseQA.menuSwitch=<zip> (title
screen: pack, other, off, back) and vsdh.releaseQA.worldSwitch=true (off in a world, applies on leave).
Distant Horizons is optional (mods.toml type="optional"): DhMixinPlugin applies no vsdh.mixins.json hook
without DH, Vsdh touches DH's API only via its DistantHorizonsSetup holder, WorldUniforms skips DH scalars
and Selection forces distant=false. Never let core render code load com.seibel classes unguarded; test
with -PtestNoDh (pack and -PtestNoShader). Smoothness is measured with run-vsdh-026-flight.ps1 (30 s flight,
per-frame CSV, flight-analyse.py at 165 Hz); single runs vary 168-227 FPS, compare medians of several.
Device-local buffer copies on the render thread ride the frame upload batch (GraphicsQueue.recordBufferUpload,
one barrier at batch end) instead of a transfer submit + fence each. G1 pauses from server/DH allocation
dominate flight stutter; keep render-thread allocation near zero per frame (no per-draw maps/regex/streams).
Pack options are listed from the pack's own screens and language file (PackOptionsView, read on the
preparation worker); only differences from the profile are stored in shaderOptions, and a newly chosen
profile clears them as in Iris. Test with vsdh.releaseQA.packOptions=true.
ShaderPackSource.options() indexes the source files plus every #included file of any extension (Miniature
keeps its settings in shader.h); never narrow it back to the extension list.
The Shader page's first row shows ShaderPackStartup.active()/requested() at the time the page opens.
Worldgen rule lists loop by index (MaterialRuleListMixin, SurfaceSequenceRuleMixin; -Dvsdh.worldgenLoops=false
for A/B). DH 3.3.x's chunk update loop spins while its head is debounced or after leaving the world;
MixinChunkUpdateQueueBackoff waits 2 ms after a pass that took in and dispatched nothing (applied only when
DH has runQueueingLoop; -Dvsdh.dhQueueBackoff=false). Both cut allocation, neither moved FPS or hitches in
7 A/B flight pairs: do not claim a smoothness gain. -Xms=-Xmx and MaxGCPauseMillis were measured and are
not recommended (longer pauses). Never leave a bench client unwatched: the flight menu wait once hung 3 h.
G1 remark spends 10-32 ms in class unloading about once a second while almost nothing unloads; the player-side
Java argument -XX:-ClassUnloadingWithConcurrentMark cut remark to ~1 ms (JFR). Recommend it, never set it.
Fullscreen vs Iris (1440p): VSDH more FPS, half the small hitches, but more >25 ms frames (G1, driver present).
Long fullscreen frames are mostly vkQueuePresentKHR blocking 20-25 ms in the driver; an FPS cap just below
the refresh rate removed them (VSDH 0 frames >25 ms vs Iris 2-3). Recommend it; do not add an automatic cap.
GUI item/entity state boundaries close at GuiGraphics.flush and reset per frame (GuiDeferredDrawState): batching mods (ImmediatelyFast) replace BufferSource and skip vanilla endBatch. World entities in renderLevel are never GUI previews.
One JAR covers every DH version: the nested coremod (src/dh330, Dh330CoreMod/Dh330Mapping) rewrites VSDH's DH references
to DH 3.3.0's _neoforge twins at load time, only for the pinned 3.3.0 SHA-1; DhSupport turns the adapter off on any
mismatch. Run verifyDhLinks after touching DH-facing code. Unimplemented GL calls warn once (GlEmulationLog.unimplemented);
UnsupportedModNotice shows one notice for mods on the compat lists and blocks nothing.
An over-budget shadow map is re-prepared with the pack's next smaller listed shadowMapResolution (ShadowResolutionFallback, never written to the player's config); the world-join check against free VRAM stays.
The swapchain is rebuilt when the window gains or loses its monitor without a resize (SwapChain.windowModeChanged, Borderless Window mod): exclusive fullscreen uses the Vulkan swapchain, every window DXGI flip.
Since 14.11. a pack change also applies inside a world (WorldShaderSession.replaceInWorld: stop builders, switch the terrain contract under PipelineManager.runTerrainSwitchInWorld, then WorldRenderer.allChanged); this replaces the old "only while no level is loaded" rule. -Dvsdh.liveShaderSwitch=false restores it. A missing configured pack file falls back to the newest file of the same pack (PackFileFallback).
BiomeManager.getBiome remembers the chosen biome cell per position on the render thread only (BiomeCornerCache, per BiomeManager instance, so per seed). NativeShaderChain geometry/scene output lists are memoised per program: view() caches by list identity, never pass a fresh list per call.
The bundled renderer has mod id vsdh_renderer (not vulkanmod, so a separately installed VulkanMod is refused by FML instead of colliding); keep the incompatible vulkanmod entry.
Frame diagnostics ([ChunkDiag] spike/buffer lines) log only with -Dvsdh.chunkDiag=true or -Dvsdh.spikeMs; the test clients set chunkDiag. Release metadata: CHANGELOG.md, docs/curseforge/, dist/<version>/ with SHA256SUMS.
Pack geometry programs that sample a colortex they draw into bind the live attachment through VK_EXT_attachment_feedback_loop_layout (FeedbackLoops, decided per program and image from the plan: image usage, feedback layout in render pass and descriptor, pipeline create flag from RenderPass.feedbackLoopPipelineFlags); on by default again since 15.11. (-Dvsdh.feedbackLoops=false for the snapshot copy NativeColorTargets.feedbackImage): the "water moved along" report came from bobbing/late sections; loop-on/off water pictures differ only by run noise. Views are keyed by image ids plus the loop mask.
DH programs sampling dhDepthTex/dhDepthTex0 read the opaque DH depth copy by default: the live depth as a depth feedback loop races with dh_water's own depth writes (no barrier between draws; Eclipse marches reflections through it at other pixels) and made distant water flicker. -Dvsdh.dhDepthFeedbackLoop=true opts in; never make it the default without a same-scene flicker check. dhDepthTex1 stays the separate opaque copy, made only when something reads it. Never bind an attachment as a sampler outside a pass built for that loop (renderDraw rejects it).
The shadow pass walks ShadowSectionList (drawable sections grouped by area, rebuilt only after RenderSection changes call ShadowSectionList.changed()); a new writer of completelyEmpty, chunkArea or the section origin must call it. -Dvsdh.shadowSectionList=false is the old full walk, =verify compares both. ShadowCuller.contains must imply visible for every inner box.
No frame limiter of our own: the existing Max Framerate slider carries the advice (vsdh.options.framerateLimit.tooltip, German and English). Stop every test client while the user plays (Prism instance or another game such as Rocket League); results from such a run are invalid.
In big packs the long frames follow the 50 ms client tick (JFR + frame CSV): look at tick work first. BiomeFiddleCache must keep getFiddledDistance's operand order (bit-identical, the test compares raw bits); VTextureSelector.uploadSubTexture stages only SubImageRange and keeps the old path whenever the range is not inside the buffer; WorldUniforms picks each member's provider once (UniformRoutes): a new provider or name set must be added to UniformRoutes.route in loop order. A/B: -Dvsdh.biomeFiddleCache, -Dvsdh.tightSpriteUpload, -Dvsdh.uniformRoutes (=false). Render-thread JFR shares in that pack rest on 100-300 samples; quote them as rough.
Window modes: Window, Borderless Fullscreen, Exclusive Fullscreen (Config.borderlessFullscreen = kind, Minecraft's flag =
fullscreen at all). Borderless presents through the Vulkan swapchain (WindowPresentation, -Dvsdh.borderlessPresent=dxgi for
the old DXGI flip path); ordinary windows keep DXGI. Measured 15.11. (Eclipse 1440p flights, 2 runs each): frames >2 refresh
intervals per s borderless-Vulkan 3.2, exclusive 2.6-2.7, borderless-DXGI 4.6-6.0. Exclusive fullscreen: no Windows audio
device or endpoint change was seen on enter/minimise/restore (scratch audio-watch); the player's brief switch is outside the
mod (SteelSeries Sonar routing), so borderless is the answer for it.
"Water moves with the camera" (Eclipse + DH) was missing vanilla sections, not a shader bug: sections compile only once the
graph reaches them, so after turning/walking DH far water filled the gap ~1 s. SectionGraph.prebuildHidden builds the nearest
never-built sections while no visible rebuild waits (-Dvsdh.prebuildHidden=false). Reproduce with the walk series:
-PtestSysProps=vsdh.turnSeries=true,vsdh.turnSeries.shore=true,vsdh.turnSeries.walk=true (test clients, both Iris and VSDH).
DH projection for pack programs = camera GL projection with DH depth row (DistantProjection.followCamera): DH hands a plain
perspective without view bobbing and with a lagging FOV. Uncompiled sections are open in the visibility walk (openUncompiled);
ChunkCatchUp raises build/upload budgets while >=24 visible buildable sections are uncompiled (unloaded edge chunks never count).
Walk tests: vsdh.turnSeries.walk (spectator, no bobbing) and vsdh.turnSeries.ground (real walking); screenshot frame diffs between
clients mix bob phases (partial tick at grab time), so do not read them as flicker without a per-frame check.
While a pack host exists, view bobbing and hurt tilt go into the model view, not the projection (CameraBobbing,
WorldShaderGameMixin; Iris: MixinModelViewBobbing): packs treat gbufferProjection as a pure perspective, otherwise shadows
and reflections slide with every step. Never publish a projection with off-diagonal x/y terms to a pack. The pack DH view is
the camera view (matrices.view), not DH's own rotation. Walking bugs need the ground test (real walking), not spectator flight.
Prepared packs are cached per PackPreparationKey (file size/mtime, profile, options, distant, translator switches) in
ShaderPackStartup (LRU 3, -Dvsdh.packCache); preparations run one at a time (PREPARING lock); warm() prepares ahead on a
MIN_PRIORITY worker when the Shader page opens. A recheck that must prepare again calls forget() first, or the cached result
loops. PackOptionsView.latest is set when a prepared pack is asked for (also from the cache), never by a warm-up.
While the surface has no extent (minimised, or a recreate that ran during a fullscreen/window switch) SwapChain destroys the Vulkan swapchain and returns early; the client tick keeps running. Its image list and texture ids used to keep the destroyed images, and a Screenshot.grab in that state crashed in nvoglv64 (vkCmdCopyImageToBuffer, read of 0x108). Now the early return, closeDxgi and the Vulkan-to-DXGI switch unbind the ids (createGlIds reuses them, no new set per recreate), getColorAttachmentGlId returns 0 and getColorAttachment null without images (SwapChainSlots), Screenshot._grab reports a failure, and Renderer.beginFrame retries a lost swapchain every 250 ms while the window reports a size. Never hand out a swapchain image or its id after the swapchain is gone.
ClientLevel.getBiome answers on the render thread from the chunk sections when every section behind the eight candidate cells holds one and the same biome (UniformBiomeProbe, ClientLevelUniformBiomeMixin adds the method, no callback object); all else takes the BiomeManager path unchanged. A/B: -Dvsdh.uniformBiomeSkip=false, =verify compares both and logs UNIFORM_BIOME hit rate/mismatches. Not measured in game yet; the profiled scene is GPU-bound (43 % of render-thread samples wait on present/fence), so expect shorter tick frames, not more FPS.
Pack frames skip a terrain layer that cannot draw (TerrainLayerDraws: SOLID/TRIPWIRE never hold geometry, a layer with no area buffer in a queued area neither); it used to cost uniforms, an empty pass and, for TRIPWIRE, the feedback snapshot copies of gbuffers_water. The skipped path must still run finishSectionLayer (indirect uploads after CUTOUT/TRIPWIRE). -Dvsdh.skipEmptyTerrainLayers=false for A/B. PersistentSwap (-Dvsdh.swapAfterGeometry=true, opt-in) also swaps persistent targets geometry draws into when an unblended fullscreen pass replaces the alternate half first; keep it opt-in until pictures are compared (a shrunk or discarding pass shows other stale texels than Iris), and compute-only passes are no readers there. Neither is measured in game yet.
ChunkCatchUp raises only counts (32 uploads/schedules); its time budgets equal the normal ones (2 ms upload, 1 ms schedule):
the 5/3 ms version cost 1% lows in the flight A/B. prebuildHidden: 1 section per frame within 96 blocks horizontally.
uniformBiomeSkip is opt-in (0.2 % hit rate in game). skipEmptyTerrainLayers is on (GPU-measured), swapAfterGeometry opt-in.
GC (15.11., Eclipse flight, 10 GiB heap like the player): generational ZGC (-XX:+UseZGC -XX:+ZGenerational) best, plain ZGC close, G1 clearly worse (9-10 frames >33 ms per 30 s vs 0-1). Recommend, never set.
DH stalls (15.11.): in stall frames the GPU idles 16-30 ms before an already submitted frame starts while the CPU waits in the fence: the frame waits for the swapchain image (uploads submitted alone run at once, -Dvsdh.separateUploadSubmit=true); it follows DH's loading work (16 workers), not GPU work, VMA allocations (-Dvsdh.vmaTrace), fence polling or swapchain image count (-Dvsdh.swapchainImages). Fewer DH threads load less distant terrain per flight: never compare FPS across DH thread settings without the GPU profile's dhOpaque/dhWater. Recommend DH CPU settings to players, never write them.
The visible-section set is walked again on ANY camera or cull-frustum change (GraphUpdatePolicy, as Sodium/vanilla): the pinned Reforged rule (turn >2 degrees or move 0.5 block, frustum without margin) left sections at the leading screen edge undrawn until the next step ("drags behind when I turn"); block entities use the same frustum. -Dvsdh.exactCullUpdates=false restores the thresholds. Measured 15.11. (test client): turn series shows real terrain at the leading edge with the fix and DH LODs without; Eclipse 1440p flights 3 pairs: median FPS equal (130.5 vs 130.4), frames >2 refresh intervals 3.6-4.3/s vs 6.3-7.7/s (a hint, flight is no turn test). Checked and fine in the same review: previous-frame matrices (one capture/commit per renderLevel, bobbed view in both), shadow/DH frusta of the current frame.
Late swapchain wait (pack frames submit world+pack passes before the image wait, FINAL/GUI later; commit 8fe3174, removed again): works (every frame split, none refused) but measured worse, Eclipse 1440p window 3 pairs: frames >2 refresh intervals 27-32/s vs 3.7-4.2/s, median FPS 116.8 vs 124.5; GPU profile: imageWait was already 0, the GPU instead idled ~0.3 ms waiting for the second submit. Do not retry without a new cause for an image wait.
Pipeline cache (PipelineCacheStore/PipelineCacheFile, config/vulkanmod_pipeline_cache.bin): every vkCreate*Pipelines call already went through the one shared cache (only GraphicsPipeline and ComputePipeline create pipelines, all on the render thread; pack preparation workers build no GPU objects). What was missing: it was written only on clean exit. Now Renderer.beginFrame snapshots it (vkGetPipelineCacheData on the render thread) 3 s after the last pipeline creation (20 s at the latest) when its size changed, and a worker writes temp file + ATOMIC_MOVE; the loaded file's VkPipelineCacheHeaderVersionOne must match vendorID/deviceID/pipelineCacheUUID or the cache starts empty. Never create the cache with EXTERNALLY_SYNCHRONIZED_BIT (without it the driver locks it itself), never pass VK_NULL_HANDLE to a new creation site. -Dvsdh.pipelineCacheSave=false: save at exit only. Log: "Pack pipelines of <pack>: N created in X ms (cache: Y KiB loaded)". Cold vs warm start not measured in game yet: do not claim a stutter gain.
Input latency ("the camera turns later than the mouse", 15.11.): Minecraft polls input at the END of a frame (Window.updateDisplay -> RenderSystem.flipFrame, and the limiter's glfwWaitEventsTimeout) and applies the mouse in the next frame after RenderSystem.clear (MouseHandler.handleAccumulatedMovement). Under OpenGL the blocking swap sits before that poll; the renderer blocked after it (frame-slot fence in preInitFrame at runTick HEAD, vkAcquireNextImageKHR in beginFrame at the clear), so the wait was added to the latency. Renderer.waitBeforeInput now spends both waits at the end of endFrame, before the poll (InputLatency; earlyImageIndex is dropped by recreateSwapChain; DXGI frames move only the fence). A/B: -Dvsdh.earlyFrameWait=false (old order), -Dvsdh.earlyAcquire=false (fence only). Every new CPU wait of a frame (fence, acquire, present wait) belongs in waitBeforeInput, never between the poll and the camera. Low latency mode (video setting "Low Latency Mode", Config.lowLatency, -Dvsdh.lowLatency=true|false, default off): VK_KHR_present_id + VK_KHR_present_wait (enabled at device creation when offered, -Dvsdh.presentWait=false), each present carries an id and the next frame waits up to 100 ms for it to be on screen (vkWaitForPresentKHR) before the poll; Vulkan swapchain only (borderless/exclusive). It halves the frame rate with VSync whenever a frame takes longer than a refresh interval: keep it opt-in. -Dvsdh.inputTrace=true logs INPUT_TRACE (poll-to-present avg/max). Pack camera hooks were checked and are not stale (view/projection come from the same renderLevel call). NOT measured in game yet: do not claim a latency gain before an A/B (PresentMon or INPUT_TRACE).
Setup computes: every numbered host runs once per graph in index order (setup, setup1 ... setup99, Iris ProgramSet.readProgramArray), not only `setup` (ShaderChainPlan.hostsOf). Jurgens v1.1 fills its cloud noise volumes in setup2-setup10 and its multiple-scattering table in setup1; with only `setup` run the 3D images stayed zero: no volumetric clouds, picture too bright. The log line "Setup computes dispatched once" lists the programs and the custom images; auditWorldHost prints setupComputes offline. Not yet compared in game.
Shadow casters the camera never looked at (15.11.): sections compile only once the camera's walk queues them, and a dirty
section outside the walk is dropped from the queue, so hills, trees and block changes behind or beside the player cast no
shadow until looked at (Iris+Sodium behave the same: MixinRenderSectionManagerShadow cancels updateChunks in the shadow
pass). The pack shadow pass now starts them itself (ShadowCasterBuilds, SectionGraph.scheduleShadowBuilds): dirty, buildable
sections inside ShadowCuller.visible and the shadowDistance*1.414 cube, never-built ones only when they hold blocks, the
nearest 4 of a 2048-section scan window per frame; nothing while a camera section waits, only inside the rest of the normal
1 ms schedule time, never into the last 32 worker queue slots. -Dvsdh.shadowCasterBuilds=false for A/B
(-Dvsdh.shadowCasterBuildsPerFrame=N). Not measured in game: check flight 1% lows before raising the count, never raise the time.
Pack preparation (15.11., headless bench, see docs/PERFORMANCE.md): shaderc was 2/3-3/4 of the 18-22 s. The plan thread
still reads the pack and flattens every entry file alone (ShaderPackSource is single-threaded: never touch pack.source() from a
worker); translation + compilation of each program run on PreparePool workers (PackProgramSource.task, one shaderc compiler and
options object per task as shaderc.h allows; -Dvsdh.prepareThreads=N, 0 = old sequential path, default cores-2 capped at 16;
30 threads were slower than 8-16). Results are taken in plan order, so they do not depend on worker timing: the bench digest
(PackPrepareBench: every translated text, SPIR-V, binding, texture) must stay equal between prepareThreads=0 and the default
for every pack; check it after any change there. WorldPackPlan.scout queues the programs the plan will ask for (read-ahead,
*.prefetch beside each *.prepare): keep each prefetch's names/inputs/alpha equal to its prepare; a wrong one only wastes
worker time, a missing one costs wall time. PackProgramSource.settle waits for every worker before a preparation returns:
the translator's switches and registries (CustomImages/CustomStorage, reduceTrig, nativeVolumes) are process-wide, so no
task may run on into the next pack. Two disk stores under config/vsdh-shadercache (ShaderDiskCache): spirv/ (SpirvCache:
key = SHA-256 of translated stage texts + program name + binding table + fullscreen flag + edition; edition = bytes of
VulkanProgramCompiler/SpirvInterface/GeometryFold classes + LWJGL/shaderc version, so a changed compile step never reads old
files; digest + SPIR-V magic/alignment checked, anything else is a miss; atomic move; 256 MiB LRU, -Dvsdh.spirvCacheMiB;
-Dvsdh.spirvCache=false) and translations/ (the translator's own TranslationCache, edition = digest of the dev/vitrail classes
in the mod file; -Dvsdh.translationCache=false). Whatever else decides a compile result must go into SpirvCache.key.
VulkanProgramCompiler.bindings walks stages in enum order (the translated stage map has none). Decoded pack textures are shared
between the dimensions of one opening and PNGs decode on the workers during compilation (-Dvsdh.sharedPackTextures=false).
Warm-ups use a quarter of the workers at MIN_PRIORITY until a request is open (PreparePool.urgent). No world-load warm-up was
added: with warm stores a pack prepares in 0.5-2 s (JIT-warm) and the Shader page already warms two used packs.
-Dvsdh.keepTerrainOnSwitch=true (opt-in, NOT tested in game): a pack change in a world keeps the built sections when both
packs have the same block.properties table (WorldPackSet.sameTerrainContract; the 44-byte format is pack-independent) and
only re-marks sections whose build was interrupted (WorldRenderer.resumeBuilders); default stays allChanged.
Evening 15.11. (player report "40-70 FPS with every pack, the picture hitches", config had lowLatency=true): low latency
mode makes every frame wait for the previous one to be on screen, so CPU and GPU time add up (VSync: rounded up to whole
refresh intervals, 180 Hz -> 60/45; no VSync: 1/(c+g) instead of 1/max(c,g)). That is a loss with every GPU-bound scene,
i.e. with shader packs on any GPU; it is no longer a video setting. Only -Dvsdh.lowLatency=true turns it on; Config.lowLatency
stays in the file but is not read (InputLatency.lowLatency). Do not bring the switch back without an in-game FPS A/B with a pack.
ShadowCasterBuilds is opt-in (-Dvsdh.shadowCasterBuilds=true) until measured: besides the region copies, never-built air
sections stay dirty and pay the caster test, two set lookups and a chunk lookup on every scan pass, and every section it
builds is drawn into the shadow map from then on. This supersedes "-Dvsdh.shadowCasterBuilds=false for A/B" above.
Rule from this day: a change with per-frame cost that was not measured in game ships off (opt-in switch); defaults must hold
for players in general (any GPU/CPU, render distance, pack, VSync on/off, window mode), never for one test setup.
Kept on after review: exact cull updates (flight A/B: equal median FPS), early fence/acquire (moves waits, adds none; the
limiter is deadline-based), pipeline cache snapshot (one vkGetPipelineCacheData copy 3-20 s after a pipeline burst, not per frame;
its render-thread time is still unmeasured: -Dvsdh.pipelineCacheSave=false if a hitch follows the "Pipeline cache saved" line).
Translucent entity layers (entity_translucent and cull variants: player skin, 3D skin layers, horses) of a pack WITHOUT
gbuffers_entities_translucent / gbuffers_block_translucent draw with gbuffers_entities / gbuffers_block (cutout variant, alpha
0.1), as Iris (ProgramId.EntitiesTrans -> Entities, BlockTrans -> Block; javap on the pinned Iris JAR). They used to fall to the
standard shader, which writes colortex0 only: in a deferred pack the player's body was missing in third person while armour
(cutout) was drawn. EntityPackPlan.translucentOrFallback; -Dvsdh.translucentEntityFallback=false. A program the pack has but
that was refused keeps the standard draw. EntityPackPlan.programs() lists a shared program once. Not checked in game yet.
A configured pack whose file is gone (PackFileFallback) is named by its real file in the log ("ready: v1.2 (configured: v1.1,
file not found)") and the Shader page reads profiles from the resolved file; vsdh.json is still not rewritten.
