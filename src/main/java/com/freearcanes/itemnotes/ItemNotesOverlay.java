/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;

class ItemNotesOverlay extends Overlay
{
	private final Client client;
	private final ItemNotesPlugin plugin;
	private final ItemNotesConfig config;
	private final TooltipManager tooltipManager;

	@Inject
	private ItemNotesOverlay(Client client, ItemNotesPlugin plugin, ItemNotesConfig config,
		TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.tooltipManager = tooltipManager;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!client.isMenuOpen() && config.showTooltips())
		{
			String note = plugin.getHoveredItemNote();
			if (note != null)
			{
				String tooltip = ColorUtil.prependColorTag("Note:", config.noteLabelColor())
					+ " " + ColorUtil.prependColorTag(note, config.noteTextColor());
				tooltipManager.add(new Tooltip(tooltip));
			}
		}

		return null;
	}
}
