package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprehensive listener for rewards from various sources in the game
 */
@Slf4j
public class RewardListener {
    private final Client client;
    private final EventLogService eventLogService;
    private final ClientThread clientThread;
    private final ItemManager itemManager;

    // Known reward interface IDs with their sources
    private final Map<Integer, String> rewardInterfaces = new HashMap<>();

    // Chat message patterns for different reward sources
    private final Map<Pattern, String> chatPatterns = new HashMap<>();

    // Map to track recent reward sources to their details
    private final Map<String, JsonObject> pendingRewards = new HashMap<>();

    // Inventory tracking system
    private InventoryID inventoryId;
    private Map<Integer, Integer> inventorySnapshot;
    private int inventoryTimeout;
    private static final int INVCHANGE_TIMEOUT = 10; // server ticks

    // Tracking for chest looting
    private boolean chestLooted;

    // Map regions for special events
    private static final Set<Integer> WINTERTODT_REGIONS = Set.of(6461);
    private static final Set<Integer> TEMPOROSS_REGIONS = Set.of(12588);
    private static final Set<Integer> GUARDIANS_OF_RIFT_REGIONS = Set.of(14484);
    private static final Set<Integer> HALLOWED_SEPULCHRE_REGIONS = Set.of(8797, 10077, 9308, 10074, 9050);
    private static final Set<Integer> TOB_REGIONS = Set.of(12867, 14642);
    private static final Set<Integer> HAM_STOREROOM_REGIONS = Set.of(10321);
    private static final Set<Integer> HESPORI_REGIONS = Set.of(5021);
    private static final Set<Integer> FONT_OF_CONSUMPTION_REGIONS = Set.of(12106);
    private static final Set<Integer> BA_LOBBY_REGIONS = Set.of(10039);
    private static final Set<Integer> SOUL_WARS_REGIONS = Set.of(8493, 8749, 9005);
    private static final Set<Integer> LAVA_MAZE_REGIONS = Set.of(12348);

    // Message patterns for specific rewards
    private static final Pattern CLUE_SCROLL_PATTERN = Pattern.compile("You have completed [0-9]+ ([a-z]+) Treasure Trails?\\.");
    private static final Pattern PICKPOCKET_PATTERN = Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");
    private static final Pattern BIRDHOUSE_PATTERN = Pattern.compile("You dismantle and discard the trap, retrieving (?:(?:a|\\d{1,2}) nests?, )?10 dead birds, \\d{1,3} feathers and (\\d,?\\d{1,3}) Hunter XP\\.");
    private static final Pattern HAM_CHEST_PATTERN = Pattern.compile("Your (?<key>[a-z]+) key breaks in the lock.*");
    private static final Pattern SHADE_CHEST_NO_KEY_PATTERN = Pattern.compile("You need a [a-z]+ key with a [a-z]+ trim to open this chest .*");
    private static final Pattern ROGUES_CHEST_PATTERN = Pattern.compile("You find (a|some)([a-z\\s]*) inside.");
    private static final Pattern LARRAN_LOOTED_PATTERN = Pattern.compile("You have opened Larran's (big|small) chest .*");

    // Common chat messages
    private static final String WINTERTODT_LOOT_STRING = "You found some loot: ";
    private static final String TEMPOROSS_LOOT_STRING = "You found some loot: ";
    private static final String GUARDIANS_OF_THE_RIFT_LOOT_STRING = "You found some loot: ";
    private static final String CHEST_LOOTED_MESSAGE = "You find some treasure in the chest!";
    private static final String OTHER_CHEST_LOOTED_MESSAGE = "You steal some loot from the chest.";
    private static final String DORGESH_KAAN_CHEST_LOOTED_MESSAGE = "You find treasure inside!";
    private static final String GRUBBY_CHEST_LOOTED_MESSAGE = "You have opened the Grubby Chest";
    private static final String ANCIENT_CHEST_LOOTED_MESSAGE = "You open the chest and find";
    private static final String HALLOWED_SEPULCHRE_COFFIN_MESSAGE = "You push the coffin lid aside.";
    private static final String HERBIBOAR_LOOTED_MESSAGE = "You harvest herbs from the herbiboar, whereupon it escapes.";
    private static final String HESPORI_LOOTED_MESSAGE = "You have successfully cleared this patch for new crops.";
    private static final String FONT_OF_CONSUMPTION_MESSAGE = "You place the Unsired into the Font of Consumption...";
    private static final String IMPLING_CATCH_MESSAGE = "You manage to catch the impling and acquire some loot.";

    // Event names
    private static final String BARROWS_EVENT = "Barrows";
    private static final String CHAMBERS_OF_XERIC_EVENT = "Chambers of Xeric";
    private static final String THEATRE_OF_BLOOD_EVENT = "Theatre of Blood";
    private static final String TOMBS_OF_AMASCUT_EVENT = "Tombs of Amascut";
    private static final String KINGDOM_EVENT = "Kingdom of Miscellania";
    private static final String FISHING_TRAWLER_EVENT = "Fishing Trawler";
    private static final String DRIFT_NET_EVENT = "Drift Net";
    private static final String WINTERTODT_EVENT = "Wintertodt";
    private static final String WINTERTODT_SUPPLY_CRATE_EVENT = "Supply crate (Wintertodt)";
    private static final String TEMPOROSS_EVENT = "Reward pool (Tempoross)";
    private static final String TEMPOROSS_CASKET_EVENT = "Casket (Tempoross)";
    private static final String GUARDIANS_OF_THE_RIFT_EVENT = "Guardians of the Rift";
    private static final String HERBIBOAR_EVENT = "Herbiboar";
    private static final String HESPORI_EVENT = "Hespori";
    private static final String BIRDNEST_EVENT = "Bird nest";
    private static final String CASKET_EVENT = "Casket";
    private static final String SEEDPACK_EVENT = "Seed pack";
    private static final String HALLOWED_SEPULCHRE_COFFIN_EVENT = "Coffin (Hallowed Sepulchre)";
    private static final String HALLOWED_SACK_EVENT = "Hallowed Sack";
    private static final String SPOILS_OF_WAR_EVENT = "Spoils of war";
    private static final String MAHOGANY_CRATE_EVENT = "Supply crate (Mahogany Homes)";
    private static final String BA_HIGH_GAMBLE_EVENT = "Barbarian Assault high gamble";
    private static final String ORE_PACK_VM_EVENT = "Ore Pack (Volcanic Mine)";

    // Map of chest regions to their names
    private final Map<Integer, String> CHEST_EVENT_TYPES = new HashMap<>();

    // Map of shade chest IDs to their key types
    private final Map<Integer, String> SHADE_CHEST_OBJECTS = new HashMap<>();

    // Map of birdhouse XP to their types
    private final Map<Integer, String> BIRDHOUSE_XP_TO_TYPE = new HashMap<>();

    // Set of item IDs that should trigger reward collection when used
    private final Set<Integer> rewardItemIds = new HashSet<>();

    // Set of bird nest item IDs
    private final Set<Integer> BIRDNEST_IDS = new HashSet<>();

    // Set of impling jar IDs
    private final Set<Integer> IMPLING_JARS = new HashSet<>();

