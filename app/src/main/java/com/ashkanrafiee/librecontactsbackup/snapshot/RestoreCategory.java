package com.ashkanrafiee.librecontactsbackup.snapshot;

import com.ashkanrafiee.librecontactsbackup.R;

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
 * {@link #notRecommendedReasonRes} explaining why, shown in parentheses.
 *
 * Title/description/example/reason are string resource ids rather than
 * literal text so every supported language renders its own translation;
 * resolve them against a Context (e.g. {@code context.getString(category.titleRes)}).
 */
public enum RestoreCategory {

    CONTACT_INFO(
            R.string.category_contact_info_title,
            R.string.category_contact_info_description,
            R.string.category_contact_info_example,
            true, 0),

    PHOTOS(
            R.string.category_photos_title,
            R.string.category_photos_description,
            R.string.category_photos_example,
            true, 0),

    GROUPS(
            R.string.category_groups_title,
            R.string.category_groups_description,
            R.string.category_groups_example,
            true, 0),

    ADDITIONAL_DATA(
            R.string.category_additional_title,
            R.string.category_additional_description,
            R.string.category_additional_example,
            false, R.string.category_additional_not_recommended),

    ACCOUNT_INFO(
            R.string.category_account_title,
            R.string.category_account_description,
            R.string.category_account_example,
            false, R.string.category_account_not_recommended);

    public final int titleRes;
    public final int descriptionRes;
    public final int exampleRes;
    public final boolean recommended;
    /** 0 only when {@link #recommended} is true; otherwise a string resource explaining why, for display in parentheses. */
    public final int notRecommendedReasonRes;

    RestoreCategory(int titleRes, int descriptionRes, int exampleRes, boolean recommended, int notRecommendedReasonRes) {
        this.titleRes = titleRes;
        this.descriptionRes = descriptionRes;
        this.exampleRes = exampleRes;
        this.recommended = recommended;
        this.notRecommendedReasonRes = notRecommendedReasonRes;
    }
}
