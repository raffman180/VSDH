# VSDH — Prüfstand 0.26.0-dev, 11.11.2026

## Stand 14.11.2026 nachts (0.27.0-beta, „Wasser bewegt sich mit“ nach Shader-Wechsel in der Welt; Fable, NUR Code + Logs)

- Meldung: Monkeytalk-Pack, neuester Build, Jurgens 1.0.1 → Eclipse Unstable IN der Welt, Wasser „bewegt sich noch
  immer mit“. Kein Spieltest (untersagt). KEINE Codeänderung: es wurde kein Zustand gefunden, der den Wechsel
  überlebt, und ein Fix auf Verdacht wäre nicht prüfbar gewesen.
- Geprüft, jeweils gleich wie beim Weltbeitritt: `WorldShaderSession.replaceInWorld` baut Sitzung, `WorldUniforms`
  (Kamera, Vorframe-Matrizen, `frameTimeCounter`, `frameCounter`, Formeln, `written`/PER_DRAW), `WorldMatrices`,
  Kette, SSBOs, Custom-Images, Schatten, DH-Tiefen und alle Programme neu; nichts davon ist statisch. Builder und
  synchrone Builder werden mit der neuen Materialtabelle neu erzeugt (`TaskDispatcher.createThreads`), alle Bereiche
  und `DrawBuffers` (Stride/Bias je Bereich) entstehen mit dem neuen `SectionGrid` neu, Flüssigkeitsquads tragen
  Material/`fluid`/`at_midBlock` aus dem neuen Build. DH-Zeichnungen (`VulkanRenderEngine.packFrame`, Versätze) sind
  je Frame. `vsdh.tightSpriteUpload` kann nur Texel eines Animationsframes betreffen, nie die Lage zur Kamera.
- Unterschiede zum Weltbeitritt, die bleiben: (1) Vanillas `LevelRenderer.allChanged()` läuft nicht (nur
  `WorldRenderer.allChanged`), andere Mods mit Hook darauf erfahren nichts (Iris ruft die Vanilla-Methode);
  (2) DHs LOD-Puffer bleiben (packunabhängig); (3) `WorldRenderer.setLevel` setzt Kamera-Merker zurück, `allChanged`
  positioniert das Gitter stattdessen direkt neu.
- Logs des Nutzers (`2026-11-14-4..7`): Eclipse wurde mit abgeschalteten Farb-Schleifen NIE frisch gestartet, nur
  per Wechsel erreicht (22:09). Die frischen Eclipse-Starts (20:22, 21:52) liefen noch mit Schleifen. Der Bezug zum
  Wechsel ist damit nicht belegt.
- Verdächtige im Pack-Pfad (alle kameragebunden, unabhängig vom Wechsel): Eclipse `lib/specular.glsl:2196-2216`
  (Wasserspiegelung/-brechung aus dem Vorframe `colortex5` mit `cameraPosition - previousCameraPosition` und
  `gbufferPrevious*`); Eclipse Profil HIGH `WATER_INTERACTION 2` (`dimensions/prepare*.csh`, SSBO
  `previousCameraPositionWave2`/`water_move_compensationSSBO`, `waveSim`-Bilder, Fenster folgt der Kamera);
  Jurgens `rippleImgA/B`, `waterLvlImg`, `trailImg` (gleiches Prinzip); LPV `cameraPosition - floor(previousCameraPosition)`.
- Code-Review: ja. Kompilieren, 140 Unit-Tests, `verifyDhLinks`: grün (unveränderter Baum). Im Spiel: NICHT geprüft.
- Nächster Schritt (Nutzer, je 1 Minute): Eclipse frisch starten und ohne Wechsel beitreten; nach einem Wechsel
  F3+A, dann Fenstergröße ändern; Eclipse-Option `WATER_INTERACTION` auf 0; `-Dvsdh.liveShaderSwitch=false`;
  `-Dvsdh.uniformDump=gbuffers_water` loggt `cameraPosition`/`previousCameraPosition`/`frameTimeCounter`.

## Stand 14.11.2026 nachts (0.27.0-beta, Tick-Frames und Uniform-Wege; Fable, NUR Code + vorhandene JFR)

- Kein Spieltest. Auswertung von `mt-turn1`/`mt-profile1` (128-Mod-Pack): lange Frames folgen dem 50-ms-Tick; im Tick
  liegen `getBiome` (Vanilla-Partikel, Detailed Map), Sprite-Uploads und Mod-Code.
- Neu, ergebnisgleich, je mit Schalter: `BiomeFiddleCache` (`-Dvsdh.biomeFiddleCache=false`), nur der gelesene
  Ausschnitt eines Sprite-Blatts wird gestagt (`SubImageRange`, `-Dvsdh.tightSpriteUpload=false`), Uniform-Lieferant je
  Member einmal bestimmt (`UniformRoutes`, `-Dvsdh.uniformRoutes=false`), `Std140Layout` hält den Typ beim Member,
  Framebuffer-Suche ohne Lambda.
- Code-Review: ja. Kompilieren, 138 Unit-Tests, `verifyDhLinks`: grün. Im Spiel: NICHT geprüft, kein FPS-Anspruch.
- Offen: Flugmessung in Paaren mit den drei Schaltern; Bildvergleich animierter Texturen (Wasser, Lava, Feuer,
  Mod-Sprites) mit `tightSpriteUpload` an/aus; Pack-Uniforms mit `zzUniformProbe` und `uniformRoutes` an/aus.
  Details: `docs/PERFORMANCE.md` (Ende).

## Stand 14.11.2026 nachts (0.27.0-beta, Flackern in der Ferne nach den Feedback-Schleifen; Fable)

- Meldung des Nutzers (eigenes Pack, Eclipse Unstable, DH 3.3.3, 1440p): „Alles weiter weg flackert etwas", seit dem
  Umbau vom Abend. Nur Code-Analyse, KEIN Spieltest (vom Nutzer untersagt).
- Wahrscheinlichste Ursache: die Tiefen-Feedback-Schleife auf der DH-Tiefe. Eclipses `dh_water`
  (`dimensions/DH_translucent.fsh`, `rayTrace`) marschiert für die Spiegelung mit zeitlich wechselndem Versatz durch
  `dhDepthTex` an FREMDEN Pixeln, während derselbe Pass (ein Render-Pass, alle DH-Wasser-Draws, Tiefenschreiben an)
  diese Tiefe schreibt. Ohne Barriere zwischen Schreiben und späterem Lesen ist das in Vulkan auch im
  Feedback-Layout ein Datenrennen; `RenderPass` hat keine Selbstabhängigkeit, im Pass wird keine Barriere
  aufgezeichnet. Vor dem Umbau las `dh_water` die vollständige, unveränderliche opake Kopie.
- Behoben: DH-Programme lesen `dhDepthTex`/`dhDepthTex0` wieder aus der opaken Kopie (`dhDepthTex1`-Bild). Die
  Live-Tiefe gibt es nur noch mit `-Dvsdh.dhDepthFeedbackLoop=true` (`FeedbackLoops.DISTANT_DEPTH_REQUESTED`).
  Logzeile: `DH depth read from the opaque copy (dhDepthTex in [dh_water])`.
- Unverändert Standard: Farb-Feedback-Schleifen (`-Dvsdh.feedbackLoops=false` schaltet sie und die DH-Tiefe ab) und
  die Schattenliste (`-Dvsdh.shadowSectionList=false|verify`). Farb-Schleifen sind nach dem Buchstaben der
  Spezifikation dasselbe Rennen, wo sich Fragmente eines Passes überdecken; Eclipse liest dort nur den eigenen Pixel
  (`colortex11`: Ergebnis unabhängig von der Reihenfolge; `colortex7`: nur im DH-Übergangsring, Wasser ohne opaken
  Grund dahinter). Bleibt Flackern, zuerst `-Dvsdh.feedbackLoops=false` prüfen, dann `-Dvsdh.shadowSectionList=false`.
- Schattenliste: kein Fehler gefunden (gleiche Bedingungen und Reihenfolge wie der alte Durchlauf, alle Schreiber
  auf dem Render-Thread). Gehärtet: `RenderSection` meldet `ShadowSectionList.changed()` jetzt NACH dem Schreiben
  von Ursprung/`completelyEmpty`.
- Kompiliert, 133 Unit-Tests (neu `FeedbackLoopsTest.theDistantDepthLoopIsOptIn`), `verifyDhLinks` PASS
  (3.2.0-b, 3.3.0–3.3.3). Im Spiel NICHT geprüft: ob das Flackern weg ist.

## Stand 14.11.2026 abends (0.27.0-beta, Feedback-Schleifen und Schattenliste; Fable)

- Neu: Anhang-Feedback für Pack-Programme (`FeedbackLoops`, `VK_EXT_attachment_feedback_loop_layout`), DH-Programme
  lesen die laufende DH-Tiefe; Rückfall auf Kopien ohne Erweiterung oder mit `-Dvsdh.feedbackLoops=false`.
  `ShadowSectionList` für den Schattenpass (`-Dvsdh.shadowSectionList=false|verify`). Zwei allokationsfreie
  Render-Thread-Stellen. Tooltip der Bildraten-Grenze (de/en). Details und Zahlen: `docs/PERFORMANCE.md` (Ende).
