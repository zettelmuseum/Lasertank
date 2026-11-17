package jk.lasertank;

/*  LaserTank 4.1.2 by Jim Kindley (public domain) - Android/Java 7 port.
    Die Spiellogik (Eng) ist eine 1:1-Uebersetzung von LTANK2.C / LTANK.C (WM_TIMER).
    Gezeichnet wird nicht inkrementell, sondern jedes Bild komplett aus dem Spielzustand. */

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/* ------------------------------------------------------------------ */
interface Ui {                       // Rueckrufe der Engine in die Oberflaeche
    void sound(int n);               // SoundPlay
    void setTimer(int ms);           // SetTimer
    void killTimer();                // KillTimer
    void postDead();                 // PostMessage(WM_Dead)
    void post105();                  // PostMessage(WM_COMMAND,105) = Restart
    void deadDialog();               // DeadBox (Antwort -> Eng.deadChoice)
    void won();                      // Flagge erreicht
    void gameOver();                 // keine weiteren Level (WinBox)
    void levelLoaded();              // Level geladen (Remember Last Level)
    void hsChanged();                // Highscore-Datei geaendert
}

final class G {                      // TGAMEREC
    byte[][] PF = new byte[16][16], PF2 = new byte[16][16], BMF = new byte[16][16], BMF2 = new byte[16][16];
    int ScoreMove, ScoreShot, RecP, tx, ty, dir;      // tx,ty,dir = Tank.X, Tank.Y, Tank.Dir
    boolean firing, good;                             // Tank.Firing, Tank.Good
    G copy() {
        G g = new G();
        for (int i = 0; i < 16; i++) {
            System.arraycopy(PF[i], 0, g.PF[i], 0, 16);
            System.arraycopy(PF2[i], 0, g.PF2[i], 0, 16);
            System.arraycopy(BMF[i], 0, g.BMF[i], 0, 16);
            System.arraycopy(BMF2[i], 0, g.BMF2[i], 0, 16);
        }
        g.ScoreMove = ScoreMove; g.ScoreShot = ScoreShot; g.RecP = RecP;
        g.tx = tx; g.ty = ty; g.dir = dir; g.firing = firing; g.good = good;
        return g;
    }
}

final class Ice { int x, y, dx, dy; boolean s; }       // TICEREC

/* ------------------------------------------------------------------ */
final class Eng {
    static Ui ui;
    static final int MaxObjects = 26, MaxMBuffer = 20, UndoBufStep = 200, UndoMax = 10000, RecBufStep = 10000;
    static final int ani_delay = 4, MAX_TICEMEM = 16, BADMOVE = 256, Obj_Water = 3, Obj_Ice = 24, Obj_ThinIce = 25, Obj_Tunnel = 0x40;
    static final int VK_SPACE = 32, VK_LEFT = 37, VK_UP = 38, VK_RIGHT = 39, VK_DOWN = 40;
    static final int S_Bricks = 1, S_Fire = 2, S_Move = 3, S_Head = 4, S_Turn = 5, S_EndLev = 6, S_Die = 7, S_Anti1 = 8,
        S_Anti2 = 9, S_Deflb = 10, S_LaserHit = 11, S_Push2 = 12, S_Push1 = 13, S_Rotate = 14, S_Push3 = 15, S_Sink = 16;
    static final int[] GetOBMArray = {1,2,6,9,13,14,15,16,36,39,42,20,21,22,23,24,27,30,33,45,47,48,49,50,56,57,55};
    static final int[] CheckArray = {1,0,1,1,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,0,0,0,0,0,1,1};

    static G Game = new G(), SaveGame;
    static byte[] lvl, hs, ghs;                         // <Set>.lvl / <Set>.hs (lokal, null: fehlt) / <Set>.ghs (global)
    static String playerName = "";
    static byte[] recPF = new byte[256];                // CurRecData.PF[x*16+y]
    static String LName = "", Hint = "", Author = "";
    static int SDiff, CurLevel, Difficulty = 31;
    static boolean SkipCL, Ani_On = true, Game_On, ConvMoving, FindTank, wasIce, WaitToTrans, BlackHole;
    static int AniLevel, AniCount, LaserBounceOnIce, UndoP, UndoRollOver, RB_TOS, MB_TOS, MB_SP;
    static int lx, ly, ldir, lbA, lbB;                  // laser.X/Y/Dir ; lbA/lbB: Form des Strahls (Zeichnen)
    static boolean lfiring, lgood;
    static final Ice SlideO = new Ice(), SlideT = new Ice();
    static final Ice[] mem = new Ice[MAX_TICEMEM];      // SlideMem.Objects (1-basiert)
    static int memCount;
    static byte[] RecBuffer = new byte[10000];
    static G[] ub = new G[UndoBufStep];
    static final int[] mbX = new int[MaxMBuffer], mbY = new int[MaxMBuffer], mbZ = new int[MaxMBuffer];
    static final int[][] findmap = new int[16][16];
    static int ttx, tty;                               // Ein-/Ausgabe von TranslateTunnel

    static { for (int i = 0; i < MAX_TICEMEM; i++) mem[i] = new Ice(); }

    static boolean ISTunnel(int x, int y) { return (Game.PF[x][y] & Obj_Tunnel) == Obj_Tunnel; }
    static int GetTunnelID(int x, int y) { return (Game.PF[x][y] & 0x0F) >> 1; }

    // ---------- Sliding-Stack (MGY) ----------
    static void Mem_to_SlideO(int i) {
        if (i >= 0 && i <= memCount) { SlideO.x = mem[i].x; SlideO.y = mem[i].y; SlideO.dx = mem[i].dx; SlideO.dy = mem[i].dy; SlideO.s = mem[i].s; }
    }
    static void SlideO_to_Mem(int i) {
        if (i >= 0 && i <= memCount) { mem[i].x = SlideO.x; mem[i].y = SlideO.y; mem[i].dx = SlideO.dx; mem[i].dy = SlideO.dy; mem[i].s = SlideO.s; }
    }
    static void add_SlideO_to_Mem() {
        if (memCount < MAX_TICEMEM - 1) {
            for (int i = 1; i <= memCount; i++)
                if (mem[i].x == SlideO.x && mem[i].y == SlideO.y) { SlideO_to_Mem(i); return; }
            memCount++;
            SlideO_to_Mem(memCount);
            SlideO.s = (memCount > 0);
        }
    }
    static void sub_SlideO_from_Mem(int iSlideObj) {
        for (int i = iSlideObj; i < memCount; i++) { Mem_to_SlideO(i + 1); SlideO_to_Mem(i); }
        memCount--;
        SlideO.s = (memCount > 0);
    }
    static void del_SlideO_from_Mem(int x, int y) {
        for (int i = memCount; i >= 1; i--)
            if (mem[i].x == x && mem[i].y == y) { sub_SlideO_from_Mem(i); return; }
        SlideO.s = (memCount > 0);
    }

    static boolean TestIfConvCanMoveTank() {
        switch (Game.PF[Game.tx][Game.ty]) {
        case 15: if (CheckLoc(Game.tx, Game.ty - 1)) return true; break;
        case 16: if (CheckLoc(Game.tx + 1, Game.ty)) return true; break;
        case 17: if (CheckLoc(Game.tx, Game.ty + 1)) return true; break;
        case 18: if (CheckLoc(Game.tx - 1, Game.ty)) return true; break;
        }
        return false;
    }

