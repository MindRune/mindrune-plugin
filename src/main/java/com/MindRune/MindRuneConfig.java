package com.MindRune;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("example")
public interface MindRuneConfig extends Config
{
	@ConfigItem(
			keyName = "registrationKey",
			name = "Registration Key",
			description = "Your RuneBoy registration key"
	)
	default String registrationKey() {
		return "";
	}

	@ConfigItem(
			keyName = "enableChatNotifications",
			name = "Enable Chat Notifications",
			description = "Toggle chat notifications on or off"
	)
	default boolean enableChatNotifications() {
		return true;  // Default to enabled
	}
}
