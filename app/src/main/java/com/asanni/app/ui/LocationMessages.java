package com.asanni.app.ui;

import com.asanni.app.R;
import com.asanni.app.auth.UserProfileRepository;
import com.asanni.app.location.LocationCatalogueRepository;

/**
 * Maps failures to user-facing messages (strings_location.xml), shared by the location screens.
 *
 * Location: app/src/main/java/com/asanni/app/ui/LocationMessages.java  (NEW)
 */
final class LocationMessages {

    private LocationMessages() {}

    static int catalogue(LocationCatalogueRepository.Failure failure) {
        switch (failure) {
            case NETWORK:           return R.string.catalogue_error_network;
            case PERMISSION_DENIED: return R.string.catalogue_error_permission;
            case NOT_SEEDED:        return R.string.catalogue_error_not_seeded;
            case UNKNOWN:
            default:                return R.string.catalogue_error_generic;
        }
    }

    static int save(UserProfileRepository.Failure failure) {
        switch (failure) {
            case NETWORK:           return R.string.location_save_error_network;
            case PERMISSION_DENIED: return R.string.location_save_error_permission;
            case DATABASE_MISSING:
            case UNKNOWN:
            default:                return R.string.location_save_error_generic;
        }
    }
}