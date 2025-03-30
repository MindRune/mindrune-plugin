package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.MindRune.util.TextUtil;
import com.google.gson.JsonObject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.HitsplatID;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

/**
 * Listener for hitsplat-related events
 */
public class HitSplatListener {
    private final Client client;
    private final EventLogService eventLogService;

    public HitSplatListener(Client client, EventLogService eventLogService) {
        this.client = client;
        this.eventLogService = eventLogService;
    }

    /**
     * Convert a hitsplat type ID to a readable string
     * @param hitsplatType The hitsplat type ID
     * @return A readable string representation
     */
    private String getHitsplatTypeString(int hitsplatType) {
        // Map hitsplat type IDs to readable strings
        switch (hitsplatType) {
            case HitsplatID.POISON:
                return "Poison";
            case HitsplatID.DISEASE:
                return "Disease";
            case HitsplatID.VENOM:
                return "Venom";
            case HitsplatID.HEAL:
                return "Heal";
            case HitsplatID.DAMAGE_ME:
            case HitsplatID.DAMAGE_OTHER:
                return "Damage";
            case HitsplatID.DAMAGE_MAX_ME:
            case HitsplatID.DAMAGE_MAX_ME_CYAN:
            case HitsplatID.DAMAGE_MAX_ME_ORANGE:
            case HitsplatID.DAMAGE_MAX_ME_WHITE:
            case HitsplatID.DAMAGE_MAX_ME_YELLOW:
                return "Max Damage";
            case HitsplatID.BLOCK_ME:
                return "Block";
            default:
                return "" + hitsplatType;
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        Player localPlayer = client.getLocalPlayer();
        Actor target = event.getActor();

        if (target == null || localPlayer == null) {
            return;
        }

        JsonObject details = new JsonObject();

        if (target == localPlayer) {
            Actor attacker = localPlayer.getInteracting();

            details.addProperty("target", "Player");
            details.addProperty("damage", event.getHitsplat().getAmount());

            // Use an int for type
            int hitsplatType = event.getHitsplat().getHitsplatType();
            details.addProperty("type", hitsplatType);

            // Also add a string representation of the type
            details.addProperty("typeString", getHitsplatTypeString(hitsplatType));

            if (attacker != null) {
                details.addProperty("source", TextUtil.stripColorTags(attacker.getName()));
            } else {
                // Use the string representation instead of the numeric value
                details.addProperty("source", getHitsplatTypeString(hitsplatType));
            }

            details.addProperty("direction", "incoming");
            eventLogService.logEvent("HIT_SPLAT", client, details);
        }

        else if (isPlayerInteractingWith(target)) {
            details.addProperty("source", "Player");
            details.addProperty("target", TextUtil.stripColorTags(target.getName()));
            details.addProperty("damage", event.getHitsplat().getAmount());

            // Use an int for type
            int hitsplatType = event.getHitsplat().getHitsplatType();
            details.addProperty("type", hitsplatType);

            // Also add a string representation of the type
            details.addProperty("typeString", getHitsplatTypeString(hitsplatType));

            details.addProperty("direction", "outgoing");
            eventLogService.logEvent("HIT_SPLAT", client, details);
        }
    }

    /**
     * Check if the player is interacting with the given actor
     */
    private boolean isPlayerInteractingWith(Actor target) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return false;
        }

        Actor interacting = localPlayer.getInteracting();
        return interacting != null && interacting == target;
    }
}