package com.jeannedarc.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener.
 *
 * We never read notifications here. The service exists only because
 * MediaSessionManager.getActiveSessions() requires the caller to name a
 * component that holds notification-listener access -- that grant is what
 * lets the now-playing widget read the current track and send transport
 * controls from any media app (Spotify, the firmware radio, etc.).
 */
public class NLService extends NotificationListenerService {
}
