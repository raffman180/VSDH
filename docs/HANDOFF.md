# Weiterarbeit an VSDH

Projekt: `C:/Users/Stranger/Documents/Meine Mods/VSDH`, Branch `codex/renderer-shaders`.
MC 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.2.0-b, 3.3.0–3.3.3. Der Nutzer hat nach der
Fork-Empfehlung die autonome Weiterarbeit bis zum Ziel beauftragt. Keine erneute
Grundsatzfrage Addon/Fork. Eine JAR enthält die Mod-IDs `vsdh` und `vulkanmod`;
die originale Reforged-JAR darf nicht parallel installiert sein.

## Aktuell 0.26 — Speicher-Churn (13.11.2026 vormittags, `dist/0.26.0-dev-perf`)

Mixins: `net.vulkanmod.mixin.optimization.MaterialRuleListMixin`/`SurfaceSequenceRuleMixin` (Access Transformer
für `SurfaceRules$SurfaceRule`/`$SequenceRule`, Schalter in `MixinPlugin`), `dh3.MixinChunkUpdateQueueBackoff`
(Schalter und Methodenprüfung in `DhMixinPlugin.dhClassHasMethod`). Werkzeuge: Flug mit Menü-Wartezeit
`-PtestFlightMenu=<s>` (ReleaseScene `menuStopAt`), JFR-Auswertung mit `--stack-depth 40` und Zeitfenster.
Messläufe immer mit Wächter starten (Log-Alter prüfen); ein Lauf hing einmal 3 h unbemerkt.
Weitere Testclient-Schalter: `-PtestFlightFps=<n>` (beide Clients, FPS-Grenze im Flug), `-PtestJfrSettings=<jfc>`
(VSDH-Client). Lange Frames zuordnen: Spike-Zeilen mit `-Dvsdh.spikeMs=18 -Dvsdh.spikeLogMs=0` gegen
`flight-frames.csv` (Epoch-Zeitstempel). Ergebnisse und Spieler-Empfehlungen: `docs/PERFORMANCE.md` (Ende).

## 0.26 — DH optional und Flug-Messung (13.11.2026 nachts)

Pack-Optionen: `PackOptionsView.read` (im Vorbereitungs-Worker von `ShaderPackStartup`, Sprachdatei via
`PackLang`, Layout via `ShaderProperties.screenLayout`), Seite `ShaderSettingsPage.optionsPage()`; Test
`-PtestSysProps=vsdh.releaseQA.packOptions=true` (optional `vsdh.releaseQA.packOption=<NAME>`).

DH optional: `neoforge.mods.toml` (optional), `DhMixinPlugin.distantHorizonsInstalled()` (keine DH-Hooks,
auch nicht `shared.MixinLevelRenderer`), `Vsdh.distantHorizons()` + Halterklasse `DistantHorizonsSetup`,
`WorldUniforms.captureDistantScalars`, `ShaderPackStartup.Selection` (distant nur mit DH), Shader-Seite.
Testclient: `-PtestNoDh` (DH nur zum Kompilieren). Glätte: `run-vsdh-026-flight.ps1`, `flight-analyse.py`,
`FlightBench` in beiden Testclients, `-PtestJvmArgs`, `-PtestJfr/-PtestJfrDelay/-PtestJfrDuration`.
Upload-Batch für Geräte-lokale Puffer: `MemoryTypes.DeviceLocalMemory.copyToBuffer` →
`GraphicsQueue.recordBufferUpload`/`closeBufferUploads`. Ergebnisse: `docs/PERFORMANCE.md`, letzter Abschnitt.

## Aktuell 0.26 — Shader-Seite im Menü (12.11.2026 abends)

Spieler wählen Packs unter Videoeinstellungen → Shader (`de.stranger.vsdh.shader.ShaderSettingsPage`,
eingehängt in `Options.getOptionPages`, Übernehmen ruft über `ModSettingsRegistry` `applied()`).
`ShaderPackStartup` ist jetzt ein Steuerobjekt: `start` (Konfiguration beim Laden), `request(Selection)`
(Seite), Tick tauscht den Welt-Host nur ohne geladene Welt (`uninstall`, dann `install`).
`DhVulkanConfig.save/selectPack` schreiben `vsdh.json` und merken Profil/Optionen je Pack (`otherPacks`).
Neue Widgets `ActionOption`/`ActionOptionWidget` (Knopf ohne Wert). Testszene:
`-PtestSysProps=vsdh.releaseQA.menuSwitch=<zip>` und `vsdh.releaseQA.worldSwitch=true`
(`release-client-026-packs/ReleaseScene`). Paket `dist/0.26.0-dev-menu`.

## Aktuell 0.26 — Pack-Kompatibilität (11.11.2026)

