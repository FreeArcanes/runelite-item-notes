/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
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
	public void testVerifiedLocalPlayerMatchesExpectedCharacter()
	{
		assertEquals("Clan Mate",
			ItemNotesPlugin.validateLocalPlayerName("Clan  Mate", "clan mate"));
		assertEquals("4453", ItemNotesPlugin.validateLocalPlayerName("4453", null));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testVerifiedLocalPlayerRejectsWrongCharacter()
	{
		ItemNotesPlugin.validateLocalPlayerName("Not Quickstart", "Quickstart");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testVerifiedLocalPlayerRequiresLogin()
	{
		ItemNotesPlugin.validateLocalPlayerName(null, null);
	}

	@Test
	public void testLoanLifecyclePreservesRecord()
	{
		ItemLoan active = new ItemLoan("loan-id", 995, "Coins", "Alec", 25,
			"For supplies", 1000, 2000, 0, true, null);

		ItemLoan edited = active.withDetails("Clan Mate", 50, "Updated", 3000);
		assertEquals("loan-id", edited.getId());
		assertEquals(1000, edited.getRecordedAt());
		assertEquals("Clan Mate", edited.getBorrower());
		assertTrue(edited.isLinkedToItemNote());
		assertNull(edited.getAgreementCode());

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

	@Test
	public void testFormatDueStatus()
	{
		ZoneId zone = ZoneId.systemDefault();
		long today = LocalDate.of(2026, 7, 24).atStartOfDay(zone).toInstant().toEpochMilli();
		long yesterday = LocalDate.of(2026, 7, 23).atStartOfDay(zone).toInstant().toEpochMilli();
		long tomorrow = LocalDate.of(2026, 7, 25).atStartOfDay(zone).toInstant().toEpochMilli();
		long nextWeek = LocalDate.of(2026, 7, 31).atStartOfDay(zone).toInstant().toEpochMilli();

		assertEquals("Overdue by 1 day", ItemLoansPanel.formatDueStatus(yesterday, today));
		assertEquals("Due today", ItemLoansPanel.formatDueStatus(today, today));
		assertEquals("Due tomorrow", ItemLoansPanel.formatDueStatus(tomorrow, today));
		assertTrue(ItemLoansPanel.formatDueStatus(nextWeek, today).startsWith("Due "));
	}

	@Test
	public void testLoanFilters()
	{
		ZoneId zone = ZoneId.systemDefault();
		long now = LocalDate.of(2026, 7, 24).atStartOfDay(zone).toInstant().toEpochMilli();
		long yesterday = LocalDate.of(2026, 7, 23).atStartOfDay(zone).toInstant().toEpochMilli();
		long nextWeek = LocalDate.of(2026, 7, 31).atStartOfDay(zone).toInstant().toEpochMilli();
		ItemLoan overdue = new ItemLoan("late", 995, "Coins", "Alec", 1,
			"", now, yesterday, 0, false, null);
		ItemLoan dueSoon = new ItemLoan("soon", 995, "Coins", "Alec", 1,
			"", now, nextWeek, 0, false, null);
		ItemLoan noDate = new ItemLoan("none", 995, "Coins", "Alec", 1,
			"", now, 0, 0, false, null);

		assertTrue(ItemLoansPanel.matchesFilter(overdue, ItemLoansPanel.LoanFilter.OVERDUE, now));
		assertTrue(ItemLoansPanel.matchesFilter(dueSoon, ItemLoansPanel.LoanFilter.DUE_SOON, now));
		assertTrue(ItemLoansPanel.matchesFilter(noDate, ItemLoansPanel.LoanFilter.NO_DUE_DATE, now));
		assertFalse(ItemLoansPanel.matchesFilter(overdue, ItemLoansPanel.LoanFilter.DUE_SOON, now));
	}

	@Test
	public void testBackupValidationAndCounts()
	{
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("note_995", "Cash stack");
		entries.put("loanRecordedAt_995", "1000");
		entries.put("loanRecord_loan-id", "{}");
		entries.put("loanLedgerVersion", "2");
		ItemNotesBackup backup = new ItemNotesBackup(
			ItemNotesBackup.CURRENT_VERSION, 1000, entries).validated();

		assertEquals(1, backup.getNoteCount());
		assertEquals(1, backup.getLoanCount());
		assertTrue(ItemNotesBackup.isBackupKey("note_995"));
		assertFalse(ItemNotesBackup.isBackupKey("note_coins"));
		assertFalse(ItemNotesBackup.isBackupKey("showTooltips"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectUnsupportedBackupVersion()
	{
		new ItemNotesBackup(99, 0, new LinkedHashMap<>()).validated();
	}

	@Test
	public void testAgreementOfferAcceptanceRoundTrip()
	{
		LoanReferenceCode offer = LoanReferenceCode.offer(
			"4453", "Quickstart", "VEN", "0067");
		assertEquals("4453-QUICKSTART-VEN-0067", offer.encode());

		LoanReferenceCode decodedOffer = LoanReferenceCode.decode(offer.encode());
		assertEquals(LoanReferenceCode.State.OFFER, decodedOffer.getState());
		assertEquals("4453", decodedOffer.getLender());
		assertEquals("QUICKSTART", decodedOffer.getBorrower());

		String receiptCode = decodedOffer.accept().encode();
		assertEquals("OK-4453-QUICKSTART-VEN-0067", receiptCode);
		LoanReferenceCode receipt = LoanReferenceCode.decode(receiptCode);
		assertEquals(LoanReferenceCode.State.ACCEPTED, receipt.getState());
		assertTrue(receipt.matchesOffer(decodedOffer));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectDamagedAgreementCode()
	{
		LoanReferenceCode.decode("4453-QUICKSTART-0067");
	}

	@Test
	public void testReferenceCodeHandlesNamesAndShortcut()
	{
		LoanReferenceCode reference = LoanReferenceCode.offer(
			"A-B", "Clan Mate", LoanReferenceCode.suggestShortcut("Venator bow"), "0042");
		assertEquals("A~B-CLAN_MATE-VEN-0042", reference.encode());
		LoanReferenceCode decoded = LoanReferenceCode.decode(reference.encode());
		assertEquals("A-B", decoded.getLender());
		assertEquals("CLAN MATE", decoded.getBorrower());
		assertEquals("VEN", decoded.getItemShortcut());
	}

	@Test
	public void testChangingLoanTermsClearsAgreement()
	{
		ItemLoan agreed = new ItemLoan("loan-id", 4151, "Abyssal whip", "Clan Mate", 1,
			"", 1000, 0, 0, false, "RL-A1.receipt");

		assertEquals("RL-A1.receipt",
			agreed.withDetails("Clan Mate", 1, "", 0).getAgreementCode());
		assertNull(agreed.withDetails("Clan Mate", 2, "", 0).getAgreementCode());
	}
}