    // ---------- Eingabepuffer / Maus ----------
    static void AddKBuff(int zz) {
        RecBuffer[RB_TOS] = (byte) zz;
        RB_TOS++;
        if (RB_TOS >= RecBuffer.length) RecBuffer = Arrays.copyOf(RecBuffer, RecBuffer.length + RecBufStep);
    }

    static void FindTarget(int px, int py, int pathlen) {
        if (px < 0 || px > 15 || py < 0 || py > 15) return;
        if (Game.PF[px][py] != 0 && !(Game.tx == px && Game.ty == py)) return;
        if (findmap[px][py] <= pathlen) return;
        findmap[px][py] = pathlen++;
        if (px == Game.tx && py == Game.ty) return;
        FindTarget(px - 1, py, pathlen);
        FindTarget(px + 1, py, pathlen);
        FindTarget(px, py - 1, pathlen);
        FindTarget(px, py + 1, pathlen);
    }

    static boolean MouseOperation(int sp) {
        int dx, dy, cx, cy, ltdir;
        dx = mbX[sp] - Game.tx;
        dy = mbY[sp] - Game.ty;
        boolean XBigger = Math.abs(dx) > Math.abs(dy);
        if (mbZ[sp] == 1) {
            for (cx = 0; cx < 16; cx++) for (cy = 0; cy < 16; cy++) findmap[cx][cy] = BADMOVE;
            dx = Game.PF[mbX[sp]][mbY[sp]];
            if (!((dx < 3) || ((dx > 14) && (dx < 19)) || (dx > 23) || ((Obj_Tunnel & dx) == Obj_Tunnel))) return false;
            findmap[mbX[sp]][mbY[sp]] = 0;
            FindTarget(mbX[sp] - 1, mbY[sp], 1);
            FindTarget(mbX[sp] + 1, mbY[sp], 1);
            FindTarget(mbX[sp], mbY[sp] - 1, 1);
            FindTarget(mbX[sp], mbY[sp] + 1, 1);
            if (findmap[Game.tx][Game.ty] == BADMOVE) return false;
            cx = Game.tx; cy = Game.ty;
            ltdir = Game.dir;
            while (findmap[cx][cy] != 0) {
                if (cx > 0 && findmap[cx - 1][cy] == findmap[cx][cy] - 1) {
                    if (ltdir != 4) AddKBuff(VK_LEFT);
                    AddKBuff(VK_LEFT); cx--; ltdir = 4;
                } else if (cx < 15 && findmap[cx + 1][cy] == findmap[cx][cy] - 1) {
                    if (ltdir != 2) AddKBuff(VK_RIGHT);
                    AddKBuff(VK_RIGHT); cx++; ltdir = 2;
                } else if (cy > 0 && findmap[cx][cy - 1] == findmap[cx][cy] - 1) {
                    if (ltdir != 1) AddKBuff(VK_UP);
                    AddKBuff(VK_UP); cy--; ltdir = 1;
                } else if (cy < 15 && findmap[cx][cy + 1] == findmap[cx][cy] - 1) {
                    if (ltdir != 3) AddKBuff(VK_DOWN);
                    AddKBuff(VK_DOWN); cy++; ltdir = 3;
                } else throw new IllegalStateException("abort");
            }
        } else {
            if (XBigger) {
                if (dx > 0) { if (Game.dir != 2) AddKBuff(VK_RIGHT); }
                else if (Game.dir != 4) AddKBuff(VK_LEFT);
            } else {
                if (dy > 0) { if (Game.dir != 3) AddKBuff(VK_DOWN); }
                else if (Game.dir != 1) AddKBuff(VK_UP);
            }
            AddKBuff(VK_SPACE);
        }
        return true;
    }

    // ---------- Undo ----------
    static void InitBuffers() {
        UndoP = 0; RecBuffer = new byte[10000]; MB_TOS = MB_SP = 0;
        SlideT.s = false; SlideO.s = false; memCount = 0; UndoRollOver = UndoMax;
    }
    static void ResetUndoBuffer() {
        ub = new G[UndoBufStep];
        ub[0] = new G();                       // Tank.Dir = 0  => nichts zum Rueckgaengigmachen
        UndoP = 0;
        MB_TOS = MB_SP = 0;
    }
    static void UpdateUndo() {
        UndoP++;
        if (UndoP >= ub.length) {
            if (UndoP >= UndoMax) { UndoRollOver = UndoP - 1; UndoP = 0; }
            else ub = Arrays.copyOf(ub, ub.length + UndoBufStep);
        }
        ub[UndoP] = Game.copy();
    }
    static void UndoStep() {
        if (ub[UndoP] == null || ub[UndoP].dir == 0) return;
        Game = ub[UndoP].copy();
        RB_TOS = Game.RecP;
        SlideT.s = false; SlideO.s = false; memCount = 0;
        ub[UndoP].dir = 0;
        UndoP--;
        if (UndoP < 0) UndoP = UndoRollOver;
        MB_TOS = MB_SP = 0;
    }

    // ---------- Spielfeld ----------
    static int GetOBM(int ob) { return (ob > -1 && ob <= MaxObjects) ? GetOBMArray[ob] : 1; }

