# Weiterarbeit an VSDH

Projekt: `C:/Users/Stranger/Documents/Meine Mods/VSDH`, Branch `codex/renderer-shaders`.
MC 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.3.3. Der Nutzer hat nach der
Fork-Empfehlung die autonome Weiterarbeit bis zum Ziel beauftragt. Keine erneute
Grundsatzfrage Addon/Fork. Eine JAR enthält die Mod-IDs `vsdh` und `vulkanmod`;
die originale Reforged-JAR darf nicht parallel installiert sein.

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
