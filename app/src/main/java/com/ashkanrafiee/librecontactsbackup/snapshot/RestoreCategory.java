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
            "Less common or provider-specific fields found in this backup.",
            "e.g. a custom field saved by WhatsApp or another app",
            false, "rarely needed, and may not display correctly on this device"),

    ACCOUNT_INFO(
            "Account/source information",
            "Preserve the original account (e.g. a Google account) where supported. When a contact came from more than one source, it's restored as one contact under a single account — the original multi-source structure isn't recreated exactly.",
            "e.g. that a contact is linked to your Google account",
            false, "not recommended when restoring to a different device or account — the contact still restores, just as a local contact");

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
