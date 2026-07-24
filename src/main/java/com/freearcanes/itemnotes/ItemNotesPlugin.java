/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.Notifier;
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
	name = "RuneLedger: Item Notes & Loans",
	description = "Attach private notes to items and track loans",
	tags = {"agreement", "item", "loan", "notes"}
)
public class ItemNotesPlugin extends Plugin
{
	static final String CONFIG_GROUP = "itemNotes";
	static final String KEY_PREFIX = "note_";
	static final String LOAN_RECORDED_AT_PREFIX = "loanRecordedAt_";
	static final String LOAN_RECORD_PREFIX = "loanRecord_";
	static final String LOAN_LEDGER_VERSION_KEY = "loanLedgerVersion";
	private static final int LOAN_LEDGER_VERSION = 2;
	static final int CHARACTER_LIMIT = 256;
	private static final String ADD_NOTE = "Add Note";
	private static final String EDIT_NOTE = "Edit Note";
	private static final String RECORD_LOAN = "Record Loan";
	private static final String NOTE_PROMPT_FORMAT = "%s Notes<br>" +
		ColorUtil.prependColorTag("(Limit %s Characters; leave blank to remove)", new Color(0, 0, 170));
	private static final Pattern MENTION_PATTERN = Pattern.compile(
		"(?i)(?<![A-Za-z0-9])@([A-Za-z0-9](?:[A-Za-z0-9_-]{0,10}[A-Za-z0-9])?)(?![A-Za-z0-9_-])");
	private static final Pattern BORROWER_PATTERN = Pattern.compile(
		"[A-Za-z0-9](?:[A-Za-z0-9 -]{0,10}[A-Za-z0-9])?");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

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
	private boolean overdueNotificationSent;

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
			.tooltip("RuneLedger")
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
		loansPanel.clearUndo();
		hoveredItemNote = null;
		pendingExamineNotes.clear();
		overdueNotificationSent = false;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (event.getKey().startsWith(KEY_PREFIX)
			|| event.getKey().startsWith(LOAN_RECORD_PREFIX))
		{
			refreshLoans();
		}
		if ("notifyOverdueLoans".equals(event.getKey()))
		{
			overdueNotificationSent = false;
			clientThread.invokeLater((Runnable) this::notifyOverdueLoans);
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		loansPanel.clearUndo();
		overdueNotificationSent = false;
		refreshLoans();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			overdueNotificationSent = false;
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			notifyOverdueLoans();
		}
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

