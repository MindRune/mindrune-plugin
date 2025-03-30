package com.MindRune.listener;

import com.MindRune.service.EventLogService;
import com.MindRune.util.TextUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.ItemComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.AnimationChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Listener for monster death and loot events, inspired by RuneLite's LootTracker plugin
 * with extended functionality for detailed event logging.
 */
@Slf4j
public class MonsterKillListener {
    private final Client client;
    private final EventLogService eventLogService;
    private final ClientThread clientThread;

    // Track kills and their associated loot
    private final Map<String, KillInfo> activeKills = new HashMap<>();

    // Track kill locations for loot association
    private final Map<WorldPoint, String> killLocations = new HashMap<>();

    // How long to keep tracking a kill for its loot (ticks)
    private static final int LOOT_TRACKING_TIMEOUT = 10; // About 6 seconds

    // Minimum time to wait before finalizing a kill (ticks)
    private static final int MIN_TRACKING_TIME = 3; // About 1.8 seconds

    // Ground items tracking for special cases
    private String groundSnapshotName;
    private int groundSnapshotCombatLevel;
    private Multiset<Integer> groundSnapshot;
    private int groundSnapshotCycleDelay;
    private int groundSnapshotRegion;

    // Last interacted NPC (for special cases)
    private int lastNpcTypeTarget;
    private String lastMenuOption;

    // Class to store kill information
    private static class KillInfo {
        String killId;
        JsonObject details;
        JsonArray items = new JsonArray();
        int ticksSinceKill = 0;
        boolean finalized = false;
        // Adding a WorldPoint to more accurately track the kill location
        WorldPoint killLocation;

        KillInfo(String killId, JsonObject details, WorldPoint killLocation) {
            this.killId = killId;
            this.details = details;
            this.killLocation = killLocation;
        }

        void addItem(JsonObject item) {
            items.add(item);
        }
    }

    @Inject
    public MonsterKillListener(Client client, EventLogService eventLogService, ClientThread clientThread) {
        this.client = client;
        this.eventLogService = eventLogService;
        this.clientThread = clientThread;
    }

    /**
     * Update kill tracking every tick
     */
    @Subscribe
    public void onGameTick(GameTick tick) {
        // Update tick counters and finalize old kills
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, KillInfo> entry : activeKills.entrySet()) {
            KillInfo info = entry.getValue();
            info.ticksSinceKill++;

            // Log any kills that are old enough to have collected all loot
            if (info.ticksSinceKill >= MIN_TRACKING_TIME && !info.finalized) {
                // Only finalize if we have items or it's past the timeout
                if (info.items.size() > 0 || info.ticksSinceKill >= LOOT_TRACKING_TIMEOUT) {
                    finalizeKill(entry.getKey(), info);
                }
            }

            // Mark for removal if past the tracking timeout
            if (info.ticksSinceKill >= LOOT_TRACKING_TIMEOUT) {
                toRemove.add(entry.getKey());
            }
        }

        // Remove old kills
        for (String killId : toRemove) {
            KillInfo info = activeKills.remove(killId);

            // If this kill never got finalized (shouldn't happen normally), do it now
            if (!info.finalized) {
                finalizeKill(killId, info);
            }
        }

        // Clean up old location mappings that no longer have an active kill
        List<WorldPoint> locationsToRemove = new ArrayList<>();
        for (Map.Entry<WorldPoint, String> entry : killLocations.entrySet()) {
            if (!activeKills.containsKey(entry.getValue())) {
                locationsToRemove.add(entry.getKey());
            }
        }

        for (WorldPoint location : locationsToRemove) {
            killLocations.remove(location);
        }

        // Handle ground item snapshot for special cases like The Whisperer
        // (using the technique from LootTrackerPlugin)
        if (groundSnapshotCycleDelay > 0) {
            groundSnapshotCycleDelay--;

            if (groundSnapshotCycleDelay == 0) {
                log.debug("Ground snapshot: Loot timeout");
                groundSnapshotName = null;
                groundSnapshotCombatLevel = 0;
                groundSnapshot = null;
                return;
            }

            var region = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).getRegionID();
            if (region != groundSnapshotRegion) {
                log.debug("Ground snapshot: In wrong region {} != {}", region, groundSnapshotRegion);
                return;
            }

