/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.FontManager;

final class ItemLoansTheme
{
	static final Color BACKGROUND = new Color(24, 20, 15);
	static final Color SURFACE = new Color(39, 32, 23);
	static final Color SURFACE_RAISED = new Color(50, 41, 29);
	static final Color INPUT = new Color(29, 25, 19);
	static final Color SLOT = new Color(27, 23, 17);
	static final Color GOLD = new Color(214, 178, 94);
	static final Color GOLD_BRIGHT = new Color(240, 208, 120);
	static final Color GOLD_DARK = new Color(110, 86, 38);
	static final Color PARCHMENT = new Color(231, 216, 177);
	static final Color MUTED = new Color(168, 154, 120);
	static final Color ACTIVE = new Color(72, 105, 44);
	static final Color ACTIVE_HOVER = new Color(87, 124, 53);
	static final Color DANGER = new Color(116, 56, 47);
	static final Color DANGER_HOVER = new Color(143, 68, 57);
	static final Color SECONDARY = new Color(66, 55, 39);
	static final Color SECONDARY_HOVER = new Color(82, 68, 47);
	static final Color OVERDUE = new Color(232, 91, 72);

	private ItemLoansTheme()
	{
	}

	static Border surfaceBorder()
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(GOLD_DARK),
			BorderFactory.createEmptyBorder(8, 8, 8, 8));
	}

	static Border cardBorder()
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(17, 14, 10)),
			BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(2, 0, 0, 0, GOLD_DARK),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
	}

	static Border inputBorder()
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(GOLD_DARK),
			BorderFactory.createEmptyBorder(4, 6, 4, 6));
	}

	static void styleInput(JTextComponent input)
	{
		input.setBackground(INPUT);
		input.setForeground(PARCHMENT);
		input.setCaretColor(GOLD_BRIGHT);
		input.setSelectionColor(GOLD_DARK);
		input.setSelectedTextColor(PARCHMENT);
		input.setBorder(inputBorder());
	}

	static void styleSelect(JComboBox<?> select)
	{
		select.setBackground(SURFACE_RAISED);
		select.setForeground(PARCHMENT);
		select.setFocusable(false);
		select.setBorder(BorderFactory.createLineBorder(GOLD_DARK));
	}

	static void styleSpinner(JSpinner spinner)
	{
		spinner.setBackground(INPUT);
		spinner.setBorder(BorderFactory.createLineBorder(GOLD_DARK));
		if (spinner.getEditor() instanceof JSpinner.DefaultEditor)
		{
			styleInput(((JSpinner.DefaultEditor) spinner.getEditor()).getTextField());
		}
	}

	static JButton button(String text, ButtonKind kind)
	{
		Color background;
		Color hover;
		Color foreground = PARCHMENT;
		switch (kind)
		{
			case PRIMARY:
				background = ACTIVE;
				hover = ACTIVE_HOVER;
				break;
			case DANGER:
				background = DANGER;
				hover = DANGER_HOVER;
				break;
			default:
				background = SECONDARY;
				hover = SECONDARY_HOVER;
				break;
		}

		JButton button = new JButton(text);
		button.setBackground(background);
		button.setForeground(foreground);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(GOLD_DARK),
			BorderFactory.createEmptyBorder(4, 7, 4, 7)));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setFocusPainted(false);
		button.setFocusable(false);
		button.setOpaque(true);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent event)
			{
				button.setBackground(hover);
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				button.setBackground(background);
			}
		});
		return button;
	}

	enum ButtonKind
	{
		PRIMARY,
		SECONDARY,
		DANGER
	}
}
