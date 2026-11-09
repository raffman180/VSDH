# Fortsetzungs-Prompt für die nächste KI

Neuester Zwischenstand 0.7: PackExpressions wertet echte Packformeln mit dem
portablen Vitrail-/StarEval-Auswerter aus. Keine MC-26-Laufzeit importiert;
ExpressionFrame/UniformCatalog sind kleine VSDH-Adapter. Glättungszustand gehört
zum einzelnen Ausdruck und zur Sitzung. PackWorldValues liest Handmaterialien aus
item.properties, echtes Blocklicht inklusive Block-State-Komponenten, Biome,
Niederschlag, geglättetes Augenlicht und Wetness. Ein LightTexture-Mixin erfasst
den tatsächlichen darknessLightFactor. Unauflösbare/zyklische Formeln bleiben
diagnostiziert, nicht endliche Ergebnisse brechen die Aktivierung/Auswertung ab.

45 CPU-Tests und verify-0.7-client.log bestehen vollständig: zwei Welten, alle
alten GPU-Gruppen sowie EXPRESSION WORLD GPU PASS für Luft, Wasser, Effekte und
Regen. Getrennte Hand-IDs, alte Nebenhandlicht-Regel, Formelergebnisse, Bool-/Vec3-
Werte und das untere Byte von 33554559 wurden tatsächlich zurückgelesen.
Complementary-Audit: aktiv fehlen nur shadowModelView/shadowProjection in Oberwelt
und Ende, im Nether keine Weltwerte; Ressourcen und Renderprogramme bleiben offen.
Nicht aktive End-Flash-Formeln brauchen ein neueres Minecraft-Feature und bleiben
diagnostiziert. Nächste konkrete Arbeit ist der native Schattenpass mit Sonnenkamera
und allen geladenen Chunks, danach Texturversorgung und verbleibende Geometrie/DH.
Die folgenden Abschnitte halten den Ausgangsstand 0.6 fest; git status/neuste Logs
prüfen, weil weitere Schattenänderungen noch laufen können.

Arbeite direkt am Projekt `C:/Users/Stranger/Documents/Meine Mods/VSDH` weiter.
Antworte auf Deutsch, erkläre wichtige Entscheidungen verständlich und frage nicht
für kleine technische Schritte nach Erlaubnis. Der Nutzer hat den eigenen
VulkanMod-Reforged-Fork und die Weiterentwicklung bis zum Ziel bereits beauftragt.

Ziel: Minecraft 1.21.1 / NeoForge 21.1.264 / Java 21 mit VulkanMod Reforged,
Distant Horizons und Shaderpacks. Erstes festgelegtes Ziel: DH 3.3.3 und
Complementary Reimagined r5.9.3 MEDIUM. Keine pauschale Zusage für alle Shaderpacks
oder DH-Versionen, keine Behauptung absoluter Fehlerfreiheit. Iris/Sodium nicht
als zusätzliche Renderer-JARs installieren; dieser Fork soll deren Shaderfunktionen
nativ unter Vulkan ersetzen.

Lies zuerst AGENTS.md, docs/HANDOFF.md, docs/MIGRATION_STATUS.md,
docs/WORLD_SHADER_HOST.md, docs/WORLD_UNIFORMS.md und den neuesten Bericht unter
docs/verification/.
Prüfe anschließend git status und die neuesten Build-/Spielprotokolle. Es können
noch nicht versionierte Änderungen der laufenden Arbeit vorhanden sein. Nicht
blind zurücksetzen oder schon geprüfte Teile erneut entwickeln.

Branch: codex/renderer-shaders. Ausgangs-Commit vor 0.6 war
5d9820007c243a51a53248b878045dd7142e1a3f (0.5). Den aktuellsten Commit selbst prüfen.
Der vollständige Complementary-Renderer ist ausdrücklich noch NICHT fertig.

Stand 0.5: WorldShaderSession leitet echte Weltzeichnungen in die native
Shaderkette um. WorldRenderer nutzt dieselben realen Chunk-Puffer für die
Shaderprogramme. WorldMatrices/WorldUniforms liefern echte Kameramatrizen,
Projektionshistorie und grundlegende Weltwerte. Unbekannte Uniforms/Ressourcen
werden vor Aktivierung abgewiesen. Der Diagnosepack läuft auf echten Chunks
samt bisherigem DH-Composite. Shaderprogramme für Entitäten/Himmel/Hand/DH selbst
und Schatten sind noch nicht vollständig eingebunden.

Belegt: verify-0.5-client.log besteht mit zwei Welten, Resize, Nacht/Regen,
44->20-Byte-Formatwechsel und Shader-Tests bei 1280x720/100% sowie 480x270/50%.
WORLD SHADER HOST PASS zählt echte Einheitsnormalen im MRT und prüft einen
FINAL-Kontrollpixel im tatsächlichen Ausgabebild. Keine ERROR/FATAL-Zeilen;
eine beim Weltstart nicht endliche Minecraft-Projektion wird gezielt mit dem
normalen Ziel überbrückt, ohne Shader-Historie fortzuschreiben. Details in den Logs.
Vorherige Fehlerläufe verify-world-client*.log aufbewahren.

Wichtige Korrekturen: nur Sodium benötigt of_TexShrink; Native-Vulkan-Inputs dürfen
es nicht automatisch bekommen. DH-Compat.rebindMainTarget muss die MainPass-
Schnittstelle respektieren, keine erzwungene DefaultMainPass-/Swapchain-Bindung.
Viewport immer in logischen Koordinaten übergeben, sonst wird RenderScale doppelt
angewendet. Stride/Bias je DrawBuffers-Instanz, keine statischen Formatwerte.
/build/ in .gitignore muss verankert bleiben, sonst fehlen 28 Renderer-Quelldateien.

