# Fortsetzungs-Prompt für die nächste KI

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
docs/WORLD_SHADER_HOST.md sowie docs/verification/0.5.0-dev.json, sofern vorhanden.
Prüfe anschließend git status und die neuesten Build-/Spielprotokolle. Es können
noch nicht versionierte Änderungen der laufenden Arbeit vorhanden sein. Nicht
blind zurücksetzen oder schon geprüfte Teile erneut entwickeln.

Branch: codex/renderer-shaders. Letzter Ausgangs-Commit vor 0.5 war
473115c2963373c0f23918e97a85cf8f68a2ac0c (0.4). Den aktuellsten Commit selbst prüfen.
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

Konkreter nächster Schritt: `build/world-host-audit.json` auswerten.
`auditWorldHost` meldet für Complementary derzeit 59 fehlende Weltwerte je
Dimension sowie benötigte Schatten-/Noise-/weitere Texturen. Implementiere echte
Werte und die benutzerdefinierten Uniform-Ausdrücke aus shaders.properties;
keine Null-/Identitätswerte als Ersatz für fehlende Pflichtdaten einsetzen.
Danach Schattenkamera/-geometrie und native Pack-Ausgabe für DH-Terrain/Wasser
anschließen, gefolgt von den übrigen Geometriefamilien. Erst nach Prüfung eine
vollständige Pack-Auswahl und Hotreload freigeben. Der Diagnosepack ist kein
Nachweis vollständiger Complementary-Unterstützung.

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
