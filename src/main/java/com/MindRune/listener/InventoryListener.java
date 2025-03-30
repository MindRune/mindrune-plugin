package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listener for inventory-related events
 */
public class InventoryListener {
    private final Client client;
    private final EventLogService eventLogService;
    private final Map<Integer, Integer> previousInventoryMap = new HashMap<>();
    private final Map<Integer, Set<Integer>> previousPositionMap = new HashMap<>();
    private boolean isInitialized = false;

    // Event types for inventory changes
    private static final String EVENT_ITEM_ADDED = "INVENTORY_CHANGE";
    private static final String EVENT_ITEM_MOVED = "INVENTORY_CHANGE";

    public InventoryListener(Client client, EventLogService eventLogService) {
        this.client = client;
        this.eventLogService = eventLogService;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Initialize inventory tracking on the first game tick
        if (!isInitialized) {
            updatePreviousInventory();
            isInitialized = true;
        }
    }

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

        // If not yet initialized, just update the inventory state
        if (!isInitialized) {
            updatePreviousInventory();
            return;
        }

        // Create a map of current item IDs to quantities and positions
        Map<Integer, Integer> currentInventoryMap = new HashMap<>();
        Map<Integer, Set<Integer>> currentPositionMap = new HashMap<>();

        Item[] items = container.getItems();
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            if (item.getId() != -1) {  // Skip empty slots
                int itemId = item.getId();
                int quantity = item.getQuantity();

                // Track total quantities
                currentInventoryMap.put(itemId, currentInventoryMap.getOrDefault(itemId, 0) + quantity);

                // Track item positions
                if (!currentPositionMap.containsKey(itemId)) {
                    currentPositionMap.put(itemId, new HashSet<>());
                }
                currentPositionMap.get(itemId).add(i);
            }
        }

        // Check for new items or quantity increases
        for (Map.Entry<Integer, Integer> entry : currentInventoryMap.entrySet()) {
            int itemId = entry.getKey();
            int currentQuantity = entry.getValue();
            int previousQuantity = previousInventoryMap.getOrDefault(itemId, 0);

            if (previousQuantity < currentQuantity) {
                // Item was actually added (quantity increased)
                ItemComposition itemComp = client.getItemDefinition(itemId);
                String itemName = itemComp != null ? itemComp.getName() : "Unknown Item";

                JsonObject details = new JsonObject();
                details.addProperty("itemId", itemId);
                details.addProperty("itemName", itemName);
                details.addProperty("quantity", currentQuantity - previousQuantity);
                details.addProperty("changeType", "ADD");

                eventLogService.logEvent(EVENT_ITEM_ADDED, client, details);
            } else if (previousQuantity == currentQuantity) {
                // Same quantity, but check if positions changed
                Set<Integer> currentPositions = currentPositionMap.get(itemId);
                Set<Integer> previousPositions = previousPositionMap.getOrDefault(itemId, new HashSet<>());

                // Simple check: if positions are different but quantity is the same, it was moved
                if (!currentPositions.equals(previousPositions)) {
                    ItemComposition itemComp = client.getItemDefinition(itemId);
                    String itemName = itemComp != null ? itemComp.getName() : "Unknown Item";

                    JsonObject details = new JsonObject();
                    details.addProperty("itemId", itemId);
                    details.addProperty("itemName", itemName);
                    details.addProperty("quantity", currentQuantity);
                    details.addProperty("changeType", "MOVE");

                    // Optional: include position information
                    StringBuilder oldPositions = new StringBuilder();
                    for (Integer pos : previousPositions) {
                        oldPositions.append(pos).append(",");
                    }
                    StringBuilder newPositions = new StringBuilder();
                    for (Integer pos : currentPositions) {
                        newPositions.append(pos).append(",");
                    }

                    details.addProperty("oldPositions", oldPositions.toString());
                    details.addProperty("newPositions", newPositions.toString());

                    eventLogService.logEvent(EVENT_ITEM_MOVED, client, details);
                }
            }
        }

        // Update previous inventory state
        previousInventoryMap.clear();
        previousInventoryMap.putAll(currentInventoryMap);
        previousPositionMap.clear();
        previousPositionMap.putAll(currentPositionMap);
    }

    /**
     * Update the previous inventory state from the current inventory
     */
    private void updatePreviousInventory() {
        previousInventoryMap.clear();
        previousPositionMap.clear();

        ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
        if (container != null) {
            Item[] items = container.getItems();
            for (int i = 0; i < items.length; i++) {
                Item item = items[i];
                if (item.getId() != -1) {  // Skip empty slots
                    int itemId = item.getId();
                    int quantity = item.getQuantity();

                    // Track total quantities
                    previousInventoryMap.put(itemId, previousInventoryMap.getOrDefault(itemId, 0) + quantity);

                    // Track item positions
                    if (!previousPositionMap.containsKey(itemId)) {
                        previousPositionMap.put(itemId, new HashSet<>());
                    }
                    previousPositionMap.get(itemId).add(i);
                }
            }
        }
    }
}