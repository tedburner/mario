package com.mario.assistant.application;

import android.os.Handler;
import com.mario.assistant.infrastructure.a11y.Gestures;
import com.mario.assistant.infrastructure.a11y.UiActions;
import com.mario.assistant.infrastructure.a11y.UiTreeSource;
import com.mario.assistant.infrastructure.ocr.MlKitTextReader;
import com.mario.assistant.infrastructure.system.DeviceEnvironment;
import com.mario.assistant.infrastructure.ui.RunLog;

/** 任务运行器可用的公共能力；由组合根实现，运行器不直接持有 AccessibilityService。 */
public interface RunHost {
    long now();
    Handler handler();
    DeviceEnvironment env();
    UiTreeSource trees();
    UiActions uiActions();
    Gestures gestures();
    MlKitTextReader ocr();
    RunLog log();
    void startForeground();
    void stopForeground();
}