- Kompiliert, 129 Unit-Tests (neu `FeedbackLoopsTest`, `ShadowCullerTest.everyBoxInsideAContainedBoxIsVisible`),
  `verifyDhLinks` PASS (3.2.0-b, 3.3.0–3.3.3).
- Im Spiel: Eclipse 1440p GPU-Profil 2 + 2 Läufe (Wasser −0,6 bis −0,85 ms, 98–104 → 117–122 FPS in der festen
  Szene), Eclipse-Bild gegen Iris und gegen Kopien ohne sichtbaren Fehler (Aufnahme während eines fremden Spiels),
  Schattenliste 2000 Frames identisch zum alten Durchlauf.
- NICHT geprüft (Nutzer spielte, Testclients gestoppt): Bildvergleiche Complementary Reimagined, Photon, BSL gegen
  Iris; GPU-Profil eines zweiten Packs; Release-Matrix; Vulkan-Validation-Layer (nicht installiert); Geräte ohne die
  Erweiterung; der Composite-Gewinn bei Eclipse ist unerklärt und zu wiederholen. Fortsetzen mit dem Abschnitt
  „Offene Läufe“ in `docs/NEXT_AI_PROMPT.md`.

## Stand 13.11.2026 vormittags (0.26, Speicher-Churn, `dist/0.26.0-dev-perf`)

- Neu: Indexschleifen in der Weltgenerierung (`MaterialRuleListMixin`, `SurfaceSequenceRuleMixin`), Pause der
  DH-3.3.x-Chunk-Update-Schleife im Leerlauf (`MixinChunkUpdateQueueBackoff`, nur wenn DH `runQueueingLoop`
  hat), zwei allokationsfreie Render-Thread-Stellen. `tools/RemapDh330.java` benennt jetzt auch
  Mixin-Zielangaben (`Lowner;name(desc)`) um; ohne das scheiterte die DH-3.3.0-JAR beim Start.
- Messung (Details `docs/PERFORMANCE.md`): weniger Allokation und GC-Läufe, aber kein messbarer FPS-/Hänger-
  Gewinn in 7+7 Flügen. Java-Optionen geprüft, keine empfohlen.
- Prüfung 09:53–10:18 mit den JARs f5e88bf7…/f09de1bf…: Unit-Tests 96, Matrix 6/6 mit 0 Fehlerzeilen
  (DH 3.3.0 nach der Remapper-Korrektur erneut), Smoke 3/3 ohne Fehlerzeile, ohne DH, Menütest, Weltwechsel.
- Nachmittags nur Messungen (kein Mod-Code geändert): Vollbild gegen Iris, lange Frames zugeordnet (G1-Remark mit
  Class Unloading, Treiber-Present 20–25 ms). Spieler-Empfehlung: `-XX:-ClassUnloadingWithConcurrentMark` und
  FPS-Grenze knapp unter der Bildwiederholrate (VSDH dann 0 Frames > 25 ms, Iris 2–3). GPU-Abstand
  `composite1`/`deferred1` ohne GPU-Profiler offen; vom Nutzer am 13.11. abgeschlossen.

- Nachtrag 13.11. abends (nur kompiliert, nicht im Spiel getestet): GL-Emulation für Mod-Texturen –
  `GlStateManager._texSubImage2D` und `_pixelStore` waren leere Overwrites (Raffman's Detailed Map lud so keine
  Kacheln), `glTexSubImage2D` schreibt jetzt in die angegebene Mip-Stufe, `AbstractTexture.setFilter` behält ein
  ausdrücklich gesetztes CLAMP_TO_EDGE. Offen: welches Menü der Nutzer meint; Jurgens-Shader CINEMATIC scheitert an
  der festen 512-MiB-Schattengrenze (`NativeWorldShadows`); ImmediatelyFast lief mit dem Vulkan-Renderer ohne Warnung.

- Nachtrag 13.11. spät (kompiliert, 96 Unit-Tests, nicht im Spiel): GUI-Zustandsgrenze für Gegenstände/Figuren schließt
  an `GuiGraphics.flush` und wird je Frame verworfen (`GuiDeferredDrawState`), damit ImmediatelyFasts eigene
  BufferSource sie nicht mehr übergeht (Esc-Menü zeigte nur „Zurück zum Spiel“). Weltfiguren zählen während
  `GameRenderer.renderLevel` nicht als GUI-Vorschau. JARs: `dist/0.26.0-dev-compat`.

- Nachtrag 13.11. nachts (kompiliert, 107 Unit-Tests, `verifyDhLinks` PASS für 3.2.0-b/3.3.0–3.3.3, NICHT im Spiel):
  Eine JAR für alle DH-Versionen (verschachtelter Coremod `src/dh330` passt VSDH nur bei DH 3.3.0 beim Laden an,
  `DhSupport` schaltet den Adapter bei Unstimmigkeit ab; `jarDh330`/`RemapDh330` entfallen). GL-Emulation: 7 Aufrufe
  nachgerüstet, ~500 nicht emulierte melden sich einmal (`GlEmulationLog.unimplemented`). Hinweis beim Start für nicht
  voll unterstützte Mods (`UnsupportedModNotice`, nichts gesperrt). Release-Matrix mit der Einzel-JAR steht aus.

- Nachtrag 14.11. (kompiliert, 110 Unit-Tests, nicht im Spiel): Schattenbudget richtet sich nach dem Grafikspeicher
  (`NativeWorldShadows.shadowBudgetBytes`: 1/6 des Geräte-Heaps, 512 MiB bis 3 GiB; vorher fest 512 MiB, Jurgens
  CINEMATIC brauchte 1 GiB). Hinweis: Minecrafts „Chunk-Aktualisierung“ (prioritizeChunkUpdates) hat im VulkanMod-
  Renderer keine Wirkung; Spike-Zeilen „build“ sind Worker-Zeit, nicht Render-Thread. Die Hänger im Monkeytalk-Pack
  (25–110 ms ohne zugeordnete Ursache) sind noch nicht profiliert.

- Nachtrag 14.11. nachmittags (kompiliert, 111 Unit-Tests, verifyDhLinks PASS, nicht im Spiel): Review-Funde behoben –
  `glTexSubImage2D` überspringt nicht umrechenbare Uploads mit Warnung statt Absturz, Region auch für Stufe 0 geprüft;
  Weltpass als Zähler (verschachteltes renderLevel); `Dh330Mapping` ignoriert Strings, die keine Deskriptoren sind;
  Schattenbudget höchstens 3/4 des freien VMA-Heaps (mind. 512 MiB); keine Warnung mehr für MRT-glDrawBuffers in Reihenfolge.
  Sodium bleibt inkompatibel (OpenGL-4.5-Renderer, GLFW_NO_API-Fenster, Polyform-Shield-Lizenz); Iris-Koexistenz gestrichen.

- Nachtrag 14.11. abends (kompiliert, 111 Unit-Tests): zwei Allokationen pro Aufruf entfernt (Warntext, glDrawBuffers(int[])),
  GUI-transformierte Gegenstände im Weltpass öffnen keine GUI-Zustandsgrenze mehr (`ItemRendererM`).
  Fable-Vorschlagsliste (Rangfolge): Pack-JAR tauschen und testen; Spark-/JVM-A/B; Schattenauflösung bei Budgetüberschreitung
  halbieren statt Pack abschalten; Einzel-JAR-Matrix; Statusblock auf der Shader-Seite; GL-Lücken je Mod (Aufrufer);
  Smoke-Tests gängiger GL-Mods (Flywheel/Veil brauchen GL 4.x); Jurgens in Audit/Smoke; NeoForge-Pin weiten; DH-Adapter-aus
  sichtbar machen; Kamera-Frame beim Welteintritt; Build-ID im Log, eine dist pro Release, englische README.

- Nachtrag 14.11. abends (kompiliert, 114 Unit-Tests, nicht im Spiel): Passt die Schattenkarte nicht ins GPU-Budget, bereitet
  `ShaderPackStartup` das Pack mit dem nächstkleineren eigenen Wert von `shadowMapResolution` neu vor (Werteliste des Packs,
  `ShadowResolutionFallback`), statt es beim Welteintritt abzuschalten; Toast „Schatten verkleinert“, gespeicherte Optionen
  bleiben unverändert. Schätzung ohne Gerät: `NativeWorldShadows.estimateBytes`. Prüfung beim Welteintritt (freier Speicher) bleibt.

