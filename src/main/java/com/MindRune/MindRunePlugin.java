package com.MindRune;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.WidgetLoaded;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

@Slf4j
@PluginDescriptor(
		name = "MindRune",
		description = "MindRune - Play to grow The MindRune Graph and earn claim to his future success!",
		tags = {"example", "template"}
)
public class MindRunePlugin extends Plugin {

	@Inject
	private Client client;

	private final List<JsonObject> eventLog = new ArrayList<>();
	private final Gson gson = new Gson();
	private static final String API_URL = "https://api.mindrune.xyz/osrs/create"; // Change this

	@Inject
	private MindRuneConfig config;
	@Inject
	private ClientThread clientThread;

	@Provides
	MindRuneConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(MindRuneConfig.class);
	}

	@Override
	protected void startUp() {
		log.info("MindRune Plugin Started!");
		if (config.enableChatNotifications()) {
			clientThread.invokeLater(() -> {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "MindRune is now creating memories! Enjoy your adventure!", null);
			});
		}
		startDataSender();
	}

	@Override
	protected void shutDown() {
		log.info("MindRune Plugin Stopped!");
		if (config.enableChatNotifications()) {
			clientThread.invokeLater(() -> {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "MindRune has stopped creating memories!", null);
			});
		}
		startDataSender();
	}

	public String stripColorTags(String input) {
		return input.replaceAll("<col=[0-9a-fA-F]+>", "").trim();
	}

	private void logEvent(String eventType, JsonObject details) {
		JsonObject json = new JsonObject();
		json.addProperty("eventType", eventType);
		json.addProperty("timestamp", Instant.now().toString());

		// Capture player's location (if available)
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null) {
			WorldPoint location = localPlayer.getWorldLocation();
			JsonObject playerLocation = new JsonObject();
			playerLocation.addProperty("x", location.getX());
			playerLocation.addProperty("y", location.getY());
			playerLocation.addProperty("plane", location.getPlane());
			json.add("playerLocation", playerLocation);
		}

		json.add("details", details);
		eventLog.add(json);
	}

	// Chat Message Tracking
	@Subscribe
	public void onChatMessage(ChatMessage event) {

		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM) {
			String message = event.getMessage();
			// Quest completion messages typically follow the pattern "Congratulations, you've completed [Quest Name]!"
			if (message.contains("Congratulations") && message.contains("completed") && message.contains("Quest")) {
				JsonObject details = new JsonObject();

				// Try to extract the quest name from the message
				// This is an approximation and might need adjusting
				String questName = message.substring(message.indexOf("completed") + 10);
				questName = questName.substring(0, questName.indexOf("!"));

				details.addProperty("questName", questName.trim());
				details.addProperty("message", message);

				logEvent("QUEST_COMPLETION", details);
			}
		}

		// Added from onGameMessage - Achievement diary tracking
		if (event.getType() == ChatMessageType.GAMEMESSAGE ||
				event.getType() == ChatMessageType.SPAM) {

			String message = event.getMessage();

			// Diary completion messages
			if ((message.contains("completed") || message.contains("Completed")) &&
					(message.contains("diary") || message.contains("Diary"))) {

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

				logEvent("ACHIEVEMENT_DIARY_COMPLETION", details);
			}

			// Combat achievement messages
			if (message.contains("Combat Achievement") || message.contains("combat achievement")) {
				JsonObject details = new JsonObject();
				details.addProperty("message", message);

				// Try to extract achievement name
				String achievementName = message;
				if (message.contains(":")) {
					achievementName = message.substring(message.indexOf(":") + 1).trim();
				}

				details.addProperty("achievementName", achievementName);
				logEvent("COMBAT_ACHIEVEMENT_COMPLETION", details);
			}
		}
	}

	// XP Tracking
	private final Map<Skill, Integer> previousXp = new HashMap<>();
	private final List<Integer> previousInventory = new ArrayList<>();

	@Subscribe
	public void onGameTick(GameTick event) {
		// Populate previousXp once at startup
		if (previousXp.isEmpty()) {
			for (Skill skill : Skill.values()) {
				previousXp.put(skill, client.getSkillExperience(skill)); // Store initial XP
			}
		}

		// Populate previousInventory once at startup (initialize only if empty)
		if (previousInventory.isEmpty()) {
			ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
			if (container != null) {
				for (Item item : container.getItems()) {
					previousInventory.add(item.getId()); // Store current inventory items
				}
			}
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

				logEvent("XP_GAIN", details);
			}
		}

		// Always update the stored XP value
		previousXp.put(skill, newXp);
	}

	// Inventory Changes
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		// Only process inventory changes
		if (event.getContainerId() != InventoryID.INVENTORY.getId()) {
			return;
		}

		// Get current inventory items
		ItemContainer container = event.getItemContainer();
		if (container == null) {
			return;
		}

		// Create a map of current item IDs to quantities
		Map<Integer, Integer> currentInventoryMap = new HashMap<>();
		for (Item item : container.getItems()) {
			if (item.getId() != -1) {  // Skip empty slots
				int itemId = item.getId();
				int quantity = item.getQuantity();

				// Add or update quantity in the map
				currentInventoryMap.put(itemId, currentInventoryMap.getOrDefault(itemId, 0) + quantity);
			}
		}

		// If this is the first inventory check (login), just save the inventory state without logging events
		if (previousInventory.isEmpty()) {
			// Initialize the previous inventory tracking list with both IDs and quantities
			for (Map.Entry<Integer, Integer> entry : currentInventoryMap.entrySet()) {
				previousInventory.add(entry.getKey());      // Item ID
				previousInventory.add(entry.getValue());    // Quantity
			}
			return;  // Exit without logging events for initial inventory
		}

		// Create a map of previous inventory for easier comparison
		Map<Integer, Integer> previousInventoryMap = new HashMap<>();
		for (int i = 0; i < previousInventory.size(); i += 2) {
			if (i + 1 < previousInventory.size()) {
				int itemId = previousInventory.get(i);
				int quantity = previousInventory.get(i + 1);
				previousInventoryMap.put(itemId, quantity);
			}
		}

		// Check for new items or quantity increases
		for (Map.Entry<Integer, Integer> entry : currentInventoryMap.entrySet()) {
			int itemId = entry.getKey();
			int currentQuantity = entry.getValue();
			int previousQuantity = previousInventoryMap.getOrDefault(itemId, 0);

			if (previousQuantity < currentQuantity) {
				// Only track actual increases
				ItemComposition itemComp = client.getItemDefinition(itemId);
				String itemName = itemComp != null ? itemComp.getName() : "Unknown Item";

				JsonObject details = new JsonObject();
				details.addProperty("itemId", itemId);
				details.addProperty("itemName", itemName);

				logEvent("INVENTORY_CHANGE", details);
			}
		}

		// Update the previous inventory tracking list with both IDs and quantities
		previousInventory.clear();
		for (Map.Entry<Integer, Integer> entry : currentInventoryMap.entrySet()) {
			previousInventory.add(entry.getKey());      // Item ID
			previousInventory.add(entry.getValue());    // Quantity
		}
	}

	// Combat Hits
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) {
		Player localPlayer = client.getLocalPlayer();
		Actor target = event.getActor();

		// Ensure the hit is related to the player or their targeted NPC
		if (target != null && (target == localPlayer || isPlayerInteractingWith(target))) {
			JsonObject details = new JsonObject();
			details.addProperty("target", stripColorTags(target.getName()));
			details.addProperty("damage", event.getHitsplat().getAmount());
			logEvent("HIT_SPLAT", details);
		}
	}

	// Helper method to check if the player is interacting with the given actor
	private boolean isPlayerInteractingWith(Actor target) {
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null) {
			return false;
		}

		Actor interacting = localPlayer.getInteracting();
		return interacting != null && interacting == target;
	}

	// World Change
	@Subscribe
	public void onWorldChanged(WorldChanged event) {
		JsonObject details = new JsonObject();
		logEvent("WORLD_CHANGE", details);
	}

	// Mouse Clicks & Object Interactions
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		JsonObject details = new JsonObject();
		details.addProperty("action", stripColorTags(event.getMenuOption()));
		details.addProperty("target", stripColorTags(event.getMenuTarget()));
		logEvent("MENU_CLICK", details);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		// Achievement diary completion interface
		if (event.getGroupId() == 153) {
			Widget achievementWidget = client.getWidget(153, 0);
			if (achievementWidget != null && !achievementWidget.isHidden()) {
				String[] widgetText = achievementWidget.getText().split("<br>");
				for (String line : widgetText) {
					if (line.contains("Congratulations")) {
						// Parse the achievement diary name
						String diaryText = line.replaceAll("<[^>]*>", "").trim();
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

							logEvent("ACHIEVEMENT_DIARY_COMPLETION", details);
						}
					}
				}
			}
		}

		// Combat Achievement interface
		if (event.getGroupId() == 717) {
			Widget combatWidget = client.getWidget(717, 0);
			if (combatWidget != null && !combatWidget.isHidden()) {
				Widget[] children = combatWidget.getChildren();
				if (children != null) {
					for (Widget child : children) {
						String text = child.getText();
						if (text != null && (text.contains("completed") || text.contains("Completed"))) {
							String achievementText = text.replaceAll("<[^>]*>", "").trim();

							JsonObject details = new JsonObject();
							details.addProperty("achievementName", achievementText);
							details.addProperty("message", text);

							logEvent("COMBAT_ACHIEVEMENT_COMPLETION", details);
						}
					}
				}
			}
		}
	}

	// Send Data Periodically
	private void startDataSender() {
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				sendEventData();
			}
		}, 0, 60000); // Send every 60 seconds
	}

	private void sendEventData() {
		if (eventLog.isEmpty()) return;

		HttpURLConnection conn = null;
		try {
			// Retrieve player details
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null) {
				log.warn("No local player found, skipping data send.");
				return;
			}

			JsonObject playerInfo = new JsonObject();
			playerInfo.addProperty("playerName", localPlayer.getName());
			playerInfo.addProperty("playerId", client.getAccountHash());

			// Add combat level
			playerInfo.addProperty("combatLevel", localPlayer.getCombatLevel());

			// Calculate and add total level and total XP
			int totalLevel = 0;
			long totalXp = 0;
			for (Skill skill : Skill.values()) {
				totalLevel += client.getRealSkillLevel(skill);
				totalXp += client.getSkillExperience(skill);
			}
			playerInfo.addProperty("totalLevel", totalLevel);
			playerInfo.addProperty("totalXp", totalXp);

			// Construct the final JSON structure
			List<JsonObject> finalPayload = new ArrayList<>();
			finalPayload.add(playerInfo);
			finalPayload.addAll(eventLog); // Append all event logs after player info

			// Convert event log to JSON string
			String jsonPayload = gson.toJson(finalPayload);

			// Open connection
			conn = (HttpURLConnection) new URL(API_URL).openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");

			// Retrieve registration key from config and set Authorization header
			String registrationKey = config.registrationKey();
			if (registrationKey != null && !registrationKey.isEmpty()) {
				conn.setRequestProperty("Authorization", "Bearer " + registrationKey);
			} else {
				log.warn("No registration key found. Request may be unauthorized.");
			}

			conn.setDoOutput(true);

			// Send JSON payload
			try (OutputStream os = conn.getOutputStream()) {
				byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
				os.write(input, 0, input.length);
			}

			// Read response
			int responseCode = conn.getResponseCode();
			if (responseCode == 200) {
				try (java.io.BufferedReader reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder responseStr = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						responseStr.append(line);
					}
					String responseBody = responseStr.toString();

					// Parse the JSON response to extract points
					int points = 0;
					try {
						JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
						if (responseJson.has("points")) {
							points = responseJson.get("points").getAsInt();
						}
					} catch (Exception e) {
						log.warn("Could not parse points from response", e);
					}

					if (config.enableChatNotifications()) {
						final int finalPoints = points; // Need a final variable for the lambda
						clientThread.invokeLater(() -> {
							client.addChatMessage(
									ChatMessageType.GAMEMESSAGE,
									"",
									"New MindRune memory created! Points earned: " + finalPoints,
									null
							);
						});
					}
				}
			} else {
				// Log the error response body
				try (java.io.BufferedReader reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder errorResponse = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						errorResponse.append(line);
					}
					log.warn("Failed to send data, response code: {}, error: {}", responseCode, errorResponse.toString());
				} catch (Exception e) {
					log.error("Failed to read error response", e);
				}
			}
		} catch (Exception e) {
			log.error("Error sending event data", e);
		} finally {
			// Always clear the event log after attempting to send data
			eventLog.clear();
		}
	}
}