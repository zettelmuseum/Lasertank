LaserTank 4.1.2 (Jim Kindley) - Android/Java 7 port for AIDE
==============================================================
1. Copy the "LaserTank" folder to your phone (e.g. to /sdcard/AppProjects/).
2. In AIDE, choose Open Project > select the folder > Run (Play button).

Controls
  Arrow keys  = turn / move (hold down = continuous movement)
  FIRE        = shoot             UNDO = undo the last move
  Tap the playfield = move there (like the left mouse button)
  Long-press the playfield = shoot in that direction (like the right mouse button)
  Menu (top right): save/load position, restart, previous/next level,
  open level, choose level set, hint, difficulty levels, initials,
  animation, sound, skip solved levels

Level sets: LaserTank, Challenge-I through Challenge-V (Menu > Choose level set).
  Each set remembers its most recently played level and has its own high scores.
  To add a new set: put Name.lvl (and optionally Name.ghs) in assets/ and add
  its name to the SETS array in MainActivity.java.

High scores
  At the top of the screen: "HS" = your personal best, "Global" = the best score
  from the .ghs file (moves/shots/initials) for the current level.
  Your own records are saved separately for each set in <Name>.hs
  (using the original file format).

Files
  src/jk/lasertank/MainActivity.java   all source code (engine = 1:1 port from LTANK2.C / LTANK.C)
  assets/*.lvl, *.ghs                  original levels + global high scores
  assets/sprites.png                   Game.BMP + Mask.BMP, transparency precomputed
  assets/sounds/*.wav                  original sounds
