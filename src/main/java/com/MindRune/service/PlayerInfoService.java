package com.MindRune.service;

import com.MindRune.model.PlayerInfo;
import com.google.gson.JsonObject;
import net.runelite.api.Client;

/**
 * Service for retrieving and managing player information
 */
public class PlayerInfoService {
    private final Client client;

    public PlayerInfoService(Client client) {
        this.client = client;
    }

    /**
     * Get current player information
     *
     * @return PlayerInfo object or null if player is not available
     */
    public PlayerInfo getCurrentPlayerInfo() {
        return PlayerInfo.fromClient(client);
    }

    /**
     * Get player information as JSON
     *
     * @return JsonObject with player information or null if player is not available
     */
    public JsonObject getPlayerInfoJson() {
        PlayerInfo info = getCurrentPlayerInfo();
        return (info != null) ? info.toJson() : null;
    }
}