package com.mario.assistant.infrastructure.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.mario.assistant.domain.model.TextBlock;
import com.mario.assistant.infrastructure.a11y.A11yScreenshot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 截图 + ML Kit 中文识别；串行执行，回调统一回到扫描线程。 */
public final class MlKitTextReader {
    private final A11yScreenshot screenshots;
    private final Handler handler;
    private TextRecognizer recognizer;
    private boolean busy;

    public MlKitTextReader(A11yScreenshot screenshots, Handler handler) {
        this.screenshots = screenshots;
        this.handler = handler;
    }

    public boolean busy() {
        return busy;
    }

    /** done 收到 null 表示截图或识别失败；screenshotFailure 仅处理截图错误码。 */
    public void recognize(Consumer<List<TextBlock>> done, IntConsumer screenshotFailure) {
        if (Build.VERSION.SDK_INT < 30) {
            done.accept(null);
            return;
        }
        busy = true;
        screenshots.capture(new A11yScreenshot.Callback() {
            @Override public void onBitmap(Bitmap image) {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                }
                recognizer.process(InputImage.fromBitmap(image, 0))
                        .addOnSuccessListener(text -> handler.post(() -> {
                            busy = false;
                            done.accept(toBlocks(text));
                        }))
                        .addOnFailureListener(error -> handler.post(() -> {
                            busy = false;
                            done.accept(null);
                        }))
                        .addOnCompleteListener(task -> handler.post(image::recycle));
            }

            @Override public void onFailure(int errorCode) {
                busy = false;
                handler.post(() -> {
                    if (screenshotFailure != null) screenshotFailure.accept(errorCode);
                    else done.accept(null);
                });
            }
        });
    }

    private static List<TextBlock> toBlocks(Text text) {
        List<TextBlock> blocks = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect rect = line.getBoundingBox();
                if (rect != null) {
                    blocks.add(new TextBlock(line.getText().replace(" ", ""),
                            rect.left, rect.top, rect.right, rect.bottom));
                }
            }
        }
        return blocks;
    }

    public void close() {
        if (recognizer != null) recognizer.close();
    }
}
