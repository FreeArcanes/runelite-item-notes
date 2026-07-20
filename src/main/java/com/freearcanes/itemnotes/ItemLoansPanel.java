/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import org.apache.commons.text.StringEscapeUtils;

class ItemLoansPanel extends PluginPanel
{
	private static final Dimension ITEM_ICON_SIZE = new Dimension(32, 32);
	private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter RECORDED_DATE_FORMAT = DateTimeFormatter
		.ofLocalizedDate(FormatStyle.MEDIUM)
		.withLocale(Locale.getDefault())
		.withZone(LOCAL_ZONE);
	private static final DateTimeFormatter RECORDED_TIME_FORMAT = DateTimeFormatter
		.ofLocalizedTime(FormatStyle.SHORT)
		.withLocale(Locale.getDefault())
		.withZone(LOCAL_ZONE);

	private final ItemNotesPlugin plugin;
	private final ItemManager itemManager;
	private final JLabel summary = new JLabel();
	private final JTextField search = new JTextField();
	private final JComboBox<LoanSort> sort = new JComboBox<>(LoanSort.values());
	private final JPanel activeContainer = createListContainer();
	private final JPanel historyContainer = createListContainer();
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel content = new JPanel(contentLayout);
	private final JButton activeTab = new JButton();
	private final JButton historyTab = new JButton();
	private final JPanel undoPanel = new JPanel(new BorderLayout(4, 0));
	private final JLabel undoLabel = new JLabel();
	private List<ItemLoan> loans = new ArrayList<>();
	private ItemLoan undoLoan;

	@Inject
	private ItemLoansPanel(ItemNotesPlugin plugin, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.itemManager = itemManager;

		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ItemLoansTheme.BACKGROUND);
		getScrollPane().getViewport().setBackground(ItemLoansTheme.BACKGROUND);
		getScrollPane().getViewport().getView().setBackground(ItemLoansTheme.BACKGROUND);
		getWrappedPanel().setBackground(ItemLoansTheme.BACKGROUND);

		JLabel title = new JLabel("ITEM LOAN LEDGER");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		title.setForeground(ItemLoansTheme.GOLD_BRIGHT);

