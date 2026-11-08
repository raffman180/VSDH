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
