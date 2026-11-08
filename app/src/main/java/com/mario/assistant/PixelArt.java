package com.mario.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/** 8-bit 像素画：字符矩阵 + 调色板 → Bitmap；最近邻缩放保持硬边。 */
final class PixelArt {
    private PixelArt() { }

    /** 每行等长，'.' 为透明；palette 需覆盖其余出现的字符。 */
    static Bitmap render(String[] rows, Map<Character, Integer> palette) {
        char[][] grid = new char[rows.length][];
        for (int i = 0; i < rows.length; i++) grid[i] = rows[i].toCharArray();
        return render(grid, palette);
    }

    static Bitmap render(char[][] grid, Map<Character, Integer> palette) {
        int h = grid.length, w = grid[0].length;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                char c = grid[y][x];
                bmp.setPixel(x, y, c == '.' ? 0x00000000 : palette.get(c));
            }
        return bmp;
    }

    static Map<Character, Integer> palette(char[] keys, int[] colors) {
        Map<Character, Integer> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) map.put(keys[i], colors[i]);
        return map;
    }

    /** 按目标高度等比最近邻放大（像素风禁止平滑插值）。 */
    static Bitmap scaleToHeight(Bitmap src, int targetHeightPx) {
        int w = Math.max(1, Math.round(src.getWidth() * (float) targetHeightPx / src.getHeight()));
        return scale(src, w, targetHeightPx);
    }

    static Bitmap scale(Bitmap src, int w, int h) {
        return Bitmap.createScaledBitmap(src, w, h, false);
    }

    static Drawable drawable(Context c, Bitmap bmp) {
        BitmapDrawable d = new BitmapDrawable(c.getResources(), bmp);
        d.setFilterBitmap(false); // 关闭双线性过滤，保留硬边像素
        return d;
    }

    /** 放大到指定 dp 高的精灵 drawable（图标用）。 */
    static Drawable sprite(Context c, Bitmap src, float dpHeight) {
        int px = Math.max(1, Math.round(dpHeight * c.getResources().getDisplayMetrics().density));
        return drawable(c, scaleToHeight(src, px));
    }
}