		JLabel subtitle = new JLabel("Private records - stored locally");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ItemLoansTheme.MUTED);

		summary.setForeground(ItemLoansTheme.PARCHMENT);
		summary.setFont(FontManager.getRunescapeSmallFont());

		JPanel titlePanel = new JPanel();
		titlePanel.setBackground(ItemLoansTheme.SURFACE);
		titlePanel.setBorder(ItemLoansTheme.surfaceBorder());
		titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
		titlePanel.add(title);
		titlePanel.add(Box.createVerticalStrut(2));
		titlePanel.add(subtitle);
		titlePanel.add(Box.createVerticalStrut(6));
		titlePanel.add(summary);

		search.setToolTipText("Search by item, borrower, or loan details");
		ItemLoansTheme.styleInput(search);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				renderLoans();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				renderLoans();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				renderLoans();
			}
		});
		ItemLoansTheme.styleSelect(sort);
		sort.addActionListener(event -> renderLoans());

		JLabel findLabel = new JLabel("FIND A LOAN");
		findLabel.setFont(FontManager.getRunescapeSmallFont());
		findLabel.setForeground(ItemLoansTheme.GOLD);
		JPanel searchControls = new JPanel(new BorderLayout(5, 0));
		searchControls.setOpaque(false);
		searchControls.add(search, BorderLayout.CENTER);
		searchControls.add(sort, BorderLayout.EAST);
		JPanel searchPanel = new JPanel();
		searchPanel.setOpaque(false);
		searchPanel.setLayout(new BoxLayout(searchPanel, BoxLayout.Y_AXIS));
		searchPanel.add(findLabel);
		searchPanel.add(Box.createVerticalStrut(3));
		searchPanel.add(searchControls);

		JButton undo = ItemLoansTheme.button("Undo", ItemLoansTheme.ButtonKind.PRIMARY);
		undo.addActionListener(event ->
		{
			if (undoLoan != null)
			{
				plugin.reopenLoan(undoLoan);
			}
		});
		undoLabel.setForeground(ItemLoansTheme.PARCHMENT);
		undoLabel.setFont(FontManager.getRunescapeSmallFont());
		undoPanel.setBorder(ItemLoansTheme.surfaceBorder());
		undoPanel.setBackground(ItemLoansTheme.SURFACE_RAISED);
		undoPanel.add(undoLabel, BorderLayout.CENTER);
		undoPanel.add(undo, BorderLayout.EAST);
		undoPanel.setVisible(false);

		configureTab(activeTab, false);
		configureTab(historyTab, true);
		JPanel tabRow = new JPanel(new GridLayout(1, 2, 4, 0));
		tabRow.setOpaque(false);
		tabRow.add(activeTab);
		tabRow.add(historyTab);

		JPanel header = new JPanel();
		header.setOpaque(false);
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.add(titlePanel);
		header.add(Box.createVerticalStrut(8));
		header.add(searchPanel);
		header.add(Box.createVerticalStrut(8));
		header.add(tabRow);
		header.add(Box.createVerticalStrut(6));
		header.add(undoPanel);

		content.setOpaque(false);
		content.add(activeContainer, "active");
		content.add(historyContainer, "history");

		add(header, BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
		selectTab(false);
	}

	void showLoans(List<ItemLoan> updatedLoans)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> showLoans(updatedLoans));
			return;
		}

		loans = new ArrayList<>(updatedLoans);
		renderLoans();
	}

	void offerUndo(ItemLoan loan)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> offerUndo(loan));
			return;
		}

		undoLoan = loan;
		undoLabel.setText("Returned " + loan.getItemName());
		undoPanel.setVisible(true);
		revalidate();
	}

	void clearUndo()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::clearUndo);
			return;
		}

		undoLoan = null;
		undoPanel.setVisible(false);
		revalidate();
	}

	void showLoanEditor(int itemId, String itemName, @Nullable ItemLoan existing,
		@Nullable String suggestedBorrower, @Nullable String suggestedNote)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> showLoanEditor(
				itemId, itemName, existing, suggestedBorrower, suggestedNote));
			return;
		}

		JTextField borrower = new JTextField(existing == null
			? valueOrEmpty(suggestedBorrower) : existing.getBorrower());
		JSpinner quantity = new JSpinner(new SpinnerNumberModel(
			existing == null ? 1 : existing.getQuantity(), 1, Integer.MAX_VALUE, 1));
		JTextField dueDate = new JTextField(existing != null && existing.getDueAt() > 0
			? Instant.ofEpochMilli(existing.getDueAt()).atZone(LOCAL_ZONE).toLocalDate().toString() : "");
		JTextArea details = new JTextArea(existing == null
			? valueOrEmpty(suggestedNote) : existing.getNote(), 5, 24);
		details.setLineWrap(true);
		details.setWrapStyleWord(true);
		ItemLoansTheme.styleInput(borrower);
		ItemLoansTheme.styleSpinner(quantity);
		ItemLoansTheme.styleInput(dueDate);
		ItemLoansTheme.styleInput(details);
		JScrollPane detailsScroll = new JScrollPane(details);
		detailsScroll.setBorder(BorderFactory.createLineBorder(ItemLoansTheme.GOLD_DARK));
		detailsScroll.getViewport().setBackground(ItemLoansTheme.INPUT);

		JPanel form = new JPanel();
		form.setBackground(ItemLoansTheme.BACKGROUND);
		form.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.add(createFieldLabel("BORROWER (RUNESCAPE NAME)"));
		form.add(borrower);
		form.add(Box.createVerticalStrut(7));
		form.add(createFieldLabel("QUANTITY"));
		form.add(quantity);
		form.add(Box.createVerticalStrut(7));
		form.add(createFieldLabel("DUE DATE (YYYY-MM-DD, OPTIONAL)"));
		form.add(dueDate);
		form.add(Box.createVerticalStrut(7));
		form.add(createFieldLabel("DETAILS - UP TO " + ItemNotesPlugin.CHARACTER_LIMIT + " CHARACTERS"));
		form.add(detailsScroll);

		while (true)
		{
			int result = JOptionPane.showConfirmDialog(this, form,
				(existing == null ? "Record " : "Edit ") + itemName,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (result != JOptionPane.OK_OPTION)
			{
				return;
			}

			String normalizedBorrower = ItemNotesPlugin.normalizeBorrower(borrower.getText());
			if (normalizedBorrower == null)
			{
				showValidationError("Enter a valid RuneScape name using 1-12 letters, numbers, spaces, or hyphens.");
				continue;
			}
			if (details.getText().trim().length() > ItemNotesPlugin.CHARACTER_LIMIT)
			{
				showValidationError("Loan details must be 256 characters or fewer.");
				continue;
			}
			try
			{
				quantity.commitEdit();
			}
			catch (ParseException ex)
			{
				showValidationError("Enter a whole-number quantity of at least 1.");
				continue;
			}

			long dueAt = 0;
			String dueText = dueDate.getText().trim();
			if (!dueText.isEmpty())
			{
				try
				{
					dueAt = LocalDate.parse(dueText).atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli();
				}
				catch (DateTimeParseException ex)
				{
					showValidationError("Enter the due date as YYYY-MM-DD, or leave it blank.");
					continue;
				}
			}

			try
			{
				plugin.saveLoan(existing, itemId, itemName, normalizedBorrower,
					((Number) quantity.getValue()).intValue(), dueAt, details.getText());
				return;
			}
			catch (IllegalArgumentException ex)
			{
				showValidationError(ex.getMessage());
			}
		}
	}

	private void renderLoans()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::renderLoans);
			return;
		}

		List<ItemLoan> active = filterAndSort(false);
		List<ItemLoan> history = filterAndSort(true);
		long totalActive = loans.stream().filter(loan -> !loan.isReturned()).count();
		long totalHistory = loans.size() - totalActive;
		summary.setText(totalActive + (totalActive == 1 ? " active loan" : " active loans")
			+ " | " + totalHistory + " returned");
		activeTab.setText("ACTIVE  " + totalActive);
		historyTab.setText("HISTORY  " + totalHistory);

		renderContainer(activeContainer, active, false, totalActive > 0);
		renderContainer(historyContainer, history, true, totalHistory > 0);
	}

	private void configureTab(JButton button, boolean history)
	{
		button.setText(history ? "HISTORY  0" : "ACTIVE  0");
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setFocusPainted(false);
		button.setFocusable(false);
		button.setOpaque(true);
		button.addActionListener(event -> selectTab(history));
	}

	private void selectTab(boolean history)
	{
		contentLayout.show(content, history ? "history" : "active");
		styleTab(activeTab, !history);
		styleTab(historyTab, history);
	}

	private static void styleTab(JButton button, boolean selected)
	{
		button.setBackground(selected ? ItemLoansTheme.SURFACE_RAISED : ItemLoansTheme.INPUT);
		button.setForeground(selected ? ItemLoansTheme.GOLD_BRIGHT : ItemLoansTheme.MUTED);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(selected ? ItemLoansTheme.GOLD : ItemLoansTheme.GOLD_DARK),
			BorderFactory.createEmptyBorder(7, 5, 7, 5)));
	}

	private List<ItemLoan> filterAndSort(boolean returned)
	{
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		List<ItemLoan> matches = new ArrayList<>();
		for (ItemLoan loan : loans)
		{
			if (loan.isReturned() == returned && matchesSearch(loan, query))
			{
				matches.add(loan);
			}
		}
		matches.sort(getComparator());
		return matches;
	}

	private Comparator<ItemLoan> getComparator()
	{
		LoanSort selected = (LoanSort) sort.getSelectedItem();
		if (selected == LoanSort.OLDEST)
		{
			return Comparator.comparingLong(ItemLoan::getRecordedAt);
		}
		if (selected == LoanSort.NEWEST)
		{
			return Comparator.comparingLong(ItemLoan::getRecordedAt).reversed();
		}
		if (selected == LoanSort.DUE_DATE)
		{
			return Comparator.comparingLong(loan -> loan.getDueAt() == 0 ? Long.MAX_VALUE : loan.getDueAt());
		}
		return Comparator.comparing(ItemLoan::getBorrower, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(ItemLoan::getItemName, String.CASE_INSENSITIVE_ORDER);
	}

	private void renderContainer(JPanel container, List<ItemLoan> visibleLoans,
		boolean returned, boolean hasUnfilteredLoans)
	{
		container.removeAll();
		if (visibleLoans.isEmpty())
		{
			if (hasUnfilteredLoans)
			{
				container.add(createEmptyState("NO MATCHING LOANS", "Try a different search."));
			}
			else if (returned)
			{
				container.add(createEmptyState("NO LOAN HISTORY", "Returned loans will be kept here."));
			}
			else
			{
				container.add(createEmptyState("YOUR LEDGER IS EMPTY",
					"Shift-right-click an item and choose <b>Record Loan</b>.<br>"
						+ "You can also add <b>@username</b> to an item note."));
			}
		}
		else
		{
			for (ItemLoan loan : visibleLoans)
			{
				container.add(createLoanCard(loan));
				container.add(Box.createVerticalStrut(6));
			}
		}
		container.revalidate();
		container.repaint();
	}

	private JPanel createLoanCard(ItemLoan loan)
	{
		JPanel card = new JPanel(new BorderLayout(8, 6));
		card.setBackground(ItemLoansTheme.SURFACE);
		card.setBorder(ItemLoansTheme.cardBorder());
		card.setAlignmentX(LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(ITEM_ICON_SIZE);
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(loan.getItemId()).addTo(icon);
		JPanel iconSlot = new JPanel(new BorderLayout());
		iconSlot.setPreferredSize(new Dimension(42, 42));
		iconSlot.setBackground(ItemLoansTheme.SLOT);
		iconSlot.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ItemLoansTheme.GOLD_DARK),
			BorderFactory.createEmptyBorder(4, 4, 4, 4)));
		iconSlot.add(icon, BorderLayout.CENTER);

		String displayItem = loan.getQuantity() > 1
			? loan.getQuantity() + " x " + loan.getItemName() : loan.getItemName();
		JLabel itemName = new JLabel("<html><b>" + escape(displayItem) + "</b></html>");
		itemName.setForeground(ItemLoansTheme.GOLD_BRIGHT);

		JLabel status = new JLabel(loan.isReturned() ? " DONE " : " ACTIVE ");
		status.setOpaque(true);
		status.setHorizontalAlignment(SwingConstants.CENTER);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ItemLoansTheme.PARCHMENT);
		status.setBackground(loan.isReturned() ? ItemLoansTheme.SECONDARY : ItemLoansTheme.ACTIVE);
		status.setBorder(BorderFactory.createLineBorder(ItemLoansTheme.GOLD_DARK));

		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setOpaque(false);
		titleRow.add(itemName, BorderLayout.CENTER);
		titleRow.add(status, BorderLayout.EAST);

		JLabel borrower = new JLabel("Loaned to @" + loan.getBorrower());
		borrower.setForeground(ItemLoansTheme.GOLD);
		borrower.setFont(FontManager.getRunescapeFont());

		JLabel recordedAt = new JLabel(formatLoanAge(loan.getRecordedAt(), System.currentTimeMillis()));
		recordedAt.setForeground(ItemLoansTheme.MUTED);
		recordedAt.setFont(FontManager.getRunescapeSmallFont());
		recordedAt.setToolTipText("Recorded " + formatTimestamp(loan.getRecordedAt()));

		JPanel details = new JPanel();
		details.setOpaque(false);
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.add(titleRow);
		details.add(Box.createVerticalStrut(2));
		details.add(borrower);
		details.add(recordedAt);

		if (loan.getDueAt() > 0)
		{
			LocalDate due = Instant.ofEpochMilli(loan.getDueAt()).atZone(LOCAL_ZONE).toLocalDate();
			boolean overdue = !loan.isReturned() && LocalDate.now(LOCAL_ZONE).isAfter(due);
			JLabel dueDate = new JLabel((overdue ? "Overdue - " : "Due - ")
				+ RECORDED_DATE_FORMAT.format(Instant.ofEpochMilli(loan.getDueAt())));
			dueDate.setFont(FontManager.getRunescapeSmallFont());
			dueDate.setForeground(overdue ? ItemLoansTheme.OVERDUE : ItemLoansTheme.PARCHMENT);
			details.add(dueDate);
		}

		if (loan.isReturned())
		{
			JLabel returnedAt = new JLabel("Returned " + formatTimestamp(loan.getReturnedAt()));
			returnedAt.setFont(FontManager.getRunescapeSmallFont());
			returnedAt.setForeground(ItemLoansTheme.ACTIVE_HOVER);
			details.add(returnedAt);
		}

		if (!loan.getNote().isEmpty())
		{
			JLabel note = new JLabel("<html><div style='width: 145px'>"
				+ escape(loan.getNote()).replace("\n", "<br>") + "</div></html>");
			note.setOpaque(true);
			note.setBackground(ItemLoansTheme.INPUT);
			note.setForeground(ItemLoansTheme.PARCHMENT);
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 0, 0, ItemLoansTheme.GOLD_DARK),
				BorderFactory.createEmptyBorder(4, 6, 4, 4)));
			note.setToolTipText(loan.getNote());
			details.add(Box.createVerticalStrut(3));
			details.add(note);
		}

		JPanel actions = new JPanel();
		actions.setOpaque(false);
		actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
		JButton edit = ItemLoansTheme.button("Edit", ItemLoansTheme.ButtonKind.SECONDARY);
		edit.addActionListener(event -> showLoanEditor(
			loan.getItemId(), loan.getItemName(), loan, loan.getBorrower(), loan.getNote()));
		JButton delete = ItemLoansTheme.button("Delete", ItemLoansTheme.ButtonKind.DANGER);
		delete.addActionListener(event -> deleteLoanWithConfirmation(loan));
		JButton state = ItemLoansTheme.button(loan.isReturned() ? "Reopen loan" : "Mark returned",
			ItemLoansTheme.ButtonKind.PRIMARY);
		state.setMaximumSize(new Dimension(Integer.MAX_VALUE, state.getPreferredSize().height));
		state.setAlignmentX(LEFT_ALIGNMENT);
		state.addActionListener(event ->
		{
			if (loan.isReturned())
			{
				plugin.reopenLoan(loan);
			}
			else
			{
				plugin.markReturned(loan);
			}
		});
		JPanel recordActions = new JPanel(new GridLayout(1, 2, 4, 0));
		recordActions.setOpaque(false);
		recordActions.add(edit);
		recordActions.add(delete);
		actions.add(recordActions);
		actions.add(Box.createVerticalStrut(4));
		actions.add(state);

		card.add(iconSlot, BorderLayout.WEST);
		card.add(details, BorderLayout.CENTER);
		card.add(actions, BorderLayout.SOUTH);
		Dimension preferred = card.getPreferredSize();
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		return card;
	}

	private void deleteLoanWithConfirmation(ItemLoan loan)
	{
		int result = JOptionPane.showConfirmDialog(this,
			"Permanently delete the loan record for " + loan.getItemName() + "?",
			"Delete loan record", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (result == JOptionPane.YES_OPTION)
		{
			plugin.deleteLoan(loan);
		}
	}

	private void showValidationError(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Invalid loan", JOptionPane.ERROR_MESSAGE);
	}

	private static JPanel createEmptyState(String titleText, String description)
	{
		JPanel empty = new JPanel();
		empty.setBackground(ItemLoansTheme.SURFACE);
		empty.setBorder(ItemLoansTheme.surfaceBorder());
		empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
		empty.setAlignmentX(LEFT_ALIGNMENT);

		JLabel ornament = new JLabel("- * -", SwingConstants.CENTER);
		ornament.setForeground(ItemLoansTheme.GOLD_DARK);
		ornament.setFont(FontManager.getRunescapeBoldFont());
		ornament.setAlignmentX(CENTER_ALIGNMENT);

		JLabel title = new JLabel(titleText, SwingConstants.CENTER);
		title.setForeground(ItemLoansTheme.GOLD_BRIGHT);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(CENTER_ALIGNMENT);

		JLabel copy = new JLabel("<html><div style='width: 170px; text-align: center'>"
			+ description + "</div></html>", SwingConstants.CENTER);
		copy.setForeground(ItemLoansTheme.MUTED);
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setAlignmentX(CENTER_ALIGNMENT);

		empty.add(ornament);
		empty.add(Box.createVerticalStrut(5));
		empty.add(title);
		empty.add(Box.createVerticalStrut(6));
		empty.add(copy);
		Dimension preferred = empty.getPreferredSize();
		empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		return empty;
	}

	private static JLabel createFieldLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ItemLoansTheme.GOLD);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static JPanel createListContainer()
	{
		JPanel container = new JPanel();
		container.setBackground(ItemLoansTheme.BACKGROUND);
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		return container;
	}

	private static boolean matchesSearch(ItemLoan loan, String query)
	{
		if (query.isEmpty())
		{
			return true;
		}
		return loan.getItemName().toLowerCase(Locale.ROOT).contains(query)
			|| loan.getBorrower().toLowerCase(Locale.ROOT).contains(query)
			|| loan.getNote().toLowerCase(Locale.ROOT).contains(query);
	}

	static String formatLoanAge(long recordedAt, long now)
	{
		long minutes = Math.max(0, Duration.ofMillis(now - recordedAt).toMinutes());
		if (minutes < 1)
		{
			return "Loaned just now";
		}
		if (minutes < 60)
		{
			return "Loaned " + minutes + (minutes == 1 ? " minute" : " minutes") + " ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return "Loaned " + hours + (hours == 1 ? " hour" : " hours") + " ago";
		}
		long days = hours / 24;
		return "Loaned " + days + (days == 1 ? " day" : " days") + " ago";
	}

	private static String formatTimestamp(long timestamp)
	{
		Instant instant = Instant.ofEpochMilli(timestamp);
		return RECORDED_DATE_FORMAT.format(instant) + " at " + RECORDED_TIME_FORMAT.format(instant);
	}

	private static String escape(String value)
	{
		return StringEscapeUtils.escapeHtml4(value);
	}

	private static String valueOrEmpty(@Nullable String value)
	{
		return value == null ? "" : value;
	}

	private enum LoanSort
	{
		BORROWER("Borrower"),
		OLDEST("Oldest"),
		NEWEST("Newest"),
		DUE_DATE("Due");

		private final String label;

		LoanSort(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
