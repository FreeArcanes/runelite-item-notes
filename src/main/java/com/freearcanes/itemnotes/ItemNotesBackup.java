/*
 * Copyright (c) 2026, FreeArcanes
 * All rights reserved.
 */
package com.freearcanes.itemnotes;

import com.google.common.base.Strings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Value;

@Value
class ItemNotesBackup
{
	static final int CURRENT_VERSION = 1;
	private static final int MAX_ENTRIES = 10_000;
	private static final int MAX_VALUE_LENGTH = 8_192;

	int version;
	long exportedAt;
	Map<String, String> entries;

	ItemNotesBackup validated()
	{
		if (version != CURRENT_VERSION)
		{
			throw new IllegalArgumentException("This backup version is not supported.");
		}
		if (entries == null || entries.size() > MAX_ENTRIES)
		{
			throw new IllegalArgumentException("The backup contains an invalid number of records.");
		}

		Map<String, String> safeEntries = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : entries.entrySet())
		{
			String key = entry.getKey();
			String value = entry.getValue();
			if (!isBackupKey(key) || value == null || value.length() > MAX_VALUE_LENGTH)
			{
				throw new IllegalArgumentException("The backup contains an invalid RuneLedger record.");
			}
			safeEntries.put(key, value);
		}
		return new ItemNotesBackup(version, Math.max(0, exportedAt),
			Collections.unmodifiableMap(safeEntries));
	}

	int getNoteCount()
	{
		return countKeys(ItemNotesPlugin.KEY_PREFIX);
	}

	int getLoanCount()
	{
		return countKeys(ItemNotesPlugin.LOAN_RECORD_PREFIX);
	}

	private int countKeys(String prefix)
	{
		if (entries == null)
		{
			return 0;
		}
		return (int) entries.keySet().stream().filter(key -> key.startsWith(prefix)).count();
	}

	static boolean isBackupKey(String key)
	{
		if (Strings.isNullOrEmpty(key))
		{
			return false;
		}
		return key.equals(ItemNotesPlugin.LOAN_LEDGER_VERSION_KEY)
			|| hasNumericSuffix(key, ItemNotesPlugin.KEY_PREFIX)
			|| hasNumericSuffix(key, ItemNotesPlugin.LOAN_RECORDED_AT_PREFIX)
			|| hasNonEmptySuffix(key, ItemNotesPlugin.LOAN_RECORD_PREFIX);
	}

	private static boolean hasNumericSuffix(String key, String prefix)
	{
		if (!hasNonEmptySuffix(key, prefix))
		{
			return false;
		}
		for (int i = prefix.length(); i < key.length(); i++)
		{
			if (!Character.isDigit(key.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean hasNonEmptySuffix(String key, String prefix)
	{
		return key.startsWith(prefix) && key.length() > prefix.length();
	}
}
