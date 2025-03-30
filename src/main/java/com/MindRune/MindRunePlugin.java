package com.MindRune;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import com.MindRune.listener.*;
import com.MindRune.service.DataSenderService;
import com.MindRune.service.EventLogService;
import com.MindRune.service.PlayerInfoService;

@Slf4j
@PluginDescriptor(
		name = "MindRune",
		description = "MindRune - The ultimate gameplay tracker for OSRS!",
		tags = {"example", "template"}
)
public class MindRunePlugin extends Plugin {

	@Inject
	private Client client;

	@Inject
	private MindRuneConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ItemManager itemManager;

	// Services
	private EventLogService eventLogService;
	private DataSenderService dataSenderService;
	private PlayerInfoService playerInfoService;

	// Listeners
	private HitSplatListener hitsplatListener;
	private MonsterKillListener monsterkillListener;
	private InventoryListener inventoryListener;
	private SkillListener skillListener;
	private InteractionListener interactionListener;
	private AchievementListener achievementListener;
	private RewardListener rewardListener;

	@Provides
	MindRuneConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(MindRuneConfig.class);
	}

	@Override
	protected void startUp() {
		log.info("MindRune Plugin Started!");

		// Initialize services
		eventLogService = new EventLogService();
		playerInfoService = new PlayerInfoService(client);
		dataSenderService = new DataSenderService(client, config, clientThread, eventLogService);

		// Initialize listeners
		hitsplatListener = new HitSplatListener(client, eventLogService);
		monsterkillListener = new MonsterKillListener(client, eventLogService, clientThread);
		inventoryListener = new InventoryListener(client, eventLogService);
		skillListener = new SkillListener(client, eventLogService);
		interactionListener = new InteractionListener(client, eventLogService);
		achievementListener = new AchievementListener(client, eventLogService, clientThread);
		rewardListener = new RewardListener(client, eventLogService, clientThread, itemManager);

		// Register all listeners with the event bus
		eventBus.register(hitsplatListener);
		eventBus.register(monsterkillListener);
		eventBus.register(inventoryListener);
		eventBus.register(skillListener);
		eventBus.register(interactionListener);
		eventBus.register(achievementListener);
		eventBus.register(rewardListener);

		// Start data sender
		dataSenderService.startDataSender();

		// Chat notification
		if (config.enableChatNotifications()) {
			clientThread.invokeLater(() -> {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "MindRune is now creating memories! Enjoy your adventure!", null);
			});
		}
	}

	@Override
	protected void shutDown() {
		log.info("MindRune Plugin Stopped!");

		// Unregister all listeners
		eventBus.unregister(hitsplatListener);
		eventBus.unregister(monsterkillListener);
		eventBus.unregister(inventoryListener);
		eventBus.unregister(skillListener);
		eventBus.unregister(interactionListener);
		eventBus.unregister(achievementListener);
		eventBus.unregister(rewardListener);

		// Stop data sender
		dataSenderService.stopDataSender();

		// Chat notification
		if (config.enableChatNotifications()) {
			clientThread.invokeLater(() -> {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "MindRune has stopped creating memories!", null);
			});
		}
	}
}