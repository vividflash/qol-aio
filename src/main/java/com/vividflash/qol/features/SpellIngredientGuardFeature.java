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

import com.vividflash.qol.QolConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Superglass Make and Arceuus offering-spell misclick guards. The sulphurous
 * essence lockout lives in {@link NeypotzliEssenceGuardFeature}.
 *
 * Counts are tracked via {@link ItemContainerChanged} (authoritative inventory
 * state), not read live off the inventory widget at click time: a live read
 * lags a tick behind the click and lets repeated withdrawals slip past the cap.
 *
 * Sinister/Demonic Offering skip the min-count block while the Fountain of
 * Rune's effect is active, since a cast there consumes no runes and a partial
 * offering wastes nothing.
 */
@Slf4j
@Singleton
public class SpellIngredientGuardFeature implements Feature
{
    private static final int[] SINISTER_BONES = {
        ItemID.BONES,
        ItemID.MM_NORMAL_MONKEY_BONES,
        ItemID.BAT_BONES,
        ItemID.BIG_BONES,
        ItemID.TBWT_JOGRE_BONES,
        ItemID.BABYWYRM_BONES,
        ItemID.ZOGRE_BONES,
        ItemID.TBWT_BEAST_BONES,
        ItemID.BABYDRAGON_BONES,
        ItemID.WYRM_BONES,
        ItemID.DRAGON_BONES,
        ItemID.WYVERN_BONES,
        ItemID.DRAKE_BONES,
        ItemID.ZOGRE_ANCESTRAL_BONES_FAYG,
        ItemID.LAVA_DRAGON_BONES,
        ItemID.ZOGRE_ANCESTRAL_BONES_RAURG,
        ItemID.FROST_DRAGON_BONES,
        ItemID.HYDRA_BONES,
        ItemID.DAGANNOTH_KING_BONES,
        ItemID.ZOGRE_ANCESTRAL_BONES_OURG,
        ItemID.DRAGON_BONES_SUPERIOR,
        ItemID.STRYKEWYRM_BONES,
    };

    private static final int[] DEMONIC_ASHES = {
        ItemID.FIENDISH_ASHES,
        ItemID.VILE_ASHES,
        ItemID.MALICIOUS_ASHES,
        ItemID.ABYSSAL_ASHES,
        ItemID.INFERNAL_ASHES,
    };

    private static final Set<Integer> TRACKED_ITEMS = new HashSet<>();
    static
    {
        TRACKED_ITEMS.add(ItemID.BUCKET_SAND);
        TRACKED_ITEMS.add(ItemID.GIANT_SEAWEED);
        for (int id : SINISTER_BONES)
        {
            TRACKED_ITEMS.add(id);
        }
        for (int id : DEMONIC_ASHES)
        {
            TRACKED_ITEMS.add(id);
        }
    }

    private final Map<Integer, Integer> trackedCounts = new HashMap<>();

