# Fortsetzungs-Prompt — VSDH 0.16

## Aktuelle Arbeit

0.16 liefert `gl_MultiTexCoord1` und `gl_MultiTexCoord2` als dieselbe
Lichtmap-Eingabe im Vulkan-Terrainformat. NativeShaderChain unterstützt
programmweite Blend-Direktiven in den Vollbildphasen vor Final. Der
Diagnoselauf verify-0.16-compat-diagnostic-4.log prüft beide Lichtwerte
in vier Terrainvarianten, additive Mischung mit dem vorhandenen Puffer
und eine ungemischte RGBA8UI-Ausgabe im selben Pass wie ein Float-Ziel.
Den Spieltest der gebauten JAR und den Archiv-Neuaufbau im Bericht unter
dist/0.16.0-dev prüfen. Der frühere Stand liegt weiter unter dist/0.15.0-dev.

0.15 korrigiert die sichtbar falsche Nahwasser-Normale. Die
Originalpack-Vergleiche in packaged-water-pbuffer.log und
iris-water-pbuffer.log zeigten die Abweichung in colortex0 vor Composite.
Die anschließende lokale Diagnosekopie las Materialfarbe, Beleuchtung,
Reflexion und Normale separat. Bei VSDH war die Normale nach unten
gerichtet, bei Iris nach oben. Der A/B-Test unter
build/reference/release-client/packaged-water-trace-ccw.log belegt, dass
die Rückseitenausblendung die sichtbare Normale korrigiert und die
Beleuchtung dicht an die Iris-Referenz bringt.

WorldRenderer belässt jetzt bei aktivem Shaderpfad die normale
Minecraft-Cull-Einstellung für das transparente Terrain. Das gilt
bei eingeschalteter Rückseitenausblendung in den Vulkan-Einstellungen.
WaterFaceProbe in verify-0.15-water-diagnostic.log liest tatsächliche
Flächennormalen der Testszene. Weitere Spielbelege im Prüfbericht.
Testpack-Kopie und Diagnose-Mixins unter build/reference bleiben lokal.


0.14.0-dev war der erste Entwicklungsstand mit normalem Shaderstart.
Quell-Commit 284960f. dist/0.14.0-dev enthält die exakt im isolierten Profil getestete
JAR, Quellarchiv, Prüfsummen, Prüfbericht und Testlogs. 60 CPU-Tests,
build, verifyBundledLwjgl und Neuaufbau aus entpacktem Quellarchiv
bestehen. Die gebaute JAR wurde im getrennten NeoForge-Profil bei
2560×1440 mit aktiven Terrain-, Fernterrain- und Fernwasser-Draws geprüft.

Die experimentelle Shader-Auswahl erfolgt über config/vsdh.json.
ShaderPackStartup bereitet Pack und WorldPackSet asynchron vor und
installiert im Menü auf dem Renderthread. Ein leerer Name wählt den
Standardmodus. Die ZIP-Datei bleibt extern. verify-0.14-rejected-pack-2.log
prüft die Pfadablehnung. verify-0.14-configured-client.log bestätigt
Aktivierung, Oberwelt → Nether → Ende → Oberwelt, vorhandene Terrainpuffer,
zwei Rendergrößen, Partikel und Wetter sowie eine anschließende Welt ohne
Shader. Ein ungültiges Pack oder fehlende Daten immer aus dem Log klären.

Offen bleiben vollständige Entitäts-/Hand-Pässe, weitere Shaderpacks und
DH-Versionen. Die Nahwasser-Farbabweichung aus 0.14 ist oben eingegrenzt. Der Vergleich benutzt
Iris 1.8.12, Sodium 0.6.13, DH 3.3.3 und in beiden Fällen MEDIUM. Die
isolierten Projekte build/reference/iris-client und release-client
enthalten Wasserproben vor Composite, die in beiden Renderern denselben
Bildbereich aus colortex0 und colortex3 lesen. Prüfe die aktuellen Logs
packaged-water-pbuffer.log und iris-water-pbuffer.log auf die Befunde.

