package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;

import java.io.IOException;
import java.io.InputStream;
import android.util.Log;

/**
 * Composites the face animation frames into a single bitmap.
 * Handles memory optimization via Bitmap pooling.
 */
public class FaceCompositor {

    private final Context context;
    private final Face face;
    private final int width;
    private final int height;

    // The Mutable Master Bitmap
    private Bitmap masterBitmap;
    private Canvas masterCanvas;
    private Paint paint;

    // Bitmap Pooling for decoding layers
    private Bitmap layerBuffer; 
    private BitmapFactory.Options options;

    private float scaleFactor = 1.0f;

    public FaceCompositor(Context context, Face face, int width, int height) {
        this.context = context;
        this.face = face;
        this.width = width;
        this.height = height;
        initialize();
    }

    private void initialize() {
        // 0. Calculate Scale Factor based on Body dimensions vs Screen dimensions
        calculateScaleFactor();

        // 1. Create Master Bitmap (Mutable)
        masterBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        masterCanvas = new Canvas(masterBitmap);
        
        paint = new Paint();
        paint.setFilterBitmap(true);

        // 2. Setup Options for Reuse
        options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inSampleSize = 1; 
    }

    private void calculateScaleFactor() {
        String bodyPath = face.getBodyPath();
        if (bodyPath == null) return;

        try {
            InputStream is = context.getAssets().open(bodyPath);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            is.close();

            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                 // Use max(scaleX, scaleY) to fill the screen (CENTER_CROP style strategy)
                 // or min(scaleX, scaleY) to fit entirely (FIT_CENTER)
                 // "Body should suite to screen size" -> Filling is usually safer for backgrounds.
                 float scaleX = (float) width / bounds.outWidth;
                 float scaleY = (float) height / bounds.outHeight;
                 scaleFactor = Math.max(scaleX, scaleY);
            }
        } catch (IOException e) {
            // Keep default scale 1.0
        }
    }

    /**
     * Composes a frame for the given state and frame index.
     * @param state Current animation state.
     * @param frameIndex Current frame number.
     */
    public void composeFrame(AnimationRenderer.AnimState state, int frameIndex) {
        // Clear previous frame
        masterCanvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);

        // Apply Scaling
        masterCanvas.save();
        masterCanvas.scale(scaleFactor, scaleFactor);

        // 1. Draw Body (Bottom Layer)
        // Body is constant or depends on config, usually 0 index or specific file.
        // User said: "exept body - it stays the same". So we load it once? 
        // For simplicity, we redraw it. Cache could be improved here.
        drawLayerIsolate(face.getBodyPath(), face.getCoordinate(Face.BODY));

        // 2. Draw Eyebrows
        drawAnimatedLayer(state, Face.LEFT_EYEBROW, frameIndex);
        drawAnimatedLayer(state, Face.RIGHT_EYEBROW, frameIndex);

        // 3. Draw Eyes
        drawAnimatedLayer(state, Face.LEFT_EYE, frameIndex);
        drawAnimatedLayer(state, Face.RIGHT_EYE, frameIndex);

        // 4. Draw Nose
        drawAnimatedLayer(state, Face.NOSE, frameIndex);

        // 5. Draw Mouth
        drawAnimatedLayer(state, Face.MOUTH, frameIndex);

        masterCanvas.restore();
    }

    public Bitmap getLatestFrame() {
        return masterBitmap;
    }

    // --- Helper Methods ---

    private void drawAnimatedLayer(AnimationRenderer.AnimState state, String layerName, int frameIndex) {
        String dir = face.getPartDirectory(state, layerName);
        if (dir == null) return;

        Point coord = face.getCoordinate(layerName);
        if (coord == null) return;

        // Robust strategy: list available files in the asset dir and pick one using modulo
        AssetManager am = context.getAssets();
        try {
            String[] files = am.list(dir);
            if (files == null || files.length == 0) {
                Log.w("FaceCompositor", "No files found in folder: " + dir);
                return;
            }

            // Choose file by index mod number of files, so missing 0.png vs 1.png is handled
            String chosen = files[frameIndex % files.length];
            String path = dir + "/" + chosen;
            boolean success = drawLayerIsolate(path, coord);
            if (!success) {
                Log.w("FaceCompositor", "Failed to draw layer: " + path);
            }
        } catch (IOException e) {
            Log.w("FaceCompositor", "Error listing assets for: " + dir, e);
        }
    }

    private boolean drawLayerIsolate(String path, Point p) {
        if (path == null) return false;

        Bitmap bitmap = decodeBitmap(path);
        if (bitmap != null) {
            masterCanvas.drawBitmap(bitmap, p.x, p.y, paint);
            return true;
        }
        return false;
    }

    /**
     * Decodes a bitmap from Assets. Uses pooling when safe but avoids throwing reusable inBitmap errors.
     */
    private Bitmap decodeBitmap(String path) {
        AssetManager assetManager = context.getAssets();
        InputStream istr = null;
        try {
            // First, try to open the file to ensure it exists
            istr = assetManager.open(path);

            // Decode bounds first to determine appropriate scaling and size
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(istr, null, bounds);
            istr.close();

            int srcW = bounds.outWidth;
            int srcH = bounds.outHeight;
            if (srcW <= 0 || srcH <= 0) {
                Log.w("FaceCompositor", "Invalid image bounds for: " + path);
                return null;
            }

            // Re-open stream to actually decode
            istr = assetManager.open(path);

            // Choose inSampleSize so image is not larger than screen after scaling
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decodeOpts.inMutable = true;

            // If source is huge, downsample proportionally to master size to save memory
            int desiredW = (int) Math.ceil(width / scaleFactor);
            int desiredH = (int) Math.ceil(height / scaleFactor);
            int inSample = 1;
            while ((srcW / inSample) > desiredW * 2 || (srcH / inSample) > desiredH * 2) {
                inSample *= 2;
            }
            decodeOpts.inSampleSize = inSample;

            // Attempt to reuse buffer if sizes match approximately
            if (layerBuffer != null) {
                try {
                    decodeOpts.inBitmap = layerBuffer;
                } catch (IllegalArgumentException e) {
                    // ignore, we'll decode without reuse
                    decodeOpts.inBitmap = null;
                }
            }

            try {
                Bitmap decoded = BitmapFactory.decodeStream(istr, null, decodeOpts);
                if (decoded != null) {
                    layerBuffer = decoded; // reuse for future decodes (best-effort)
                    return decoded;
                }
            } catch (IllegalArgumentException e) {
                // inBitmap not compatible — retry without it
                Log.w("FaceCompositor", "inBitmap reuse failed for: " + path + " — retrying without reuse");
                decodeOpts.inBitmap = null;
                try { istr.close(); } catch (IOException ignore) { }
                istr = assetManager.open(path);
                Bitmap decoded = BitmapFactory.decodeStream(istr, null, decodeOpts);
                if (decoded != null) {
                    layerBuffer = decoded;
                    return decoded;
                }
            }

        } catch (IOException e) {
            Log.w("FaceCompositor", "Asset not found or IO error: " + path + " — " + e.getMessage());
            return null;
        } finally {
            if (istr != null) {
                try { istr.close(); } catch (IOException e) { }
            }
        }
        return null;
    }
}
