# Welt-Renderkette — 0.5.0-dev

Minecraft 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.3.3. Der eigene
Diagnosepack wird erstmals auf echten Welt-Chunks ausgeführt. Das ist keine
vollständige Complementary-Unterstützung und kein freigegebener Shader-Auswahldialog.
Der normale Modus bleibt standardmäßig aktiv; nur der Smoke-Test installiert die
WorldShaderSession ausdrücklich vor dem Weltstart.

## Datenfluss

WorldPackPlan bereitet die Vollbildkette, fünf Terrainvarianten einschließlich
Alpha-Tests und die Packmaterialien auf einem Worker vor. Unbekannte Weltwerte
und Ressourcen werden vor Aktivierung abgewiesen. ZIP-Pfade bleiben nicht erhalten.

WorldShaderSession umschließt die vorhandene MainPass-Schnittstelle während der
Weltzeichnung. Beginn ist nach Auswahl der aktuellen Nebelfarbe und vor dem
Löschen des Weltbildes. BEGIN/PREPARE laufen vor der Welt; opakes Terrain schreibt
über NativeTerrainPass in die geplanten MRTs. Vor Transparenz folgen Tiefenkopien
und DEFERRED; COMPOSITE/FINAL nach Welt/DH/Hand und vor der Oberfläche. FINAL
schreibt in das echte vorherige Hauptziel. Übrige Vanilla-Zeichnungen und der
bisherige DH-Composite verwenden im aktiven Frame das gemeinsame Szenenbild.

WorldRenderer teilt seinen tatsächlichen direkten/indirekten Draw-Walk zwischen
normalem und Shaderpfad. Es werden keine Ersatz-Chunks erzeugt. Die bestehende
Sichtbarkeit, Pufferverwaltung und Transparenzsortierung bleiben erhalten.

WorldMatrices übernimmt Kameraansicht und die Minecraft-Projektion. Ihre Y-Zeile
wird für die nativ abgetasteten Bilder umgekehrt; die GL-Tiefenkonvention bleibt
bis zur bestehenden Vertex-Epilog-Konvertierung erhalten. Projektion/Inversen und
vorige Kamera/Projektion werden nur nach einem erfolgreichen Shaderframe erneuert.
Weltwechsel, Resize und große Kamerasprünge setzen die Historie zurück. Die im
festgelegten Reforged-Fork unendliche Far-Projektion ist gesondert geprüft.

WorldUniforms liefert reale Grundwerte, keine erfundenen Pflichtdaten. Nicht
endliche Kamera-/Projektionsdaten beim Weltstart lassen einen Shaderframe aus:
das normale Renderziel bleibt aktiv und die Shaderhistorie schreitet nicht fort.
Im Spiel trat dabei eine anfangs nicht endliche Minecraft-Projektion auf;
verify-0.5-client.log protokolliert den gezielten Rückfall als Warnung.

## Gefundene Fehler und Korrekturen

- Die erste Diagnose-Fassung schrieb Formatnamen außerhalb des Konfigurations-
  Kommentars. Die GLSL-Fehler sind in verify-world-client.log dokumentiert;
  WorldPackPlanTest kompiliert den vollständigen Diagnosepack jetzt vor dem Spiel.
- Der Übersetzer ergänzte of_TexShrink auch für das Reforged-Format. Dieser Wert
  gehört ausschließlich zum Sodium-Vertrag und wird dort weiter bedient.
- DH-Compat erzwang DefaultMainPass und die Swapchain. Rebind verwendet jetzt die
  MainPass-Schnittstelle; Tiefenbild/Abmessungen stammen aus dem aktuellen Ziel.
- Bei RenderScale muss der Viewport in logischen Koordinaten angegeben werden.
  Bereits verkleinerte Maße würden ein zweites Mal verkleinert. Der neue Host
  berücksichtigt dies und wird bei 100% und 50% geprüft.
- Eine bloße Alpha-Prüfung im Normalenpuffer konnte dessen Löschfarbe mitzählen.
  Der Test prüft jetzt die Länge der dekodierten Normalen und zusätzlich einen
  FINAL-Kontrollpixel im tatsächlichen Ausgabebild. Keine synthetische Szene als
  Beleg echter Weltgeometrie ausgeben.

## Tatsächliche Prüfungen

28 CPU-Tests, einschließlich Projektions-Rundlauf, Historie/Resize/Teleport,
unendlicher Far-Projektion und realem SPIR-V-Discard nur für Cutout-Varianten.
verify-0.5-client.log: zwei Welten, Resize, Nacht/Regen, Standardmodus nach
Shader-Deinstallation und sechs bisherige GPU-Gruppen je viermal bestanden.
WORLD SHADER HOST PASS belegt 31.347 reale Normalenpixel bei 1280x720/100%
und 4.409 bei 480x270/50%, jeweils mit FINAL-Kontrollpixel im echten Hauptziel.
Die temporäre RenderScale-Einstellung wird vor Welt 2 wiederhergestellt.
Keine ERROR-/FATAL-Zeilen. Kein kontrollierter FPS-Vergleich.

## Verbleibende Grenze

Der Diagnosepack ist eigenes Testmaterial, nicht Complementary. Der Audit
`auditWorldHost` übersetzt die fünf Terrainvarianten von Complementary r5.9.3
MEDIUM in allen drei Dimensionen und meldet noch 59 unbediente Weltwerte pro
Dimension. Aufstellung: verification/0.5-complementary-requirements.json.
Dazu fehlen Schatten-/Noise-/weitere Texturen und die vollständigen Packprogramme
für Himmel, Entitäten, Hand sowie DH-Terrain/-Wasser. Hand-Reihenfolge ist noch
Vanilla; depthtex1/2 sind am Prä-Transparenz-Punkt derzeit gleich. Die übrigen
Vanilla-Familien werden bisher nur in das Szenenbild umgeleitet. Benutzerdefinierte
Uniform-Ausdrücke, Pack-Blend-/Cull-Regeln, andere Dimensionen im aktiven Host,
UI-Auswahl und Hotreload sind noch keine zugesagten Funktionen.

Der aktuelle Host darf vor dieser Vervollständigung nicht als vollständiger
Complementary-Renderer aktiviert oder beworben werden. Keine universelle
Kompatibilität mit sämtlichen Packs, DH-Versionen, Mods, GPUs oder Treibern.