Für Entitäten liefert verify-entity-contract-2.log den SPIR-V-Audit von
Complementary MEDIUM. Aktive Eingaben enthalten echte EntityIds und
MidTexCoord. Tangenten sind im geprüften MEDIUM-Programm optimiert, können
bei anderen Einstellungen aktiv sein. Das übernommene ENTITY-Profil bindet
DynamicTransforms und of_CameraBob aus der 26.x-Vitrail-Umgebung.
Für 1.21.1 die tatsächlichen Draw-Transformationen und Overlaytextur
binden, feste Attributorte behalten und die IDs beim Meshaufbau erfassen,
damit verzögerte Minecraft-Batches den Entitätskontext behalten. Kein
Entitäts- oder Handprogramm als unterstützt ausgeben, bevor echte
Geometrie, IDs und Materialwerte im Spiel geprüft sind.


0.13 bereitet alle drei Vanilla-Dimensionen in WorldPackSet vor und tauscht
den aktiven Plan bei LevelRenderer.setLevel. Die Materialtabellen müssen
gleich sein, damit der Terrainstride und die Chunk-Builder beim Wechsel
gültig bleiben. Jede Welt erhält neue Vulkan-Ziele und frische Historie.
60 CPU-Tests, Build und Audit stehen in verify-0.13-build.log. Den
Spielstatus in verify-0.13-dimensions-client.log nachsehen. Der Lauf verwendet
-PsmokeDimensions=true zusätzlich zum echten Pack und DH.

0.12 ist mit Diagnose- und Originalpack, Weltwechsel, 59 Tests und einem
Neuaufbau des Quellarchivs geprüft. Commit d5a7670. Artefakte liegen unter
dist/0.12.0-dev. Regen und beide Partikelphasen bestehen im Originallauf.

Beim direkten MEDIUM-Vergleich unter Iris ist der Detailübergang auch
vorhanden. Das Nahwasser ist in VSDH messbar dunkler. Das Fernwasser ist
in den Stichproben nahezu gleich hell. Details und Prüfarchiv in
docs/DISTANT_PACK.md. Vergleiche die Near-GBuffer-Ausgaben und Reflexionen,
bevor die Pack-Mischung oder die DH-Tiefe weiter geändert wird.

Nächster Schritt nach einem erfolgreichen Dimensionslauf ist eine explizit
experimentelle Auswahl beim normalen Start. Packaufbereitung auf einem
Worker, Installation auf dem Renderthread bei ungeladener Welt und ein
Rückfall auf den Standardmodus bei fehlerhaften Packdateien sind Pflicht.
Entitäten und Pack-Hand bleiben eigene Geometriearbeiten.


0.11.0-dev ist ein vollständig geprüfter Zwischenstand. Commit
98c6537. Unter dist/0.11.0-dev liegen JAR, Quellarchiv, Prüfsummen und
verification.json. verify-0.11-complementary-client.log belegt Himmelskreis,
Sonne, Mond, Sterne, MC-Tiefe und beide DH-Tiefen bei 1280×720 und 480×270.
Der Diagnose-Test las den korrekten Himmelsfarbwert auf der GPU. 59 Tests
und der Offline-Neubau aus dem Quellarchiv bestanden.

0.12 ergänzt ParticlePackPlan und die gemeinsame
NativeGeometryPass für vorhandene Minecraft-Meshes. WorldShaderParticleMixin
kapselt die NeoForge-1.21.1-Aufrufe für Partikel vor und nach Deferred sowie
Regen und Schnee. auditWorldHost meldet je drei übersetzte Partikel- und
Wettervarianten und keine fehlenden aktiven Uniforms für Complementary.
Der Diagnoseclient in verify-0.12-diagnostic-3.log las 26.513 tatsächliche
Regenpixel aus RGBA16F und zählte 845 frühe und 279 späte Partikel-Draws.
Der Lauf mit dem Originalpack wird in verify-0.12-complementary-client.log
festgehalten. Prüfe Abschluss und PASS-Marker vor weiteren Änderungen.

