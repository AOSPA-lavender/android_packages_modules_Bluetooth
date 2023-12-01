/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.bluetooth;

import static android.bluetooth.BluetoothAdapter.STATE_BLE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_ON;
import static android.bluetooth.BluetoothAdapter.STATE_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_ON;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import static com.android.server.bluetooth.BluetoothAirplaneModeListener.APM_ENHANCEMENT;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothProfileServiceConnection;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.flags.FeatureFlags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.BluetoothManagerServiceDumpProto;
import com.android.server.bluetooth.airplane.AirplaneModeListener;
import com.android.server.bluetooth.satellite.SatelliteModeListener;

import kotlin.Unit;
import kotlin.time.TimeSource;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class BluetoothManagerService {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final String BLUETOOTH_PRIVILEGED =
            android.Manifest.permission.BLUETOOTH_PRIVILEGED;

    private static final int ACTIVE_LOG_MAX_SIZE = 20;
    private static final int CRASH_LOG_MAX_SIZE = 100;

    private static final int DEFAULT_REBIND_COUNT = 3;
    // Maximum msec to wait for a bind
    private static final int TIMEOUT_BIND_MS =
        3000 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

    // Timeout value for synchronous binder call
    private static final Duration SYNC_CALLS_TIMEOUT =
        Duration.ofSeconds(3 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1));

    /**
     * @return timeout value for synchronous binder call
     */
    private static Duration getSyncTimeout() {
        return SYNC_CALLS_TIMEOUT;
    }

    // Maximum msec to wait for service restart
    private static final int SERVICE_RESTART_TIME_MS
        = 400 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);
    // Maximum msec to wait for restart due to error
    private static final int ERROR_RESTART_TIME_MS
        = 3000 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);
    // Maximum msec to delay MESSAGE_USER_SWITCHED
    private static final int USER_SWITCHED_TIME_MS
        = 200 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);
    // Delay for the addProxy function in msec
    private static final int ADD_PROXY_DELAY_MS
        = 100 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);
    // Delay for retrying enable and disable in msec
    private static final int ENABLE_DISABLE_DELAY_MS
        = 300 * SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

    @VisibleForTesting static final int MESSAGE_ENABLE = 1;
    @VisibleForTesting static final int MESSAGE_DISABLE = 2;
    @VisibleForTesting static final int MESSAGE_HANDLE_ENABLE_DELAYED = 3;
    @VisibleForTesting static final int MESSAGE_HANDLE_DISABLE_DELAYED = 4;
    @VisibleForTesting static final int MESSAGE_INFORM_ADAPTER_SERVICE_UP = 22;
    @VisibleForTesting static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    @VisibleForTesting static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    @VisibleForTesting static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    @VisibleForTesting static final int MESSAGE_TIMEOUT_BIND = 100;
    @VisibleForTesting static final int MESSAGE_GET_NAME_AND_ADDRESS = 200;
    @VisibleForTesting static final int MESSAGE_USER_SWITCHED = 300;
    @VisibleForTesting static final int MESSAGE_USER_UNLOCKED = 301;
    @VisibleForTesting static final int MESSAGE_ADD_PROXY_DELAYED = 400;
    @VisibleForTesting static final int MESSAGE_BIND_PROFILE_SERVICE = 401;
    @VisibleForTesting static final int MESSAGE_RESTORE_USER_SETTING = 500;

    private static final int RESTORE_SETTING_TO_ON = 1;
    private static final int RESTORE_SETTING_TO_OFF = 0;

    private static final int MAX_ERROR_RESTART_RETRIES = 6;
    private static final int MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES = 10;

    // Bluetooth persisted setting is off
    @VisibleForTesting static final int BLUETOOTH_OFF = 0;
    // Bluetooth persisted setting is on
    // and Airplane mode won't affect Bluetooth state at start up
    // This is the default value
    @VisibleForTesting static final int BLUETOOTH_ON_BLUETOOTH = 1;
    // Bluetooth persisted setting is on
    // but Airplane mode will affect Bluetooth state at start up
    // and Airplane mode will have higher priority.
    @VisibleForTesting static final int BLUETOOTH_ON_AIRPLANE = 2;

    private static final int FLAGS_SYSTEM_APP =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    // APM enhancement feature is enabled by default
    // Set this value to 0 to disable the feature
    private static final int DEFAULT_APM_ENHANCEMENT_STATE = 1;

    private static final Map<Integer, String> PROFILE_TO_SERVICE_NAME =
            Map.ofEntries(
                    Map.entry(BluetoothProfile.HEADSET, "android.bluetooth.IBluetoothHeadset"),
                    Map.entry(BluetoothProfile.A2DP, "android.bluetooth.IBluetoothA2dp"),
                    Map.entry(BluetoothProfile.HID_HOST, "android.bluetooth.IBluetoothHidHost"),
                    Map.entry(BluetoothProfile.PAN, "android.bluetooth.IBluetoothPan"),
                    Map.entry(BluetoothProfile.PBAP, "android.bluetooth.IBluetoothPbap"),
                    Map.entry(BluetoothProfile.MAP, "android.bluetooth.IBluetoothMap"),
                    Map.entry(BluetoothProfile.SAP, "android.bluetooth.IBluetoothSap"),
                    Map.entry(BluetoothProfile.A2DP_SINK, "android.bluetooth.IBluetoothA2dpSink"),
                    Map.entry(
                            BluetoothProfile.AVRCP_CONTROLLER,
                            "android.bluetooth.IBluetoothAvrcpController"),
                    Map.entry(
                            BluetoothProfile.HEADSET_CLIENT,
                            "android.bluetooth.IBluetoothHeadsetClient"),
                    Map.entry(
                            BluetoothProfile.PBAP_CLIENT, "android.bluetooth.IBluetoothPbapClient"),
                    Map.entry(BluetoothProfile.MAP_CLIENT, "android.bluetooth.IBluetoothMapClient"),
                    Map.entry(BluetoothProfile.HID_DEVICE, "android.bluetooth.IBluetoothHidDevice"),
                    Map.entry(
                            BluetoothProfile.HEARING_AID, "android.bluetooth.IBluetoothHearingAid"),
                    Map.entry(BluetoothProfile.LE_AUDIO, "android.bluetooth.IBluetoothLeAudio"),
                    Map.entry(
                            BluetoothProfile.VOLUME_CONTROL,
                            "android.bluetooth.IBluetoothVolumeControl"),
                    Map.entry(
                            BluetoothProfile.CSIP_SET_COORDINATOR,
                            "android.bluetooth.IBluetoothCsipSetCoordinator"),
                    Map.entry(
                            BluetoothProfile.LE_AUDIO_BROADCAST,
                            "android.bluetooth.IBluetoothLeAudio"),
                    Map.entry(
                            BluetoothProfile.LE_CALL_CONTROL,
                            "android.bluetooth.IBluetoothLeCallControl"),
                    Map.entry(BluetoothProfile.HAP_CLIENT, "android.bluetooth.IBluetoothHapClient"),
                    Map.entry(
                            BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                            "android.bluetooth.IBluetoothLeBroadcastAssistant"));

    private final Context mContext;
    private final Looper mLooper;

    private final UserManager mUserManager;

    // -3     match with Userhandle.USER_CURRENT_OR_SELF
    private static final UserHandle USER_HANDLE_CURRENT_OR_SELF = UserHandle.of(-3);
    // -10000 match with Userhandle.USER_NULL
    private static final UserHandle USER_HANDLE_NULL = UserHandle.of(-10000);

    // Locks are not provided for mName and mAddress.
    // They are accessed in handler or broadcast receiver, same thread context.
    private String mAddress = null;
    private String mName = null;
    private final ContentResolver mContentResolver;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks =
            new RemoteCallbackList<IBluetoothManagerCallback>();
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks =
            new RemoteCallbackList<IBluetoothStateChangeCallback>();
    private final BluetoothServiceBinder mBinder;

    private final ReentrantReadWriteLock mAdapterLock = new ReentrantReadWriteLock();

    private final FeatureFlags mFeatureFlags;

    @GuardedBy("mAdapterLock")
    private AdapterBinder mAdapter = null;

    private int mBindingUserID;
    private boolean mTryBindOnBindTimeout = false;

    private List<Integer> mSupportedProfileList = new ArrayList<>();

    // TODO(b/309033118): remove BluetoothAirplaneModeListener once use_new_airplane_mode ship
    private final BluetoothAirplaneModeListener mBluetoothAirplaneModeListener;

    // TODO(b/303552318): remove BluetoothNotificationManager once airplane_ressources_in_app ship
    private BluetoothNotificationManager mBluetoothNotificationManager;

    // TODO(b/289584302): remove BluetoothSatelliteModeListener once use_new_satellite_mode ship
    private BluetoothSatelliteModeListener mBluetoothSatelliteModeListener;

    private final boolean mUseNewSatelliteMode;
    private final boolean mUseNewAirplaneMode;

    // used inside handler thread
    private boolean mQuietEnable = false;
    private boolean mEnable = false;
    private boolean mShutdownInProgress = false;

    private Context mCurrentUserContext = null;

    static String timeToLog(long timestamp) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    // Used for tracking apps that enabled / disabled Bluetooth.
    private static class ActiveLog {
        private int mReason;
        private String mPackageName;
        private boolean mEnable;
        private long mTimestamp;

        ActiveLog(int reason, String packageName, boolean enable, long timestamp) {
            mReason = reason;
            mPackageName = packageName;
            mEnable = enable;
            mTimestamp = timestamp;
            Log.d(TAG, this.toString());
        }

        @Override
        public String toString() {
            return timeToLog(mTimestamp)
                    + (mEnable ? "  Enabled " : " Disabled ")
                    + " due to "
                    + getEnableDisableReasonString(mReason)
                    + " by "
                    + mPackageName;
        }

        void dump(ProtoOutputStream proto) {
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.TIMESTAMP_MS, mTimestamp);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.ENABLE, mEnable);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.PACKAGE_NAME, mPackageName);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.REASON, mReason);
        }
    }

    private final LinkedList<ActiveLog> mActiveLogs = new LinkedList<>();
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private int mCrashes = 0;
    private long mLastEnabledTime;

    // configuration from external IBinder call which is used to
    // synchronize with broadcast receiver.
    private boolean mQuietEnableExternal = false;
    private boolean mEnableExternal = false;

    // Map of apps registered to keep BLE scanning on.
    private Map<IBinder, ClientDeathRecipient> mBleApps =
            new ConcurrentHashMap<IBinder, ClientDeathRecipient>();

    private final BluetoothAdapterState mState = new BluetoothAdapterState();

    private final BluetoothHandler mHandler;
    private int mErrorRecoveryRetryCounter = 0;

    private final boolean mIsHearingAidProfileSupported;

    // Save a ProfileServiceConnections object for each of the bound
    // bluetooth profile services
    private final Map<Integer, ProfileServiceConnections> mProfileServices = new HashMap<>();

    private volatile boolean mUnbindingAll = false;

    private final IBluetoothCallback mBluetoothCallback =
            new IBluetoothCallback.Stub() {
                @Override
                public void onBluetoothStateChange(int prevState, int newState)
                        throws RemoteException {
                    mHandler.obtainMessage(MESSAGE_BLUETOOTH_STATE_CHANGE, prevState, newState)
                            .sendToTarget();
                }
            };

    public void onUserRestrictionsChanged(UserHandle userHandle) {
        final boolean newBluetoothDisallowed =
                mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_BLUETOOTH, userHandle);
        // Disallow Bluetooth sharing when either Bluetooth is disallowed or Bluetooth sharing
        // is disallowed
        final boolean newBluetoothSharingDisallowed =
                mUserManager.hasUserRestrictionForUser(
                                UserManager.DISALLOW_BLUETOOTH_SHARING, userHandle)
                        || newBluetoothDisallowed;

        // Disable OPP activities for this userHandle
        updateOppLauncherComponentState(userHandle, newBluetoothSharingDisallowed);

        // DISALLOW_BLUETOOTH can only be set by DO or PO on the system user.
        // Only trigger once instead of for all users
        if (UserHandle.SYSTEM.equals(userHandle) && newBluetoothDisallowed) {
            sendDisableMsg(
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED,
                    mContext.getPackageName());
        }
    }

    boolean onFactoryReset(AttributionSource source) {
        // Wait for stable state if bluetooth is temporary state.
        int state = getState();
        if (state == STATE_BLE_TURNING_ON
                || state == STATE_TURNING_ON
                || state == STATE_TURNING_OFF) {
            if (!waitForState(STATE_BLE_ON, STATE_ON)) {
                return false;
            }
        }

        // Clear registered LE apps to force shut-off Bluetooth
        clearBleApps();
        state = getState();
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter == null) {
                return false;
            }
            if (state == STATE_BLE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(),
                        false);
                mAdapter.stopBle(source);
                return true;
            } else if (state == STATE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(),
                        false);
                mAdapter.disable(source);
                return true;
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to shutdown Bluetooth", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }
        return false;
    }

    private int estimateBusyTime(int state) {
        if (state == STATE_BLE_ON && isBluetoothPersistedStateOn()) {
            // Bluetooth is in BLE and is starting classic
            return SERVICE_RESTART_TIME_MS;
        } else if (state != STATE_ON && state != STATE_OFF && state != STATE_BLE_ON) {
            // Bluetooth is turning state
            return ADD_PROXY_DELAY_MS;
        } else if (mHandler.hasMessages(MESSAGE_ENABLE)
                || mHandler.hasMessages(MESSAGE_DISABLE)
                || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                || mHandler.hasMessages(MESSAGE_TIMEOUT_BIND)
                || mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE)) {
            Log.d(
                    TAG,
                    "Busy reason:"
                            + " ENABLE="
                            + mHandler.hasMessages(MESSAGE_ENABLE)
                            + " DISABLE="
                            + mHandler.hasMessages(MESSAGE_DISABLE)
                            + " HANDLE_ENABLE_DELAYED="
                            + mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)
                            + " HANDLE_DISABLE_DELAYED="
                            + mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            + " RESTART_BLUETOOTH_SERVICE="
                            + mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                            + " TIMEOUT_BIND="
                            + mHandler.hasMessages(MESSAGE_TIMEOUT_BIND)
                            + " BIND_PROFILE_SERVICE="
                            + mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE));
            // Bluetooth is restarting
            return SERVICE_RESTART_TIME_MS;
        }
        return 0;
    }

    private void delayModeChangedIfNeeded(Object token, Runnable r, String modechanged) {
        final int state = getState();
        final int delayMs = estimateBusyTime(state);
        Log.d(
                TAG,
                "delayModeChangedIfNeeded("
                        + modechanged
                        + "): "
                        + "state="
                        + BluetoothAdapter.nameForState(state)
                        + ", isAirplaneModeOn()="
                        + isAirplaneModeOn()
                        + ", isSatelliteModeOn()="
                        + isSatelliteModeOn()
                        + ", delayed="
                        + delayMs
                        + "ms");

        mHandler.removeCallbacksAndMessages(token);

        if (delayMs > 0) {
            mHandler.postDelayed(
                    () -> delayModeChangedIfNeeded(token, r, modechanged), token, delayMs);
        } else {
            r.run();
        }
    }

    /** Send Intent to the Notification Service in the Bluetooth app */
    Unit sendAirplaneModeNotification(String notificationState) {
        Intent intent = new Intent("android.bluetooth.airplane.action.SEND_NOTIFICATION");
        intent.setComponent(resolveSystemService(intent));
        intent.putExtra("android.bluetooth.airplane.extra.NOTIFICATION_STATE", notificationState);
        mContext.startService(intent);
        return Unit.INSTANCE;
    }

    private static final Object ON_AIRPLANE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SATELLITE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SWITCH_USER_TOKEN = new Object();

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    Unit onAirplaneModeChanged(boolean isAirplaneModeOn) {
        mHandler.postDelayed(
                () ->
                        delayModeChangedIfNeeded(
                                ON_AIRPLANE_MODE_CHANGED_TOKEN,
                                () -> handleAirplaneModeChanged(isAirplaneModeOn),
                                "onAirplaneModeChanged"),
                ON_AIRPLANE_MODE_CHANGED_TOKEN,
                0);
        return Unit.INSTANCE;
    }

    // TODO(b/289584302): Update to private once use_new_satellite_mode is enabled
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    Unit onSatelliteModeChanged(boolean isSatelliteModeOn) {
        mHandler.postDelayed(
                () ->
                        delayModeChangedIfNeeded(
                                ON_SATELLITE_MODE_CHANGED_TOKEN,
                                () -> handleSatelliteModeChanged(isSatelliteModeOn),
                                "onSatelliteModeChanged"),
                ON_SATELLITE_MODE_CHANGED_TOKEN,
                0);
        return Unit.INSTANCE;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void onSwitchUser(UserHandle userHandle) {
        mHandler.postDelayed(
                () ->
                        delayModeChangedIfNeeded(
                                ON_SWITCH_USER_TOKEN,
                                () -> handleSwitchUser(userHandle),
                                "onSwitchUser"),
                ON_SWITCH_USER_TOKEN,
                0);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void handleAirplaneModeChanged(boolean isAirplaneModeOn) {
        synchronized (this) {
            if (isBluetoothPersistedStateOn()) {
                if (isAirplaneModeOn) {
                    persistBluetoothSetting(BLUETOOTH_ON_AIRPLANE);
                } else {
                    persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                }
            }

            int st = mState.get();

            Log.d(
                    TAG,
                    "handleAirplaneModeChanged(isAirplaneModeOn="
                            + isAirplaneModeOn
                            + ") | current state="
                            + BluetoothAdapter.nameForState(st));

            if (isAirplaneModeOn) {
                // Clear registered LE apps to force shut-off
                clearBleApps();

                // If state is BLE_ON make sure we trigger stopBle
                if (st == STATE_BLE_ON) {
                    mAdapterLock.readLock().lock();
                    try {
                        if (mAdapter != null) {
                            addActiveLog(
                                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                                    mContext.getPackageName(),
                                    false);
                            mAdapter.stopBle(mContext.getAttributionSource());
                            mEnable = false;
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "Unable to call stopBle", e);
                    } finally {
                        mAdapterLock.readLock().unlock();
                    }
                } else {
                    sendDisableMsg(
                            BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                            mContext.getPackageName());
                }
            } else if (mEnableExternal) {
                if (isBluetoothPersistedStateOn()) {
                    Log.d(TAG, "Airplane OFF: sendEnableMsg");
                    sendEnableMsg(
                            mQuietEnableExternal,
                            BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                            mContext.getPackageName());
                }
            }
        }
    }

    private void handleSatelliteModeChanged(boolean isSatelliteModeOn) {
        if (shouldBluetoothBeOn(isSatelliteModeOn) && getState() != STATE_ON) {
            sendEnableMsg(
                    mQuietEnableExternal,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE,
                    mContext.getPackageName());
        } else if (!shouldBluetoothBeOn(isSatelliteModeOn) && getState() != STATE_OFF) {
            sendDisableMsg(
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE,
                    mContext.getPackageName());
        }
    }

    private boolean shouldBluetoothBeOn(boolean isSatelliteModeOn) {
        if (!isBluetoothPersistedStateOn()) {
            Log.d(TAG, "shouldBluetoothBeOn: User want BT off.");
            return false;
        }

        if (isSatelliteModeOn) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as satellite mode is on.");
            return false;
        }

        if (isAirplaneModeOn() && isBluetoothPersistedStateOnAirplane()) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as airplaneMode is on.");
            return false;
        }

        Log.d(TAG, "shouldBluetoothBeOn: BT should be on.");
        return true;
    }

    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                        String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                        if (DBG) {
                            Log.d(
                                    TAG,
                                    "Bluetooth Adapter name changed to "
                                            + newName
                                            + " by "
                                            + mContext.getPackageName());
                        }
                        if (newName != null) {
                            storeNameAndAddress(newName, null);
                        }
                    } else if (BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED.equals(action)) {
                        String newAddress =
                                intent.getStringExtra(BluetoothAdapter.EXTRA_BLUETOOTH_ADDRESS);
                        if (newAddress != null) {
                            if (DBG) {
                                Log.d(TAG, "Bluetooth Adapter address changed to " + newAddress);
                            }
                            storeNameAndAddress(null, newAddress);
                        } else {
                            if (DBG) {
                                Log.e(TAG, "No Bluetooth Adapter address parameter found");
                            }
                        }
                    } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                        final String name = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                        if (Settings.Global.BLUETOOTH_ON.equals(name)) {
                            // The Bluetooth On state may be changed during system restore.
                            final String prevValue =
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                            final String newValue =
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);

                            if (DBG) {
                                Log.d(
                                        TAG,
                                        "ACTION_SETTING_RESTORED with BLUETOOTH_ON, prevValue="
                                                + prevValue
                                                + ", newValue="
                                                + newValue);
                            }

                            if ((newValue != null)
                                    && (prevValue != null)
                                    && !prevValue.equals(newValue)) {
                                mHandler.obtainMessage(
                                                MESSAGE_RESTORE_USER_SETTING,
                                                newValue.equals("0")
                                                        ? RESTORE_SETTING_TO_OFF
                                                        : RESTORE_SETTING_TO_ON,
                                                0)
                                        .sendToTarget();
                            }
                        }
                    } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                        Log.i(TAG, "Device is shutting down.");
                        mShutdownInProgress = true;
                        mAdapterLock.readLock().lock();
                        try {
                            mEnable = false;
                            mEnableExternal = false;
                            if (mAdapter != null && mState.oneOf(STATE_BLE_ON)) {
                                mAdapter.stopBle(mContext.getAttributionSource());
                            } else if (mAdapter != null && mState.oneOf(STATE_ON)) {
                                mAdapter.disable(mContext.getAttributionSource());
                            }
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to shutdown Bluetooth", e);
                        } finally {
                            mAdapterLock.readLock().unlock();
                        }
                    }
                }
            };

    BluetoothManagerService(
            @NonNull Context context, @NonNull Looper looper, @NonNull FeatureFlags featureFlags) {
        mContext = requireNonNull(context, "Context cannot be null");
        mLooper = requireNonNull(looper, "Looper cannot be null");
        mFeatureFlags = requireNonNull(featureFlags, "Feature Flags cannot be null");

        mUserManager =
                requireNonNull(
                        mContext.getSystemService(UserManager.class),
                        "UserManager system service cannot be null");

        mBinder = new BluetoothServiceBinder(this, mContext, mUserManager);
        mHandler = new BluetoothHandler(mLooper);

        mTryBindOnBindTimeout = false;
        mContentResolver = mContext.getContentResolver();

        // Observe BLE scan only mode settings change.
        registerForBleScanModeChange();

        if (!mFeatureFlags.airplaneRessourcesInApp() && !mFeatureFlags.useNewAirplaneMode()) {
            mBluetoothNotificationManager = new BluetoothNotificationManager(mContext);
        }

        // Disable ASHA if BLE is not supported, overriding any system property
        if (!isBleSupported(mContext)) {
            mIsHearingAidProfileSupported = false;
        } else {
            // ASHA default value is:
            //   * disabled on Automotive, TV, and Watch.
            //   * enabled for other form factor
            // This default value can be overridden with a system property
            final boolean isAshaEnabledByDefault =
                    !(isAutomotive(mContext) || isWatch(mContext) || isTv(mContext));
            mIsHearingAidProfileSupported =
                    BluetoothProperties.isProfileAshaCentralEnabled()
                            .orElse(isAshaEnabledByDefault);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED);
        filter.addAction(Intent.ACTION_SETTING_RESTORED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter);

        IntentFilter filterUser = new IntentFilter();
        filterUser.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        filterUser.addAction(Intent.ACTION_USER_SWITCHED);
        filterUser.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverForAllUsers(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        switch (intent.getAction()) {
                            case Intent.ACTION_USER_SWITCHED:
                                int foregroundUserId =
                                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                                propagateForegroundUserId(foregroundUserId);
                                break;
                            case UserManager.ACTION_USER_RESTRICTIONS_CHANGED:
                                onUserRestrictionsChanged(getSendingUser());
                                break;
                            default:
                                Log.e(
                                        TAG,
                                        "Unknown broadcast received in BluetoothManagerService"
                                                + " receiver registered across all users");
                        }
                    }
                },
                filterUser,
                null,
                null);

        loadStoredNameAndAddress();
        if (isBluetoothPersistedStateOn()) {
            if (DBG) {
                Log.d(TAG, "Startup: Bluetooth persisted state is ON.");
            }
            mEnableExternal = true;
        }

        // Caching is necessary to prevent caller requiring the READ_DEVICE_CONFIG permission
        mUseNewAirplaneMode = mFeatureFlags.useNewAirplaneMode();
        if (mUseNewAirplaneMode) {
            mBluetoothAirplaneModeListener = null;
        } else {
            mBluetoothAirplaneModeListener =
                    new BluetoothAirplaneModeListener(
                            this, looper, mContext, mBluetoothNotificationManager, mFeatureFlags);
        }

        // Caching is necessary to prevent caller requiring the READ_DEVICE_CONFIG permission
        mUseNewSatelliteMode = mFeatureFlags.useNewSatelliteMode();
        if (!mUseNewSatelliteMode) {
            // Only instantiate the legacy listener
            // New implementation is instantiated during onBootPhase on correct thread
            mBluetoothSatelliteModeListener =
                    new BluetoothSatelliteModeListener(this, mLooper, mContext);
        }
    }

    IBluetoothManager.Stub getBinder() {
        return mBinder;
    }

    /** Returns true if airplane mode is currently on */
    private boolean isAirplaneModeOn() {
        if (mUseNewAirplaneMode) {
            return AirplaneModeListener.isOn();
        }
        return mBluetoothAirplaneModeListener.isAirplaneModeOn();
    }

    /** Returns true if satellite mode is turned on. */
    private boolean isSatelliteModeOn() {
        if (mUseNewSatelliteMode) {
            return SatelliteModeListener.isOn();
        }
        return mBluetoothSatelliteModeListener.isSatelliteModeOn();
    }

    /** Returns true if the Bluetooth saved state is "on" */
    private boolean isBluetoothPersistedStateOn() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        if (DBG) {
            Log.d(TAG, "isBluetoothPersistedStateOn: " + state);
        }
        return state != BLUETOOTH_OFF;
    }

    private boolean isBluetoothPersistedStateOnAirplane() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        if (DBG) {
            Log.d(TAG, "isBluetoothPersistedStateOnAirplane: " + state);
        }
        return state == BLUETOOTH_ON_AIRPLANE;
    }

    /** Returns true if the Bluetooth saved state is BLUETOOTH_ON_BLUETOOTH */
    private boolean isBluetoothPersistedStateOnBluetooth() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        if (DBG) {
            Log.d(TAG, "isBluetoothPersistedStateOnBluetooth: " + state);
        }
        return state == BLUETOOTH_ON_BLUETOOTH;
    }

    /** Save the Bluetooth on/off state */
    private void persistBluetoothSetting(int value) {
        if (DBG) {
            Log.d(TAG, "Persisting Bluetooth Setting: " + value);
        }
        // waive WRITE_SECURE_SETTINGS permission check
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(
                    mContext.getContentResolver(), Settings.Global.BLUETOOTH_ON, value);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Returns true if the Bluetooth Adapter's name and address is locally cached
     *
     * @return
     */
    private boolean isNameAndAddressSet() {
        return mName != null && mAddress != null && mName.length() > 0 && mAddress.length() > 0;
    }

    /** Retrieve the Bluetooth Adapter's name and address and save it in the local cache */
    private void loadStoredNameAndAddress() {
        if (DBG) {
            Log.d(TAG, "Loading stored name and address");
        }
        if (BluetoothProperties.isAdapterAddressValidationEnabled().orElse(false)
                && Settings.Secure.getInt(mContentResolver, Settings.Secure.BLUETOOTH_ADDR_VALID, 0)
                        == 0) {
            // if the valid flag is not set, don't load the address and name
            if (DBG) {
                Log.d(TAG, "invalid bluetooth name and address stored");
            }
            return;
        }
        mName =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(mContentResolver, Settings.Secure.BLUETOOTH_NAME);
        mAddress =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(
                                mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS);

        if (DBG) {
            Log.d(TAG, "Stored bluetooth Name=" + mName + ",Address=" + mAddress);
        }
    }

    /**
     * Save the Bluetooth name and address in the persistent store. Only non-null values will be
     * saved.
     *
     * @param name
     * @param address
     */
    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_NAME, name);
            mName = name;
            if (DBG) {
                Log.d(
                        TAG,
                        "Stored Bluetooth name: "
                                + Settings.Secure.getString(
                                        mContentResolver, Settings.Secure.BLUETOOTH_NAME));
            }
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS, address);
            mAddress = address;
            if (DBG) {
                Log.d(
                        TAG,
                        "Stored Bluetoothaddress: "
                                + Settings.Secure.getString(
                                        mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS));
            }
        }

        if ((name != null) && (address != null)) {
            Settings.Secure.putInt(mContentResolver, Settings.Secure.BLUETOOTH_ADDR_VALID, 1);
        }
    }

    IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.register(callback);
        }
        return mAdapter != null ? mAdapter.getAdapterBinder() : null;
    }

    void unregisterAdapter(IBluetoothManagerCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mHandler.obtainMessage(MESSAGE_REGISTER_STATE_CHANGE_CALLBACK, callback).sendToTarget();
    }

    void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mHandler.obtainMessage(MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK, callback).sendToTarget();
    }

    boolean isEnabled() {
        return getState() == STATE_ON;
    }

