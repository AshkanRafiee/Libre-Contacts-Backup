package com.ashkanrafiee.librecontactsbackup.snapshot;

/**
 * User-facing restore categories. The backup itself is always comprehensive;
 * these categories let the user choose what gets materialized into the
 * target Contacts Provider on a given restore, without ever touching what
 * is preserved inside the .lcb archive itself.
 *
 * Deliberately avoids exposing technical concepts like "RawContact" or
 * "MIME type" in the label/description shown to users. {@link #recommended}
 * controls the default selection state in the restore-selection dialog;
 * categories that aren't recommended by default carry a
 * {@link #notRecommendedReason} explaining why, shown in parentheses.
 */
public enum RestoreCategory {

    CONTACT_INFO(
            "Contact information",
            "Names, phone numbers, emails, addresses, organizations, notes, birthdays, and other everyday contact fields.",
            "e.g. “John Smith · +1 555-0100 · john@example.com”",
            true, null),

    PHOTOS(
            "Contact photos",
            "Photos you've set for your contacts.",
            "e.g. a contact's profile picture",
            true, null),

    GROUPS(
            "Groups",
            "Contact groups and who belongs to them.",
            "e.g. “Family”, “Work”, “Book Club”",
            true, null),

    ADDITIONAL_DATA(
            "Additional contact data",
            "Entries that carry their own data from another app (e.g. a messaging app), and less common provider-specific fields. Each such entry is restored exactly as a whole — all of its data AND its original account together, or none of it at all — since a partial version (e.g. just a name, without what made it that entry, or without the account that app recognizes as its own) wouldn't match the original and isn't useful on its own. Note: whether that app actually recognizes the restored entry as its own is up to that app, not this setting — some apps may still add a new entry of their own regardless.",
            "e.g. a messaging-app-linked entry restored with all its own data and its account intact, or not at all",
            false, "rarely needed, may not display correctly on this device, restores each such entry only in full, never partially, and doesn't guarantee the other app won't still create its own entry too"),

    ACCOUNT_INFO(
            "Account/source information",
            "Preserve your ordinary contacts' original account (e.g. a Google account) so they stay linked to it. This is separate from Additional contact data, which always keeps its own entries' accounts together with their data when selected.",
            "e.g. that a contact is linked to your Google account",
            false, "not recommended when restoring to a different device or account — the contact still restores, just as a local contact. Note: keeping an account doesn't guarantee that app will recognize the contact as already synced — that depends on the app, not this setting");

    public final String title;
    public final String description;
    public final String example;
    public final boolean recommended;
    /** Non-null only when {@link #recommended} is false; explains why, for display in parentheses. */
    public final String notRecommendedReason;

    RestoreCategory(String title, String description, String example, boolean recommended, String notRecommendedReason) {
        this.title = title;
        this.description = description;
        this.example = example;
        this.recommended = recommended;
        this.notRecommendedReason = notRecommendedReason;
    }
}
