package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import java.util.HashMap;
import java.util.Map;

/**
 * Listener for skill and XP related events
 */
public class SkillListener {
    private final Client client;
    private final EventLogService eventLogService;
    private final Map<Skill, Integer> previousXp = new HashMap<>();
    private boolean isInitialized = false;

    public SkillListener(Client client, EventLogService eventLogService) {
        this.client = client;
        this.eventLogService = eventLogService;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Initialize XP tracking on the first game tick
        if (!isInitialized) {
            for (Skill skill : Skill.values()) {
                previousXp.put(skill, client.getSkillExperience(skill));
            }
            isInitialized = true;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        int newXp = event.getXp();

        // Only proceed if we have a previous value for this skill
        if (previousXp.containsKey(skill)) {
            int previousXpValue = previousXp.get(skill);
            int xpGained = newXp - previousXpValue;

            // Only log actual XP gains
            if (xpGained > 0) {
                JsonObject details = new JsonObject();
                details.addProperty("skill", skill.getName());
                details.addProperty("totalXp", newXp);
                details.addProperty("xpGained", xpGained);
                details.addProperty("level", event.getLevel());

                eventLogService.logEvent("XP_GAIN", client, details);
            }
        }

        // Always update the stored XP value
        previousXp.put(skill, newXp);
    }
}