            Multiset<Integer> ground = HashMultiset.create();
            var scene = client.getScene();
            Tile[][] p0 = scene.getTiles()[0];
            Arrays.stream(p0)
                    .flatMap(Arrays::stream)
                    .filter(Objects::nonNull)
                    .map(Tile::getGroundItems)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .forEach(item -> ground.add(item.getId(), item.getQuantity()));

            var diff = Multisets.difference(ground, groundSnapshot);
            if (diff.isEmpty()) {
                // loot is not spawned yet
                log.debug("Ground snapshot: No loot yet");
                return;
            }

            log.debug("Ground snapshot: Loot received {} on cycle {}", diff, client.getGameCycle());

            // Create a JSON array for the detected items
            JsonArray lootItems = new JsonArray();
            for (Multiset.Entry<Integer> entry : diff.entrySet()) {
                ItemComposition itemComp = client.getItemDefinition(entry.getElement());
                if (itemComp != null) {
                    JsonObject itemObj = new JsonObject();
                    itemObj.addProperty("itemId", entry.getElement());
                    itemObj.addProperty("itemName", itemComp.getName());
                    itemObj.addProperty("quantity", entry.getCount());
                    lootItems.add(itemObj);
                }
            }

            // Generate a new kill ID for this special case
            String killId = UUID.randomUUID().toString();

            // Create kill details
            JsonObject details = new JsonObject();
            details.addProperty("monsterName", groundSnapshotName);
            details.addProperty("combatLevel", groundSnapshotCombatLevel);
            details.addProperty("killId", killId);
            details.add("items", lootItems);

            // Log the event directly
            eventLogService.logEvent("MONSTER_KILL", client, details);