- Nachtrag 14.11. abends (kompiliert, 114 Unit-Tests, nicht im Spiel): Borderless Window macht aus dem exklusiven Vollbild ein
  gleich großes randloses Fenster, ohne Größenereignis; die Swapchain blieb auf dem Vollbild-Pfad (Vulkan statt DXGI-Flip).
  `SwapChain.windowModeChanged()` + Prüfung in `Renderer.beginFrame` bauen sie dann neu. Solas und Eclipse Unstable in den
  shaderpacks-Ordner von Monkeytalk kopiert (nicht ausgeliefert, nur lokal).
- Nachtrag 14.11. spät (kompiliert, 140 Unit-Tests, nicht im Spiel): Minecrafts Vollbild (F11, options.txt) ist jetzt standardmäßig ein
  randloses Fenster über dem Monitor (DXGI-Flip-Pfad); exklusives Vollbild nur noch über Fenstermodus → „Exklusives Vollbild“
  (`exclusiveFullscreen` in vulkanmod_settings.json). Grund: Moduswechsel bei Start und Alt-Tab ließ den Ton kurz das Gerät wechseln.
- Nachtrag 14.11. spät (kompiliert, 140 Unit-Tests, verifyDhLinks PASS, Walk-Test im Testclient bestätigt): Nie gesehene Chunk-Abschnitte werden vorab gebaut (`SectionGraph.prebuildHidden`); vorher fehlte nach Drehen/Laufen das nahe Gelände ~1 s und das flache DH-Wasser schien durch („Wasser und Schatten bewegen sich mit“).
- Nachtrag 15.11. früh (kompiliert, 144 Unit-Tests, verifyDhLinks PASS): DH-Projektion aus der Kamera-Projektion (Bobbing/FOV, `DistantProjection`), offene unbebaute Abschnitte im Sichtgraph und `ChunkCatchUp`. Testclient: Lücke nach Pack-Wechsel deutlich kürzer; DH-Fix im Spiel nicht eindeutig messbar.
- Nachtrag 15.11. 00:40 (kompiliert, 145 Unit-Tests, verifyDhLinks PASS, im Client NICHT gestartet): View Bobbing wird bei aktivem Pack in die Modelview statt in die Projektion gelegt (`CameraBobbing`); Ursache für mitrutschendes Wasser/Schatten beim Gehen.
- Nachtrag 15.11. nachmittags (Fable; kompiliert, 210 Unit-Tests, verifyDhLinks PASS, im Client NICHT gestartet): Pack-Vorbereitung parallel (`PreparePool`, `-Dvsdh.prepareThreads`) und mit Plattenspeicher `config/vsdh-shadercache` (`SpirvCache`, `TranslationCache`; `-Dvsdh.spirvCache=false`, `-Dvsdh.translationCache=false`). Headless gemessen: Jurgens 22.0 -> 5.0 -> 1.0-2.0 s, Eclipse 21.1 -> 7.2 -> 1.9-2.9 s, Solas 18.2 -> 4.8 -> 0.5-1.7 s, Ergebnis-Digest gleich. Opt-in `-Dvsdh.keepTerrainOnSwitch=true` (ungetestet). Tabelle und offene Spielprüfung: `docs/PERFORMANCE.md` („Pack preparation time“), `docs/NEXT_AI_PROMPT.md`.
- Nachtrag 15.11. 01:00 (kompiliert, 147 Unit-Tests, verifyDhLinks PASS, im Client NICHT gestartet): Vorbereitete Packs werden zwischengespeichert (`PackPreparationKey`, LRU 3), Vorwärmen beim Öffnen der Shader-Seite.
- Nachtrag 15.11. 00:45 (kompiliert, Unit-Tests, verifyDhLinks; im Client nicht gestartet): Randloses Vollbild auf Wunsch des Nutzers entfernt; Vollbild ist wieder immer exklusiv.

- Nachtrag 14.11. abends (kompiliert, 118 Unit-Tests, NICHT im Spiel): Shader-Wechsel in der Welt (`WorldShaderSession.replaceInWorld`):
  Chunk-Builder stoppen, Terrain-Vertexformat unter `PipelineManager.runTerrainSwitchInWorld` tauschen, alte Session freigeben,
  neue für die aktuelle Dimension installieren, `WorldRenderer.allChanged` baut alles neu. `-Dvsdh.liveShaderSwitch=false`
  stellt das alte Verhalten (erst nach Verlassen) her. Fehlt die eingestellte Pack-Datei, nimmt `PackFileFallback` die
  neueste Version desselben Packs (gespeicherte Wahl bleibt), Toast „Andere Pack-Version verwendet“. Tooltips angepasst.

- Nachtrag 14.11. spät (Fable; kompiliert, 124 Unit-Tests, verifyDhLinks PASS, NICHT im Spiel): JFR des Monkeytalk-Packs:
  60 % der Render-Thread-Zeit ist Client-Tick, `BiomeManager.getBiome` allein 34 % (Subtle Effects, Raffman's Map, Vanilla).
  Neu: `BiomeCornerCache`/`BiomeManagerCornerMixin` merkt sich auf dem Render-Thread die Zellwahl je Position
  (`-Dvsdh.biomeCornerCache=false`). Fehler behoben: unbegrenzter View-Cache im Shader-Modus (`NativeShaderChain` geometry/scene
  erzeugten je Aufruf eine neue Liste), veraltete Schatten-Draw-Listen nach `allChanged`, Rollback beim Live-Wechsel, Schatten-
  Fallback beim Spielstart vor dem Gerät (`ShadowResolutionFallback.needsRecheck`). Weniger Allokation: PipelineState.hashCode,
  NativeBlend, Schatten-Samplernamen ohne Regex. Harness `vsdh.releaseQA.worldSwitch` erwartet noch „wirkt beim Verlassen“.

- Nachtrag 14.11. spät (kompiliert, 124 Unit-Tests, verifyDhLinks PASS, nicht im Spiel): Mod-ID des mitgelieferten Renderers
  `vulkanmod` → `vsdh_renderer` (`Initializer`, mods.toml, CORE_MOD_IDS); das echte VulkanMod ist als `incompatible`
  eingetragen. Keine der 127 anderen Mods im Monkeytalk-Pack nennt die ID `vulkanmod`. Testclient 026-packs prüft `vsdh_renderer`.

- 14.11.2026: Öffentliches Repository https://github.com/raffman180/VSDH (LGPL-3.0, Standardzweig `main` = Stand 258f0f3 von
  `codex/renderer-shaders`). Lokaler Zweig verfolgt `origin/main`. Noch keine CurseForge-Veröffentlichung; vorher Spieltests,
  Version/Name ohne „dev“ und NeoForge-Bereich prüfen.

- 14.11.2026 abends: Veröffentlichungs-Vorbereitung 0.27.0-beta (kompiliert, 124 Unit-Tests, verifyDhLinks PASS, NICHT im Spiel):
  Version `0.27.0-beta`, Name „VSDH“, Logo (`vsdh_logo.png`, Projekt-Icon `docs/curseforge/icon-400.png`), Links auf GitHub,
  `credits` in mods.toml, englische Projektbeschreibung `docs/curseforge/DESCRIPTION.md`, `CHANGELOG.md`, `dist/0.27.0-beta`
  mit SHA256SUMS. Frame-Diagnosezeilen nur noch mit `-Dvsdh.chunkDiag=true` (Testclients setzen es). Testskripte auf
  0.27.0-beta umgestellt. Vor dem Upload offen: Release-Matrix/Smoke/Menü-/Weltwechsel mit der Einzel-JAR, NeoForge-Bereich.

- 14.11. spät: Farb-Feedback-Loops standardmäßig aus (`-Dvsdh.feedbackLoops=true` schaltet sie ein): Im Monkeytalk-Pack „bewegte sich das Wasser komisch mit“ (Kamera), wahrscheinlich Lesen von colortex7/11 während überlappende Wasserfragmente schreiben. Nicht im Spiel bestätigt.

## Stand 13.11.2026 nachts (0.26, DH optional, Glätte im Flug)

- Neu: Seite **Pack-Optionen** (`PackOptionsView`, `ShaderSettingsPage.optionsPage`): alle Optionen, die
  die Seiten des Packs erreichen (`screen=`), mit Beschriftungen und Tooltips aus der Sprachdatei des
  Packs; Ein/Aus oder Werte aus der Liste. „Übernehmen“ speichert nur Abweichungen vom Profil in
  `shaderOptions` und lädt das Pack neu (Hauptmenü sofort, Welt beim Verlassen). Ein neu gewähltes Profil
  ersetzt Einzeloptionen (wie Iris). Die Liste entsteht beim Vorbereiten des Packs ohne zweites Lesen.
  Im Spiel: Complementary 305 Optionen, `SHADER_STYLE=4` gesetzt, neu geladen, Welt danach bestanden.
- Endprüfung 13.11. 01:16–01:38 mit den JARs 0a23c752…/4ae7368d… (damals `dist/0.26.0-dev-night`, inzwischen ersetzt): Unit-Tests
  95, Smoke 3/3 ohne Fehlerzeile, Release-Matrix 6/6 mit 0 Fehlerzeilen, ohne DH (Complementary),
  Menütest, Weltwechsel (Photon), Pack-Optionen mit BSL (359 Optionen, `SHADOW=false`, neu geladen, Welt mit DH).
