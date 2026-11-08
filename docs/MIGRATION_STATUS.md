# VSDH — Prüfstand 0.2.0-dev, 08.11.2026

## Ziel und Entscheidungen

Minecraft 1.21.1, NeoForge 21.1.264, Java 21. Nutzerentscheidung: VulkanMod Reforged,
schrittweiser Ausbau Vulkan + DH + ausgewählte Shaderpacks. Erstes Ziel:
Complementary Reimagined r5.9.3. Eigener Renderer-Fork nach der Fortsetzungsanweisung.
Breite spätere Kompatibilität ist ein Ziel, keine vorhandene Eigenschaft.

Eine JAR enthält jetzt `vsdh` und `vulkanmod`. Die bisherige Addon-Baseline
`6699fe6` bleibt erhalten; Renderer-Import in `783f068`.

## Gebundene Quellen

| Quelle | Festgelegter Stand | Verwendung |
|---|---|---|
| Braffolk/distant-horizons-vulkanmod | a72ba645188c5fab5f9d48254b6703b659543f0c | native DH-Ausgabe |
| TrulyRin/VulkanMod-Reforged | 0618965fbedb71ca2ee2e5d4528fcedc4fb27551 | Renderer im eigenen Projekt |
| avpbynf/Vitrail-Shaders | 7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e | portable Parser/GLSL-Übersetzung |
| Distant Horizons | 3.3.3, Modrinth 9w34y8ai | externe JAR, Hashprüfung im Build |
| Complementary Reimagined | r5.9.3, Modrinth Bqen1mJX | ausschließlich externe Testkopie |

Shaderpack-SHA-1: `838139b54cddb56b2e83cd260d8efd960ac536d6`.
LGPL- und Autorenhinweise: `NOTICE`, `LICENSE`, `COPYING`, `docs/upstream`.
Private Vergleichsquellen und dekompilierte DH-Klassen liegen außerhalb des
Repositorys. Keine solchen Dateien oder Complementary-Quellen in den Artefakten.

## Tatsächlich geprüfte Ergebnisse

| Prüfung | Ergebnis und Aussagegrenze |
|---|---|
| Java-Build | `build` erfolgreich; vollständiger Renderer-Fork kompiliert |
| JUnit | 12 Tests, keine Fehler oder übersprungenen Tests |
| std140 | Mitgliedsoffsets gegen unabhängig mit shaderc erzeugtes SPIR-V verglichen |
| SQLite-Abbruch | echte native DH-SQLite-Abfrage in RAM beendet, bevor die Verbindung geschlossen wird |
| Original-Abhängigkeiten | DH- und Flywheel-Hashes stimmen; Downloadskript geprüft |
| Laufzeitbibliotheken | `verifyBundledLwjgl`: erwartete Vulkan/shaderc/VMA-JARs und Metadaten geprüft |
| Complementary-Compiler | alle 96 Programmpaare in drei Dimensionen, Profil MEDIUM, zu SPIR-V kompiliert; Uniformlayouts berechnet |
| Mehrere Farbausgaben | vier getrennte Ausgaben, zwei Puffergrößen und ein reiner Tiefendurchlauf; alle Pixel von der GPU zurückgelesen und verglichen |
| Nativer Pack-Durchlauf | eigenes Diagnosepack: Includes, Legacy-GLSL, Uniforms, zwei RGBA16F-Ziele, Texturlesen und Wechsel eines Deskriptors mit derselben Pipeline |
| Echtes Complementary-Programm | `world0/final` mit kontrollierten HDR-/Rauschbildern auf der GPU ausgeführt; erwartete mittlere Pixelfarbe verglichen |
| Spiel-Lifecycle | zwei neu erzeugte Welten, Resize, Nacht/Regen, Disconnect, Neuinitialisierung, reguläres Beenden |
| Sichtprüfung | aktuelle Screenshots der zweiten Welt bei Tag und Regen/Nacht angesehen; normales Weltbild nach Diagnose-Durchläufen wiederhergestellt |

Hardware dieser Spieltests: RTX 4070, Windows; Entwicklungsclient zunächst
1280×720, dann 960×540. Kein FPS-Benchmark. Das Referenzgerät des Nutzers ist ein
Ryzen 9 7950X3D mit 64 GB DDR5 und regulär 2560×1440.

Wichtige Protokolle im lokalen Projekt: `verify-0.2.log`,
`run-native-pass-cancel.log`, `run-complementary-final.log`,
`verify-final-client.log` und
`build/shader-audit/report.txt`. Der letzte vollständige Spieltest mit dem echten
Final-Programm enthält vier bestandene Prüfgruppen vor/nach Resize in zwei Welten,
keine ERROR/FATAL-Zeilen und einen erfolgreichen Prozessabschluss.

## DH-Abschaltrennen

Das frühere `stmt pointer is closed` trat auch beim regulären Disconnect erneut
auf. Nicht als harmlos verworfen: Die Klassenanalyse zeigte, dass DH 3.3.3 zwar
`shutdownNow()` aufruft, aber eine laufende native Regenerationsabfrage nicht
beendet und deren Ende vor Repository-Close nicht abwartet.

Eine erste reine Warteergänzung schlug fehl: Die native Abfrage lief trotz
Java-Interrupt weiter. Dieser Fehlversuch steht in `run-native-pass.log`;
der blockierte, ausschließlich eigene Testclient wurde beendet.

Der korrigierte Pfad registriert nur die lesende Regenerations-Positionsabfrage,
fordert beim Shutdown deren JDBC-Abbruch an und wartet begrenzt auf die Worker.
Nur explizites `SQLITE_INTERRUPT` dieser eigenen Abbruchanforderung ist ein normaler
Abbruch. Andere SQL-Fehler werden weitergereicht. Datenbankinhalte werden nicht
verändert. Zwei folgende vollständige Weltwechsel-Läufe verliefen ohne diesen
Fehler. Das ist kein Langzeitnachweis für alle DH-Abschaltpfade.

## Noch offen — keine fertige Shaderpack-Unterstützung

Der vollständige Welt-Rendergraph, Block-/Materialattribute, echte Schatten-
Geometrie, Himmel, Entitäten, Hand, Transparenz und DH-Terrain/-Wasser durch die
Pack-Programme sind noch nicht integriert. Der tatsächliche Complementary-
Final-Durchlauf ist ein gezielter Backend-Test, kein spielbarer Complementary-Modus.
Die Mod bietet deshalb noch keine Aktivierung vollständiger Shaderpacks an.

Keine Freigabe für Iris-/Sodium-Co-Loading, andere DH-Versionen, alle Shaderpacks,
andere GPUs/Betriebssysteme, große Modpacks oder dedizierte Server. Keine FPS-
Gewinne behauptet. Textur-LODs, zusätzliche DH-AA-/Drahtgitter-/API-Geometrie und
Wolkenüberdeckung bleiben wie im ersten Meilenstein eingeschränkt.

## Nächster zusammenhängender Entwicklungsschritt

Ziel ist die gemeinsame Welt-Pipeline: colortex-Wechsel/History und Tiefenführung,
Terrain-/Materialformat und Schattenkamera zuerst; danach weitere Geometriefamilien
und shaderseitige DH-Ausgabe. Ein einfacher Bildschirmfilter ersetzt diese Arbeit
nicht. Erst die vollständige Sichtprüfung darf als Complementary-Unterstützung
bezeichnet werden. Neue Abhängigkeiten oder alternative Renderer wurden dafür
nicht eingeführt.

Maschinenlesbarer Prüfbericht: `docs/verification/0.2.0-dev.json`.
