package dev.cgm.app.ui

/**
 * How an insulin or carbohydrate amount is written on screen.
 *
 * Drops the decimal when there is not one, so "6 U" rather than "6.0 U" and "45 g"
 * rather than "45.0 g". A trailing zero on a dose claims a precision pens do not
 * have, and a column of them reads as machine output rather than as what somebody
 * wrote down.
 *
 * Uses the default locale, so a Spanish interface shows "6,5" — the same separator
 * the form accepted it in.
 *
 * One function for every screen that prints an amount: the diary, the reminders and
 * the trend chart all have to agree, and three copies of a formatting rule is three
 * places for "6.0 U" to come back.
 */
internal fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else String.format("%.1f", units)