- Korrigiert danach: Der Optionsindex las nur Dateien mit bekannten Endungen; Miniature deklariert seine
  Optionen in `shaders/shader.h` (13 statt 82 Optionen erkannt, Profilwerte dieser Optionen wurden still
  ignoriert, `shaderOptions` dafür als unbekannt abgelehnt). Jetzt liest `ShaderPackSource.options()` auch
  jede per `#include` eingebundene Datei, wie Iris. Pack-Audit aller 14 Packs: nur Miniature ändert sich
  (49 Optionen in 6 Gruppen). Im Spiel: Miniature `WATER_BRIGHTNESS=0.0` macht das Wasser sichtbar dunkler
  (Helligkeit 112 → 91), `shadowMapResolution=256` (const) neu geladen ohne Fehler (Kartengröße nicht gemessen).
- Prüfung 13.11. 01:59–02:22 mit den JARs 131e9473…/7c927d4b… (vor dem Zurücksetzen-Knopf): Unit-Tests 96 (neu `IncludedOptionIndexTest`), Smoke 3/3 ohne Fehlerzeile, Matrix 6/6
  mit 0 Fehlerzeilen, ohne DH, Menütest, Weltwechsel (Photon), Pack-Optionen (Miniature `WATER_BRIGHTNESS`).
- Neu: Knopf „Alle Pack-Optionen zurücksetzen“ (auf Profilwerte, wirksam mit Übernehmen). Geprüft 02:30–02:54
  mit den JARs 64e549e0…/97a85ac9…: Unit-Tests 96, Smoke 3/3 ohne Fehlerzeile,
  Matrix 6/6 mit 0 Fehlerzeilen, ohne DH, Menütest, Weltwechsel (Photon), Pack-Optionen + Zurücksetzen mit
  Miniature und BSL (jeweils neu geladen, danach Welt).
- Neu: Statuszeile auf der Shader-Seite (aktiv / wird vorbereitet / nach Verlassen der Welt), Profilnamen aus
  der Sprachdatei des Packs. **Endstand** 03:12–03:36 mit den JARs 8358cd0a…/89f0bde3… (= `dist/0.26.0-dev-night`):
  Unit-Tests 96, Smoke 3/3 ohne Fehlerzeile, Matrix 6/6 mit 0 Fehlerzeilen, ohne DH, Menütest, Weltwechsel,
  Pack-Optionen + Zurücksetzen (Miniature, BSL).

- Distant Horizons ist optional: Ohne DH starten Vulkan-Renderer, Standarddarstellung und Shaderpacks
  (DH-Hooks werden nicht geladen, DH-Schalter auf der Shader-Seite ist dann gesperrt mit Hinweis).
- Neue Flug-Messung (`run-vsdh-026-flight.ps1`, beide Clients): 2560×1440 Complementary, Median VSDH
  ≈ 208 FPS / 18 verpasste 165-Hz-Takte pro s gegen Iris ≈ 188 FPS / 33 pro s (6 bzw. 4 Läufe; Einzelläufe
  schwanken stark). Einzelheiten und Ursachen in `docs/PERFORMANCE.md`.
- Geändert: Geräte-lokale Puffer-Uploads (DH-LODs, VBOs) im Upload-Batch des Frames statt eigener
  Transfer-Übermittlung mit Fence; Render-Thread-Allokationen pro Frame stark verringert; Diagnose
  `-Dvsdh.spikeMs`, `-Dvsdh.gpuSpikes`, `bufUp` in Spike-Zeilen.
- Code geprüft: eigene Durchsicht. Kompiliert: ja. Unit-Tests: 95 bestanden. Im Spiel (00:24–00:45, JARs
  dac921de…/e5a14b1b… = `dist/0.26.0-dev-smooth`): Smoke 3/3 (eine OpenAL-Fehlerzeile von Minecraft),
  Release-Matrix 6/6 mit 0 Fehlerzeilen, ohne DH (Complementary), Menütest (Complementary → BSL → Aus →
  zurück), Weltwechseltest (Photon). AMD-iGPU nicht testbar: Monitor hängt an der NVIDIA-Karte.

## Stand 12.11.2026 abends (0.26, Bedienung für alle Spieler)

- Neu: Seite **Shader** in den Videoeinstellungen (`ShaderSettingsPage`): Shaderpack (Liste aus
  `shaderpacks`, „Aus“), Pack-Profil (aus `shaders.properties`, „Pack-Standard“), Schalter „Pack auch
  für Distant Horizons“, Knopf „Shaderpack-Ordner öffnen“. „Übernehmen“ speichert `config/vsdh.json`
  und lädt das Pack ohne Neustart (`ShaderPackStartup.request`): im Hauptmenü sofort, in einer Welt
  beim nächsten Verlassen. Profil/Optionen anderer Packs bleiben in `otherPacks`. Meldungen
  „wird vorbereitet“/„bereit“/„ausgeschaltet“/„fehlgeschlagen“; scheitert die Vorbereitung, bleibt die
  bisherige Darstellung. Eine unlesbare `vsdh.json` wird vor dem Speichern als `vsdh.json.unreadable`
  gesichert. Chat-Meldungen des Frame-Guards sind übersetzt (de/en). NeoForge meldet Iris, Oculus,
  Sodium und Embeddium mit klarer Begründung als unverträglich.
- Code geprüft: eigene Durchsicht. Kompiliert: ja. Unit-Tests: 95 bestanden. Im Spiel (RTX 4070,
  1280×720): Menütest Complementary → BSL → Aus → Complementary ohne Neustart, gespeicherte
  Konfiguration korrekt, danach Welt mit Shader und DH (`PACKAGED JAR WORLD PASS`); Weltwechsel-Test
  BSL: „Aus“ in der Welt, nach Verlassen Standarddarstellung; Bildschirmfoto der Seite geprüft.
  Smoke 3/3 und Release-Matrix 6/6 mit 0 Fehlerzeilen (Stand vor der letzten Sicherungs-Änderung);
  mit den endgültigen JARs (SHA-256 e68ec9ce…, dh330 e6d54aed…) erneut Menütest Complementary → Photon →
  Aus → Complementary (Profil MEDIUM gemerkt) und Release-Matrix 6/6 mit 0 Fehlerzeilen. Paket
  `dist/0.26.0-dev-menu` = diese JARs.
- Nicht getestet: echte Mausklicks durch einen Menschen (Test ruft dieselben Methoden wie die Knöpfe),
  ein Pack, dessen Vorbereitung scheitert, über die Seite; andere Sprachen als de/en.

## Stand 12.11.2026 nachts (0.26, Leistung ohne Bildverlust)

- 12.11. mittags: eigene DirectX-12-Bildausgabe im Fenster (`net.vulkanmod.vulkan.present.D3d12Presenter`,
  Standard auf NVIDIA unter Windows, `-Dvsdh.dxgiPresent=false` schaltet ab); im Vollbild bleibt die
  Vulkan-Swapchain (dort schneller), `SwapChain` wechselt bei F11. Fenster 1440p jetzt +5 bis +14 %
  vor Iris (alle fünf Mess-Packs). Code geprüft: eigene Durchsicht, alle COM-Funktionsnummern gegen
  SDK-10.0.28000-Header. Kompiliert: ja. Unit-Tests: 95 bestanden. Im Spiel: Smoke 3/3 (0 Fehlerzeilen),
  Release-Matrix 6/6, F11-hin-und-zurück plus zwei Größenänderungen ohne Fehler, Desktop-Bildschirmfoto
  gleich Spiel-Screenshot. Nur RTX 4070 gemessen. Paket `dist/0.26.0-dev-d3d12` (SHA-256 5d661992…,
  dh330 5558c30f…) = die geprüften JARs.
- Neu: Upload-Batch im selben Submit wie der Frame; Mip-Ketten eines Passes gebündelt; echte
  3D-Texturen für Pack-Volumen (`-Dvsdh.flatVolumes=true` = alter Atlas); `shadowtex1`-Kopie nur
  bei Bedarf; Profiler mit `GPU_GAP`, `<pass>/setup`, eindeutigen Stufenenden.
- Untersucht ohne Änderung: GPU-Pause zwischen Frames = Treiber-Präsentation; gleicher Shader ist
  unter Vulkan 10–20 % langsamer als unter OpenGL (Treiber-Codeerzeugung).
- Code geprüft: eigene Durchsicht der Änderungen. Kompiliert: ja. Unit-Tests: 95 bestanden
  (neu `NativeVolumeTest`). Im Spiel: Smoke 3/3, Release-Matrix 6/6, 14-Pack-Bildvergleich ohne
  Fehlerzeilen. Bench 1440p: Vollbild +9 bis +18 % gegenüber Iris, Fenster −0,7 bis +6 %
  (`docs/PERFORMANCE.md`). Paket `dist/0.26.0-dev-perf`: dieselben JARs liefen um 02:44 durch
  Smoke 3/3 (0 Fehlerzeilen) und Matrix 6/6.

