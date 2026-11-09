# Verifizierte technische Befunde

## DH 3.3.3 – Abschaltreihenfolge

Referenz: die in `build.gradle` mit SHA-1 gebundene originale DH-JAR. Private
Klassenanalyse außerhalb dieses Repositorys; keine dekompilierten Dateien im Paket.

`FullDataSourceProviderV2.close()` schließt Updater, Propagator und Migrator und
anschließend das Repository. `FullDataUpdatePropagatorV2.close()` ruft lediglich
`shutdownNow()` für `updateQueueProcessor` und `regenQueueingThread` auf. Ein bereits
laufender Datenbankzugriff kann deshalb die Repository-Schließung überlappen.
Der Fehler ist in `run-mrt.log` bei beiden Weltentladungen mit
`FullDataSourceV2Repo.getChildPositionsToRegen` im Stack beobachtet worden.

Die gezielte Ergänzung wartet nach der bestehenden Abbruchanforderung begrenzt auf
die tatsächliche Beendigung dieser beiden Worker. Sie verändert weder Datenbank-
Inhalte noch das normale Rendern. Bei überschrittener Frist wird die Schließung
nicht als erfolgreich behandelt. Nachprüfung im Spiel ist getrennt zu dokumentieren.

## Vulkan-Shader

Vier MRT-Ausgaben und ein reiner Tiefendurchlauf wurden mit GPU-Rücklesen und
Pixelassertionen geprüft; jeweils vor/nach Resize in zwei isolierten Welten.
`run-mrt.log`: vier `MRT GPU PASS`, erfolgreicher Prozessabschluss, aber die oben
beschriebenen DH-SQL-Fehler. Kein Anspruch eines fehlerfreien Gesamtlaufs.

Complementary Reimagined r5.9.3, Profil MEDIUM: 96 Programmpaare in drei Dimensionen
zu SPIR-V kompiliert. Das ist eine Compilerprüfung und kein laufender Shaderpack-
Renderer. Nicht implementierte Vitrail-26.x-Funktionsmeldungen wurden entfernt.

## Verifizierte Korrektur und native Pack-Prüfung

`run-native-pass.log`: reine Warteergänzung fehlgeschlagen; Thread-Dump zeigte
Regen-Worker in `dh_sqlite.core.NativeDB.step`, weiterhin hohe CPU-Zeit.
Der konkrete JDBC-Treiber verwendet `jdbc:dh_sqlite:`; sein `NativeDB.interrupt`
ist kein synchronisierter Java-Aufruf. `PreparedStatement.cancel()` beendet die
Abfrage über diesen nativen Pfad. Ein eigenständiger RAM-Datenbanktest mit dem
echten DH-Treiber bestätigt den Abbruch vor Verbindungsfreigabe.

`run-native-pass-cancel.log` und `run-complementary-final.log`: je zwei reguläre
Weltentladungen, alle GPU-Assert-Gruppen bestanden, keine ERROR/FATAL-Zeilen.
Die gezielte Behandlung akzeptiert ausschließlich SQLITE_INTERRUPT (Code 9),
wenn der zugehörige Regenerationsleser selbst eine Abbruchanforderung hat.
Keine Repository-Schreiboperationen werden abgefangen oder unterdrückt.

Der echte Complementary-`world0/final` wurde viermal mit kontrolliertem HDR-Eingang
und Rauschwert ausgeführt. Sein mittlerer Ausgabepixel entspricht dem erwarteten
Dithering-Ergebnis. Das bestätigt Übersetzung, Bindings, Uniformlayout und native
Ausführung dieses Programms. Material-/Schatten-/Welt-Integration bleiben offen.

## 09.11.2026 — Shader-Kette

Die vollständige kontrollierte Diagnosekette läuft über BEGIN/PREPARE/DEFERRED/
COMPOSITE/FINAL, einschließlich gespeicherter Werte über vier Frames, verkleinerten
Puffern, HDR-Mipmaps und dem Pfad ohne FINAL. Die zuerst inline geschriebenen
Mipmap-Direktiven wurden nicht ausgewertet; der neue CPU-Test entdeckte dies.
Das Diagnosepack verwendet nun mehrzeilige Direktiven und ein Schachbrettmuster,
dessen Mittelwert tatsächlich im Mipmap-Bild geprüft wird.

Die echte Complementary-Konstruktion scheiterte zunächst in
Pipeline.createDescriptorSetLayout mit IndexOutOfBoundsException. shaderc hatte
inaktive Sampler entfernt, die Bindungsnummern der übrigen aber beibehalten.
Das Layout ist nun kompakt aufgebaut; seine Einträge tragen die unveränderten
Bindungsnummern. Der Regressionstest prüft aktive SPIR-V-Bindungen {0,2}, nachdem
Binding 1 eines unbenutzten Shadow-Samplers entfernt wurde.

NativeWorldDepth behält das Originalbild beim Schließen bei und kopiert D32/
D24S8/D32S8. Die Prüfwerte .25/.5/.75 und unterschiedliche Stencilwerte vor Hand,
Transparenz und danach bleiben in den korrekten Snapshots erhalten.

Parsec wurde auf ausdrücklichen Nutzerwunsch gestartet; Prozess und Fenstername
Parsec wurden beobachtet. Es wurde keine Remote-Verbindung oder Kontoeinstellung
bedient. Kein direkter Eingriff in andere Anwendungen während der Spieltests.