Architektur und Grenzen: `docs/NATIVE_PACK_FEATURES.md`. Kernklassen:
`NativeComputePass` + `ComputePipeline` (Compute, `colorimgN`, GENERAL-Layout,
Barriere nach jedem Dispatch), `ShaderChainPlan.Compute/setup/shadowComputes`,
`NativeStorageBuffers` + `StorageBufferDescriptor` (SSBO, Schlüssel `ssbo#N`),
`NativeCustomImages` + `CustomImageBindings` (`image.*`), `PackTexturePlan.Texels`
(Rohdaten 2D) und `PackTexturePlan.Volume` (3D-Volumen als echtes 3D-Bild, seit 12.11.;
`-Dvsdh.flatVolumes=true` = alter `VolumeAtlas`), Geometry: `GeometryFold` im Compiler, sonst
nativ mit `SpirvInterface.renameGeometryOutputs/linkStages`; Tessellation über
VulkanMods vorhandenen Pfad (`renameControlOutputs`, `sizeControlOutputs`).
`EngineDefines.FEATURES` steuert `iris.features.required` und `IRIS_FEATURE_*`.
Diagnose: `-Dvsdh.uniformDump=<Programmendung>` loggt alle 240 Frames die
geschriebenen Uniforms eines Programms (`UNIFORM_DUMP`). Der Testclient
`build/reference/release-client-026-packs` nimmt `-PtestUniformDump=` und
`-PtestShotTick=`; `run-vsdh-026-compare.ps1 -ReuseIris -Only ...` vergleicht mit
den Iris-Bildern in `build/reference/compare-026`, `pair.ps1` (Scratchpad) setzt
Paare zusammen. Ein Fehler beim Graphaufbau schaltet die Welt auf den
Standard-Renderer (Chat-Hinweis), bis Fenstergröße oder Dimension wechseln.

Stand 11.11. abends: BSL-Wolken kommen über DH-Generic-Objekte; die hohe
Dschungelinsel hängt vom DH-Erzeugungsfortschritt ab (kein Renderfehler);
RV-Entity-Schattenwerfer kompilieren nativ; `shadowcolorimgN` und `indirect.*`
sind umgesetzt. Die Pack-Kette arbeitet in GL-Ausrichtung (Zeilen von unten,
`gbufferProjection[1][1]` positiv, einmaliges gespiegeltes Blit ins Hauptziel,
`Renderer.setMainTargetRowsBottomUp`, `PipelineState.getCurrentPipelineState(pass,true)`,
DH-Tiefenleser spiegelt). `vignette`/`underwaterOverlay` wie Iris. Prüf-Packs
(nur Test): `build/reference/packs-debug/zzUniformProbe.zip`, `zzIndirectProbe.zip`.
Offen: typisierte Custom-Image-Views (kein Testpack nutzt sie), reine
Nachbearbeitungs-Packs ohne Terrainprogramm (werden mit Meldung abgelehnt).

## Aktuell 0.26 — Nachtarbeit (12.11.2026)

Ergebnisse und Messhinweise: `docs/PERFORMANCE.md` („GPU-Pause zwischen Frames genau vermessen“).
Neue Klassen/Stellen: `GraphicsQueue.detachBatch` + `Renderer.submitFrame(uploads)`,
`NativeImageOps.mipmaps(List, boolean[])`, `PackTexturePlan.Volume` + `NativeWorldTextures.createVolume`,
`GlslTranslator.nativeVolumes`, `NativeWorldShadows.opaqueSnapshot`, `NativeGraphicsProgram.transitionInputs`
(nur im Profilmodus). Diagnose: `-Dvsdh.debugCpuDelayUs=N`, Testclient `-PtestSysProps=a=b,c=d`.
Mess-Packs mit festem Bildinhalt (nur lokal erzeugen, nie ausliefern) sind für Vergleiche einzelner
Shader mit Iris verlässlicher als Pass-Zeiten echter Packs.

## Aktuell 0.26 — eigene DXGI-Ausgabe (12.11.2026 mittags)

Im Fenster präsentiert VSDH auf NVIDIA über D3D12 (`net.vulkanmod.vulkan.present`): D3D12 besitzt drei
gemeinsame Texturen und zwei Fences, Vulkan rendert hinein, D3D12 kopiert in den Back-Buffer einer
Flip-Swapchain. Im Vollbild bleibt die Vulkan-Swapchain (schneller); `SwapChain.createSwapChain`
wechselt nach `glfwGetWindowMonitor`. Fehler jeder Art → Rückfall auf Vulkan. Schalter
`-Dvsdh.dxgiPresent=auto|d3d12|d3d11|false`. Ergebnis und verworfene Wege (D3D11, Kopier-Queue,
Vorwärmen): `docs/PERFORMANCE.md`. Test-Hilfe: `-PtestSysProps=vsdh.releaseQA.windowSteps=150:toggle;350:1600x900`.

## 0.26 — 1440p gegen Iris (11.11.2026 abends)

Vollbild 2560×1440: VSDH in allen fünf Mess-Packs vor Iris (Tabelle
`docs/PERFORMANCE.md`). Im Fenster kostet NVIDIAs Vulkan-Present ~1 ms GPU pro Frame
(Treiberkopie), dort liegt VSDH 6–10 % zurück. Present-Thread, verzögertes Present und
eigene Present-Queue brachten nichts und sind entfernt bzw. aus
(`-Dvsdh.separatePresentQueue=true`). Gewinne: Tausch statt Rückkopie persistenter
Ziele (`NativeShaderChain.swappable`, Notschalter `-Dvsdh.copyBack=true`), Tiefen-
Schnappschüsse nur bei Bedarf (`PackUsage`). Werkzeuge: `run-vsdh-026-bench.ps1
-HighResolution -Fullscreen`, `run-vsdh-026-profile.ps1 -Extra '-Ptest1440p','-PtestFullscreen'`,
`-PtestVsync`, `-PtestSyncPresent` (nur noch Diagnose).