    static void BuildBMField() {
        int i = 1;
        Game.tx = 7; Game.ty = 15;
        Game.dir = 1; Game.firing = false;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            int pt = Game.PF[x][y] & 0xFF;
            if (pt > 0x19) {
                pt = GetTunnelID(x, y);
                Game.PF[x][y] = (byte) ((pt << 1) + 0x40);
            }
            if (Game.PF[x][y] == 1) {
                i = 1; Game.tx = x; Game.ty = y; Game.PF[x][y] = 0;
            } else {
                if (Game.PF[x][y] < 64) i = GetOBM(Game.PF[x][y]);
                else if (ISTunnel(x, y)) i = 55;
            }
            Game.BMF[x][y] = (byte) i;
            Game.BMF2[x][y] = 1;
            Game.PF2[x][y] = 0;
        }
        Game.ScoreMove = 0;
        Game.ScoreShot = 0;
    }

    static void GameOn(boolean b) {
        if (b) ui.setTimer(50); else ui.killTimer();
        Game_On = b;
    }

    static void Animate() {
        AniLevel++;
        AniCount = 0;
        if (AniLevel == 3) AniLevel = 0;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            switch (Game.PF2[x][y]) {
            case 2:  Game.BMF2[x][y] = (byte) (6 + AniLevel); break;
            case 15: Game.BMF2[x][y] = (byte) (24 + AniLevel); break;
            case 16: Game.BMF2[x][y] = (byte) (27 + AniLevel); break;
            case 17: Game.BMF2[x][y] = (byte) (30 + AniLevel); break;
            case 18: Game.BMF2[x][y] = (byte) (33 + AniLevel); break;
            }
            switch (Game.PF[x][y]) {
            case 2:  Game.BMF[x][y] = (byte) (6 + AniLevel); break;
            case 3:  Game.BMF[x][y] = (byte) (9 + AniLevel); break;
            case 7:  Game.BMF[x][y] = (byte) (16 + AniLevel); break;
            case 8:  Game.BMF[x][y] = (byte) (36 + AniLevel); break;
            case 9:  Game.BMF[x][y] = (byte) (39 + AniLevel); break;
            case 10: Game.BMF[x][y] = (byte) (42 + AniLevel); break;
            case 15: Game.BMF[x][y] = (byte) (24 + AniLevel); break;
            case 16: Game.BMF[x][y] = (byte) (27 + AniLevel); break;
            case 17: Game.BMF[x][y] = (byte) (30 + AniLevel); break;
            case 18: Game.BMF[x][y] = (byte) (33 + AniLevel); break;
            }
        }
    }

    static void TranslateTunnel() {
        int cx, cy;
        int bb = Game.PF[ttx][tty];
        WaitToTrans = false;
        BlackHole = false;
        for (cy = 0; cy < 16; cy++) for (cx = 0; cx < 16; cx++)
            if (Game.PF[cx][cy] == bb && !(ttx == cx && tty == cy)) { ttx = cx; tty = cy; return; }
        for (cy = 0; cy < 16; cy++) for (cx = 0; cx < 16; cx++)
            if (Game.PF2[cx][cy] == bb && !(ttx == cx && tty == cy)) { WaitToTrans = true; return; }
        BlackHole = true;
    }

    static void ConvMoveTank(int x, int y, boolean check) {
        Game.ty += y;
        Game.tx += x;
        if (ISTunnel(Game.tx, Game.ty)) {
            ttx = Game.tx; tty = Game.ty; TranslateTunnel(); Game.tx = ttx; Game.ty = tty;
            if (BlackHole) ui.postDead();
        }
        if (WaitToTrans) Game.good = true;
        ConvMoving = true;
        if (wasIce && check) {
            SlideT.x = Game.tx; SlideT.y = Game.ty; SlideT.s = true; SlideT.dx = x; SlideT.dy = y;
        }
        AntiTank();
    }

    static void UpDateTankPos(int x, int y) {
        ui.sound(S_Move);
        UpdateUndo();
        Game.ScoreMove++;
        Game.ty += y;
        Game.tx += x;
        Game.good = false;
        if (ISTunnel(Game.tx, Game.ty)) {
            ttx = Game.tx; tty = Game.ty; TranslateTunnel(); Game.tx = ttx; Game.ty = tty;
            if (BlackHole) ui.postDead();
        }
        if (WaitToTrans) Game.good = true;
    }

    static void MoveTank(int d) {
        if (Game.dir != d) { Game.dir = d; ui.sound(S_Turn); return; }
        switch (d) {
        case 1: if (CheckLoc(Game.tx, Game.ty - 1)) UpDateTankPos(0, -1); else ui.sound(S_Head);
                SlideT.dy = -1; SlideT.dx = 0; break;
        case 2: if (CheckLoc(Game.tx + 1, Game.ty)) UpDateTankPos(1, 0); else ui.sound(S_Head);
                SlideT.dy = 0; SlideT.dx = 1; break;
        case 3: if (CheckLoc(Game.tx, Game.ty + 1)) UpDateTankPos(0, 1); else ui.sound(S_Head);
                SlideT.dy = 1; SlideT.dx = 0; break;
        case 4: if (CheckLoc(Game.tx - 1, Game.ty)) UpDateTankPos(-1, 0); else ui.sound(S_Head);
                SlideT.dy = 0; SlideT.dx = -1; break;
        }
        if (wasIce) { SlideT.x = Game.tx; SlideT.y = Game.ty; SlideT.s = true; }
    }

    static boolean CheckLoc(int x, int y) {
        if (x < 0 || x > 15 || y < 0 || y > 15) return false;
        wasIce = (Game.PF[x][y] == Obj_Ice) || (Game.PF[x][y] == Obj_ThinIce);
        if ((Game.PF[x][y] & Obj_Tunnel) == Obj_Tunnel) return true;
        return CheckArray[Game.PF[x][y]] != 0;
    }

    static void MoveObj(int x, int y, int dx, int dy, int sf) {
        byte obt = Game.PF[x][y];
        byte bm = Game.BMF[x][y];
        int bb = 0, cx = 0, cy = 0;
        if ((Game.PF2[x][y] & Obj_Tunnel) == Obj_Tunnel) {
            bb = Game.PF2[x][y] | 1;
            boolean ok = false;
            search:
            for (cy = 0; cy < 16; cy++) for (cx = 0; cx < 16; cx++)
                if (Game.PF2[cx][cy] == bb && !(x == cx && y == cy)) { ok = true; break search; }
            if (ok) {
                Game.PF[x][y] = Game.PF[cx][cy];
                Game.BMF[x][y] = Game.BMF[cx][cy];
                Game.PF[cx][cy] = (byte) (Game.PF2[cx][cy] & 0xFE);
                Game.PF2[cx][cy] = 0;
                Game.BMF[cx][cy] = Game.BMF2[cx][cy];
            } else {
                Game.PF[x][y] = (byte) (Game.PF2[x][y] & 0xFE);
                Game.PF2[x][y] = 0;
                Game.BMF[x][y] = Game.BMF2[x][y];
                if (Game.PF[Game.tx][Game.ty] == (bb & 0xFE) && Game.good) {
                    Game.ScoreMove--;
                    UpDateTankPos(0, 0);
                    UndoP--;
                }
            }
        } else {
            Game.PF[x][y] = Game.PF2[x][y];
            Game.PF2[x][y] = 0;
            Game.BMF[x][y] = Game.BMF2[x][y];
        }
        x += dx;
        y += dy;
        if (ISTunnel(x, y)) {
            ttx = x; tty = y; TranslateTunnel(); x = ttx; y = tty;
            if (BlackHole) return;
        } else WaitToTrans = false;

        Game.PF2[x][y] = Game.PF[x][y];
        if (WaitToTrans) Game.PF2[x][y] |= 1;
        Game.BMF2[x][y] = Game.BMF[x][y];
        if (Game.PF[x][y] != 3) {
            Game.PF[x][y] = obt;
            Game.BMF[x][y] = bm;
        } else {
            sf = S_Sink;
            if (obt == 5) {
                Game.PF[x][y] = 0;
                Game.PF2[x][y] = 0;
                Game.BMF[x][y] = 19;
                Game.BMF2[x][y] = 19;
            }
        }
        ui.sound(sf);
    }

    static void IceMoveT() {
        boolean savei = false;
        if (Game.PF[SlideT.x][SlideT.y] == Obj_ThinIce) {
            Game.BMF[SlideT.x][SlideT.y] = 9;
            Game.PF[SlideT.x][SlideT.y] = Obj_Water;
        }
        if (CheckLoc(SlideT.x + SlideT.dx, SlideT.y + SlideT.dy)) {
            savei = wasIce;
            ConvMoveTank(SlideT.dx, SlideT.dy, false);
        } else {
            SlideT.s = false;
            return;
        }
        SlideT.x += SlideT.dx;
        SlideT.y += SlideT.dy;
        if (!savei) SlideT.s = false;
    }

    static void IceMoveO() {
        boolean savei;
        for (int i = memCount; i >= 1; i--) {
            Mem_to_SlideO(i);
            if (i <= memCount) {
                if (Game.PF2[SlideO.x][SlideO.y] == Obj_ThinIce) {
                    Game.BMF2[SlideO.x][SlideO.y] = 9;
                    Game.PF2[SlideO.x][SlideO.y] = Obj_Water;
                }
                if (CheckLoc(SlideO.x + SlideO.dx, SlideO.y + SlideO.dy)
                    && !(SlideO.x + SlideO.dx == Game.tx && SlideO.y + SlideO.dy == Game.ty)) {
                    savei = wasIce;
                    MoveObj(SlideO.x, SlideO.y, SlideO.dx, SlideO.dy, S_Push2);
                    AntiTank();
                    SlideO.x += SlideO.dx;
                    SlideO.y += SlideO.dy;
                    if (!savei) {
                        SlideO.s = false;
                        SlideO_to_Mem(i);
                        sub_SlideO_from_Mem(i);
                    } else {
                        SlideO_to_Mem(i);
                    }
                } else {
                    if (Game.PF2[SlideO.x][SlideO.y] == Obj_Water) MoveObj(SlideO.x, SlideO.y, 0, 0, 0);
                    SlideO.s = false;
                    SlideO_to_Mem(i);
                    sub_SlideO_from_Mem(i);
                    AntiTank();
                }
            }
        }
        Mem_to_SlideO(memCount);
        SlideO.s = (memCount > 0);
    }

    static void KillAtank(int x, int y, int bm) {
        Game.PF[x][y] = 4;
        Game.BMF[x][y] = (byte) bm;
        ui.sound(S_Anti1);
    }

    // hier richtet der Laser Schaden an; true = Laser hat nichts getroffen
    static boolean CheckLLoc(int x, int y, int dx, int dy) {
        if (x < 0 || x > 15 || y < 0 || y > 15) return false;
        if (x == Game.tx && y == Game.ty) { WM_Dead(); return false; }
        wasIce = false;
        switch (Game.PF[x][y]) {
        case 0: case 2: case 3: case 15: case 16: case 17: case 18: return true;
        case 4: ui.sound(S_LaserHit); break;
        case 5: if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push1); else ui.sound(S_LaserHit); break;
        case 6: Game.PF[x][y] = 0; Game.BMF[x][y] = 1; ui.sound(S_Bricks); break;
        case 7: if (dy == 1) { KillAtank(x, y, 54); return false; }
                else if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push3); else ui.sound(S_LaserHit); break;
        case 8: if (dx == -1) { KillAtank(x, y, 52); return false; }
                else if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push3); else ui.sound(S_LaserHit); break;
        case 9: if (dy == -1) { KillAtank(x, y, 12); return false; }
                else if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push3); else ui.sound(S_LaserHit); break;
        case 10: if (dx == 1) { KillAtank(x, y, 53); return false; }
                else if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push3); else ui.sound(S_LaserHit); break;
        case 11: if (ldir == 2 || ldir == 3) return true;
                if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push2); else ui.sound(S_LaserHit); break;
        case 12: if (ldir == 3 || ldir == 4) return true;
                if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push2); else ui.sound(S_LaserHit); break;
        case 13: if (ldir == 1 || ldir == 4) return true;
                if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push2); else ui.sound(S_LaserHit); break;
        case 14: if (ldir == 1 || ldir == 2) return true;
                if (CheckLoc(x + dx, y + dy)) MoveObj(x, y, dx, dy, S_Push2); else ui.sound(S_LaserHit); break;
        case 19: return true;                                        // Kristall (Aufleuchten wird beim Zeichnen gemacht)
        case 20: if (ldir == 2 || ldir == 3) return true;
                Game.PF[x][y] = 21; Game.BMF[x][y] = 48; ui.sound(S_Rotate); break;
        case 21: if (ldir == 3 || ldir == 4) return true;
                Game.PF[x][y] = 22; Game.BMF[x][y] = 49; ui.sound(S_Rotate); break;
        case 22: if (ldir == 1 || ldir == 4) return true;
                Game.PF[x][y] = 23; Game.BMF[x][y] = 50; ui.sound(S_Rotate); break;
        case 23: if (ldir == 1 || ldir == 2) return true;
                Game.PF[x][y] = 20; Game.BMF[x][y] = 47; ui.sound(S_Rotate); break;
        case 24: case 25: return true;
        default: if (ISTunnel(x, y)) return true;
        }
        if (wasIce) {
            del_SlideO_from_Mem(x, y);
            SlideO.x = x + dx; SlideO.y = y + dy; SlideO.s = true; SlideO.dx = dx; SlideO.dy = dy;
            add_SlideO_to_Mem();
        } else {
            del_SlideO_from_Mem(x, y);
        }
        return false;
    }

    static void MoveLaser() {
        do {
            LaserBounceOnIce = 0;
            int x = 0, y = 0;
            switch (ldir) {
            case 1: y = -1; break;
            case 2: x = 1; break;
            case 3: y = 1; break;
            case 4: x = -1;
            }
            if (CheckLLoc(lx + x, ly + y, x, y)) {
                ly += y;
                lx += x;
                lbA = 0;
                int p = Game.PF[lx][ly];
                if ((p > 10 && p < 15) || (p > 19 && p < 24)) {
                    int oDir = ldir;
                    switch (p) {
                    case 11: case 20: if (ldir == 2) ldir = 1; else ldir = 4; break;
                    case 12: case 21: if (ldir == 3) ldir = 2; else ldir = 1; break;
                    case 13: case 22: if (ldir == 1) ldir = 2; else ldir = 3; break;
                    case 14: case 23: if (ldir == 1) ldir = 4; else ldir = 3;
                    }
                    lbA = oDir; lbB = ldir;                          // UpDateLaserBounce
                    for (int i = 1; i <= memCount; i++)
                        if (mem[i].s && mem[i].x == lx && mem[i].y == ly) LaserBounceOnIce = 1;
                    ui.sound(S_Deflb);
                }
                lfiring = true;
            } else {
                Game.firing = false;
                if (Game_On) AntiTank();
                if (TestIfConvCanMoveTank()) ConvMoving = true;
            }
        } while (LaserBounceOnIce != 0);
    }

    static void FireLaser(int x, int y, int d, int sf) {
        Game.firing = true;
        ldir = d; lx = x; ly = y;
        lfiring = false;
        ui.sound(sf);
        lgood = (sf == 2);
        MoveLaser();
    }

    static void AntiTank() {
        int x, y;
        if (Game.firing) return;
        x = Game.tx;
        while (CheckLoc(x, Game.ty)) x++;
        if (x < 16 && Game.PF[x][Game.ty] == 10 && Game.tx != x) { FireLaser(x, Game.ty, 4, S_Anti2); return; }
        x = Game.tx;
        while (CheckLoc(x, Game.ty)) x--;
        if (x >= 0 && Game.PF[x][Game.ty] == 8 && Game.tx != x) { FireLaser(x, Game.ty, 2, S_Anti2); return; }
        y = Game.ty;
        while (CheckLoc(Game.tx, y)) y++;
        if (y < 16 && Game.PF[Game.tx][y] == 7 && Game.ty != y) { FireLaser(Game.tx, y, 1, S_Anti2); return; }
        y = Game.ty;
        while (CheckLoc(Game.tx, y)) y--;
        if (y >= 0 && Game.PF[Game.tx][y] == 9 && Game.ty != y) { FireLaser(Game.tx, y, 3, S_Anti2); return; }
    }

    // ---------- Tod ----------
    static void WM_Dead() {              // GameOn(FALSE); Ton; DeadBox
        GameOn(false);
        ui.sound(S_Die);
        ui.deadDialog();
    }
    static void deadChoice(int i) {      // i: 0 = Undo, sonst Restart/Abbruch
        if (!(Game.RecP > 1)) i = 1;     // im ersten Zug immer Restart
        if (i == 0) { UndoStep(); GameOn(true); }
        else { UndoStep(); ui.post105(); }
    }

    // ---------- Kommandos ----------
    static void Restart() {              // WM_COMMAND 105
        if (CurLevel > 0) {
            if (UndoP > 0) UpdateUndo();
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) Game.PF[x][y] = recPF[x * 16 + y];
            BuildBMField();
            GameOn(true);
            Game.RecP = 0;
            RB_TOS = 0;
            SlideO.s = false; memCount = 0; SlideT.s = false;
            MB_TOS = MB_SP = 0;
        }
    }
    static void SavePos() { SaveGame = Game.copy(); }                       // 111
    static void RestorePos() {                                              // 112
        if (SaveGame == null) return;
        Game = SaveGame.copy();
        RB_TOS = Game.RecP;
        SlideT.s = false; SlideO.s = false; memCount = 0;      // Original-Bug: rutschte sonst vom alten Punkt aus weiter (UB, Tank verliess das Feld)
        MB_TOS = MB_SP = 0;
    }

    // ---------- Level / Highscore ----------
    static String str(int off, int max) {
        int n = 0;
        while (n < max && lvl[off + n] != 0) n++;
        return new String(lvl, off, n, Charset.forName("windows-1252"));
    }
    static boolean readLevel(int idx) {
        int off = idx * 576;                                   // sizeof(TLEVEL)
        if (off < 0 || off + 576 > lvl.length) return false;
        System.arraycopy(lvl, off, recPF, 0, 256);
        LName = str(off + 256, 31); Hint = str(off + 287, 256); Author = str(off + 543, 31);
        SDiff = (lvl[off + 574] & 255) | ((lvl[off + 575] & 255) << 8);
        return true;
    }
    static int w16(byte[] f, int o) { return (f[o] & 255) | ((f[o + 1] & 255) << 8); }
    static int hsMoves(int idx) { return (hs != null && idx >= 0 && idx * 10 + 10 <= hs.length) ? w16(hs, idx * 10) : 0; }

    // Highscore des aktuellen Levels aus hs/ghs als Text: "Zuege/Schuesse Name"
    static String hsInfo(byte[] f) {
        int i = (CurLevel - 1) * 10;
        if (f == null || i < 0 || i + 10 > f.length || w16(f, i) == 0) return "-";
        int e = 4;
        while (e < 10 && f[i + e] != 0) e++;
        return w16(f, i) + "/" + w16(f, i + 2) + " " + new String(f, i + 4, e - 4, Charset.forName("windows-1252")).trim();
    }
    static boolean beatsGlobal() {                          // HSBox: Game.ScoreMove < GHS.moves ...
        int i = (CurLevel - 1) * 10;
        if (ghs == null || i < 0 || i + 10 > ghs.length) return false;
        int m = w16(ghs, i), s = w16(ghs, i + 2);
        return m == 0 || Game.ScoreMove < m || (Game.ScoreMove == m && Game.ScoreShot < s);
    }

    static boolean LoadNextLevel(boolean DirectLoad, boolean Scanning) {
        GameOn(false);
        int SavedLevelNum = CurLevel;
        do {
            if (!readLevel(CurLevel)) {
                CurLevel = SavedLevelNum;
                if (!Scanning) ui.gameOver();
                return false;
            }
            if (SkipCL && hs != null && !DirectLoad && hsMoves(CurLevel) > 0) SDiff = 128;
            CurLevel++;
        } while (!DirectLoad && SDiff > 0 && (Difficulty & SDiff) == 0);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) Game.PF[x][y] = recPF[x * 16 + y];
        BuildBMField();
        GameOn(true);
        FindTank = true;
        ui.setTimer(700);
        ResetUndoBuffer();
        ui.levelLoaded();
        Game.RecP = 0;
        RB_TOS = 0;
        SlideT.s = false; SlideO.s = false; memCount = 0;
        return true;
    }

    static void LoadLastLevel() {
        if (CurLevel < 2) return;
        GameOn(false);
        CurLevel--;
        do {
            CurLevel--;
            if (!readLevel(CurLevel)) { ui.gameOver(); return; }
            if (SkipCL && hs != null && hsMoves(CurLevel) > 0) SDiff = 128;
        } while (CurLevel > 0 && SDiff > 0 && (Difficulty & SDiff) == 0);
        LoadNextLevel(true, false);
    }

    static boolean CheckHighScore() {                       // true = neuer lokaler Rekord
        int idx = CurLevel - 1, m = 0, s = 0;
        if (hs == null) hs = new byte[0];
        if (CurLevel * 10 > hs.length) hs = Arrays.copyOf(hs, Math.max(hs.length, idx * 10));
        else if (idx * 10 + 10 <= hs.length) { m = w16(hs, idx * 10); s = w16(hs, idx * 10 + 2); }
        if (m == 0 || Game.ScoreMove < m || (Game.ScoreMove == m && Game.ScoreShot < s)) {
            if (hs.length < idx * 10 + 10) hs = Arrays.copyOf(hs, idx * 10 + 10);
            hs[idx * 10] = (byte) Game.ScoreMove; hs[idx * 10 + 1] = (byte) (Game.ScoreMove >> 8);
            hs[idx * 10 + 2] = (byte) Game.ScoreShot; hs[idx * 10 + 3] = (byte) (Game.ScoreShot >> 8);
            for (int i = 0; i < 6; i++) hs[idx * 10 + 4 + i] = (byte) (i < 5 && i < playerName.length() ? playerName.charAt(i) : 0);
            ui.hsChanged();
            return true;
        }
        return false;
    }

    // ---------- WM_TIMER ----------
    static void tick() {
        if (FindTank) { FindTank = false; ui.setTimer(50); }
        if (Ani_On) AniCount++;
        if (AniCount == ani_delay) Animate();
        if (Game.firing) MoveLaser();
        if (Game.RecP < RB_TOS && !(Game.firing || ConvMoving || SlideO.s || SlideT.s)) {
            switch (RecBuffer[Game.RecP]) {
            case VK_UP:    MoveTank(1); break;
            case VK_RIGHT: MoveTank(2); break;
            case VK_DOWN:  MoveTank(3); break;
            case VK_LEFT:  MoveTank(4); break;
            case VK_SPACE:
                UpdateUndo();
                Game.ScoreShot++;
                FireLaser(Game.tx, Game.ty, Game.dir, S_Fire);
            }
            Game.RecP++;
            AntiTank();
        }
        if (SlideO.s) IceMoveO();
        if (SlideT.s) IceMoveT();
        ConvMoving = false;
        switch (Game.PF[Game.tx][Game.ty]) {
        case 2:
            if (Game_On) { GameOn(false); ui.sound(S_EndLev); ui.won(); }
            break;
        case 3: ui.postDead(); break;
        case 15: if (CheckLoc(Game.tx, Game.ty - 1)) ConvMoveTank(0, -1, true); break;
        case 16: if (CheckLoc(Game.tx + 1, Game.ty)) ConvMoveTank(1, 0, true); break;
        case 17: if (CheckLoc(Game.tx, Game.ty + 1)) ConvMoveTank(0, 1, true); break;
        case 18: if (CheckLoc(Game.tx - 1, Game.ty)) ConvMoveTank(-1, 0, true);
        }
        if (Game.RecP == RB_TOS && MB_TOS != MB_SP && !(Game.firing || ConvMoving || SlideO.s || SlideT.s)) {
            if (MouseOperation(MB_SP)) { MB_SP++; if (MB_SP == MaxMBuffer) MB_SP = 0; }
            else MB_SP = MB_TOS;
        }
    }
}

