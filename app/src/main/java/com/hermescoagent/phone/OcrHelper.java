package com.hermescoagent.phone;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

/** On-device OCR via ML Kit. Reads text from a screenshot — the agent's way to
 *  see text in apps whose accessibility tree is empty (games, canvas, video). */
public final class OcrHelper {

    public static JSONObject recognize(Bitmap bmp) {
        JSONObject out = new JSONObject();
        try {
            InputImage image = InputImage.fromBitmap(bmp, 0);
            Text result = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .getResult(); // blocking — caller runs on a background thread

            JSONArray lines = new JSONArray();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    JSONObject l = new JSONObject();
                    l.put("text", line.getText());
                    Rect r = line.getBoundingBox();
                    if (r != null) {
                        JSONArray b = new JSONArray();
                        b.put(r.left); b.put(r.top); b.put(r.right); b.put(r.bottom);
                        l.put("bounds", b);
                    }
                    lines.put(l);
                }
            }
            out.put("ok", true);
            out.put("text", result.getText() == null ? "" : result.getText());
            out.put("lines", lines);
            out.put("count", lines.length());
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", "ocr failed: " + t);
            } catch (Exception ignored) {}
        } finally {
            if (bmp != null) { try { bmp.recycle(); } catch (Throwable ignored) {} }
        }
        return out;
    }
}
