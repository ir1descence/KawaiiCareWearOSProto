package com.fufelshmertzpakostincorporated.kawaicare.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Utility class for gesture file operations.
 * Centralizes gesture directory and file lookup logic to avoid code duplication.
 */
public final class GestureFileUtils {

    /** Directory name for gesture files within app files dir */
    public static final String GESTURES_DIR = "gestures";
    
    /** File extension for gesture files */
    public static final String GESTURE_FILE_EXTENSION = ".gesture";

    private GestureFileUtils() {
        // Prevent instantiation
    }

    /**
     * Get the gestures directory, creating it if necessary.
     * 
     * @param context Application context
     * @return The gestures directory File object
     */
    @NonNull
    public static File getGesturesDirectory(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), GESTURES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Find the newest gesture file in the gestures directory.
     * 
     * @param context Application context
     * @return The newest gesture file, or null if none exists
     */
    @Nullable
    public static File getNewestGestureFile(@NonNull Context context) {
        File gesturesDir = new File(context.getFilesDir(), GESTURES_DIR);
        if (!gesturesDir.exists()) {
            return null;
        }

        File[] files = gesturesDir.listFiles((dir, name) -> 
                name.endsWith(GESTURE_FILE_EXTENSION));

        if (files == null || files.length == 0) {
            return null;
        }

        File newest = files[0];
        for (File file : files) {
            if (file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    /**
     * Check if a custom gesture file exists.
     * 
     * @param context Application context
     * @return true if at least one gesture file exists
     */
    public static boolean hasCustomGestureFile(@NonNull Context context) {
        return getNewestGestureFile(context) != null;
    }

    /**
     * Get the path to the newest gesture file.
     * 
     * @param context Application context
     * @return Absolute path or null if no gesture file exists
     */
    @Nullable
    public static String getNewestGestureFilePath(@NonNull Context context) {
        File file = getNewestGestureFile(context);
        return file != null ? file.getAbsolutePath() : null;
    }

    /**
     * Get all gesture files sorted by modification time (newest first).
     * 
     * @param context Application context
     * @return Array of gesture files, or empty array if none exist
     */
    @NonNull
    public static File[] getAllGestureFiles(@NonNull Context context) {
        File gesturesDir = new File(context.getFilesDir(), GESTURES_DIR);
        if (!gesturesDir.exists()) {
            return new File[0];
        }

        File[] files = gesturesDir.listFiles((dir, name) -> 
                name.endsWith(GESTURE_FILE_EXTENSION));

        if (files == null) {
            return new File[0];
        }

        // Sort by modification time, newest first
        java.util.Arrays.sort(files, (f1, f2) -> 
                Long.compare(f2.lastModified(), f1.lastModified()));

        return files;
    }

    /**
     * Delete all gesture files.
     * 
     * @param context Application context
     * @return Number of files deleted
     */
    public static int deleteAllGestureFiles(@NonNull Context context) {
        File[] files = getAllGestureFiles(context);
        int deleted = 0;
        for (File file : files) {
            if (file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Create a new gesture file with a timestamp-based name.
     * 
     * @param context Application context
     * @return The new gesture file (not yet created on disk)
     */
    @NonNull
    public static File createNewGestureFile(@NonNull Context context) {
        File gesturesDir = getGesturesDirectory(context);
        String filename = "gesture_" + System.currentTimeMillis() + GESTURE_FILE_EXTENSION;
        return new File(gesturesDir, filename);
    }
}
