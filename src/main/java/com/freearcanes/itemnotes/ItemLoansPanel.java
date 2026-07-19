/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import org.apache.commons.text.StringEscapeUtils;

class ItemLoansPanel extends PluginPanel
{
	private static final Dimension ITEM_ICON_SIZE = new Dimension(32, 32);
	private static final DateTimeFormatter RECORDED_DATE_FORMAT = DateTimeFormatter
		.ofLocalizedDate(FormatStyle.MEDIUM)
		.withLocale(Locale.getDefault())
		.withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter RECORDED_TIME_FORMAT = DateTimeFormatter
		.ofLocalizedTime(FormatStyle.SHORT)
		.withLocale(Locale.getDefault())
		.withZone(ZoneId.systemDefault());

	private final ItemNotesPlugin plugin;
	private final ItemManager itemManager;
	private final JLabel summary = new JLabel();
	private final JPanel loansContainer = new JPanel();
	private final PluginErrorPanel emptyPanel = new PluginErrorPanel();

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

		JPanel header = new JPanel();
		header.setOpaque(false);
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.add(title);
		header.add(Box.createVerticalStrut(3));
		header.add(summary);

		loansContainer.setOpaque(false);
		loansContainer.setLayout(new BoxLayout(loansContainer, BoxLayout.Y_AXIS));

		emptyPanel.setContent("No active loans",
			"Add <b>@username</b> to an item note to track who has it.");

		add(header, BorderLayout.NORTH);
		add(loansContainer, BorderLayout.CENTER);
	}

	void showLoans(List<ItemLoan> loans)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> showLoans(loans));
			return;
		}

		loansContainer.removeAll();
		summary.setText(loans.size() + (loans.size() == 1 ? " active loan" : " active loans"));

		if (loans.isEmpty())
		{
			loansContainer.add(emptyPanel);
		}
		else
		{
			for (ItemLoan loan : loans)
			{
				loansContainer.add(createLoanCard(loan));
				loansContainer.add(Box.createVerticalStrut(6));
			}
		}

		loansContainer.revalidate();
		loansContainer.repaint();
	}

	private JPanel createLoanCard(ItemLoan loan)
	{
		JPanel card = new JPanel(new BorderLayout(8, 6));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 146));

		JLabel icon = new JLabel();
		icon.setPreferredSize(ITEM_ICON_SIZE);
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(loan.getItemId()).addTo(icon);

		JLabel itemName = new JLabel("<html><b>" + escape(loan.getItemName()) + "</b></html>");
		itemName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel borrower = new JLabel("Loaned to @" + loan.getBorrower());
		borrower.setForeground(ColorScheme.BRAND_ORANGE);

		Instant recordedInstant = Instant.ofEpochMilli(loan.getRecordedAt());
		JLabel recordedAt = new JLabel("<html>Recorded<br>"
			+ RECORDED_DATE_FORMAT.format(recordedInstant) + "<br>"
			+ RECORDED_TIME_FORMAT.format(recordedInstant) + "</html>");
		recordedAt.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		recordedAt.setFont(FontManager.getRunescapeSmallFont());

		JLabel note = new JLabel("<html>" + escape(loan.getNote()) + "</html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());

		JPanel details = new JPanel();
		details.setOpaque(false);
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.add(itemName);
		details.add(borrower);
		details.add(recordedAt);
		details.add(note);

		JButton returned = new JButton("Mark returned");
		returned.setFocusable(false);
		returned.addActionListener(e -> plugin.markReturned(loan));

		card.add(icon, BorderLayout.WEST);
		card.add(details, BorderLayout.CENTER);
		card.add(returned, BorderLayout.SOUTH);
		return card;
	}

	private static String escape(String value)
	{
		return StringEscapeUtils.escapeHtml4(value);
	}
}
