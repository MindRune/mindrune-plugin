package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.MindRune.util.TextUtil;
import com.google.gson.JsonObject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Listener for quest and achievement-related events
 */
public class AchievementListener {
    private final Client client;
    private final EventLogService eventLogService;
    private final ClientThread clientThread;

    // Interface group IDs
    private static final int ACHIEVEMENT_DIARY_INTERFACE_GROUP = 153;
    private static final int COMBAT_ACHIEVEMENT_INTERFACE_GROUP = 717;

    public AchievementListener(Client client, EventLogService eventLogService, ClientThread clientThread) {
        this.client = client;
        this.eventLogService = eventLogService;
        this.clientThread = clientThread;
    }

    /**
     * Track chat messages related to achievements and quests
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = event.getMessage();

        // Quest completion detection
        if (message.contains("Congratulations") && message.contains("completed") && message.contains("Quest")) {
            handleQuestCompletion(message);
        }

        // Achievement diary completion detection
        if ((message.contains("completed") || message.contains("Completed")) &&
                (message.contains("diary") || message.contains("Diary"))) {
            handleDiaryCompletion(message);
        }

        // Combat achievement detection
        if (message.contains("Combat Achievement") || message.contains("combat achievement")) {
            handleCombatAchievement(message);
        }
    }

    /**
     * Track widget/interface loading for achievements
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // Use clientThread because widgets might not be fully loaded when the event fires
        clientThread.invokeLater(() -> {
            // Achievement diary completion interface
            if (event.getGroupId() == ACHIEVEMENT_DIARY_INTERFACE_GROUP) {
                processAchievementDiaryInterface();
            }

            // Combat Achievement interface
            else if (event.getGroupId() == COMBAT_ACHIEVEMENT_INTERFACE_GROUP) {
                processCombatAchievementInterface();
            }
        });
    }

    /**
     * Handle quest completion messages
     */
    private void handleQuestCompletion(String message) {
        JsonObject details = new JsonObject();

        // Try to extract the quest name from the message
        String questName = message.substring(message.indexOf("completed") + 10);
        questName = questName.substring(0, questName.indexOf("!"));

        details.addProperty("questName", questName.trim());
        details.addProperty("message", message);

        eventLogService.logEvent("QUEST_COMPLETION", client, details);
    }

    /**
     * Handle achievement diary completion messages
     */
    private void handleDiaryCompletion(String message) {
        JsonObject details = new JsonObject();
        details.addProperty("message", message);

        // Try to extract diary details from message
        if (message.contains("Easy") || message.contains("Medium") ||
                message.contains("Hard") || message.contains("Elite")) {

            String[] parts = message.split("\\s+");
            String diaryName = "";
            String diaryTier = "";

            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("diary") || parts[i].contains("Diary")) {
                    if (i > 0) diaryName = parts[i-1];
                    if (i + 1 < parts.length) {
                        if (parts[i+1].equals("Easy") || parts[i+1].equals("Medium") ||
                                parts[i+1].equals("Hard") || parts[i+1].equals("Elite")) {
                            diaryTier = parts[i+1];
                        }
                    }
                }
            }

            details.addProperty("diaryName", diaryName);
            details.addProperty("diaryTier", diaryTier);
        }

        eventLogService.logEvent("ACHIEVEMENT_DIARY_COMPLETION", client, details);
    }

    /**
     * Handle combat achievement messages
     */
    private void handleCombatAchievement(String message) {
        JsonObject details = new JsonObject();
        details.addProperty("message", message);

        // Try to extract achievement name
        String achievementName = message;
        if (message.contains(":")) {
            achievementName = message.substring(message.indexOf(":") + 1).trim();
        }

        details.addProperty("achievementName", achievementName);
        eventLogService.logEvent("COMBAT_ACHIEVEMENT_COMPLETION", client, details);
    }

    /**
     * Process the achievement diary interface for completion data
     */
    private void processAchievementDiaryInterface() {
        Widget achievementWidget = client.getWidget(ACHIEVEMENT_DIARY_INTERFACE_GROUP, 0);
        if (achievementWidget == null || achievementWidget.isHidden()) {
            return;
        }

        String[] widgetText = achievementWidget.getText().split("<br>");
        for (String line : widgetText) {
            if (line.contains("Congratulations")) {
                // Parse the achievement diary name
                String diaryText = TextUtil.stripHtmlTags(line);
                String diaryName = "";
                String diaryTier = "";

                // Extract diary name and tier (e.g., "Ardougne Diary - Hard")
                if (diaryText.contains("diary") || diaryText.contains("Diary")) {
                    String[] parts = diaryText.split("diary|Diary");
                    if (parts.length >= 2) {
                        diaryName = parts[0].trim();
                        if (parts[1].contains("Elite") || parts[1].contains("Hard") ||
                                parts[1].contains("Medium") || parts[1].contains("Easy")) {
                            diaryTier = parts[1].trim();
                        }
                    }
                }

                if (!diaryName.isEmpty()) {
                    JsonObject details = new JsonObject();
                    details.addProperty("diaryName", diaryName);
                    details.addProperty("diaryTier", diaryTier);
                    details.addProperty("message", diaryText);

                    eventLogService.logEvent("ACHIEVEMENT_DIARY_COMPLETION", client, details);
                }
            }
        }
    }

    /**
     * Process the combat achievement interface for completion data
     */
    private void processCombatAchievementInterface() {
        Widget combatWidget = client.getWidget(COMBAT_ACHIEVEMENT_INTERFACE_GROUP, 0);
        if (combatWidget == null || combatWidget.isHidden()) {
            return;
        }

        Widget[] children = combatWidget.getChildren();
        if (children == null) {
            return;
        }

        for (Widget child : children) {
            String text = child.getText();
            if (text != null && (text.contains("completed") || text.contains("Completed"))) {
                String achievementText = TextUtil.stripHtmlTags(text);

                JsonObject details = new JsonObject();
                details.addProperty("achievementName", achievementText);
                details.addProperty("message", text);

                eventLogService.logEvent("COMBAT_ACHIEVEMENT_COMPLETION", client, details);
            }
        }
    }
}