    @Inject
    public RewardListener(Client client, EventLogService eventLogService, ClientThread clientThread, ItemManager itemManager) {
        this.client = client;
        this.eventLogService = eventLogService;
        this.clientThread = clientThread;
        this.itemManager = itemManager;

        // Initialize all data structures
        initRewardInterfaces();
        initChatPatterns();
        initChestEventTypes();
        initShadeChestObjects();
        initBirdhouseTypes();
        initRewardItemIds();
        initBirdNestIds();
        initImplingJars();

        log.info("RewardListener initialized");
    }

    /**
     * Track when reward interfaces are loaded
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();
        log.info("Widget loaded: Group ID {}", groupId);

        // Check if this is a known reward interface
        String rewardSource = rewardInterfaces.get(groupId);

        // If we identified a reward interface, process it after it's fully loaded
        if (rewardSource != null) {
            log.info("Found matching reward interface: {} -> {}", groupId, rewardSource);
            final String finalRewardSource = rewardSource;

            // Check for special cases like raids where we need to track if the chest was looted
            boolean isRaidsChest = CHAMBERS_OF_XERIC_EVENT.equals(finalRewardSource) ||
                    THEATRE_OF_BLOOD_EVENT.equals(finalRewardSource) ||
                    TOMBS_OF_AMASCUT_EVENT.equals(finalRewardSource);

            if (isRaidsChest && chestLooted) {
                log.info("Raid chest already looted, skipping");
                return; // Prevent duplicate rewards for raids
            }

            // Mark the chest as looted for tracking purposes
            if (isRaidsChest) {
                log.info("Setting chest looted flag for: {}", finalRewardSource);
                chestLooted = true;
            }

            // Process the interface
            clientThread.invokeLater(() -> {
                log.info("Processing reward interface for: {}", finalRewardSource);

                // Special handling for ToA to capture additional metadata
                Object metadata = null;
                if (TOMBS_OF_AMASCUT_EVENT.equals(finalRewardSource)) {
                    int raidLevel = client.getVarbitValue(Varbits.TOA_RAID_LEVEL);
                    int teamSize = calculateToATeamSize();
                    int raidDamage = client.getVarbitValue(Varbits.TOA_RAID_DAMAGE);
                    metadata = new int[]{raidLevel, teamSize, raidDamage};
                } else if (FISHING_TRAWLER_EVENT.equals(finalRewardSource) || DRIFT_NET_EVENT.equals(finalRewardSource)) {
                    metadata = client.getBoostedSkillLevel(Skill.FISHING);
                }

                processRewardInterface(groupId, finalRewardSource, metadata);
                return true;
            });
        }
    }

    /**
     * Calculate team size for Tombs of Amascut
     */
    private int calculateToATeamSize() {
        return Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1);
    }

    /**
     * Take a snapshot of the player's inventory to track changes
     */
    private void takeInventorySnapshot(String rewardSource) {
        takeInventorySnapshot(rewardSource, null);
    }

    /**
     * Take a snapshot of the player's inventory to track changes, with metadata
     */
    private void takeInventorySnapshot(String rewardSource, Object metadata) {
        log.info("Taking inventory snapshot for: {}", rewardSource);
        inventoryId = InventoryID.INVENTORY;
        inventorySnapshot = new HashMap<>();
        inventoryTimeout = INVCHANGE_TIMEOUT;

        final ItemContainer itemContainer = client.getItemContainer(inventoryId);
        if (itemContainer != null) {
            log.info("Current inventory items:");
            for (net.runelite.api.Item item : itemContainer.getItems()) {
                if (item.getId() > 0) {
                    inventorySnapshot.put(item.getId(), item.getQuantity());
                    ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                    String itemName = itemComp != null ? itemComp.getName() : "Unknown";
                    log.info("  - {}x{} ({})", itemName, item.getQuantity(), item.getId());
                }
            }
            log.info("Inventory snapshot complete: {} items", inventorySnapshot.size());
        } else {
            log.info("Failed to get item container for inventory snapshot");
        }

        // Store the reward source with pending rewards
        if (!pendingRewards.containsKey(rewardSource)) {
            log.info("Creating new pending reward for: {}", rewardSource);
            JsonObject details = new JsonObject();
            details.addProperty("rewardSource", rewardSource);
            details.addProperty("timestamp", System.currentTimeMillis());
            if (metadata != null) {
                if (metadata instanceof Integer) {
                    details.addProperty("skillLevel", (Integer)metadata);
                } else if (metadata instanceof int[]) {
                    int[] metadataArray = (int[])metadata;
                    if (metadataArray.length >= 3) {
                        details.addProperty("raidLevel", metadataArray[0]);
                        details.addProperty("teamSize", metadataArray[1]);
                        details.addProperty("raidDamage", metadataArray[2]);
                    }
                }
            }
            details.add("items", new JsonArray());
            pendingRewards.put(rewardSource, details);
        } else {
            log.info("Pending reward already exists for: {}", rewardSource);
        }
    }

    /**
     * Track inventory changes after taking a snapshot
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Clear wilderness chest looted flag when it's empty
        if (event.getContainerId() == InventoryID.WILDERNESS_LOOT_CHEST.getId()
                && Arrays.stream(event.getItemContainer().getItems()).noneMatch(i -> i.getId() > -1)) {
            log.info("Wilderness loot chest is now empty, resetting chest looted flag");
            chestLooted = false;
        }

        if (inventoryId == null || event.getContainerId() != inventoryId.getId()) {
            return;
        }

        log.info("Item container changed event for inventory (ID: {})", event.getContainerId());

        // Process inventory changes
        final ItemContainer container = event.getItemContainer();
        Map<Integer, Integer> currentInventory = new HashMap<>();
        JsonArray newItems = new JsonArray();
        Map<Integer, Integer> removedItems = new HashMap<>();

        // Build current inventory state
        for (net.runelite.api.Item item : container.getItems()) {
            if (item.getId() > 0) {
                currentInventory.put(item.getId(), item.getQuantity());
                log.info("Current inventory item: {}x{}", item.getId(), item.getQuantity());
            }
        }

        // Find items that were added to the inventory (new items or increased quantities)
        for (Map.Entry<Integer, Integer> entry : currentInventory.entrySet()) {
            int itemId = entry.getKey();
            int currentQty = entry.getValue();
            int previousQty = inventorySnapshot.getOrDefault(itemId, 0);

            if (currentQty > previousQty) {
                int gainedQty = currentQty - previousQty;
                ItemComposition itemComp = itemManager.getItemComposition(itemId);

                if (itemComp != null) {
                    log.info("Detected new item: {}x{} ({})",
                            itemComp.getName(), gainedQty, itemId);

                    JsonObject itemObj = new JsonObject();
                    itemObj.addProperty("itemId", itemId);
                    itemObj.addProperty("itemName", itemComp.getName());
                    itemObj.addProperty("quantity", gainedQty);
                    newItems.add(itemObj);
                } else {
                    log.info("Failed to get item composition for ID: {}", itemId);
                }
            }
        }

        // Find items that were removed from inventory (used items or decreased quantities)
        for (Map.Entry<Integer, Integer> entry : inventorySnapshot.entrySet()) {
            int itemId = entry.getKey();
            int previousQty = entry.getValue();
            int currentQty = currentInventory.getOrDefault(itemId, 0);

            if (currentQty < previousQty) {
                int lostQty = previousQty - currentQty;
                removedItems.put(itemId, lostQty);
                log.info("Item removed from inventory: {}x{}", itemId, lostQty);
            }
        }

        log.info("Found {} new items and {} removed items", newItems.size(), removedItems.size());

        // If we found any new items, add them to the pending reward
        if (newItems.size() > 0) {
            // Debug log all pending rewards
            log.info("Current pending rewards: {}", pendingRewards.keySet());

            // Find the pending reward to update
            boolean foundReward = false;
            for (Map.Entry<String, JsonObject> entry : pendingRewards.entrySet()) {
                String rewardSource = entry.getKey();
                JsonObject details = entry.getValue();

                // Only process recent pending rewards
                long timestamp = details.get("timestamp").getAsLong();
                long currentTime = System.currentTimeMillis();
                long timeDiff = currentTime - timestamp;

                log.info("Checking pending reward: {} (age: {}ms)", rewardSource, timeDiff);

                if (timeDiff < 10000) { // Within 10 seconds
                    log.info("Processing pending reward: {}", rewardSource);
                    JsonArray existingItems = details.getAsJsonArray("items");

                    // Add the new items to the existing items
                    for (int i = 0; i < newItems.size(); i++) {
                        existingItems.add(newItems.get(i));
                        log.info("Added item to pending reward");
                    }

                    // Log the event if we have items
                    if (existingItems.size() > 0) {
                        log.info("Finalizing reward with {} items", existingItems.size());
                        details.addProperty("itemCount", existingItems.size());
                        details.addProperty("rewardId", UUID.randomUUID().toString());
                        eventLogService.logEvent("REWARD", client, details);

                        // Remove this reward from pending since we've logged it
                        pendingRewards.remove(rewardSource);
                        log.info("Removed pending reward: {}", rewardSource);
                        foundReward = true;
                        break;
                    }
                } else {
                    log.info("Pending reward too old: {} ({} ms)", rewardSource, timeDiff);
                }
            }

            if (!foundReward) {
                log.info("No matching pending reward found for new items, creating generic reward");

                // Create a generic reward for items without a matching pending reward
                JsonObject details = new JsonObject();
                details.addProperty("rewardSource", "Unknown Reward");
                details.addProperty("timestamp", System.currentTimeMillis());
                details.add("items", newItems);
                details.addProperty("itemCount", newItems.size());
                details.addProperty("rewardId", UUID.randomUUID().toString());
                eventLogService.logEvent("REWARD", client, details);
            }
        }

        // Special case handling for stackable items like impling jars
        for (Map.Entry<Integer, Integer> entry : removedItems.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();

            // Check if item is an impling jar
            if (IMPLING_JARS.contains(itemId)) {
                ItemComposition itemComp = itemManager.getItemComposition(itemId);
                if (itemComp != null) {
                    log.info("Processing removed impling jar: {}x{}", itemComp.getName(), quantity);

                    JsonObject details = new JsonObject();
                    details.addProperty("rewardSource", itemComp.getName());
                    details.addProperty("timestamp", System.currentTimeMillis());
                    details.add("items", newItems); // We got newItems from the inventory change
                    details.addProperty("itemCount", newItems.size());
                    details.addProperty("rewardId", UUID.randomUUID().toString());
                    eventLogService.logEvent("REWARD", client, details);
                }
            }
        }

        // Reset inventory tracking
        resetInventoryTracking();
    }

    /**
     * Recursively log the entire widget hierarchy
     */
    private void logWidgetHierarchy(Widget widget, int depth) {
        if (widget == null) return;

        // Create indent for better readability
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        // Log basic widget information
        log.info("{}Widget ID: {}, Type: {}, ContentType: {}",
                indent, widget.getId(), widget.getType(), widget.getContentType());

        // Log item information if present
        if (widget.getItemId() > 0) {
            log.info("{}  ItemID: {}, ItemQuantity: {}", indent, widget.getItemId(), widget.getItemQuantity());

            // Try to get item name
            try {
                ItemComposition itemComp = itemManager.getItemComposition(widget.getItemId());
                if (itemComp != null) {
                    log.info("{}  Item Name: {}", indent, itemComp.getName());
                }
            } catch (Exception e) {
                log.info("{}  Error getting item name: {}", indent, e.getMessage());
            }
        }

        // Log text if present
        if (widget.getText() != null && !widget.getText().isEmpty()) {
            log.info("{}  Text: '{}'", indent, widget.getText());
        }

        // Log size and position
        log.info("{}  Size: {}x{}, Position: ({},{})",
                indent, widget.getWidth(), widget.getHeight(), widget.getRelativeX(), widget.getRelativeY());

        // Recursively log all types of children

        // Regular children
        Widget[] children = widget.getChildren();
        if (children != null && children.length > 0) {
            log.info("{}  Children count: {}", indent, children.length);
            for (Widget child : children) {
                if (child != null) {
                    logWidgetHierarchy(child, depth + 1);
                }
            }
        }

        // Dynamic children
        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null && dynamicChildren.length > 0) {
            log.info("{}  Dynamic children count: {}", indent, dynamicChildren.length);
            for (Widget child : dynamicChildren) {
                if (child != null) {
                    logWidgetHierarchy(child, depth + 1);
                }
            }
        }

        // Static children
        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null && staticChildren.length > 0) {
            log.info("{}  Static children count: {}", indent, staticChildren.length);
            for (Widget child : staticChildren) {
                if (child != null) {
                    logWidgetHierarchy(child, depth + 1);
                }
            }
        }
    }

    /**
     * Process a reward interface to extract reward items
     */
    private void processRewardInterface(int interfaceId, String rewardSource, Object metadata) {
        log.info("Processing reward interface: {} for {}", interfaceId, rewardSource);

        Widget rootWidget = client.getWidget(interfaceId, 0);
        if (rootWidget == null || rootWidget.isHidden()) {
            log.info("Interface {} not found or hidden", interfaceId);
            return;
        }

        // Use a more comprehensive approach to extract items
        JsonArray items = new JsonArray();
        Set<String> processedWidgets = new HashSet<>();
        searchAllWidgetsForItems(rootWidget, items, processedWidgets);

        log.info("Extracted {} items from interface {}", items.size(), interfaceId);

        // Only proceed if we found items
        if (items.size() > 0) {
            processRewardItems(rewardSource, metadata, items);
        } else {
            log.info("No items found in interface, not logging reward");
        }
    }

    /**
     * Recursively search all widgets and their children for items
     * Uses a set to track processed widgets to avoid duplicates
     */
    private void searchAllWidgetsForItems(Widget widget, JsonArray items, Set<String> processedWidgets) {
        if (widget == null || widget.isHidden()) {
            return;
        }

        // Create a unique identifier for this widget that includes itemId and position
        // This prevents counting the same item twice while still allowing different items
        // at different positions to be counted
        String widgetKey = widget.getId() + "_" + widget.getItemId() + "_" + widget.getRelativeX() + "_" + widget.getRelativeY();

        // Skip if we've already processed this exact widget with this item
        if (processedWidgets.contains(widgetKey) && widget.getItemId() > 0) {
            return;
        }

        // Mark this widget as processed if it has an item
        if (widget.getItemId() > 0) {
            processedWidgets.add(widgetKey);
        }

        // Check if this widget has an item
        if (widget.getItemId() > 0 && widget.getItemQuantity() > 0) {
            // For Lunar Chest, let's log more details to debug
            if (widget.getId() >> 16 == 868) {
                log.info("Found item in Lunar Chest widget: ID={}, ItemID={}, Qty={}, Pos=({},{})",
                        widget.getId(), widget.getItemId(), widget.getItemQuantity(),
                        widget.getRelativeX(), widget.getRelativeY());
            }

            addItemToArray(widget.getItemId(), widget.getItemQuantity(), items);
        }

        // Recursively check all child widgets

        // Static children
        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) {
                searchAllWidgetsForItems(child, items, processedWidgets);
            }
        }

        // Regular children
        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                searchAllWidgetsForItems(child, items, processedWidgets);
            }
        }

        // Dynamic children
        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                searchAllWidgetsForItems(child, items, processedWidgets);
            }
        }
    }

    /**
     * Helper method to add an item to the JSON array
     * Combines quantities if the same item already exists
     */
    private void addItemToArray(int itemId, int quantity, JsonArray items) {
        // Skip placeholder items
        if (itemId <= 0 || quantity <= 0) {
            return;
        }

        // Try to find an existing item in the array
        for (int i = 0; i < items.size(); i++) {
            JsonObject existingItem = items.get(i).getAsJsonObject();
            if (existingItem.get("itemId").getAsInt() == itemId) {
                // If found, update the quantity
                int newQuantity = existingItem.get("quantity").getAsInt() + quantity;
                existingItem.addProperty("quantity", newQuantity);
                log.info("Updated existing item: {}x{} ({})",
                        existingItem.get("itemName").getAsString(), newQuantity, itemId);
                return;
            }
        }

        // If not found, add as a new item
        ItemComposition itemComp = itemManager.getItemComposition(itemId);
        if (itemComp != null) {
            log.info("Found new item: {}x{} ({})",
                    itemComp.getName(), quantity, itemId);

            JsonObject item = new JsonObject();
            item.addProperty("itemId", itemId);
            item.addProperty("itemName", itemComp.getName());
            item.addProperty("quantity", quantity);
            items.add(item);
        } else {
            log.info("Failed to get item composition for item ID: {}", itemId);
        }
    }

    /**
     * Helper method to process reward items
     */
    private void processRewardItems(String rewardSource, Object metadata, JsonArray items) {
        JsonObject details;

        // Check if we have a pending reward for this source
        if (pendingRewards.containsKey(rewardSource)) {
            // Update existing reward details
            log.info("Updating existing pending reward: {}", rewardSource);
            details = pendingRewards.get(rewardSource);
        } else {
            // Create a new reward event
            log.info("Creating new reward details for: {}", rewardSource);
            details = new JsonObject();
            details.addProperty("rewardSource", rewardSource);
            details.addProperty("timestamp", System.currentTimeMillis());
        }

        // Set the items and item count
        details.add("items", items);
        details.addProperty("itemCount", items.size());
        details.addProperty("rewardId", UUID.randomUUID().toString());

        // Add metadata if available
        if (metadata != null) {
            if (metadata instanceof Integer) {
                details.addProperty("skillLevel", (Integer)metadata);
            } else if (metadata instanceof int[]) {
                int[] metadataArray = (int[])metadata;
                if (metadataArray.length >= 3) {
                    details.addProperty("raidLevel", metadataArray[0]);
                    details.addProperty("teamSize", metadataArray[1]);
                    details.addProperty("raidDamage", metadataArray[2]);
                }
            }
        }

        // Log the event
        log.info("Logging REWARD event for {} with {} items", rewardSource, items.size());
        eventLogService.logEvent("REWARD", client, details);

        // Remove from pending map after processing
        pendingRewards.remove(rewardSource);
        log.info("Removed pending reward after processing: {}", rewardSource);
    }

    /**
     * Check if we should track inventory for a specific reward source
     */
    private boolean shouldTrackInventoryForSource(String source) {
        // List of sources where we should watch inventory
        List<String> inventoryTrackingSources = new ArrayList<>(Arrays.asList(
                WINTERTODT_EVENT,
                TEMPOROSS_EVENT,
                GUARDIANS_OF_THE_RIFT_EVENT,
                "Hallowed Sepulchre",
                HALLOWED_SEPULCHRE_COFFIN_EVENT,
                "Volcanic Mine",
                "Mahogany Homes",
                HERBIBOAR_EVENT,
                HESPORI_EVENT,
                "Clue Scroll (Beginner)",
                "Clue Scroll (Easy)",
                "Clue Scroll (Medium)",
                "Clue Scroll (Hard)",
                "Clue Scroll (Elite)",
                "Clue Scroll (Master)",
                CASKET_EVENT,
                SEEDPACK_EVENT,
                BA_HIGH_GAMBLE_EVENT
        ));

        // Also include all chest types EXCEPT ones with widgets
        for (String chestType : CHEST_EVENT_TYPES.values()) {
            // Skip "Lunar Chest" and any other chest types with widgets
            if (!chestType.equals("Lunar Chest")) {
                inventoryTrackingSources.add(chestType);
            }
        }

        // And all shade chest types
        for (String shadeChestType : SHADE_CHEST_OBJECTS.values()) {
            inventoryTrackingSources.add(shadeChestType);
        }

        // And all birdhouse types
        for (String birdhouseType : BIRDHOUSE_XP_TO_TYPE.values()) {
            inventoryTrackingSources.add(birdhouseType);
        }

        boolean result = inventoryTrackingSources.contains(source);
        log.info("Should track inventory for {}? {}", source, result);
        return result;
    }

    /**
     * Check if player is in one of the specified regions
     */
    private boolean isPlayerInRegion(Set<Integer> regions) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            log.info("Player is null, can't check region");
            return false;
        }

        final int[] mapRegions = client.getMapRegions();
        log.info("Checking player regions: {}", Arrays.toString(mapRegions));

        for (int region : mapRegions) {
            if (regions.contains(region)) {
                log.info("Player is in target region: {}", region);
                return true;
            }
        }

        return false;
    }

    /**
     * Track menu option clicks for reward items
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!event.isItemOp()) {
            return;
        }

        final int itemId = event.getItemId();
        final String option = event.getMenuOption();

        log.info("Menu option clicked: {} on item ID {}", option, itemId);

        // Shade chest opening
        if (isObjectOp(event.getMenuAction()) && option.equals("Open") && SHADE_CHEST_OBJECTS.containsKey(event.getId())) {
            String chestType = SHADE_CHEST_OBJECTS.get(event.getId());
            log.info("Opening shade chest: {}", chestType);
            takeInventorySnapshot(chestType);
            return;
        }

        // Seed pack opening
        if (itemId == 22866 && (option.equals("Take") || option.equals("Take-all"))) {
            log.info("Opening seed pack");
            takeInventorySnapshot(SEEDPACK_EVENT);
            return;
        }

        // Bird nest searching
        if (option.equals("Search") && BIRDNEST_IDS.contains(itemId)) {
            log.info("Searching bird nest");
            takeInventorySnapshot(BIRDNEST_EVENT);
            return;
        }

        // Check if this is a reward item being opened
        if (rewardItemIds.contains(itemId) &&
                (option.equals("Open") || option.equals("Search") || option.equals("Loot"))) {

            ItemComposition itemComp = itemManager.getItemComposition(itemId);
            if (itemComp != null) {
                log.info("Opening reward item: {}", itemComp.getName());
                takeInventorySnapshot(itemComp.getName());
            } else {
                log.info("Failed to get item composition for item: {}", itemId);
            }
        }

        // Handle special items by ID
        if (option.equals("Open")) {
            switch (itemId) {
                case 405: // Casket
                    log.info("Opening casket");
                    takeInventorySnapshot(CASKET_EVENT);
                    break;
                case 20703: // Supply crate (Wintertodt)
                case 24420: // Extra supply crate (Wintertodt)
                    log.info("Opening Wintertodt supply crate");
                    takeInventorySnapshot(WINTERTODT_SUPPLY_CRATE_EVENT);
                    break;
                case 23951: // Spoils of war (Soul Wars)
                    log.info("Opening spoils of war");
                    takeInventorySnapshot(SPOILS_OF_WAR_EVENT);
                    break;
                case 25590: // Casket (Tempoross)
                    log.info("Opening Tempoross casket");
                    takeInventorySnapshot(TEMPOROSS_CASKET_EVENT);
                    break;
                case 25516: // Hallowed sack
                    log.info("Opening hallowed sack");
                    takeInventorySnapshot(HALLOWED_SACK_EVENT);
                    break;
                case 24884: // Supply crate (Mahogany Homes)
                    log.info("Opening Mahogany Homes supply crate");
                    takeInventorySnapshot(MAHOGANY_CRATE_EVENT, client.getBoostedSkillLevel(Skill.CONSTRUCTION));
                    break;
                case 27693: // Ore pack (Volcanic Mine)
                    log.info("Opening ore pack");
                    takeInventorySnapshot(ORE_PACK_VM_EVENT);
                    break;
                // Bag of gems variants
                case 12109: // Bag full of gems (Percy)
                    log.info("Opening bag of gems (Percy)");
                    takeInventorySnapshot("Bag full of gems (Percy)");
                    break;
                case 24853: // Bag full of gems (Belona)
                    log.info("Opening bag of gems (Belona)");
                    takeInventorySnapshot("Bag full of gems (Belona)");
                    break;
                case 25537: // Bag full of gems (Dusuri)
                    log.info("Opening bag of gems (Dusuri)");
                    takeInventorySnapshot("Bag full of gems (Dusuri)");
                    break;
                // Various lockboxes and crates
                case 25647: // Simple lockbox
                case 25649: // Elaborate lockbox
                case 25651: // Ornate lockbox
                case 25638: // Cache of runes
                case 25642: // Intricate pouch
                case 25644: // Frozen cache
                    log.info("Opening reward container: {}", itemManager.getItemComposition(itemId).getName());
                    takeInventorySnapshot(itemManager.getItemComposition(itemId).getName());
                    break;
                // Hunter's loot sacks
                case 27606: // Hunter's loot sack
                case 28354: // Hunter's loot sack (basic)
                case 28355: // Hunter's loot sack (adept)
                case 28356: // Hunter's loot sack (expert)
                case 28357: // Hunter's loot sack (master)
                    handleHunterLootSacks(itemId);
                    break;
            }
        }

        // Handle impling jars
        if (option.equals("Loot") && IMPLING_JARS.contains(itemId)) {
            log.info("Looting impling jar: {}", itemManager.getItemComposition(itemId).getName());
            takeInventorySnapshot(itemManager.getItemComposition(itemId).getName());
        }
    }

    /**
     * Handle hunter's loot sacks which can be stacked
     */
    private void handleHunterLootSacks(int itemId) {
        ItemComposition itemComp = itemManager.getItemComposition(itemId);
        if (itemComp != null) {
            log.info("Opening hunter's loot sack: {}", itemComp.getName());

            // Create metadata with skill levels
            Map<String, Integer> metadata = new HashMap<>();
            metadata.put("WOODCUTTING", client.getBoostedSkillLevel(Skill.WOODCUTTING));
            metadata.put("HERBLORE", client.getBoostedSkillLevel(Skill.HERBLORE));
            metadata.put("HUNTER", client.getBoostedSkillLevel(Skill.HUNTER));

            JsonObject details = new JsonObject();
            details.addProperty("rewardSource", itemComp.getName());
            details.addProperty("timestamp", System.currentTimeMillis());
            details.addProperty("woodcuttingLevel", metadata.get("WOODCUTTING"));
            details.addProperty("herbloreLevel", metadata.get("HERBLORE"));
            details.addProperty("hunterLevel", metadata.get("HUNTER"));
            details.add("items", new JsonArray());

            pendingRewards.put(itemComp.getName(), details);
            takeInventorySnapshot(itemComp.getName());
        }
    }

    /**
     * Check if the menu action is for an object interaction
     */
    private boolean isObjectOp(MenuAction menuAction) {
        final int id = menuAction.getId();
        return (id >= MenuAction.GAME_OBJECT_FIRST_OPTION.getId() && id <= MenuAction.GAME_OBJECT_FOURTH_OPTION.getId())
                || id == MenuAction.GAME_OBJECT_FIFTH_OPTION.getId();
    }

    /**
     * Initialize the map of interface IDs to reward sources
     */
    private void initRewardInterfaces() {
        // Reward interfaces from widgets
        rewardInterfaces.put(InterfaceID.BARROWS_REWARD, BARROWS_EVENT);
        rewardInterfaces.put(InterfaceID.CHAMBERS_OF_XERIC_REWARD, CHAMBERS_OF_XERIC_EVENT);
        rewardInterfaces.put(InterfaceID.TOB_REWARD, THEATRE_OF_BLOOD_EVENT);
        rewardInterfaces.put(InterfaceID.TOA_REWARD, TOMBS_OF_AMASCUT_EVENT);
        rewardInterfaces.put(InterfaceID.KINGDOM, KINGDOM_EVENT);
        rewardInterfaces.put(InterfaceID.TRAWLER_REWARD, FISHING_TRAWLER_EVENT);
        rewardInterfaces.put(InterfaceID.DRIFT_NET_FISHING_REWARD, DRIFT_NET_EVENT);
        rewardInterfaces.put(InterfaceID.WILDERNESS_LOOT_CHEST, "Loot Chest");
        rewardInterfaces.put(InterfaceID.LUNAR_CHEST, "Lunar Chest");
        rewardInterfaces.put(InterfaceID.FORTIS_COLOSSEUM_REWARD, "Fortis Colosseum");

        // Additional interfaces
        rewardInterfaces.put(155, BARROWS_EVENT);
        rewardInterfaces.put(291, CHAMBERS_OF_XERIC_EVENT);
        rewardInterfaces.put(235, THEATRE_OF_BLOOD_EVENT);
        rewardInterfaces.put(289, TOMBS_OF_AMASCUT_EVENT);
        rewardInterfaces.put(81, KINGDOM_EVENT);
        rewardInterfaces.put(371, FISHING_TRAWLER_EVENT);
        rewardInterfaces.put(334, WINTERTODT_EVENT);
        rewardInterfaces.put(426, "Shooting Star");
        rewardInterfaces.put(422, "Brimstone Chest");
        rewardInterfaces.put(982, "Mahogany Homes");
        rewardInterfaces.put(540, "Giant's Foundry");
        rewardInterfaces.put(746, GUARDIANS_OF_THE_RIFT_EVENT);
        rewardInterfaces.put(611, "Volcanic Mine");
        rewardInterfaces.put(668, "Hallowed Sepulchre");
        rewardInterfaces.put(730, "Moons of Peril");
        rewardInterfaces.put(868, "Lunar Chest");

        log.info("Initialized {} reward interfaces", rewardInterfaces.size());
    }

    /**
     * Initialize the map of chat patterns to reward sources
     */
    private void initChatPatterns() {
        // Barrows
        chatPatterns.put(
                Pattern.compile("Your Barrows chest count is: (\\d+)"),
                BARROWS_EVENT
        );

        // Clue scroll completion message
        chatPatterns.put(
                Pattern.compile("You have completed (\\d+) ([\\w\\s]+) Treasure Trails"),
                "Treasure Trail"
        );

        // Tempoross
        chatPatterns.put(
                Pattern.compile("Reward permits: (\\d+)"),
                TEMPOROSS_EVENT
        );

        // Wintertodt
        chatPatterns.put(
                Pattern.compile("Your subdued the Wintertodt"),
                WINTERTODT_EVENT
        );

        // CoX
        chatPatterns.put(
                Pattern.compile("Challenge complete!"),
                CHAMBERS_OF_XERIC_EVENT
        );

        // ToB
        chatPatterns.put(
                Pattern.compile("Theatre of Blood total completion time:"),
                THEATRE_OF_BLOOD_EVENT
        );

        // ToA
        chatPatterns.put(
                Pattern.compile("Tombs of Amascut completed!"),
                TOMBS_OF_AMASCUT_EVENT
        );

        // Fishing Trawler
        chatPatterns.put(
                Pattern.compile("You have completed (\\d+) trips on the Fishing Trawler"),
                FISHING_TRAWLER_EVENT
        );

        // Kingdom
        chatPatterns.put(
                Pattern.compile("Kingdom Management: Collected resources"),
                KINGDOM_EVENT
        );

        // Shooting Stars
        chatPatterns.put(
                Pattern.compile("You hand over your stardust"),
                "Shooting Star"
        );

        // Mahogany Homes
        chatPatterns.put(
                Pattern.compile("You've completed (\\d+) Mahogany Homes contracts"),
                "Mahogany Homes"
        );

        // Giant's Foundry
        chatPatterns.put(
                Pattern.compile("You've completed (\\d+) Giant's Foundry commissions"),
                "Giant's Foundry"
        );

        // Guardians of the Rift
        chatPatterns.put(
                Pattern.compile("Elemental energy: (\\d+)"),
                GUARDIANS_OF_THE_RIFT_EVENT
        );

        // Volcanic Mine
        chatPatterns.put(
                Pattern.compile("The volcano erupts shortly after your escape!"),
                "Volcanic Mine"
        );

        // Hallowed Sepulchre
        chatPatterns.put(
                Pattern.compile("You've completed (\\d+) laps of the Hallowed Sepulchre"),
                "Hallowed Sepulchre"
        );

        // Moons of Peril
        chatPatterns.put(
                Pattern.compile("You have completed (\\d+) Moons of Peril"),
                "Moons of Peril"
        );

        // Lunar Chest
        chatPatterns.put(
                Pattern.compile("Your Lunar Chest count is:.*?(\\d+).*"),
                "Lunar Chest"
        );

        // Additional patterns for various chests
        chatPatterns.put(LARRAN_LOOTED_PATTERN, "Larran's chest");
        chatPatterns.put(ROGUES_CHEST_PATTERN, "Rogues' Chest");

        log.info("Initialized {} chat patterns", chatPatterns.size());
    }

    /**
     * Initialize the map of region IDs to chest types
     */
    private void initChestEventTypes() {
        CHEST_EVENT_TYPES.put(5179, "Brimstone Chest");
        CHEST_EVENT_TYPES.put(11573, "Crystal Chest");
        CHEST_EVENT_TYPES.put(12093, "Larran's big chest");
        CHEST_EVENT_TYPES.put(12127, "The Gauntlet");
        CHEST_EVENT_TYPES.put(13113, "Larran's small chest");
        CHEST_EVENT_TYPES.put(13151, "Elven Crystal Chest");
        CHEST_EVENT_TYPES.put(5277, "Stone chest");
        CHEST_EVENT_TYPES.put(10835, "Dorgesh-Kaan Chest");
        CHEST_EVENT_TYPES.put(10834, "Dorgesh-Kaan Chest");
        CHEST_EVENT_TYPES.put(7323, "Grubby Chest");
        CHEST_EVENT_TYPES.put(8593, "Isle of Souls Chest");
        CHEST_EVENT_TYPES.put(7827, "Dark Chest");
        CHEST_EVENT_TYPES.put(13117, "Rogues' Chest");
        CHEST_EVENT_TYPES.put(13156, "Chest (Ancient Vault)");
        CHEST_EVENT_TYPES.put(LAVA_MAZE_REGIONS.iterator().next(), "Muddy Chest");
        CHEST_EVENT_TYPES.put(5422, "Chest (Aldarin Villas)");
        CHEST_EVENT_TYPES.put(6550, "Chest (Moon key)");

        log.info("Initialized {} chest types", CHEST_EVENT_TYPES.size());
    }

    /**
     * Initialize the map of object IDs to shade chest types
     */
    private void initShadeChestObjects() {
        SHADE_CHEST_OBJECTS.put(ObjectID.BRONZE_CHEST, "Bronze key red");
        SHADE_CHEST_OBJECTS.put(ObjectID.BRONZE_CHEST_4112, "Bronze key brown");
        SHADE_CHEST_OBJECTS.put(ObjectID.BRONZE_CHEST_4113, "Bronze key crimson");
        SHADE_CHEST_OBJECTS.put(ObjectID.BRONZE_CHEST_4114, "Bronze key black");
        SHADE_CHEST_OBJECTS.put(ObjectID.BRONZE_CHEST_4115, "Bronze key purple");
        SHADE_CHEST_OBJECTS.put(ObjectID.STEEL_CHEST, "Steel key red");
        SHADE_CHEST_OBJECTS.put(ObjectID.STEEL_CHEST_4117, "Steel key brown");
        SHADE_CHEST_OBJECTS.put(ObjectID.STEEL_CHEST_4118, "Steel key crimson");
        SHADE_CHEST_OBJECTS.put(ObjectID.STEEL_CHEST_4119, "Steel key black");
        SHADE_CHEST_OBJECTS.put(ObjectID.STEEL_CHEST_4120, "Steel key purple");
        SHADE_CHEST_OBJECTS.put(ObjectID.BLACK_CHEST, "Black key red");
        SHADE_CHEST_OBJECTS.put(ObjectID.BLACK_CHEST_4122, "Black key brown");
        SHADE_CHEST_OBJECTS.put(ObjectID.BLACK_CHEST_4123, "Black key crimson");
        SHADE_CHEST_OBJECTS.put(ObjectID.BLACK_CHEST_4124, "Black key black");
        SHADE_CHEST_OBJECTS.put(ObjectID.BLACK_CHEST_4125, "Black key purple");
        SHADE_CHEST_OBJECTS.put(ObjectID.SILVER_CHEST, "Silver key red");
        SHADE_CHEST_OBJECTS.put(ObjectID.SILVER_CHEST_4127, "Silver key brown");
        SHADE_CHEST_OBJECTS.put(ObjectID.SILVER_CHEST_4128, "Silver key crimson");
        SHADE_CHEST_OBJECTS.put(ObjectID.SILVER_CHEST_4129, "Silver key black");
        SHADE_CHEST_OBJECTS.put(ObjectID.SILVER_CHEST_4130, "Silver key purple");
        SHADE_CHEST_OBJECTS.put(ObjectID.GOLD_CHEST, "Gold key red");
        SHADE_CHEST_OBJECTS.put(ObjectID.GOLD_CHEST_41213, "Gold key brown");
        SHADE_CHEST_OBJECTS.put(ObjectID.GOLD_CHEST_41214, "Gold key crimson");
        SHADE_CHEST_OBJECTS.put(ObjectID.GOLD_CHEST_41215, "Gold key black");
        SHADE_CHEST_OBJECTS.put(ObjectID.GOLD_CHEST_41216, "Gold key purple");

        log.info("Initialized {} shade chest types", SHADE_CHEST_OBJECTS.size());
    }

    /**
     * Initialize the map of XP values to birdhouse types
     */
    private void initBirdhouseTypes() {
        BIRDHOUSE_XP_TO_TYPE.put(280, "Regular Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(420, "Oak Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(560, "Willow Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(700, "Teak Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(820, "Maple Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(960, "Mahogany Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(1020, "Yew Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(1140, "Magic Bird House");
        BIRDHOUSE_XP_TO_TYPE.put(1200, "Redwood Bird House");

        log.info("Initialized {} birdhouse types", BIRDHOUSE_XP_TO_TYPE.size());
    }

    /**
     * Initialize item IDs that should trigger reward collection when used/opened
     */
    private void initRewardItemIds() {
        // Supply crates
        rewardItemIds.add(20703); // Supply crate (Wintertodt)
        rewardItemIds.add(24884); // Supply crate (Mahogany Homes)

        // Caskets
        rewardItemIds.add(405);   // Casket
        rewardItemIds.add(25590); // Casket (Tempoross)

        // Reward items from minigames
        rewardItemIds.add(23951); // Spoils of war (Soul Wars)
        rewardItemIds.add(25516); // Hallowed sack (Hallowed Sepulchre)

        // Chests and keys
        rewardItemIds.add(21511); // Brimstone key
        rewardItemIds.add(23083); // Konar's chest

        // Bags of gems
        rewardItemIds.add(12109); // Bag full of gems (Percy)
        rewardItemIds.add(24853); // Bag full of gems (Belona)
        rewardItemIds.add(25537); // Bag full of gems (Dusuri)

        // Seed pack
        rewardItemIds.add(22866); // Seed pack

        // Ore packs
        rewardItemIds.add(27693); // Ore pack (Volcanic Mine)

        // Hunter's loot sacks
        rewardItemIds.add(27606); // Hunter's loot sack
        rewardItemIds.add(28354); // Hunter's loot sack (basic)
        rewardItemIds.add(28355); // Hunter's loot sack (adept)
        rewardItemIds.add(28356); // Hunter's loot sack (expert)
        rewardItemIds.add(28357); // Hunter's loot sack (master)

        // Various lockboxes and crates
        rewardItemIds.add(25647); // Simple lockbox
        rewardItemIds.add(25649); // Elaborate lockbox
        rewardItemIds.add(25651); // Ornate lockbox
        rewardItemIds.add(25638); // Cache of runes
        rewardItemIds.add(25642); // Intricate pouch
        rewardItemIds.add(25644); // Frozen cache

        // Bounty crates
        for (int i = 27417; i <= 27425; i++) { // Bounty crate tier 1-9
            rewardItemIds.add(i);
        }

        // Potion packs
        rewardItemIds.add(27291); // Apprentice potion pack
        rewardItemIds.add(27293); // Adept potion pack
        rewardItemIds.add(27295); // Expert potion pack

        log.info("Initialized {} reward item IDs", rewardItemIds.size());
    }

    /**
     * Initialize bird nest item IDs
     */
    private void initBirdNestIds() {
        BIRDNEST_IDS.add(5070); // Bird nest
        BIRDNEST_IDS.add(5071); // Bird nest (red eggs)
        BIRDNEST_IDS.add(5072); // Bird nest (blue eggs)
        BIRDNEST_IDS.add(5073); // Bird nest (green eggs)
        BIRDNEST_IDS.add(5074); // Bird nest (seeds)
        BIRDNEST_IDS.add(7413); // Bird nest (ring)
        BIRDNEST_IDS.add(13653); // Bird nest (clue)
        BIRDNEST_IDS.add(22798); // Bird nest (Wyson)
        BIRDNEST_IDS.add(22800); // Bird nest (seaweed)

        log.info("Initialized {} bird nest IDs", BIRDNEST_IDS.size());
    }

    /**
     * Initialize impling jar IDs
     */
    private void initImplingJars() {
        IMPLING_JARS.add(11238); // Baby impling jar
        IMPLING_JARS.add(11240); // Young impling jar
        IMPLING_JARS.add(11242); // Gourmet impling jar
        IMPLING_JARS.add(11244); // Earth impling jar
        IMPLING_JARS.add(11246); // Essence impling jar
        IMPLING_JARS.add(11248); // Eclectic impling jar
        IMPLING_JARS.add(11250); // Nature impling jar
        IMPLING_JARS.add(11252); // Magpie impling jar
        IMPLING_JARS.add(11254); // Ninja impling jar
        IMPLING_JARS.add(23748); // Crystal impling jar
        IMPLING_JARS.add(11256); // Dragon impling jar
        IMPLING_JARS.add(19732); // Lucky impling jar

        log.info("Initialized {} impling jar IDs", IMPLING_JARS.size());
    }

    /**
     * Reset the inventory tracking system
     */
    private void resetInventoryTracking() {
        log.info("Resetting inventory tracking");
        inventoryId = null;
        inventorySnapshot = null;
        inventoryTimeout = 0;
    }

    @Subscribe
    public void onGameStateChanged(final GameStateChanged event) {
        // Reset chest looted flag when loading into a new area
        if (event.getGameState() == GameState.LOADING) {
            log.info("Game state changed to LOADING, resetting chest looted flag");
            chestLooted = false;
        }
    }

    /**
     * Track chat messages that might indicate rewards
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE &&
                event.getType() != ChatMessageType.SPAM &&
                event.getType() != ChatMessageType.MESBOX) {
            return;
        }

        String message = event.getMessage();
        Player player = client.getLocalPlayer();
        if (player == null) {
            return;
        }

        log.info("Processing chat message: '{}' (Type: {})", message, event.getType());
        final int regionID = player.getWorldLocation().getRegionID();

        // Check special reward messages based on region
        if (isPlayerInRegion(WINTERTODT_REGIONS) && message.contains(WINTERTODT_LOOT_STRING)) {
            log.info("Detected Wintertodt loot message");
            takeInventorySnapshot(WINTERTODT_EVENT, client.getBoostedSkillLevel(Skill.FIREMAKING));
            return;
        }

        if (isPlayerInRegion(TEMPOROSS_REGIONS) && message.startsWith(TEMPOROSS_LOOT_STRING)) {
            log.info("Detected Tempoross loot message");
            takeInventorySnapshot(TEMPOROSS_EVENT, client.getBoostedSkillLevel(Skill.FISHING));
            return;
        }

        if (isPlayerInRegion(GUARDIANS_OF_RIFT_REGIONS) && message.startsWith(GUARDIANS_OF_THE_RIFT_LOOT_STRING)) {
            log.info("Detected Guardians of the Rift loot message");
            takeInventorySnapshot(GUARDIANS_OF_THE_RIFT_EVENT, client.getBoostedSkillLevel(Skill.RUNECRAFT));
            return;
        }

        // Check for herbiboar message
        if (message.equals(HERBIBOAR_LOOTED_MESSAGE)) {
            log.info("Detected Herbiboar loot message");
            takeInventorySnapshot(HERBIBOAR_EVENT, client.getBoostedSkillLevel(Skill.HERBLORE));
            return;
        }

        // Check for Hespori message
        if (isPlayerInRegion(HESPORI_REGIONS) && message.equals(HESPORI_LOOTED_MESSAGE)) {
            log.info("Detected Hespori loot message");
            takeInventorySnapshot(HESPORI_EVENT);
            return;
        }

        // Check for Font of Consumption message
        if (isPlayerInRegion(FONT_OF_CONSUMPTION_REGIONS) && message.equals(FONT_OF_CONSUMPTION_MESSAGE)) {
            log.info("Detected Font of Consumption message");
            takeInventorySnapshot("Unsired");
            return;
        }

        // Check for impling catch message
        if (message.equals(IMPLING_CATCH_MESSAGE)) {
            log.info("Detected impling catch message");
            if (client.getLocalPlayer().getInteracting() != null) {
                takeInventorySnapshot(client.getLocalPlayer().getInteracting().getName());
            }
            return;
        }

        // Check for chest loot messages
        if (message.equals(CHEST_LOOTED_MESSAGE) || message.equals(OTHER_CHEST_LOOTED_MESSAGE)
                || message.equals(DORGESH_KAAN_CHEST_LOOTED_MESSAGE) || message.startsWith(GRUBBY_CHEST_LOOTED_MESSAGE)
                || message.startsWith(ANCIENT_CHEST_LOOTED_MESSAGE)
                || LARRAN_LOOTED_PATTERN.matcher(message).matches() || ROGUES_CHEST_PATTERN.matcher(message).matches()) {

            String chestType = CHEST_EVENT_TYPES.get(regionID);
            if (chestType != null) {
                log.info("Detected chest loot message for: {}", chestType);
                takeInventorySnapshot(chestType);
                return;
            }
        }

        // Check for Hallowed Sepulchre coffin message
        if (message.equals(HALLOWED_SEPULCHRE_COFFIN_MESSAGE) && isPlayerInRegion(HALLOWED_SEPULCHRE_REGIONS)) {
            log.info("Detected Hallowed Sepulchre coffin message");
            takeInventorySnapshot(HALLOWED_SEPULCHRE_COFFIN_EVENT);
            return;
        }

        // Check for HAM chest
        Matcher hamStoreroomMatcher = HAM_CHEST_PATTERN.matcher(message);
        if (hamStoreroomMatcher.matches() && isPlayerInRegion(HAM_STOREROOM_REGIONS)) {
            String keyType = hamStoreroomMatcher.group("key");
            log.info("Detected HAM chest message for key: {}", keyType);
            takeInventorySnapshot(String.format("H.A.M. chest (%s)", keyType));
            return;
        }

        // Check for pickpocket
        Matcher pickpocketMatcher = PICKPOCKET_PATTERN.matcher(message);
        if (pickpocketMatcher.matches()) {
            String pickpocketTarget = pickpocketMatcher.group("target");
            if (pickpocketTarget != null) {
                log.info("Detected pickpocket message for: {}", pickpocketTarget);
                takeInventorySnapshot("Pickpocket: " + pickpocketTarget);
                return;
            }
        }

        // Check for Barbarian Assault high gamble
        if (isPlayerInRegion(BA_LOBBY_REGIONS) && event.getType() == ChatMessageType.MESBOX
                && message.contains("High level gamble count:")) {
            log.info("Detected BA high gamble message");
            takeInventorySnapshot(BA_HIGH_GAMBLE_EVENT);
            return;
        }

        // Check if message is for a clue scroll reward
        Matcher clueMatcher = CLUE_SCROLL_PATTERN.matcher(message);
        if (clueMatcher.find()) {
            final String type = clueMatcher.group(1).toLowerCase();
            String eventType;
            switch (type) {
                case "beginner":
                    eventType = "Clue Scroll (Beginner)";
                    break;
                case "easy":
                    eventType = "Clue Scroll (Easy)";
                    break;
                case "medium":
                    eventType = "Clue Scroll (Medium)";
                    break;
                case "hard":
                    eventType = "Clue Scroll (Hard)";
                    break;
                case "elite":
                    eventType = "Clue Scroll (Elite)";
                    break;
                case "master":
                    eventType = "Clue Scroll (Master)";
                    break;
                default:
                    log.info("Unrecognized clue type: {}", type);
                    return;
            }

            log.info("Detected clue scroll completion: {}", eventType);
            takeInventorySnapshot(eventType);
            return;
        }

        // Check if message is a birdhouse type
        Matcher birdhouseMatcher = BIRDHOUSE_PATTERN.matcher(message);
        if (birdhouseMatcher.matches()) {
            try {
                String xpStr = birdhouseMatcher.group(1).replace(",", "");
                final int xp = Integer.parseInt(xpStr);
                final String type = BIRDHOUSE_XP_TO_TYPE.get(xp);
                if (type != null) {
                    log.info("Detected birdhouse: {}", type);
                    takeInventorySnapshot(type, client.getBoostedSkillLevel(Skill.HUNTER));
                    return;
                } else {
                    log.info("Unknown bird house type for XP: {}", xp);
                }
            } catch (NumberFormatException e) {
                log.info("Error parsing birdhouse XP: {}", e.getMessage());
            }
        }

        // Check shade chest messages
        if (SHADE_CHEST_NO_KEY_PATTERN.matcher(message).matches()) {
            log.info("Player didn't have the key they needed for shade chest");
            resetInventoryTracking();
            return;
        }

        // Check all other patterns
        for (Map.Entry<Pattern, String> entry : chatPatterns.entrySet()) {
            Matcher matcher = entry.getKey().matcher(message);
            if (matcher.find()) {
                String rewardSource = entry.getValue();
                log.info("Chat pattern matched: {} -> {}", matcher.group(0), rewardSource);

                // Extract completion count if available (group 1)
                int completionCount = 0;
                try {
                    if (matcher.groupCount() >= 1) {
                        completionCount = Integer.parseInt(matcher.group(1));
                        log.info("Extracted completion count: {}", completionCount);
                    }
                } catch (NumberFormatException e) {
                    // Not a number or no group, ignore
                    log.info("Failed to parse completion count: {}", e.getMessage());
                }

                // Create reward details
                JsonObject details = new JsonObject();
                details.addProperty("rewardSource", rewardSource);
                details.addProperty("completionCount", completionCount);
                details.addProperty("message", message);
                details.addProperty("timestamp", System.currentTimeMillis());

                // Initialize empty items array
                details.add("items", new JsonArray());

                // Store reward details for this source
                pendingRewards.put(rewardSource, details);
                log.info("Created pending reward for: {}", rewardSource);

                // For some sources, we know we should track inventory changes
                if (shouldTrackInventoryForSource(rewardSource)) {
                    log.info("Taking inventory snapshot for: {}", rewardSource);
                    takeInventorySnapshot(rewardSource);
                }

                break; // Only match one pattern per message
            }
        }
    }
}