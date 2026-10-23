/*
 * Copyright (c) 2026, vividflash
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.vividflash.qol.features;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/**
 * Menu and config string handling shared by the features: wildcard list
 * parsing, combat-level stripping, and bank option parsing.
 */
final class MenuText
{
    /** Combat level suffix on an NPC menu target, e.g. "Guard  (level-21)". */
    private static final String LEVEL_MARKER = "(level";

    private static final String WITHDRAW_PREFIX = "withdraw";

    /** Prefix of a numbered bank withdraw option, e.g. "Withdraw-10". */
    private static final String NUMBERED_WITHDRAW_PREFIX = "withdraw-";

    /** Returned by {@link #withdrawAmount} when the bank or inventory cannot be read. */
    static final int UNKNOWN_AMOUNT = Integer.MAX_VALUE;

    private static final int INVENTORY_SLOTS = 28;

    private MenuText()
    {
    }

    /**
     * Compiles a comma or newline separated list into whole-string patterns.
     * Entries are lower-cased, so match against a lower-cased value.
     */
    static List<Pattern> globList(String csv)
    {
        List<Pattern> patterns = new ArrayList<>();
        if (csv == null)
        {
            return patterns;
        }
        for (String entry : csv.split("[,\\r\\n]"))
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty())
            {
                patterns.add(glob(trimmed));
            }
        }
        return patterns;
    }

    /** Whole-string match with {@code *} standing in for any run of characters. */
    static Pattern glob(String glob)
    {
        String[] segments = glob.toLowerCase().split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0)
            {
                regex.append(".*");
            }
            if (!segments[i].isEmpty())
            {
                regex.append(Pattern.quote(segments[i]));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    static boolean matchesAny(List<Pattern> patterns, String value)
    {
        if (value == null)
        {
            return false;
        }
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(value).matches())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the trailing combat level the client appends to NPC menu targets,
     * so a name lookup sees "master farmer" rather than "master farmer  (level-79)".
     * Takes an already tag-stripped, lower-cased target.
     */
    static String stripCombatLevel(String target)
    {
        int marker = target.indexOf(LEVEL_MARKER);
        return marker >= 0 ? target.substring(0, marker).trim() : target;
    }

    /** True for the bank's withdraw options and nothing else on that widget. */
    static boolean isWithdrawOption(String option)
    {
        return option != null && option.regionMatches(true, 0, WITHDRAW_PREFIX, 0, WITHDRAW_PREFIX.length());
    }

    /**
     * How many items a bank withdraw option asks for, or -1 when the option
     * carries no number: "Withdraw-All", "Withdraw-All-but-1" and the
     * "Withdraw-X" prompt, whose amount is typed after the click.
     */
    static int withdrawQuantity(String option)
    {
        int start = NUMBERED_WITHDRAW_PREFIX.length();
        if (option == null || option.length() <= start || option.length() > start + 9
            || !option.regionMatches(true, 0, NUMBERED_WITHDRAW_PREFIX, 0, start))
        {
            return -1;
        }
        int quantity = 0;
        for (int i = start; i < option.length(); i++)
        {
            char c = option.charAt(i);
            if (c < '0' || c > '9')
            {
                return -1;
            }
            quantity = quantity * 10 + (c - '0');
        }
        return quantity;
    }

    /**
     * How many items a bank withdraw click takes out. A numbered option counts
     * as its number. "Withdraw-All" and "Withdraw-All-but-1" count what the
     * bank holds (less one), limited by free inventory slots unless the item
     * stacks or comes out as a note. Returns -1 for the "Withdraw-X" prompt,
     * whose amount is typed after the click, and {@link #UNKNOWN_AMOUNT} when
     * the bank or inventory cannot be read. Call on the client thread.
     */
    static int withdrawAmount(Client client, int itemId, String option)
    {
        int quantity = withdrawQuantity(option);
        if (quantity >= 0)
        {
            return quantity;
        }
        boolean allButOne = "Withdraw-All-but-1".equalsIgnoreCase(option);
        if (!allButOne && !"Withdraw-All".equalsIgnoreCase(option))
        {
            return -1;
        }

        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (bank == null || inventory == null)
        {
            return UNKNOWN_AMOUNT;
        }

        int amount = allButOne ? -1 : 0;
        for (Item item : bank.getItems())
        {
            if (item.getId() == itemId)
            {
                amount += item.getQuantity();
            }
        }
        if (amount <= 0)
        {
            return 0;
        }

        ItemComposition definition = client.getItemDefinition(itemId);
        boolean asNote = client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1
            && definition.getNote() == -1 && definition.getLinkedNoteId() != -1;
        if (definition.isStackable() || asNote)
        {
            return amount;
        }

        int freeSlots = INVENTORY_SLOTS;
        for (Item item : inventory.getItems())
        {
            if (item.getId() != -1)
            {
                freeSlots--;
            }
        }
        return Math.min(amount, Math.max(freeSlots, 0));
    }
}