## Aktuell 0.26 — Leistung

Messwerkzeuge, Testszene und Befunde stehen in `docs/PERFORMANCE.md`.
`ShadowCuller` begrenzt den Schattenpass wie Iris (`shadow.culling`,
Voxelisierung, `shadowDistance × shadowDistanceRenderMul`, `voxelDistance`).
`WorldRenderer.prepareShadowDraws` prüft zuerst Bereiche, dann Sektionen;
mit `indirectDraw` zeichnet der Schattenpass über eigene Indirect-Puffer.
`NativeWorldDepth` kopiert für depthtex1/2 nur den Tiefenanteil. Das war
mit 2 × 1,3 ms der größte VSDH-eigene GPU-Posten. `GraphicsQueue` bündelt
Uploads des Render-Threads pro Frame ohne CPU-Fence. `Renderer` öffnet das
Bündel am Ende von `preInitFrame`; seit 12.11. geht es in `endFrame` als erster
Command-Buffer im selben `vkQueueSubmit` wie der Frame mit (`GraphicsQueue.detachBatch`),
in `flushCmds`, beim Swapchain-Neuaufbau und vor jeder anderen Grafik-Queue-Übergabe
weiterhin als eigener Submit.
Notschalter: `-Dvsdh.legacyUploadSync=true`. Gemessen in derselben Szene:
0.25 89,4 FPS, 0.26 112,4 FPS. Kein FPS-Vergleich mit Iris, keine anderen GPUs.

Kompatibilität in 0.26: `NativeDistantPass` zeichnet DH-Pack-Programme mit
Rückseitenausblendung wie DH selbst. Das beseitigt die weißen Kacheln von
Bliss über Ozeanen; die Bildreferenz unter Iris stimmt jetzt überein. Die
DH-Kennzahlen `dhNearPlane`, `dhFarPlane` und `dhRenderDistance` sind immer
verfügbar, Bliss startet so auch ohne DH-Programme. Der Iris-Referenzclient
unter `build/reference/iris-client` hat Seed, Kamera und DH-Konfiguration
der VSDH-Testprofile und ist das Werkzeug für Pack-Fehler.

## Stand 0.25 — DH 3.2.0-b und Shader-Stufen

`DhMixinPlugin` liest FullDataSourceV2Repo.class direkt aus der installierten
DH-JAR. Die NeoForge-Mixin-Phase hat dafür noch keine zuverlässig
zugängliche transformierte Klasse. Die ältere Abfrage heißt
getChildPositionsToUpdate(III), die neuere getChildPositionsToRegen(IIII).
Die Legacy-Variante wartet vor dem Schließen der DB auf einen Executor,
die moderne auf zwei. VkMetaRenderer unterstützt applyToMcTexture und
copyToMcTexture. 3.3.0 wird weiterhin gesondert gebaut.

`VulkanProgramCompiler` verwendet für ein Vertex-/Fragment-Paar
SPIR-V-Namen zum Verbinden impliziter Stage-IO. Optimierung bleibt
aktiv. Explizite Fragment-Locations bleiben erhalten, eingebetteter
Quelltext wird nach der Zuordnung entfernt. Der Regressionstest
enthält absichtlich unterschiedlich deklarierte Reihenfolgen.
`NativeColorTargets` hält bei geometrischen Feedback-Lesezugriffen
eine bedarfsgerechte Kopie des aktiven Mip-Bereichs. Kopien zählen
zum gleichen GPU-Budget wie die ursprünglichen Farbziele.
Details zur Laufzeit in `dist/0.25.0-dev/verification.json`.

## Stand 0.24 — DH-Tiefenadapter und BSL

`src/dh33Early` kompiliert den 3.3.1-Tiefenadapter gegen das
geprüfte DH-3.3.1-Artefakt. Der moderne Adapter verwendet DH 3.3.3 zum
Kompilieren. Beide landen in einer VSDH-JAR. `tools/fetch-dependencies.ps1`
prüft beide Referenzen. Die Laufzeit lädt genau eine installierte DH-JAR.
Die älteren und neueren `setApiValue`-Signaturen ändern nur Sitzungswerte.
`jarDh330 -Pdh330Jar=<originale DH-JAR>` baut die getrennte VSDH-JAR
für 3.3.0. `tools/RemapDh330.java` prüft den DH-Hash und ordnet die
referenzierten DH-Typen den gleichnamigen `_neoforge`-Typen zu.

Die BSL-Erweiterung liegt im gemeinsamen Uniform- und Texturpfad.
Siehe `docs/MIGRATION_STATUS.md` und `dist/0.24.0-dev/verification.json` für
Spieltests und offene Pack-/Versionsfamilien.

## Stand 0.23 — Hand, Glanz und Modellschatten

