/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MMTelFeature;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;
import android.util.SparseArray;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsServiceController;
import com.android.ims.internal.IImsServiceFeatureListener;
import com.android.ims.internal.IImsUt;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Main ImsService implementation, which binds via the Telephony ImsResolver. Services that extend
 * ImsService must register the service in their AndroidManifest to be detected by the framework.
 * First, the application must declare that they use the "android.permission.BIND_IMS_SERVICE"
 * permission. Then, the ImsService definition in the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgImsService"
 *     android:permission="android.permission.BIND_IMS_SERVICE" >
 *     <!-- Apps must declare which features they support as metadata. The different categories are
 *     defined below. In this example, the RCS_FEATURE feature is supported. -->
 *     <meta-data android:name="android.telephony.ims.RCS_FEATURE" android:value="true" />
 *     <intent-filter>
 *         <action android:name="android.telephony.ims.ImsService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the ImsService you have defined in your manifest
 * if you are either:
 * 1) Defined as the default ImsService for the device in the device overlay using
 *    "config_ims_package".
 * 2) Defined as a Carrier Provided ImsService in the Carrier Configuration using
 *    {@link CarrierConfigManager#KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING}.
 *
 * The features that are currently supported in an ImsService are:
 * - RCS_FEATURE: This ImsService implements the {@link RcsFeature} class.
 * - MMTEL_FEATURE: This ImsService implements the {@link MMTelFeature} class.
 * - EMERGENCY_MMTEL_FEATURE: This ImsService implements the {@link MMTelFeature} class and will be
 *   available to place emergency calls at all times. This MUST be implemented by the default
 *   ImsService provided in the device overlay.
 *
 * @hide
 */
public abstract class ImsService extends ImsServiceBase {

    private static final String LOG_TAG = "ImsService";

    /**
     * The intent that must be defined as an intent-filter in the AndroidManifest of the ImsService.
     */
    public static final String SERVICE_INTERFACE = "android.telephony.ims.ImsService";

    // A map of slot Id -> Set of features corresponding to that slot.
    private final SparseArray<SparseArray<ImsFeature>> mFeatures = new SparseArray<>();

    // Implements all supported features as a flat interface.
    protected final IBinder mImsServiceController = new IImsServiceController.Stub() {

        @Override
        public void createImsFeature(int slotId, int feature, IImsFeatureStatusCallback c)
                throws RemoteException {
            synchronized (mFeatures) {
                onCreateImsFeatureInternal(slotId, feature, c);
            }
        }

        @Override
        public void removeImsFeature(int slotId, int feature) throws RemoteException {
            synchronized (mFeatures) {
                onRemoveImsFeatureInternal(slotId, feature);
            }
        }

        @Override
        public int startSession(int slotId, int featureType, PendingIntent incomingCallIntent,
                IImsRegistrationListener listener) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.startSession(incomingCallIntent, listener);
                }
            }
            return 0;
        }

        @Override
        public void endSession(int slotId, int featureType, int sessionId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.endSession(sessionId);
                }
            }
        }

        @Override
        public boolean isConnected(int slotId, int featureType, int sessionId, int callSessionType,
                int callType) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.isConnected(sessionId, callSessionType, callType);
                }
            }
            return false;
        }

        @Override
        public boolean isOpened(int slotId, int featureType, int sessionId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.isOpened(sessionId);
                }
            }
            return false;
        }

        @Override
        public int getFeatureStatus(int slotId, int featureType) throws RemoteException {
            int status = ImsFeature.STATE_NOT_AVAILABLE;
            synchronized (mFeatures) {
                SparseArray<ImsFeature> featureMap = mFeatures.get(slotId);
                if (featureMap != null) {
                    ImsFeature feature = getImsFeatureFromType(featureMap, featureType);
                    if (feature != null) {
                        status = feature.getFeatureState();
                    }
                }
            }
            return status;
        }

        @Override
        public void addRegistrationListener(int slotId, int featureType, int sessionId,
                IImsRegistrationListener listener) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.addRegistrationListener(sessionId, listener);
                }
            }
        }

        @Override
        public void removeRegistrationListener(int slotId, int featureType, int sessionId,
                IImsRegistrationListener listener) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.removeRegistrationListener(sessionId, listener);
                }
            }
        }

        @Override
        public ImsCallProfile createCallProfile(int slotId, int featureType, int sessionId,
                int callSessionType, int callType) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.createCallProfile(sessionId, callSessionType,  callType);
                }
            }
            return null;
        }

        @Override
        public IImsCallSession createCallSession(int slotId, int featureType, int sessionId,
                ImsCallProfile profile, IImsCallSessionListener listener) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.createCallSession(sessionId, profile, listener);
                }
            }
            return null;
        }

        @Override
        public IImsCallSession getPendingCallSession(int slotId, int featureType, int sessionId,
                String callId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.getPendingCallSession(sessionId, callId);
                }
            }
            return null;
        }

        @Override
        public IImsUt getUtInterface(int slotId, int featureType, int sessionId)
                throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.getUtInterface(sessionId);
                }
            }
            return null;
        }

        @Override
        public IImsConfig getConfigInterface(int slotId, int featureType, int sessionId)
                throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.getConfigInterface(sessionId);
                }
            }
            return null;
        }

        @Override
        public void turnOnIms(int slotId, int featureType, int sessionId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.turnOnIms(sessionId);
                }
            }
        }

        @Override
        public void turnOffIms(int slotId, int featureType, int sessionId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.turnOffIms(sessionId);
                }
            }
        }

        @Override
        public IImsEcbm getEcbmInterface(int slotId, int featureType, int sessionId)
                throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.getEcbmInterface(sessionId);
                }
            }
            return null;
        }

        @Override
        public void setUiTTYMode(int slotId, int featureType, int sessionId, int uiTtyMode,
                Message onComplete) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    feature.setUiTTYMode(sessionId, uiTtyMode, onComplete);
                }
            }
        }

        @Override
        public IImsMultiEndpoint getMultiEndpointInterface(int slotId, int featureType,
                int sessionId) throws RemoteException {
            synchronized (mFeatures) {
                MMTelFeature feature = resolveMMTelFeature(slotId, featureType);
                if (feature != null) {
                    return feature.getMultiEndpointInterface(sessionId);
                }
            }
            return null;
        }

    };

    @Override
    public IBinder onBind(Intent intent) {
        if(SERVICE_INTERFACE.equals(intent.getAction())) {
            return mImsServiceController;
        }
        return null;
    }

    /**
     * Called from the ImsResolver to create the requested ImsFeature, as defined by the slot and
     * featureType
     * @param slotId An integer representing which SIM slot the ImsFeature is assigned to.
     * @param featureType An integer representing the type of ImsFeature being created. This is
     * defined in {@link ImsFeature}.
     */
    // Be sure to lock on mFeatures before accessing this method
    private void onCreateImsFeatureInternal(int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        SparseArray<ImsFeature> featureMap = mFeatures.get(slotId);
        if (featureMap == null) {
            featureMap = new SparseArray<>();
            mFeatures.put(slotId, featureMap);
        }
        ImsFeature f = makeImsFeature(slotId, featureType);
        if (f != null) {
            f.setImsFeatureStatusCallback(c);
            featureMap.put(featureType, f);
        }

    }
    /**
     * Called from the ImsResolver to remove an existing ImsFeature, as defined by the slot and
     * featureType.
     * @param slotId An integer representing which SIM slot the ImsFeature is assigned to.
     * @param featureType An integer representing the type of ImsFeature being removed. This is
     * defined in {@link ImsFeature}.
     */
    // Be sure to lock on mFeatures before accessing this method
    private void onRemoveImsFeatureInternal(int slotId, int featureType) {
        SparseArray<ImsFeature> featureMap = mFeatures.get(slotId);
        if (featureMap == null) {
            return;
        }

        ImsFeature featureToRemove = getImsFeatureFromType(featureMap, featureType);
        if (featureToRemove != null) {
            featureMap.remove(featureType);
            featureToRemove.notifyFeatureRemoved(slotId);
            // Remove reference to Binder
            featureToRemove.setImsFeatureStatusCallback(null);
        }
    }

    // Be sure to lock on mFeatures before accessing this method
    private MMTelFeature resolveMMTelFeature(int slotId, int featureType) {
        SparseArray<ImsFeature> features = getImsFeatureMap(slotId);
        MMTelFeature feature = null;
        if (features != null) {
            feature = resolveImsFeature(features, featureType, MMTelFeature.class);
        }
        return feature;
    }

    // Be sure to lock on mFeatures before accessing this method
    private <T extends ImsFeature> T resolveImsFeature(SparseArray<ImsFeature> set, int featureType,
            Class<T> className) {
        ImsFeature feature = getImsFeatureFromType(set, featureType);
        if (feature == null) {
            return null;
        }
        try {
            return className.cast(feature);
        } catch (ClassCastException e)
        {
            Log.e(LOG_TAG, "Can not cast ImsFeature! Exception: " + e.getMessage());
        }
        return null;
    }

    @VisibleForTesting
    // Be sure to lock on mFeatures before accessing this method
    public SparseArray<ImsFeature> getImsFeatureMap(int slotId) {
        return mFeatures.get(slotId);
    }

    @VisibleForTesting
    // Be sure to lock on mFeatures before accessing this method
    public ImsFeature getImsFeatureFromType(SparseArray<ImsFeature> set, int featureType) {
        return set.get(featureType);
    }

    private ImsFeature makeImsFeature(int slotId, int feature) {
        switch (feature) {
            case ImsFeature.EMERGENCY_MMTEL: {
                return onCreateEmergencyMMTelImsFeature(slotId);
            }
            case ImsFeature.MMTEL: {
                return onCreateMMTelImsFeature(slotId);
            }
            case ImsFeature.RCS: {
                return onCreateRcsFeature(slotId);
            }
        }
        // Tried to create feature that is not defined.
        return null;
    }

    /**
     * @return An implementation of MMTelFeature that will be used by the system for MMTel
     * functionality. Must be able to handle emergency calls at any time as well.
     */
    public abstract MMTelFeature onCreateEmergencyMMTelImsFeature(int slotId);

    /**
     * @return An implementation of MMTelFeature that will be used by the system for MMTel
     * functionality.
     */
    public abstract MMTelFeature onCreateMMTelImsFeature(int slotId);

    /**
     * @return An implementation of RcsFeature that will be used by the system for RCS.
     */
    public abstract RcsFeature onCreateRcsFeature(int slotId);
}