## Stand 11.11.2026 spätabends (0.26, 1440p gegen Iris)

- Vollbild 2560×1440 (RTX 4070, DH 3.3.3): VSDH vor Iris in allen fünf Mess-Packs
  (+8 bis +16 %, Tabelle `docs/PERFORMANCE.md`). Fenster: 6–10 % zurück wegen der
  Present-Kopie des NVIDIA-Treibers (~1 ms GPU pro Frame, nur Vulkan).
- Neu: Tausch statt Rückkopie persistenter Ziele (nur bei ungeblendeten
  Vollbildschreibern), Tiefen-Schnappschüsse nur bei Bedarf.
- Prüfungen danach: Unit-Tests 93; Smoke 3/3 (zwei Proben an neue Semantik
  angepasst); Release-Matrix 6/6; 14-Pack-Vergleich ohne Fehlerzeilen, Mittelwerte
  gleich Iris.

## Stand 11.11.2026 abends (0.26, Iris-Abgleich)

Neu (Einzelheiten: `docs/NATIVE_PACK_FEATURES.md`, „Iris parity fixes“):
- **GL-Ausrichtung der Pack-Kette**: `gbufferProjection[1][1]` positiv wie unter
  Iris, Pack-Bilder mit Zeilen von unten, ein gespiegeltes Blit am Ende.
  Vorher kehrten Formeln wie `gbufferProjection[1][1] / 1.37` (AO in BSL/Solas/
  Insanity, Brechung in Complementary/RV, Tiefenschärfe in Mellow) das Vorzeichen
  um. Gemessen mit einem Uniform-Prüf-Pack: alle Werte gleich Iris außer `near`.
- **`vignette`/`underwaterOverlay`** werden beachtet; vorher lag die Vanilla-
  Vignette über jedem Pack (Ränder 5–10 % dunkler als unter Iris).
- **Iris-Merkmale** `HIGHER_SHADOWCOLOR`, `BLOCK_EMISSION_ATTRIBUTE`,
  `ENTITY_TRANSLUCENT`; `shadowcolorimgN`; Schattenprogramme lesen nicht
  geschriebene Schattenziele (Rethinking Voxels: interaktives Wasser läuft).
- **Terrainprogramme je Schicht** (`gbuffers_terrain_solid`/`_cutout`) und
  Ersatzkette bis `gbuffers_textured`/`gbuffers_basic`.
- **`indirect.<pass>`**: Compute-Dispatch mit Gruppenzahlen aus einem
  Speicherpuffer (`vkCmdDispatchIndirect`).

Prüfungen (RTX 4070, DH 3.3.3 wo nicht anders genannt):
- Code geprüft: Probe-Pack-Vergleich Wert für Wert gegen Iris; A/B-Läufe gegen die
  JAR vor der Umstellung (Snapshot) für jede Bildabweichung.
- Kompiliert: Haupt-JAR und DH-3.3.0-Variante; Unit-Tests 93 bestanden.
- Smoke 3/3 (Diagnose, Complementary, Dimensionen; 0 Assertion-Fehler). Zwei
  GPU-Proben lasen feste Zeilen im alten Top-down-Layout und wurden angepasst.
- Release-Matrix 6/6 (DH 3.2.0-b, 3.3.0-Variante, 3.3.1, 3.3.2, BSL und Bliss mit
  3.3.3; 2560×1440; 0 Fehlerzeilen). JARs in `dist/0.26.0-dev-compat/`
  (SHA-256 184ad5a1… Haupt-JAR, c491e1bd… DH-3.3.0-Variante; identisch mit den
  in der Matrix geprüften Dateien).
- Im Spiel gegen Iris: alle 14 Testpacks ohne Fehlerzeilen, sichtbar übereinstimmend.
  Rethinking Voxels HIGH mit interaktivem Wasser, `indirect`-Testpack (Iris und
  VSDH grün), Beacon-Strahl mit Complementary und ohne Pack.
- Leistung 720p unverändert bis leicht besser (Tabelle in `docs/PERFORMANCE.md`).
- Befund ohne Codefehler: Die dunklen Rechtecke im nahen Wasser ohne
  Shaderpack waren noch nicht aktualisierte DH-LODs; eine Minute später gleicht
  das Bild Iris. Die zuvor gemessene „dunklere Himmel“-Abweichung aller Packs
  war die Vignette (und im Testclient zusätzlich die sichtbare GUI).

## Stand 11.11.2026 nachmittags (0.26, Fortsetzung)

Neu und im Spiel geprüft (RTX 4070, DH 3.3.3, Sichtweite 4, gleiche Szene wie
der Iris-1.8.12-Referenzclient):
- **DH-Generic-Objekte** (DHs eigene Wolken, Beacon-Strahlen, API-Boxen) werden
  gezeichnet: als LOD-Quads über `dh_generic` bzw. `dh_terrain` (Pack) oder DHs
  Pipeline (ohne Pack). Vorher schaltete VSDH sie ab; deshalb fehlten in BSL die
  Wolken. Pack-Regel `dhClouds`/`clouds=off` wie Iris. `docs/DISTANT_PACK.md`.
- **Geschlossener Horizont** für Pack-Himmelsprogramme (`SkyHorizon`): behebt die
  harte, hellere Kante am Sichtweitenrand bei AstraLex und Insanity.
  `docs/WORLD_SKY.md`.
- **`near`** folgt der tatsächlichen Projektion (Reforgeds JOML-Mixin greift unter
  NeoForge nicht; wirksame Near-Ebene 0,1). `docs/WORLD_UNIFORMS.md`.
- **Frame-Guard**: Laufzeitfehler beim Zeichnen beenden nur das Pack-Rendering
  (Chat-Hinweis, Standarddarstellung) statt das Spiel.
- **CPU**: Uniforms je Programm nur einmal pro Frame und Zeichenraum voll
  schreiben; `skyColor` nur auf Anfrage. `docs/PERFORMANCE.md`.

Wichtiger Befund: Ein früherer, inzwischen entfernter DH-API-Abschalter hatte
`rendererMode = "DISABLED"` in die DH-Konfiguration des Testclients geschrieben.
Alle VSDH-Läufe zwischen 06:43 und 09:40 hatten daher keine LODs; Benchmarks und
„BSL-DH-Wasser fehlt“ aus dieser Zeit waren ungültig. Die Skripte setzen den Wert
jetzt zurück; Benutzer dieser Zwischenstände sollten in DH „Renderer Mode“ prüfen.

Prüfungen:
- Unit-Tests: 87 bestanden. Smoke 3/3 (Diagnose, Complementary, Dimensionen).
  Release-Matrix 6/6 (DH 3.2.0-b, 3.3.0-Variante, 3.3.1, 3.3.2, BSL und Bliss mit
  3.3.3; 2560×1440; 0 Fehlerzeilen). JARs in `dist/0.26.0-dev-compat/`
  (SHA-256 4a12c847… Haupt-JAR, cb8056ab… DH-3.3.0-Variante; identisch mit den
  in der Matrix geprüften Dateien).
- Bildvergleich aller 14 Testpacks gegen Iris, alle gestartet ohne Fehlerzeilen,
  DH aktiv wo das Pack DH-Programme hat: AstraLex, Insanity, Bliss, BSL (jetzt mit
  DH-Wolken), Complementary R/U, Eclipse, MakeUp, Miniature, Photon, Solas, Super
  Duper Vanilla sichtbar übereinstimmend; Rethinking Voxels und Mellow mit kleinen
  Farbunterschieden im Nahbereich. Die Detailstufe der fernen Insel schwankt je
  nach DH-Erzeugungsfortschritt.
- Leistung (Tabellen in `docs/PERFORMANCE.md`): Ohne Shaderpack ist der
  Vulkan-Renderer in 720p 35–50 % schneller als Iris+Sodium, in 1440p gleichauf.
  Mit Shaderpack und DH ist VSDH 7–20 % langsamer als Iris (GPU-Arbeit pro Pixel;
  SPIR-V-Optimierungsstufe ändert nichts).

Offen:
- Leistungsabstand zu Iris mit Shaderpack (Ursache in der GPU-Ausführung der
  übersetzten Programme; ohne GPU-Profiler-Vergleich nicht weiter eingegrenzt).
- BSL: ferne Inselbäume zeitweise dunkler als unter Iris (Ursache offen).
- DH-Wolken im Standardrenderer (ohne Pack) und Beacon-Strahlen nicht im Bild
  geprüft; DH-Ereignis `DhApiBeforeGenericObjectRenderEvent` wird nicht gefeuert.
- Entity-Schattenwerfer mit Geometry-Stufe (Rethinking Voxels) fällt mit Warnung
  zurück; typisierte Custom-Image-Views, `indirect.*`, `shadowcolorimgN`.

## Kompatibilitätsschritt 0.26 (11.11.2026)