NativeHandRenderer ruft die Minecraft-Hand mit eigener Matrix und
eigenen Puffern auf. Die opake Hand liegt zwischen depthtex2 und
depthtex1. Die transparente Hand liegt vor Composite. Die normale
Egoansicht führt ihre Bildschirmeffekte weiter aus. ItemRenderer
grenzt Item-IDs über eigene Batches ab.
NativeModelBuffers hält getrennte Vertexpuffer für Glanz und Grundmodell.
NativeWorldShadows zeichnet die Modelle in derselben Schattenkarte vor
der opaken Schattenkopie. shaderOptions überschreibt deklarierte
Pack-Einstellungen beim Start. Prüfstände stehen im Paketbericht.

## Stand 0.20 — Transparente Mob-Schichten

EntityPackPlan prüft `gbuffers_entities_translucent` einschließlich der
DH-Werte. Bei `separateEntityDraws=true` übernimmt WorldShaderEntityLayerMixin
die sortierten Meshes für dieselbe Frame-Warteschlange. runDeferred zeichnet
sie nach der opaken Tiefenkopie und vor der Welttransparenz. Die normalen
Material-IDs stammen weiter aus dem Meshaufbau. Nicht unterstützte Schichten
benutzen den Standardpfad.

## Stand 0.19 — Opake Blockentitäten

WorldShaderBlockEntityMixin erfasst die Blockzustands-ID aus derselben
Materialtabelle wie das Terrain. Homogene NEW_ENTITY-Batches mit
Blockkontext verwenden den Originalpass `gbuffers_block` vor Deferred.
NativeEntityMesh schreibt die ID in die zweite U16-Spur. Bei einem
Batch mit verschiedenen Kontextarten greift der Standardpfad.

## Stand 0.18 — Opake Entitäten

EntityPackPlan bereitet den tatsächlichen Entitätspass und die
Materialtabelle vor. WorldShaderLevelMixin setzt den Kontext bei
LevelRenderer.renderEntity. BufferBuilderM speichert die ID passend
zum Vertex und hängt sie an MeshData. NativeEntityMesh erweitert den
Batch unmittelbar vor dem Vulkan-Zeichnen. Der Fallback behält den
Originalpuffer. Unterstützt sind die opaken Standardprogramme für
NEW_ENTITY-Quads vor Deferred und die Standardtexturen dieser Pässe.
Complementarys Netherprogramm bezieht noisetex aus PackTexturePlan.
Details zu Tests und den noch offenen Geometriephasen in NEXT_AI_PROMPT.md.

## Stand 0.17 — Ganzzahlige Shader-Farbpuffer lesen

NativeGraphicsProgram bindet `isampler2D` und `usampler2D` an passende
Farbbilder. NativeColorTargets liefert dafür bereits Nearest-Filter.
ShaderChainPlan lehnt im CPU-Lauf falsche Paare aus Farbformat und
Sampler ab. ChainGpuCheck prüft das Lesen signierter und unsignierter
Werte aus Prepare in Deferred. Die konkreten Spielläufe und das
Quellarchiv stehen unter dist/0.17.0-dev.

## Stand 0.16 — Terrain-Lichtkoordinaten und Vollbildmischung

VulkanTerrainVertex aliasiert `gl_MultiTexCoord2` auf die Lichtkoordinaten
von `gl_MultiTexCoord1`. NativeShaderChain übernimmt programmweite
Mischregeln für die Vollbild-Durchläufe vor Final. NativeFullscreenPass
überspringt die Mischung für ganzzahlige Ausgaben. ChainGpuCheck prüft
sowohl die gemischten Farbwerte als auch eine unveränderte RGBA8UI-Ausgabe.
TerrainGpuCheck liest beide GLSL-Eingaben in allen vier Terrainvarianten.

## Stand 0.15 — Beleuchtung des Nahwassers

Bei aktivem Shader und backFaceCulling bleibt die Minecraft-Cull-Einstellung
für transparentes Terrain wirksam. Ein Vulkan-/Iris-A/B-Test isolierte die
nach unten gerichtete Normale der doppelt gezeichneten Wasseroberfläche.
WaterFaceProbe prüft die sichtbare Normale im unveränderten Welt-Zeichenpfad.
Details und verbleibende Aufgaben in DISTANT_PACK.md und NEXT_AI_PROMPT.md.

## Stand 0.14 — Experimentelle Auswahl

ShaderPackStartup liest die Auswahl aus config/vsdh.json. Die bereits
geladene Clientkonfiguration wird unverändert verwendet. Das Pack und
seine Dimensionspläne entstehen auf einem Worker. Die Installation erfolgt
im Menü auf dem Renderthread. Testprofile befinden sich unter build/.
Die verifizierte JAR, das Quellarchiv und der Prüfbericht liegen unter
dist/0.14.0-dev. Das gebaute JAR wurde bei 2560×1440 im getrennten
NeoForge-Profil geprüft. Der Dimensionslauf und die Ablehnung eines
ungültigen Pfads sind in den 0.14-Logs belegt.

## Stand 0.13 — Dimensionspläne

WorldPackSet bereitet die Programme für Oberwelt, Nether und Ende vor.
WorldShaderSession wählt den neuen Plan am ClientLevel-Wechsel. Ein
gemeinsamer Materialvertrag hält die Terrainpuffer kompatibel. Der
CPU-Audit und die 60 Tests bestehen. Aktuellen Spielstatus in
verify-0.13-dimensions-client.log nachsehen.

## Stand 0.12 — Wetter und Partikel

