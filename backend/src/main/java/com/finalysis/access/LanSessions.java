package com.finalysis.access;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every HTTP session (only the phone's exist: localhost is stateless), so a lock can end them all (§9.3). Sessions live
 * in memory only; a restart ends them too.
 */
class LanSessions implements HttpSessionListener {

    private final Set<HttpSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        sessions.add(event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessions.remove(event.getSession());
    }

    /** Ends every session; returns how many there were. */
    int invalidateAll() {
        int count = 0;
        for (HttpSession session : Set.copyOf(sessions)) {
            try {
                session.invalidate();
                count++;
            } catch (IllegalStateException alreadyGone) {
                // invalidated meanwhile
            }
            sessions.remove(session);
        }
        return count;
    }

    int count() {
        return sessions.size();
    }
}
