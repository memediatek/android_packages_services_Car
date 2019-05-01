/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.util.Log;

/**
 * A helper class to connect to a single {@link MediaBrowser}. Connecting to a new one
 * automatically disconnects the previous browser. Changes of the currently connected browser are
 * sent via {@link MediaBrowserConnector.Callback}.
 */
public class MediaBrowserConnector {

    private static final String TAG = "MediaBrowserConnector";

    /** The callback to receive the currently connected {@link MediaBrowser}. */
    public interface Callback {
        /** When disconnecting, the given browser will be null. */
        void onConnectedBrowserChanged(@Nullable MediaBrowser browser);
    }

    private final Context mContext;
    private final Callback mCallback;

    @Nullable private ComponentName mBrowseService;
    @Nullable private MediaBrowser mBrowser;

    /**
     * Create a new MediaBrowserConnector.
     *
     * @param context The Context with which to build MediaBrowsers.
     */
    public MediaBrowserConnector(@NonNull Context context, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    /** Counter so callbacks from obsolete connections can be ignored. */
    private int mBrowserConnectionCallbackCounter = 0;

    private class BrowserConnectionCallback extends MediaBrowser.ConnectionCallback {

        private final int mSequenceNumber = ++mBrowserConnectionCallbackCounter;
        private final String mCallbackPackage = mBrowseService.getPackageName();

        private boolean isValidCall(String method) {
            if (mSequenceNumber != mBrowserConnectionCallbackCounter) {
                Log.e(TAG, "Ignoring callback " + method + " for " + mCallbackPackage + " seq: "
                        + mSequenceNumber + " current: " + mBrowserConnectionCallbackCounter
                        + " current: " + mBrowseService.getPackageName());
                return false;
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, method + " " + mBrowseService.getPackageName());
            }
            return true;
        }

        @Override
        public void onConnected() {
            if (isValidCall("onConnected")) {
                mCallback.onConnectedBrowserChanged(mBrowser);
            }
        }

        @Override
        public void onConnectionFailed() {
            if (isValidCall("onConnectionFailed")) {
                mCallback.onConnectedBrowserChanged(null);
            }
        }

        @Override
        public void onConnectionSuspended() {
            if (isValidCall("onConnectionSuspended")) {
                mCallback.onConnectedBrowserChanged(null);
            }
        }
    }

    /**
     * Creates and connects a new {@link MediaBrowser} if the given {@link ComponentName}
     * isn't null. If needed, the previous browser is disconnected.
     * @param browseService the ComponentName of the media browser service.
     * @see MediaBrowser#MediaBrowser(Context, ComponentName,
     * MediaBrowser.ConnectionCallback, android.os.Bundle)
     */
    public void connectTo(@Nullable ComponentName browseService) {
        if (mBrowser != null && mBrowser.isConnected()) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Disconnecting: " + mBrowseService.getPackageName());
            }
            mCallback.onConnectedBrowserChanged(null);
            mBrowser.disconnect();
        }

        mBrowseService = browseService;
        if (mBrowseService != null) {
            mBrowser = createMediaBrowser(mBrowseService, new BrowserConnectionCallback());
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Connecting to: " + mBrowseService.getPackageName());
            }
            try {
                mBrowser.connect();
            } catch (IllegalStateException ex) {
                // Is this comment still valid ?
                // Ignore: MediaBrowse could be in an intermediate state (not connected, but not
                // disconnected either.). In this situation, trying to connect again can throw
                // this exception, but there is no way to know without trying.
                Log.e(TAG, "Connection exception: " + ex);
            }
        } else {
            mBrowser = null;
        }
    }

    // Override for testing.
    @NonNull
    protected MediaBrowser createMediaBrowser(@NonNull ComponentName browseService,
            @NonNull MediaBrowser.ConnectionCallback callback) {
        return new MediaBrowser(mContext, browseService, callback, null);
    }
}