/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ItemNotesPluginTest
{
	@Test
	public void testFindMention()
	{
		assertEquals("Clan Mate", ItemNotesPlugin.findMention("Loaned to @Clan_Mate until Friday"));
		assertEquals("Alec", ItemNotesPlugin.findMention("@Alec"));
	}

	@Test
	public void testRejectInvalidMention()
	{
		assertNull(ItemNotesPlugin.findMention("mail@example.com"));
		assertNull(ItemNotesPlugin.findMention("Loaned to @NameThatIsTooLong"));
		assertNull(ItemNotesPlugin.findMention("No borrower here"));
	}

	@Test
	public void testRemoveMentionPreservesNote()
	{
		assertEquals("Loaned to Clan Mate until Friday",
			ItemNotesPlugin.removeMention("Loaned to @Clan_Mate until Friday"));
		assertEquals("Quickstart has a shrimp",
			ItemNotesPlugin.removeMention("@Quickstart has a shrimp"));
		assertEquals("Keep this reminder", ItemNotesPlugin.removeMention("Keep this reminder"));
		assertEquals("", ItemNotesPlugin.removeMention("@Alec"));
	}

	@Test
	public void testNormalizeBorrower()
	{
		assertEquals("Clan Mate", ItemNotesPlugin.normalizeBorrower(" @Clan_Mate "));
		assertEquals("A-B 12", ItemNotesPlugin.normalizeBorrower("A-B  12"));
		assertNull(ItemNotesPlugin.normalizeBorrower(""));
		assertNull(ItemNotesPlugin.normalizeBorrower("-invalid"));
		assertNull(ItemNotesPlugin.normalizeBorrower("invalid-"));
		assertNull(ItemNotesPlugin.normalizeBorrower("NameThatIsTooLong"));
	}

	@Test
	public void testSameBorrower()
	{
		assertTrue(ItemNotesPlugin.sameBorrower("Clan Mate", "clan mate"));
		assertTrue(ItemNotesPlugin.sameBorrower(null, null));
		assertFalse(ItemNotesPlugin.sameBorrower("Alec", "Adam"));
		assertFalse(ItemNotesPlugin.sameBorrower("Alec", null));
	}

	@Test
	public void testLoanLifecyclePreservesRecord()
	{
		ItemLoan active = new ItemLoan("loan-id", 995, "Coins", "Alec", 25,
			"For supplies", 1000, 2000, 0, true);

		ItemLoan edited = active.withDetails("Clan Mate", 50, "Updated", 3000);
		assertEquals("loan-id", edited.getId());
		assertEquals(1000, edited.getRecordedAt());
		assertEquals("Clan Mate", edited.getBorrower());
		assertTrue(edited.isLinkedToItemNote());

		ItemLoan returned = edited.returned(4000);
		assertTrue(returned.isReturned());
		assertEquals(4000, returned.getReturnedAt());
		assertFalse(returned.reopened().isReturned());
	}

	@Test
	public void testReplaceMention()
	{
		assertEquals("Loaned to @New_Name until Friday",
			ItemNotesPlugin.replaceMention("Loaned to @Old_Name until Friday", "New Name"));
		assertEquals("No borrower", ItemNotesPlugin.replaceMention("No borrower", "New Name"));
	}

	@Test
	public void testFormatLoanAge()
	{
		long now = 10_000_000L;
		assertEquals("Loaned just now", ItemLoansPanel.formatLoanAge(now, now));
		assertEquals("Loaned 1 minute ago", ItemLoansPanel.formatLoanAge(now - 60_000L, now));
		assertEquals("Loaned 2 hours ago", ItemLoansPanel.formatLoanAge(now - 7_200_000L, now));
		assertEquals("Loaned 3 days ago", ItemLoansPanel.formatLoanAge(now - 259_200_000L, now));
	}
}