Partikel und Niederschlag nutzen ihre Packprogramme auf dem bestehenden
Minecraft-Vertexformat. Der Diagnoseclient hat 26.513 echte Regenpixel
gelesen und beide Seiten des Partikelzeitpunkts ausgeführt. WORLD_WEATHER.md
beschreibt Reihenfolge und Grenzen. Für den Original-Pack-Lauf den neuesten
Prüfbericht und verify-0.12-complementary-client.log lesen.

## Stand 0.11 — Pack-Himmel und Welttiefe

Vier Varianten decken die Vanilla-Himmelsmeshes ab. WorldShaderGameMixin
schließt Composite/Final vor dem Vanilla-Hand-Depth-Clear ab. Details in
WORLD_SKY.md. Die restlichen Geometriefamilien, visuelle Tests in Nether
und Ende sowie die normale Shader-Auswahl bleiben offen.

## Stand 0.10 — Native DH-Programme und Mischregeln

Die beiden originalen DH-Programme von Complementary können auf den echten
DH-3.3.3-Meshes zeichnen. Diagnosemodus und technische Regeln stehen in
DISTANT_PACK.md. DistantWorldProbe liest beide DH-Tiefen bei 100 und 50 Prozent
Rendermaßstab zurück. Der Wasserübergang bleibt in der Sichtprüfung erkennbar.
Die Darstellung anderer Geometriefamilien und die öffentliche Pack-Auswahl
bleiben offen. Den neuesten Spiel- und Buildbericht zuerst prüfen.

## Stand 0.9 — Formeln, Schatten, Texturen und echte Pack-Ausführung

Lies zuerst NEXT_AI_PROMPT.md für den aktuellen vollständigen Fortsetzungsauftrag.
56 CPU-Tests bestehen. NativeWorldShadows zeichnet reale geladene Chunk-Puffer,
PackTexturePlan/NativeWorldTextures liefern echte PNG-/Noise-/Stage-Texturen.
Formelauswertung, Handlicht und Biomwerte sind seit 0.7 hinzugekommen.
Der echte Complementary-Weltlauf verify-realpack-world-7.log besteht, einschließlich
GPU-assertierter Blickrichtung nach Korrektur des Tiefenraums. Frühere nichtschwarze
Bildprüfungen allein hatten den Wolkenfehler nicht erkannt.

Ursache: Reforgeds Matrix4fM liefert 0..1-Tiefe; ForwardDepthProjection übersetzt
sie vor Veröffentlichung korrekt in GL -1..1. Shaderbetrieb verwendet zusätzlich
Minecraft-Fernweite getRenderDistance()*4 statt der unendlichen Epsilon-Projektion.
Die native .5z+.5w-Konvertierung bleibt genau einmal. Testen und nicht entfernen.

Verbleibend: heller Übergang im Wasser im echten Packbild, shaderseitiges DH,
generische Pack-Geometrie für Himmel/Entitäten/Hand usw., Blend/Cull pro Attachment,
Hand-/Tiefenzeitpunkte, Dimensionwechsel und danach öffentliche Auswahl/Hotreload.
Kein vollständiger Renderer und keine universelle Kompatibilität. Aktuelle Befunde
stehen in WORLD_SHADOWS.md und dem 0.9-Prüfbericht; ältere Abschnitte sind Historie.

## Stand 0.6 — aktive Uniforms und echte Weltwerte

Weiterarbeit 0.7: echte Packformeln samt sitzungseigener Glättung, Hand-IDs aus
item.properties, komponentenabhängiges Blocklicht, Biome, Wetness und tatsächlicher
LightTexture-Darknesswert sind hinzugekommen. 45 Tests und verify-0.7-client.log
bestehen mit vier neuen EXPRESSION WORLD GPU PASS-Marken neben den bisherigen
Prüfungen in zwei Welten. Aktiver Complementary-Uniform-Audit: 2/0/2 fehlend,
jeweils Schattenmatrizen. Vollständiger Renderer bleibt offen. Nächster Schritt:
native Schattenkamera und Schattenzeichnung aller geladenen Chunk-Puffer.

UniformUsage liest aktive OfGlobals-Member aus optimiertem SPIR-V. Zuordnung über
Byteoffsets, unverändertes std140-Layout, unbekannte Pointerpfade konservativ.
NativeGraphicsProgram cached die aktive Liste. WorldUniforms validiert und schreibt
nur diese; unbenutzte unbekannte Deklarationen werden nicht künstlich bedient.

BuiltinWorldValues ergänzt 18 echte Provider für Minecraft 1.21.1. CameraCoordinates
zerlegt absolute Kamera-/Vorkamera-Koordinaten vor der float-Umwandlung mit floor.
Weitere Provider: Auge/Blick, Licht, Medium, Effekte, Wolkenhöhe, Mond/Sonne, Gamma,
wirklich gebundene Atlasgröße und OfFog. Details/Grenzen: WORLD_UNIFORMS.md.
36 CPU-Tests bestehen. GPU-Probes prüfen tatsächliche Terrainpixel in Luft,
Wasser an negativen Koordinaten und mit Nachtsicht/Blindheit/Darkness.

