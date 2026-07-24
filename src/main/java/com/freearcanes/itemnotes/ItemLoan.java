/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import java.util.Objects;
import lombok.Value;

@Value
class ItemLoan
{
	String id;
	int itemId;
	String itemName;
	String borrower;
	int quantity;
	String note;
	long recordedAt;
	long dueAt;
	long returnedAt;
	boolean linkedToItemNote;
	String agreementCode;

	boolean isReturned()
	{
		return returnedAt > 0;
	}

	ItemLoan withDetails(String newBorrower, int newQuantity, String newNote, long newDueAt)
	{
		boolean sameTerms = ItemNotesPlugin.sameBorrower(borrower, newBorrower)
			&& quantity == newQuantity && Objects.equals(note, newNote) && dueAt == newDueAt;
		return new ItemLoan(id, itemId, itemName, newBorrower, newQuantity, newNote,
			recordedAt, newDueAt, returnedAt, linkedToItemNote, sameTerms ? agreementCode : null);
	}

	ItemLoan withAgreementCode(String newAgreementCode)
	{
		return new ItemLoan(id, itemId, itemName, borrower, quantity, note,
			recordedAt, dueAt, returnedAt, linkedToItemNote, newAgreementCode);
	}

	ItemLoan returned(long timestamp)
	{
		return new ItemLoan(id, itemId, itemName, borrower, quantity, note,
			recordedAt, dueAt, timestamp, false, agreementCode);
	}

	ItemLoan reopened()
	{
		return new ItemLoan(id, itemId, itemName, borrower, quantity, note,
			recordedAt, dueAt, 0, false, agreementCode);
	}
}
