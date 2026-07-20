/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.BorderLayout;
import java.awt.Dimension;
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
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
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
	private final JTabbedPane tabs = new JTabbedPane();
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
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Item Loan Ledger");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setFont(FontManager.getRunescapeSmallFont());

		search.setToolTipText("Search by item, borrower, or loan details");
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
		sort.addActionListener(event -> renderLoans());

		JPanel searchRow = new JPanel(new BorderLayout(5, 0));
		searchRow.setOpaque(false);
		searchRow.add(search, BorderLayout.CENTER);
		searchRow.add(sort, BorderLayout.EAST);

		JButton undo = new JButton("Undo");
		undo.setFocusable(false);
		undo.addActionListener(event ->
		{
			if (undoLoan != null)
			{
				plugin.reopenLoan(undoLoan);
			}
		});
		undoPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		undoPanel.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		undoPanel.add(undoLabel, BorderLayout.CENTER);
		undoPanel.add(undo, BorderLayout.EAST);
		undoPanel.setVisible(false);

		JPanel header = new JPanel();
		header.setOpaque(false);
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.add(title);
		header.add(Box.createVerticalStrut(3));
		header.add(summary);
		header.add(Box.createVerticalStrut(7));
		header.add(searchRow);
		header.add(Box.createVerticalStrut(6));
		header.add(undoPanel);

		tabs.addTab("Active", activeContainer);
		tabs.addTab("History", historyContainer);

		add(header, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
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

		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.add(new JLabel("Borrower (RuneScape name)"));
		form.add(borrower);
		form.add(Box.createVerticalStrut(7));
		form.add(new JLabel("Quantity"));
		form.add(quantity);
		form.add(Box.createVerticalStrut(7));
		form.add(new JLabel("Due date (YYYY-MM-DD, optional)"));
		form.add(dueDate);
		form.add(Box.createVerticalStrut(7));
		form.add(new JLabel("Loan details (up to " + ItemNotesPlugin.CHARACTER_LIMIT + " characters)"));
		form.add(new JScrollPane(details));

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
		tabs.setTitleAt(0, "Active (" + totalActive + ")");
		tabs.setTitleAt(1, "History (" + totalHistory + ")");

		renderContainer(activeContainer, active, false, totalActive > 0);
		renderContainer(historyContainer, history, true, totalHistory > 0);
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
			PluginErrorPanel empty = new PluginErrorPanel();
			if (hasUnfilteredLoans)
			{
				empty.setContent("No matching loans", "Try a different search.");
			}
			else if (returned)
			{
				empty.setContent("No loan history", "Returned loans will be kept here.");
			}
			else
			{
				empty.setContent("No active loans",
					"Shift-right-click an item and choose <b>Record Loan</b>, or add <b>@username</b> to its note.");
			}
			container.add(empty);
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
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(ITEM_ICON_SIZE);
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(loan.getItemId()).addTo(icon);

		String displayItem = loan.getQuantity() > 1
			? loan.getQuantity() + " x " + loan.getItemName() : loan.getItemName();
		JLabel itemName = new JLabel("<html><b>" + escape(displayItem) + "</b></html>");
		itemName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel borrower = new JLabel("Loaned to @" + loan.getBorrower());
		borrower.setForeground(ColorScheme.BRAND_ORANGE);

		JLabel recordedAt = new JLabel(formatLoanAge(loan.getRecordedAt(), System.currentTimeMillis()));
		recordedAt.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		recordedAt.setFont(FontManager.getRunescapeSmallFont());
		recordedAt.setToolTipText("Recorded " + formatTimestamp(loan.getRecordedAt()));

		JPanel details = new JPanel();
		details.setOpaque(false);
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.add(itemName);
		details.add(borrower);
		details.add(recordedAt);

		if (loan.getDueAt() > 0)
		{
			LocalDate due = Instant.ofEpochMilli(loan.getDueAt()).atZone(LOCAL_ZONE).toLocalDate();
			boolean overdue = !loan.isReturned() && LocalDate.now(LOCAL_ZONE).isAfter(due);
			JLabel dueDate = new JLabel((overdue ? "Overdue - " : "Due - ")
				+ RECORDED_DATE_FORMAT.format(Instant.ofEpochMilli(loan.getDueAt())));
			dueDate.setFont(FontManager.getRunescapeSmallFont());
			dueDate.setForeground(overdue ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			details.add(dueDate);
		}

		if (loan.isReturned())
		{
			JLabel returnedAt = new JLabel("Returned " + formatTimestamp(loan.getReturnedAt()));
			returnedAt.setFont(FontManager.getRunescapeSmallFont());
			returnedAt.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			details.add(returnedAt);
		}

		if (!loan.getNote().isEmpty())
		{
			JLabel note = new JLabel("<html><div style='width: 145px'>"
				+ escape(loan.getNote()).replace("\n", "<br>") + "</div></html>");
			note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setToolTipText(loan.getNote());
			details.add(Box.createVerticalStrut(3));
			details.add(note);
		}

		JPanel actions = new JPanel();
		actions.setOpaque(false);
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		JButton edit = new JButton("Edit");
		edit.setFocusable(false);
		edit.addActionListener(event -> showLoanEditor(
			loan.getItemId(), loan.getItemName(), loan, loan.getBorrower(), loan.getNote()));
		JButton delete = new JButton("Delete");
		delete.setFocusable(false);
		delete.addActionListener(event -> deleteLoanWithConfirmation(loan));
		JButton state = new JButton(loan.isReturned() ? "Reopen" : "Return");
		state.setFocusable(false);
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
		actions.add(edit);
		actions.add(Box.createHorizontalStrut(4));
		actions.add(delete);
		actions.add(Box.createHorizontalGlue());
		actions.add(state);

		card.add(icon, BorderLayout.WEST);
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

	private static JPanel createListContainer()
	{
		JPanel container = new JPanel();
		container.setOpaque(false);
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