Erster Fehlversuch verify-0.6-client.log: Daten im MRT-Alpha wurden auf transparenten
Flächen vermischt. Diagnosepack verwendet jetzt Alpha=1 und gerade/ungerade Pixel
für zusätzliche Messwerte. verify-0.6-client-2.log besteht vollständig: zwei Welten,
drei neue Werte-Probes, beide Weltketten-/FINAL-/Scale-Prüfungen, sechs GPU-Gruppen
je viermal, keine ERROR/FATAL-Zeilen. 444.850/62.562 echte Normalenpixel bei 100%/50%.
Build und verifyBundledLwjgl ohne Build-Cache mit allen Tasks erneut bestanden.
Vollständige Nachweise: verification/0.6.0-dev.json.

Aktiv fehlen Complementary MEDIUM nun 17/14/12 Weltwerte für Oberwelt/Nether/Ende.
Die alte Zahl 59 zählte Deklarationen, vor neuen Providern waren in der Oberwelt
31 wirklich gelesen. Nächster Schritt sind verbleibende echte Provider, Glättung
und Pack-Ausdrücke, danach Schatten/Ressourcen/weitere Geometrie/DH-Packprogramme.
Keine Schatten-Identitätsmatrizen, erfundenen Material-IDs oder Null-Provider.
Float-cameraPosition benötigt weiterhin das separate Origin-Rebasing.
Der vollständige Renderer ist nicht fertig; normale Installation bietet keine
vollständige Shader-Auswahl. Ältere stabile Pakete unter dist erhalten.

## Stand 0.5 — echte Welt-Renderkette

WorldPackPlan/WorldShaderSession/WorldMatrices/WorldUniforms und zwei Client-Mixins
sind implementiert. Der Host umschließt MainPass und wird nur ausdrücklich vor
einem Weltstart installiert; Shader-Host bleibt standardmäßig deaktiviert. Der Smoke-Test nutzt einen
eigenen nativen Welt-Diagnosepack, NICHT vollständiges Complementary. Terrain nutzt
dieselben echten AreaBuffers samt direkten/indirekten Draws. Vanilla und bisheriger
DH-Composite gehen ins Szenenbild, FINAL ins echte Hauptziel vor dem GUI.

verify-0.5-client.log besteht: zwei Welten, Resize, Nacht/Regen, 44->20-Byte-
Rückwechsel, Shader-Host bei 1280x720/100% und 480x270/50%. 31.347 bzw. 4.409 echte
Einheitsnormalenpixel sowie FINAL-Kontrollpixel im echten Ausgabebild belegt.
Sechs bisherige GPU-Gruppen je viermal bestanden; keine ERROR/FATAL-Einträge.
Eine anfangs nicht endliche Minecraft-Projektion führt zu einem protokollierten
normalen Frame statt zu NaN-Shaderhistorie oder Absturz. 28 CPU-Tests bestehen.

Korrekturen: of_TexShrink nur für Sodium; DH-Compat respektiert MainPass statt
DefaultMainPass/Swapchain zu erzwingen; RenderScale nutzt logische Viewports.
RenderPass/Framebuffer/Image-Freigaben bleiben in der vorhandenen verzögerten
GPU-Verwaltung. Keine Warteschleifen/Readbacks im Produktions-Shaderpfad; der
Frame-Observer und die Rückleseassertionen werden nur im Smoke-Test verwendet.

Damals gemeldet: 59 unbediente Complementary-Deklarationen je Dimension,
inklusive echter Provider und benutzerdefinierter Uniform-Ausdrücke. Konkrete Liste
in docs/verification/0.5-complementary-requirements.json. Shader-Ressourcen,
Schattenkamera/-geometrie, weitere Pack-Geometriefamilien und DH-Pack-Terrain/-Wasser
folgen. Der bisherige DH-Composite ist noch keine DH-Shaderprogramm-Anbindung.
Hand-Reihenfolge bleibt Vanilla; depthtex1/2 derzeit beide vor Transparenz.
Weitere Grenzen und Ablauf: docs/WORLD_SHADER_HOST.md.

Der Nutzer verlangt Weiterarbeit und einen Fortsetzungs-Prompt vor dem Ende der
Arbeit. docs/NEXT_AI_PROMPT.md ist dafür bereits geschrieben und zu aktualisieren.

Die folgenden Abschnitte halten die älteren Stände 0.4/0.3 fest.

## Aktuell 0.4 — Terrain

ShaderTerrainVertex/CustomVertexFormat.SHADER_TERRAIN: 44 Byte, echte Normalen und
Tangenten aus Quad-Ecken/UVs, 32-Bit-Packmaterial, Fluidkennung, Mittelpunkte,
Emission und zwei Farben mit getrennten AO-/Flächenschattierungsdaten. Nicht die
Sodium-Dekodierung verwenden. dev.vitrail.glsl.VulkanTerrainVertex hat explizite
Locations; alle vier oldLighting/separateAo-Regeln sind implementiert/geprüft.
PackProgramSource.prepareTerrain liest die tatsächlichen Pack-Einstellungen.
ShaderBlockMaterials erzeugt eine unveränderliche Registry-/Tag-Tabelle.

