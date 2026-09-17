package com.ashkanrafiee.librecontactsbackup.snapshot;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.SimPhonebookContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the SIM card phonebook, best-effort.
 *
 * On API 31+ the dedicated SIM phonebook provider
 * ({@link SimPhonebookContract}, authority {@code com.android.simphonebook})
 * is queried through its bundle-argument overload (the string-selection
 * overload is not supported there and throws). On API 26–30 the legacy
 * {@code content://icc/adn} provider is used.
 *
 * Either provider may be absent, empty (no SIM inserted), or blocked by the
 * device vendor on any given device, so every query is guarded and a failure
 * simply yields an empty result. A backup must never fail because the SIM
 * phonebook could not be read — the SIM entries are an additive, best-effort
 * part of the snapshot, and the empty result is indistinguishable from "no
 * SIM contacts".
 */
public final class SimContactsReader {

    private static final String TAG = "SimContactsReader";
    private static final Uri LEGACY_ADN_URI = Uri.parse("content://icc/adn");
    private static final String[] LEGACY_PROJECTION = { "_id", "name", "number" };

    private SimContactsReader() {}

    public static final class Result {
        public final List<SimContact> contacts = new ArrayList<>();
        /** True when a SIM phonebook provider could be queried at all (even if empty). */
        public final boolean providerAvailable;

        Result(boolean providerAvailable) {
            this.providerAvailable = providerAvailable;
        }
    }

    public static Result readSimContacts(ContentResolver resolver) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                List<SimContact> modern = readModern(resolver);
                Result result = new Result(true);
                result.contacts.addAll(modern);
                return result;
            } catch (Exception e) {
                Log.w(TAG, "Modern SIM phonebook unreadable, falling back to legacy provider", e);
            }
        }

        try {
            Result result = new Result(true);
            readLegacy(resolver, result.contacts);
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Legacy SIM phonebook unreadable", e);
            return new Result(false);
        }
    }

    /**
     * Best-effort list of subscription ids that currently expose a SIM
     * phonebook (ADN) elementary file — i.e. the cards a restore could write
     * contacts to. Returns an empty list on providers that are absent,
     * unsupported, or empty (no SIM inserted); callers use this to offer the
     * user a target-card choice.
     */
    public static List<Integer> activeAdnSubscriptionIds(ContentResolver resolver) {
        try {
            int[] subs = adnSubscriptionIds(resolver);
            List<Integer> out = new ArrayList<>(subs.length);
            for (int sub : subs) out.add(sub);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate SIM card subscriptions", e);
            return Collections.emptyList();
        }
    }

    /**
     * Reads every ADN (address book) record across all inserted SIM
     * subscriptions via {@link SimPhonebookContract}. Pressed {@code null}
     * when the provider itself is absent/broken, so the caller can fall back.
     */
    private static List<SimContact> readModern(ContentResolver resolver) throws Exception {
        List<SimContact> out = new ArrayList<>();
        for (int subId : adnSubscriptionIds(resolver)) {
            Uri recordsUri = SimPhonebookContract.SimRecords.getContentUri(subId, SimPhonebookContract.ElementaryFiles.EF_ADN);
            String[] projection = {
                    SimPhonebookContract.SimRecords.NAME,
                    SimPhonebookContract.SimRecords.PHONE_NUMBER
            };
            // The bundle overload is the only supported query path on this
            // provider; an empty query args bundle with no SQL argument keys
            // simply means "all rows" (the string-selection overload throws).
            Cursor cursor = resolver.query(recordsUri, projection, new Bundle(), null);
            if (cursor == null) continue;
            try {
                int idxName = cursor.getColumnIndex(SimPhonebookContract.SimRecords.NAME);
                int idxNumber = cursor.getColumnIndex(SimPhonebookContract.SimRecords.PHONE_NUMBER);
                if (idxName < 0 || idxNumber < 0) continue;
                while (cursor.moveToNext()) {
                    SimContact sim = new SimContact();
                    sim.name = cursor.isNull(idxName) ? null : cursor.getString(idxName);
                    sim.number = cursor.isNull(idxNumber) ? null : cursor.getString(idxNumber);
                    sim.subscriptionId = subId;
                    if (!sim.isEmpty()) out.add(sim);
                }
            } finally {
                cursor.close();
            }
        }
        return out;
    }

    /**
     * Finds the subscription id of every ADN elementary file among the
     * inserted SIM cards (there is one ADN per SIM slot). Delegates to the
     * legacy provider when the modern one is not present.
     */
    private static int[] adnSubscriptionIds(ContentResolver resolver) throws Exception {
        String[] projection = {
                SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID,
                SimPhonebookContract.ElementaryFiles.EF_TYPE
        };
        ArrayList<Integer> subs = new ArrayList<>();
        Cursor cursor = resolver.query(SimPhonebookContract.ElementaryFiles.CONTENT_URI, projection, new Bundle(), null);
        if (cursor == null) return new int[0];
        try {
            int idxSub = cursor.getColumnIndex(SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID);
            int idxEfType = cursor.getColumnIndex(SimPhonebookContract.ElementaryFiles.EF_TYPE);
            if (idxSub < 0 || idxEfType < 0) return new int[0];
            while (cursor.moveToNext()) {
                if (cursor.getInt(idxEfType) != SimPhonebookContract.ElementaryFiles.EF_ADN) continue;
                int subId = cursor.getInt(idxSub);
                if (!subs.contains(subId)) subs.add(subId);
            }
        } finally {
            cursor.close();
        }
        int[] result = new int[subs.size()];
        for (int i = 0; i < subs.size(); i++) result[i] = subs.get(i);
        return result;
    }

    private static void readLegacy(ContentResolver resolver, List<SimContact> out) throws Exception {
        Cursor cursor = resolver.query(LEGACY_ADN_URI, LEGACY_PROJECTION, null, null, null);
        if (cursor == null) return;
        try {
            int idxName = cursor.getColumnIndex("name");
            int idxNumber = cursor.getColumnIndex("number");
            while (cursor.moveToNext()) {
                SimContact sim = new SimContact();
                sim.name = idxName >= 0 && !cursor.isNull(idxName) ? cursor.getString(idxName) : null;
                sim.number = idxNumber >= 0 && !cursor.isNull(idxNumber) ? cursor.getString(idxNumber) : null;
                if (!sim.isEmpty()) out.add(sim);
            }
        } finally {
            cursor.close();
        }
    }
}