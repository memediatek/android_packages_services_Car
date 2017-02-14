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
 * limitations under the License.
 */

package com.android.car;

import android.annotation.Nullable;
import android.car.annotation.FutureFeature;
import android.car.hardware.CarDiagnosticEvent;
import android.car.hardware.CarDiagnosticManager;
import android.car.hardware.ICarDiagnostic;
import android.car.hardware.ICarDiagnosticEventListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import com.android.car.Listeners.ClientWithRate;
import com.android.car.hal.DiagnosticHalService;
import com.android.internal.annotations.GuardedBy;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@FutureFeature
/** @hide */
public class CarDiagnosticService extends ICarDiagnostic.Stub
        implements CarServiceBase, DiagnosticHalService.DiagnosticListener {
    /** {@link #mDiagnosticLock} is not waited forever for handling disconnection */
    private static final long MAX_DIAGNOSTIC_LOCK_WAIT_MS = 1000;

    /** lock to access diagnostic structures */
    private final ReentrantLock mDiagnosticLock = new ReentrantLock();
    /** hold clients callback */
    @GuardedBy("mDiagnosticLock")
    private final LinkedList<DiagnosticClient> mClients = new LinkedList<>();

    /** key: diagnostic type. */
    @GuardedBy("mDiagnosticLock")
    private final HashMap<Integer, Listeners<DiagnosticClient>> mDiagnosticListeners =
        new HashMap<>();

    /** the latest live frame data. */
    @GuardedBy("mDiagnosticLock")
    private final LiveFrameRecord mLiveFrameDiagnosticRecord = new LiveFrameRecord(mDiagnosticLock);

    /** the latest freeze frame data (key: DTC) */
    @GuardedBy("mDiagnosticLock")
    private final FreezeFrameRecord mFreezeFrameDiagnosticRecords = new FreezeFrameRecord(
        mDiagnosticLock);

    private final DiagnosticHalService mDiagnosticHal;

    private final Context mContext;

    public CarDiagnosticService(Context context, DiagnosticHalService diagnosticHal) {
        mContext = context;
        mDiagnosticHal = diagnosticHal;
    }

    @Override
    public void init() {
        mDiagnosticLock.lock();
        try {
            mDiagnosticHal.setDiagnosticListener(this);
            setInitialLiveFrame();
            setInitialFreezeFrames();
        } finally {
            mDiagnosticLock.unlock();
        }
    }

    private CarDiagnosticEvent setInitialLiveFrame() {
        CarDiagnosticEvent liveFrame = setRecentmostLiveFrame(mDiagnosticHal.getCurrentLiveFrame());
        return liveFrame;
    }

    private void setInitialFreezeFrames() {
        for (long timestamp: mDiagnosticHal.getFreezeFrameTimestamps()) {
            setRecentmostFreezeFrame(mDiagnosticHal.getFreezeFrame(timestamp));
        }
    }

    private CarDiagnosticEvent setRecentmostLiveFrame(final CarDiagnosticEvent event) {
        return mLiveFrameDiagnosticRecord.update(Objects.requireNonNull(event).checkLiveFrame());
    }

    private CarDiagnosticEvent setRecentmostFreezeFrame(final CarDiagnosticEvent event) {
        return mFreezeFrameDiagnosticRecords.update(
                Objects.requireNonNull(event).checkFreezeFrame());
    }

    @Override
    public void release() {
        mDiagnosticLock.lock();
        try {
            mDiagnosticListeners.forEach(
                    (Integer frameType, Listeners diagnosticListeners) ->
                            diagnosticListeners.release());
            mDiagnosticListeners.clear();
            mLiveFrameDiagnosticRecord.disableIfNeeded();
            mFreezeFrameDiagnosticRecords.disableIfNeeded();
            mClients.clear();
        } finally {
            mDiagnosticLock.unlock();
        }
    }

    private void processDiagnosticData(List<CarDiagnosticEvent> events) {
        ArrayMap<CarDiagnosticService.DiagnosticClient, List<CarDiagnosticEvent>> eventsByClient =
                new ArrayMap<>();

        Listeners<DiagnosticClient> listeners = null;

        mDiagnosticLock.lock();
        for (CarDiagnosticEvent event : events) {
            if (event.isLiveFrame()) {
                // record recent-most live frame information
                setRecentmostLiveFrame(event);
                listeners = mDiagnosticListeners.get(CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE);
            } else if (event.isFreezeFrame()) {
                setRecentmostFreezeFrame(event);
                listeners = mDiagnosticListeners.get(CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE);
            } else {
                Log.w(
                        CarLog.TAG_DIAGNOSTIC,
                        String.format("received unknown diagnostic event: %s", event));
                continue;
            }

            if (null != listeners) {
                for (ClientWithRate<DiagnosticClient> clientWithRate : listeners.getClients()) {
                    DiagnosticClient client = clientWithRate.getClient();
                    List<CarDiagnosticEvent> clientEvents = eventsByClient.computeIfAbsent(client,
                            (DiagnosticClient diagnosticClient) -> new LinkedList<>());
                    clientEvents.add(event);
                }
            }
        }
        mDiagnosticLock.unlock();

        for (ArrayMap.Entry<CarDiagnosticService.DiagnosticClient, List<CarDiagnosticEvent>> entry :
                eventsByClient.entrySet()) {
            CarDiagnosticService.DiagnosticClient client = entry.getKey();
            List<CarDiagnosticEvent> clientEvents = entry.getValue();

            client.dispatchDiagnosticUpdate(clientEvents);
        }
    }

    /** Received diagnostic data from car. */
    @Override
    public void onDiagnosticEvents(List<CarDiagnosticEvent> events) {
        processDiagnosticData(events);
    }

    private List<CarDiagnosticEvent> getCachedEventsLocked(int frameType) {
        ArrayList<CarDiagnosticEvent> events = new ArrayList<>();
        switch (frameType) {
            case CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE:
                mLiveFrameDiagnosticRecord.lock();
                events.add(mLiveFrameDiagnosticRecord.getLastEvent());
                mLiveFrameDiagnosticRecord.unlock();
                break;
            case CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE:
                mFreezeFrameDiagnosticRecords.lock();
                mFreezeFrameDiagnosticRecords.getEvents().forEach(events::add);
                mFreezeFrameDiagnosticRecords.unlock();
                break;
            default: break;
        }
        return events;
    }

    private void assertPermission(int frameType) {
        if (Binder.getCallingUid() != Process.myUid()) {
            switch (getDiagnosticPermission(frameType)) {
                case PackageManager.PERMISSION_GRANTED:
                    break;
                default:
                    throw new SecurityException(
                        "client does not have permission:"
                            + getPermissionName(frameType)
                            + " pid:"
                            + Binder.getCallingPid()
                            + " uid:"
                            + Binder.getCallingUid());
            }
        }
    }

    @Override
    public boolean registerOrUpdateDiagnosticListener(int frameType, int rate,
                ICarDiagnosticEventListener listener) {
        boolean shouldStartDiagnostics = false;
        CarDiagnosticService.DiagnosticClient diagnosticClient = null;
        Integer oldRate = null;
        Listeners<DiagnosticClient> diagnosticListeners = null;
        mDiagnosticLock.lock();
        try {
            assertPermission(frameType);
            diagnosticClient = findDiagnosticClientLocked(listener);
            Listeners.ClientWithRate<DiagnosticClient> diagnosticClientWithRate = null;
            if (diagnosticClient == null) {
                diagnosticClient = new DiagnosticClient(listener);
                try {
                    listener.asBinder().linkToDeath(diagnosticClient, 0);
                } catch (RemoteException e) {
                    Log.w(
                            CarLog.TAG_DIAGNOSTIC,
                            String.format(
                                    "received RemoteException trying to register listener for %s",
                                    frameType));
                    return false;
                }
                mClients.add(diagnosticClient);
            }
            // If we have a cached event for this diagnostic, send the event.
            diagnosticClient.dispatchDiagnosticUpdate(getCachedEventsLocked(frameType));
            diagnosticListeners = mDiagnosticListeners.get(frameType);
            if (diagnosticListeners == null) {
                diagnosticListeners = new Listeners<>(rate);
                mDiagnosticListeners.put(frameType, diagnosticListeners);
                shouldStartDiagnostics = true;
            } else {
                oldRate = diagnosticListeners.getRate();
                diagnosticClientWithRate =
                        diagnosticListeners.findClientWithRate(diagnosticClient);
            }
            if (diagnosticClientWithRate == null) {
                diagnosticClientWithRate =
                        new ClientWithRate<>(diagnosticClient, rate);
                diagnosticListeners.addClientWithRate(diagnosticClientWithRate);
            } else {
                diagnosticClientWithRate.setRate(rate);
            }
            if (diagnosticListeners.getRate() > rate) {
                diagnosticListeners.setRate(rate);
                shouldStartDiagnostics = true;
            }
            diagnosticClient.addDiagnostic(frameType);
        } finally {
            mDiagnosticLock.unlock();
        }
        Log.i(
                CarLog.TAG_DIAGNOSTIC,
                String.format(
                        "shouldStartDiagnostics = %s for %s at rate %d",
                        shouldStartDiagnostics, frameType, rate));
        // start diagnostic outside lock as it can take time.
        if (shouldStartDiagnostics) {
            if (!startDiagnostic(frameType, rate)) {
                // failed. so remove from active diagnostic list.
                Log.w(CarLog.TAG_DIAGNOSTIC, "startDiagnostic failed");
                mDiagnosticLock.lock();
                try {
                    diagnosticClient.removeDiagnostic(frameType);
                    if (oldRate != null) {
                        diagnosticListeners.setRate(oldRate);
                    } else {
                        mDiagnosticListeners.remove(frameType);
                    }
                } finally {
                    mDiagnosticLock.unlock();
                }
                return false;
            }
        }
        return true;
    }

    //TODO(egranata): handle permissions correctly
    private int getDiagnosticPermission(int frameType) {
        String permission = getPermissionName(frameType);
        int result = PackageManager.PERMISSION_GRANTED;
        if (permission != null) {
            return mContext.checkCallingOrSelfPermission(permission);
        }
        // If no permission is required, return granted.
        return result;
    }

    private String getPermissionName(int frameType) {
        return null;
    }

    private boolean startDiagnostic(int frameType, int rate) {
        Log.i(CarLog.TAG_DIAGNOSTIC, String.format("starting diagnostic %s at rate %d",
                frameType, rate));
        DiagnosticHalService diagnosticHal = getDiagnosticHal();
        if (diagnosticHal != null) {
            if (!diagnosticHal.isReady()) {
                Log.w(CarLog.TAG_DIAGNOSTIC, "diagnosticHal not ready");
                return false;
            }
            switch (frameType) {
                case CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE:
                    if (mLiveFrameDiagnosticRecord.isEnabled()) {
                        return true;
                    }
                    if (diagnosticHal.requestSensorStart(CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE,
                            rate)) {
                        mLiveFrameDiagnosticRecord.enable();
                        return true;
                    }
                    break;
                case CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE:
                    if (mFreezeFrameDiagnosticRecords.isEnabled()) {
                        return true;
                    }
                    if (diagnosticHal.requestSensorStart(CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE,
                            rate)) {
                        mFreezeFrameDiagnosticRecords.enable();
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    @Override
    public void unregisterDiagnosticListener(
            int frameType, ICarDiagnosticEventListener listener) {
        boolean shouldStopDiagnostic = false;
        boolean shouldRestartDiagnostic = false;
        int newRate = 0;
        mDiagnosticLock.lock();
        try {
            DiagnosticClient diagnosticClient = findDiagnosticClientLocked(listener);
            if (diagnosticClient == null) {
                Log.i(
                        CarLog.TAG_DIAGNOSTIC,
                        String.format(
                                "trying to unregister diagnostic client %s for %s which is not registered",
                                listener, frameType));
                // never registered or already unregistered.
                return;
            }
            diagnosticClient.removeDiagnostic(frameType);
            if (diagnosticClient.getNumberOfActiveDiagnostic() == 0) {
                diagnosticClient.release();
                mClients.remove(diagnosticClient);
            }
            Listeners<DiagnosticClient> diagnosticListeners = mDiagnosticListeners.get(frameType);
            if (diagnosticListeners == null) {
                // diagnostic not active
                return;
            }
            ClientWithRate<DiagnosticClient> clientWithRate =
                    diagnosticListeners.findClientWithRate(diagnosticClient);
            if (clientWithRate == null) {
                return;
            }
            diagnosticListeners.removeClientWithRate(clientWithRate);
            if (diagnosticListeners.getNumberOfClients() == 0) {
                shouldStopDiagnostic = true;
                mDiagnosticListeners.remove(frameType);
            } else if (diagnosticListeners.updateRate()) { // rate changed
                newRate = diagnosticListeners.getRate();
                shouldRestartDiagnostic = true;
            }
        } finally {
            mDiagnosticLock.unlock();
        }
        Log.i(
                CarLog.TAG_DIAGNOSTIC,
                String.format(
                        "shouldStopDiagnostic = %s, shouldRestartDiagnostic = %s for type %s",
                        shouldStopDiagnostic, shouldRestartDiagnostic, frameType));
        if (shouldStopDiagnostic) {
            stopDiagnostic(frameType);
        } else if (shouldRestartDiagnostic) {
            startDiagnostic(frameType, newRate);
        }
    }

    private void stopDiagnostic(int frameType) {
        DiagnosticHalService diagnosticHal = getDiagnosticHal();
        if (diagnosticHal == null || !diagnosticHal.isReady()) {
            Log.w(CarLog.TAG_DIAGNOSTIC, "diagnosticHal not ready");
            return;
        }
        switch (frameType) {
            case CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE:
                if (mLiveFrameDiagnosticRecord.disableIfNeeded())
                    diagnosticHal.requestSensorStop(CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE);
                break;
            case CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE:
                if (mFreezeFrameDiagnosticRecords.disableIfNeeded())
                    diagnosticHal.requestSensorStop(CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE);
                break;
        }
    }

    private DiagnosticHalService getDiagnosticHal() {
        return mDiagnosticHal;
    }

    // ICarDiagnostic implementations

    @Override
    public CarDiagnosticEvent getLatestLiveFrame() {
        mLiveFrameDiagnosticRecord.lock();
        CarDiagnosticEvent liveFrame = mLiveFrameDiagnosticRecord.getLastEvent();
        mLiveFrameDiagnosticRecord.unlock();
        return liveFrame;
    }

    @Override
    public long[] getFreezeFrameTimestamps() {
        mFreezeFrameDiagnosticRecords.lock();
        long[] timestamps = mFreezeFrameDiagnosticRecords.getFreezeFrameTimestamps();
        mFreezeFrameDiagnosticRecords.unlock();
        return timestamps;
    }

    @Override
    @Nullable
    public CarDiagnosticEvent getFreezeFrame(long timestamp) {
        mFreezeFrameDiagnosticRecords.lock();
        CarDiagnosticEvent freezeFrame = mFreezeFrameDiagnosticRecords.getEvent(timestamp);
        mFreezeFrameDiagnosticRecords.unlock();
        return freezeFrame;
    }

    @Override
    public boolean clearFreezeFrames(long... timestamps) {
        mFreezeFrameDiagnosticRecords.lock();
        mDiagnosticHal.clearFreezeFrames(timestamps);
        mFreezeFrameDiagnosticRecords.clearEvents();
        mFreezeFrameDiagnosticRecords.unlock();
        return true;
    }

    /**
     * Find DiagnosticClient from client list and return it. This should be called with mClients
     * locked.
     *
     * @param listener
     * @return null if not found.
     */
    private CarDiagnosticService.DiagnosticClient findDiagnosticClientLocked(
            ICarDiagnosticEventListener listener) {
        IBinder binder = listener.asBinder();
        for (DiagnosticClient diagnosticClient : mClients) {
            if (diagnosticClient.isHoldingListenerBinder(binder)) {
                return diagnosticClient;
            }
        }
        return null;
    }

    private void removeClient(DiagnosticClient diagnosticClient) {
        mDiagnosticLock.lock();
        try {
            for (int diagnostic : diagnosticClient.getDiagnosticArray()) {
                unregisterDiagnosticListener(
                        diagnostic, diagnosticClient.getICarDiagnosticEventListener());
            }
            mClients.remove(diagnosticClient);
        } finally {
            mDiagnosticLock.unlock();
        }
    }

    private class DiagnosticDispatchHandler extends Handler {
        private static final long DIAGNOSTIC_DISPATCH_MIN_INTERVAL_MS = 16; // over 60Hz

        private static final int MSG_DIAGNOSTIC_DATA = 0;

        private long mLastDiagnosticDispatchTime = -1;
        private int mFreeListIndex = 0;
        private final LinkedList<CarDiagnosticEvent>[] mDiagnosticDataList = new LinkedList[2];

        private DiagnosticDispatchHandler(Looper looper) {
            super(looper);
            for (int i = 0; i < mDiagnosticDataList.length; i++) {
                mDiagnosticDataList[i] = new LinkedList<CarDiagnosticEvent>();
            }
        }

        private synchronized void handleDiagnosticEvents(List<CarDiagnosticEvent> data) {
            LinkedList<CarDiagnosticEvent> list = mDiagnosticDataList[mFreeListIndex];
            list.addAll(data);
            requestDispatchLocked();
        }

        private synchronized void handleDiagnosticEvent(CarDiagnosticEvent event) {
            LinkedList<CarDiagnosticEvent> list = mDiagnosticDataList[mFreeListIndex];
            list.add(event);
            requestDispatchLocked();
        }

        private void requestDispatchLocked() {
            Message msg = obtainMessage(MSG_DIAGNOSTIC_DATA);
            long now = SystemClock.uptimeMillis();
            long delta = now - mLastDiagnosticDispatchTime;
            if (delta > DIAGNOSTIC_DISPATCH_MIN_INTERVAL_MS) {
                sendMessage(msg);
            } else {
                sendMessageDelayed(msg, DIAGNOSTIC_DISPATCH_MIN_INTERVAL_MS - delta);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DIAGNOSTIC_DATA:
                    doHandleDiagnosticData();
                    break;
                default:
                    break;
            }
        }

        private void doHandleDiagnosticData() {
            List<CarDiagnosticEvent> listToDispatch = null;
            synchronized (this) {
                mLastDiagnosticDispatchTime = SystemClock.uptimeMillis();
                int nonFreeListIndex = mFreeListIndex ^ 0x1;
                List<CarDiagnosticEvent> nonFreeList = mDiagnosticDataList[nonFreeListIndex];
                List<CarDiagnosticEvent> freeList = mDiagnosticDataList[mFreeListIndex];
                if (nonFreeList.size() > 0) {
                    // copy again, but this should not be normal case
                    nonFreeList.addAll(freeList);
                    listToDispatch = nonFreeList;
                    freeList.clear();
                } else if (freeList.size() > 0) {
                    listToDispatch = freeList;
                    mFreeListIndex = nonFreeListIndex;
                }
            }
            // leave this part outside lock so that time-taking dispatching can be done without
            // blocking diagnostic event notification.
            if (listToDispatch != null) {
                processDiagnosticData(listToDispatch);
                listToDispatch.clear();
            }
        }
    }

    /** internal instance for pending client request */
    private class DiagnosticClient implements Listeners.IListener {
        /** callback for diagnostic events */
        private final ICarDiagnosticEventListener mListener;

        private final Set<Integer> mActiveDiagnostics = new HashSet<>();

        /** when false, it is already released */
        private volatile boolean mActive = true;

        DiagnosticClient(ICarDiagnosticEventListener listener) {
            this.mListener = listener;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof CarDiagnosticService.DiagnosticClient
                    && mListener.asBinder()
                            == ((CarDiagnosticService.DiagnosticClient) o).mListener.asBinder()) {
                return true;
            }
            return false;
        }

        boolean isHoldingListenerBinder(IBinder listenerBinder) {
            return mListener.asBinder() == listenerBinder;
        }

        void addDiagnostic(int frameType) {
            mActiveDiagnostics.add(frameType);
        }

        void removeDiagnostic(int frameType) {
            mActiveDiagnostics.remove(frameType);
        }

        int getNumberOfActiveDiagnostic() {
            return mActiveDiagnostics.size();
        }

        int[] getDiagnosticArray() {
            return mActiveDiagnostics.stream().mapToInt(Integer::intValue).toArray();
        }

        ICarDiagnosticEventListener getICarDiagnosticEventListener() {
            return mListener;
        }

        /** Client dead. should remove all diagnostic requests from client */
        @Override
        public void binderDied() {
            mListener.asBinder().unlinkToDeath(this, 0);
            removeClient(this);
        }

        void dispatchDiagnosticUpdate(List<CarDiagnosticEvent> events) {
            if (events.size() == 0) {
                return;
            }
            if (mActive) {
                try {
                    mListener.onDiagnosticEvents(events);
                } catch (RemoteException e) {
                    //ignore. crash will be handled by death handler
                }
            } else {
            }
        }

        @Override
        public void release() {
            if (mActive) {
                mListener.asBinder().unlinkToDeath(this, 0);
                mActiveDiagnostics.clear();
                mActive = false;
            }
        }
    }

    private static abstract class DiagnosticRecord {
        private final ReentrantLock mLock;
        protected boolean mEnabled = false;

        DiagnosticRecord(ReentrantLock lock) {
            mLock = lock;
        }

        void lock() {
            mLock.lock();
        }

        void unlock() {
            mLock.unlock();
        }

        boolean isEnabled() {
            return mEnabled;
        }

        void enable() {
            mEnabled = true;
        }

        abstract boolean disableIfNeeded();
        abstract CarDiagnosticEvent update(CarDiagnosticEvent newEvent);
    }

    private static class LiveFrameRecord extends DiagnosticRecord {
        /** Store the most recent live-frame. */
        CarDiagnosticEvent mLastEvent = null;

        LiveFrameRecord(ReentrantLock lock) {
            super(lock);
        }

        @Override
        boolean disableIfNeeded() {
            if (!mEnabled) return false;
            mEnabled = false;
            mLastEvent = null;
            return true;
        }

        @Override
        CarDiagnosticEvent update(CarDiagnosticEvent newEvent) {
            newEvent = Objects.requireNonNull(newEvent);
            if((null == mLastEvent) || mLastEvent.isEarlierThan(newEvent))
                mLastEvent = newEvent;
            return mLastEvent;
        }

        CarDiagnosticEvent getLastEvent() {
            return mLastEvent;
        }
    }

    private static class FreezeFrameRecord extends DiagnosticRecord {
        /** Store the timestamp --> freeze frame mapping. */
        HashMap<Long, CarDiagnosticEvent> mEvents = new HashMap<>();

        FreezeFrameRecord(ReentrantLock lock) {
            super(lock);
        }

        @Override
        boolean disableIfNeeded() {
            if (!mEnabled) return false;
            mEnabled = false;
            clearEvents();
            return true;
        }

        void clearEvents() {
            mEvents.clear();
        }

        @Override
        CarDiagnosticEvent update(CarDiagnosticEvent newEvent) {
            mEvents.put(newEvent.timestamp, newEvent);
            return newEvent;
        }

        long[] getFreezeFrameTimestamps() {
            return mEvents.keySet().stream().mapToLong(Long::longValue).toArray();
        }

        CarDiagnosticEvent getEvent(long timestamp) {
            return mEvents.get(timestamp);
        }

        Iterable<CarDiagnosticEvent> getEvents() {
            return mEvents.values();
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarDiagnosticService*");
        writer.println("**last events for diagnostics**");
        if (null != mLiveFrameDiagnosticRecord.getLastEvent()) {
            writer.println("last live frame event: ");
            writer.println(mLiveFrameDiagnosticRecord.getLastEvent());
        }
        writer.println("freeze frame events: ");
        mFreezeFrameDiagnosticRecords.getEvents().forEach(writer::println);
        writer.println("**clients**");
        try {
            for (DiagnosticClient client : mClients) {
                if (client != null) {
                    try {
                        writer.println(
                                "binder:"
                                        + client.mListener
                                        + " active diagnostics:"
                                        + Arrays.toString(client.getDiagnosticArray()));
                    } catch (ConcurrentModificationException e) {
                        writer.println("concurrent modification happened");
                    }
                } else {
                    writer.println("null client");
                }
            }
        } catch (ConcurrentModificationException e) {
            writer.println("concurrent modification happened");
        }
        writer.println("**diagnostic listeners**");
        try {
            for (int diagnostic : mDiagnosticListeners.keySet()) {
                Listeners diagnosticListeners = mDiagnosticListeners.get(diagnostic);
                if (diagnosticListeners != null) {
                    writer.println(
                            " Diagnostic:"
                                    + diagnostic
                                    + " num client:"
                                    + diagnosticListeners.getNumberOfClients()
                                    + " rate:"
                                    + diagnosticListeners.getRate());
                }
            }
        } catch (ConcurrentModificationException e) {
            writer.println("concurrent modification happened");
        }
    }
}