Neue Pack-Fähigkeiten, Einzelheiten in `docs/NATIVE_PACK_FEATURES.md`: Pack-Wolken
(`gbuffers_clouds`), Compute-Programme (begin/prepare/deferred/composite/final,
`setup`, `shadowcomp`), `colorimgN` in Compute- und Vollbildprogrammen,
Speicherpuffer (`bufferObject`), eigene Bilder (`image.*`, 1D/2D/3D),
Rohtexturen (2D, 3D als Atlas), Geometry-Stufen (gefaltet oder nativ),
Tessellation-Stufen,
Matrizen und `CAT_*` in Packformeln. `IRIS_FEATURE_COMPUTE_SHADERS/SSBO/
CUSTOM_IMAGES` werden gesetzt. Ein Graphfehler beim Weltstart führt nicht mehr
zum Absturz, sondern zur Standarddarstellung mit Chat-Hinweis.

Geprüft am 11.11.2026 (RTX 4070, 1280×720, DH 3.3.3, Sichtweite 4, gleiche
Szene wie der Iris-1.8.12-Referenzclient):
- Unit-Tests: 86 bestanden. Smoke (Diagnose, Complementary, Dimensionen) bestanden.
  Release-Matrix 6/6 (DH 3.2.0-b, 3.3.0-Variante, 3.3.1, 3.3.2, BSL und Bliss mit
  3.3.3; 2560×1440). JARs: `dist/0.26.0-dev-compat/` mit `SHA256SUMS.txt`.
- CPU-Vorbereitung aller drei Dimensionen mit Profil MEDIUM und mit den
  Pack-Standardwerten: alle 14 Testpacks ohne Fehler.
- Im Spiel mit Bildvergleich gegen Iris sichtbar übereinstimmend: Photon,
  Eclipse Unstable, Bliss, Complementary Reimagined/Unbound, Miniature.
  Weitgehend gleich mit kleineren Farb-/Dunstunterschieden: Solas,
  Rethinking Voxels. BSL: die eigenen Volumenwolken aus deferred1 fehlen
  (Uniform-Werte geprüft und plausibel, Ursache offen); eine fehlende Insel
  in früheren Bildern war die noch laufende DH-Landschaftserzeugung.
- Im Spiel geladen ohne Fehler, Bilder noch nicht im Detail verglichen:
  AstraLex, Insanity, MakeUp, Mellow, Super Duper Vanilla.
- Rückfälle mit Warnung (kein Absturz): Entity-Schattenwerfer mit
  Geometry-Stufe bei Rethinking Voxels.
- Offen: AstraLex/Insanity zeigen am Sichtweitenrand eine harte Kante statt
  Nebel; BSL-Volumenwolken fehlen (Hinweis: fernes DH-Wasser liegt unter VSDH
  nicht wie unter Iris nach deferred1). Laufzeitfehler beim Zeichnen beenden
  das Spiel noch (nur Fehler beim Graphaufbau fallen sanft zurück).

## Stand 0.25.0-dev

DH 3.2.0-b ist die erste ältere Vorabversion im regulären Build. Der
Mixin-Plugin liest die Methoden der installierten DH-JAR und lädt
die Abfrage- und Shutdown-Hooks der passenden API-Generation.
Der Texturadapter unterstützt den älteren und den aktuellen DH-Aufruf.
Die Spieltests und der 1440p-Benchmark stehen in
`dist/0.25.0-dev/verification.json`.

## Änderungen im Shader-Backend

Bliss 2.1.2 lässt sich nach den Ergänzungen für Materialtexturen,
Licht, Projektion und Wetter in allen drei Vanilla-Dimensionen
vorbereiten. Für normale und spiegelnde Materialkarten stehen neutrale
Standardtexel bereit. Eigene PBR-Texturen aus Resourcepacks fehlen noch.
Der native Renderer verbindet die GLSL-Ausgabe eines Vertexshaders mit
der gleichnamigen Fragmenteingabe. Explizite Fragmenteingänge behalten
ihren angegebenen Slot. Wenn ein Geometriepass eine eigene Farbausgabe
gleichzeitig abtastet, verwendet er einen separaten GPU-Snapshot.
Das Budget der Farbziele richtet sich nach dem verfügbaren GPU-Heap.
Die native Oberwelt-Szene mit DH 3.3.3 wurde bei 2560×1440 auf einer
RTX 4070 geprüft. Bild und GPU-Pixel zeigen nach der Stage-IO-Korrektur
wieder Wasser, Reflexionen und Terrain. Die anderen Dimensionen sind
für Bliss CPU-geprüft. Die DH-Schattenprogramme von Bliss fehlen.

Photon 1.4 benötigt für `deferred4_a` einen nativen Compute-Durchlauf.
DH 3.1.2 fehlt bereits die 3.2-Texturkonfiguration. Für DH 2.x ist
ein eigener Renderer-Einstieg erforderlich.

## Stand 0.24

### Umfang der Kompatibilität in 0.24

Modrinth listet für Minecraft 1.21.1 und NeoForge vier stabile DH-Versionen
und 22 Vorabversionen. 0.24 prüft die reguläre JAR mit 3.3.1 bis 3.3.3
und die separate `-dh330.jar` mit 3.3.0.
Die Testmatrix mit den tatsächlich geprüften Pack-/DH-Kombinationen steht
in `dist/0.24.0-dev/verification.json`.

DH 3.3.1 verwendet interne Typen für Tiefenrichtung und Tiefenbereich.
DH 3.3.2/3.3.3 veröffentlichen beide Typen im API-Paket. Der gemeinsame
Vulkan-Renderer lädt den passenden Tiefenadapter. Temporäre DH-Einstellungen
verwenden die verfügbare `setApiValue`-Signatur. DH 3.3.0 liefert für NeoForge
zusätzlich `_neoforge`-Klassen mit einem getrennten Renderer-Injector.
Der erste Spieltest mit der regulären JAR hat wegen des getrennten Injectors
keinen Renderer geladen. `jarDh330` ordnet für den separaten Build die
referenzierten Typen aus dem Original-JAR der NeoForge-Namensvariante zu.
Die älteren 3.x-Betas haben
weitere Schnittstellenunterschiede. Den geprüften 2.x-Versionen fehlt die
Klasse `AbstractDhRenderApiDefinition` vollständig.

BSL 10.1 verwendet zusätzlich `bedrockLevel`, `heightLimit`, `sunPosition`,
`blockEntityId` und `isRightHanded`. `ofTexture` ist der übersetzte Name von
`texture` und erhält dieselbe Live-Textur. Die Packprüfung nennt konkrete
weitere Hürden für Bliss 2.1.2 und Photon 1.4. Bliss scheitert in der aktuellen
Übersetzung an `FlatNormals` und `viewToWorld`. Photon benötigt `deferred4_a`
als Compute-Pass. Die JAR enthält keine Shaderpacks oder DH-JARs.

## Aktuell 0.23

Native Handpässe, Verzauberungsglanz, transparente Blockentitäten,
leuchtende Mob-Schichten und dynamische Modellschatten sind eingebunden.
Der GPU-Test prüft die Hand-Tiefekopien, Glanzpixel und den zusätzlichen
Tiefeninhalt in der Schattenkarte. MEDIUM bleibt im Originalprofil.
`shaderOptions` kann die Pack-Einstellung für Modellschatten aktivieren.

## Stand 0.20

Die beiden normalen transparenten Entitäts-Zeichenarten nutzen den
Packpass `gbuffers_entities_translucent`. Die optionale späte Phase
behält das sortierte Mesh bis nach Deferred. Material-ID, Texturen und
Pack-Mischregeln gelten in beiden Phasen. Der Spieltest benutzt einen
Schleim und prüft, in welcher Phase der native Pass ausgeführt wird.

## Stand 0.19

Der Blockentitätspass nutzt die vorhandene Materialtabelle der Welt und
einen eigenen ID-Kanal im Mesh. Truhen sind die erste getestete Szene.
Die GPU-Probe vergleicht die Mob- und Blockentitäts-ID getrennt.

## Stand 0.18

Der native Pfad für opake Entitäten verwendet die Minecraft-1.21.1-Matrizen,
das Overlay und die pro Vertex erfasste Material-ID. Ein gepufferter
Minecraft-Testbatch prüft auf der GPU zwei IDs, beide UV-Ausrichtungen,
Lichtkoordinaten und Tangenten. Der Spieltest verwendet das Originalpack.
Die unterstützten Entitätsarten, Dimensionsprüfungen und offenen Phasen
stehen im Prüfbericht und in NEXT_AI_PROMPT.md.

## Stand 0.17

Die GPU liest beide Arten ganzzahliger Farbpuffer im nächsten Renderpass.
Die Vulkan-Probe schreibt (7, 19, 33, 250) und (-120, 60, -8, 127) und
prüft die Werte im Deferred-Shader. Der CPU-Test prüft die Meldung eines
falschen Samplertyps bei der Packvorbereitung.

## Stand 0.16

