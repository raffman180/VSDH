# Fortsetzungs-Prompt — VSDH 0.9

Arbeite direkt unter C:/Users/Stranger/Documents/Meine Mods/VSDH weiter, auf Deutsch.
Der Nutzer verlangt die Vervollständigung von Vulkan + Shadern + Distant Horizons.
Das Gesamtziel ist noch NICHT erreicht. Keine erneute Grundsatzfrage Addon/Fork,
keine Zusage perfekter Unterstützung sämtlicher Packs oder DH-Versionen.

Lies AGENTS.md, docs/HANDOFF.md, docs/WORLD_SHADOWS.md und den neuesten Bericht
unter docs/verification/. Prüfe git status, git log und die aktuellen Logs, bevor
Änderungen erfolgen. Branch: codex/renderer-shaders. Ausgangs-Commit vor der
Schatten-/Texturarbeit: 4bb9292. Neueren Commit selbst ermitteln; nicht zurücksetzen.

Zielversionen: Minecraft 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.3.3.
Erstes festgelegtes Pack: Complementary Reimagined r5.9.3 MEDIUM, Original-SHA1
838139b54cddb56b2e83cd260d8efd960ac536d6. RTX 4070 unter Windows wurde geprüft.
Die eine VSDH-JAR enthält vsdh und vulkanmod. Original-Reforged, Iris und Sodium
nicht zusätzlich installieren. Die Shaderfunktionen werden nativ implementiert.

Was jetzt tatsächlich vorhanden ist:
- Echter 44-Byte-Chunk-Vertexpfad samt Block-/Fluidmaterial, Normalen/Tangenten,
  AO/Licht und Indexed Draws; Standardmodus nutzt weiter 20 Byte.
- NativeShaderChain mit begin/prepare/deferred/composite/final, MRTs, HDR/Mips,
  Frame-Historie und Tiefenkopien; WorldShaderSession als expliziter MainPass-Host.
- Aktive Uniform-Member werden aus optimiertem SPIR-V über std140-Byteoffsets
  erkannt. Unklare Pointerpfade bleiben konservativ. Keine Null-Pflichtdaten.
- Echte Welt-/Kamera-/Augen-/Effektwerte, Hand-IDs aus item.properties inklusive
  Block-State-Licht, Biome, Wetness und LightTexture-Darkness-Erfassung.
- PackExpressions nutzt portablen Vitrail/StarEval-Code mit kleinen VSDH-Adaptern.
  Formeln und Abhängigkeiten werden einmal pro Frame ausgewertet, Glättung ist
  sitzungseigen. Fehlende/zyklische Formeln bleiben diagnostiziert; NaN/Inf wird
  abgewiesen. Nicht aktive End-Flash-Ausdrücke neuerer MC-Versionen bleiben offen.
- NativeWorldShadows zeichnet tatsächlich hochgeladene Chunks, auch außerhalb des
  Spielerblicks, über separate Queues. shadowtex1 ist opak, shadowtex0 enthält
  Transparenz; Schattenfarben und Sonnen-/Mondmatrizen sind echt. Noch keine
  Schatten für Entitäten/Blockentitäten/DH. Alle geladenen Chunks werden derzeit
  konservativ gezeichnet; Schatten-Depth-Mips/Perspektivkameras fehlen.
- PackTexturePlan dekodiert PNGs auf dem Worker, erhält rohe RGBA-/Graustufenbytes
  und behält keine ZIP-Handles. NativeWorldTextures lädt über Staging hoch.
  Stage-Texturersetzungen und Buffer-Flips werden respektiert. Echte GBuffer-
  Eingaben im Terrain folgen dem Deferred-Punkt. Raw-/16-Bit-PNG und weitere
  Minecraft-Ressourcen außer dem geliehenen Blockatlas fehlen.
- entityId=0 gilt ausdrücklich nur im Terrain-/Terrain-Schattenkontext (keine
  Entity). Das ersetzt keine echten IDs der noch fehlenden Geometriefamilien.

Sehr wichtiger gefundener Projektionsfehler, nicht erneut einbauen:
Reforgeds Matrix4fM erzwingt bei perspective/setPerspective ohne boolean Vulkan-
Tiefe 0..1. Der alte Host behandelte diese Matrix irrtümlich als GL -1..1.
ForwardDepthProjection.toLegacy verwendet jetzt rowZ = 2*rowZ - rowW vor der
Veröffentlichung an Shader. Die Vertex-Epilog-Konvertierung .5z+.5w erfolgt genau
EINMAL. GameRendererMixin behält die unendliche Fernprojektion nur im Standardmodus;
bei installiertem Shaderhost gilt die tatsächliche Minecraft-Fernweite
getRenderDistance()*4. Die JOML-Infinity-Epsilon-Projektion rekonstruiert bei
Sky-Tiefe 1 Punkte hinter der Kamera. Dies ließ Complementary Wolken über Wasser
statt im Himmel berechnen. Native GPU-Instrumentierung belegte vorher Blick-Y
+0.871, danach korrekt -0.871 bei nach unten blickender Kamera. Wolkenhöhe 192
war stets korrekt. Legacy-Matrix-Y wird für native Bilder als ZEILE umgekehrt.
Shader-Direktiven clouds=off/fast/fancy werden während der Sitzung beachtet, ohne
Benutzereinstellungen zu speichern. scene() stellt Vanilla-Fallback nur Ausgabe 0
bereit, auch wenn das echte gbuffers_basic mehrere MRTs beschreibt.

