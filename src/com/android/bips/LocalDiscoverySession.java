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

package com.android.bips;

import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrinterDiscoverySession;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;

import com.android.bips.discovery.DiscoveredPrinter;
import com.android.bips.discovery.Discovery;
import com.android.bips.ipp.CapabilitiesCache;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class LocalDiscoverySession extends PrinterDiscoverySession implements Discovery.Listener {
    private static final String TAG = LocalDiscoverySession.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Printers are removed after not being seen for this long
    static final long PRINTER_EXPIRATION_MILLIS = 3000;

    private static final String KNOWN_GOOD_FILE = "knowngood.json";
    private static final int KNOWN_GOOD_MAX = 50;

    private final BuiltInPrintService mPrintService;
    private final CapabilitiesCache mCapabilitiesCache;
    private final Map<PrinterId, LocalPrinter> mPrinters = new HashMap<>();
    private final Set<PrinterId> mPriorityIds = new HashSet<>();
    private final Set<PrinterId> mTrackingIds = new HashSet<>();
    private final List<PrinterId> mKnownGood = new ArrayList<>();
    private Runnable mExpirePrinters;

    LocalDiscoverySession(BuiltInPrintService service) {
        mPrintService = service;
        mCapabilitiesCache = service.getCapabilitiesCache();
        loadKnownGood();
    }

    @Override
    public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
        if (DEBUG) Log.d(TAG, "onStartPrinterDiscovery() " + priorityList);

        // Replace priority IDs with the current list.
        mPriorityIds.clear();
        mPriorityIds.addAll(priorityList);

        // Mark all known printers as "not found". They may return shortly or may expire
        mPrinters.values().forEach(LocalPrinter::notFound);
        monitorExpiredPrinters();

        mPrintService.getDiscovery().start(this);
    }

    @Override
    public void onStopPrinterDiscovery() {
        if (DEBUG) Log.d(TAG, "onStopPrinterDiscovery()");
        mPrintService.getDiscovery().stop(this);

        if (mExpirePrinters != null) {
            mPrintService.getMainHandler().removeCallbacks(mExpirePrinters);
            mExpirePrinters = null;
        }
    }

    @Override
    public void onValidatePrinters(List<PrinterId> printerIds) {
        if (DEBUG) Log.d(TAG, "onValidatePrinters() " + printerIds);
    }

    @Override
    public void onStartPrinterStateTracking(final PrinterId printerId) {
        if (DEBUG) Log.d(TAG, "onStartPrinterStateTracking() " + printerId);
        LocalPrinter localPrinter = mPrinters.get(printerId);
        mTrackingIds.add(printerId);

        // We cannot track the printer yet; wait until it is discovered
        if (localPrinter == null || !localPrinter.isFound()) return;

        // Immediately request a refresh of capabilities
        localPrinter.requestCapabilities();
    }

    @Override
    public void onStopPrinterStateTracking(PrinterId printerId) {
        if (DEBUG) Log.d(TAG, "onStopPrinterStateTracking() " + printerId.getLocalId());
        mTrackingIds.remove(printerId);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        saveKnownGood();
    }

    /**
     * A printer was found during discovery
     */
    @Override
    public void onPrinterFound(DiscoveredPrinter discoveredPrinter) {
        if (DEBUG) Log.d(TAG, "onPrinterFound() " + discoveredPrinter);
        if (isDestroyed()) {
            Log.w(TAG, "Destroyed; ignoring");
            return;
        }

        final PrinterId printerId = discoveredPrinter.getId(mPrintService);
        LocalPrinter localPrinter = mPrinters.get(printerId);
        if (localPrinter == null) {
            localPrinter = new LocalPrinter(mPrintService, this, discoveredPrinter);
            mPrinters.put(printerId, localPrinter);
        }
        localPrinter.found();
    }

    /**
     * A printer was lost during discovery
     */
    @Override
    public void onPrinterLost(DiscoveredPrinter lostPrinter) {
        if (DEBUG) Log.d(TAG, "onPrinterLost() " + lostPrinter);

        PrinterId printerId = lostPrinter.getId(mPrintService);
        if (printerId.getLocalId().startsWith("ipp")) {
            // Forget capabilities for network addresses (which are not globally unique)
            mCapabilitiesCache.remove(lostPrinter.getUri());
        }

        LocalPrinter localPrinter = mPrinters.get(printerId);
        if (localPrinter == null) return;

        localPrinter.notFound();
        handlePrinter(localPrinter);
        monitorExpiredPrinters();
    }

    private void monitorExpiredPrinters() {
        if (mExpirePrinters == null && !mPrinters.isEmpty()) {
            mExpirePrinters = new ExpirePrinters();
            mPrintService.getMainHandler().postDelayed(mExpirePrinters, PRINTER_EXPIRATION_MILLIS);
        }
    }

    /** A complete printer record is available */
    void handlePrinter(LocalPrinter localPrinter) {
        if (localPrinter.getCapabilities() == null &&
                !mKnownGood.contains(localPrinter.getPrinterId())) {
            // Ignore printers that have no capabilities and are not known-good
            return;
        }

        PrinterInfo info = localPrinter.createPrinterInfo();

        mKnownGood.remove(localPrinter.getPrinterId());

        if (info == null) return;

        // Update known-good database with current results.
        if (info.getStatus() == PrinterInfo.STATUS_IDLE && localPrinter.getUuid() != null) {
            // Mark UUID-based printers with IDLE status as known-good
            mKnownGood.add(0, localPrinter.getPrinterId());
        }

        if (DEBUG) {
            Log.d(TAG, "handlePrinter: reporting " + localPrinter +
                    " caps=" + (info.getCapabilities() != null) + " status=" + info.getStatus());
        }

        addPrinters(Collections.singletonList(info));
    }

    /**
     * Return true if the {@link PrinterId} corresponds to a high-priority printer
     */
    boolean isPriority(PrinterId printerId) {
        return mPriorityIds.contains(printerId) || mTrackingIds.contains(printerId);
    }

    /**
     * Return true if the {@link PrinterId} corresponds to a known printer
     */
    boolean isKnown(PrinterId printerId) {
        return mPrinters.containsKey(printerId);
    }

    /**
     * Load "known good" printer IDs from storage, if possible
     */
    private void loadKnownGood() {
        File file = new File(mPrintService.getCacheDir(), KNOWN_GOOD_FILE);
        if (!file.exists()) return;
        try (JsonReader reader = new JsonReader(new FileReader(file))) {
            reader.beginArray();
            while (reader.hasNext()) {
                String localId = reader.nextString();
                mKnownGood.add(mPrintService.generatePrinterId(localId));
            }
            reader.endArray();
        } catch (IOException e) {
            Log.w(TAG, "Failed to read known good list", e);
        }
    }

    /**
     * Save "known good" printer IDs to storage, if possible
     */
    private void saveKnownGood() {
        File file = new File(mPrintService.getCacheDir(), KNOWN_GOOD_FILE);
        try (JsonWriter writer = new JsonWriter(new FileWriter(file))) {
            writer.beginArray();
            for (int i = 0; i < Math.min(KNOWN_GOOD_MAX, mKnownGood.size()); i++) {
                writer.value(mKnownGood.get(i).getLocalId());
            }
            writer.endArray();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write known good list", e);
        }
    }

    /** A runnable that periodically removes expired printers, when any exist */
    private class ExpirePrinters implements Runnable {
        @Override
        public void run() {
            boolean allFound = true;
            List<PrinterId> idsToRemove = new ArrayList<>();

            for (LocalPrinter localPrinter : mPrinters.values()) {
                if (localPrinter.isExpired()) {
                    if (DEBUG) Log.d(TAG, "Expiring " + localPrinter);
                    idsToRemove.add(localPrinter.getPrinterId());
                }
                if (!localPrinter.isFound()) allFound = false;
            }
            idsToRemove.forEach(mPrinters::remove);
            removePrinters(idsToRemove);
            if (!allFound) {
                mPrintService.getMainHandler().postDelayed(this, PRINTER_EXPIRATION_MILLIS);
            } else {
                mExpirePrinters = null;
            }
        }
    }
}