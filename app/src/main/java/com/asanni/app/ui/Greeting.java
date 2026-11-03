package com.asanni.app.ui;

import android.content.Context;

import com.asanni.app.R;

import java.util.Calendar;

/**
 * Time-of-day greeting for Discover ("Good Evening, Anas").
 *
 * Location: app/src/main/java/com/asanni/app/ui/Greeting.java  (NEW in phase 6)
 *
 * Uses the phone's own clock and time zone. The first name comes from the saved profile and is
 * only READ here; the profile is never changed to build the greeting. No name = no comma.
 */
final class Greeting {

    /** [CONFIG] Period boundaries (24-hour clock): morning 5 AM, afternoon 12 PM, evening 5 PM. */
    static final int MORNING_START_HOUR = 5;
    static final int AFTERNOON_START_HOUR = 12;
    static final int EVENING_START_HOUR = 17;

    enum Period { MORNING, AFTERNOON, EVENING }

    private Greeting() {}

    static Period periodAt(int hourOfDay) {
        if (hourOfDay >= MORNING_START_HOUR && hourOfDay < AFTERNOON_START_HOUR) return Period.MORNING;
        if (hourOfDay >= AFTERNOON_START_HOUR && hourOfDay < EVENING_START_HOUR) return Period.AFTERNOON;
        return Period.EVENING; // 5 PM - 4:59 AM
    }

    static String text(Context c, String firstName, Calendar now) {
        String name = firstName == null ? "" : firstName.trim();
        boolean hasName = !name.isEmpty() && !name.contains("@") && !name.equalsIgnoreCase("null");
        switch (periodAt(now.get(Calendar.HOUR_OF_DAY))) {
            case MORNING:
                return hasName ? c.getString(R.string.greeting_morning_named, name) : c.getString(R.string.greeting_morning);
            case AFTERNOON:
                return hasName ? c.getString(R.string.greeting_afternoon_named, name) : c.getString(R.string.greeting_afternoon);
            default:
                return hasName ? c.getString(R.string.greeting_evening_named, name) : c.getString(R.string.greeting_evening);
        }
    }

    /** Milliseconds until the greeting should change next (the next period boundary). */
    static long millisUntilNextChange(Calendar now) {
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int nextHour;
        if (hour < MORNING_START_HOUR) nextHour = MORNING_START_HOUR;
        else if (hour < AFTERNOON_START_HOUR) nextHour = AFTERNOON_START_HOUR;
        else if (hour < EVENING_START_HOUR) nextHour = EVENING_START_HOUR;
        else nextHour = MORNING_START_HOUR + 24;
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.HOUR_OF_DAY, nextHour); // add() handles day changes and DST
        return Math.max(1_000L, next.getTimeInMillis() - now.getTimeInMillis() + 500L);
    }
}