/* ------------------------------------------------------------------ */
public class MainActivity extends Activity implements Ui {
    static final int[] BMSTA = {0,0,1,1,1,1,0,0,0,0,0,0,1,0,1,0,1,1,1,0,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0,0,0,0,1,1,1,0,0,0,0};
    static final String[] SETS = {"LaserTank", "Challenge-I", "Challenge-II", "Challenge-III", "Challenge-IV", "Challenge-V"};   // assets/<Name>.lvl + .ghs
    static final String[] SND = {"bricks","fire","move","head","turn","endlev","die","anti1","anti2","deflb","laser2","push2","push1","rotate","push3","sink"};
    static final int[] DIFCOL = {0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00, 0xFFFF00FF, 0xFFFF0000};
    static final String[] DIFN = {"Kids", "Einfach", "Mittel", "Hart", "T\u00f6dlich"};

    final Handler h = new Handler();
    GameView view;
    SharedPreferences pr;
    SoundPool sp;
    final int[] snd = new int[17];
    int lastStream, epoch, held, heldId = -1, heldTicks, curSet;
    boolean soundOn = true, deadUp, paused;
    Bitmap sheet;

    final Runnable tickR = new Runnable() {
        public void run() {
            int e = epoch;
            if (held != 0 && ++heldTicks > 8 && Eng.RB_TOS <= Eng.Game.RecP) Eng.AddKBuff(held);   // Tasten-Wiederholung
            Eng.tick();
            view.invalidate();
            if (e == epoch && Eng.Game_On) h.postDelayed(this, 50);                                // GameDelay
        }
    };

