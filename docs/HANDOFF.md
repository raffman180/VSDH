# Weiterarbeit an VSDH

Projekt: `C:/Users/Stranger/Documents/Meine Mods/VSDH`, Branch `codex/renderer-shaders`.
MC 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.3.3. Der Nutzer hat nach der
Fork-Empfehlung die autonome Weiterarbeit bis zum Ziel beauftragt. Keine erneute
Grundsatzfrage Addon/Fork. Eine JAR enthält die Mod-IDs `vsdh` und `vulkanmod`;
die originale Reforged-JAR darf nicht parallel installiert sein.

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
