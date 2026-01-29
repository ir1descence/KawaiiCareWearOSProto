package com.fufelshmertzpakostincorporated.kawaicare.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Session Manager for managing authentication state.
 * Uses EncryptedSharedPreferences to securely store the authToken.
 */
public class SessionManager {

    private static final String TAG = "SessionManager";
    private static final String PREFS_FILE_NAME = "kawaicare_secure_prefs";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences sharedPreferences;
    private static volatile SessionManager instance;

    /**
     * Private constructor to enforce singleton pattern.
     * @param context Application context
     */
    private SessionManager(Context context) {
        sharedPreferences = createEncryptedPreferences(context);
    }

    /**
     * Get singleton instance of SessionManager.
     * Uses double-checked locking with volatile for thread safety.
     * @param context Application context
     * @return SessionManager instance
     */
    public static SessionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * Creates encrypted SharedPreferences using AndroidX Security library.
     * Falls back to regular SharedPreferences if encryption fails.
     */
    private SharedPreferences createEncryptedPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to regular", e);
            // Fallback to regular SharedPreferences (not recommended for production)
            return context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    /**
     * Save authentication token.
     * @param token The authentication token to save
     * @return true if saved successfully
     */
    public boolean saveAuthToken(String token) {
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "Attempted to save null or empty token");
            return false;
        }

        try {
            return sharedPreferences.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "Error saving auth token", e);
            return false;
        }
    }

    /**
     * Get the stored authentication token.
     * @return The auth token, or null if not present
     */
    public String getAuthToken() {
        try {
            return sharedPreferences.getString(KEY_AUTH_TOKEN, null);
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving auth token", e);
            return null;
        }
    }

    /**
     * Check if user is authenticated.
     * @return true if auth token exists
     */
    public boolean isAuthenticated() {
        String token = getAuthToken();
        return token != null && !token.isEmpty();
    }

    /**
     * Save user ID.
     * @param userId The user ID to save
     */
    public void saveUserId(String userId) {
        sharedPreferences.edit()
                .putString(KEY_USER_ID, userId)
                .apply();
    }

    /**
     * Get stored user ID.
     * @return User ID or null
     */
    public String getUserId() {
        return sharedPreferences.getString(KEY_USER_ID, null);
    }

    /**
     * Save device ID for this watch.
     * @param deviceId The device ID to save
     */
    public void saveDeviceId(String deviceId) {
        sharedPreferences.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .apply();
    }

    /**
     * Get stored device ID.
     * @return Device ID or null
     */
    public String getDeviceId() {
        return sharedPreferences.getString(KEY_DEVICE_ID, null);
    }

    /**
     * Clear all session data (logout).
     */
    public void clearSession() {
        try {
            sharedPreferences.edit()
                    .remove(KEY_AUTH_TOKEN)
                    .remove(KEY_USER_ID)
                    // Keep device ID even after logout
                    .commit();
            Log.i(TAG, "Session cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing session", e);
        }
    }

    /**
     * Clear all data including device ID.
     */
    public void clearAllData() {
        try {
            sharedPreferences.edit()
                    .clear()
                    .commit();
            Log.i(TAG, "All data cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing all data", e);
        }
    }
}