            // Reset ground snapshot tracking
            groundSnapshotName = null;
            groundSnapshotCombatLevel = 0;
            groundSnapshot = null;
            groundSnapshotCycleDelay = 0;
        }
    }

    /**
     * Finalize a kill by logging the event with all collected loot
     */
    private void finalizeKill(String killId, KillInfo info) {
        // Add the items array to the event details
        info.details.add("items", info.items);

        // Log the complete event
        eventLogService.logEvent("MONSTER_KILL", client, info.details);

        // Mark as finalized
        info.finalized = true;
    }

    /**
     * Track when NPCs die
     */
    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();

        // Only process NPC deaths
        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        // More robust interaction checking, inspired by LootTrackerPlugin
        boolean playerInvolved = false;

        // Check if this player was directly interacting with the NPC
        Actor interacting = localPlayer.getInteracting();
        if (interacting == npc) {
            playerInvolved = true;
        }

        // Check if the NPC was interacting with the player
        else if (npc.getInteracting() == localPlayer) {
            playerInvolved = true;
        }

        // Also check for recent animation changes or other indicators
        else if (lastNpcTypeTarget == npc.getId()) {
            playerInvolved = true;
        }

        if (!playerInvolved) {
            // Player wasn't involved with this NPC, so likely didn't kill it
            return;
        }

        // Create details for the death event
        JsonObject details = new JsonObject();
        String npcName = TextUtil.stripColorTags(npc.getName());

        details.addProperty("monsterName", npcName);
        details.addProperty("monsterId", npc.getId());
        details.addProperty("combatLevel", npc.getCombatLevel());

        // Add region information (useful for contextual analysis)
        WorldPoint location = npc.getWorldLocation();
        if (location != null) {
            details.addProperty("regionId", location.getRegionID());
            details.addProperty("x", location.getX());
            details.addProperty("y", location.getY());
            details.addProperty("plane", location.getPlane());
        }

        // Generate a unique ID for this kill
        String killId = UUID.randomUUID().toString();
        details.addProperty("killId", killId);

        // Store kill info for loot tracking
        WorldPoint killLocation = npc.getWorldLocation();
        KillInfo killInfo = new KillInfo(killId, details, killLocation);
        activeKills.put(killId, killInfo);

        // Store kill location for loot association
        if (killLocation != null) {
            killLocations.put(killLocation, killId);

            // Add a small radius around the kill point for better loot detection
            // Some loot can drop 1-2 tiles away from the exact death location
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    if (x == 0 && y == 0) continue; // Skip the exact point (already added)
                    WorldPoint nearby = new WorldPoint(
                            killLocation.getX() + x,
                            killLocation.getY() + y,
                            killLocation.getPlane()
                    );
                    killLocations.put(nearby, killId);
                }
            }
        }

        // Don't log the event yet - we'll wait to collect loot first
    }

    /**
     * Track items spawned on the ground (potential loot)
     */
    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        TileItem item = event.getItem();
        WorldPoint itemLocation = event.getTile().getWorldLocation();

        // Check if this item appeared at a known kill location
        String killId = killLocations.get(itemLocation);
        if (killId == null) {
            return; // Not associated with a known kill
        }

        // Get the kill info
        KillInfo killInfo = activeKills.get(killId);
        if (killInfo == null) {
            return; // Kill no longer being tracked
        }

        // Get item information
        ItemComposition itemComp = client.getItemDefinition(item.getId());
        if (itemComp == null) {
            return;
        }

        String itemName = itemComp.getName();
        int quantity = item.getQuantity();

        // Create item object and add to the kill's items
        JsonObject itemObj = new JsonObject();
        itemObj.addProperty("itemId", item.getId());
        itemObj.addProperty("itemName", itemName);
        itemObj.addProperty("quantity", quantity);

        killInfo.addItem(itemObj);

        // If this is the first item and we're past minimum tracking time,
        // schedule kill for finalization in the next tick
        if (killInfo.items.size() == 1 && killInfo.ticksSinceKill >= MIN_TRACKING_TIME) {
            // We'll let the game tick handler finalize this in the next tick
            // This allows a small window to collect additional items that spawn in the same tick
        }
    }

    /**
     * Track NPC despawns for special cases like The Whisperer
     * (inspired by LootTrackerPlugin)
     */
    @Subscribe
    public void onNpcDespawned(NpcDespawned npcDespawned) {
        var npc = npcDespawned.getNpc();

        // Add your own NPC IDs for special cases here, similar to how LootTrackerPlugin handles the Whisperer
        // For example, if the NPC is relevant for special handling:

        int npcId = npc.getId();
        // Example check for a specific NPC that needs special handling
        // if (npcId == 12345 || npcId == 12346) {
        if (isSpecialNpcWithDelayedLoot(npc.getId())) {
            // Collect all items on the ground floor (z=0)
            Multiset<Integer> ground = HashMultiset.create();
            var scene = client.getScene();
            Tile[][] p0 = scene.getTiles()[0];
            Arrays.stream(p0)
                    .flatMap(Arrays::stream)
                    .filter(Objects::nonNull)
                    .map(Tile::getGroundItems)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .forEach(item -> ground.add(item.getId(), item.getQuantity()));

            groundSnapshotName = npc.getName();
            groundSnapshotCombatLevel = npc.getCombatLevel();
            groundSnapshotRegion = WorldPoint.fromLocalInstance(client, npc.getLocalLocation()).getRegionID();
            groundSnapshot = ground;
            // Similar to Whisperer, give a delay to detect loot
            groundSnapshotCycleDelay = 59;

            log.debug("Ground snapshot: Recorded ground items {} on cycle {} region {}",
                    ground, client.getGameCycle(), groundSnapshotRegion);
        }
    }

    /**
     * Track animation changes for certain NPCs that require special handling
     */
    @Subscribe
    public void onAnimationChanged(AnimationChanged animationChanged) {
        Actor actor = animationChanged.getActor();

        // Keep track of player interactions with NPCs
        if (actor instanceof NPC) {
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer != null && localPlayer.getInteracting() == actor) {
                lastNpcTypeTarget = ((NPC) actor).getId();
            }
        }
    }

    /**
     * Check if an NPC requires special loot handling
     */
    private boolean isSpecialNpcWithDelayedLoot(int npcId) {
        // Add NPC IDs that need special handling here
        // Example: bosses with delayed loot spawns
        // return npcId == 12345 || npcId == 12346;
        return false; // Default to false if no special cases defined
    }
}