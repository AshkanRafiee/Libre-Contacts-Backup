package com.ashkanrafiee.librecontactsbackup.snapshot;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single entry read from the SIM card phonebook.
 *
 * Only the fields that can actually be stored on a SIM card are captured:
 * the display name and the phone number. {@code subscriptionId} records
 * which SIM (subscription) the entry was read from, so a restore can target
 * the same card again when the device has more than one; {@code slotIndex}
 * is kept as best-effort extra context and may be -1 when the provider does
 * not expose it.
 */
public final class SimContact {

    public String name;
    public String number;
    public int subscriptionId = -1;
    public int slotIndex = -1;

    public SimContact() {}

    public SimContact(String name, String number) {
        this.name = name;
        this.number = number;
    }

    public boolean isEmpty() {
        return (name == null || name.isEmpty()) && (number == null || number.isEmpty());
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("name", name != null ? name : "");
        obj.put("number", number != null ? number : "");
        obj.put("subscriptionId", subscriptionId);
        obj.put("slotIndex", slotIndex);
        return obj;
    }

    public static SimContact fromJson(JSONObject obj) throws JSONException {
        SimContact sim = new SimContact();
        String name = obj.optString("name", null);
        String number = obj.optString("number", null);
        sim.name = (name == null || name.isEmpty()) ? null : name;
        sim.number = (number == null || number.isEmpty()) ? null : number;
        sim.subscriptionId = obj.optInt("subscriptionId", -1);
        sim.slotIndex = obj.optInt("slotIndex", -1);
        return sim;
    }
}