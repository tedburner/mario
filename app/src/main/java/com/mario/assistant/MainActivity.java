package com.mario.assistant;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/** 马里奥像素风主界面：游戏 HUD + 分组按钮 + SMB 对话底板运行结果。 */
public final class MainActivity extends Activity {
    private static final String CHECKLIST = "1. 允许通知权限——通知栏可见运行状态\n"
            + "2. 省电策略设为「无限制」：设置 → 应用管理 → Mario → 省电策略 → 无限制\n"
            + "3. 最近任务里长按本应用卡片 → 点「锁定」\n"
            + "4. 试运行期间不要划掉本应用\n"
            + "5. 点击广告跳转后系统可能短暂冻结本应用（约 1–2 分钟），解冻后自动继续；期间触屏会导致流程如实停止";

    private SharedPreferences prefs;
    private TextView hudService;
    private TextView hudRun;
    private TextView hudState;
    private TextView resultStatus;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mario", MODE_PRIVATE);
        getWindow().setStatusBarColor(MarioTheme.SKY);
        getWindow().setNavigationBarColor(MarioTheme.SKY);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(MarioTheme.SKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MarioTheme.SKY);
        int pad = MarioTheme.dp(this, 18);
        root.setPadding(pad, pad, pad, MarioTheme.dp(this, 100)); // 底部留出地面装饰
        scroll.addView(root);

        root.addView(header());

