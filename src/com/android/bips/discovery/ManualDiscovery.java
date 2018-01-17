/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2016 Mopria Alliance, Inc.
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

package com.android.bips.discovery;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.bips.BuiltInPrintService;
import com.android.bips.ipp.CapabilitiesCache;
import com.android.bips.jni.LocalPrinterCapabilities;
import com.android.bips.util.WifiMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Manage a list of printers manually added by the user.
 */
public class ManualDiscovery extends SavedDiscovery {
    private static final String TAG = ManualDiscovery.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Likely paths at which a print service may be found
    private static final Uri[] IPP_URIS = {Uri.parse("ipp://path:631/ipp/print"),
            Uri.parse("ipp://path:80/ipp/print"), Uri.parse("ipp://path:631/ipp/printer"),
            Uri.parse("ipp://path:631/ipp"), Uri.parse("ipp://path:631/")};

    private WifiMonitor mWifiMonitor;
    private CapabilitiesCache mCapabilitiesCache;

    public ManualDiscovery(BuiltInPrintService printService) {
        super(printService);
        mCapabilitiesCache = getPrintService().getCapabilitiesCache();
    }

    @Override
    void onStart() {
        if (DEBUG) Log.d(TAG, "onStart");

        // Upon any network change scan for all manually added printers
        mWifiMonitor = new WifiMonitor(getPrintService(), isConnected -> {
            if (isConnected) {
                for (DiscoveredPrinter printer : getSavedPrinters()) {
                    mCapabilitiesCache.request(printer, false, capabilities -> {
                        if (capabilities != null) {
                            printerFound(printer);
                        }
                    });
                }
            } else {
                allPrintersLost();
            }
        });
    }

    @Override
    void onStop() {
        if (DEBUG) Log.d(TAG, "onStop");
        mWifiMonitor.close();
        allPrintersLost();
    }

    /**
     * Asynchronously attempt to add a new manual printer, calling back with success
     */
    public void addManualPrinter(String hostname, PrinterAddCallback callback) {
        if (DEBUG) Log.d(TAG, "addManualPrinter " + hostname);
        new CapabilitiesFinder(hostname, callback);
    }

    /** Used to convey response to {@link #addManualPrinter} */
    public interface PrinterAddCallback {
        /**
         * The requested manual printer was found.
         *
         * @param printer   information about the discovered printer
         * @param supported true if the printer is supported (and was therefore added), or false
         *                  if the printer was found but is not supported (and was therefore not
         *                  added)
         */
        void onFound(DiscoveredPrinter printer, boolean supported);

        /**
         * The requested manual printer was not found.
         */
        void onNotFound();
    }

    /**
     * Search common printer paths for a successful response
     */
    private class CapabilitiesFinder {
        private final PrinterAddCallback mFinalCallback;
        private final List<CapabilitiesCache.OnLocalPrinterCapabilities> mRequests =
                new ArrayList<>();

        /**
         * Constructs a new finder
         *
         * @param hostname Hostname to crawl for IPP endpoints
         * @param callback Callback to issue when the first successful response arrives, or
         *                 when all responses have failed.
         */
        CapabilitiesFinder(String hostname, PrinterAddCallback callback) {
            mFinalCallback = callback;

            for (Uri uri : IPP_URIS) {
                Uri printerPath = uri.buildUpon().encodedAuthority(hostname + ":" + uri.getPort())
                        .build();
                CapabilitiesCache.OnLocalPrinterCapabilities capabilitiesCallback =
                        new CapabilitiesCache.OnLocalPrinterCapabilities() {
                            @Override
                            public void onCapabilities(LocalPrinterCapabilities capabilities) {
                                mRequests.remove(this);
                                handleCapabilities(printerPath, capabilities);
                            }
                        };
                mRequests.add(capabilitiesCallback);

                mCapabilitiesCache.request(new DiscoveredPrinter(null, "", printerPath, null),
                        true, capabilitiesCallback);
            }
        }

        /** Capabilities have arrived (or not) for the printer at a given path */
        void handleCapabilities(Uri printerPath, LocalPrinterCapabilities capabilities) {
            if (DEBUG) Log.d(TAG, "request " + printerPath + " cap=" + capabilities);

            if (capabilities == null) {
                if (mRequests.isEmpty()) {
                    mFinalCallback.onNotFound();
                }
                return;
            }

            // Success, so cancel all other requests
            for (CapabilitiesCache.OnLocalPrinterCapabilities request : mRequests) {
                mCapabilitiesCache.cancel(request);
            }
            mRequests.clear();

            // Deliver a successful response
            Uri uuid = TextUtils.isEmpty(capabilities.uuid) ? null : Uri.parse(capabilities.uuid);
            String name = TextUtils.isEmpty(capabilities.name) ? printerPath.getHost()
                    : capabilities.name;

            DiscoveredPrinter resolvedPrinter = new DiscoveredPrinter(uuid, name, printerPath,
                    capabilities.location);

            // Only add supported printers
            if (capabilities.isSupported) {
                if (addSavedPrinter(resolvedPrinter)) {
                    printerFound(resolvedPrinter);
                }
            }

            mFinalCallback.onFound(resolvedPrinter, capabilities.isSupported);
        }
    }
}
