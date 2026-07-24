/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import com.google.common.base.Strings;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.Value;

@Value
class LoanReferenceCode
{
	private static final String ACCEPTED_PREFIX = "OK-";
	private static final Pattern SHORTCUT_PATTERN = Pattern.compile("[A-Z0-9]{2,5}");
	private static final Pattern TRANSACTION_PATTERN = Pattern.compile("[0-9]{4}");

	State state;
	String lender;
	String borrower;
	String itemShortcut;
	String transactionId;

	static LoanReferenceCode offer(String lender, String borrower, String itemShortcut,
		String transactionId)
	{
		String normalizedLender = ItemNotesPlugin.normalizeBorrower(lender);
		String normalizedBorrower = ItemNotesPlugin.normalizeBorrower(borrower);
		String normalizedShortcut = normalizeShortcut(itemShortcut);
		if (normalizedLender == null)
		{
			throw new IllegalArgumentException("Enter a valid lender RuneScape name.");
		}
		if (normalizedBorrower == null)
		{
			throw new IllegalArgumentException("The loan has an invalid borrower name.");
		}
		if (normalizedShortcut == null)
		{
			throw new IllegalArgumentException("Use a 2-5 letter or number item shortcut.");
		}
		if (!TRANSACTION_PATTERN.matcher(Strings.nullToEmpty(transactionId)).matches())
		{
			throw new IllegalArgumentException("The transaction ID must contain four digits.");
		}
		return new LoanReferenceCode(State.OFFER, normalizedLender, normalizedBorrower,
			normalizedShortcut, transactionId);
	}

	static LoanReferenceCode decode(String input)
	{
		String code = Strings.nullToEmpty(input).trim().toUpperCase(Locale.ROOT);
		State state = State.OFFER;
		if (code.startsWith(ACCEPTED_PREFIX))
		{
			state = State.ACCEPTED;
			code = code.substring(ACCEPTED_PREFIX.length());
		}

		String[] parts = code.split("-", -1);
		if (parts.length != 4)
		{
			throw new IllegalArgumentException(
				"Use a code like LENDER-BORROWER-ITEM-0067.");
		}
		LoanReferenceCode offer = offer(decodeName(parts[0]), decodeName(parts[1]),
			parts[2], parts[3]);
		return state == State.ACCEPTED ? offer.accept() : offer;
	}

	LoanReferenceCode accept()
	{
		if (state != State.OFFER)
		{
			throw new IllegalArgumentException("This loan reference is already accepted.");
		}
		return new LoanReferenceCode(State.ACCEPTED, lender, borrower, itemShortcut, transactionId);
	}

	LoanReferenceCode asOffer()
	{
		return state == State.OFFER ? this
			: new LoanReferenceCode(State.OFFER, lender, borrower, itemShortcut, transactionId);
	}

	String encode()
	{
		String offer = encodeName(lender) + "-" + encodeName(borrower) + "-"
			+ itemShortcut + "-" + transactionId;
		return state == State.ACCEPTED ? ACCEPTED_PREFIX + offer : offer;
	}

	boolean matchesOffer(LoanReferenceCode other)
	{
		return asOffer().encode().equalsIgnoreCase(other.asOffer().encode());
	}

	static String suggestShortcut(String itemName)
	{
		String compact = Strings.nullToEmpty(itemName)
			.replaceAll("[^A-Za-z0-9]", "")
			.toUpperCase(Locale.ROOT);
		return compact.substring(0, Math.min(3, compact.length()));
	}

	private static String encodeName(String name)
	{
		return name.toUpperCase(Locale.ROOT).replace("-", "~").replace(' ', '_');
	}

	private static String decodeName(String name)
	{
		return name.replace('~', '-').replace('_', ' ');
	}

	private static String normalizeShortcut(String shortcut)
	{
		String normalized = Strings.nullToEmpty(shortcut).trim().toUpperCase(Locale.ROOT);
		return SHORTCUT_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	enum State
	{
		OFFER,
		ACCEPTED
	}
}