Aktuelle Weiterarbeit 0.6: UniformUsage erkennt tatsächlich gelesene Member im
optimierten SPIR-V-Uniformblock anhand der Byteoffsets, ohne Debugnamen.
Unklare Pointerpfade behalten konservativ alle Werte. NativeGraphicsProgram cached
aktive Member; WorldUniforms validiert/beschreibt diese. BuiltinWorldValues ergänzt
18 echte MC-1.21.1-Provider: Int-/Fract-Kamera, Augen-/Blickwerte, Augenlicht,
Kameramedium, Effekte, Wolkenhöhe, Sonnenwinkel, Mondphase, Gamma, reale Atlasgröße
und OfFog. CameraCoordinates zerlegt negative/große Koordinaten mit floor.
Keine erfundenen Pflichtdaten oder Null-Schattenmatrizen einsetzen.

36 CPU-Tests, Build und verifyBundledLwjgl bestehen; alle Build-Tasks erneut ohne
Gradle-Build-Cache ausgeführt (verify-0.6-build.log). verify-0.6-client-2.log besteht
vollständig mit zwei Welten, drei neuen Werte-Probes, beiden Weltketten-/FINAL-
Scale-Prüfungen und allen sechs bisherigen GPU-Gruppen je viermal, ohne ERROR/FATAL.
Aktuellen Stand auch im versionierten Bericht prüfen. Der erste Lauf deckte einen
Fehler im Diagnosepack auf: Messwerte im Alpha-Kanal wurden bei transparenten
Flächen vermischt (erwartet 1.0, gemessen 0.75). Die Korrektur nutzt Alpha=1 und
benachbarte gerade/ungerade Pixel für zusätzliche Werte; keine Prüfung abschwächen.
BuiltinWorldProbe belegt Luft, echtes Wasser bei (-3.25,59.62,-7.125) und drei
aktive Statuseffekte auf echten Terrainpixeln. Weltkette/FINAL belegen 444.850
Normalenpixel bei 1280x720/100% und 62.562 bei 480x270/50%. Welt 2 nutzt wieder
20 statt 44 Byte; Shader-Deinstallation und DH zeichnen weiter erfolgreich.
Der Wasserpool und die Effekte betreffen nur eine frisch erzeugte isolierte QA-Welt.

Nächster funktionaler Schritt: build/world-host-audit.json auswerten. Aktiv fehlen
Oberwelt 17, Nether 14, Ende 12 Werte. Die alte Zahl 59 zählte alle Deklarationen;
vor den neuen Providern waren in der Oberwelt 31 wirklich gelesen. Offen sind u.a.
eyeBrightnessM/frameTimeSmooth, Held-Item-Materialtabellen, darknessLightFactor,
Pack-Ausdrücke (inDry/inSnowy/inRainy, rainFactor/wetness/maxBlindnessDarkness)
und shadowModelView/shadowProjection. Tatsächliche Semantiken und zeitabhängige
Glättung implementieren, keine bloßen Namen mit Nullwerten freischalten.
Float-cameraPosition benötigt separat Iris-Origin-Rebasing; die neuen absoluten
Int-/Fract-Werte ersetzen dies nicht. Danach Schattenkamera/-geometrie, Noise- und
weitere Texturen sowie native Pack-Ausgabe für DH-Terrain/Wasser anschließen,
gefolgt von den übrigen Geometriefamilien. DH läuft bisher als später Composite
ins Szenenbild, noch nicht über dh_terrain/dh_water. Hand-Reihenfolge/depthtex1/2,
Pack-Blend/Cull, Dimensionwechsel, Auswahl/Hotreload sind ebenfalls offen.
Der Diagnosepack beweist keine vollständige Complementary-Unterstützung.
Bestehende stabile dist/0.5.0-dev-Artefakte nicht überschreiben.

Referenzen/Pins und Lizenzen stehen in AGENTS.md/NOTICE/docs/upstream. Private
Referenzen liegen außerhalb des Projekts unter
`C:/Users/Stranger/Documents/ChatGPT/Vulkan Shader&DistantHorizons/.reference`.
ComplementaryReimagined_r5.9.3.zip bleibt dort, SHA1
838139b54cddb56b2e83cd260d8efd960ac536d6. Weder Pack noch private Minecraft-/DH-
Referenzquellen in JAR oder Quellpaket übernehmen.

Prüfen: gradlew.bat test build verifyBundledLwjgl; Shader-CPU-Audit mit
`auditWorldHost -PshaderPack=<absoluter ZIP-Pfad>`; echter Spieltest mit
`runSmokeClient -PsmokeShaderPack=<absoluter ZIP-Pfad>`. Die Smoke-Welten sind
isoliert. Bestehende Welten/Inventare und Nutzereinstellungen schützen. Alle
Vulkan-Ressourcen auf dem Renderthread, GPU-Freigaben verzögert über vorhandene
Renderer-Verwaltung. Build, CPU-Tests und Spieltests getrennt berichten.

Vor Ende der Arbeit diese Datei und HANDOFF.md aktualisieren und dem Nutzer
einen kopierbaren Fortsetzungs-Prompt geben. Nicht lediglich einen Plan liefern:
Änderungen direkt umsetzen und verfügbare passende Prüfungen ausführen.
