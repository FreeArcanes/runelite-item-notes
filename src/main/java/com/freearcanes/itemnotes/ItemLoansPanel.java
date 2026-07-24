/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
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
import javax.swing.filechooser.FileNameExtensionFilter;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import org.apache.commons.text.StringEscapeUtils;

class ItemLoansPanel extends PluginPanel
{
	private static final Dimension ITEM_ICON_SIZE = new Dimension(32, 32);
	private static final long MAX_BACKUP_SIZE = 5L * 1024L * 1024L;
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
	private final JLabel activeMetric = new JLabel("0", SwingConstants.CENTER);
	private final JLabel overdueMetric = new JLabel("0", SwingConstants.CENTER);
	private final JLabel returnedMetric = new JLabel("0", SwingConstants.CENTER);
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
	private final JPanel referenceReadyPanel = new JPanel();
	private final JLabel referenceReadyStatus = new JLabel();
	private final JTextField referenceReadyCode = new JTextField();
	private final Map<LoanFilter, JButton> filterButtons = new EnumMap<>(LoanFilter.class);
	private final JButton borrowerView = new JButton("BORROWER VIEW");
	private List<ItemLoan> loans = new ArrayList<>();
	private ItemLoan undoLoan;
	private LoanFilter loanFilter = LoanFilter.ALL;
	private boolean groupByBorrower;
	private File lastBackupDirectory;

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

		JLabel title = new JLabel("RUNELEDGER");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		title.setForeground(ItemLoansTheme.GOLD_BRIGHT);

