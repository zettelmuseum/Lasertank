LaserTank 4.1.2 (Jim Kindley) - Android/Java-7-Port fuer AIDE
==============================================================
1. Ordner "LaserTank" auf das Handy kopieren (z.B. nach /sdcard/AppProjects/).
2. AIDE > Open Project > Ordner waehlen > Run (Play-Button).

Bedienung
  Pfeiltasten  = drehen / fahren (gedrueckt halten = Dauerfahren)
  FIRE         = schiessen        UNDO = letzten Zug zurueck
  Spielfeld antippen = dorthin fahren (wie linke Maustaste)
  Spielfeld lang druecken = in diese Richtung schiessen (wie rechte Maustaste)
  Menue (oben rechts): Position speichern/abrufen, Anfang, Level <<, >>,
  Level oeffnen, Levelset waehlen, Tipp, Schwierigkeitsgrade, Initialen,
  Animation, Sound, Geloeste Level ueberspringen

Levelsets: LaserTank, Challenge-I .. Challenge-V (Menue > Levelset waehlen).
  Jedes Set merkt sich sein zuletzt gespieltes Level und seine eigenen High Scores.
  Neues Set: Name.lvl (+ optional Name.ghs) nach assets/ legen und den Namen in
  MainActivity.java im Array SETS eintragen.

High Scores
  Oben im Bild: "HS" = eigener Bestwert, "Global" = Bestwert aus der .ghs-Datei
  (Zuege/Schuesse Initialen) fuer das aktuelle Level.
  Eigene Rekorde werden pro Set in <Name>.hs gespeichert (Format wie im Original).

Dateien
  src/jk/lasertank/MainActivity.java   gesamter Code (Engine = 1:1 aus LTANK2.C / LTANK.C)
  assets/*.lvl, *.ghs                  Originallevel + globale High Scores
  assets/sprites.png                   Game.BMP + Mask.BMP, Transparenz vorberechnet
  assets/sounds/*.wav                  Original-Sounds
