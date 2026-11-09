# Nächster Schritt nach 0.5: Complementary-Weltwerte und Ressourcen

Die eigenen nativen Welt-Shader laufen auf tatsächlichen Chunks samt bisherigem
DH-Composite. Noch kein vollständiges Complementary. Der eigene Reforged-Fork ist
bereits beauftragt; diese Entscheidung nicht erneut abfragen.

1. docs/verification/0.5-complementary-requirements.json auswerten. Die 59 fehlenden
   Weltwerte je Dimension durch echte Provider und den Ausdrucksgraphen aus
   shaders.properties liefern. Keine Pflichtdaten durch Null-/Identitätswerte ersetzen.
2. Benötigte Sampler, Schattenkamera/-geometrie und passende Packzustände anbinden.
   Verfügbare Programme für Himmel, Entitäten, Hand und andere Familien auswählen.
   Derzeitige Vanilla-Familien schreiben lediglich in das gemeinsame Szenenbild.
3. DH-Terrain/-Wasser wirklich durch die Packprogramme und gemeinsame Tiefenführung
   zeichnen. Die bestehende DH-Composite-Anbindung ist dafür kein Ersatz.

Erst nach überprüfter vollständiger Weltgrafik Pack-/Profilwahl und Hotreload
freigeben. Weitere Packs, DH-Versionen und Hardware getrennt prüfen. Verlässliche
Wiedereinstiegspunkte: WORLD_SHADER_HOST.md, HANDOFF.md, NEXT_AI_PROMPT.md.
