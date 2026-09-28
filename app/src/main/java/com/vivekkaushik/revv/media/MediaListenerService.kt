package com.vivekkaushik.revv.media

import android.service.notification.NotificationListenerService

/**
 * Revv never reads notifications. Android only shares the list of active media sessions with apps
 * that hold notification access, and this service is what the user grants that access to.
 */
class MediaListenerService : NotificationListenerService()
