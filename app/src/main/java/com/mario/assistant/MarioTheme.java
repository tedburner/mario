package com.mario.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** NES 马里奥主题：调色板、像素斜面按钮、HUD 文本、SMB 对话底板。 */
final class MarioTheme {
    static final int SKY = 0xFF5C94FC;
    private static final int OUTLINE = 0xFF1A0F00;
    /** face / light / dark 三色组。 */
    static final int[] PIPE = {0xFF00A800, 0xFF80D010, 0xFF005800};
    static final int[] BRICK = {0xFFC84C0C, 0xFFFC9838, 0xFF7A2800};
    static final int[] DANGER = {0xFFD82800, 0xFFF87858, 0xFF7A0F00};

    private MarioTheme() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** HUD/标题文本：等宽加粗 + 硬边投影（像素字效果）。 */
    static TextView label(Context c, String text, float sp, int color) {
        return label(c, text, sp, color, true);
    }

    static TextView label(Context c, String text, float sp, int color, boolean shadow) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        t.setTextSize(sp);
        if (shadow) t.setShadowLayer(0, dp(c, 1.5f), dp(c, 1.5f), 0x99000000);
        return t;
    }

    /** 方块斜面底图：黑描边 + 上左亮边 + 下右暗边，拉伸后即大像素斜面。 */
    private static Bitmap blockBitmap(int face, int light, int dark, float darken) {
        int s = 16;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        b.eraseColor(OUTLINE);
        int f = shade(face, darken), l = shade(light, darken), d = shade(dark, darken);
        for (int y = 1; y < s - 1; y++)
            for (int x = 1; x < s - 1; x++)
                b.setPixel(x, y, f);
        for (int i = 2; i < s - 2; i++) {
            b.setPixel(i, 1, l); b.setPixel(1, i, l);
            b.setPixel(i, s - 2, d); b.setPixel(s - 2, i, d);
        }
        return b;
    }

    static int shade(int color, float factor) {
        int r = Math.min(0xFF, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(0xFF, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(0xFF, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 按钮背景：常态 + 按压态（整体压暗）。 */
    static Drawable blockButtonBg(Context c, int[] colors) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                wrap(c, blockBitmap(colors[0], colors[1], colors[2], 0.62f)));
        sld.addState(new int[0], wrap(c, blockBitmap(colors[0], colors[1], colors[2], 1f)));
        return sld;
    }

    private static BitmapDrawable wrap(Context c, Bitmap b) {
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setFilterBitmap(false);
        return d;
    }

    /** SMB 对话底板：白底黑框、像素圆角。按 dp 绘制，避免位图拉伸导致边框过粗。 */
    static Drawable messageBoxBg(Context c) {
        int border = dp(c, 3), cut = dp(c, 5);
        android.graphics.Paint paint = new android.graphics.Paint();
        return new android.graphics.drawable.Drawable() {
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                paint.setColor(OUTLINE);
                canvas.drawRect(b, paint);
                paint.setColor(0xFFFFFFFF);
                canvas.drawRect(b.left + border, b.top + border, b.right - border, b.bottom - border, paint);
                paint.setColor(SKY); // 与天空同色切角，形成像素圆角
                canvas.drawRect(b.left, b.top, b.left + cut, b.top + cut, paint);
                canvas.drawRect(b.right - cut, b.top, b.right, b.top + cut, paint);
                canvas.drawRect(b.left, b.bottom - cut, b.left + cut, b.bottom, paint);
                canvas.drawRect(b.right - cut, b.bottom - cut, b.right, b.bottom, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** 像素方块按钮：左侧精灵图标 + 等宽粗体白字。 */
    static TextView button(Context c, String text, Drawable icon, int[] colors, float textSp, int heightDp) {
        TextView t = label(c, text, textSp, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        t.setCompoundDrawablePadding(dp(c, 12));
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setHeight(dp(c, heightDp));
        t.setBackground(blockButtonBg(c, colors));
        return t;
    }
}
