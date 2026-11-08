package com.mario.assistant;

import android.graphics.Bitmap;

import java.util.Arrays;
import java.util.Map;

/** 手绘 8-bit 精灵：金币、马里奥、蘑菇、炸弹、云、?/! 方块、砖块。 */
final class Sprites {
    private Sprites() { }

    private static final Map<Character, Integer> COIN_PAL = PixelArt.palette(
            new char[]{'o', 'y', 'l', 'd'},
            new int[]{0xFF1A0F00, 0xFFFBD000, 0xFFFFF3B0, 0xFFC98A00});
    private static final String[] COIN = {
            "...oooooo...",
            "..oyyyyyyo..",
            ".oyllyyyyydo",
            ".oylyooyyydo",
            "oylyyooyyydo",
            "oylyyooyyydo",
            "oylyyooyyydo",
            "oylyyooyyydo",
            "oylyyooyyydo",
            "oylyyooyyydo",
            ".oylyooyyydo",
            ".oyllyyyyydo",
            "..oyyyyyyo..",
            "...oooooo...",
    };

    private static final Map<Character, Integer> MARIO_PAL = PixelArt.palette(
            new char[]{'r', 's', 'h', 'k', 'b', 'y'},
            new int[]{0xFFE52521, 0xFFFDBF93, 0xFF6B3E12, 0xFF1A1000, 0xFF2A44C8, 0xFFFBD000});
    private static final String[] MARIO = {
            "......rrrrr.....",
            ".....rrrrrrrrr..",
            ".....hhhssks....",
            "....hshsssksss..",
            "....hshhsssksss.",
            "....hhsssskkkk..",
            "......ssssss....",
            "....rrrrbbrr....",
            "...rrrbbbbrrr...",
            "..rrrrbbbbrrrr..",
            "..ssrbbbbbbrss..",
            "..sbybbbbbbybs..",
            "..bbbbbbbbbbbb..",
            "...bbb....bbb...",
            "..hhh......hhh..",
            ".hhhh......hhhh.",
    };

    private static final Map<Character, Integer> MUSHROOM_PAL = PixelArt.palette(
            new char[]{'r', 'w', 'k'},
            new int[]{0xFFE52521, 0xFFFFF3E0, 0xFF1A1000});
    private static final String[] MUSHROOM = {
            "...rrrrrr...",
            ".rrwwrrwwrr.",
            "rrwwrrrrwwrr",
            "rrrrrwwrrrrr",
            "rrrrwwwwrrrr",
            ".rrrrrrrrrr.",
            "..wwwwwwww..",
            "..wkkwwkkw..",
            "..wwwwwwww..",
            "...wwwwww...",
    };

    private static final Map<Character, Integer> BOMB_PAL = PixelArt.palette(
            new char[]{'k', 'w', 'y'},
            new int[]{0xFF26221F, 0xFFFFFFFF, 0xFFFBD000});
    private static final String[] BOMB = {
            ".......yy...",
            "......yy....",
            "....kkkk....",
            "...kkkkkk...",
            "..kkkkkkkk..",
            ".kkkkkkkkkk.",
            ".kwwkkkkwwk.",
            ".kkkkkkkkkk.",
            "..kkkkkkkk..",
            "...kkkkkk...",
            "..yy....yy..",
    };

    private static final Map<Character, Integer> CLOUD_PAL = PixelArt.palette(
            new char[]{'w'}, new int[]{0xFFFFFFFF});
    private static final String[] CLOUD = {
            "....wwww......",
            "..wwwwwwww....",
            ".wwwwwwwwwww..",
            "wwwwwwwwwwwwww",
            ".wwwwwwwwwwww.",
    };

    private static final String[] QMARK = {
            "..wwww..",
            ".wwwwww.",
            ".ww..ww.",
            ".....ww.",
            "....ww..",
            "...ww...",
            "...ww...",
            "........",
            "...ww...",
            "...ww...",
    };

    private static final String[] EXCL = {
            ".ww.",
            ".ww.",
            ".ww.",
            ".ww.",
            ".ww.",
            "....",
            ".ww.",
            ".ww.",
    };

    static Bitmap coin() { return PixelArt.render(COIN, COIN_PAL); }

    static Bitmap mario() { return PixelArt.render(MARIO, MARIO_PAL); }

    static Bitmap mushroom() { return PixelArt.render(MUSHROOM, MUSHROOM_PAL); }

    static Bitmap bomb() { return PixelArt.render(BOMB, BOMB_PAL); }

    static Bitmap cloud() { return PixelArt.render(CLOUD, CLOUD_PAL); }

    static Bitmap questionBlock() { return glyphBlock(QMARK, false); }

    static Bitmap exclaimBlock() { return glyphBlock(EXCL, true); }

    /** ?/! 方块：黑描边 + 上左亮边 + 下右暗边 + 四角铆钉 + 居中字形。 */
    private static Bitmap glyphBlock(String[] glyph, boolean danger) {
        int s = 16;
        char[][] g = new char[s][s];
        for (char[] row : g) Arrays.fill(row, 'y');
        for (int i = 0; i < s; i++) { g[0][i] = 'o'; g[s - 1][i] = 'o'; g[i][0] = 'o'; g[i][s - 1] = 'o'; }
        for (int i = 2; i < s - 2; i++) {
            g[1][i] = 'l'; g[i][1] = 'l';
            g[s - 2][i] = 'd'; g[i][s - 2] = 'd';
        }
        g[2][2] = g[2][s - 3] = g[s - 3][2] = g[s - 3][s - 3] = 'd';
        int gy = (s - glyph.length) / 2, gx = (s - glyph[0].length()) / 2;
        for (int y = 0; y < glyph.length; y++)
            for (int x = 0; x < glyph[y].length(); x++)
                if (glyph[y].charAt(x) != '.') g[gy + y][gx + x] = 'w';
        int[] colors = danger
                ? new int[]{0xFF1A0F00, 0xFFD82800, 0xFFF87858, 0xFF7A0F00, 0xFFFFFFFF}
                : new int[]{0xFF1A0F00, 0xFFFC9838, 0xFFFFD9A0, 0xFFB4560A, 0xFFFFF6E0};
        return PixelArt.render(g, PixelArt.palette(new char[]{'o', 'y', 'l', 'd', 'w'}, colors));
    }

    /** 砖块地砖：两皮砖、错缝、黑色灰浆（可横向平铺）。 */
    static Bitmap brickTile() {
        int s = 16;
        char[][] g = new char[s][s];
        for (char[] row : g) Arrays.fill(row, 'b');
        for (int x = 0; x < s; x++) { g[0][x] = 'l'; g[8][x] = 'l'; }
        for (int x = 0; x < s; x++) { g[7][x] = 'o'; g[15][x] = 'o'; }
        for (int y = 0; y < 7; y++) g[y][7] = 'o';
        for (int y = 8; y < 15; y++) { g[y][3] = 'o'; g[y][11] = 'o'; }
        return PixelArt.render(g, PixelArt.palette(
                new char[]{'b', 'o', 'l'},
                new int[]{0xFFC84C0C, 0xFF7A2800, 0xFFFC9838}));
    }
}