Nachweise:
- 56 CPU-Tests bestehen, einschließlich Packformeln, PNG-Rohwerte, Schattenkamera
  und reproduzierter Infinity-/Tiefenumrechnungsfehler.
- verify-0.7-client.log: Formeln/Handlicht/Regen in zwei Welten.
- verify-0.8-client.log und verify-0.9-client.log: echte Schatten und PNG-/Noise-
  Farbassertionen, alle bisherigen GPU-Gruppen je viermal, 100%/50% Scale.
- verify-realpack-world-7.log: tatsächliches Complementary auf realer Welt,
  Schatten, Packtexturen, Resize/Scale und Rückwechsel in den Standardmodus.
  REAL PACK CLOUD GPU VALUES prüft die tatsächliche Shader-Blickrichtung.
- verify-0.9-final-client.log ist der vollständige Diagnose-Regressionslauf NACH
  der Tiefenkorrektur. Neuesten Build-/Archivbericht tatsächlich kontrollieren.
- Ältere realpack-world-2/3/4/6-Läufe hatten zwar nichtschwarze Bilder, aber den
  sichtbaren Wolkenfehler. Diese Marker NICHT als korrekte Packgrafik ausgeben.
  realpack-world-5 scheiterte an einer fehlenden Diagnose-Samplerbindung; behoben.
- NativeWorld-Code ist physisch Client-only. Dedicated Server und Multiplayer
  sind nicht im Spiel geprüft. Produktions-JAR nicht separat im Launcher getestet.

Was zur Vervollständigung konkret noch fehlt:
1. Shaderseitige DH-Integration: DISTANT_HORIZONS bleibt absichtlich UNDEFINIERT,
   bis echte dh_terrain/dh_water-Programme und getrennte DH-Tiefen/Matrizen laufen.
   Der bestehende native DH-Composite zeichnet spät ins Szenenbild. Das ist noch
   keine Pack-DH-Unterstützung. Im echten Packbild bleibt ein auffälliger heller
   Übergang im Wasser. Ursache zuerst gezielt z.B. mit/ohne DH vergleichen und
   anhand von Tiefen/Materialdaten nachweisen, nicht wieder auf Verdacht ändern.
2. Übrige Geometriefamilien: Himmel, Sonne/Mond, Entitäten, Blockentitäten, Partikel,
   Wetter und Hand laufen bisher über Vanilla-Fallback in Farbausgabe 0. Sie
   schreiben keine vollständigen Pack-Materialdaten. NativeGraphicsProgram bindet
   bislang FULLSCREEN oder VULKAN_TERRAIN. Für generische Draws die tatsächlichen
   Vanilla-Vertexformate und Matrizen aus VShaderInstance/Drawer/VBO übernehmen,
   scoped Entity-/Item-/BlockEntity-IDs aufbauen und echte Packprogramme wählen.
3. Pack-Blend-/Cull-Regeln pro Attachment, richtige Handreihenfolge und getrennte
   depthtex1/2-Zeitpunkte. Derzeit beide Kopien vor Transparenz. Weiterhin fehlen
   Dimensionwechsel im aktiven Host, Origin-Rebasing für float-cameraPosition,
   vollständige Ressourcen-/Featureabdeckung und danach Auswahl/Hotreload.
4. Compiler nutzt LocalZeroes derzeit noch nicht. Bei Nachweisen undefinierter
   Pack-Lokaler die vorhandene portable Implementierung mit GPU-Tests prüfen,
   nicht blind zuschalten. Direkt als Shaderuniform verwendete variable.-Einträge
   brauchen bei Bedarf noch Wurzeln aus aktiven Programmlesezugriffen.

Keine öffentliche vollständige Shader-Auswahl oder automatische Aktivierung als
fertig ausgeben. Nichtschwarze Pixel und Compiler-Erfolg beweisen keine komplette
Kompatibilität. Zwischenstände erhalten; keine alten dist-Verzeichnisse ersetzen.

Bauen: gradlew.bat build verifyBundledLwjgl
Audit: gradlew.bat auditWorldHost -PshaderPack=<absoluter Pack-ZIP-Pfad>
Diagnosewelt: gradlew.bat runSmokeClient -PsmokeShaderPack=<absoluter Pack-ZIP-Pfad>
Echtes Pack: zusätzlich -PsmokeLivePack=true
Alle Smoke-Änderungen betreffen frisch erstellte isolierte Welten. Bestehende Welten,
Inventare und gespeicherte Benutzereinstellungen schützen. GPU-Arbeit nur auf dem
Renderthread, Freigaben verzögert. Keine blockierende Dateioperation pro Frame.

Referenzen liegen privat außerhalb des Projekts unter
C:/Users/Stranger/Documents/ChatGPT/Vulkan Shader&DistantHorizons/.reference.
Pins und Lizenzen stehen in AGENTS.md, NOTICE und docs/upstream. Keine Original-
Packdateien, übersetzten Packquellen oder privaten dekompilierten Referenzen in
Lieferungen übernehmen. Vor dem Ende HANDOFF, Prüfbericht und diesen Prompt auf
den tatsächlich geprüften Stand bringen. Weiter implementieren und testen.