//  @GuardedBy("mAdapterLock")
//     private boolean synchronousDisable(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return false;
//         final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
//         mAdapter.disable(attributionSource, recv);
//         return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(false);
//     }

//     @GuardedBy("mAdapterLock")
//     private boolean synchronousEnable(boolean quietMode, AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return false;
//         final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
//         mAdapter.enable(quietMode, attributionSource, recv);
//         return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(false);
//     }

//     @GuardedBy("mAdapterLock")
//     private String synchronousGetAddress(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return null;
//         final SynchronousResultReceiver<String> recv = SynchronousResultReceiver.get();
//         mAdapter.getAddress(attributionSource, recv);
//         return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private String synchronousGetName(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return null;
//         final SynchronousResultReceiver<String> recv = SynchronousResultReceiver.get();
//         mAdapter.getName(attributionSource, recv);
//         return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private int synchronousGetState() throws RemoteException, TimeoutException {
//         if (mAdapter == null) return STATE_OFF;
//         final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
//         mAdapter.getState(recv);
//         return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(STATE_OFF);
//     }

//     @GuardedBy("mAdapterLock")
//     private void synchronousOnBrEdrDown(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return;
//         final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
//         //mAdapter.onBrEdrDown(attributionSource, recv);
//         recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private void synchronousOnLeServiceUp(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return;
//         final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
//         //mAdapter.onLeServiceUp(attributionSource, recv);
//         recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private void synchronousRegisterCallback(
//             IBluetoothCallback callback, AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return;
//         final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
//         mAdapter.registerCallback(callback, attributionSource, recv);
//         recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private void synchronousUnregisterCallback(
//             IBluetoothCallback callback, AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         if (mAdapter == null) return;
//         final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
//         mAdapter.unregisterCallback(callback, attributionSource, recv);
//         recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
//     }

