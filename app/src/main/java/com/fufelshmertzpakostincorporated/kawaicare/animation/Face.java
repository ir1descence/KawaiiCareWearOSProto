package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.graphics.Point;
import java.util.HashMap;
import java.util.Map;

/**
 * Data Model for a Face.
 * Stores coordinates and file paths for face parts.
 */
public class Face {

    // Defined layers
    public static final String BODY = "body";
    public static final String LEFT_EYEBROW = "left_eyebrow";
    public static final String RIGHT_EYEBROW = "right_eyebrow";
    public static final String LEFT_EYE = "left_eye";
    public static final String RIGHT_EYE = "right_eye";
    public static final String NOSE = "nose";
    public static final String MOUTH = "mouth";

    // Coordinates for each layer (Offset from top-left or center)
    public Map<String, Point> coordinates = new HashMap<>();

    // Paths for each emotion. 
    // Key: AnimState Enum Name (String to avoid circular dependency issues if needed, but Enum is better)
    // Value: Map of Layer Name -> Directory Path
    private Map<AnimationRenderer.AnimState, Map<String, String>> emotionPaths = new HashMap<>();
    
    // Body path (static/consistent across emotions)
    private String bodyPath;

    public Face() {
        // Initialize default coordinates
        coordinates.put(BODY, new Point(0, 0));
        coordinates.put(LEFT_EYEBROW, new Point(0, 0));
        coordinates.put(RIGHT_EYEBROW, new Point(0, 0));
        coordinates.put(LEFT_EYE, new Point(0, 0));
        coordinates.put(RIGHT_EYE, new Point(0, 0));
        coordinates.put(NOSE, new Point(0, 0));
        coordinates.put(MOUTH, new Point(0, 0));
    }

    public void setCoordinate(String layer, int x, int y) {
        coordinates.put(layer, new Point(x, y));
    }

    public Point getCoordinate(String layer) {
        return coordinates.get(layer);
    }

    public void setBodyPath(String path) {
        this.bodyPath = path;
    }

    public String getBodyPath() {
        return bodyPath;
    }

    /**
     * Set the directory path where the sequence of images for a specific part and state are located.
     */
    public void setPartDirectory(AnimationRenderer.AnimState state, String layer, String dirPath) {
        emotionPaths.putIfAbsent(state, new HashMap<>());
        emotionPaths.get(state).put(layer, dirPath);
    }

    public String getPartDirectory(AnimationRenderer.AnimState state, String layer) {
        if (!emotionPaths.containsKey(state)) return null;
        return emotionPaths.get(state).get(layer);
    }

    /**
     * Initializes all face parts for a given emotion state based on a root directory.
     * Assumes standard folder structure: root/left_eyebrow, root/right_eyebrow, etc.
     */
    public void initializeEmotion(AnimationRenderer.AnimState state, String emotionRootPath) {
        setPartDirectory(state, LEFT_EYEBROW, emotionRootPath + "/left_eyebrow");
        setPartDirectory(state, RIGHT_EYEBROW, emotionRootPath + "/right_eyebrow");
        setPartDirectory(state, LEFT_EYE, emotionRootPath + "/left_eye");
        setPartDirectory(state, RIGHT_EYE, emotionRootPath + "/right_eye");
        setPartDirectory(state, NOSE, emotionRootPath + "/nose");
        setPartDirectory(state, MOUTH, emotionRootPath + "/mouth");
    }
}
