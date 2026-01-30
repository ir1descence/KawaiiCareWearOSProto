package com.fufelshmertzpakostincorporated.kawaicare.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper class for Android Network Service Discovery (NSD).
 * 
 * Allows client devices to automatically discover this Wear OS service
 * on the local Wi-Fi network without needing to know the IP address.
 * 
 * Usage:
 * 1. Create NsdHelper with context
 * 2. Call registerService() with service name, type, and port
 * 3. Call unregisterService() when done
 * 
 * Clients can discover services of type "_kawaicare._tcp." using DNS-SD/mDNS.
 */
public class NsdHelper {

    private static final String TAG = "NsdHelper";

    private final Context context;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;

    // Registration state
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);
    private final AtomicReference<String> registeredServiceName = new AtomicReference<>(null);
    private int registeredPort = -1;
    
    // Listeners (held as instance variables to prevent garbage collection)
    private NsdManager.RegistrationListener registrationListener;

    // Discovery state (for clients)
    private NsdManager.DiscoveryListener discoveryListener;
    private final AtomicBoolean isDiscovering = new AtomicBoolean(false);

    // Callback interface for discovery results
    public interface ServiceDiscoveryCallback {
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
        void onServiceResolved(NsdServiceInfo serviceInfo);
        void onError(String error);
    }

    private ServiceDiscoveryCallback discoveryCallback;

    public NsdHelper(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is NULL - NSD will not work!");
        } else {
            Log.d(TAG, "NsdHelper initialized with NsdManager");
        }
    }

    // =========================================
    // Service Registration (Server Side)
    // =========================================

    /**
     * Register this device as a network service for discovery.
     * 
     * @param serviceName Base name for the service (may be modified if duplicates exist)
     * @param serviceType Service type (e.g., "_kawaicare._tcp.")
     * @param port Port number the server is listening on
     */
    public void registerService(String serviceName, String serviceType, int port) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available - cannot register service!");
            return;
        }

        if (isRegistered.get()) {
            Log.w(TAG, "Service already registered, unregistering first");
            unregisterService();
        }

        // Log detailed registration info
        Log.i(TAG, "╔══════════════════════════════════════════════════════════════");
        Log.i(TAG, "║ NSD SERVICE REGISTRATION");
        Log.i(TAG, "╠══════════════════════════════════════════════════════════════");
        Log.i(TAG, "║ Service Name: " + serviceName);
        Log.i(TAG, "║ Service Type: " + serviceType);
        Log.i(TAG, "║ Port: " + port);
        Log.i(TAG, "║ Device: " + Build.MODEL);
        
        // Log Wi-Fi info for debugging
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                int ipInt = wifiInfo.getIpAddress();
                String ip = String.format("%d.%d.%d.%d",
                        (ipInt & 0xff),
                        (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff),
                        (ipInt >> 24 & 0xff));
                Log.i(TAG, "║ Wi-Fi SSID: " + wifiInfo.getSSID());
                Log.i(TAG, "║ Wi-Fi IP: " + ip);
                Log.i(TAG, "║ Wi-Fi RSSI: " + wifiInfo.getRssi() + " dBm");
            } else {
                Log.w(TAG, "║ Wi-Fi Info: Not available (null)");
            }
        } else {
            Log.w(TAG, "║ WifiManager: Not available");
        }
        Log.i(TAG, "╚══════════════════════════════════════════════════════════════");

        // Create the service info
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setPort(port);

        // Create registration listener with detailed logging
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                String errorStr = errorCodeToString(errorCode);
                Log.e(TAG, "╔══════════════════════════════════════════════════════════════");
                Log.e(TAG, "║ ❌ NSD REGISTRATION FAILED!");
                Log.e(TAG, "╠══════════════════════════════════════════════════════════════");
                Log.e(TAG, "║ Error Code: " + errorCode + " (" + errorStr + ")");
                Log.e(TAG, "║ Service Name: " + serviceInfo.getServiceName());
                Log.e(TAG, "║ Service Type: " + serviceInfo.getServiceType());
                Log.e(TAG, "║ Port: " + serviceInfo.getPort());
                Log.e(TAG, "╠══════════════════════════════════════════════════════════════");
                
                // Provide troubleshooting hints
                switch (errorCode) {
                    case NsdManager.FAILURE_ALREADY_ACTIVE:
                        Log.e(TAG, "║ HINT: Another service is already using this name or port.");
                        Log.e(TAG, "║       Try using a unique service name.");
                        break;
                    case NsdManager.FAILURE_INTERNAL_ERROR:
                        Log.e(TAG, "║ HINT: Internal error in NSD daemon. Try restarting Wi-Fi.");
                        break;
                    case NsdManager.FAILURE_MAX_LIMIT:
                        Log.e(TAG, "║ HINT: Maximum number of NSD services reached.");
                        break;
                    default:
                        Log.e(TAG, "║ HINT: Check Wi-Fi connection and multicast permissions.");
                        break;
                }
                Log.e(TAG, "╚══════════════════════════════════════════════════════════════");
                
                isRegistered.set(false);
                registeredPort = -1;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Service unregistration failed: " + errorCodeToString(errorCode));
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // The actual registered name may differ due to conflict resolution
                String actualName = serviceInfo.getServiceName();
                registeredServiceName.set(actualName);
                registeredPort = port;
                isRegistered.set(true);
                
                Log.i(TAG, "╔══════════════════════════════════════════════════════════════");
                Log.i(TAG, "║ ✅ NSD SERVICE REGISTERED SUCCESSFULLY!");
                Log.i(TAG, "╠══════════════════════════════════════════════════════════════");
                Log.i(TAG, "║ Registered Name: " + actualName);
                Log.i(TAG, "║ Service Type: " + serviceType);
                Log.i(TAG, "║ Port: " + port);
                Log.i(TAG, "╠══════════════════════════════════════════════════════════════");
                Log.i(TAG, "║ Clients should discover with type: " + serviceType);
                Log.i(TAG, "╚══════════════════════════════════════════════════════════════");
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registeredServiceName.set(null);
                registeredPort = -1;
                isRegistered.set(false);
                Log.i(TAG, "Service unregistered: " + serviceInfo.getServiceName());
            }
        };

        try {
            nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener);
            Log.d(TAG, "registerService() called - waiting for callback...");
        } catch (Exception e) {
            Log.e(TAG, "Exception calling registerService()", e);
        }
    }

    /**
     * Unregister the network service.
     */
    public void unregisterService() {
        if (nsdManager == null || registrationListener == null) {
            return;
        }

        if (!isRegistered.get()) {
            Log.d(TAG, "Service not registered, nothing to unregister");
            return;
        }

        try {
            nsdManager.unregisterService(registrationListener);
            Log.d(TAG, "Unregistering service");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Service was not registered or already unregistered");
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering service", e);
        } finally {
            isRegistered.set(false);
            registeredServiceName.set(null);
        }
    }

    /**
     * Check if the service is currently registered.
     */
    public boolean isServiceRegistered() {
        return isRegistered.get();
    }

    /**
     * Get the actual registered service name (may differ from requested name).
     */
    public String getRegisteredServiceName() {
        return registeredServiceName.get();
    }

    /**
     * Get the registered port number.
     * @return port number or -1 if not registered
     */
    public int getRegisteredPort() {
        return isRegistered.get() ? registeredPort : -1;
    }

    // =========================================
    // Service Discovery (Client Side)
    // =========================================

    /**
     * Start discovering services of the specified type.
     * This is primarily for client devices looking for the Wear OS service.
     * 
     * @param serviceType Service type to discover (e.g., "_kawaicare._tcp.")
     * @param callback Callback for discovery events
     */
    public void startDiscovery(String serviceType, ServiceDiscoveryCallback callback) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            if (callback != null) {
                callback.onError("NsdManager not available");
            }
            return;
        }

        if (isDiscovering.get()) {
            Log.w(TAG, "Discovery already in progress");
            return;
        }

        this.discoveryCallback = callback;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCodeToString(errorCode));
                isDiscovering.set(false);
                if (discoveryCallback != null) {
                    discoveryCallback.onError("Discovery start failed: " + errorCodeToString(errorCode));
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: " + errorCodeToString(errorCode));
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started for: " + serviceType);
                isDiscovering.set(true);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped for: " + serviceType);
                isDiscovering.set(false);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                if (discoveryCallback != null) {
                    discoveryCallback.onServiceFound(serviceInfo);
                }
                // Automatically resolve the service to get IP and port
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service lost: " + serviceInfo.getServiceName());
                if (discoveryCallback != null) {
                    discoveryCallback.onServiceLost(serviceInfo);
                }
            }
        };

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            Log.d(TAG, "Starting discovery for: " + serviceType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start discovery", e);
            if (callback != null) {
                callback.onError("Failed to start discovery: " + e.getMessage());
            }
        }
    }

    /**
     * Stop service discovery.
     */
    public void stopDiscovery() {
        if (nsdManager == null || discoveryListener == null) {
            return;
        }

        if (!isDiscovering.get()) {
            Log.d(TAG, "Discovery not in progress");
            return;
        }

        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
            Log.d(TAG, "Stopping discovery");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Discovery was not started or already stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping discovery", e);
        } finally {
            isDiscovering.set(false);
        }
    }

    /**
     * Resolve a discovered service to get its IP address and port.
     */
    private void resolveService(NsdServiceInfo serviceInfo) {
        if (nsdManager == null) {
            return;
        }

        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed for " + serviceInfo.getServiceName() + 
                        ": " + errorCodeToString(errorCode));
                if (discoveryCallback != null) {
                    discoveryCallback.onError("Resolve failed: " + errorCodeToString(errorCode));
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service resolved: " + serviceInfo.getServiceName() +
                        " at " + serviceInfo.getHost() + ":" + serviceInfo.getPort());
                if (discoveryCallback != null) {
                    discoveryCallback.onServiceResolved(serviceInfo);
                }
            }
        });
    }

    /**
     * Check if discovery is currently in progress.
     */
    public boolean isDiscovering() {
        return isDiscovering.get();
    }

    // =========================================
    // Utility Methods
    // =========================================

    /**
     * Convert NSD error code to human-readable string.
     */
    private static String errorCodeToString(int errorCode) {
        switch (errorCode) {
            case NsdManager.FAILURE_ALREADY_ACTIVE:
                return "FAILURE_ALREADY_ACTIVE";
            case NsdManager.FAILURE_INTERNAL_ERROR:
                return "FAILURE_INTERNAL_ERROR";
            case NsdManager.FAILURE_MAX_LIMIT:
                return "FAILURE_MAX_LIMIT";
            default:
                return "UNKNOWN_ERROR_" + errorCode;
        }
    }

    /**
     * Clean up all resources.
     * Call this when the helper is no longer needed.
     */
    public void tearDown() {
        stopDiscovery();
        unregisterService();
        discoveryCallback = null;
    }
}
