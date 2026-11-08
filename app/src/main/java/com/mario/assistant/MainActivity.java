package com.mario.assistant;

import android.app.Activity;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView serviceStatus;
    private TextView resultStatus;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("Mario 任务助手");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);
        TextView status = new TextView(this);
        status.setText("开发中：森林仅可试运行已完成浏览任务的领取。起点试运行广告任务行（激励任务、做任务领惊喜福利、完成3个广告任务得奖励，按此优先级）：以广告平台披露行为锚点点击底部按钮，文案不限；下载/安装/充值/支付类按钮自动跳过，落地页内不做任何点击。已领取/不可用的行自动跳过。截图只在设备内存中处理，不上传、不保存。");
        status.setTextSize(16);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);
        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(16);
        root.addView(serviceStatus);
        Button permission = new Button(this);
        permission.setText("打开无障碍设置");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(permission);
        TextView checklist = new TextView(this);
        checklist.setText("运行前检查（HyperOS 防冻结，重要）：\n"
                + "1. 允许通知权限——通知栏可见运行状态\n"
                + "2. 省电策略设为“无限制”：设置 → 应用管理 →\n"
                + "    Mario 任务助手 → 省电策略 → 无限制\n"
                + "3. 最近任务里长按本应用卡片 → 点“锁定”🔒\n"
                + "4. 试运行期间不要划掉本应用\n"
                + "5. 点击广告跳转后系统可能短暂冻结本应用\n"
                + "    （约 1–2 分钟），期间请耐心等待，解冻后自动\n"
                + "    继续；期间触屏会导致流程如实停止");
        checklist.setTextSize(14);
        checklist.setPadding(0, pad, 0, pad / 2);
        root.addView(checklist);
        Button appInfo = new Button(this);
        appInfo.setText("打开本应用详情（省电策略/自启动）");
        appInfo.setOnClickListener(v -> {
            Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            detail.setData(android.net.Uri.fromParts("package", getPackageName(), null));
            startActivity(detail);
        });
        root.addView(appInfo);
        Button claim = new Button(this);
        claim.setText("试运行：领取森林浏览任务");
        claim.setOnClickListener(v -> {
            TaskService service = TaskService.connected();
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.eg.android.AlipayGphone");
            if (service == null || launch == null) {
                resultStatus.setText(service == null ? "请先开启无障碍服务。" : "未找到支付宝。");
                return;
            }
            service.arm();
            startActivity(launch);
        });
        root.addView(claim);
        Button openReading = new Button(this);
        openReading.setText("试运行：起点广告任务（激励/限时/章节卡）");
        openReading.setOnClickListener(v -> {
            TaskService service = TaskService.connected();
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.qidian.QDReader");
            if (service == null || launch == null) {
                resultStatus.setText(service == null ? "请先开启无障碍服务。" : "未找到起点读书。");
                return;
            }
            service.inspectReading();
            startActivity(launch);
        });
        root.addView(openReading);
        Button stop = new Button(this);
        stop.setText("停止试运行");
        stop.setOnClickListener(v -> {
            TaskService service = TaskService.connected();
            if (service != null) service.cancel();
            refreshResult();
        });
        root.addView(stop);
        resultStatus = new TextView(this);
        resultStatus.setTextSize(16);
        resultStatus.setPadding(0, pad, 0, pad);
        root.addView(resultStatus);
        for (String[] task : new String[][]{
                {"蚂蚁森林", "领取已浏览任务 · 已真机验证"},
                {"蚂蚁庄园", "待适配"},
                {"芭芭农场", "待适配"},
                {"起点读书", "广告任务全自动试运行 · 返回验证中"}}) {
            TextView item = new TextView(this);
            item.setText(task[0] + "  ·  " + task[1]);
            item.setTextSize(17);
            item.setPadding(0, pad, 0, pad / 2);
            root.addView(item);
        }
        setContentView(scroll);
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
    private void refreshResult() {
        resultStatus.setText(getSharedPreferences("mario", MODE_PRIVATE).getString("last_result", "尚无运行记录。"));
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
        serviceStatus.setText(enabled ? "无障碍服务：已开启" : "无障碍服务：未开启");
        refreshResult();
    }
}
