package com.MindRune.service;

import com.MindRune.model.GameEvent;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Service for logging game events
 */
public class EventLogService {
    // Using CopyOnWriteArrayList for thread safety
    private final List<JsonObject> eventLog = new CopyOnWriteArrayList<>();

    /**
     * Log a game event
     *
     * @param eventType The type of event
     * @param client The RuneLite client
     * @param details Additional event details
     */
    public void logEvent(String eventType, Client client, JsonObject details) {
        Player player = client.getLocalPlayer();
        GameEvent event = GameEvent.create(eventType, player, details);
        eventLog.add(event.toJson());
    }

    /**
     * Get all logged events and clear the log
     *
     * @return List of event JSONs
     */
    public List<JsonObject> getAndClearEvents() {
        List<JsonObject> events = new ArrayList<>(eventLog);
        eventLog.clear();
        return events;
    }

    /**
     * Check if there are any events logged
     *
     * @return true if there are events, false otherwise
     */
    public boolean hasEvents() {
        return !eventLog.isEmpty();
    }
}