        TextView hint = MarioTheme.button(this, "出发前检查（防冻结）",
                PixelArt.sprite(this, Sprites.exclaimBlock(), 18), MarioTheme.BRICK, 13, 42);
        hint.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("出发前检查（HyperOS 防冻结）")
                .setMessage(CHECKLIST)
                .setPositiveButton("明白了", null)
                .show());
        root.addView(stacked(hint, 16, 6));

        root.addView(stacked(section("任务试运行"), 14, 6));
        root.addView(stacked(runButton("森林任务", Sprites.mushroom(), MarioTheme.PIPE,
                "未找到支付宝。", "com.eg.android.AlipayGphone", TaskService::arm), 0, 10));
        root.addView(stacked(runButton("起点任务", Sprites.coin(), MarioTheme.PIPE,
                "未找到起点读书。", "com.qidian.QDReader", TaskService::inspectReading), 0, 10));
        TextView stop = MarioTheme.button(this, "停止运行",
                PixelArt.sprite(this, Sprites.bomb(), 24), MarioTheme.DANGER, 17, 54);
        stop.setOnClickListener(v -> {
            TaskService service = TaskService.connected();
            if (service != null) service.cancel();
            refreshAll(false);
        });
        root.addView(stacked(stop, 0, 10));

        root.addView(stacked(section("设置"), 8, 6));
        LinearLayout settings = new LinearLayout(this);
        TextView a11y = MarioTheme.button(this, "无障碍服务",
                PixelArt.sprite(this, Sprites.questionBlock(), 16), MarioTheme.BRICK, 13, 46);
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        TextView info = MarioTheme.button(this, "应用详情",
                PixelArt.sprite(this, Sprites.brickTile(), 16), MarioTheme.BRICK, 13, 46);
        info.setOnClickListener(v -> {
            Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            detail.setData(android.net.Uri.fromParts("package", getPackageName(), null));
            startActivity(detail);
        });
        settings.addView(stretch(a11y));
        settings.addView(gap(10));
        settings.addView(stretch(info));
        root.addView(settings);

        root.addView(stacked(resultBox(), 18, 0));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(MarioTheme.SKY);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(footer(), new FrameLayout.LayoutParams(-1, MarioTheme.dp(this, 74), Gravity.BOTTOM));
        setContentView(frame);

        handleGestureCommand(getIntent());
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("temporary_diagnostic_start", false)) {
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                TaskService service = TaskService.connected();
                Intent launch = getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");
                if (service != null && launch != null) { service.inspectReading(); startActivity(launch); }
            }, 1500);
        }
    }

    /** 云朵背景 + 游戏 HUD（服务 / RUN 金币计数 / 状态）+ 标题。 */
    private View header() {
        FrameLayout h = new FrameLayout(this);
        addCloud(h, Gravity.TOP | Gravity.START, 10, 2, 30);
        addCloud(h, Gravity.TOP | Gravity.END, 30, 24, 38);
        addCloud(h, Gravity.TOP | Gravity.START, 160, 0, 22);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hud = new LinearLayout(this);
        hudService = MarioTheme.label(this, "", 17, 0xFFFFFFFF);
        hud.addView(hudColumn("服务", hudService));
        LinearLayout runRow = new LinearLayout(this);
        runRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView coin = new ImageView(this);
        coin.setImageDrawable(PixelArt.sprite(this, Sprites.coin(), 14));
        runRow.addView(coin);
        hudRun = MarioTheme.label(this, "", 17, 0xFFFFFFFF);
        runRow.addView(hudRun);
        hud.addView(hudColumn("RUN", runRow));
        hudState = MarioTheme.label(this, "", 17, 0xFFFFFFFF);
        hud.addView(hudColumn("状态", hudState));
        inner.addView(hud);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView q1 = blockIcon(20);
        ImageView q2 = blockIcon(20);
        TextView title = MarioTheme.label(this, "MARIO 任务助手", 24, 0xFFFFFFFF);
        titleRow.addView(q1);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = MarioTheme.dp(this, 10);
        tlp.rightMargin = MarioTheme.dp(this, 10);
        titleRow.addView(title, tlp);
        titleRow.addView(q2);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = MarioTheme.dp(this, 18);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        inner.addView(titleRow, titleLp);
        h.addView(inner);
        return h;
    }

    private LinearLayout hudColumn(String label, View value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(MarioTheme.label(this, label, 11, 0xFFC7D4F8));
        col.addView(value);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return col;
    }

    private ImageView blockIcon(float dp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(PixelArt.sprite(this, Sprites.questionBlock(), dp));
        return iv;
    }

    private void addCloud(FrameLayout header, int gravity, int marginDp, int topDp, float sizeDp) {
        ImageView cloud = new ImageView(this);
        cloud.setImageDrawable(PixelArt.sprite(this, Sprites.cloud(), sizeDp));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        if ((gravity & Gravity.START) != 0) lp.leftMargin = MarioTheme.dp(this, marginDp);
        else lp.rightMargin = MarioTheme.dp(this, marginDp);
        lp.topMargin = MarioTheme.dp(this, topDp);
        header.addView(cloud, lp);
    }

    private TextView section(String text) {
        return MarioTheme.label(this, "▶ " + text, 13, 0xFFFFFFFF);
    }

    private TextView runButton(String text, Bitmap icon, int[] colors, String missing,
                               String pkg, Consumer<TaskService> action) {
        TextView button = MarioTheme.button(this, text, PixelArt.sprite(this, icon, 24), colors, 17, 54);
        button.setOnClickListener(v -> {
            TaskService service = TaskService.connected();
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (service == null || launch == null) {
                resultStatus.setText(service == null ? "请先开启无障碍服务。" : missing);
                return;
            }
            action.accept(service);
            startActivity(launch);
            prefs.edit().putInt("run_count", prefs.getInt("run_count", 0) + 1).apply();
            refreshAll(true);
        });
        return button;
    }

    private View resultBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(MarioTheme.messageBoxBg(this));
        int bp = MarioTheme.dp(this, 14);
        box.setPadding(bp, bp, bp, bp);
        box.addView(MarioTheme.label(this, "运行结果", 12, 0xFF8A8A8A, false));
        resultStatus = MarioTheme.label(this, "", 14, 0xFF1A1A1A, false);
        box.addView(resultStatus);
        return box;
    }

    /** 底部地面装饰：砖块地坪 + 马里奥 + 头顶金币（领金币主题）。 */
    private View footer() {
        FrameLayout f = new FrameLayout(this);
        BitmapDrawable ground = new BitmapDrawable(getResources(),
                PixelArt.scale(Sprites.brickTile(), MarioTheme.dp(this, 22), MarioTheme.dp(this, 22)));
        ground.setFilterBitmap(false);
        ground.setTileModeX(Shader.TileMode.REPEAT);
        View groundView = new View(this);
        groundView.setBackground(ground);
        f.addView(groundView, new FrameLayout.LayoutParams(-1, MarioTheme.dp(this, 22), Gravity.BOTTOM));

        ImageView mario = new ImageView(this);
        mario.setImageDrawable(PixelArt.sprite(this, Sprites.mario(), 34));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        mlp.rightMargin = MarioTheme.dp(this, 56);
        mlp.bottomMargin = MarioTheme.dp(this, 20);
        f.addView(mario, mlp);

        ImageView coin = new ImageView(this);
        coin.setImageDrawable(PixelArt.sprite(this, Sprites.coin(), 18));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clp.rightMargin = MarioTheme.dp(this, 64);
        clp.bottomMargin = MarioTheme.dp(this, 58);
        f.addView(coin, clp);
        return f;
    }

    private View stacked(View child, int topMarginDp, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = MarioTheme.dp(this, topMarginDp);
        lp.bottomMargin = MarioTheme.dp(this, bottomMarginDp);
        child.setLayoutParams(lp);
        return child;
    }

    private View stretch(View child) {
        child.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return child;
    }

    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(MarioTheme.dp(this, dp), 1));
        return v;
    }

    private void refreshAll(boolean accessibilityEnabled) {
        TaskService service = TaskService.connected();
        hudService.setText(accessibilityEnabled ? "ON" : "OFF");
        hudService.setTextColor(accessibilityEnabled ? 0xFFB8FF7A : 0xFFFF6B5A);
        hudState.setText(service != null && service.busy() ? "进行中" : "待命");
        hudRun.setText(" ×" + prefs.getInt("run_count", 0));
        resultStatus.setText(prefs.getString("last_result", "尚无运行记录。"));
    }

    /** debug 构建：adb 经 am start 直达手势指令；先退出界面避免遮挡目标窗口。 */
    private void handleGestureCommand(Intent intent) {
        if (intent == null || intent.getStringExtra("gesture_cmd") == null) return;
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        GestureBridge.queueGesture(intent, 700);
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleGestureCommand(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean enabled = false;
        if (manager != null) {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                        && TaskService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) {
                    enabled = true;
                    break;
                }
            }
        }
        refreshAll(enabled);
    }
}
