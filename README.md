# NovaBird Mail für Android

NovaBird Mail ist ein Fork von [Thunderbird für Android](https://github.com/thunderbird/thunderbird-android)
(Stand: Thunderbird 25.0b2). Er bringt die Funktionen des NovaBird-Add-ons für Thunderbird am PC aufs Handy und
ergänzt Thunderbird um einige Punkte, die uns im Alltag gefehlt haben.

Die Suche, der Phishing-Schutz und die KI-Texte nutzen denselben Kern wie das Add-on am PC. Rechenintensive Aufgaben
übernimmt auf Wunsch der **NovaBird Helper** auf dem PC: Vektoren für die Suche, Zusammenfassungen und Vorlesen.
Alles läuft im eigenen Netz, eine Cloud wird nicht verwendet.

> **Beta.** NovaBird Mail ist noch in Entwicklung. Es läuft auf Android 14 und neuer (64 Bit).

<table>
  <tr>
    <td><img src="docs/novabird/screenshots/search.png" width="220" alt="Suche in Alltagssprache mit Chips"></td>
    <td><img src="docs/novabird/screenshots/phishing.png" width="220" alt="Phishing-Warnung mit Begründung"></td>
    <td><img src="docs/novabird/screenshots/inbox.png" width="220" alt="Posteingang mit Schnellfiltern"></td>
  </tr>
  <tr>
    <td align="center">Suche in Alltagssprache</td>
    <td align="center">Phishing-Warnung</td>
    <td align="center">Posteingang</td>
  </tr>
</table>

## Was NovaBird gegenüber Thunderbird ändert

Sortiert nach Wichtigkeit.

1. **Suche in Alltagssprache.** „von Petra im März mit PDF“ wird zu Chips für Absender, Zeitraum und Anhang.
   Ein Chip lässt sich mit einem Tipp entfernen, seine Wörter verschwinden dann auch aus dem Suchfeld. Der eigene
   Suchindex auf dem Handy findet auch Text im Inhalt und in Anhangnamen, ohne Server.
2. **Semantische Suche.** Findet Mails nach ihrer Bedeutung, auch ohne das genaue Wort, etwa „Kosten“ für
   Rechnungen. Die Vektoren rechnet der NovaBird Helper auf der Grafikkarte des PCs, rund 50-mal schneller als das
   Handy, und teilt sie mit allen Handys der Gruppe. Ohne Helper rechnet das Handy selbst, auf Wunsch nur beim Laden.
   Die Chips filtern auch diese Treffer.
3. **Phishing-Schutz.** Prüft Absender, Links, Anhänge und Formulierungen neuer und geöffneter Mails nach Regeln.
   Er liest auch QR-Codes und Text in Bildern, etwa ein gefälschtes Bank-Logo als Link. Das Ergebnis erscheint als
   Banner mit Begründung in der Mail und als Symbol in der Liste. Links in verdächtigen Mails fragen vor dem Öffnen.
   Im Graubereich darf das Sprachmodell des Helpers eine Warnung verschärfen, aber nie abschwächen. Die Prüfung
   läuft auf dem Handy.
4. **Spam-Erkennung.** Feste Regeln, mit Listen für gesperrte und erlaubte Absender. Kontakte und Leute, denen du
   geschrieben hast, gelten nie als Spam. „Als Spam markieren“ sperrt den Absender.
5. **KI über den Helper.** Zusammenfassung einer Mail, „Was gibt's Neues?“ über die neuen Mails und Vorlesen mit
   der Stimme des Helpers. Ist der Helper nicht erreichbar, liest die Stimme des Handys.
6. **Ganzes Postfach am Stück laden.** Wahlweise den Posteingang oder alle Ordner, nur im WLAN, mit
   Fortschrittsanzeige, auch bei ausgeschaltetem Bildschirm. Startet nach dem Einrichten eines Kontos von selbst.
   Außerdem lädt „Alle Nachrichten“ jetzt wirklich alle Nachrichten. Bei Thunderbird waren es rund 100 pro Ordner.
7. **Übernahme vom PC.** Konten lassen sich per QR-Code aus Thunderbird am PC übernehmen. Danach stellst du auf
   einer Seite pro Konto Zeitraum, Anzahl, Benachrichtigungen, Push und Hintergrundaktivität ein.
8. **Sync-Grenzen.** Eigene Grenzen für die Mailgröße im mobilen Netz und im WLAN, statt einer festen Größe pro
   Konto.
9. **Einrichtungsassistent.** Fragt nach Zeitraum (bis zu zwei Jahre oder alles), Anzahl, Push für den Posteingang
   und Hintergrundaktivität, damit neue Mails auch nach längerer Pause ankommen. Die Anmeldung bei Thundermail ist
   ausgeblendet.
10. **Editor mit Formatierung.** Fett, kursiv, Listen, Überschriften, Zitat, Schriftart und Schriftgröße.
11. **Oberfläche.** NovaBird-Design, Schnellfilter „Alle“ und „Ungelesen“, Konto-Chips über der Liste und
    Absender-Logos (BIMI) von der Domain des Absenders.
12. **Kleinere Korrekturen.** Zeichensatz von Mailteilen aus dem Content-Type-Header und Seite „Über“ mit Autoren
    und Lizenzen. Unterstützt werden nur Android-Versionen, die noch Sicherheitsupdates bekommen.

<table>
  <tr>
    <td><img src="docs/novabird/screenshots/phishing-settings.png" width="220" alt="Einstellungen Phishing-Schutz"></td>
    <td><img src="docs/novabird/screenshots/ai-settings.png" width="220" alt="Vektorsuche und KI, Kopplung mit dem Helper"></td>
    <td><img src="docs/novabird/screenshots/editor.png" width="220" alt="Editor mit Formatierung"></td>
  </tr>
  <tr>
    <td align="center">Phishing-Schutz</td>
    <td align="center">Helper koppeln</td>
    <td align="center">Formatierung</td>
  </tr>
</table>

## Zusammenspiel mit dem PC

- **NovaBird-Add-on** für Thunderbird am PC: [Novabird-Addons/NovaBird](https://github.com/Novabird-Addons/NovaBird).
  Suche, Phishing-Schutz und KI-Texte verhalten sich auf dem Handy wie dort.
- **NovaBird Helper**: läuft auf dem PC, rechnet Vektoren, Zusammenfassungen und Vorlesen und wird im Heimnetz
  gekoppelt. Die Verbindung ist verschlüsselt.

## Bauen

Der Code liegt im Zweig `novabird/main`. Die App ist das Modul `app-novabird`, die NovaBird-Funktionen liegen unter
`feature/novabird/`.

```bash
./gradlew :app-novabird:assembleFossRelease
```

## Lizenz und Dank

NovaBird Mail steht wie Thunderbird für Android unter der [Apache-Lizenz 2.0](LICENSE). Dank an das Thunderbird-Team
und alle, die zu K-9 Mail und Thunderbird für Android beigetragen haben. Die Lizenzen der verwendeten Bibliotheken
stehen in der App unter „Über“.