    /**
     * Noted sand and seaweed carried, counted toward the withdraw caps only:
     * the casts need the unnoted items. Note ids are looked up on first use,
     * on the client thread.
     */
    private int notedSandId = -2;
    private int notedSeaweedId = -2;
    private int notedSand;
    private int notedSeaweed;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        if (log.isDebugEnabled())
        {
            log.debug("[config] spellguard superglass={} sand={} seaweed={} sinister={} minBones={} demonic={} minAshes={}",
                config.preventBadSuperglassMake(), config.superglassMakeSandAmount(), config.superglassMakeSeaweedAmount(),
                config.preventBadSinisterOffering(), config.sinisterOfferingMinBones(),
                config.preventBadDemonicOffering(), config.demonicOfferingMinAshes());
        }
        eventBus.register(this);
        // Nothing fires ItemContainerChanged until the next inventory change,
        // so starting up already logged in would count nothing carried.
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                primeCounts();
            }
        });
    }

    /** The inventory is null until the player exists, hence the guard. */
    private void primeCounts()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null)
        {
            recount(inventory);
        }
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        trackedCounts.clear();
        notedSand = 0;
        notedSeaweed = 0;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INV)
        {
            return;
        }

        recount(event.getItemContainer());
    }

    private void recount(ItemContainer inventory)
    {
        if (notedSandId == -2)
        {
            notedSandId = client.getItemDefinition(ItemID.BUCKET_SAND).getLinkedNoteId();
            notedSeaweedId = client.getItemDefinition(ItemID.GIANT_SEAWEED).getLinkedNoteId();
        }

        trackedCounts.clear();
        notedSand = 0;
        notedSeaweed = 0;
        for (Item item : inventory.getItems())
        {
            int id = item.getId();
            if (TRACKED_ITEMS.contains(id))
            {
                trackedCounts.merge(id, item.getQuantity(), Integer::sum);
            }
            else if (id != -1 && id == notedSandId)
            {
                notedSand += item.getQuantity();
            }
            else if (id != -1 && id == notedSeaweedId)
            {
                notedSeaweed += item.getQuantity();
            }
        }
    }

    /** Carried amount for a withdraw cap, noted copies included. */
    private int withdrawCount(int itemId)
    {
        int noted = itemId == ItemID.BUCKET_SAND ? notedSand : itemId == ItemID.GIANT_SEAWEED ? notedSeaweed : 0;
        return trackedCounts.getOrDefault(itemId, 0) + noted;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        int widgetId = event.getParam1();

        if (widgetId == InterfaceID.Bankmain.ITEMS)
        {
            // Withdraw options only, so Examine and the other bank item
            // options are left alone.
            if (config.preventBadSuperglassMake() && MenuText.isWithdrawOption(event.getMenuOption()))
            {
                int itemId = event.getItemId();
                if (itemId == ItemID.BUCKET_SAND)
                {
                    bulkWithdrawAllowed(event, ItemID.BUCKET_SAND, config.superglassMakeSandAmount(), "sand");
                }
                else if (itemId == ItemID.GIANT_SEAWEED)
                {
                    withdrawAllowed(event, ItemID.GIANT_SEAWEED, config.superglassMakeSeaweedAmount(), "seaweed");
                }
            }
            return;
        }

        if (widgetId == InterfaceID.MagicSpellbook.SUPERGLASS)
        {
            if (config.preventBadSuperglassMake())
            {
                int sand = count(ItemID.BUCKET_SAND);
                int seaweed = count(ItemID.GIANT_SEAWEED);
                int sandAmount = config.superglassMakeSandAmount();
                int seaweedAmount = config.superglassMakeSeaweedAmount();
                if (sand != sandAmount || seaweed != seaweedAmount)
                {
                    block(event, "WL: Superglass Make", "Need exactly " + sandAmount + " sand + "
                        + seaweedAmount + " seaweed (have " + sand + "/" + seaweed + ").");
                }
            }
            return;
        }

        if (widgetId == InterfaceID.MagicSpellbook.SINISTER_OFFERING && config.preventBadSinisterOffering() && !nearFountainOfRune())
        {
            int bones = count(SINISTER_BONES);
            int minBones = config.sinisterOfferingMinBones();
            if (bones < minBones)
            {
                block(event, "Block spells", "Only " + bones + " bones (need " + minBones + ").");
                return;
            }
        }

        if (widgetId == InterfaceID.MagicSpellbook.DEMONIC_OFFERING && config.preventBadDemonicOffering() && !nearFountainOfRune())
        {
            int ashes = count(DEMONIC_ASHES);
            int minAshes = config.demonicOfferingMinAshes();
            if (ashes < minAshes)
            {
                block(event, "Block spells", "Only " + ashes + " ashes (need " + minAshes + ").");
            }
        }
    }

    /**
     * True while the Fountain of Rune's effect is on the player, where
     * offering spells cost no runes.
     */
    private boolean nearFountainOfRune()
    {
        return client.getVarbitValue(VarbitID.FOUNTAIN_OF_RUNE_ACTIVE) == 1;
    }

    /**
     * Stricter than {@link #withdrawAllowed}: requires the inventory to be
     * completely empty of this item before allowing a withdrawal (so a leftover
     * item can never combine with a fresh withdrawal to overshoot the target),
     * and requires either the exact "Withdraw-{target}" preset button or the
     * custom "Withdraw-X" prompt (its typed number is not in the click event,
     * it comes from a separate chat input step, so it cannot be checked here
     * and is trusted).
     */
    private boolean bulkWithdrawAllowed(MenuOptionClicked event, int itemId, int target, String itemLabel)
    {
        int current = withdrawCount(itemId);
        if (current > 0)
        {
            blockWithdraw(event, "Deposit your " + itemLabel + " first.");
            return false;
        }

        String option = event.getMenuOption();
        boolean isExactPreset = ("Withdraw-" + target).equalsIgnoreCase(option);
        boolean isCustomPrompt = "Withdraw-X".equalsIgnoreCase(option);
        if (!isExactPreset && !isCustomPrompt)
        {
            blockWithdraw(event, "Set withdraw quantity to X=" + target + ".");
            return false;
        }

        trackedCounts.merge(itemId, target, Integer::sum);
        return true;
    }

    /**
     * Returns false (and consumes the event) if this withdrawal should be blocked,
     * including a numbered or Withdraw-All option that takes out more than
     * is left under the cap.
     * Otherwise optimistically bumps the tracked count so a rapid follow-up click
     * still sees an up-to-date value even before the container update lands.
     */
    private boolean withdrawAllowed(MenuOptionClicked event, int itemId, int limit, String description)
    {
        int current = withdrawCount(itemId);
        if (current >= limit)
        {
            blockWithdraw(event, "Enough " + description + " already.");
            return false;
        }

        int remaining = limit - current;
        int requested = MenuText.withdrawAmount(client, itemId, event.getMenuOption());
        if (requested > remaining)
        {
            blockWithdraw(event, "Only " + remaining + " more " + description + " allowed.");
            return false;
        }

        // The Withdraw-X prompt carries no number, so it counts as the full
        // headroom.
        trackedCounts.merge(itemId, requested < 0 ? remaining : requested, Integer::sum);
        return true;
    }

    private void block(MenuOptionClicked event, String prefix, String message)
    {
        event.consume();
        log.debug("[spellguard] blocked click ({}): {}", prefix, message);
        if (config.showBlockMessages())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[" + prefix + "] " + message, null);
        }
    }

    private void blockWithdraw(MenuOptionClicked event, String message)
    {
        event.consume();
        log.debug("[spellguard] blocked withdraw: {}", message);
        if (config.showBlockMessages())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[WL: Superglass Make] " + message, null);
        }
    }

    private int count(int itemId)
    {
        return trackedCounts.getOrDefault(itemId, 0);
    }

    private int count(int[] itemIds)
    {
        int total = 0;
        for (int id : itemIds)
        {
            total += trackedCounts.getOrDefault(id, 0);
        }
        return total;
    }
}
