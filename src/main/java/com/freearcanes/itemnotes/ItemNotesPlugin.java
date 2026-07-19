/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Item Notes",
	description = "Attach private notes to items and track loans",
	tags = {"item", "loan", "notes"}
)
public class ItemNotesPlugin extends Plugin
{
	static final String CONFIG_GROUP = "itemNotes";
	static final String KEY_PREFIX = "note_";
	private static final String LOAN_RECORDED_AT_PREFIX = "loanRecordedAt_";
	private static final int CHARACTER_LIMIT = 256;
	private static final String ADD_NOTE = "Add Note";
	private static final String EDIT_NOTE = "Edit Note";
	private static final String NOTE_PROMPT_FORMAT = "%s Notes<br>" +
		ColorUtil.prependColorTag("(Limit %s Characters; leave blank to remove)", new Color(0, 0, 170));
	private static final Pattern MENTION_PATTERN = Pattern.compile(
		"(?i)(?<![A-Za-z0-9])@([A-Za-z0-9_-]{1,12})(?![A-Za-z0-9_-])");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ItemNotesConfig config;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemNotesOverlay overlay;

	@Inject
	private ItemLoansPanel loansPanel;

	@Getter
	@Nullable
	private String hoveredItemNote;

	private final Deque<String> pendingExamineNotes = new ArrayDeque<>();
	private NavigationButton navigationButton;

	@Provides
	ItemNotesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ItemNotesConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "item_notes_icon.png");
		navigationButton = NavigationButton.builder()
			.tooltip("Item Loans")
			.icon(icon)
			.priority(8)
			.panel(loansPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		refreshLoans();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navigationButton);
		hoveredItemNote = null;
		pendingExamineNotes.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CONFIG_GROUP.equals(event.getGroup()) && event.getKey().startsWith(KEY_PREFIX))
		{
			refreshLoans();
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		refreshLoans();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (event.getItemId() <= 0)
		{
			hoveredItemNote = null;
			return;
		}
		if (!"Examine".equals(event.getOption()))
		{
			return;
		}

		final int itemId = canonicalize(event.getItemId());
		hoveredItemNote = getItemNote(itemId);

		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		client.getMenu().createMenuEntry(-1)
			.setOption(hoveredItemNote == null ? ADD_NOTE : EDIT_NOTE)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setItemId(itemId)
			.onClick(this::editNote);
	}

	private void editNote(MenuEntry entry)
	{
		final int itemId = canonicalize(entry.getItemId());
		final ItemComposition item = itemManager.getItemComposition(itemId);
		final String itemName = item.getName();

		chatboxPanelManager.openTextInput(String.format(NOTE_PROMPT_FORMAT, itemName, CHARACTER_LIMIT))
			.value(Strings.nullToEmpty(getItemNote(itemId)))
			.onDone(content ->
			{
				if (content == null)
				{
					return;
				}

				content = Text.removeTags(content).trim();
				if (content.length() > CHARACTER_LIMIT)
				{
					content = content.substring(0, CHARACTER_LIMIT);
				}
				setItemNote(itemId, content);
				hoveredItemNote = Strings.emptyToNull(content);
				log.debug("Updated note for item {} ({})", itemId, itemName);
			})
			.build();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!"Examine".equals(event.getMenuOption()))
		{
			return;
		}

		int itemId = event.getMenuAction() == MenuAction.EXAMINE_ITEM_GROUND
			? event.getId() : event.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		String note = getItemNote(canonicalize(itemId));
		if (note != null)
		{
			pendingExamineNotes.addLast(note);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.ITEM_EXAMINE || pendingExamineNotes.isEmpty())
		{
			return;
		}

		String message = new ChatMessageBuilder()
			.append(config.noteLabelColor(), "Note:")
			.append(" ")
			.append(config.noteTextColor(), pendingExamineNotes.removeFirst())
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	int canonicalize(int itemId)
	{
		return itemManager.canonicalize(itemId);
	}

	void setItemNote(int itemId, @Nullable String note)
	{
		final String key = KEY_PREFIX + itemId;
		final String previousBorrower = findMention(getItemNote(itemId));
		final String borrower = findMention(note);
		if (Strings.isNullOrEmpty(note))
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, key, note);
		}

		final String recordedAtKey = LOAN_RECORDED_AT_PREFIX + itemId;
		if (borrower == null)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, recordedAtKey);
		}
		else if (!sameBorrower(previousBorrower, borrower) || getLoanRecordedAt(itemId) == 0)
		{
			configManager.setConfiguration(CONFIG_GROUP, recordedAtKey, System.currentTimeMillis());
		}
	}

	@Nullable
	String getItemNote(int itemId)
	{
		return configManager.getConfiguration(CONFIG_GROUP, KEY_PREFIX + itemId);
	}

	private long getLoanRecordedAt(int itemId)
	{
		String value = configManager.getConfiguration(CONFIG_GROUP, LOAN_RECORDED_AT_PREFIX + itemId);
		if (value != null)
		{
			try
			{
				return Long.parseLong(value);
			}
			catch (NumberFormatException ex)
			{
				log.debug("Ignoring invalid loan timestamp for item {}: {}", itemId, value);
			}
		}
		return 0;
	}

	void markReturned(ItemLoan loan)
	{
		setItemNote(loan.getItemId(), removeMention(loan.getNote()));
		refreshLoans();
	}

	private void refreshLoans()
	{
		clientThread.invokeLater(this::loadLoans);
	}

	private void loadLoans()
	{
		List<ItemLoan> loans = new ArrayList<>();
		String wholePrefix = CONFIG_GROUP + "." + KEY_PREFIX;
		for (String wholeKey : configManager.getConfigurationKeys(wholePrefix))
		{
			String key = wholeKey.substring((CONFIG_GROUP + ".").length());
			String note = configManager.getConfiguration(CONFIG_GROUP, key);
			String borrower = findMention(note);
			if (borrower == null)
			{
				continue;
			}

			try
			{
				int itemId = Integer.parseInt(key.substring(KEY_PREFIX.length()));
				String itemName = itemManager.getItemComposition(itemId).getName();
				long recordedAt = getLoanRecordedAt(itemId);
				if (recordedAt == 0)
				{
					recordedAt = System.currentTimeMillis();
					configManager.setConfiguration(CONFIG_GROUP,
						LOAN_RECORDED_AT_PREFIX + itemId, recordedAt);
				}
				loans.add(new ItemLoan(itemId, itemName, borrower, note, recordedAt));
			}
			catch (NumberFormatException ex)
			{
				log.debug("Ignoring invalid item note key: {}", key);
			}
		}

		loans.sort(Comparator.comparing(ItemLoan::getBorrower, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ItemLoan::getItemName, String.CASE_INSENSITIVE_ORDER));
		loansPanel.showLoans(loans);
	}

	@Nullable
	static String findMention(@Nullable String note)
	{
		Matcher matcher = MENTION_PATTERN.matcher(Strings.nullToEmpty(note));
		return matcher.find() ? matcher.group(1).replace('_', ' ') : null;
	}

	static String removeMention(String note)
	{
		Matcher matcher = MENTION_PATTERN.matcher(note);
		if (!matcher.find())
		{
			return note;
		}

		return (note.substring(0, matcher.start()) + note.substring(matcher.end()))
			.replaceAll("\\s{2,}", " ")
			.trim();
	}

	static boolean sameBorrower(@Nullable String first, @Nullable String second)
	{
		return first == null ? second == null : second != null && first.equalsIgnoreCase(second);
	}
}