NativeGraphicsProgram teilt Descriptor-/UBO-Eigentum; NativeFullscreenPass behält
seine API, NativeTerrainPass zeichnet opak indiziert mit 12-Byte-Chunk-Push-Constant.
TerrainBufferBuilder und echte Block-/Fluid-Bauer schreiben das Format. Der
Standardmodus bleibt 20 Byte; keine Shaderauswahl aktivieren oder behaupten.
PipelineManager.configureShaderTerrain(snapshot)/restoreDefaultTerrain verlangen
eine entladene Welt. Smoke nutzt 44 Byte in Welt 1 und 20 in Welt 2.

Neue Fehlerbefunde und Korrekturen:
- DrawBuffers' statischer Stride verursachte im Rückwechsel fehlende Segmente.
  Nun Stride/Bias je Bereich, UploadBuffer trägt den Stride, AreaBuffer prüft
  Ausrichtung immer. verify-terrain-world-client.log ist der Fehlerlauf;
  verify-terrain-world-fixed.log besteht vollständig ohne ERROR/FATAL.
- TaskDispatcher.fixedBuffers wurde beim Stop geschlossen, aber wiederverwendet.
  Nun werden synchroner Pack und Worker bei createThreads neu erzeugt.
- .gitignore build/ schloss 28 echte Renderer-Quelldateien aus. /build/ korrigiert,
  alle 28 Dateien mitversionieren! Alte source.zip-Archive waren unvollständig;
  neue Quelle baut separat erfolgreich: verify-0.4-source-uncached.log, alle Tasks
  ohne Gradle-Build-Cache ausgeführt. build/source-probe.json enthält Input-Hashes.

23 Tests bestehen. Letzter vollständiger Lauf: verify-0.4-final-client.log, ohne
ERROR/FATAL, vier bestandene Gruppen und zwei REAL-TERRAIN-Stridenachweise.
Große Integer-Material-IDs dürfen nicht über float zurück nach int laufen. Die
GPU-Prüfung kontrolliert deshalb das untere Byte von 33554559. TerrainGpuCheck liest alle Pixel aus vier HDR-Zielen unter
vier Lichtregeln zurück, prüft reale Registry-Mappings und erstellt 36 tatsächliche
Complementary-Terrain/Wasser/Shadow-Pipelines. Reale Chunks laufen zum Layouttest
durch die normale Terrainpipeline: weiterhin KEINE vollständige Complementary-
Weltgrafik. Details: docs/TERRAIN_FORMAT.md.

Nächster funktionaler Schritt: native Terrainprogramme an WorldRenderer.renderSectionLayer
und NativeShaderChain anbinden, tatsächliche Kamera-/Welt-/Texturuniforms und
Schattenkamera/-geometrie liefern. Danach übrige Geometriefamilien und DH-Packausgabe,
bevor Shader-Auswahl/Hotreload freigegeben werden. Originalzip niemals beilegen.

Die folgenden Abschnitte dokumentieren die älteren Schritte 0.2/0.3.

Baseline `6699fe6` und `dist/0.1`-Artefakte erhalten. Renderer-Import: `783f068`.
Pin und Credits: `AGENTS.md`, `NOTICE`, `docs/upstream`.

## Verifizierte Schritte

- Native DH-Anbindung in zwei Welten mit Resize und Cleanup geprüft.
- Mehrere Vulkan-Farbausgaben, Tiefenpass, RGBA16F, Uniformlayout, Texturlesen
  und Deskriptorwechsel anhand GPU-Rücklesen geprüft. Diagnosecode nur in `src/smoke`.
- Complementary r5.9.3 MEDIUM: 96 Programmpaare in drei Dimensionen kompiliert.
  Original-ZIP bleibt extern. Dies ist noch keine vollständige Welt-Grafik.
- `Std140LayoutTest` vergleicht Layout-Offsets mit unabhängig erzeugtem SPIR-V.
- DH-Abschaltrennen durch echte SQLite-Query belegt; gezieltes Statement-Cancel
  plus begrenztes Worker-Drain. Kein bloßes Ausblenden von SQL-Fehlern. Die erste
  Variante nur mit Drain scheiterte; aktueller Spieltest in `run-native-pass-cancel.log`.

## Aktueller Code

`de.stranger.vsdh.shader`: CPU-Compiler, Bindings aus optimiertem SPIR-V,
std140 und native Vollbildpipeline. `dev.vitrail` enthält portable Parser/
Übersetzung, nicht den Minecraft-26.x-Renderer. Nicht unterstützte IRIS_FEATURE-
Symbole wurden entfernt; Tiefenkonvention ist Reforged vorwärts 0..1.
`net.vulkanmod.vulkan.framebuffer` und `GraphicsPipeline` unterstützen mehrere
Farbausgaben. `VulkanImage` kennt die nötigen unkomprimierten Formatgrößen.

## Noch erforderlich

1. Ganzen Welt-Rendergraph verdrahten: colortex-Hälften/History, Tiefenkopien,
   begin/prepare/deferred/composite/final in richtiger Reihenfolge.
2. Terrainformat für Block-IDs, Normalen/Tangenten und Quad-Mittelpunkte;
   Schatten-Kamera/-Geometrie; Himmel/Entitäten/Partikel/Hand/Transparenz.
