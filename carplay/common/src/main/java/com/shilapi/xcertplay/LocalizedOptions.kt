package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** What is wrong with the car hotspot's details, for the driver. */
internal fun ManualHotspotValidation.Error.messageResource(): Int = when (this) {
    ManualHotspotValidation.Error.EMPTY_NAME -> R.string.hotspot_error_empty_name
    ManualHotspotValidation.Error.LONG_NAME -> R.string.hotspot_error_long_name
    ManualHotspotValidation.Error.INVALID_CHARACTER -> R.string.hotspot_error_invalid_character
    ManualHotspotValidation.Error.PASSWORD_LENGTH -> R.string.hotspot_error_password_length
}