Iris 1.8.12 und Sodium 0.6.13 wurden für die Wasserdiagnose in einem
isolierten Vergleichsclient unter build/reference/iris-client mit DH 3.3.3
eingerichtet. iris-reference-medium.log bestätigt MEDIUM ohne zusätzliche
Optionen. Der Prüfbericht enthält beide Ausschnitte und deren RGB-Mittelwerte. Quellordner build/reference,
fremde JARs und Shader-ZIP bleiben aus den VSDH-Artefakten heraus.

## Stand 0.11 am Vulkan-Renderer

0.11 ergänzt gbuffers_skybasic und gbuffers_skytextured über die
Minecraft-1.21.1-Aufrufe renderSky und renderEndSky. SkyPackPlan trennt
CPU-Übersetzung von Minecraft-Klassen. NativeSkyPass verwendet das
bereits hochgeladene VBO, feste Vulkan-Attributorte, die Pack-Mischregeln
und WorldUniforms.writeSky mit tatsächlichen Draw-Matrizen und Draw-Farbe.
Die Kamerahistorie bleibt getrennt. Testlauf verify-sky-world-1.log
zeichnet den Himmelskreis, Sonne, Mond und Sterne mit Complementary.
WORLD_SKY.md beschreibt die Umsetzung. Neuere Build- und Spielresultate
im letzten Prüfbericht und in den verify-0.11-Logs lesen.

GameRenderer.renderLevel löscht unmittelbar vor der Handzeichnung die
Welttiefe. WorldShaderGameMixin schließt Composite/Final jetzt vor diesem
Clear ab. RETURN bleibt für Frames ohne Hand-Aufruf. Die Vanilla-Hand
wird als Overlay nach Final gerendert. NearWorldDepthProbe prüft die
tatsächlich gefüllte Tiefe auf der GPU. Erst ein echter Pack-Handpass
macht depthtex1 und depthtex2 zeitlich verschieden.

Als Nächstes den sichtbaren Wasserübergang mit der korrigierten Tiefe
erneut messen. Für Wetter und Partikel nutzen LevelRenderer.renderSnowAndRain
und ParticleEngine.render bei NeoForge 1.21.1 denselben PARTICLE-Vertexaufbau,
aber unterschiedliche Texturen und Phasen vor bzw. nach Deferred.
Danach Entitäten und Hand, dimensionenspezifische Laufzeit und Auswahl.
Keine öffentliche Zusage für ungeprüfte Kombinationen.

## Stand 0.10 an DH

0.10.0-dev zeichnet mit -PsmokeLivePack=true -PsmokeDistantPack=true die
originalen Programme dh_terrain und dh_water aus Complementary Reimagined
r5.9.3 MEDIUM. verify-0.10-dh-client.log belegt GPU-Rücklesen beider
DH-Tiefenbilder bei 1280×720 und 480×270 sowie die Mischregeln pro Vulkan-Ziel.
Der sichtbare Übergang von Nah- zu Fernwasser bleibt offen. Details zu
Mischregeln und Tiefenvertrag stehen in docs/DISTANT_PACK.md.

NativeDistantTerrain sammelt die bestehenden DH-3.3.3-Vulkan-Puffer ohne
neuen Upload. Opak zeichnet vor dem Minecraft-Terrain. Frühe Wasserbefehle
werden erst nach Deferred ausgeführt. dhDepthTex1 enthält die opake Kopie.
DH-Wasser muss diese Kopie auch lesen, wenn es sie dhDepthTex nennt, um
Feedback auf der Vulkan-Tiefenanlage zu vermeiden. Außerhalb der DH-Pässe
bleibt dhDepthTex die vollständige DH-Tiefe. verify-dh-world-1.log belegt
den verhinderten Feedback-Fehler. Die Kamera-Tiefe von 0.9 und DHs eigene
GL-Projektion sind verschiedene Verträge. DHs Projektion nicht doppelt
nach GL konvertieren. DISTANT_HORIZONS ist pro Pack-Lesung gesetzt und
muss bei Expansion, finalem GLSL und Cache-Schlüssel identisch sein.

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
