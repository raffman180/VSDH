# VSDH — Prüfstand 0.5.0-dev, 09.11.2026

## Aktuell 0.5

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
