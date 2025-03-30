package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.MindRune.util.TextUtil;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WorldChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Listener for player interactions with the game world
 */
public class InteractionListener {
    private final Client client;
    private final EventLogService eventLogService;

    public InteractionListener(Client client, EventLogService eventLogService) {
        this.client = client;
        this.eventLogService = eventLogService;
    }

    /**
     * Track menu option clicks (player interactions)
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        JsonObject details = new JsonObject();
        details.addProperty("action", TextUtil.stripColorTags(event.getMenuOption()));
        details.addProperty("target", TextUtil.stripColorTags(event.getMenuTarget()));

        // Add identifier if available
        if (event.getId() != -1) {
            details.addProperty("id", event.getId());
        }

        eventLogService.logEvent("MENU_CLICK", client, details);
    }

    /**
     * Track world changes
     */
    @Subscribe
    public void onWorldChanged(WorldChanged event) {
        JsonObject details = new JsonObject();
        details.addProperty("worldId", client.getWorld());

        eventLogService.logEvent("WORLD_CHANGE", client, details);
    }
}