3. DH-Terrain/Wasser durch die Pack-Programme und gemeinsame Tiefenführung.
4. Paket-/Profilwahl, sicherer Ressourcenwechsel und Fehlerrückmeldung.
5. Erst danach vollständiges Complementary im Spiel bewerten und weitere
   Packs, GPUs, Treiber und DH-Adapter freigeben.

Keinen einfachen Bildschirmfilter als vollständigen Complementary-Support
bezeichnen. Keine pauschale Freigabe aller Shaderpacks oder DH-Versionen.
Bauen: `tools/fetch-dependencies.ps1`, `gradlew.bat build verifyBundledLwjgl`.
Private Quellen/Decompiles und Shaderpack-ZIPs nie in JAR/Quellpaket übernehmen.

Abschließender Lauf: `verify-final-client.log`, alle vier GPU-Gruppen bestanden,
keine ERROR/FATAL-Einträge, BUILD SUCCESSFUL. Paket-/Testdaten in
`docs/verification/0.2.0-dev.json`.

## Fortsetzung 09.11.2026 — 0.3

NativeShaderChain/NativeColorTargets/NativeWorldDepth sind hinzugefügt. Reale
Pack-Reihenfolge, Größen und Mipmaps werden über ShaderChainPlan vorbereitet.
Die Kette ist mit eigener Testgeometrie über mehrere Frames auf der GPU geprüft.
Depth-Sentinels für D32, D24S8 und D32S8 sowie Stencil-Kopien sind enthalten.
RenderGraphAudit bereitet alle drei Complementary-Dimensionen ohne GPU vor.

Neue Fehlerbefunde und Korrekturen:
- Mipmap-Schalter im ersten Diagnosepack waren inline geschrieben und wurden
  nicht gelesen. Mehrzeilige Direktiven plus Schachbrett-/Mittelwerttest beheben
  den Testfehler. JUnit kontrolliert die tatsächlich angeforderten Mipmaps.
- Die echte Complementary-Kette deckte einen Indexfehler in Reforged Pipeline auf:
  Descriptor-Bindungsnummern wurden als Array-Positionen verwendet. Kompaktes
  Layout-Array, explizite Bindungsnummern und sortierte dynamische UBOs sind korrigiert.
  Ein wegoptimierter unaufgerufener Shadow-Sampler erzeugt eine echte Bindungslücke
  im Regressionstest.
- Rendern verwendet Mipmap-Stufe 0; die übrigen Stufen erhalten auch bei abweichendem
  RenderPass-Endlayout eine konsistente Zustandsverfolgung.

API und Aussagegrenzen: docs/RENDER_GRAPH.md. Vollständige Weltgeometrie/-Uniforms,
Material-IDs, Schattengeometrie, weitere Geometriefamilien und DH-Pack-Ausgabe sind
weiterhin erforderlich. Keine sichtbare Shader-Aktivierung vortäuschen.
Die obige ältere Aufgabenliste zu colortex/History/Tiefenkopien ist damit teilweise
umgesetzt; die Verdrahtung in die tatsächliche Welt-Pipeline bleibt offen.

## Verifizierter Abschluss dieses Entwicklungsschritts

Aktuell 0.3.0-dev. `verify-0.3-client-fixed.log`: zwei Welten, fünf unterschiedliche
Prüfgruppen je viermal bestanden, keine ERROR/FATAL-Zeilen, BUILD SUCCESSFUL.
Neun echte Complementary-Pipelines werden mit vorgezogener Vk-Pipeline-Erstellung
geprüft; die gesamte echte Welt wird weiter nicht durch das Pack gezeichnet.
16 Tests bestanden. Bericht: docs/verification/0.3.0-dev.json.

Konkrete nächste Einstiegspunkte: PipelineManager.TERRAIN_VERTEX_FORMAT und
getTerrainShader; WorldRenderer.renderSectionLayer; CustomVertexFormat und
VertexBuilder.CompressedVertexBuilder; TerrainBufferBuilder.setBlockAttributes
ist in der Referenz leer. Ein Shader-Terrainformat muss Material- und Normaldaten
wirklich liefern. VertexInputs.TERRAIN aus Vitrail dekodiert Sodium, nicht Reforged.
Nicht ungeprüft wiederverwenden. Auch ClipSpace.toLegacyDepth stammt aus dem
26.x-Reversed-Z-Kontext und darf vor Anpassung nicht in die 1.21.1-Weltmatrix-
Anbindung übernommen werden. Die bislang aktive Vollbildkette benötigt es nicht.

Keine erneute Addon/Fork-Frage; der Nutzer will Weiterarbeit. Alte Artefakte behalten,
keinen vollständigen Shader-Modus behaupten oder einen bloßen Filter aktivieren.

## Fortsetzung 11.11.2026 nachmittags

Siehe `docs/MIGRATION_STATUS.md` (oberster Abschnitt). Kurz: DH-Wolken und andere
DH-Generic-Objekte werden gezeichnet, Pack-Himmel hat einen geschlossenen Horizont,
`near` folgt der Projektion, Laufzeitfehler beim Zeichnen beenden nur noch das
Pack-Rendering. Leistung: ohne Shaderpack 35–50 % schneller als Iris+Sodium (720p),
mit Shaderpack und DH 7–20 % langsamer als Iris. Eine frühere Testserie lief wegen
einer persistierten DH-Einstellung ohne LODs und ist ungültig (Details dort).
