package com.MindRune.model;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;

/**
 * Represents player information to be sent with event data
 */
public class PlayerInfo {
    private final String playerName;
    private final long playerId;
    private final int combatLevel;
    private final int totalLevel;
    private final long totalXp;

    private PlayerInfo(String playerName, long playerId, int combatLevel, int totalLevel, long totalXp) {
        this.playerName = playerName;
        this.playerId = playerId;
        this.combatLevel = combatLevel;
        this.totalLevel = totalLevel;
        this.totalXp = totalXp;
    }

    /**
     * Create PlayerInfo from client data
     *
     * @param client RuneLite client
     * @return PlayerInfo object, or null if player information isn't available
     */
    public static PlayerInfo fromClient(Client client) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return null;
        }

        // Calculate total level and XP
        int totalLevel = 0;
        long totalXp = 0;
        for (Skill skill : Skill.values()) {
            totalLevel += client.getRealSkillLevel(skill);
            totalXp += client.getSkillExperience(skill);
        }

        return new PlayerInfo(
                player.getName(),
                client.getAccountHash(),
                player.getCombatLevel(),
                totalLevel,
                totalXp
        );
    }

    /**
     * Convert to JSON representation
     *
     * @return JsonObject with player information
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("playerName", playerName);
        json.addProperty("playerId", playerId);
        json.addProperty("combatLevel", combatLevel);
        json.addProperty("totalLevel", totalLevel);
        json.addProperty("totalXp", totalXp);
        return json;
    }
}