Die GPU-Tests bestätigen identische Lichtkoordinaten über beide alten
GLSL-Namen sowie die Farbmischung in Vollbild-Durchläufen. Ein gemeinsamer
Durchlauf prüft zusätzlich gemischte Float- und Ganzzahlziele. Der Floatpuffer
folgt der Packregel, der Ganzzahlpuffer erhält die vier Shaderwerte direkt.

## Stand 0.15

Die Oberseite der Flüssigkeitsflächen wird mit ihrer richtigen Normalen
gezeichnet, wenn Rückseitenausblendung aktiv ist. Das behebt die dunkle
Nahwasserbeleuchtung im Vergleich zu Iris. Die GPU-Probe las 28.800 von
28.800 beziehungsweise 3.960 von 3.960 nach oben gerichtete Normalen.
Die Standarddarstellung von Reforged behält ihre Behandlung transparenter
Flächen. Die Zwischenwerte sind in DISTANT_PACK.md dokumentiert.

## Stand 0.14

Eine optional konfigurierte Packauswahl wird asynchron vorbereitet und im
Menü installiert. Ungültige Dateinamen und Aufbereitungsfehler werden
protokolliert. Die normale Shaderwahl steht in README.md. 60 CPU-Tests, der Neubau aus dem Quellarchiv und ein Lauf mit der gebauten
JAR bei 2560×1440 sind erfolgreich. Der Dimensionslauf und die Prüfung der
beiden DH-Tiefen stehen im Prüfbericht unter dist/0.14.0-dev.

## Stand 0.13

WorldPackSet bereitet die drei Vanilla-Dimensionen vor. Die Sitzung tauscht
bei jedem ClientLevel-Wechsel ihre Renderziele und Historie aus. Im
Originallauf wurden Oberwelt, Nether, Ende und die Rückkehr zur Oberwelt
mit dem jeweiligen Pack-Ordner gezeichnet. Der CPU-Build enthält 60 Tests.
Die erste Dimensionsaufnahme erfasste frühe Frames. Der nachfolgende
Test wartet auf hochgeladene Terrainpuffer.

## Stand 0.12

Wetter und Partikel sind in die Pack-Durchläufe eingebunden. Drei
Partikel-/Wettervarianten pro Dimension kompilieren im Complementary-Audit.
Die Oberweltprobe prüft tatsächliche Regenpixel sowie beide Partikelphasen.
Entitäten, Hand, Dimensionswechsel und die normale Shader-Auswahl bleiben
offen. Details stehen in WORLD_WEATHER.md.

## Stand 0.11

Vier native Himmelsvarianten für den Shaderpack-Pfad. Die Oberweltprobe
zeichnet Himmelskreis, Sonne, Mond und Sterne. Der Diagnose-Shader bestätigt
Pixel und Spielfarbe. Composite und Final laufen vor dem Tiefen-Clear der
Hand. Die GPU-Probe liest die tatsächliche Tiefe des Nahterrains in zwei
Rendergrößen. Ausstehend sind weitere Geometriefamilien, echte Dimensionsläufe
und die normale Shader-Auswahl. Details stehen in WORLD_SKY.md.

## Stand 0.10

Der Entwicklungsclient zeichnet die originalen Programme dh_terrain und
dh_water aus Complementary Reimagined r5.9.3 MEDIUM. Vulkan verwendet
getrennte D32-Bilder für vollständige und opake DH-Tiefe. GPU-Rücklesen
bestätigt beide Zeichnungen in zwei Rendergrößen. Die Mischregeln des Packs
für Wasser werden pro Ausgabe übernommen und mit vertauschten Ausgaben
auf der GPU geprüft. Der sichtbare Wasserübergang bleibt offen. Himmel,
Entitäten und Hand folgen weiterhin dem normalen Zeichenpfad. Details stehen
in DISTANT_PACK.md und im Prüfbericht.

## Stand 0.9

Echte Packformeln, Handmaterial/-licht, Biome und Glättung, native Welt-Schatten
sowie vorab dekodierte Packtexturen sind implementiert. 56 CPU-Tests bestehen.
Complementary läuft nun auf tatsächlichen Welt-Chunks. Ein falscher Tiefenraum
und die unendliche Epsilon-Fernprojektion verursachten einen nachweisbaren Fehler
der Shader-Blickrichtung; ForwardDepthProjection und endliche Fernweite im
Shadermodus beheben ihn. GPU-Messung und Sichtprüfung bestätigen die Korrektur.

Das Gesamtziel bleibt offen: sichtbarer heller Wasserübergang, Packprogramme für
DH und übrige Geometriefamilien, Blend/Cull, Hand-/Depth-Reihenfolge, Dimensionen
und Auswahl/Hotreload. Details im neuesten Prüfbericht und NEXT_AI_PROMPT.md.
Die folgenden Abschnitte sind historische Stände.

## Stand 0.6

Aktive SPIR-V-Member werden über ihre unveränderten std140-Byteoffsets erkannt.
18 echte Minecraft-Provider ergänzen Kamera-/Augen-/Licht-/Medium-/Effektwerte,
Atlasgröße, Nebel sowie Mond/Sonne/Wolken/Gamma. 36 CPU-Tests bestehen. Neue
GPU-Probes prüfen Luft, Wasser an negativen Koordinaten und aktive Statuseffekte
auf tatsächlichen Terrainpixeln. Die erste Diagnose-Ausgabe wurde wegen
Alpha-Blending korrigiert; Details in WORLD_UNIFORMS.md und im 0.6-Prüfbericht.

Vollständiges Complementary bleibt offen. Aktiv fehlen 17/14/12 Werte in
Oberwelt/Nether/Ende, zusätzlich Schatten und Ressourcen. Die alte Zahl 59 war
eine Liste unbedienter Deklarationen. Beides wird jetzt getrennt ausgewiesen.
Die folgenden Abschnitte beschreiben den historischen Stand 0.5 und früher.

## Stand 0.5

Die Weltzeichnung ist mit NativeShaderChain verbunden. Eigener Diagnosepack auf
realen Chunks, echte Kamera-/Projektionswerte, Schatten noch nicht implementiert.
Welt/DH/Vanilla teilen das Szenenziel; FINAL erreicht das eigentliche Ausgabebild
vor dem GUI. 28 Tests und verify-0.5-client.log bestehen: zwei Welten, Resize,
Nacht/Regen, 100%/50% RenderScale, 44->20-Byte-Formatwechsel, ohne ERROR/FATAL.
31.347 bzw. 4.409 dekodierte echte Normalenpixel und FINAL-Sentinel auf dem echten
Hauptziel wurden per GPU-Rücklesen geprüft. Nicht endliche Start-Projektion wird
protokolliert mit normalem Frame überbrückt, ohne Shader-Historie zu verändern.

Vollständiges Complementary ist weiterhin nicht freigegeben. Der CPU-Audit
meldet 59 fehlende Weltwerte je Dimension, dazu weitere Texturen/Schatten und
Geometriefamilien. Details: WORLD_SHADER_HOST.md und verification/0.5.0-dev.json.
Fortsetzungs-Prompt: NEXT_AI_PROMPT.md. Die folgenden Abschnitte beschreiben die
historischen Schritte 0.4/0.3 und deren weiterhin geltende Grenzen.

## Stand und Ziel

Minecraft 1.21.1, NeoForge 21.1.264, Java 21, DH 3.3.3. Eigener Reforged-Fork;
eine JAR enthält `vsdh` und `vulkanmod`. Erstes Shader-Ziel bleibt Complementary
Reimagined r5.9.3, Profil MEDIUM. **Vollständige Shader-Weltdarstellung ist noch
nicht implementiert.** Iris/Sodium werden nicht gleichzeitig geladen.

0.3 ergänzt die native Durchlaufkette: programmabhängige Lese-/Schreibbilder,
HDR-/Mipmap-Puffer, persistente Frame-Daten, Größenvorprüfung, Tiefensnapshots und
Stencil-Kopien. Die Basis-Vulkan-/DH-Ausgabe bleibt erhalten. Detaillierter
Datenfluss und API-Grenzen: `docs/RENDER_GRAPH.md`.

0.4 ergänzt das echte 44-Byte-Terrainformat, BlockState-Materialtabellen und die
native indizierte Terrainpipeline. Alle vier oldLighting-/separateAo-Kombinationen
werden mit HDR-Pixelassertionen geprüft. 36 Complementary-Terrain-/Wasser-/Shadow-
Pipelines werden erstellt; echte Chunk-Erzeugung wird im erweiterten Format und
nach Rückwechsel im Standardformat ausgeführt. Details: `docs/TERRAIN_FORMAT.md`.
Die Weltkette mit Packuniforms und Schatten bleibt noch zu verbinden.

Der Weltwechseltest deckte einen statischen Stride in DrawBuffers auf; er ist nun
an den jeweiligen Bereich gebunden. Falsche Upload-Strides werden früh abgewiesen.
TaskDispatcher erneuert auch seine synchronen Builder nach Welt-/Ressourcenwechsel.
Der korrigierte vollständige Lauf `verify-terrain-world-fixed.log` besteht ohne
ERROR-/FATAL-Zeilen. 23 CPU-Tests bestehen, einschließlich SPIR-V-Attributprüfung.

