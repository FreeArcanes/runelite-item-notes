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
		assertEquals("Loaned to until Friday",
			ItemNotesPlugin.removeMention("Loaned to @Clan_Mate until Friday"));
		assertEquals("Keep this reminder", ItemNotesPlugin.removeMention("Keep this reminder"));
		assertEquals("", ItemNotesPlugin.removeMention("@Alec"));
	}

	@Test
	public void testSameBorrower()
	{
		assertTrue(ItemNotesPlugin.sameBorrower("Clan Mate", "clan mate"));
		assertTrue(ItemNotesPlugin.sameBorrower(null, null));
		assertFalse(ItemNotesPlugin.sameBorrower("Alec", "Adam"));
		assertFalse(ItemNotesPlugin.sameBorrower("Alec", null));
	}
}