		JLabel subtitle = new JLabel("Item notes & loans - stored locally");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ItemLoansTheme.MUTED);

		JPanel metrics = new JPanel(new GridLayout(1, 3, 4, 0));
		metrics.setOpaque(false);
		metrics.add(createMetric(activeMetric, "ACTIVE", ItemLoansTheme.ACTIVE_HOVER));
		metrics.add(createMetric(overdueMetric, "OVERDUE", ItemLoansTheme.OVERDUE));
		metrics.add(createMetric(returnedMetric, "RETURNED", ItemLoansTheme.PARCHMENT));

		JPanel titlePanel = new JPanel();
		titlePanel.setBackground(ItemLoansTheme.SURFACE);
		titlePanel.setBorder(ItemLoansTheme.surfaceBorder());
		titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
		titlePanel.add(title);
		titlePanel.add(Box.createVerticalStrut(2));
		titlePanel.add(subtitle);
		titlePanel.add(Box.createVerticalStrut(6));
		titlePanel.add(metrics);

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
		sort.setToolTipText("Sort the loans in the selected tab");
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

		JLabel filterLabel = new JLabel("QUICK FILTER");
		filterLabel.setFont(FontManager.getRunescapeSmallFont());
		filterLabel.setForeground(ItemLoansTheme.GOLD);
		JPanel filterRow = new JPanel(new GridLayout(1, LoanFilter.values().length, 3, 0));
		filterRow.setOpaque(false);
		for (LoanFilter filter : LoanFilter.values())
		{
			JButton button = new JButton(filter.label);
			button.setToolTipText(filter.tooltip);
			button.addActionListener(event -> setLoanFilter(filter));
			filterButtons.put(filter, button);
			filterRow.add(button);
		}
		styleControlButton(borrowerView, false);
		borrowerView.setToolTipText("Group active loans by borrower with record and quantity totals");
		borrowerView.addActionListener(event ->
		{
			groupByBorrower = !groupByBorrower;
			styleControlButton(borrowerView, groupByBorrower);
			renderLoans();
		});

		JPanel filterPanel = new JPanel();
		filterPanel.setOpaque(false);
		filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.Y_AXIS));
		filterPanel.add(filterLabel);
		filterPanel.add(Box.createVerticalStrut(3));
		filterPanel.add(filterRow);
		filterPanel.add(Box.createVerticalStrut(4));
		filterPanel.add(borrowerView);
		setLoanFilter(LoanFilter.ALL);

		JButton export = ItemLoansTheme.button("Export", ItemLoansTheme.ButtonKind.SECONDARY);
		export.setToolTipText("Save item notes and loan records to a JSON backup");
		export.addActionListener(event -> exportBackup());
		JButton importBackup = ItemLoansTheme.button("Import", ItemLoansTheme.ButtonKind.SECONDARY);
		importBackup.setToolTipText("Merge a RuneLedger JSON backup into this profile");
		importBackup.addActionListener(event -> importBackup());
		JPanel backupRow = new JPanel(new GridLayout(1, 2, 4, 0));
		backupRow.setOpaque(false);
		backupRow.add(export);
		backupRow.add(importBackup);

		JButton reviewAgreement = ItemLoansTheme.button(
			"Review agreement code", ItemLoansTheme.ButtonKind.PRIMARY);
		reviewAgreement.setToolTipText("Review a loan offer or accepted receipt shared with you");
		reviewAgreement.addActionListener(event -> reviewAgreementCode());

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

		referenceReadyCode.setEditable(false);
		referenceReadyCode.setHorizontalAlignment(SwingConstants.CENTER);
		referenceReadyCode.setFont(FontManager.getRunescapeBoldFont());
		ItemLoansTheme.styleInput(referenceReadyCode);
		JButton copyReadyReference = ItemLoansTheme.button("Copy", ItemLoansTheme.ButtonKind.PRIMARY);
		copyReadyReference.addActionListener(event ->
			copyAgreementCode(referenceReadyCode.getText()));
		JButton closeReadyReference = ItemLoansTheme.button("Dismiss", ItemLoansTheme.ButtonKind.SECONDARY);
		closeReadyReference.addActionListener(event ->
		{
			referenceReadyPanel.setVisible(false);
			revalidate();
		});
		JLabel readyTitle = new JLabel("REFERENCE READY");
		readyTitle.setForeground(ItemLoansTheme.GOLD_BRIGHT);
		readyTitle.setFont(FontManager.getRunescapeBoldFont());
		referenceReadyStatus.setForeground(ItemLoansTheme.PARCHMENT);
		referenceReadyStatus.setFont(FontManager.getRunescapeSmallFont());
		JPanel readyCodeRow = new JPanel(new BorderLayout(4, 0));
		readyCodeRow.setOpaque(false);
		readyCodeRow.add(referenceReadyCode, BorderLayout.CENTER);
		readyCodeRow.add(copyReadyReference, BorderLayout.EAST);
		JPanel readyActions = new JPanel(new BorderLayout());
		readyActions.setOpaque(false);
		readyActions.add(referenceReadyStatus, BorderLayout.CENTER);
		readyActions.add(closeReadyReference, BorderLayout.EAST);
		referenceReadyPanel.setBackground(ItemLoansTheme.SURFACE_RAISED);
		referenceReadyPanel.setBorder(ItemLoansTheme.surfaceBorder());
		referenceReadyPanel.setLayout(new BoxLayout(referenceReadyPanel, BoxLayout.Y_AXIS));
		referenceReadyPanel.add(readyTitle);
		referenceReadyPanel.add(Box.createVerticalStrut(4));
		referenceReadyPanel.add(readyCodeRow);
		referenceReadyPanel.add(Box.createVerticalStrut(4));
		referenceReadyPanel.add(readyActions);
		referenceReadyPanel.setVisible(false);

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
		header.add(filterPanel);
		header.add(Box.createVerticalStrut(8));
		header.add(tabRow);
		header.add(Box.createVerticalStrut(6));
		header.add(undoPanel);
		header.add(Box.createVerticalStrut(6));
		header.add(referenceReadyPanel);
		header.add(Box.createVerticalStrut(6));
		header.add(reviewAgreement);
		header.add(Box.createVerticalStrut(4));
		header.add(backupRow);

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
		JLabel detailsCount = new JLabel();
		details.setLineWrap(true);
		details.setWrapStyleWord(true);
		ItemLoansTheme.styleInput(borrower);
		ItemLoansTheme.styleSpinner(quantity);
		ItemLoansTheme.styleInput(dueDate);
		ItemLoansTheme.styleInput(details);
		JScrollPane detailsScroll = new JScrollPane(details);
		detailsScroll.setBorder(BorderFactory.createLineBorder(ItemLoansTheme.GOLD_DARK));
		detailsScroll.getViewport().setBackground(ItemLoansTheme.INPUT);
		bindCharacterCount(details, detailsCount);

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
		form.add(detailsCount);

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
		long totalOverdue = loans.stream().filter(ItemLoansPanel::isOverdue).count();
		activeMetric.setText(Long.toString(totalActive));
		overdueMetric.setText(Long.toString(totalOverdue));
		returnedMetric.setText(Long.toString(totalHistory));
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
		borrowerView.setEnabled(!history);
		for (JButton button : filterButtons.values())
		{
			button.setEnabled(!history);
		}
		renderLoans();
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
			if (loan.isReturned() == returned && matchesSearch(loan, query)
				&& (returned || matchesFilter(loan, loanFilter, System.currentTimeMillis())))
			{
				matches.add(loan);
			}
		}
		matches.sort(getComparator(returned));
		return matches;
	}

	private Comparator<ItemLoan> getComparator(boolean returned)
	{
		LoanSort selected = (LoanSort) sort.getSelectedItem();
		if (selected == LoanSort.OLDEST)
		{
			return Comparator.comparingLong(loan -> getSortTimestamp(loan, returned));
		}
		if (selected == LoanSort.NEWEST)
		{
			return Comparator.comparingLong((ItemLoan loan) -> getSortTimestamp(loan, returned)).reversed();
		}
		if (selected == LoanSort.DUE_DATE)
		{
			return Comparator.comparingLong((ItemLoan loan) ->
					loan.getDueAt() == 0 ? Long.MAX_VALUE : loan.getDueAt())
				.thenComparingLong(ItemLoan::getRecordedAt);
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
				container.add(createEmptyState("NO MATCHING LOANS", "Try a different search or filter."));
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
			if (groupByBorrower && !returned)
			{
				renderBorrowerGroups(container, visibleLoans);
			}
			else
			{
				addLoanCards(container, visibleLoans);
			}
		}
		container.revalidate();
		container.repaint();
	}

	private void renderBorrowerGroups(JPanel container, List<ItemLoan> visibleLoans)
	{
		Map<String, List<ItemLoan>> byBorrower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (ItemLoan loan : visibleLoans)
		{
			byBorrower.computeIfAbsent(loan.getBorrower(), key -> new ArrayList<>()).add(loan);
		}
		for (Map.Entry<String, List<ItemLoan>> entry : byBorrower.entrySet())
		{
			List<ItemLoan> borrowerLoans = entry.getValue();
			int totalQuantity = borrowerLoans.stream().mapToInt(ItemLoan::getQuantity).sum();
			container.add(createBorrowerHeader(entry.getKey(), borrowerLoans.size(), totalQuantity));
			container.add(Box.createVerticalStrut(4));
			addLoanCards(container, borrowerLoans);
			container.add(Box.createVerticalStrut(4));
		}
	}

	private void addLoanCards(JPanel container, List<ItemLoan> visibleLoans)
	{
		for (ItemLoan loan : visibleLoans)
		{
			container.add(createLoanCard(loan));
			container.add(Box.createVerticalStrut(6));
		}
	}

	private static JPanel createBorrowerHeader(String borrower, int records, int totalQuantity)
	{
		JLabel name = new JLabel("@" + borrower);
		name.setForeground(ItemLoansTheme.GOLD_BRIGHT);
		name.setFont(FontManager.getRunescapeBoldFont());

		JLabel totals = new JLabel(records + (records == 1 ? " record" : " records")
			+ "  |  " + totalQuantity + (totalQuantity == 1 ? " item" : " items"));
		totals.setForeground(ItemLoansTheme.MUTED);
		totals.setFont(FontManager.getRunescapeSmallFont());

		JPanel header = new JPanel();
		header.setBackground(ItemLoansTheme.SURFACE_RAISED);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, ItemLoansTheme.GOLD),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setAlignmentX(LEFT_ALIGNMENT);
		name.setAlignmentX(LEFT_ALIGNMENT);
		totals.setAlignmentX(LEFT_ALIGNMENT);
		header.add(name);
		header.add(totals);
		Dimension preferred = header.getPreferredSize();
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		return header;
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

		boolean overdue = isOverdue(loan);
		JLabel status = new JLabel(loan.isReturned() ? " DONE " : overdue ? " OVERDUE " : " ACTIVE ");
		status.setOpaque(true);
		status.setHorizontalAlignment(SwingConstants.CENTER);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ItemLoansTheme.PARCHMENT);
		status.setBackground(loan.isReturned() ? ItemLoansTheme.SECONDARY
			: overdue ? ItemLoansTheme.DANGER : ItemLoansTheme.ACTIVE);
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
			String dueText = loan.isReturned()
				? "Was due " + RECORDED_DATE_FORMAT.format(Instant.ofEpochMilli(loan.getDueAt()))
				: formatDueStatus(loan.getDueAt(), System.currentTimeMillis());
			JLabel dueDate = new JLabel(dueText);
			dueDate.setFont(FontManager.getRunescapeSmallFont());
			dueDate.setForeground(overdue ? ItemLoansTheme.OVERDUE : ItemLoansTheme.PARCHMENT);
			dueDate.setToolTipText("Due "
				+ RECORDED_DATE_FORMAT.format(Instant.ofEpochMilli(loan.getDueAt())));
			details.add(dueDate);
		}

		if (loan.isReturned())
		{
			JLabel returnedAt = new JLabel("Returned " + formatTimestamp(loan.getReturnedAt()));
			returnedAt.setFont(FontManager.getRunescapeSmallFont());
			returnedAt.setForeground(ItemLoansTheme.ACTIVE_HOVER);
			details.add(returnedAt);
		}

		if (loan.getAgreementCode() != null)
		{
			JLabel agreement = new JLabel("Agreement accepted");
			agreement.setFont(FontManager.getRunescapeSmallFont());
			agreement.setForeground(ItemLoansTheme.ACTIVE_HOVER);
			try
			{
				LoanReferenceCode reference = plugin.decodeAgreement(loan.getAgreementCode());
				if (reference.getState() == LoanReferenceCode.State.ACCEPTED)
				{
					agreement.setText("Agreed by @" + reference.getBorrower());
					agreement.setToolTipText("Accepted receipt attached to this loan");
				}
				else
				{
					agreement.setText("Awaiting @" + reference.getBorrower());
					agreement.setForeground(ItemLoansTheme.GOLD);
					agreement.setToolTipText("Open View reference to copy the code and see the next steps");
				}
			}
			catch (IllegalArgumentException ex)
			{
				agreement.setText("Agreement receipt unavailable");
				agreement.setForeground(ItemLoansTheme.OVERDUE);
			}
			details.add(agreement);
			details.add(Box.createVerticalStrut(4));
			details.add(createReferenceRow(loan.getAgreementCode()));
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
		JButton agreement = ItemLoansTheme.button(
			loan.getAgreementCode() == null ? "Create reference" : "View reference",
			ItemLoansTheme.ButtonKind.SECONDARY);
		agreement.setMaximumSize(new Dimension(Integer.MAX_VALUE, agreement.getPreferredSize().height));
		agreement.setAlignmentX(LEFT_ALIGNMENT);
		agreement.addActionListener(event -> showAgreementForLoan(loan));
		actions.add(recordActions);
		actions.add(Box.createVerticalStrut(4));
		if (loan.getAgreementCode() == null)
		{
			actions.add(agreement);
		}
		else
		{
			JButton copyReference = ItemLoansTheme.button(
				"Copy reference", ItemLoansTheme.ButtonKind.PRIMARY);
			copyReference.setToolTipText("Copy " + loan.getAgreementCode());
			copyReference.addActionListener(event ->
				copyAgreementCode(loan.getAgreementCode()));
			JPanel referenceActions = new JPanel(new GridLayout(1, 2, 4, 0));
			referenceActions.setOpaque(false);
			referenceActions.add(agreement);
			referenceActions.add(copyReference);
			actions.add(referenceActions);
		}
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

	private void showAgreementForLoan(ItemLoan loan)
	{
		if (loan.getAgreementCode() != null)
		{
			try
			{
				LoanReferenceCode reference = plugin.decodeAgreement(loan.getAgreementCode());
				showAgreementCode(reference.getState() == LoanReferenceCode.State.ACCEPTED
					? "Accepted loan reference" : "Loan reference", reference,
					loan.getAgreementCode());
			}
			catch (IllegalArgumentException ex)
			{
				showAgreementError(ex.getMessage());
			}
			return;
		}

		plugin.verifyLocalPlayer(null,
			lender -> showAgreementCreationForm(loan, lender),
			this::showAgreementError);
	}

	private void showAgreementCreationForm(ItemLoan loan, String lender)
	{
		JTextField shortcut = new JTextField(LoanReferenceCode.suggestShortcut(loan.getItemName()));
		ItemLoansTheme.styleInput(shortcut);
		JPanel form = new JPanel();
		form.setBackground(ItemLoansTheme.BACKGROUND);
		form.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.add(createFieldLabel("VERIFIED LENDER"));
		form.add(createVerifiedPlayerLabel(lender));
		form.add(Box.createVerticalStrut(7));
		form.add(createFieldLabel("ITEM SHORTCUT (2-5 LETTERS OR NUMBERS)"));
		form.add(shortcut);
		form.add(Box.createVerticalStrut(8));
		form.add(createAgreementNotice());

		while (true)
		{
			int result = JOptionPane.showConfirmDialog(this, form,
				"Create chat reference for " + loan.getItemName(),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (result != JOptionPane.OK_OPTION)
			{
				return;
			}
			try
			{
				String code = plugin.createAgreementOffer(loan, lender, shortcut.getText());
				LoanReferenceCode reference = plugin.decodeAgreement(code);
				boolean copied = showReferenceReady(code);
				SwingUtilities.invokeLater(() -> showAgreementCode(
					copied ? "Reference created and copied" : "Reference created",
					reference, code));
				return;
			}
			catch (IllegalArgumentException ex)
			{
				showAgreementError(ex.getMessage());
			}
		}
	}

	private void reviewAgreementCode()
	{
		JTextField input = new JTextField();
		ItemLoansTheme.styleInput(input);
		JButton paste = ItemLoansTheme.button("Paste from clipboard", ItemLoansTheme.ButtonKind.PRIMARY);
		paste.addActionListener(event -> pasteAgreementCode(input));

		JLabel example = new JLabel("<html>Enter a reference such as "
			+ "<b>4453-QUICKSTART-VEN-0067</b><br>"
			+ "or an accepted receipt beginning with <b>OK-</b>.</html>");
		example.setForeground(ItemLoansTheme.PARCHMENT);
		JPanel form = new JPanel();
		form.setBackground(ItemLoansTheme.BACKGROUND);
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.add(example);
		form.add(Box.createVerticalStrut(8));
		form.add(input);
		form.add(Box.createVerticalStrut(6));
		form.add(paste);
		if (JOptionPane.showConfirmDialog(this, form, "Review agreement code",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}

		try
		{
			String normalizedCode = input.getText().replaceAll("\\s+", "");
			LoanReferenceCode agreement = plugin.decodeAgreement(normalizedCode);
			if (agreement.getState() == LoanReferenceCode.State.OFFER)
			{
				reviewLoanOffer(agreement);
			}
			else
			{
				reviewAcceptedReceipt(agreement, normalizedCode);
			}
		}
		catch (IllegalArgumentException ex)
		{
			showAgreementError(ex.getMessage());
		}
	}

	private void reviewLoanOffer(LoanReferenceCode offer)
	{
		JPanel contentPanel = createAgreementSummary(offer);
		int action = JOptionPane.showOptionDialog(this, contentPanel, "Review loan offer",
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
			new String[]{"Accept offer", "Close"}, "Accept offer");
		if (action != 0)
		{
			return;
		}

		plugin.verifyLocalPlayer(offer.getBorrower(),
			acceptingPlayer -> confirmLoanAcceptance(offer, acceptingPlayer),
			this::showAgreementError);
	}

	private void confirmLoanAcceptance(LoanReferenceCode offer, String acceptingPlayer)
	{
		JPanel acceptance = new JPanel();
		acceptance.setBackground(ItemLoansTheme.BACKGROUND);
		acceptance.setLayout(new BoxLayout(acceptance, BoxLayout.Y_AXIS));
		acceptance.add(createFieldLabel("VERIFIED BORROWER"));
		acceptance.add(createVerifiedPlayerLabel(acceptingPlayer));
		acceptance.add(Box.createVerticalStrut(8));
		acceptance.add(createAgreementNotice());
		if (JOptionPane.showConfirmDialog(this, acceptance, "Accept loan terms",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}

		try
		{
			String receiptCode = plugin.acceptAgreement(offer, acceptingPlayer);
			LoanReferenceCode receipt = plugin.decodeAgreement(receiptCode);
			boolean copied = showReferenceReady(receiptCode);
			SwingUtilities.invokeLater(() -> showAgreementCode(
				copied ? "Receipt accepted and copied" : "Loan offer accepted",
				receipt, receiptCode));
		}
		catch (IllegalArgumentException ex)
		{
			showAgreementError(ex.getMessage());
		}
	}

	private void reviewAcceptedReceipt(LoanReferenceCode receipt, String code)
	{
		JPanel contentPanel = createAgreementSummary(receipt);
		int action = JOptionPane.showOptionDialog(this, contentPanel, "Accepted loan agreement",
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
			new String[]{"Attach to matching loan", "Copy receipt", "Close"}, "Close");
		if (action == 0)
		{
			plugin.verifyLocalPlayer(receipt.getLender(),
				verifiedPlayer -> attachAcceptedReceipt(receipt),
				this::showAgreementError);
		}
		else if (action == 1)
		{
			copyAgreementCode(code);
		}
	}

	private void attachAcceptedReceipt(LoanReferenceCode receipt)
	{
		try
		{
			plugin.attachAgreementReceipt(receipt);
			JOptionPane.showMessageDialog(this,
				"The accepted agreement is now attached to the matching loan.",
				"Agreement attached", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IllegalArgumentException ex)
		{
			showAgreementError(ex.getMessage());
		}
	}

	private void showAgreementCode(String title, LoanReferenceCode agreement, String code)
	{
		JTextField codeField = new JTextField(code);
		codeField.setEditable(false);
		codeField.setHorizontalAlignment(SwingConstants.CENTER);
		codeField.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		ItemLoansTheme.styleInput(codeField);
		codeField.setToolTipText("Select and copy this reference manually if needed");

		JPanel contentPanel = new JPanel();
		contentPanel.setBackground(ItemLoansTheme.BACKGROUND);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.add(createAgreementSummary(agreement));
		contentPanel.add(Box.createVerticalStrut(8));
		contentPanel.add(createFieldLabel(
			agreement.getState() == LoanReferenceCode.State.OFFER ? "OFFER CODE" : "RECEIPT CODE"));
		contentPanel.add(codeField);
		contentPanel.add(Box.createVerticalStrut(9));
		contentPanel.add(createHandoffInstructions(agreement.getState()));
		String copyLabel = agreement.getState() == LoanReferenceCode.State.OFFER
			? "Copy reference" : "Copy receipt";
		int action = JOptionPane.showOptionDialog(this, contentPanel, title,
			JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
			new String[]{copyLabel, "Done"}, copyLabel);
		if (action == 0)
		{
			copyAgreementCode(code);
		}
	}

	private static JLabel createHandoffInstructions(LoanReferenceCode.State state)
	{
		String steps = state == LoanReferenceCode.State.OFFER
			? "<b>NEXT STEPS</b><br>"
				+ "1. Copy the reference above.<br>"
				+ "2. Send it manually in RuneScape chat.<br>"
				+ "3. The borrower opens <b>Review agreement code</b>.<br>"
				+ "4. They accept and send the <b>OK-</b> receipt back.<br>"
				+ "5. Review that receipt and attach it to this loan."
			: "<b>NEXT STEPS</b><br>"
				+ "1. Copy the <b>OK-</b> receipt above.<br>"
				+ "2. Send it back to the lender.<br>"
				+ "3. The lender opens <b>Review agreement code</b>.<br>"
				+ "4. They choose <b>Attach to matching loan</b>.";
		JLabel instructions = new JLabel("<html><div style='width: 330px'>" + steps + "</div></html>");
		instructions.setForeground(ItemLoansTheme.PARCHMENT);
		instructions.setFont(FontManager.getRunescapeSmallFont());
		return instructions;
	}

	private static JPanel createAgreementSummary(LoanReferenceCode agreement)
	{
		String status = agreement.getState() == LoanReferenceCode.State.ACCEPTED
			? "<font color='#577c35'><b>ACCEPTED by @" + escape(agreement.getBorrower())
				+ "</b></font>"
			: "<font color='#d6b25e'><b>AWAITING ACCEPTANCE</b></font>";
		JLabel summary = new JLabel("<html><div style='width: 330px'>"
			+ status + "<br>Transaction: <b>" + escape(agreement.getTransactionId())
			+ "</b><br><br>Item shortcut: <b>" + escape(agreement.getItemShortcut()) + "</b><br>"
			+ "Lender: @" + escape(agreement.getLender()) + "<br>"
			+ "Borrower: @" + escape(agreement.getBorrower()) + "</div></html>");
		summary.setForeground(ItemLoansTheme.PARCHMENT);

		JPanel panel = new JPanel();
		panel.setBackground(ItemLoansTheme.SURFACE);
		panel.setBorder(ItemLoansTheme.surfaceBorder());
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(summary);
		panel.add(Box.createVerticalStrut(8));
		panel.add(createAgreementNotice());
		return panel;
	}

	private JPanel createReferenceRow(String code)
	{
		LoanReferenceCode reference = plugin.decodeAgreement(code);
		JLabel label = createFieldLabel(reference.getState() == LoanReferenceCode.State.ACCEPTED
			? "ACCEPTED RECEIPT" : "LOAN REFERENCE");
		JTextField value = new JTextField(code);
		value.setEditable(false);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setToolTipText(code);
		ItemLoansTheme.styleInput(value);
		JButton copy = ItemLoansTheme.button("Copy", ItemLoansTheme.ButtonKind.SECONDARY);
		copy.addActionListener(event -> copyAgreementCode(code));
		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setOpaque(false);
		row.add(value, BorderLayout.CENTER);
		row.add(copy, BorderLayout.EAST);
		JPanel container = new JPanel();
		container.setOpaque(false);
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.add(label);
		container.add(row);
		container.setAlignmentX(LEFT_ALIGNMENT);
		return container;
	}

	private static JLabel createAgreementNotice()
	{
		JLabel notice = new JLabel("<html><div style='width: 330px'>"
			+ "This chat-safe reference does not contain quantity, due date, or details. "
			+ "RuneLedger checks the locally logged-in character when creating or accepting, "
			+ "but offline text can still be altered or fabricated. Confirm the full terms separately. "
			+ "It does not prove identity, transfer an item, or create a binding contract."
			+ "</div></html>");
		notice.setForeground(ItemLoansTheme.MUTED);
		notice.setFont(FontManager.getRunescapeSmallFont());
		return notice;
	}

	private static JLabel createVerifiedPlayerLabel(String playerName)
	{
		JLabel player = new JLabel("@"
			+ escape(playerName) + "  |  LOGGED-IN CHARACTER");
		player.setOpaque(true);
		player.setBackground(ItemLoansTheme.INPUT);
		player.setForeground(ItemLoansTheme.ACTIVE_HOVER);
		player.setFont(FontManager.getRunescapeBoldFont());
		player.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));
		player.setToolTipText("Read directly from the character logged into this RuneLite client");
		return player;
	}

	private void copyAgreementCode(String code)
	{
		if (copyAgreementCodeToClipboard(code))
		{
			JOptionPane.showMessageDialog(this,
				"Copied to the clipboard.\nSend it manually—RuneLedger will never type into game chat.",
				"Reference copied", JOptionPane.INFORMATION_MESSAGE);
		}
		else
		{
			showAgreementError("The agreement code could not be copied. Select it and copy it manually.");
		}
	}

	private boolean showReferenceReady(String code)
	{
		boolean copied = copyAgreementCodeToClipboard(code);
		LoanReferenceCode reference = plugin.decodeAgreement(code);
		boolean receipt = reference.getState() == LoanReferenceCode.State.ACCEPTED;
		referenceReadyCode.setText(code);
		referenceReadyCode.setCaretPosition(0);
		referenceReadyStatus.setText(copied
			? receipt ? "Copied - send it back to the lender" : "Copied - send it to the borrower"
			: receipt ? "Press Copy, then send it back to the lender"
				: "Press Copy, then send it to the borrower");
		referenceReadyStatus.setForeground(copied
			? ItemLoansTheme.ACTIVE_HOVER : ItemLoansTheme.OVERDUE);
		referenceReadyPanel.setVisible(true);
		revalidate();
		repaint();
		return copied;
	}

	private static boolean copyAgreementCodeToClipboard(String code)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(code), null);
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private void pasteAgreementCode(JTextField input)
	{
		try
		{
			Object value = Toolkit.getDefaultToolkit().getSystemClipboard()
				.getData(DataFlavor.stringFlavor);
			if (!(value instanceof String))
			{
				throw new IllegalArgumentException("The clipboard does not contain a RuneLedger agreement code.");
			}
			plugin.decodeAgreement((String) value);
			input.setText((String) value);
			input.setCaretPosition(0);
		}
		catch (Exception ex)
		{
			showAgreementError(ex instanceof IllegalArgumentException
				? ex.getMessage() : "The agreement code could not be read from the clipboard.");
		}
	}

	private void showAgreementError(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Invalid agreement",
			JOptionPane.ERROR_MESSAGE);
	}

	private void exportBackup()
	{
		JFileChooser chooser = createBackupChooser("Export RuneLedger backup");
		chooser.setSelectedFile(new File(chooser.getCurrentDirectory(),
			"runeledger-backup-" + LocalDate.now(LOCAL_ZONE) + ".json"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		File file = ensureJsonExtension(chooser.getSelectedFile());
		lastBackupDirectory = file.getParentFile();
		if (file.exists())
		{
			int replace = JOptionPane.showConfirmDialog(this,
				"Replace the existing file " + file.getName() + "?",
				"Replace backup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (replace != JOptionPane.YES_OPTION)
			{
				return;
			}
		}

		try
		{
			Files.write(file.toPath(), plugin.createBackupJson().getBytes(StandardCharsets.UTF_8));
			JOptionPane.showMessageDialog(this,
				"RuneLedger backup saved to:\n" + file.getAbsolutePath(),
				"Backup exported", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IOException | RuntimeException ex)
		{
			showBackupError("The backup could not be saved.", ex);
		}
	}

	private void importBackup()
	{
		JFileChooser chooser = createBackupChooser("Import RuneLedger backup");
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		File file = chooser.getSelectedFile();
		lastBackupDirectory = file.getParentFile();
		try
		{
			if (Files.size(file.toPath()) > MAX_BACKUP_SIZE)
			{
				throw new IllegalArgumentException("The selected backup is too large.");
			}
			String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			ItemNotesBackup backup = plugin.parseBackup(json);
			int merge = JOptionPane.showConfirmDialog(this,
				"<html>Merge <b>" + backup.getNoteCount() + "</b> item notes and <b>"
					+ backup.getLoanCount() + "</b> loan records into this profile?<br><br>"
					+ "Records with the same item or loan ID will be replaced.</html>",
				"Import RuneLedger backup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (merge != JOptionPane.YES_OPTION)
			{
				return;
			}
			plugin.importBackup(backup);
			JOptionPane.showMessageDialog(this,
				"Imported " + backup.getNoteCount() + " item notes and "
					+ backup.getLoanCount() + " loan records.",
				"Backup imported", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IOException | RuntimeException ex)
		{
			showBackupError("The backup could not be imported.", ex);
		}
	}

	private JFileChooser createBackupChooser(String title)
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileFilter(new FileNameExtensionFilter("RuneLedger backup (*.json)", "json"));
		chooser.setAcceptAllFileFilterUsed(false);
		if (lastBackupDirectory != null)
		{
			chooser.setCurrentDirectory(lastBackupDirectory);
		}
		return chooser;
	}

	private void showBackupError(String message, Exception ex)
	{
		String detail = ex.getMessage();
		JOptionPane.showMessageDialog(this,
			message + (detail == null ? "" : "\n" + detail),
			"RuneLedger backup", JOptionPane.ERROR_MESSAGE);
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

	private static JPanel createMetric(JLabel value, String labelText, Color valueColor)
	{
		value.setForeground(valueColor);
		value.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));

		JLabel label = new JLabel(labelText, SwingConstants.CENTER);
		label.setForeground(ItemLoansTheme.MUTED);
		label.setFont(FontManager.getRunescapeSmallFont());

		JPanel metric = new JPanel();
		metric.setBackground(ItemLoansTheme.INPUT);
		metric.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ItemLoansTheme.GOLD_DARK),
			BorderFactory.createEmptyBorder(4, 2, 4, 2)));
		metric.setLayout(new BoxLayout(metric, BoxLayout.Y_AXIS));
		value.setAlignmentX(CENTER_ALIGNMENT);
		label.setAlignmentX(CENTER_ALIGNMENT);
		metric.add(value);
		metric.add(label);
		return metric;
	}

	private void setLoanFilter(LoanFilter selected)
	{
		loanFilter = selected;
		for (Map.Entry<LoanFilter, JButton> entry : filterButtons.entrySet())
		{
			styleControlButton(entry.getValue(), entry.getKey() == selected);
		}
		renderLoans();
	}

	private static void styleControlButton(JButton button, boolean selected)
	{
		button.setBackground(selected ? ItemLoansTheme.SURFACE_RAISED : ItemLoansTheme.INPUT);
		button.setForeground(selected ? ItemLoansTheme.GOLD_BRIGHT : ItemLoansTheme.MUTED);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setFocusable(false);
		button.setOpaque(true);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(selected ? ItemLoansTheme.GOLD : ItemLoansTheme.GOLD_DARK),
			BorderFactory.createEmptyBorder(5, 3, 5, 3)));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.setAlignmentX(LEFT_ALIGNMENT);
	}

	private static void bindCharacterCount(JTextArea details, JLabel counter)
	{
		counter.setFont(FontManager.getRunescapeSmallFont());
		counter.setAlignmentX(RIGHT_ALIGNMENT);
		Runnable update = () ->
		{
			int length = details.getText().trim().length();
			counter.setText(length + " / " + ItemNotesPlugin.CHARACTER_LIMIT);
			counter.setForeground(length > ItemNotesPlugin.CHARACTER_LIMIT
				? ItemLoansTheme.OVERDUE : ItemLoansTheme.MUTED);
		};
		details.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				update.run();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				update.run();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				update.run();
			}
		});
		update.run();
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

	static boolean matchesFilter(ItemLoan loan, LoanFilter filter, long now)
	{
		if (filter == LoanFilter.ALL)
		{
			return true;
		}
		if (filter == LoanFilter.OVERDUE)
		{
			return isOverdue(loan, now);
		}
		if (filter == LoanFilter.NO_DUE_DATE)
		{
			return !loan.isReturned() && loan.getDueAt() == 0;
		}
		if (loan.isReturned() || loan.getDueAt() == 0)
		{
			return false;
		}

		LocalDate today = Instant.ofEpochMilli(now).atZone(LOCAL_ZONE).toLocalDate();
		LocalDate due = Instant.ofEpochMilli(loan.getDueAt()).atZone(LOCAL_ZONE).toLocalDate();
		long days = ChronoUnit.DAYS.between(today, due);
		return days >= 0 && days <= 7;
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

	static String formatDueStatus(long dueAt, long now)
	{
		LocalDate due = Instant.ofEpochMilli(dueAt).atZone(LOCAL_ZONE).toLocalDate();
		LocalDate today = Instant.ofEpochMilli(now).atZone(LOCAL_ZONE).toLocalDate();
		long days = ChronoUnit.DAYS.between(today, due);
		if (days < 0)
		{
			long overdueDays = -days;
			return "Overdue by " + overdueDays + (overdueDays == 1 ? " day" : " days");
		}
		if (days == 0)
		{
			return "Due today";
		}
		if (days == 1)
		{
			return "Due tomorrow";
		}
		return "Due " + RECORDED_DATE_FORMAT.format(Instant.ofEpochMilli(dueAt));
	}

	private static boolean isOverdue(ItemLoan loan)
	{
		return isOverdue(loan, System.currentTimeMillis());
	}

	static boolean isOverdue(ItemLoan loan, long now)
	{
		if (loan.isReturned() || loan.getDueAt() == 0)
		{
			return false;
		}
		LocalDate due = Instant.ofEpochMilli(loan.getDueAt()).atZone(LOCAL_ZONE).toLocalDate();
		LocalDate today = Instant.ofEpochMilli(now).atZone(LOCAL_ZONE).toLocalDate();
		return today.isAfter(due);
	}

	private static File ensureJsonExtension(File file)
	{
		if (file.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
		{
			return file;
		}
		return new File(file.getParentFile(), file.getName() + ".json");
	}

	private static long getSortTimestamp(ItemLoan loan, boolean returned)
	{
		return returned && loan.getReturnedAt() > 0 ? loan.getReturnedAt() : loan.getRecordedAt();
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

	enum LoanFilter
	{
		ALL("ALL", "Show all active loans"),
		OVERDUE("LATE", "Show overdue loans"),
		DUE_SOON("SOON", "Show loans due in the next 7 days"),
		NO_DUE_DATE("NO DATE", "Show loans without a due date");

		private final String label;
		private final String tooltip;

		LoanFilter(String label, String tooltip)
		{
			this.label = label;
			this.tooltip = tooltip;
		}
	}
}
