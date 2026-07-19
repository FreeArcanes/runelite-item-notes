/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import lombok.Value;

@Value
class ItemLoan
{
	int itemId;
	String itemName;
	String borrower;
	String note;
	long recordedAt;
}
