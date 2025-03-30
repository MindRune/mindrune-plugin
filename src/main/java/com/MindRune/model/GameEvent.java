package com.MindRune.model;

import com.google.gson.JsonObject;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import java.time.Instant;

/**
 * Represents a game event that will be logged and sent to the API
 */
public class GameEvent {
    private final String eventType;
    private final Instant timestamp;
    private final JsonObject playerLocation;
    private final JsonObject details;

    private GameEvent(String eventType, JsonObject playerLocation, JsonObject details) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.playerLocation = playerLocation;
        this.details = details;
    }

    /**
     * Create a new game event
     *
     * @param eventType Type of event
     * @param player Player involved in the event
     * @param details Additional event details
     * @return New GameEvent object
     */
    public static GameEvent create(String eventType, Player player, JsonObject details) {
        JsonObject playerLocation = null;

        if (player != null) {
            WorldPoint location = player.getWorldLocation();
            playerLocation = new JsonObject();
            playerLocation.addProperty("x", location.getX());
            playerLocation.addProperty("y", location.getY());
            playerLocation.addProperty("plane", location.getPlane());
        }

        return new GameEvent(eventType, playerLocation, details);
    }

    /**
     * Convert the event to a JSON object
     *
     * @return JSON representation of the event
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("eventType", eventType);
        json.addProperty("timestamp", timestamp.toString());

        if (playerLocation != null) {
            json.add("playerLocation", playerLocation);
        }

        json.add("details", details);
        return json;
    }
}