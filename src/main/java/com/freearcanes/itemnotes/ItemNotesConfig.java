/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ItemNotesPlugin.CONFIG_GROUP)
public interface ItemNotesConfig extends Config
{
	@ConfigItem(
		position = 1,
		keyName = "noteLabelColor",
		name = "Note label color",
		description = "Color of the Note: label shown after examining an item"
	)
	default Color noteLabelColor()
	{
		return Color.CYAN;
	}

	@ConfigItem(
		position = 2,
		keyName = "noteTextColor",
		name = "Note text color",
		description = "Color of the saved note shown after examining an item"
	)
	default Color noteTextColor()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		position = 3,
		keyName = "showTooltips",
		name = "Show tooltips",
		description = "Show an item's note when hovering over it"
	)
	default boolean showTooltips()
	{
		return true;
	}

	@ConfigItem(
		position = 4,
		keyName = "notifyOverdueLoans",
		name = "Overdue loan notification",
		description = "Notify once after login when one or more active loans are overdue"
	)
	default boolean notifyOverdueLoans()
	{
		return false;
	}
}