//     @GuardedBy("mAdapterLock")
//     private List<Integer> synchronousGetSupportedProfiles(AttributionSource attributionSource)
//             throws RemoteException, TimeoutException {
//         final ArrayList<Integer> supportedProfiles = new ArrayList<Integer>();
//         if (mAdapter == null) return supportedProfiles;
//         final SynchronousResultReceiver<Long> recv = SynchronousResultReceiver.get();
//         mAdapter.getSupportedProfiles(attributionSource, recv);
//         final long supportedProfilesBitMask =
//                 recv.awaitResultNoInterrupt(getSyncTimeout()).getValue((long) 0);

//         for (int i = 0; i <= BluetoothProfile.MAX_PROFILE_ID; i++) {
//             if ((supportedProfilesBitMask & (1L << i)) != 0) {
//                 supportedProfiles.add(i);
//             }
//         }

//         return supportedProfiles;
//     }

    /**
     * Sends the current foreground user id to the Bluetooth process. This user id is used to
     * determine if Binder calls are coming from the active user.
     *
     * @param userId is the foreground user id we are propagating to the Bluetooth process
     */
    private void propagateForegroundUserId(int userId) {
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter != null) {
                mAdapter.setForegroundUserId(userId, mContext.getAttributionSource());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set foreground user id", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }
    }

    int getState() {
        return mState.get();
    }

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        private String mPackageName;

        ClientDeathRecipient(String packageName) {
            mPackageName = packageName;
        }

        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Binder is dead - unregister " + mPackageName);
            }

            for (Map.Entry<IBinder, ClientDeathRecipient> entry : mBleApps.entrySet()) {
                IBinder token = entry.getKey();
                ClientDeathRecipient deathRec = entry.getValue();
                if (deathRec.equals(this)) {
                    updateBleAppCount(token, false, mPackageName);
                    break;
                }
            }

            int appCount = mBleApps.size();
            if (DBG) {
                Log.d(TAG, appCount + "Binder is dead,registered Ble Apps");
            }

            if (appCount == 0 && mEnable) {
                disableBleScanMode();
            }

            if (appCount == 0) {
                int st = STATE_OFF;
                try {
                    mAdapterLock.readLock().lock();
                    if (mAdapter != null) {
                        st = getState();
                    }
                    if (!mEnableExternal || (st == STATE_BLE_ON)) {
                        if (DBG) {
                            Log.d(TAG, "Move to BT state OFF");
                        }
                        sendBrEdrDownCallback(mContext.getAttributionSource());
                    }
                } finally {
                    mAdapterLock.readLock().unlock();
                }
            }
        }

        public String getPackageName() {
            return mPackageName;
        }
    }

    boolean isBleScanAlwaysAvailable() {
        if (isAirplaneModeOn() && !mEnable) {
            return false;
        }
        try {
            return Settings.Global.getInt(
                            mContentResolver, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE)
                    != 0;
        } catch (SettingNotFoundException e) {
        }
        return false;
    }

    boolean isHearingAidProfileSupported() {
        return mIsHearingAidProfileSupported;
    }

    Context getCurrentUserContext() {
        return mCurrentUserContext;
    }

    boolean isMediaProfileConnected() {
        if (mAdapter == null || !mState.oneOf(STATE_ON)) {
            return false;
        }
        return mAdapter.isMediaProfileConnected(mContext.getAttributionSource());
    }

    // Monitor change of BLE scan only mode settings.
    private void registerForBleScanModeChange() {
        ContentObserver contentObserver =
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        if (isBleScanAlwaysAvailable()) {
                            // Nothing to do
                            return;
                        }
                        // BLE scan is not available.
                        disableBleScanMode();
                        clearBleApps();
                        mAdapterLock.readLock().lock();
                        try {
                            if (mAdapter != null) {
                                addActiveLog(
                                        BluetoothProtoEnums
                                                .ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                                        mContext.getPackageName(),
                                        false);
                                mAdapter.stopBle(mContext.getAttributionSource());
                            }
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "error when disabling bluetooth", e);
                        } finally {
                            mAdapterLock.readLock().unlock();
                        }
                    }
                };

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE),
                false,
                contentObserver);
    }

    // Disable ble scan only mode.
    private void disableBleScanMode() {
        mAdapterLock.writeLock().lock();
        try {
            if (mAdapter != null && mState.oneOf(STATE_ON) && (!isBluetoothPersistedStateOnBluetooth())) {
                if (DBG) {
                    Log.d(TAG, "Resetting the mEnable flag for clean disable");
                }
                if (!mEnableExternal) {
                    mEnable = false;
                }
            }
        } finally {
            mAdapterLock.writeLock().unlock();
        }
    }

    private int updateBleAppCount(IBinder token, boolean enable, String packageName) {
        ClientDeathRecipient r = mBleApps.get(token);
        int st = STATE_OFF;
        if (r == null && enable) {
            ClientDeathRecipient deathRec = new ClientDeathRecipient(packageName);
            try {
                token.linkToDeath(deathRec, 0);
            } catch (RemoteException ex) {
                throw new IllegalArgumentException("BLE app (" + packageName + ") already dead!");
            }
            mBleApps.put(token, deathRec);
            if (DBG) {
                Log.d(TAG, "Registered for death of " + packageName);
            }
        } else if (!enable && r != null) {
            // Unregister death recipient as the app goes away.
            token.unlinkToDeath(r, 0);
            mBleApps.remove(token);
            if (DBG) {
                Log.d(TAG, "Unregistered for death of " + packageName);
            }
        }

        int appCount = mBleApps.size();
        if (DBG) {
            Log.d(TAG, appCount + " registered Ble Apps");
        }
        return appCount;
    }

    boolean enableBle(String packageName, IBinder token) {
        if (isAirplaneModeOn()) {
            Log.d(TAG, "enableBle(): not enabling - airplaneMode is on");
            return false;
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableBle(): not enabling - satellite mode is on.");
            return false;
        }

        if (DBG) {
            Log.d(
                    TAG,
                    "enableBle("
                            + packageName
                            + "):  mAdapter="
                            + mAdapter
                            + " isBinding="
                            + isBinding()
                            + " mState="
                            + mState);
        }

        // TODO(b/262605980): enableBle/disableBle should be on handler thread
        updateBleAppCount(token, true, packageName);

        if (mState.oneOf(
                STATE_ON,
                STATE_BLE_ON,
                STATE_TURNING_ON,
                STATE_TURNING_OFF,
                STATE_BLE_TURNING_ON)) {
            Log.d(TAG, "enableBLE(): Bluetooth is already enabled or is turning on");
            return true;
        }
        synchronized (mReceiver) {
            // waive WRITE_SECURE_SETTINGS permission check
            sendEnableMsg(
                    false,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                    packageName,
                    true);
        }
        return true;
    }

    boolean disableBle(AttributionSource source, String packageName, IBinder token) {
        if (DBG) {
            Log.d(
                    TAG,
                    "disableBle("
                            + packageName
                            + "):  mAdapter="
                            + mAdapter
                            + " isBinding="
                            + isBinding()
                            + " mState="
                            + mState);
        }
        /* update app count even in bt off state, if quick enableBle and
         * disableBle during BT turning off may leave app count non zero
         */
        updateBleAppCount(token, false, packageName);

        if (isSatelliteModeOn()) {
            Log.d(TAG, "disableBle(): not disabling - satellite mode is on.");
            return false;
        }

        if (mState.oneOf(STATE_OFF)) {
            Log.d(TAG, "disableBLE(): Already disabled");
            return false;
        }
        // TODO(b/262605980): enableBle/disableBle should be on handler thread
        updateBleAppCount(token, false, packageName);

        if (mState.oneOf(STATE_BLE_ON) && !isBleAppPresent()) {
            if (mEnable) {
                disableBleScanMode();
            }
            if (!mEnableExternal) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                        packageName,
                        false);
                sendBrEdrDownCallback(source);
            }
        }
        return true;
    }

    // Clear all apps using BLE scan only mode.
    private void clearBleApps() {
        mBleApps.clear();
    }

    boolean isBleAppPresent() {
        if (DBG) {
            Log.d(TAG, "isBleAppPresent() count: " + mBleApps.size());
        }
        return mBleApps.size() > 0;
    }

    /**
     * Will call startBrEdr() if bluetooth classic should be on and will call stopBle if bluetooth
     * BLE should be off
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void continueFromBleOnState() {
        if (DBG) {
            Log.d(TAG, "continueFromBleOnState()");
        }
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter == null) {
                Log.e(TAG, "continueFromBleOnState: mAdapter is null!");
                return;
            }
            int st = getState();
            if (st != STATE_BLE_ON) {
                if (DBG) Log.v(TAG, "onBluetoothServiceUp: state isn't BLE_ON: " +
                         BluetoothAdapter.nameForState(st));
                return;
            }
            if (!mEnableExternal && !isBleAppPresent() &&
                !isBluetoothPersistedStateOnBluetooth()) {
                 // TODO(b/262605980): this code is unlikely to be trigger and will never be once
                 // enableBle & disableBle are executed on the handler
                Log.i(TAG, "Bluetooth was disabled while enabling BLE, disable BLE now");
                mEnable = false;
                mAdapter.stopBle(mContext.getAttributionSource());
                return;
            }
            if (isBluetoothPersistedStateOnBluetooth() ||
                 mEnableExternal) {
                // This triggers transition to STATE_ON
                mAdapter.updateQuietModeStatus(mQuietEnable,
                        mContext.getAttributionSource());
                mAdapter.startBrEdr(mContext.getAttributionSource());
                persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to call onServiceUp", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }
    }

    /**
     * Inform BluetoothAdapter instances that BREDR part is down and turn off all service and stack
     * if no LE app needs it
     */
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            })
    private void sendBrEdrDownCallback(AttributionSource source) {
        if (DBG) {
            Log.d(TAG, "Calling sendBrEdrDownCallback callbacks");
        }

        if (mAdapter == null) {
            Log.w(TAG, "Bluetooth handle is null");
            return;
        }

        if (isBleAppPresent()) {
            // Need to stay at BLE ON. Disconnect all Gatt connections
            try {
                mAdapter.unregAllGattClient(source);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to disconnect all apps.", e);
            }
        } else {
            mAdapterLock.readLock().lock();
            try {
                if (mAdapter != null) {
                    mAdapter.stopBle(source);
                }
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Call to stopBle() failed.", e);
            } finally {
                mAdapterLock.readLock().unlock();
            }
        }
    }

    boolean enableNoAutoConnect(String packageName) {
        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableNoAutoConnect(): not enabling - satellite mode is on.");
            return false;
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = true;
            mEnableExternal = true;
            sendEnableMsg(
                    true,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                    packageName);
        }
        return true;
    }

    boolean enable(String packageName) {
        if (isSatelliteModeOn()) {
            Log.d(TAG, "enable(): not enabling - satellite mode is on.");
            return false;
        }

        if (DBG) {
            Log.d(
                    TAG,
                    "enable("
                            + packageName
                            + "):  mAdapter="
                            + mAdapter
                            + " isBinding="
                            + isBinding()
                            + " mState="
                            + mState);
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = false;
            mEnableExternal = true;
            if (!mUseNewAirplaneMode) {
                mBluetoothAirplaneModeListener.notifyUserToggledBluetooth(true);
            } else {
                // TODO(b/288450479): Remove clearCallingIdentity when threading is fixed
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    AirplaneModeListener.notifyUserToggledBluetooth(
                            mContentResolver, mCurrentUserContext, true);
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            }
            sendEnableMsg(
                    false,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                    packageName);
        }
        if (DBG) {
            Log.d(TAG, "enable returning");
        }
        return true;
    }

    boolean disable(String packageName, boolean persist) {
        if (DBG) {
            Log.d(
                    TAG,
                    "disable(" + packageName + "): mAdapter="
                            + mAdapter
                            + ", persist="
                            + persist
                            + ", isBinding="
                            + isBinding());
        }

        synchronized (mReceiver) {
            if (!mUseNewAirplaneMode) {
                mBluetoothAirplaneModeListener.notifyUserToggledBluetooth(false);
                if (persist) {
                    sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                            packageName);
                } else {
                    /* It means disable is called by shutdown thread */
                    synchronized (this) {
                        clearBleApps();
                    }

                    try {
                        mAdapterLock.readLock().lock();
                        mEnableExternal = false;
                        if (mAdapter != null) {
                            if(getState() == STATE_BLE_ON) {
                                mEnable = false;
                                mAdapter.stopBle(mContext.getAttributionSource());
                            } else {
                                sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT,
                                        packageName);
                            }
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "Unable to initiate disable", e);
                    } finally {
                        mAdapterLock.readLock().unlock();
                    }
                }
            } else {
                // TODO(b/288450479): Remove clearCallingIdentity when threading is fixed
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    AirplaneModeListener.notifyUserToggledBluetooth(
                            mContentResolver, mCurrentUserContext, false);
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            }

            if (persist) {
                persistBluetoothSetting(BLUETOOTH_OFF);
            }
            mEnableExternal = false;
        }
        return true;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void unbindAndFinish() {
        if (DBG) {
            Log.d(TAG, "unbindAndFinish(): mAdapter=" + mAdapter + " isBinding=" + isBinding());
        }

        mAdapterLock.writeLock().lock();
        try {
            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE);
            if (mAdapter != null) {
                // Unregister callback object
                try {
                    mAdapter.unregisterCallback(
                            mBluetoothCallback, mContext.getAttributionSource());
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "Unable to unregister BluetoothCallback", e);
                }
                mAdapter = null;
                mContext.unbindService(mConnection);

                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
            }
        } finally {
            mAdapterLock.writeLock().unlock();
        }
    }

    public boolean isBluetoothAvailableForBinding() {
        try {
            mAdapterLock.readLock().lock();
            if (mAdapter != null && ((getState() == STATE_ON) ||
                (getState() == STATE_TURNING_ON))) {
                return true;
            } else {
                return false;
           }
        } finally {
            mAdapterLock.readLock().unlock();
        }
    }

    boolean bindBluetoothProfileService(
            int bluetoothProfile, IBluetoothProfileServiceConnection proxy) {
        if (isBluetoothAvailableForBinding() == false) {
            Log.w(
                    TAG,
                            "bindBluetoothProfileService:Trying to bind to profile: "
                            + bluetoothProfile
                            + ", while Bluetooth is disabled");
            return false;
        }
        synchronized (mProfileServices) {
            if (!mSupportedProfileList.contains(bluetoothProfile)) {
                Log.w(
                        TAG,
                        "Cannot bind profile: "
                                + bluetoothProfile
                                + ", not in supported profiles list");
                return false;
            }
            ProfileServiceConnections psc = mProfileServices.get(bluetoothProfile);
            if (psc == null) {
                if (DBG) {
                    Log.d(
                            TAG,
                            "Creating new ProfileServiceConnections object for"
                                    + " profile: "
                                    + bluetoothProfile);
                }
                psc =
                        new ProfileServiceConnections(
                                new Intent(PROFILE_TO_SERVICE_NAME.get(bluetoothProfile)));
                if (!psc.bindService(DEFAULT_REBIND_COUNT)) {
                    return false;
                }

                mProfileServices.put(bluetoothProfile, psc);
            }
            else
               Log.w(TAG, "psc is not null in bindBluetoothProfileService");
        }

        // Introducing a delay to give the client app time to prepare
        Message addProxyMsg = mHandler.obtainMessage(MESSAGE_ADD_PROXY_DELAYED);
        addProxyMsg.arg1 = bluetoothProfile;
        addProxyMsg.obj = proxy;
        mHandler.sendMessageDelayed(addProxyMsg, ADD_PROXY_DELAY_MS);
        return true;
    }

    void unbindBluetoothProfileService(
            int bluetoothProfile, IBluetoothProfileServiceConnection proxy) {
        if (mUnbindingAll) {
            return;
        }
        synchronized (mProfileServices) {
            ProfileServiceConnections psc = mProfileServices.get(bluetoothProfile);
            if (psc == null) {
                Log.e(TAG, "unbindBluetoothProfileService: psc is null, returning");
                return;
            }
            Log.w(TAG, "unbindBluetoothProfileService: calling psc.removeProxy");
            psc.removeProxy(proxy);
            if (psc.isEmpty()) {
                // All proxies are disconnected, unbind with the service.
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                mProfileServices.remove(bluetoothProfile);
            }
        }
    }

    private void unbindAllBluetoothProfileServices() {
        mUnbindingAll = true;
        synchronized (mProfileServices) {
            for (Integer i : mProfileServices.keySet()) {
                ProfileServiceConnections psc = mProfileServices.get(i);
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                psc.removeAllProxies();
            }
            mProfileServices.clear();
        }
        mUnbindingAll = false;
    }

    /**
     * Send enable message and set adapter name and address. Called when the boot phase becomes
     * PHASE_SYSTEM_SERVICES_READY.
     */
    void handleOnBootPhase(UserHandle userHandle) {
        mHandler.post(() -> internalHandleOnBootPhase(userHandle));
    }

    @VisibleForTesting
    void initialize(UserHandle userHandle) {
        if (mUseNewAirplaneMode) {
            mCurrentUserContext =
                    requireNonNull(
                            mContext.createContextAsUser(userHandle, 0),
                            "Current User Context cannot be null");
            AirplaneModeListener.initialize(
                    mLooper,
                    mContentResolver,
                    mState,
                    this::onAirplaneModeChanged,
                    this::sendAirplaneModeNotification,
                    this::isMediaProfileConnected,
                    this::getCurrentUserContext,
                    TimeSource.Monotonic.INSTANCE);
        }

        if (mUseNewSatelliteMode) {
            SatelliteModeListener.initialize(
                    mLooper, mContentResolver, this::onSatelliteModeChanged);
        }
    }

    private void internalHandleOnBootPhase(UserHandle userHandle) {
        if (DBG) {
            Log.d(TAG, "Bluetooth boot completed");
        }

        initialize(userHandle);

        final boolean isBluetoothDisallowed = isBluetoothDisallowed();
        if (isBluetoothDisallowed) {
            return;
        }
        final boolean isSafeMode = mContext.getPackageManager().isSafeMode();
        if (mEnableExternal && isBluetoothPersistedStateOnBluetooth() && !isSafeMode) {
            if (DBG) {
                Log.d(TAG, "Auto-enabling Bluetooth.");
            }
            sendEnableMsg(
                    mQuietEnableExternal,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT,
                    mContext.getPackageName());
        } else if (!isNameAndAddressSet()) {
            if (DBG) {
                Log.d(TAG, "Getting adapter name and address");
            }
            mHandler.sendEmptyMessage(MESSAGE_GET_NAME_AND_ADDRESS);
        }

        if (!mUseNewAirplaneMode) {
            mBluetoothAirplaneModeListener.start(new BluetoothModeChangeHelper(mContext));
            setApmEnhancementState();
        }
    }

    /** set APM enhancement feature state */
    @VisibleForTesting
    void setApmEnhancementState() {
        Settings.Global.putInt(
                mContext.getContentResolver(), APM_ENHANCEMENT, DEFAULT_APM_ENHANCEMENT_STATE);
    }

    /** Called when switching to a different foreground user. */
    private void handleSwitchUser(UserHandle userHandle) {
        if (DBG) {
            Log.d(TAG, "User " + userHandle + " switched");
        }
        mHandler.obtainMessage(MESSAGE_USER_SWITCHED, userHandle).sendToTarget();
    }

    /** Called when user is unlocked. */
    void handleOnUnlockUser(UserHandle userHandle) {
        if (DBG) {
            Log.d(TAG, "User " + userHandle + " unlocked");
        }
        mHandler.obtainMessage(MESSAGE_USER_UNLOCKED, userHandle).sendToTarget();
    }

    /**
     * This class manages the clients connected to a given ProfileService and maintains the
     * connection with that service.
     */
    private final class ProfileServiceConnections
            implements ServiceConnection, IBinder.DeathRecipient {
        final RemoteCallbackList<IBluetoothProfileServiceConnection> mProxies =
                new RemoteCallbackList<IBluetoothProfileServiceConnection>();
        IBinder mService;
        ComponentName mClassName;
        Intent mIntent;

        ProfileServiceConnections(Intent intent) {
            mService = null;
            mClassName = null;
            mIntent = intent;
        }

        private boolean bindService(int rebindCount) {
            if (!mState.oneOf(STATE_ON)) {
                if (DBG) {
                    Log.d(TAG, "Unable to bindService while Bluetooth is disabled");
                }
                return false;
            }

            if (mIntent != null
                    && mService == null
                    && doBind(mIntent, this, 0, USER_HANDLE_CURRENT_OR_SELF)) {
                Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE, this);
                msg.arg1 = rebindCount;
                mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
                return true;
            }
            Log.w(TAG, "Unable to bind with intent: " + mIntent);
            return false;
        }

        private void addProxy(IBluetoothProfileServiceConnection proxy) {
            mProxies.register(proxy);
            if (mService != null) {
                try {
                    proxy.onServiceConnected(mClassName, mService);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to connect to proxy", e);
                }
            } else {
                if (isBluetoothAvailableForBinding() == false) {
                    Log.w(TAG, "addProxy: Trying to bind to profile: " + mClassName +
                           ", while Bluetooth is disabled");
                    mProxies.unregister(proxy);
                    return;
                }

                if (!mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE, this)) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE, this);
                    msg.arg1 = DEFAULT_REBIND_COUNT;
                    mHandler.sendMessage(msg);
                }
            }
        }

        private void removeProxy(IBluetoothProfileServiceConnection proxy) {
            if (proxy != null) {
                if (mProxies.unregister(proxy)) {
                    try {
                        proxy.onServiceDisconnected(mClassName);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to disconnect proxy", e);
                    }
                }

                Log.w(TAG, "removing the proxy, count is "
                               + mProxies.getRegisteredCallbackCount());
            } else {
                Log.w(TAG, "Trying to remove a null proxy");
            }
        }

        private void removeAllProxies() {
            onServiceDisconnected(mClassName);
            mProxies.kill();
        }

        private boolean isEmpty() {
            return (mProxies != null && mProxies.getRegisteredCallbackCount() == 0);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // remove timeout message
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE, this);
            mService = service;
            mClassName = className;
            try {
                mService.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to linkToDeath", e);
            }

            synchronized (mProxies) {
                final int n = mProxies.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            mProxies.getBroadcastItem(i).onServiceConnected(className, service);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to connect to proxy", e);
                        }
                    }
                } finally {
                    mProxies.finishBroadcast();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (mService == null) return;
            try {
                mService.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Unable to unlinkToDeath", e);
            }
            mService = null;
            mClassName = null;

            synchronized (mProxies) {
                final int n = mProxies.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        try {
                            mProxies.getBroadcastItem(i).onServiceDisconnected(className);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to disconnect from proxy #" + i, e);
                        }
                    }
                } finally {
                    mProxies.finishBroadcast();
                }
            }
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.w(TAG, "Profile service for profile: " + mClassName + " died.");
            }
            onServiceDisconnected(mClassName);

            if (isBluetoothAvailableForBinding() == false) {
                Log.w(TAG, "binderDied: Trying to bind to profile: " + mClassName +
                           ", while Bluetooth is disabled");
                return;
            }
            // Trigger rebind
            Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE, this);
            mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
        }
    }

    private void sendBluetoothStateCallback(boolean isUp) {
        synchronized (mStateChangeCallbacks) {
            try {
                int n = mStateChangeCallbacks.beginBroadcast();
                if (DBG) {
                    Log.d(
                            TAG,
                            "Broadcasting onBluetoothStateChange("
                                    + isUp
                                    + ") to "
                                    + n
                                    + " receivers.");
                }
                for (int i = 0; i < n; i++) {
                    try {
                        mStateChangeCallbacks.getBroadcastItem(i).onBluetoothStateChange(isUp);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i, e);
                    }
                }
            } finally {
                mStateChangeCallbacks.finishBroadcast();
            }
        }
    }

    /** Inform BluetoothAdapter instances that Adapter service is up */
    private void sendBluetoothServiceUpCallback() {
        synchronized (mCallbacks) {
            mAdapterLock.readLock().lock();
            try {
                int n = mCallbacks.beginBroadcast();
                Log.d(TAG, "Broadcasting onBluetoothServiceUp() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks
                                .getBroadcastItem(i)
                                .onBluetoothServiceUp(mAdapter.getAdapterBinder());
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
                mAdapterLock.readLock().unlock();
            }
        }
    }

    /** Inform BluetoothAdapter instances that Adapter service is down */
    private void sendBluetoothServiceDownCallback() {
        synchronized (mCallbacks) {
            try {
                int n = mCallbacks.beginBroadcast();
                Log.d(TAG, "Broadcasting onBluetoothServiceDown() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onBluetoothServiceDown();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to call onBluetoothServiceDown() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }
        }
    }

    String getAddress(AttributionSource source) {
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter != null) {
                return mAdapter.getAddress(source);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(
                    TAG,
                    "getAddress(): Unable to retrieve address remotely. Returning cached address",
                    e);
        } finally {
            mAdapterLock.readLock().unlock();
        }

        // mAddress is accessed from outside.
        // It is alright without a lock. Here, bluetooth is off, no other thread is
        // changing mAddress
        return mAddress;
    }

    String getName(AttributionSource source) {
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter != null) {
                return mAdapter.getName(source);
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "getName(): Unable to retrieve name remotely. Returning cached name", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }

        // mName is accessed from outside.
        // It alright without a lock. Here, bluetooth is off, no other thread is
        // changing mName
        return mName;
    }

    @VisibleForTesting
    class BluetoothServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            if (DBG) {
                Log.d(TAG, "BluetoothServiceConnection: " + name);
            }
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service connected: " + name);
                return;
            }
            mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED, service).sendToTarget();
        }

        public void onServiceDisconnected(ComponentName componentName) {
            // Called if we unexpectedly disconnect.
            String name = componentName.getClassName();
            if (DBG) {
                Log.d(TAG, "BluetoothServiceConnection, disconnected: " + name);
            }
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service disconnected: " + name);
                return;
            }
            mHandler.sendEmptyMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    @VisibleForTesting
    class BluetoothHandler extends Handler {
        boolean mGetNameAddressOnly = false;
        private int mWaitForEnableRetry;
        private int mWaitForDisableRetry;

        BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                    }
                    mAdapterLock.writeLock().lock();
                    try {
                        if (mAdapter == null && !isBinding()) {
                            if (DBG) {
                                Log.d(TAG, "Binding to service to get name and address");
                            }
                            mGetNameAddressOnly = true;
                            mHandler.sendEmptyMessageDelayed(MESSAGE_TIMEOUT_BIND, TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!doBind(
                                    i,
                                    mConnection,
                                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                                    UserHandle.CURRENT)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                            } else {
                                mBindingUserID = ActivityManager.getCurrentUser();
                                Log.d(TAG, "Binding BT service. Current user: " + mBindingUserID);
                            }
                        } else if (mAdapter != null) {
                            try {
                                storeNameAndAddress(
                                        mAdapter.getName(mContext.getAttributionSource()),
                                        mAdapter.getAddress(mContext.getAttributionSource()));
                            } catch (RemoteException | TimeoutException e) {
                                Log.e(TAG, "Unable to grab names", e);
                            }
                            if (mGetNameAddressOnly && !mEnable) {
                                unbindAndFinish();
                            }
                            mGetNameAddressOnly = false;
                        }
                    } finally {
                        mAdapterLock.writeLock().unlock();
                    }
                    break;

                case MESSAGE_ENABLE:
                    int quietEnable = msg.arg1;
                    int isBle = msg.arg2;
                    if (mShutdownInProgress) {
                        Log.d(TAG, "Skip Bluetooth Enable in device shutdown process");
                        break;
                    }

                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        if (msg.arg2 == 0) {
                            int delay = ENABLE_DISABLE_DELAY_MS;

                            if (mHandler.hasMessages(MESSAGE_DISABLE)) {
                                delay = ENABLE_DISABLE_DELAY_MS * 2;
                            }
                            // Keep only one MESSAGE_ENABLE and ensure it is the last one
                            // to be taken out of the queue
                            mHandler.removeMessages(MESSAGE_ENABLE);
                            // We are handling enable or disable right now, wait for it.
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_ENABLE, quietEnable, 1), delay);
                            Log.d(TAG, "Queue new MESSAGE_ENABLE");
                        } else {
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_ENABLE, quietEnable, isBle), ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue previous MESSAGE_ENABLE");
                            if (mHandler.hasMessages(MESSAGE_DISABLE)) {
                                // Ensure the original order of just entering the queue
                                // if MESSAGE_DISABLE present
                                mHandler.removeMessages(MESSAGE_DISABLE);
                                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                    MESSAGE_DISABLE, 0, isBle), ENABLE_DISABLE_DELAY_MS * 2);
                                Log.d(TAG, "Re-Queue previous MESSAGE_DISABLE");
                            }
                        }
                        break;
                    } else if(msg.arg2 == 0 && mHandler.hasMessages(MESSAGE_DISABLE)) {
                        mHandler.removeMessages(MESSAGE_ENABLE);
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                            MESSAGE_ENABLE, quietEnable, isBle), ENABLE_DISABLE_DELAY_MS * 2);
                        Log.d(TAG, "MESSAGE_DISABLE exist. Queue new MESSAGE_ENABLE");
                        break;
                    }

                    if (DBG) {
                        Log.d(TAG, "MESSAGE_ENABLE(" + quietEnable + "): mAdapter=" + mAdapter);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mEnable = true;

                    if (isBle == 0) {
                        persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                    }

                    mQuietEnable = (quietEnable == 1);
                    // Use service interface to get the exact state
                    mAdapterLock.readLock().lock();
                    try {
                        if (mAdapter != null) {
                            boolean isHandled = true;
                            switch (mState.get()) {
                                case STATE_BLE_ON:
                                    if (isBle == 1) {
                                        Log.i(TAG, "Already at BLE_ON State");
                                    } else if (isBluetoothPersistedStateOnBluetooth() ||
                                        mEnableExternal) {
                                        Log.w(TAG, "BLE_ON State:Enable from Settings or" +
                                                    "BT on persisted, going to ON");
                                        mAdapter.updateQuietModeStatus(mQuietEnable,
                                                mContext.getAttributionSource());
                                        mAdapter.startBrEdr(mContext.getAttributionSource());
                                        persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);

                                        // waive WRITE_SECURE_SETTINGS permission check
                                        long callingIdentity = Binder.clearCallingIdentity();
                                        Binder.restoreCallingIdentity(callingIdentity);
                                    } else {
                                        Log.w(TAG, "BLE_ON State:Queued enable from ble app," +
                                                    " stay in ble on");
                                    }
                                    break;
                                case STATE_BLE_TURNING_ON:
                                case STATE_TURNING_ON:
                                case STATE_ON:
                                    Log.i(TAG, "MESSAGE_ENABLE: already enabled");
                                    break;
                                default:
                                    isHandled = false;
                                    break;
                            }
                            if (isHandled) break;
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "", e);
                    } finally {
                        mAdapterLock.readLock().unlock();
                    }

                    if (mAdapter == null) {
                        Log.d(TAG, "MESSAGE_ENABLE: handleEnable");
                        handleEnable(mQuietEnable);
                    } else {
                        //
                        // We need to wait until transitioned to STATE_OFF and
                        // the previous Bluetooth process has exited. The
                        // waiting period has three components:
                        // (a) Wait until the local state is STATE_OFF. This
                        //     is accomplished by sending delay a message
                        //     MESSAGE_HANDLE_ENABLE_DELAYED
                        // (b) Wait until the STATE_OFF state is updated to
                        //     all components.
                        // (c) Wait until the Bluetooth process exits, and
                        //     ActivityManager detects it.
                        // The waiting for (b) and (c) is accomplished by
                        // delaying the MESSAGE_RESTART_BLUETOOTH_SERVICE
                        // message. The delay time is backed off if Bluetooth
                        // continuously failed to turn on itself.
                        //
                        mWaitForEnableRetry = 0;
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_ENABLE_DELAYED");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_HANDLE_ENABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                    }
                    break;

                case MESSAGE_DISABLE:
                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            || isBinding()
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        if (msg.arg2 == 0) {
                            int delay = ENABLE_DISABLE_DELAY_MS;

                            if (mHandler.hasMessages(MESSAGE_ENABLE)) {
                                delay = ENABLE_DISABLE_DELAY_MS * 2;
                            }
                            // Keep only one MESSAGE_DISABLE and ensure it is the last one
                            // to be taken out of the queue
                            mHandler.removeMessages(MESSAGE_DISABLE);
                            // We are handling enable or disable right now, wait for it.
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_DISABLE, 0, 1), delay);
                            Log.d(TAG, "Queue new MESSAGE_DISABLE");
                        } else {
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MESSAGE_DISABLE, 0, 1), ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue previous MESSAGE_DISABLE");
                            if (mHandler.hasMessages(MESSAGE_ENABLE)) {
                                // Ensure the original order of just entering the queue
                                // if MESSAGE_DISABLE present
                                mHandler.removeMessages(MESSAGE_ENABLE);
                                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                   MESSAGE_ENABLE, mQuietEnableExternal ? 1: 0, 1),
                                   ENABLE_DISABLE_DELAY_MS * 2);
                                Log.d(TAG, "Re-Queue previous MESSAGE_ENABLE");
                            }
                        }
                        break;
                    } else if(msg.arg2 == 0 && mHandler.hasMessages(MESSAGE_ENABLE)) {
                        mHandler.removeMessages(MESSAGE_DISABLE);
                        // We are handling enable or disable right now, wait for it.
                        mHandler.sendEmptyMessageDelayed(MESSAGE_DISABLE, ENABLE_DISABLE_DELAY_MS * 2);
                        Log.d(TAG, "MESSAGE_ENABLE exist. Queue new MESSAGE_DISABLE");
                        break;
                    }

                    if (DBG) {
                        Log.d(TAG, "MESSAGE_DISABLE: mAdapter=" + mAdapter
                                    + ", mEnable = " + mEnable);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);

                    if (mEnable && mAdapter != null) {
                        mWaitForDisableRetry = 0;
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_HANDLE_DISABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(0)");
                    } else {
                        mEnable = false;
                        handleDisable();
                        Log.d(TAG, "MESSAGE_DISABLE: handleDisable");
                    }
                    break;

                case MESSAGE_HANDLE_ENABLE_DELAYED:
                    /* The Bluetooth is turning off, wait for STATE_OFF then restart bluetooth
                     * if ble app running, then wait for BLE ON and continue bt turn on
                     */
                    Log.d(TAG, "MESSAGE_HANDLE_ENABLE_DELAYED, mState=" +
                        BluetoothAdapter.nameForState(mState.get()) + " mEnableExternal = "
                        + mEnableExternal + " getServiceRestartMs()="
                        + getServiceRestartMs());
                    if ((mState.get() == STATE_BLE_ON) && (isBleAppPresent() ||
                                mWaitForEnableRetry > 0)) {
                        Log.d(TAG, "isBleAppPresent(): " + isBleAppPresent() +
                                " mWaitForEnableRetry=" + mWaitForEnableRetry);
                        mWaitForEnableRetry = 0;
                        if (mEnableExternal || isBluetoothPersistedStateOnBluetooth()) {
                           try {
                                mAdapterLock.readLock().lock();
                                if (mAdapter != null) {
                                    mAdapter.updateQuietModeStatus(mQuietEnable,
                                            mContext.getAttributionSource());
                                }
                           } finally {
                                mAdapterLock.readLock().unlock();
                           }
                        } else {
                            Log.e(TAG, "BLE app running stay in BLE ON state");
                        }
                        break;
                    } else if (!mState.oneOf(STATE_OFF)) {
                        if (mWaitForEnableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                            mWaitForEnableRetry++;
                            mHandler.sendEmptyMessageDelayed(
                                    MESSAGE_HANDLE_ENABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                            Log.d(TAG, "Re-Queue MESSAGE_HANDLE_ENABLE_DELAYED");
                            break;
                        } else {
                            Log.e(TAG, "Wait for STATE_OFF timeout");
                        }
                    }
                    // Either state is changed to STATE_OFF or reaches the maximum retry, we
                    // should move forward to the next step.
                    mWaitForEnableRetry = 0;
                    mHandler.sendEmptyMessageDelayed(
                            MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                    Log.d(TAG, "Queue MESSAGE_RESTART_BLUETOOTH_SERVICE");
                    Log.d(TAG, "Handle enable is finished");
                    break;

                case MESSAGE_HANDLE_DISABLE_DELAYED:
                    boolean disabling = (msg.arg1 == 1);
                    Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: disabling:" + disabling);
                    if (!disabling) {
                        /* if bluetooth is in BLE ON state and enable is from ble app
                         * then skip disable, else wait for complete ON or timeout.
                         */
                        if ((mState.get() == STATE_BLE_ON) &&
                            !mEnableExternal &&
                            !isBluetoothPersistedStateOnBluetooth() &&
                            isBleAppPresent()) {
                            Log.w(TAG, "Enable from BLE APP, stay in BLE ON");
                            mWaitForDisableRetry = 0;
                            mEnable = false;
                            break;
                        } else if (!mState.oneOf(STATE_ON)) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                mHandler.sendEmptyMessageDelayed(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                                Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(0)");
                                break;
                            } else {
                                Log.e(TAG, "Wait for STATE_ON timeout");
                            }
                        }
                        // Either state is changed to STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        mWaitForDisableRetry = 0;
                        mEnable = false;
                        Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: handleDisable");
                        handleDisable();
                        // Wait for state exiting STATE_ON
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                        Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(1)");
                    } else {
                        // The Bluetooth is turning off, wait for exiting STATE_ON
                        if (mState.oneOf(STATE_ON)) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg =
                                        mHandler.obtainMessage(
                                                MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                                mHandler.sendMessageDelayed(
                                        disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                                Log.d(TAG, "Re-Queue MESSAGE_HANDLE_DISABLE_DELAYED(1)");
                                break;
                            } else {
                                Log.e(TAG, "Wait for exiting STATE_ON timeout");
                            }
                        }
                        // Either state is exited from STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        Log.d(TAG, "Handle disable is finished");
                    }
                    break;

                case MESSAGE_RESTORE_USER_SETTING:
                    if ((msg.arg1 == RESTORE_SETTING_TO_OFF) && mEnable) {
                        if (DBG) {
                            Log.d(TAG, "Restore Bluetooth state to disabled");
                        }
                        persistBluetoothSetting(BLUETOOTH_OFF);
                        mEnableExternal = false;
                        sendDisableMsg(
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                mContext.getPackageName());
                    } else if ((msg.arg1 == RESTORE_SETTING_TO_ON) && !mEnable) {
                        if (DBG) {
                            Log.d(TAG, "Restore Bluetooth state to enabled");
                        }
                        mQuietEnableExternal = false;
                        mEnableExternal = true;
                        // waive WRITE_SECURE_SETTINGS permission check
                        sendEnableMsg(
                                false,
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                mContext.getPackageName());
                    }
                    break;
                case MESSAGE_INFORM_ADAPTER_SERVICE_UP:
                    if (DBG) Log.d(TAG,"MESSAGE_INFORM_ADAPTER_SERVICE_UP");
                    sendBluetoothServiceUpCallback();
                    break;
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK:
                    IBluetoothStateChangeCallback regCallback =
                            (IBluetoothStateChangeCallback)msg.obj;
                    if (mState.get() != STATE_ON) {
                        try {
                            regCallback.onBluetoothStateChange(true);
                        } catch (RemoteException e) {
                            Log.e(TAG, "REGISTER_STATE_CHANGE_CALLBACK: callback failed", e);
                            break;
                        }
                    }
                    mStateChangeCallbacks.register(regCallback);
                    break;

                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK:
                    IBluetoothStateChangeCallback unregCallback =
                            (IBluetoothStateChangeCallback)msg.obj;
                    try {
                        // LINT.IfChange
                        unregCallback.onBluetoothStateChange(false);
                        // LINT.ThenChange(/framework/tests/unit/src/android/bluetooth/BluetoothProfileConnectorTest.java)
                    } catch (RemoteException e) {
                        Log.e(TAG, "UNREGISTER_STATE_CHANGE_CALLBACK: callback failed", e);
                    }
                    mStateChangeCallbacks.unregister(unregCallback);
                    break;

                case MESSAGE_ADD_PROXY_DELAYED:
                    ProfileServiceConnections connection = mProfileServices.get(msg.arg1);
                    if (connection == null) {
                        break;
                    }
                    IBluetoothProfileServiceConnection proxy =
                            (IBluetoothProfileServiceConnection) msg.obj;
                    connection.addProxy(proxy);
                    break;
                case MESSAGE_BIND_PROFILE_SERVICE:
                    Log.w(TAG, "MESSAGE_BIND_PROFILE_SERVICE");
                    ProfileServiceConnections psc = (ProfileServiceConnections) msg.obj;
                    removeMessages(MESSAGE_BIND_PROFILE_SERVICE, msg.obj);
                    if (psc == null) {
                        Log.w(TAG, "psc is null, breaking");
                        break;
                    }
                    if (msg.arg1 > 0) {
                        try {
                            mContext.unbindService(psc);
                            Log.w(TAG, "Calling psc.bindService from MESSAGE_BIND_PROFILE_SERVICE");
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                        }
                        psc.bindService(msg.arg1 - 1);
                    }
                    break;

                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: " + msg.arg1);
                    }

                    IBinder service = (IBinder) msg.obj;
                    mAdapterLock.writeLock().lock();
                    try {
                        // Remove timeout
                        mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                        
                        mAdapter = BluetoothServerProxy.getInstance().createAdapterBinder(service);

                        int foregroundUserId = ActivityManager.getCurrentUser();
                        propagateForegroundUserId(foregroundUserId);

                        if (!isNameAndAddressSet()) {
                            mHandler.sendEmptyMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            if (mGetNameAddressOnly) {
                                return;
                            }
                        }

                        // Register callback object
                        try {
                            mAdapter.registerCallback(
                                    mBluetoothCallback, mContext.getAttributionSource());
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to register BluetoothCallback", e);
                        }
                        // Inform BluetoothAdapter instances that service is up
                        Message informMsg =
                                    mHandler.obtainMessage(MESSAGE_INFORM_ADAPTER_SERVICE_UP);
                        mHandler.sendMessage(informMsg);

                        // Get the supported profiles list
                        try {
                            mSupportedProfileList =
                                    mAdapter.getSupportedProfiles(mContext.getAttributionSource());
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to get the supported profiles list", e);
                        }

                        // Do enable request
                        try {
                            if (!mAdapter.enable(mQuietEnable, mContext.getAttributionSource())) {
                                Log.e(TAG, "IBluetooth.enable() returned false");
                            }
                        } catch (RemoteException | TimeoutException e) {
                            Log.e(TAG, "Unable to call enable()", e);
                        }
                    } finally {
                        mAdapterLock.writeLock().unlock();
                    }

                    if (!mEnable) {
                        /* Wait for BLE ON or ON state ,if enable is from BLE app
                         * skip disable, else wait for on state and handle disable
                         */
                        waitForState(STATE_BLE_ON, STATE_ON);

                        int st = getState();
                        if ((st == STATE_TURNING_ON) ||
                           ((st == STATE_BLE_ON) &&
                           (mEnableExternal || isBluetoothPersistedStateOnBluetooth()))) {
                            waitForState(STATE_ON);
                        } else if ((st == STATE_BLE_ON) && isBleAppPresent()) {
                            Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: ble app present");
                            break;
                        }
                        handleDisable();
                        waitForState(
                                STATE_OFF,
                                STATE_TURNING_ON,
                                STATE_TURNING_OFF,
                                STATE_BLE_TURNING_ON,
                                STATE_BLE_ON,
                                STATE_BLE_TURNING_OFF);
                    }
                    break;

                case MESSAGE_BLUETOOTH_STATE_CHANGE:
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    if (DBG) {
                        Log.d(
                                TAG,
                                "MESSAGE_BLUETOOTH_STATE_CHANGE: "
                                        + BluetoothAdapter.nameForState(prevState)
                                        + " > "
                                        + BluetoothAdapter.nameForState(newState));
                    }
                    mState.set(newState);
                    bluetoothStateChangeHandler(prevState, newState);
                    // handle error state transition case from TURNING_ON to OFF
                    // unbind and rebind bluetooth service and enable bluetooth
                    if ((prevState == STATE_BLE_TURNING_ON)
                            && (newState == STATE_OFF)
                            && (mAdapter != null)
                            && mEnable) {
                        recoverBluetoothServiceFromError(false);
                    }
                    if ((prevState == STATE_TURNING_ON)
                            && (newState == STATE_OFF)
                            && (mAdapter != null)
                            && mEnable) {
                         persistBluetoothSetting(BLUETOOTH_OFF);
                    }
                    if ((prevState == STATE_TURNING_ON)
                            && (newState == STATE_BLE_ON)
                            && (mAdapter != null)
                            && mEnable) {
                        recoverBluetoothServiceFromError(true);
                    }
                    // If we tried to enable BT while BT was in the process of shutting down,
                    // wait for the BT process to fully tear down and then force a restart
                    // here.  This is a bit of a hack (b/29363429).
                    if (prevState == STATE_BLE_TURNING_OFF && newState == STATE_OFF) {
                        if (mEnable) {
                            Log.d(TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                            mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                            waitForState(STATE_OFF);
                            mHandler.sendEmptyMessageDelayed(
                                    MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                        }
                    }
                    if (newState == STATE_ON || newState == STATE_BLE_ON) {
                        // bluetooth is working, reset the counter
                        if (mErrorRecoveryRetryCounter != 0) {
                            Log.w(TAG, "bluetooth is recovered from error");
                            mErrorRecoveryRetryCounter = 0;
                        }
                    }
                    break;

                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED:
                    Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED");
                    mAdapterLock.writeLock().lock();
                    try {
                        // if service is unbinded already, do nothing and return
                        if (mAdapter == null) {
                            break;
                        }
                        mAdapter = null;
                        mSupportedProfileList.clear();
                    } finally {
                        mAdapterLock.writeLock().unlock();
                    }

                    // Make sure BT process exit completely
                    int[] pids = null;
                    try {
                        pids = (int[]) Class.forName("android.os.Process")
                            .getMethod("getPidsForCommands", String[].class)
                            .invoke(new String[]{ "com.android.bluetooth" });
                    } catch (Exception e) {
                        Log.e(TAG, "Error to call getPidsForCommands");
                    }
                    if (pids != null && pids.length > 0) {
                        for(int pid : pids) {
                            Log.e(TAG, "Killing BT process with PID = " + pid);
                            Process.killProcess(pid);
                        }
                    }

                    // log the unexpected crash
                    addCrashLog();
                    addActiveLog(
                            BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH,
                            mContext.getPackageName(),
                            false);
                    if (mEnable) {
                        mEnable = false;
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                    }

                    sendBluetoothServiceDownCallback();

                    // Send BT state broadcast to update
                    // the BT icon correctly
                    if (mState.oneOf(STATE_TURNING_ON, STATE_ON)) {
                        bluetoothStateChangeHandler(STATE_ON, STATE_TURNING_OFF);
                        mState.set(STATE_TURNING_OFF);
                    }
                    if (mState.oneOf(STATE_TURNING_OFF)) {
                        bluetoothStateChangeHandler(STATE_TURNING_OFF, STATE_OFF);
                    }

                    mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
                    mState.set(STATE_OFF);
                    break;

                case MESSAGE_RESTART_BLUETOOTH_SERVICE:
                    mErrorRecoveryRetryCounter++;
                    Log.d(
                            TAG,
                            "MESSAGE_RESTART_BLUETOOTH_SERVICE: retry count="
                                    + mErrorRecoveryRetryCounter);
                    if (mErrorRecoveryRetryCounter < MAX_ERROR_RESTART_RETRIES) {
                        /* Enable without persisting the setting as
                         * it doesn't change when IBluetooth
                         * service restarts */
                        mEnable = true;
                        addActiveLog(
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED,
                                mContext.getPackageName(),
                                true);
                        handleEnable(mQuietEnable);
                    } else {
                        mAdapterLock.writeLock().lock();
                        mAdapter = null;
                        mAdapterLock.writeLock().unlock();
                        Log.e(TAG, "Reach maximum retry to restart Bluetooth!");
                    }
                    break;

                case MESSAGE_TIMEOUT_BIND:
                    Log.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    // TODO(b/286082382): Timeout should be more than a log. We should at least call
                    // context.unbindService, eventually log a metric with it
                    // Ensure try BIND for one more time
                    if(!mTryBindOnBindTimeout) {
                        int userID = ActivityManager.getCurrentUser();

                        Log.d(TAG, "Current user: " + userID);
                        if (mBindingUserID == userID) {
                            Log.e(TAG, " Trying to Bind again");
                            mTryBindOnBindTimeout = true;
                            handleEnable(mQuietEnable);
                        }
                    } else {
                        Log.e(TAG, "Bind trails excedded");
                        mTryBindOnBindTimeout = false;
                    }
                    break;

                case MESSAGE_USER_SWITCHED:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_USER_SWITCHED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);
                    if (!mFeatureFlags.airplaneRessourcesInApp() && !mUseNewAirplaneMode) {
                        mBluetoothNotificationManager.createNotificationChannels();
                    }
                    UserHandle userTo = (UserHandle) msg.obj;

                    try {
                        mAdapterLock.writeLock().lock();
                        int state = getState();

                        if (mAdapter != null && isEnabled()) {
                            if (mUseNewAirplaneMode) {
                                mCurrentUserContext = mContext.createContextAsUser(userTo, 0);
                            }
                            /* disable and enable BT when detect a user switch */
                            if (state == STATE_ON) {
                                restartForNewUser(userTo);
                            } else {
                                if (DBG) {
                                    Log.d(TAG, "Turn off from BLE state");
                                }
                                clearBleApps();
                                addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH,
                                          mContext.getPackageName(), false);
                                mEnable = false;
                                mAdapter.stopBle(mContext.getAttributionSource());
                            }
                        } else if (isBinding() || mAdapter != null) {
                            Message userMsg = Message.obtain(msg);
                            userMsg.arg1++;
                            // if user is switched when service is binding retry after a delay
                            mHandler.sendMessageDelayed(userMsg, USER_SWITCHED_TIME_MS);
                            if (DBG) {
                                Log.d(
                                    TAG,
                                    "MESSAGE_USER_SWITCHED user="
                                            + userTo
                                            + " number of retry attempt="
                                            + userMsg.arg1);
                            }
                        }
                    } catch (RemoteException | TimeoutException e) {
                        Log.e(TAG, "MESSAGE_USER_SWITCHED: Remote exception", e);
                    } finally {
                        mAdapterLock.writeLock().unlock();
                    }
                    break;

                case MESSAGE_USER_UNLOCKED:
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_USER_UNLOCKED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    if (mEnable && !isBinding() && (mAdapter == null)) {
                        // We should be connected, but we gave up for some
                        // reason; maybe the Bluetooth service wasn't encryption
                        // aware, so try binding again.
                        if (DBG) {
                            Log.d(TAG, "Enabled but not bound; retrying after unlock");
                        }
                        handleEnable(mQuietEnable);
                    }
                    break;
            }
        }

        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED
                })
        private void restartForNewUser(UserHandle newUser) {
            mAdapterLock.readLock().lock();
            try {
                if (mAdapter != null) {
                    mAdapter.unregisterCallback(
                            mBluetoothCallback, mContext.getAttributionSource());
                }
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, "Unable to unregister", e);
            } finally {
                mAdapterLock.readLock().unlock();
            }

            if (mState.oneOf(STATE_TURNING_OFF)) {
                // MESSAGE_USER_SWITCHED happened right after MESSAGE_ENABLE
                bluetoothStateChangeHandler(STATE_TURNING_OFF, STATE_OFF);
                mState.set(STATE_OFF);
            }
            if (mState.oneOf(STATE_OFF)) {
                bluetoothStateChangeHandler(STATE_OFF, STATE_TURNING_ON);
                mState.set(STATE_TURNING_ON);
            }

            // TODO(b/285046954): We call unregisterCallback, and yet the code is calling
            // waitForState(STATE_ON)
            // This is a non-sense
            waitForState(STATE_BLE_ON, STATE_ON);

            if (mState.oneOf(STATE_TURNING_ON)) {
                bluetoothStateChangeHandler(STATE_TURNING_ON, STATE_ON);
            }

            unbindAllBluetoothProfileServices();
            // disable
            addActiveLog(
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH,
                    mContext.getPackageName(),
                    false);

            clearBleApps();
            handleDisable();
            // Pbap service need receive STATE_TURNING_OFF intent to close
            bluetoothStateChangeHandler(STATE_ON, STATE_TURNING_OFF);

            /* wait for BLE_ON or OFF state. If its BLE ON state
             * post BLE ON state to bluetoothStateChangeHandler
             * to continue off and wait for off state
             */
            boolean didDisableTimeout =
                    !waitForState(STATE_BLE_ON, STATE_OFF);

            if(!didDisableTimeout) {
               int state = getState();

               if (state == STATE_BLE_ON) {
                   bluetoothStateChangeHandler(STATE_TURNING_OFF,
                                            STATE_BLE_ON);
               }

               didDisableTimeout =
                    !waitForState(STATE_OFF);
            }
            bluetoothStateChangeHandler(STATE_TURNING_OFF, STATE_OFF);

            //
            // If disabling Bluetooth times out, wait for an
            // additional amount of time to ensure the process is
            // shut down completely before attempting to restart.
            //
            if (didDisableTimeout) {
                SystemClock.sleep(3000);
                mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            } else {
                SystemClock.sleep(100);
            }

            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mState.set(STATE_OFF);
            // enable
            addActiveLog(
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH,
                    mContext.getPackageName(),
                    true);
            // mEnable flag could have been reset on stopBle. Reenable it.
            mEnable = true;
            handleEnable(mQuietEnable);
        }
    }

    private boolean isBinding() {
        return mHandler.hasMessages(MESSAGE_TIMEOUT_BIND);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void handleEnable(boolean quietMode) {
        mQuietEnable = quietMode;

        mAdapterLock.writeLock().lock();
        try {
            if (mAdapter == null && !isBinding()) {
                Log.d(TAG, "binding Bluetooth service");
                // Start bind timeout and bind
                mHandler.sendEmptyMessageDelayed(MESSAGE_TIMEOUT_BIND, TIMEOUT_BIND_MS);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!doBind(
                        i,
                        mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                        UserHandle.CURRENT)) {
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                } else {
                    mBindingUserID = ActivityManager.getCurrentUser();
                    Log.d(TAG, "Binding BT service. Current user: " + mBindingUserID);
                }
            } else if (mAdapter != null) {
                // Enable bluetooth
                try {
                    if (!mAdapter.enable(mQuietEnable, mContext.getAttributionSource())) {
                        Log.e(TAG, "IBluetooth.enable() returned false");
                    }
                } catch (RemoteException | TimeoutException e) {
                    Log.e(TAG, "Unable to call enable()", e);
                }
            }
        } finally {
            mAdapterLock.writeLock().unlock();
        }
    }

    boolean doBind(Intent intent, ServiceConnection conn, int flags, UserHandle user) {
        ComponentName comp = resolveSystemService(intent);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, conn, flags, user)) {
            Log.e(TAG, "Fail to bind to: " + intent);
            return false;
        }
        return true;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void handleDisable() {
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter != null) {
                if (DBG) {
                    Log.d(TAG, "Sending off request.");
                }
                if (!mAdapter.disable(mContext.getAttributionSource())) {
                    Log.e(TAG, "IBluetooth.disable() returned false");
                }
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to call disable()", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }
    }

    private void broadcastIntentStateChange(String action, int prevState, int newState) {
        if (DBG) {
            Log.d(
                    TAG,
                    "broadcastIntentStateChange:"
                            + (" action=" + action.substring(action.lastIndexOf('.') + 1))
                            + (" prevState=" + BluetoothAdapter.nameForState(prevState))
                            + (" newState=" + BluetoothAdapter.nameForState(newState)));
        }
        // Send broadcast message to everyone else
        Intent intent = new Intent(action);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.ALL, null, getTempAllowlistBroadcastOptions());
    }

    private boolean isBleState(int state) {
        switch (state) {
            case STATE_BLE_ON:
            case STATE_BLE_TURNING_ON:
            case STATE_BLE_TURNING_OFF:
                return true;
        }
        return false;
    }

    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            })
    private void bluetoothStateChangeHandler(int prevState, int newState) {
        if (prevState == newState) { // No change. Nothing to do.
            return;
        }

        // Notify all proxy objects first of adapter state change
        if (newState == STATE_ON) {
            sendBluetoothStateCallback(true);
        } else if (newState == STATE_OFF) {
            // If Bluetooth is off, send service down event to proxy objects, and unbind
            if (DBG) {
                Log.d(TAG, "Bluetooth is complete send Service Down");
            }
            sendBluetoothServiceDownCallback();
            unbindAndFinish();
        } else if (newState == STATE_BLE_ON && prevState == STATE_BLE_TURNING_ON) {
            if (DBG) {
                Log.d(TAG, "Bluetooth is in LE only mode");
            }
            continueFromBleOnState();
        } // Nothing specific to do for STATE_TURNING_<X>

        broadcastIntentStateChange(BluetoothAdapter.ACTION_BLE_STATE_CHANGED, prevState, newState);

        // BLE state are shown as STATE_OFF for BrEdr users
        final int prevBrEdrState = isBleState(prevState) ? STATE_OFF : prevState;
        final int newBrEdrState = isBleState(newState) ? STATE_OFF : newState;

        if (prevBrEdrState != newBrEdrState) { // Only broadcast when there is a BrEdr state change.
            if (newBrEdrState == STATE_OFF) {
                sendBluetoothStateCallback(false);
                sendBrEdrDownCallback(mContext.getAttributionSource());
            }
            broadcastIntentStateChange(
                    BluetoothAdapter.ACTION_STATE_CHANGED, prevBrEdrState, newBrEdrState);
        }
    }

    boolean waitForManagerState(int state) {
        return mState.waitForState(getSyncTimeout(), state);
    }

    private boolean waitForState(int... states) {
        return mState.waitForState(getSyncTimeout(), states);
    }

    /* TODO(b/151672214) - Update for change from waitForOnOff -> waitForState
    private boolean waitForMonitoredState(Set<Integer> states) {
        int i = 0;
        while (i < 10) {
            synchronized(mConnection) {
                try {
                    if (mAdapter == null) break;
                    if (on) {
                        if (mAdapter.getState() == STATE_ON) return true;
                        if (mAdapter.getState() == STATE_BLE_ON) {
                            bluetoothStateChangeHandler(STATE_BLE_TURNING_ON,
                                                        STATE_BLE_ON);
                            if (mBluetoothGatt != null) {
                                Log.d(TAG,"GattService is connected, execute waitForState");
                                boolean ret = waitForState(states);
                                return ret;
                            } else {
                                Log.d(TAG,
                                    "GattService connect in progress, return to avoid timeout");
                                return true;
                            }
                        }
                    } else if (off) {
                        if (mAdapter.getState() == STATE_OFF) return true;
                        if (mAdapter.getState() == STATE_BLE_ON) {
                            bluetoothStateChangeHandler(STATE_TURNING_OFF,
                                                        STATE_BLE_ON);
                            boolean ret = waitForState(states);
                            return ret;
                        }
                    } else {
                        if (mAdapter.getState() != STATE_ON) return true;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "getState()", e);
                    break;
                }
            }
            SystemClock.sleep(300);
            i++;
        }
        Log.e(TAG,"waitForMonitoredOnOff time out");
        return false;
    }
    */

    private void sendDisableMsg(int reason, String packageName) {
        mHandler.sendEmptyMessage(MESSAGE_DISABLE);
        addActiveLog(reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        sendEnableMsg(quietMode, reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName, boolean isBle) {
        mHandler.obtainMessage(MESSAGE_ENABLE, quietMode ? 1 : 0, isBle ? 1 : 0).sendToTarget();
        addActiveLog(reason, packageName, true);
        mLastEnabledTime = SystemClock.elapsedRealtime();
    }

    private void addActiveLog(int reason, String packageName, boolean enable) {
        synchronized (mActiveLogs) {
            if (mActiveLogs.size() > ACTIVE_LOG_MAX_SIZE) {
                mActiveLogs.remove();
            }
            mActiveLogs.add(new ActiveLog(reason, packageName, enable, System.currentTimeMillis()));

            int state =
                    enable
                            ? BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED
                            : BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED;

            BluetoothStatsLog.write_non_chained(
                    BluetoothStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED,
                    Binder.getCallingUid(),
                    null,
                    state,
                    reason,
                    packageName);
        }
    }

    private void addCrashLog() {
        synchronized (mCrashTimestamps) {
            if (mCrashTimestamps.size() == CRASH_LOG_MAX_SIZE) {
                mCrashTimestamps.removeFirst();
            }
            mCrashTimestamps.add(System.currentTimeMillis());
            mCrashes++;
        }
    }

    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            })
    private void recoverBluetoothServiceFromError(boolean clearBle) {
        Log.e(TAG, "recoverBluetoothServiceFromError");
        boolean repeatAirplaneRunnable = false;

        // 0 means we are matching unset `what` since we are using a token instead
        if (mHandler.hasMessages(0, ON_AIRPLANE_MODE_CHANGED_TOKEN)) {
            mHandler.removeCallbacksAndMessages(ON_AIRPLANE_MODE_CHANGED_TOKEN);
            repeatAirplaneRunnable = true;
        }
        mAdapterLock.readLock().lock();
        try {
            if (mAdapter != null) {
                // Unregister callback object
                mAdapter.unregisterCallback(mBluetoothCallback, mContext.getAttributionSource());
            }
        } catch (RemoteException | TimeoutException e) {
            Log.e(TAG, "Unable to unregister", e);
        } finally {
            mAdapterLock.readLock().unlock();
        }

        waitForState(STATE_OFF);

        sendBluetoothServiceDownCallback();

        mAdapterLock.writeLock().lock();
        try {
            if (mAdapter != null) {
                mAdapter = null;
                // Unbind
                mContext.unbindService(mConnection);
            }
        } finally {
            mAdapterLock.writeLock().unlock();
        }

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        mState.set(STATE_OFF);

        if (clearBle) {
            clearBleApps();
        }

        mEnable = false;

        // Send a Bluetooth Restart message to reenable bluetooth
        mHandler.sendEmptyMessageDelayed(MESSAGE_RESTART_BLUETOOTH_SERVICE, ERROR_RESTART_TIME_MS);

        if (repeatAirplaneRunnable) {
            onAirplaneModeChanged(isAirplaneModeOn());
        }
    }

    private boolean isBluetoothDisallowed() {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(UserManager.class)
                    .hasUserRestrictionForUser(UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Disables BluetoothOppLauncherActivity component, so the Bluetooth sharing option is not
     * offered to the user if Bluetooth or sharing is disallowed. Puts the component to its default
     * state if Bluetooth is not disallowed.
     *
     * @param userHandle user to disable bluetooth sharing for
     * @param bluetoothSharingDisallowed whether bluetooth sharing is disallowed.
     */
    private void updateOppLauncherComponentState(
            UserHandle userHandle, boolean bluetoothSharingDisallowed) {
        try {
            int newState;
            if (bluetoothSharingDisallowed) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } else if (BluetoothProperties.isProfileOppEnabled().orElse(false)) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            } else {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }

            // Bluetooth OPP activities that should always be enabled,
            // even when Bluetooth is turned OFF.
            List<String> baseBluetoothOppActivities =
                    List.of(
                            // Base sharing activity
                            "com.android.bluetooth.opp.BluetoothOppLauncherActivity",
                            // BT enable activities
                            "com.android.bluetooth.opp.BluetoothOppBtEnableActivity",
                            "com.android.bluetooth.opp.BluetoothOppBtEnablingActivity",
                            "com.android.bluetooth.opp.BluetoothOppBtErrorActivity");

            PackageManager systemPackageManager = mContext.getPackageManager();
            PackageManager userPackageManager =
                    mContext.createContextAsUser(userHandle, 0).getPackageManager();
            var allPackages = systemPackageManager.getPackagesForUid(Process.BLUETOOTH_UID);
            for (String candidatePackage : allPackages) {
                Log.v(TAG, "Searching package " + candidatePackage);
                PackageInfo packageInfo;
                try {
                    packageInfo =
                            systemPackageManager.getPackageInfo(
                                    candidatePackage,
                                    PackageManager.PackageInfoFlags.of(
                                            PackageManager.GET_ACTIVITIES
                                                    | PackageManager.MATCH_ANY_USER
                                                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                                                    | PackageManager.MATCH_DISABLED_COMPONENTS));
                } catch (PackageManager.NameNotFoundException e) {
                    // ignore, try next package
                    Log.e(TAG, "Could not find package " + candidatePackage);
                    continue;
                } catch (Exception e) {
                    Log.e(TAG, "Error while loading package" + e);
                    continue;
                }
                if (packageInfo.activities == null) {
                    continue;
                }
                for (var activity : packageInfo.activities) {
                    Log.v(TAG, "Checking activity " + activity.name);
                    if (baseBluetoothOppActivities.contains(activity.name)) {
                        for (String activityName : baseBluetoothOppActivities) {
                            userPackageManager.setComponentEnabledSetting(
                                    new ComponentName(candidatePackage, activityName),
                                    newState,
                                    PackageManager.DONT_KILL_APP);
                        }
                        return;
                    }
                }
            }

            Log.e(
                    TAG,
                    "Cannot toggle Bluetooth OPP activities, could not find them in any package");
        } catch (Exception e) {
            Log.e(TAG, "updateOppLauncherComponentState failed: " + e);
        }
    }

    private int getServiceRestartMs() {
        return (mErrorRecoveryRetryCounter + 1) * SERVICE_RESTART_TIME_MS;
    }

    void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if ((args.length > 0) && args[0].startsWith("--proto")) {
            dumpProto(fd);
            return;
        }
        String errorMsg = null;

        writer.println("Bluetooth Status");
        writer.println("  enabled: " + isEnabled());
        writer.println("  state: " + mState);
        writer.println("  address: " + mAddress);
        writer.println("  name: " + mName);
        if (mEnable) {
            long onDuration = SystemClock.elapsedRealtime() - mLastEnabledTime;
            String onDurationString =
                    String.format(
                            Locale.US,
                            "%02d:%02d:%02d.%03d",
                            (int) (onDuration / (1000 * 60 * 60)),
                            (int) ((onDuration / (1000 * 60)) % 60),
                            (int) ((onDuration / 1000) % 60),
                            (int) (onDuration % 1000));
            writer.println("  time since enabled: " + onDurationString);
        }

        if (mActiveLogs.size() == 0) {
            writer.println("\nBluetooth never enabled!");
        } else {
            writer.println("\nEnable log:");
            for (ActiveLog log : mActiveLogs) {
                writer.println("  " + log);
            }
        }

        writer.println("\nBluetooth crashed " + mCrashes + " time" + (mCrashes == 1 ? "" : "s"));
        if (mCrashes == CRASH_LOG_MAX_SIZE) {
            writer.println("(last " + CRASH_LOG_MAX_SIZE + ")");
        }
        for (Long time : mCrashTimestamps) {
            writer.println("  " + timeToLog(time));
        }

        writer.println(
                "\n"
                        + mBleApps.size()
                        + " BLE app"
                        + (mBleApps.size() == 1 ? "" : "s")
                        + " registered");
        for (ClientDeathRecipient app : mBleApps.values()) {
            writer.println("  " + app.getPackageName());
        }

        writer.println("\nBluetoothManagerService:");
        writer.println("  mEnable:" + mEnable);
        writer.println("  mQuietEnable:" + mQuietEnable);
        writer.println("  mEnableExternal:" + mEnableExternal);
        writer.println("  mQuietEnableExternal:" + mQuietEnableExternal);

        writer.println("");
        writer.flush();
        if (args.length == 0) {
            // Add arg to produce output
            args = new String[1];
            args[0] = "--print";
        }

        if (mAdapter == null) {
            errorMsg = "Bluetooth Service not connected";
        } else {
            try {
                // TODO(b/239890880): system_server cannot make non-oneway call
                mAdapter.getAdapterBinder().asBinder().dump(fd, args);
            } catch (RemoteException re) {
                errorMsg = "RemoteException while dumping Bluetooth Service";
            }
        }
        if (errorMsg != null) {
            writer.println(errorMsg);
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(new FileOutputStream(fd));
        proto.write(BluetoothManagerServiceDumpProto.ENABLED, isEnabled());
        proto.write(BluetoothManagerServiceDumpProto.STATE, mState.get());
        proto.write(
                BluetoothManagerServiceDumpProto.STATE_NAME,
                BluetoothAdapter.nameForState(mState.get()));
        proto.write(BluetoothManagerServiceDumpProto.ADDRESS, mAddress);
        proto.write(BluetoothManagerServiceDumpProto.NAME, mName);
        if (mEnable) {
            proto.write(BluetoothManagerServiceDumpProto.LAST_ENABLED_TIME_MS, mLastEnabledTime);
        }
        proto.write(
                BluetoothManagerServiceDumpProto.CURR_TIMESTAMP_MS, SystemClock.elapsedRealtime());
        for (ActiveLog log : mActiveLogs) {
            long token = proto.start(BluetoothManagerServiceDumpProto.ACTIVE_LOGS);
            log.dump(proto);
            proto.end(token);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_CRASHES, mCrashes);
        proto.write(
                BluetoothManagerServiceDumpProto.CRASH_LOG_MAXED, mCrashes == CRASH_LOG_MAX_SIZE);
        for (Long time : mCrashTimestamps) {
            proto.write(BluetoothManagerServiceDumpProto.CRASH_TIMESTAMPS_MS, time);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_BLE_APPS, mBleApps.size());
        for (ClientDeathRecipient app : mBleApps.values()) {
            proto.write(
                    BluetoothManagerServiceDumpProto.BLE_APP_PACKAGE_NAMES, app.getPackageName());
        }
        proto.flush();
    }

    private static String getEnableDisableReasonString(int reason) {
        switch (reason) {
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST:
                return "APPLICATION_REQUEST";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE:
                return "AIRPLANE_MODE";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED:
                return "DISALLOWED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED:
                return "RESTARTED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR:
                return "START_ERROR";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT:
                return "SYSTEM_BOOT";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH:
                return "CRASH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH:
                return "USER_SWITCH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING:
                return "RESTORE_USER_SETTING";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET:
                return "FACTORY_RESET";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_INIT_FLAGS_CHANGED:
                return "INIT_FLAGS_CHANGED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_UNSPECIFIED:
            default:
                return "UNKNOWN[" + reason + "]";
        }
    }

    static @NonNull Bundle getTempAllowlistBroadcastOptions() {
        final long duration = 10_000;
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(
                duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_BLUETOOTH_BROADCAST,
                "");
        return bOptions.toBundle();
    }

    private ComponentName resolveSystemService(@NonNull Intent intent) {
        List<ResolveInfo> results = mContext.getPackageManager().queryIntentServices(intent, 0);
        if (results == null) {
            return null;
        }
        ComponentName comp = null;
        for (int i = 0; i < results.size(); i++) {
            ResolveInfo ri = results.get(i);
            if ((ri.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            ComponentName foundComp =
                    new ComponentName(
                            ri.serviceInfo.applicationInfo.packageName, ri.serviceInfo.name);
            if (comp != null) {
                throw new IllegalStateException(
                        "Multiple system services handle "
                                + intent
                                + ": "
                                + comp
                                + ", "
                                + foundComp);
            }
            comp = foundComp;
        }
        return comp;
    }

    int setBtHciSnoopLogMode(int mode) {
        final BluetoothProperties.snoop_log_mode_values snoopMode;

        switch (mode) {
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.DISABLED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FILTERED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FULL;
                break;
            default:
                Log.e(TAG, "setBtHciSnoopLogMode: Not a valid mode:" + mode);
                return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }
        try {
            BluetoothProperties.snoop_log_mode(snoopMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "setBtHciSnoopLogMode: Failed to set mode to " + mode + ": " + e);
            return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    int getBtHciSnoopLogMode() {
        BluetoothProperties.snoop_log_mode_values mode =
                BluetoothProperties.snoop_log_mode()
                        .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED);
        if (mode == BluetoothProperties.snoop_log_mode_values.FILTERED) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED;
        } else if (mode == BluetoothProperties.snoop_log_mode_values.FULL) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL;
        }
        return BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED;
    }

    /**
     * Check if BLE is supported by this platform
     *
     * @param context current device context
     * @return true if BLE is supported, false otherwise
     */
    private static boolean isBleSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /**
     * Check if this is an automotive device
     *
     * @param context current device context
     * @return true if this Android device is an automotive device, false otherwise
     */
    private static boolean isAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Check if this is a watch device
     *
     * @param context current device context
     * @return true if this Android device is a watch device, false otherwise
     */
    private static boolean isWatch(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /**
     * Check if this is a TV device
     *
     * @param context current device context
     * @return true if this Android device is a TV device, false otherwise
     */
    private static boolean isTv(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