    // ---------- Ui ----------
    public void sound(int n) {
        if (!soundOn || sp == null || snd[n] == 0) return;
        if (lastStream != 0) sp.stop(lastStream);
        lastStream = sp.play(snd[n], 1f, 1f, 1, 0, 1f);
    }
    public void setTimer(int ms) { epoch++; h.removeCallbacks(tickR); h.postDelayed(tickR, ms); }
    public void killTimer() { epoch++; h.removeCallbacks(tickR); }
    public void postDead() { h.post(new Runnable() { public void run() { Eng.WM_Dead(); } }); }
    public void post105() { h.post(new Runnable() { public void run() { Eng.Restart(); view.invalidate(); } }); }
    public void deadDialog() {
        if (deadUp) return;
        deadUp = true;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("DU BIST TOT ! ! !").setCancelable(false);
        b.setPositiveButton("Z U R \u00dc C K", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { deadUp = false; Eng.deadChoice(0); view.invalidate(); } });
        b.setNegativeButton("ANFANG", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { deadUp = false; Eng.deadChoice(1); view.invalidate(); } });
        b.show();
    }
    public void won() {
        if (Eng.playerName.length() == 0) askName(new Runnable() { public void run() { finishLevel(); view.invalidate(); } });
        else finishLevel();
    }
    void finishLevel() {                                        // HSBox: Highscore speichern, naechstes Level
        String t = "Level " + Eng.CurLevel + ": " + Eng.Game.ScoreMove + " / " + Eng.Game.ScoreShot;
        boolean g = Eng.beatsGlobal(), n = Eng.CheckHighScore();
        if (g) t += "  - GLOBALEN REKORD GESCHLAGEN!"; else if (n) t += "  - neuer High Score!";
        Toast.makeText(this, t, Toast.LENGTH_LONG).show();
        Eng.LoadNextLevel(false, false);
    }
    void askName(final Runnable then) {                         // Initialen (HS.name)
        final EditText et = new EditText(this);
        et.setText(Eng.playerName);
        new AlertDialog.Builder(this).setTitle("Initialen (max. 5)").setView(et).setCancelable(false)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String n = et.getText().toString().trim();
                    Eng.playerName = n.length() == 0 ? "???" : n.length() > 5 ? n.substring(0, 5) : n;
                    pr.edit().putString("Player", Eng.playerName).commit();
                    if (then != null) then.run();
                } }).show();
    }
    public void gameOver() {
        int y = 0, x = Eng.lvl.length / 576;
        if (Eng.hs != null) for (int i = 0; i < x; i++) if (Eng.hsMoves(i) > 0) y++;
        new AlertDialog.Builder(this).setTitle("Gewonnen ! ! !").setMessage("Gel\u00f6st: " + y + " von " + x + " Leveln")
            .setPositiveButton("OK", null).show();
    }
    public void levelLoaded() { pr.edit().putInt("RLL_" + curSet, Eng.CurLevel).commit(); }
    public void hsChanged() {
        try { FileOutputStream o = new FileOutputStream(new File(getFilesDir(), SETS[curSet] + ".hs")); o.write(Eng.hs); o.close(); }
        catch (IOException e) { }
    }

    // ---------- Start ----------
    byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    void loadSet(int i) throws IOException {                    // Open Data File: Level + lokale/globale Highscores
        byte[] l = readAll(getAssets().open(SETS[i] + ".lvl")), g = null, h = null;
        try { g = readAll(getAssets().open(SETS[i] + ".ghs")); } catch (IOException e) { }
        File f = new File(getFilesDir(), SETS[i] + ".hs");
        if (f.exists()) h = readAll(new FileInputStream(f));
        Eng.lvl = l; Eng.ghs = g; Eng.hs = h;
        curSet = i;
        pr.edit().putInt("Set", i).commit();
        setTitle("LaserTank - " + SETS[i]);
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        pr = getPreferences(MODE_PRIVATE);
        Eng.ui = this;
        try {
            sheet = BitmapFactory.decodeStream(getAssets().open("sprites.png"));
            loadSet(pr.getInt("Set", 0));
        } catch (IOException e) { Toast.makeText(this, "Datei nicht gefunden: " + e, Toast.LENGTH_LONG).show(); finish(); return; }
        Eng.Difficulty = pr.getInt("Diff_Setting", 31);
        Eng.SkipCL = pr.getBoolean("SkipComLev", false);
        Eng.Ani_On = pr.getBoolean("Animation", true);
        soundOn = pr.getBoolean("Sound", true);
        Eng.playerName = pr.getString("Player", "");
        sp = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
        for (int i = 0; i < SND.length; i++) {
            try { AssetFileDescriptor a = getAssets().openFd("sounds/" + SND[i] + ".wav"); snd[i + 1] = sp.load(a, 1); a.close(); }
            catch (IOException e) { }
        }
        Eng.InitBuffers();
        view = new GameView();
        setContentView(view);
        command(101);                                           // New Game (Remember Last Level)
    }

    @Override protected void onPause() { super.onPause(); paused = true; killTimer(); }
    @Override protected void onResume() { super.onResume(); if (paused && Eng.Game_On) setTimer(50); paused = false; }

    @Override protected void onDestroy() { super.onDestroy(); killTimer(); if (sp != null) sp.release(); }

    // ---------- Menue / Kommandos (IDs wie im Original) ----------
    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 110, 0, "Zur\u00fcck");
        m.add(0, 111, 1, "Position speichern");
        m.add(0, 112, 2, "Position abrufen");
        m.add(0, 105, 3, "Anfang");
        m.add(0, 119, 4, "<< Level");
        m.add(0, 107, 5, "Level >>");
        m.add(0, 106, 6, "Level \u00f6ffnen");
        m.add(0, 108, 7, "Levelset w\u00e4hlen...");
        m.add(0, 301, 8, "Tipp");
        m.add(0, 225, 9, "Schwierigkeitsgrade...");
        m.add(0, 303, 10, "Initialen...");
        m.add(0, 104, 11, "Animation").setCheckable(true).setChecked(Eng.Ani_On);
        m.add(0, 102, 12, "Sound").setCheckable(true).setChecked(soundOn);
        m.add(0, 116, 13, "Gel\u00f6ste Level \u00fcberspringen").setCheckable(true).setChecked(Eng.SkipCL);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        if (it.isCheckable()) it.setChecked(!it.isChecked());
        command(it.getItemId());
        return true;
    }

    void command(int id) {
        final boolean wasOn = Eng.Game_On;
        switch (id) {
        case 101: {                                             // New Game
            int last = Eng.CurLevel;
            Eng.CurLevel = pr.getInt("RLL_" + curSet, 1) - 1;
            if (!Eng.LoadNextLevel(true, false)) Eng.CurLevel = last;
            break; }
        case 102: soundOn = !soundOn; pr.edit().putBoolean("Sound", soundOn).commit(); break;
        case 104: Eng.Ani_On = !Eng.Ani_On; pr.edit().putBoolean("Animation", Eng.Ani_On).commit(); break;
        case 105: Eng.Restart(); break;
        case 106: {                                             // Load Level
            final int last = Eng.CurLevel;
            Eng.GameOn(false);
            int n = Eng.lvl.length / 576;
            String[] items = new String[n];
            for (int i = 0; i < n; i++) items[i] = (i + 1) + "  " + Eng.str(i * 576 + 256, 31) + "  (" + Eng.str(i * 576 + 543, 31) + ")";
            AlertDialog d = new AlertDialog.Builder(this).setTitle("LaserTank Level #").setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dd, int which) {
                    Eng.CurLevel = which;
                    if (!Eng.LoadNextLevel(true, true)) { Eng.CurLevel = last; Eng.GameOn(wasOn); }
                    view.invalidate();
                } }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dd) { Eng.GameOn(wasOn); } }).create();
            d.show();
            ListView lv = d.getListView();
            lv.setSelection(Math.max(0, last - 1));
            break; }
        case 107: Eng.LoadNextLevel(false, false); break;       // Skip Level
        case 108: {                                             // Open Data File = Levelset waehlen
            Eng.GameOn(false);
            new AlertDialog.Builder(this).setTitle("Levelset").setItems(SETS, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dd, int which) {
                    try { loadSet(which); } catch (IOException e) { Toast.makeText(MainActivity.this, "" + e, Toast.LENGTH_LONG).show(); Eng.GameOn(wasOn); return; }
                    command(101);                               // zuletzt gespieltes Level dieses Sets
                } }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dd) { Eng.GameOn(wasOn); } }).show();
            break; }
        case 110: Eng.UndoStep(); break;
        case 111: Eng.SavePos(); break;
        case 112: Eng.RestorePos(); break;
        case 116: Eng.SkipCL = !Eng.SkipCL; pr.edit().putBoolean("SkipComLev", Eng.SkipCL).commit(); break;
        case 119: Eng.LoadLastLevel(); break;
        case 225: {                                             // Difficulty
            final boolean[] ch = new boolean[5];
            for (int i = 0; i < 5; i++) ch[i] = (Eng.Difficulty & (1 << i)) != 0;
            Eng.GameOn(false);
            new AlertDialog.Builder(this).setTitle("Schwierigkeitsgrade").setCancelable(false)
                .setMultiChoiceItems(DIFN, ch, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface dd, int w, boolean c) { ch[w] = c; } })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dd, int w) {
                        int v = 0;
                        for (int i = 0; i < 5; i++) if (ch[i]) v |= 1 << i;
                        if (v > 0) { Eng.Difficulty = v; pr.edit().putInt("Diff_Setting", v).commit(); }
                        Eng.GameOn(wasOn);
                    } }).show();
            break; }
        case 303: askName(null); break;
        case 301:                                               // Hint
            if (Eng.Game_On) {
                Eng.GameOn(false);
                new AlertDialog.Builder(this).setTitle("Tipp").setCancelable(false)
                    .setMessage(Eng.Hint.length() > 0 ? Eng.Hint.replace("\r", "") : "< SORRY...KEIN TIPP VORHANDEN... >")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dd, int w) { Eng.GameOn(true); } }).show();
            }
            break;
        }
        view.invalidate();
    }

    // ---------- Anzeige + Touch ----------
    class GameView extends View {
        final Paint p = new Paint(), tp = new Paint();
        final Rect src = new Rect(), dst = new Rect();
        final RectF[] bt = new RectF[6];                       // Hoch, Rechts, Runter, Links, Feuer, Undo
        final int[] btKey = {38, 39, 40, 37, 32, 110};
        final Path path = new Path();
        int tile, bx, by, boardId = -1, downCx, downCy;
        long downT;

        GameView() {
            super(MainActivity.this);
            tp.setAntiAlias(true);
            for (int i = 0; i < 6; i++) bt[i] = new RectF();
        }

        void line(Canvas c, String t, int col, float y, int W) {   // Textzeile, schrumpft auf Breite
            tp.setColor(col);
            tp.setTextSize(tile * 0.75f);
            float w = tp.measureText(t), max = W - tile * 0.6f;
            if (w > max) tp.setTextSize(tile * 0.75f * max / w);
            c.drawText(t, tile * 0.3f, tile * y, tp);
        }

        void sprite(Canvas c, int i, int x, int y) {          // BMA[i]
            src.set(((i - 1) % 10) * 32, ((i - 1) / 10) * 32, ((i - 1) % 10) * 32 + 32, ((i - 1) / 10) * 32 + 32);
            dst.set(x, y, x + 32, y + 32);
            c.drawBitmap(sheet, src, dst, p);
        }
        void tunnel(Canvas c, int id, int x, int y) {         // vorberechnete Tunnel-Farbvarianten (Zeile 7 im Sheet)
            src.set(id * 32, 192, id * 32 + 32, 224);
            dst.set(x, y, x + 32, y + 32);
            c.drawBitmap(sheet, src, dst, p);
        }
        void rect(Canvas c, int l, int t, int r, int b, int col) {   // GDI Rectangle: schwarzer 1px-Rand + Fuellung
            p.setColor(0xFF000000); c.drawRect(l, t, r, b, p);
            p.setColor(col); c.drawRect(l + 1, t + 1, r - 1, b - 1, p);
        }

        void drawCell(Canvas c, int x, int y) {               // UpDateSprite
            G g = Eng.Game;
            int bmn = g.BMF[x][y], X = x * 32, Y = y * 32;
            if (bmn == 55) tunnel(c, Eng.GetTunnelID(x, y), X, Y);
            else if (BMSTA[bmn] == 1) {
                int bmn2 = g.BMF2[x][y];
                if (bmn2 == 55) tunnel(c, (g.PF2[x][y] & 0x0F) >> 1, X, Y); else sprite(c, bmn2, X, Y);
                sprite(c, bmn, X, Y);
            } else sprite(c, bmn, X, Y);
        }

        void drawLaser(Canvas c) {                            // UpDateLaser / UpDateLaserBounce
            final int LO = 13, h2 = 16, W = 32;
            int x = Eng.lx * 32, y = Eng.ly * 32, col = Eng.lgood ? 0xFF00FF00 : 0xFFFF0000;
            if (Eng.Game.PF[Eng.lx][Eng.ly] == 19) sprite(c, Eng.lgood ? 46 : 51, x, y);
            if (Eng.lbA == 0) {
                if ((Eng.ldir & 1) == 1) rect(c, x + LO, y, x + W - LO, y + W, col);
                else rect(c, x, y + LO, x + W, y + W - LO, col);
                return;
            }
            switch (Eng.lbA) {
            case 1: rect(c, x + LO, y + h2, x + W - LO, y + W, col); break;
            case 2: rect(c, x, y + LO, x + h2, y + W - LO, col); break;
            case 3: rect(c, x + LO, y, x + W - LO, y + h2, col); break;
            case 4: rect(c, x + h2, y + LO, x + W, y + W - LO, col);
            }
            switch (Eng.lbB) {
            case 1: rect(c, x + LO, y, x + W - LO, y + h2, col); break;
            case 2: rect(c, x + h2, y + LO, x + W, y + W - LO, col); break;
            case 3: rect(c, x + LO, y + h2, x + W - LO, y + W, col); break;
            case 4: rect(c, x, y + LO, x + h2, y + W - LO, col);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            c.drawColor(0xFF000000);
            tile = Math.min(W / 16, H * 11 / 20 / 16);
            int bw = tile * 16, hud = tile * 4;
            bx = (W - bw) / 2; by = hud;
            G g = Eng.Game;
            // Anzeige: Level, Name, Autor, Moves, Shots, High Score (lokal / global)
            int di = Eng.SDiff == 1 ? 1 : Eng.SDiff == 2 ? 2 : Eng.SDiff == 4 ? 3 : Eng.SDiff == 8 ? 4 : Eng.SDiff == 16 ? 5 : 0;
            line(c, Eng.CurLevel + (di > 0 ? " - " + DIFN[di - 1] : "") + "   " + Eng.LName, DIFCOL[di], 0.9f, W);
            line(c, Eng.Author, 0xFF808080, 1.85f, W);
            line(c, "Moves: " + g.ScoreMove + "   Shots: " + g.ScoreShot, 0xFF00FF00, 2.8f, W);
            line(c, "HS: " + Eng.hsInfo(Eng.hs) + "   Global: " + Eng.hsInfo(Eng.ghs), 0xFFFFFF00, 3.75f, W);
            if (Eng.CurLevel > 0) {
                c.save();
                c.translate(bx, by);
                c.scale(tile / 32f, tile / 32f);
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) drawCell(c, x, y);
                sprite(c, 1 + g.dir, g.tx * 32, g.ty * 32);                     // UpDateTank
                if (g.firing && Eng.lfiring) drawLaser(c);
                if (Eng.FindTank) {                                             // PutLevel: Panzer suchen
                    p.setColor(0xFFFF0000);
                    c.drawRect(g.tx * 32 + 13, 2, g.tx * 32 + 19, 512, p);
                    c.drawRect(2, g.ty * 32 + 13, 512, g.ty * 32 + 19, p);
                }
                c.restore();
            }
            // Bedienfeld
            float y0 = by + bw, cs = Math.min((H - y0) / 3.3f, W / 7f), x0 = W * 0.05f, yc = y0 + (H - y0 - 3 * cs) / 2;
            float fx = W * 0.95f - 2 * cs;
            bt[0].set(x0 + cs, yc, x0 + 2 * cs, yc + cs);
            bt[1].set(x0 + 2 * cs, yc + cs, x0 + 3 * cs, yc + 2 * cs);
            bt[2].set(x0 + cs, yc + 2 * cs, x0 + 2 * cs, yc + 3 * cs);
            bt[3].set(x0, yc + cs, x0 + cs, yc + 2 * cs);
            bt[4].set(fx, yc, fx + 2 * cs, yc + 2 * cs);
            bt[5].set(fx, yc + 2.2f * cs, fx + 2 * cs, yc + 3 * cs);
            for (int i = 0; i < 6; i++) {
                RectF r = bt[i];
                p.setColor(0xFF404040); c.drawRoundRect(r, cs * 0.12f, cs * 0.12f, p);
                p.setColor(i == 4 ? 0xFFFF4040 : 0xFFE0E0E0);
                float cx = r.centerX(), cy = r.centerY(), q = cs * 0.28f;
                if (i < 4) {
                    path.reset();
                    if (i == 0) { path.moveTo(cx, cy - q); path.lineTo(cx + q, cy + q); path.lineTo(cx - q, cy + q); }
                    if (i == 1) { path.moveTo(cx + q, cy); path.lineTo(cx - q, cy + q); path.lineTo(cx - q, cy - q); }
                    if (i == 2) { path.moveTo(cx, cy + q); path.lineTo(cx + q, cy - q); path.lineTo(cx - q, cy - q); }
                    if (i == 3) { path.moveTo(cx - q, cy); path.lineTo(cx + q, cy + q); path.lineTo(cx + q, cy - q); }
                    path.close();
                    c.drawPath(path, p);
                } else {
                    tp.setColor(p.getColor()); tp.setTextSize(cs * (i == 4 ? 0.45f : 0.38f)); tp.setTextAlign(Paint.Align.CENTER);
                    c.drawText(i == 4 ? "FIRE" : "UNDO", cx, cy + cs * (i == 4 ? 0.16f : 0.13f), tp);
                    tp.setTextAlign(Paint.Align.LEFT);
                }
            }
        }

        void push(int cx, int cy, int z) {                    // MBuffer
            Eng.mbX[Eng.MB_TOS] = cx; Eng.mbY[Eng.MB_TOS] = cy; Eng.mbZ[Eng.MB_TOS] = z;
            Eng.MB_TOS++;
            if (Eng.MB_TOS == Eng.MaxMBuffer) Eng.MB_TOS = 0;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked(), i = e.getActionIndex(), id = e.getPointerId(i);
            float x = e.getX(i), y = e.getY(i);
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                for (int k = 0; k < 6; k++) if (bt[k].contains(x, y)) {
                    if (btKey[k] == 110) command(110);
                    else { Eng.AddKBuff(btKey[k]); held = btKey[k]; heldId = id; heldTicks = 0; }       // WM_KEYDOWN
                    return true;
                }
                int cx = (int) ((x - bx) / tile), cy = (int) ((y - by) / tile);
                if (x >= bx && y >= by && cx < 16 && cy < 16 && Eng.CurLevel > 0) {
                    boardId = id; downCx = cx; downCy = cy; downT = SystemClock.uptimeMillis();
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
                if (id == heldId) { held = 0; heldId = -1; }
                if (id == boardId) {                           // kurz tippen = fahren (links), lang = schiessen (rechts)
                    boardId = -1;
                    if (a != MotionEvent.ACTION_CANCEL) push(downCx, downCy, SystemClock.uptimeMillis() - downT > 400 ? 2 : 1);
                }
            }
            return true;
        }
    }
}
