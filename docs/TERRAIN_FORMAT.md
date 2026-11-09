# Native Terrain-Anbindung — 0.4

Zielumgebung: Minecraft 1.21.1, NeoForge 21.1.264, Java 21. Das Format gehört zu
VSDH/Reforged und verwendet keine Sodium-Mesh-Dekodierung. Die Standardausgabe
bleibt beim bisherigen 20-Byte-Format; der Shaderpfad verwendet 44 Byte je Vertex.
Das sind 2,2-mal so viele Vertexdaten, keine Aussage über gesamte VRAM-Nutzung
oder Bildrate. Eine spätere Reduktion nach den tatsächlich benötigten Attributen
ist noch nicht umgesetzt.

| Offset | Attribut / Vulkan-Location | Inhalt |
|---|---|---|
| 0 | vs_Position / 0 | signed short XYZ, 1/2048 Block, -4 Bias; W: zwei Lichtbytes |
| 8 | vs_Color / 1 | RGBA8, bisherige Vanilla-Farbe einschließlich AO/Flächenschattierung |
| 12 | vs_UV0 / 2 | unsigned short UV, 1/32768 |
| 16 | vs_Normal / 3 | signed Normalen-XYZ-Bytes; unsigned Flächenschattierung in Byte 3 |
| 20 | vs_Tangent / 4 | signed Tangenten-XYZ-Bytes und Händigkeit ±127 |
| 24 | vs_Material / 5 | signed int, Pack-ID aus block.properties; unzugeordnet -1 |
| 28 | vs_Fluid / 6 | signed int, Flüssigkeit 1 / Modell 0 |
| 32 | vs_MidUV / 7 | Quad-UV-Mittelpunkt, gleiche Quantisierung wie UV0 |
| 36 | vs_MidBlock / 8 | signed XYZ-Abstand zum Blockzentrum in 1/64; Emission 0..15 |
| 40 | vs_TintAO / 9 | RGBA8: ursprüngliche Vertexfarbe × Blocktönung; AO in Alpha |

Normalen stammen aus den Diagonalen aller vier tatsächlichen Eckpunkte. Tangenten
werden aus Geometrie und UV-Ableitungen orthogonalisiert. Gespiegelte UVs ändern
die Händigkeit. Für entartete Geometrie/UVs gibt es einen endlichen orthogonalen
Ersatzrahmen. Dies ersetzt keine vorhandenen benutzerdefinierten Vertexnormalen
für Modelle, die ausdrücklich weich interpolierte Normalen benötigen.

Die getrennten Daten beantworten alle vier Kombinationen von oldLighting und
separateAo ohne Division durch eine möglicherweise nullwertige AO. Die vorige
Vanilla-Farbe bleibt zusätzlich vorhanden, damit die normale Terrainpipeline
das Format unverändert darstellen kann. GLSL-Locations sind explizit; entfernte
unbenutzte Attribute verschieben keine weiteren Eingänge.

ShaderBlockMaterials liest die vorverarbeiteten Pack-Einträge, prüft Eigenschaften
und Block-Tags und erzeugt eine unveränderliche Tabelle tatsächlicher BlockStates.
Die erste passende Deklaration gewinnt. Nicht installierte optionale Blöcke bleiben
unzugeordnet. Wassergefüllte Blöcke liefern für Flüssigkeitsquads die Material-ID
des Fluid-Blockzustands, für Modellquads die des Wirtsblocks. Bei Tag-/Packwechseln
müssen Tabelle und Meshes zusammen erneuert werden.

PipelineManager.configureShaderTerrain(snapshot) und restoreDefaultTerrain()
erlauben den Formatwechsel nur bei entladener Welt. Shaderpack-Auswahl im Spiel
ist noch nicht implementiert. Die synchronen und asynchronen Builder werden bei
Weltstart neu angelegt. DrawBuffers hält Stride und Positionsbias je Bereich fest;
Uploads mit falschem Stride werden abgewiesen. Die Format-API allein aktiviert
keine Complementary-Weltgrafik.

NativeTerrainPass verwendet die gemeinsame NativeGraphicsProgram-Implementierung
für Uniforms und Deskriptoren; NativeFullscreenPass behält seine bestehende API.
Der Terrain-Zeichenaufruf ist derzeit ein opaker indizierter Durchlauf. Packgerechte
Transparenz, Alpha-Tests, echte Welt-/Kamerauniforms und Schattenkamera/-geometrie
fehlen weiterhin bei der Einbindung in die vollständige Weltkette.

## Prüfungen und Fehlerbefunde

- CPU: bekannte geneigte Fläche, gespiegelte UVs, 500 deterministische zufällige
  Flächen mit Umkehr der Umlaufrichtung, entartete Flächen und unvollständige Quads.
- SPIR-V: die tatsächlichen aktiven Eingangs-Locations unter allen vier Lichtregeln.
- GPU: echte TerrainBufferBuilder-Bytes → Vulkan-Vertexzugriff → übersetztes GLSL
  → vier HDR-Ausgaben. Alle Pixel werden für zwei Ausgabemodi und vier Lichtregeln
  geprüft, einschließlich nichtnuller firstInstance-/Chunk-Versätze und des
  niederwertigen Bytes einer Ganzzahl-Materialkennung über der Float-Präzisionsgrenze.
- 36 Complementary-Terrain-/Wasser-/Shadow-Pipelines werden vorab erstellt:
  drei Dimensionen × drei Programme × vier Lichtregeln. Das ist keine Darstellung
  der vollständigen Complementary-Welt.
- Spiel: Welt 1 baut echte Chunks im 44-Byte-Format, Welt 2 nach Wechsel im
  20-Byte-Format, mit Resize, Nacht/Regen und DH-Ressourcen-Neuanlage.

verify-terrain-world-client.log deckte beim Rückwechsel einen bestehenden statischen
Stride in DrawBuffers auf. Er erzeugte nicht ausgerichtete Uploads und fehlende
AreaBuffer-Segmente. Der Stride ist nun je Bereich fest; verify-terrain-world-fixed.log
prüft den korrigierten Wechsel. Ein BUILD SUCCESSFUL ohne Kontrolle der Fehlerlogs
würde diesen Fehler übersehen.

Die Git-Regel build/ hatte zusätzlich den echten Quellordner render/chunk/build
ausgeschlossen. Sie ist auf /build/ eingeschränkt; sämtliche 28 Renderer-Quelldateien
werden jetzt mitversioniert. Ältere source.zip-Archive aus git archive waren deshalb
unvollständig. Die alte Binär-JAR enthielt diese lokal kompilierten Klassen dennoch.

Verhaltensreferenzen: Iris-Zweig 1.21.1, Commit
12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3 (Material-/Fluid-/AO-Vertrag),
https://github.com/IrisShaders/Iris/tree/12ea3bf16b39c0a70ec31dc9de1483c77bd3ccc3.
Der portable BlockIds-Parser stammt aus dem bereits festgelegten Vitrail-Commit;
NOTICE und docs/upstream/VITRAIL_NOTICE.txt gelten auch dafür. Keine Iris-/Sodium-
Renderer-Implementierung wurde hinzugefügt.

Abschlusslauf: verify-0.4-final-client.log, zwei Welten und vier vollständige GPU-Gruppen,
keine ERROR/FATAL-Einträge. REAL TERRAIN WORLD PASS kontrolliert tatsächlich
hochgeladene Weltvertices, den Stride und die synchronen Builder nach dem Wechsel.
23 CPU-Tests und der frische Build ohne Gradle-Build-Cache bestehen.