Die frühere Git-Regel build/ hatte 28 echte Renderer-Quelldateien ausgeschlossen.
Sie lautet jetzt /build/; diese Dateien werden mitversioniert. Alte vollständige
source.zip-Archive aus git archive waren dadurch unvollständig. Binär-JAR und
sources.jar wurden aus dem lokalen SourceSet erzeugt und enthielten die Klassen.
Der neue Quellstand baut erfolgreich in einem frischen Verzeichnis; Protokoll:
`verify-0.4-source-uncached.log`: alle Build-Tasks ohne Gradle-Build-Cache erneut
ausgeführt. Externe Abhängigkeiten waren lokal vorhanden; kein Netz-unabhängigkeitsnachweis.

## Quellen

| Quelle | Festgelegter Stand |
|---|---|
| Braffolk/distant-horizons-vulkanmod | a72ba645188c5fab5f9d48254b6703b659543f0c |
| TrulyRin/VulkanMod-Reforged | 0618965fbedb71ca2ee2e5d4528fcedc4fb27551 |
| avpbynf/Vitrail-Shaders, portable Teile | 7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e |
| Distant Horizons | 3.3.3, Modrinth 9w34y8ai |
| Complementary Reimagined | r5.9.3, Modrinth Bqen1mJX |

Shaderpack-SHA-1: `838139b54cddb56b2e83cd260d8efd960ac536d6`.
Lizenzen und vollständige Autorenhinweise: NOTICE, LICENSE, COPYING und
`docs/upstream/`. Keine privaten Referenzen, dekompilierten Klassen oder
Complementary-Quellen werden mitgeliefert. Der alte Stand 0.2 bleibt in Git
und unter `dist/0.2.0-dev` erhalten; Berichte früherer Stände bleiben separat.

## Ausgeführte Prüfungen

| Prüfung | Ergebnis und Aussagegrenze |
|---|---|
| Java-Build | `build` erfolgreich; Renderer-Fork und neue Kette kompiliert |
| JUnit | 23 Tests, keine Fehler oder übersprungenen Tests |
| Pack-Compiler | alle 96 Programmpaare in drei Complementary-Dimensionen zu SPIR-V kompiliert |
| Graph-Vorbereitung | alle drei Dimensionen; echte Reihenfolge, Zielgrößen, Mipmaps und persistente Bilder ermittelt |
| GPU-Diagnosekette | alle Stufen mit bekanntem Ergebnis; vier aufeinanderfolgende Frames prüfen History-Kopien |
| Mipmap-Nachweis | Schachbrett muss in Mipmap 1 den bekannten Mittelwert liefern; zusätzlich RenderPass-Endlayout mit mehreren Mips geprüft |
| Größen/Präsentation | zwei unterschiedliche Puffergrößen, kleinere Teilpuffer und ein tatsächlich ohne FINAL geparstes Pack geprüft |
| Tiefenbilder | D32, D24S8 und D32S8; .25/.5/.75 in getrennten Kopien korrekt gelesen |
| Stencil | unterschiedliche Stencil-Sentinels vor Hand und Transparenz erhalten |
| Bindungsnummern | SPIR-V enthält aktive Bindungen 0 und 2 nach Entfernung von Binding 1; native Pipeline erstellt und gezeichnet |
| Echtes Complementary | neun Vollbild-Pipelines und elf Zielpuffer als Vulkan-Objekte angelegt; FINAL mit kontrollierten Eingaben gezeichnet |
| Client-Lifecycle | zwei neue Welten, Resize, Nacht/Regen, Disconnect, Neuinitialisierung und reguläres Beenden |
| Sichtprüfung | aktuelle zweite Welt bei Tag und Regen/Nacht angesehen; normales Bild nach Tests wiederhergestellt |
| Paket | LWJGL-Vulkan/shaderc/VMA und JarJar-Metadaten geprüft; Diagnosecode/-packs ausgeschlossen |

Referenzgerät: NVIDIA RTX 4070 unter Windows. Testfenster zunächst 1280×720,
danach 960×540. Der GPU-Test für die echte Ketten-Konstruktion verwendet kleine
Zielbilder; die 1440p-Speicherzahl ist eine Berechnung, keine gemessene VRAM-Nutzung.
Kein kontrollierter FPS-Vergleich.

Letzter vollständiger Lauf: `verify-0.4-final-client.log`, BUILD SUCCESSFUL,
je vier bestandene Gruppen für MRT, Diagnosepack, Kette, Complementary-FINAL,
Complementary-Kettenaufbau und Terrain. Zusätzlich zwei REAL-TERRAIN-Prüfungen
mit 1.081.344 Byte bei Stride 44 und 492.800 Byte bei Stride 20. Keine ERROR-/FATAL-Zeilen. Die normale Clientausgabe
wird durch native Pixelassertionen ergänzt; dies ist keine vollständige
Complementary-Sichtprüfung. Die installierbare JAR wurde strukturell geprüft,
aber nicht separat in einem normalen Launcher gestartet.

Weitere Protokolle: `verify-0.3-audit.log`, `test-chain.log`.
Maschinenlesbare Ergebnisse: `docs/verification/0.4.0-dev.json`; historische
0.3-Ergebnisse bleiben erhalten.

## Entdeckte Fehler und Nachprüfung

Der CPU-Test erkannte zunächst, dass die inline geschriebenen Mipmap-Direktiven
im Diagnosepack nicht gelesen wurden. Die Schreibweise wurde korrigiert; der
Schachbretttest verhindert eine scheinbar erfolgreiche Base-Level-Abfrage.

Der erste echte Complementary-Kettenaufbau scheiterte an einem Indexfehler in
Reforged: Bindungsnummern wurden als Array-Indizes benutzt. Nun wird ein kompaktes
Vulkan-Array mit den richtigen expliziten Bindungsnummern aufgebaut. Dynamische
UBOs sind passend sortiert. Fehlerlauf: `verify-0.3-client.log`; korrigierter
vollständiger Lauf: `verify-0.3-client-fixed.log`.

Die in 0.2 korrigierte DH-SQL-Abschaltrennbedingung bleibt nachgeprüft; in diesem
Lauf trat kein `stmt pointer is closed` auf. Kein Langzeitnachweis für sämtliche
DH-Abschaltpfade.

## Noch offen

Die echte Weltgeometrie muss an die Kette angebunden werden: Material-/Normaldaten
und native Terrainprogramme sind vorbereitet und getrennt geprüft; Welt-/Kamera-
Uniforms und Schattengeometrie fehlen weiterhin. Danach
Himmel, Entitäten, Hand, Transparenz und shaderseitiges DH-Terrain/-Wasser.
Paket-/Profilwahl und kontrollierter Wechsel gehören ebenfalls noch dazu.

Keine Freigabe für alle Shaderpacks oder DH-Versionen, andere GPUs/Betriebssysteme,
große Modpacks oder dedizierte Server. Textur-LODs, zusätzliche DH-AA-/Drahtgitter-
und API-Geometrie bleiben eingeschränkt. Wolken-/Wasser-/LOD-Übergänge benötigen
weitere Prüfung. Kein einfacher Bildschirmfilter wird als vollständige
Complementary-Unterstützung bezeichnet.

Aufräumen 15.11.2026 (Commit nach f64d2cb; nur Code, Unit-Tests und Link-Prüfung, nichts im Spiel gestartet): nicht benötigter
Code entfernt, Standardweg unverändert. Verworfene Experimente sind jetzt auch im Code weg: D3D11-Ausgabe (`DxgiPresenter`,
`-Dvsdh.dxgiPresent=d3d11`, `-Dvsdh.dxgiLatency`), Teil-Submit (`Renderer.submitPartial`, `-Dvsdh.splitSubmit`), zweite
Present-Queue (`-Dvsdh.separatePresentQueue`), `-Dvsdh.presentMode=mailbox`. Dazu unbenutzte Sprachschlüssel
(`vsdh.options.lowLatency*`), unbenutzte Methoden/Felder im Shader-Host und in der DH-Brücke (`Compat`: Tiefenkopie,
Layout-Übergänge, nie gesetzter Deferred-Hook; `VulkanBackend.readAndCacheMcDepth`) und unbenutzte Importe. Behalten und
begründet in `AGENTS.md` (letzter Absatz): alle dort genannten A/B-Schalter, `Config.lowLatency`, Reforged-/Vitrail-Quellen,
`uniformBiomeSkip`. Vor dem Löschen einer Methode immer auch `build/reference/release-client*/src` durchsuchen (kompiliert
gegen VSDH, nicht Teil des Gradle-Builds). Im Spiel zu prüfen: Start im Fenster auf NVIDIA (DXGI/D3D12), F11 hin und zurück,
Welt mit und ohne DH, ein Pack mit DH-Programmen.