		client.getMenu().createMenuEntry(-1)
			.setOption(RECORD_LOAN)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setItemId(itemId)
			.onClick(this::recordLoan);
	}

	private void recordLoan(MenuEntry entry)
	{
		final int itemId = canonicalize(entry.getItemId());
		final String itemName = itemManager.getItemComposition(itemId).getName();
		final String itemNote = getItemNote(itemId);
		final String suggestedBorrower = findMention(itemNote);
		final ItemLoan existingLoan = suggestedBorrower == null ? null : loadStoredLoans().stream()
			.filter(loan -> loan.getItemId() == itemId && !loan.isReturned()
				&& sameBorrower(loan.getBorrower(), suggestedBorrower))
			.findFirst()
			.orElse(null);

		SwingUtilities.invokeLater(() -> loansPanel.showLoanEditor(
			itemId, itemName, existingLoan, suggestedBorrower, itemNote));
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
		setItemNote(itemId, note, true);
	}

	private void setItemNote(int itemId, @Nullable String note, boolean synchronizeLoan)
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

		if (synchronizeLoan)
		{
			syncLoanFromItemNote(itemId, previousBorrower, borrower, note);
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

	void saveLoan(@Nullable ItemLoan existing, int itemId, String itemName, String borrower,
		int quantity, long dueAt, String note)
	{
		String normalizedBorrower = normalizeBorrower(borrower);
		if (normalizedBorrower == null)
		{
			throw new IllegalArgumentException("Enter a valid RuneScape name (1-12 characters).");
		}
		if (quantity < 1)
		{
			throw new IllegalArgumentException("Quantity must be at least 1.");
		}

		String trimmedNote = Strings.nullToEmpty(note).trim();
		if (trimmedNote.length() > CHARACTER_LIMIT)
		{
			throw new IllegalArgumentException("Loan details must be 256 characters or fewer.");
		}

		ItemLoan loan = existing == null
			? new ItemLoan(UUID.randomUUID().toString(), itemId, itemName, normalizedBorrower,
				quantity, trimmedNote, System.currentTimeMillis(), dueAt, 0, false, null)
			: existing.withDetails(normalizedBorrower, quantity, trimmedNote, dueAt);
		storeLoan(loan);
		if (existing != null && existing.isLinkedToItemNote()
			&& !sameBorrower(existing.getBorrower(), normalizedBorrower))
		{
			String currentNote = getItemNote(itemId);
			if (sameBorrower(findMention(currentNote), existing.getBorrower()))
			{
				setItemNote(itemId, replaceMention(currentNote, normalizedBorrower), false);
			}
		}
		refreshLoans();
	}

	void markReturned(ItemLoan loan)
	{
		ItemLoan returnedLoan = loan.returned(System.currentTimeMillis());
		storeLoan(returnedLoan);

		if (loan.isLinkedToItemNote())
		{
			String currentNote = getItemNote(loan.getItemId());
			if (sameBorrower(findMention(currentNote), loan.getBorrower()))
			{
				setItemNote(loan.getItemId(), untagMention(currentNote), false);
			}
		}

		loansPanel.offerUndo(returnedLoan);
		refreshLoans();
	}

	void reopenLoan(ItemLoan loan)
	{
		storeLoan(loan.reopened());
		loansPanel.clearUndo();
		refreshLoans();
	}

	void deleteLoan(ItemLoan loan)
	{
		configManager.unsetConfiguration(CONFIG_GROUP, LOAN_RECORD_PREFIX + loan.getId());
		if (loan.isLinkedToItemNote() && !loan.isReturned())
		{
			String currentNote = getItemNote(loan.getItemId());
			if (sameBorrower(findMention(currentNote), loan.getBorrower()))
			{
				setItemNote(loan.getItemId(), untagMention(currentNote), false);
			}
		}
		loansPanel.clearUndo();
		refreshLoans();
	}

	private void syncLoanFromItemNote(int itemId, @Nullable String previousBorrower,
		@Nullable String borrower, @Nullable String note)
	{
		List<ItemLoan> loans = loadStoredLoans();
		ItemLoan linkedLoan = loans.stream()
			.filter(loan -> loan.getItemId() == itemId && loan.isLinkedToItemNote() && !loan.isReturned())
			.findFirst()
			.orElse(null);

		if (sameBorrower(previousBorrower, borrower))
		{
			if (linkedLoan != null)
			{
				String updatedNote = Strings.nullToEmpty(note).trim();
				if (!linkedLoan.getNote().equals(updatedNote))
				{
					storeLoan(linkedLoan.withDetails(linkedLoan.getBorrower(),
						linkedLoan.getQuantity(), updatedNote, linkedLoan.getDueAt()));
				}
			}
			return;
		}

		if (linkedLoan != null)
		{
			storeLoan(linkedLoan.returned(System.currentTimeMillis()));
		}

		if (borrower == null)
		{
			return;
		}

		boolean alreadyTracked = loans.stream().anyMatch(loan -> loan.getItemId() == itemId
			&& !loan.isReturned() && sameBorrower(loan.getBorrower(), borrower));
		if (alreadyTracked)
		{
			return;
		}

		long recordedAt = getLoanRecordedAt(itemId);
		if (recordedAt == 0)
		{
			recordedAt = System.currentTimeMillis();
		}
		String itemName = itemManager.getItemComposition(itemId).getName();
		storeLoan(new ItemLoan(UUID.randomUUID().toString(), itemId, itemName, borrower,
			1, Strings.nullToEmpty(note), recordedAt, 0, 0, true, null));
	}

	private void storeLoan(ItemLoan loan)
	{
		configManager.setConfiguration(CONFIG_GROUP, LOAN_RECORD_PREFIX + loan.getId(), gson.toJson(loan));
	}

	private void refreshLoans()
	{
		clientThread.invokeLater(this::loadLoans);
	}

	private void loadLoans()
	{
		List<ItemLoan> loans = loadStoredLoans();
		if (getLoanLedgerVersion() < LOAN_LEDGER_VERSION)
		{
			migrateLegacyLoans(loans);
			loans = loadStoredLoans();
		}
		loansPanel.showLoans(loans);
		notifyOverdueLoans(loans);
	}

	String createBackupJson()
	{
		Map<String, String> entries = new LinkedHashMap<>();
		String groupPrefix = CONFIG_GROUP + ".";
		for (String wholeKey : configManager.getConfigurationKeys(groupPrefix))
		{
			String key = wholeKey.substring(groupPrefix.length());
			if (!ItemNotesBackup.isBackupKey(key))
			{
				continue;
			}
			String value = configManager.getConfiguration(CONFIG_GROUP, key);
			if (value != null)
			{
				entries.put(key, value);
			}
		}
		return gson.toJson(new ItemNotesBackup(ItemNotesBackup.CURRENT_VERSION,
			System.currentTimeMillis(), entries));
	}

	ItemNotesBackup parseBackup(String json)
	{
		try
		{
			ItemNotesBackup backup = gson.fromJson(json, ItemNotesBackup.class);
			if (backup == null)
			{
				throw new IllegalArgumentException("The selected file is not a RuneLedger backup.");
			}
			return backup.validated();
		}
		catch (RuntimeException ex)
		{
			if (ex instanceof IllegalArgumentException)
			{
				throw (IllegalArgumentException) ex;
			}
			throw new IllegalArgumentException("The selected file is not a valid RuneLedger backup.", ex);
		}
	}

	void importBackup(ItemNotesBackup backup)
	{
		ItemNotesBackup validated = backup.validated();
		for (Map.Entry<String, String> entry : validated.getEntries().entrySet())
		{
			configManager.setConfiguration(CONFIG_GROUP, entry.getKey(), entry.getValue());
		}
		loansPanel.clearUndo();
		overdueNotificationSent = false;
		refreshLoans();
	}

	String createAgreementOffer(ItemLoan loan, String lender, String itemShortcut)
	{
		// Agreement actions originate on Swing's event dispatch thread. Avoid refreshing
		// item compositions here because ItemManager requires RuneLite's client thread.
		List<ItemLoan> loans = loadStoredLoans(false);
		for (int attempt = 0; attempt < 100; attempt++)
		{
			String transactionId = String.format("%04d",
				ThreadLocalRandom.current().nextInt(10_000));
			LoanReferenceCode reference = LoanReferenceCode.offer(lender, loan.getBorrower(),
				itemShortcut, transactionId);
			boolean duplicate = loans.stream().anyMatch(candidate ->
				hasMatchingOffer(candidate, reference));
			if (!duplicate)
			{
				String code = reference.encode();
				storeLoan(loan.withAgreementCode(code));
				refreshLoans();
				return code;
			}
		}
		throw new IllegalStateException("A unique transaction ID could not be generated.");
	}

	LoanReferenceCode decodeAgreement(String code)
	{
		return LoanReferenceCode.decode(code);
	}

	void verifyLocalPlayer(@Nullable String expectedPlayer, Consumer<String> onVerified,
		Consumer<String> onRejected)
	{
		clientThread.invokeLater((Runnable) () ->
		{
			String localPlayer = client.getGameState() == GameState.LOGGED_IN
				&& client.getLocalPlayer() != null
				? client.getLocalPlayer().getName() : null;
			String playerName = null;
			String error;
			try
			{
				playerName = validateLocalPlayerName(localPlayer, expectedPlayer);
				error = null;
			}
			catch (IllegalArgumentException ex)
			{
				error = ex.getMessage();
			}

			String verifiedPlayer = playerName;
			String rejection = error;
			SwingUtilities.invokeLater(() ->
			{
				if (rejection == null)
				{
					onVerified.accept(verifiedPlayer);
				}
				else
				{
					onRejected.accept(rejection);
				}
			});
		});
	}

	static String validateLocalPlayerName(@Nullable String localPlayer, @Nullable String expectedPlayer)
	{
		String playerName = normalizeBorrower(localPlayer);
		if (playerName == null)
		{
			throw new IllegalArgumentException(
				"Log in to the RuneScape character that should perform this action.");
		}
		if (expectedPlayer != null && !sameBorrower(playerName, expectedPlayer))
		{
			throw new IllegalArgumentException("This reference is for @" + expectedPlayer
				+ ", but this client is logged in as @" + playerName
				+ ". Switch to the matching character to continue.");
		}
		return playerName;
	}

	String acceptAgreement(LoanReferenceCode offer, String acceptingPlayer)
	{
		String normalizedPlayer = normalizeBorrower(acceptingPlayer);
		if (!sameBorrower(normalizedPlayer, offer.getBorrower()))
		{
			throw new IllegalArgumentException("The acceptance name must match the requested borrower.");
		}
		return offer.accept().encode();
	}

	void attachAgreementReceipt(LoanReferenceCode receipt)
	{
		if (receipt.getState() != LoanReferenceCode.State.ACCEPTED)
		{
			throw new IllegalArgumentException("Only an accepted receipt can be attached to a loan.");
		}
		// Receipt review also originates on Swing's event dispatch thread.
		ItemLoan loan = loadStoredLoans(false).stream()
			.filter(candidate -> hasMatchingOffer(candidate, receipt))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"No matching loan was found in this RuneLedger profile."));
		storeLoan(loan.withAgreementCode(receipt.encode()));
		refreshLoans();
	}

	private boolean hasMatchingOffer(ItemLoan loan, LoanReferenceCode receipt)
	{
		if (loan.getAgreementCode() == null)
		{
			return false;
		}
		try
		{
			return receipt.matchesOffer(decodeAgreement(loan.getAgreementCode()));
		}
		catch (IllegalArgumentException ex)
		{
			return false;
		}
	}

	private void notifyOverdueLoans()
	{
		notifyOverdueLoans(loadStoredLoans());
	}

	private void notifyOverdueLoans(List<ItemLoan> loans)
	{
		if (overdueNotificationSent || !config.notifyOverdueLoans()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		long now = System.currentTimeMillis();
		long overdue = loans.stream()
			.filter(loan -> ItemLoansPanel.isOverdue(loan, now))
			.count();
		if (overdue > 0)
		{
			notifier.notify("RuneLedger: " + overdue
				+ (overdue == 1 ? " active loan is overdue." : " active loans are overdue."));
		}
		overdueNotificationSent = true;
	}

	private List<ItemLoan> loadStoredLoans()
	{
		return loadStoredLoans(true);
	}

	private List<ItemLoan> loadStoredLoans(boolean refreshItemNames)
	{
		List<ItemLoan> loans = new ArrayList<>();
		String wholePrefix = CONFIG_GROUP + "." + LOAN_RECORD_PREFIX;
		for (String wholeKey : configManager.getConfigurationKeys(wholePrefix))
		{
			String key = wholeKey.substring((CONFIG_GROUP + ".").length());
			String value = configManager.getConfiguration(CONFIG_GROUP, key);
			try
			{
				ItemLoan stored = gson.fromJson(value, ItemLoan.class);
				if (stored == null)
				{
					continue;
				}

				String borrower = normalizeBorrower(stored.getBorrower());
				if (borrower == null)
				{
					log.debug("Ignoring loan record with invalid borrower: {}", key);
					continue;
				}

				String id = key.substring(LOAN_RECORD_PREFIX.length());
				String itemName = refreshItemNames
					? itemManager.getItemComposition(stored.getItemId()).getName()
					: Strings.nullToEmpty(stored.getItemName()).trim();
				if (itemName.isEmpty())
				{
					itemName = "Item " + stored.getItemId();
				}
				loans.add(new ItemLoan(id, stored.getItemId(), itemName, borrower,
					Math.max(1, stored.getQuantity()), Strings.nullToEmpty(stored.getNote()),
					stored.getRecordedAt() > 0 ? stored.getRecordedAt() : System.currentTimeMillis(),
					Math.max(0, stored.getDueAt()), Math.max(0, stored.getReturnedAt()),
					stored.isLinkedToItemNote(), normalizeAgreementCode(stored.getAgreementCode())));
			}
			catch (RuntimeException ex)
			{
				log.debug("Ignoring invalid loan record: {}", key, ex);
			}
		}
		return loans;
	}

	private int getLoanLedgerVersion()
	{
		String value = configManager.getConfiguration(CONFIG_GROUP, LOAN_LEDGER_VERSION_KEY);
		try
		{
			return value == null ? 0 : Integer.parseInt(value);
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private void migrateLegacyLoans(List<ItemLoan> existingLoans)
	{
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
				boolean alreadyTracked = existingLoans.stream().anyMatch(loan -> loan.getItemId() == itemId
					&& !loan.isReturned() && sameBorrower(loan.getBorrower(), borrower));
				if (alreadyTracked)
				{
					continue;
				}

				long recordedAt = getLoanRecordedAt(itemId);
				if (recordedAt == 0)
				{
					recordedAt = System.currentTimeMillis();
					configManager.setConfiguration(CONFIG_GROUP,
						LOAN_RECORDED_AT_PREFIX + itemId, recordedAt);
				}
				String itemName = itemManager.getItemComposition(itemId).getName();
				ItemLoan migrated = new ItemLoan(UUID.randomUUID().toString(), itemId, itemName,
					borrower, 1, Strings.nullToEmpty(note), recordedAt, 0, 0, true, null);
				storeLoan(migrated);
				existingLoans.add(migrated);
			}
			catch (NumberFormatException ex)
			{
				log.debug("Ignoring invalid item note key: {}", key);
			}
		}

		configManager.setConfiguration(CONFIG_GROUP, LOAN_LEDGER_VERSION_KEY, LOAN_LEDGER_VERSION);
	}

	@Nullable
	static String findMention(@Nullable String note)
	{
		Matcher matcher = MENTION_PATTERN.matcher(Strings.nullToEmpty(note));
		return matcher.find() ? matcher.group(1).replace('_', ' ') : null;
	}

	static String removeMention(String note)
	{
		return untagMention(note);
	}

	static String untagMention(@Nullable String note)
	{
		if (note == null)
		{
			return "";
		}

		Matcher matcher = MENTION_PATTERN.matcher(note);
		if (!matcher.find())
		{
			return note;
		}

		if (note.trim().equals(matcher.group()))
		{
			return "";
		}

		String displayName = matcher.group(1).replace('_', ' ');
		return (note.substring(0, matcher.start()) + displayName + note.substring(matcher.end())).trim();
	}

	static String replaceMention(@Nullable String note, String borrower)
	{
		Matcher matcher = MENTION_PATTERN.matcher(Strings.nullToEmpty(note));
		if (!matcher.find())
		{
			return Strings.nullToEmpty(note);
		}

		String tag = "@" + borrower.replace(' ', '_');
		return note.substring(0, matcher.start()) + tag + note.substring(matcher.end());
	}

	@Nullable
	static String normalizeBorrower(@Nullable String borrower)
	{
		String normalized = Strings.nullToEmpty(borrower).trim();
		if (normalized.startsWith("@"))
		{
			normalized = normalized.substring(1);
		}
		normalized = normalized.replace('_', ' ').replaceAll("\\s+", " ").trim();
		if (!BORROWER_PATTERN.matcher(normalized).matches())
		{
			return null;
		}
		return normalized;
	}

	static boolean sameBorrower(@Nullable String first, @Nullable String second)
	{
		return first == null ? second == null : second != null && first.equalsIgnoreCase(second);
	}

	@Nullable
	private static String normalizeAgreementCode(@Nullable String code)
	{
		if (Strings.isNullOrEmpty(code))
		{
			return null;
		}
		try
		{
			return LoanReferenceCode.decode(code).encode();
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}
}
