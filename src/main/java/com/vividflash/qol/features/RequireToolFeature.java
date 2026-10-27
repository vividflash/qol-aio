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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

// Removes "Chop"/"Cut" on trees (and a few tree-like objects) when no axe is equipped/carried, and
// "Mine" on rocks when no pickaxe is equipped/carried. A "require fishing tool" toggle covers
// fishing spots, per the wiki's Fishing equipment table: Net (small net), Big Net, Bait (rod/oily
// rod or their pearl versions), Lure (fly rod or pearl fly rod), Rod (barbarian rod/pearl barbarian
// rod), Fish (karambwan vessel), Cage (lobster pot), and Harpoon.
//
// Harpoon is special: Otto Godblessed's Barbarian Training lets you fish tuna/swordfish and (much
// later) shark bare-handed, so removing the option whenever no harpoon is carried would delete a
// legitimate training method. The levels stand in for the unlock rather than a varbit, which keeps
// the check to values the API states plainly; a player who has the levels but has not bought the
// unlock keeps an option that does nothing, which is the side to err on. The spot's tier is read
// off its sibling option: a Big Net sibling means shark, anything else means tuna/swordfish.
// Tempoross harpoonfish need no harpoon at all, and squid spots (Harpoon with no fishing sibling)
// always do, so those two are handled by NPC id and by a lantern check respectively.
//
// Uses PostMenuSort, the event meant for menu-entry removal after RuneLite's own sort pass
// completes.
@Slf4j
@Singleton
public class RequireToolFeature implements Feature
{
    private static final Set<Integer> AXE_IDS = Set.of(
        ItemID.IRON_AXE, ItemID.BRONZE_AXE, ItemID.STEEL_AXE, ItemID.MITHRIL_AXE,
        ItemID.ADAMANT_AXE, ItemID.RUNE_AXE, ItemID.BLACK_AXE, ItemID.DRAGON_AXE,
        ItemID.ANMA_AXE, ItemID.INFERNAL_AXE, ItemID.INFERNAL_AXE_EMPTY, ItemID._3A_AXE,
        ItemID.TRAIL_GILDED_AXE, ItemID.CRYSTAL_AXE, ItemID.CRYSTAL_AXE_INACTIVE,
        ItemID.GAUNTLET_AXE_HM, ItemID.GAUNTLET_AXE, ItemID.TRAILBLAZER_AXE,
        ItemID.LEAGUE_TRAILBLAZER_AXE, ItemID.TRAILBLAZER_AXE_EMPTY,
        ItemID.TRAILBLAZER_AXE_NO_INFERNAL, ItemID.BRONZE_AXE_2H, ItemID.IRON_AXE_2H,
        ItemID.STEEL_AXE_2H, ItemID.BLACK_AXE_2H, ItemID.MITHRIL_AXE_2H,
        ItemID.ADAMANT_AXE_2H, ItemID.RUNE_AXE_2H, ItemID.DRAGON_AXE_2H,
        ItemID.CRYSTAL_AXE_2H, ItemID.CRYSTAL_AXE_2H_INACTIVE, ItemID._3A_AXE_2H,
        ItemID.TRAILBLAZER_RELOADED_AXE, ItemID.TRAILBLAZER_RELOADED_AXE_EMPTY,
        ItemID.TRAILBLAZER_RELOADED_AXE_NO_INFERNAL
    );

    private static final Set<Integer> PICKAXE_IDS = Set.of(
        ItemID.BRONZE_PICKAXE, ItemID.IRON_PICKAXE, ItemID.STEEL_PICKAXE,
        ItemID.ADAMANT_PICKAXE, ItemID.MITHRIL_PICKAXE, ItemID.RUNE_PICKAXE,
        ItemID.NZONE_RUNE_PICKAXE, ItemID.NZONE_MITHRIL_PICKAXE, ItemID.NZONE_IRON_PICKAXE,
        ItemID.DRAGON_PICKAXE, ItemID.BLACK_PICKAXE, ItemID.DRAGON_PICKAXE_PRETTY,
        ItemID.INFERNAL_PICKAXE, ItemID.INFERNAL_PICKAXE_EMPTY, ItemID._3A_PICKAXE,
        ItemID.TRAIL_GILDED_PICKAXE, ItemID.ZALCANO_PICKAXE, ItemID.CRYSTAL_PICKAXE,
        ItemID.CRYSTAL_PICKAXE_INACTIVE, ItemID.GAUNTLET_PICKAXE_HM, ItemID.GAUNTLET_PICKAXE,
        ItemID.TRAILBLAZER_PICKAXE, ItemID.LEAGUE_TRAILBLAZER_PICKAXE,
        ItemID.TRAILBLAZER_PICKAXE_EMPTY, ItemID.TRAILBLAZER_PICKAXE_NO_INFERNAL,
        ItemID.TRAILBLAZER_RELOADED_PICKAXE, ItemID.TRAILBLAZER_RELOADED_PICKAXE_EMPTY,
        ItemID.TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL
    );

    private static final Set<Integer> HARPOON_IDS = Set.of(
        ItemID.HARPOON, ItemID.DRAGON_HARPOON, ItemID.INFERNAL_HARPOON, ItemID.INFERNAL_HARPOON_EMPTY,
        ItemID.CRYSTAL_HARPOON, ItemID.CRYSTAL_HARPOON_INACTIVE, ItemID.CRYSTAL_HARPOON_FISHING,
        ItemID.GAUNTLET_HARPOON, ItemID.GAUNTLET_HARPOON_HM, ItemID.GAUNTLET_HM_HARPOON_FISHING,
        ItemID.TRAILBLAZER_HARPOON, ItemID.LEAGUE_TRAILBLAZER_HARPOON, ItemID.TRAILBLAZER_HARPOON_FISHING,
        ItemID.LEAGUE_TRAILBLAZER_HARPOON_FISHING, ItemID.TRAILBLAZER_HARPOON_EMPTY,
        ItemID.TRAILBLAZER_HARPOON_NO_INFERNAL, ItemID.TRAILBLAZER_HARPOON_FISHING_NO_INFERNAL,
        ItemID.TRAILBLAZER_RELOADED_HARPOON, ItemID.TRAILBLAZER_RELOADED_HARPOON_EMPTY,
        ItemID.TRAILBLAZER_RELOADED_HARPOON_FISHING, ItemID.TRAILBLAZER_RELOADED_HARPOON_NO_INFERNAL,
        ItemID.TRAILBLAZER_RELOADED_HARPOON_FISHING_NO_INFERNAL
    );

    // Bare-handed fishing levels (Otto Godblessed's Barbarian Training).
    // Tuna 55/35 and swordfish 70/50 share a spot, so the lower pair gates it.
    private static final int BAREHAND_FISHING_LEVEL = 55;
    private static final int BAREHAND_STRENGTH_LEVEL = 35;
    private static final int BAREHAND_SHARK_FISHING_LEVEL = 96;
    private static final int BAREHAND_SHARK_STRENGTH_LEVEL = 76;

    private static final String HARPOON_OPTION = "Harpoon";
    private static final String BIG_NET_OPTION = "Big Net";

    // Tempoross harpoonfish are catchable with no harpoon at all, so these
    // spots are exempt outright rather than level-gated.
    private static final Set<Integer> TEMPOROSS_SPOT_IDS = Set.of(
        NpcID.TEMPOROSS_HARPOONFISH_FISHINGSPOT_NORTH, NpcID.TEMPOROSS_HARPOONFISH_FISHINGSPOT_SOUTH,
        NpcID.TEMPOROSS_HARPOONFISH_FISHINGSPOT_SPECIAL, NpcID.TEMPOROSS_P2_FISHINGSPOT,
        NpcID.TEMPOROSS_P2_FISHINGSPOT_INACTIVE
    );

    // Lit lanterns only (an unlit one won't do), plus every abyssal form.
    private static final Set<Integer> LANTERN_IDS = Set.of(
        ItemID.CANDLE_LANTERN_LIT, ItemID.CANDLE_LANTERN_BLACK_LIT, ItemID.OIL_LANTERN_LIT,
        ItemID.BULLSEYE_LANTERN_LIT, ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST,
        ItemID.TOG_SAPPHIRE_LANTERN_LIT, ItemID.ABYSSAL_LANTERN, ItemID.ABYSSAL_LANTERN_NORMAL,
        ItemID.ABYSSAL_LANTERN_NORMAL_BLUE, ItemID.ABYSSAL_LANTERN_NORMAL_RED,
        ItemID.ABYSSAL_LANTERN_NORMAL_WHITE, ItemID.ABYSSAL_LANTERN_NORMAL_PURPLE,
        ItemID.ABYSSAL_LANTERN_NORMAL_GREEN, ItemID.ABYSSAL_LANTERN_OAK,
        ItemID.ABYSSAL_LANTERN_WILLOW, ItemID.ABYSSAL_LANTERN_MAPLE, ItemID.ABYSSAL_LANTERN_YEW,
        ItemID.ABYSSAL_LANTERN_BLISTERWOOD, ItemID.ABYSSAL_LANTERN_MAGIC,
        ItemID.ABYSSAL_LANTERN_REDWOOD
    );

    private static final Set<String> CUT_OVERRIDES = new HashSet<>(Set.of("Sulliuscep"));
    private static final Set<String> CHOP_OVERRIDES = new HashSet<>(Set.of(
        "Jungle Bush", "Pineapple plant", "Vines", "Tendrils", "Bruma roots",
        "Rotten sapling", "Sapling", "Thick vine", "Thick vines", "Corrupt Phren Roots", "Phren Roots"
    ));

    private static final Set<MenuAction> NPC_OPTION_TYPES = Set.of(
        MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
        MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION
    );

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Held items are cached rather than read live: onPostMenuSort runs on every
    // menu rebuild, and the tool sets are 20-35 ids each, so a live read costs
    // far more than refreshing on ItemContainerChanged. primeItemCaches fills
    // them for a plugin that starts up already logged in.
    private Item[] equippedItems = new Item[0];
    private Item[] inventoryItems = new Item[0];

    // What the held items satisfy, refreshed with the caches above so a menu
    // build only reads booleans and a small set.
    private boolean hasAxe;
    private boolean hasPickaxe;
    private boolean hasLantern;
    private final Set<String> carriedFishingOptions = new HashSet<>();

    // Settings read on every menu build, cached on startUp and ConfigChanged.
    private boolean requireAxe;
    private boolean requirePickaxe;
    private boolean requireFishing;
    private boolean harpoonBareHanded;

    // Counts last logged, so the per-frame menu pass logs only on change.
    private int lastRemoved;
    private int lastDemoted;

    // Last raw Chop/Cut target, the override set it was checked against and
    // the verdict, so a hovered tree is classified once rather than per frame.
    private String lastAxeTarget;
    private Set<String> lastAxeOverrides;
    private boolean lastAxeVerdict;

    // Fishing option to the tools that satisfy it, per the wiki's equipment table. Built once:
    // onPostMenuSort runs on every menu rebuild, so rebuilding the map and its eight sets there
    // would allocate on the same path the item caches above avoid.
    private static final Map<String, Set<Integer>> FISHING_TOOLS = buildFishingTools();

    private static Map<String, Set<Integer>> buildFishingTools()
    {
        Map<String, Set<Integer>> map = new HashMap<>();
        map.put("Small Net", Set.of(ItemID.NET));
        map.put("Big Net", Set.of(ItemID.BIG_NET));
        map.put("Bait", Set.of(ItemID.FISHING_ROD, ItemID.OILY_FISHING_ROD,
            ItemID.FISHINGROD_PEARL, ItemID.FISHINGROD_PEARL_OILY));
        map.put("Lure", Set.of(ItemID.FLY_FISHING_ROD, ItemID.FISHINGROD_PEARL_FLY));
        map.put("Rod", Set.of(ItemID.BRUT_FISHING_ROD, ItemID.FISHINGROD_PEARL_BRUT));
        map.put("Fish", Set.of(ItemID.TBWT_KARAMBWAN_VESSEL, ItemID.TBWT_KARAMBWAN_VESSEL_LOADED_WITH_KARAMBWANJI));
        map.put("Cage", Set.of(ItemID.LOBSTER_POT));
        map.put("Harpoon", HARPOON_IDS);
        return Map.copyOf(map);
    }

    @Override
    public void startUp()
    {
        loadConfig();
        eventBus.register(this);
        if (log.isDebugEnabled())
        {
            log.debug("[tool] [config] axe={} pickaxe={} fishing={} bareHandedHarpoon={}",
                requireAxe, requirePickaxe, requireFishing, harpoonBareHanded);
        }
        // Nothing fires ItemContainerChanged until the next inventory or gear
        // change, so starting up already logged in would leave both caches
        // empty and the guard would act as though you carry nothing.
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                primeItemCaches();
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            loadConfig();
        }
    }

    private void loadConfig()
    {
        requireAxe = config.requireAxeToChop();
        requirePickaxe = config.requirePickaxeToMine();
        requireFishing = config.requireFishingTool();
        harpoonBareHanded = config.harpoonBareHanded();
    }

    // Containers are null until the player exists, hence the guards.
    private void primeItemCaches()
    {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn != null)
        {
            equippedItems = worn.getItems();
        }

        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null)
        {
            inventoryItems = inventory.getItems();
        }
        refreshCarried();
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        equippedItems = new Item[0];
        inventoryItems = new Item[0];
        refreshCarried();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer container = event.getItemContainer();
        if (event.getContainerId() == InventoryID.WORN)
        {
            equippedItems = container.getItems();
        }
        else if (event.getContainerId() == InventoryID.INV)
        {
            inventoryItems = container.getItems();
        }
        else
        {
            return;
        }
        refreshCarried();
    }

    private void refreshCarried()
    {
        boolean wasAxe = hasAxe;
        boolean wasPickaxe = hasPickaxe;
        hasAxe = carriesAny(AXE_IDS);
        hasPickaxe = carriesAny(PICKAXE_IDS);
        hasLantern = carriesAny(LANTERN_IDS);
        if (wasAxe != hasAxe || wasPickaxe != hasPickaxe)
        {
            log.debug("[tool] carried tools changed: axe={} pickaxe={}", hasAxe, hasPickaxe);
        }
        carriedFishingOptions.clear();
        for (Map.Entry<String, Set<Integer>> tools : FISHING_TOOLS.entrySet())
        {
            if (carriesAny(tools.getValue()))
            {
                carriedFishingOptions.add(tools.getKey());
            }
        }
    }

    // priority -1 so the harpoon reorder runs after RuneLite's own menu sorting
    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort postMenuSort)
    {
        boolean checkAxe = !hasAxe && requireAxe;
        boolean checkPickaxe = !hasPickaxe && requirePickaxe;
        boolean checkFishing = requireFishing;
        if (!checkAxe && !checkPickaxe && !checkFishing)
        {
            return;
        }

        if (client.isMenuOpen())
        {
            return;
        }

        Menu root = client.getMenu();
        MenuEntry[] entries = root.getMenuEntries();

        boolean bareHanded = checkFishing && harpoonBareHanded;
        List<MenuEntry> harpoonToDemote = null;
        // Surviving entries, allocated on the first removal so a menu with
        // nothing to strip allocates nothing.
        MenuEntry[] kept = null;
        int removed = 0;

        for (int i = 0; i < entries.length; i++)
        {
            MenuEntry entry = entries[i];
            if (removesEntry(entry, entries, checkAxe, checkPickaxe, checkFishing, bareHanded))
            {
                if (kept == null)
                {
                    kept = new MenuEntry[entries.length];
                    System.arraycopy(entries, 0, kept, 0, i);
                }
                removed++;
                continue;
            }
            if (kept != null)
            {
                kept[i - removed] = entry;
            }
            if (bareHanded && HARPOON_OPTION.equals(entry.getOption()) && demotesHarpoon(entry, entries))
            {
                if (harpoonToDemote == null)
                {
                    harpoonToDemote = new ArrayList<>();
                }
                harpoonToDemote.add(entry);
            }
        }

        MenuEntry[] result = kept != null ? Arrays.copyOf(kept, entries.length - removed) : entries;
        if (removed != lastRemoved)
        {
            lastRemoved = removed;
            if (removed > 0 && log.isDebugEnabled())
            {
                log.debug("[tool] removed {} menu entries (axe={} pickaxe={} fishing={})",
                    removed, checkAxe, checkPickaxe, checkFishing);
            }
        }

        int demotedCount = 0;
        if (harpoonToDemote != null)
        {
            MenuEntry[] demoted = demoteHarpoonEntries(result, harpoonToDemote);
            if (demoted != null)
            {
                result = demoted;
                demotedCount = harpoonToDemote.size();
            }
        }
        if (demotedCount != lastDemoted)
        {
            lastDemoted = demotedCount;
            if (demotedCount > 0)
            {
                log.debug("[tool] demoted {} harpoon entries below usable fishing option", demotedCount);
            }
        }

        if (result != entries)
        {
            root.setMenuEntries(result);
        }
    }

    private boolean removesEntry(MenuEntry entry, MenuEntry[] entries,
        boolean checkAxe, boolean checkPickaxe, boolean checkFishing, boolean bareHanded)
    {
        MenuAction type = entry.getType();

        if (type == MenuAction.GAME_OBJECT_FIRST_OPTION)
        {
            String option = entry.getOption();
            if (checkAxe)
            {
                if (option.startsWith("Chop") && isAxeTarget(entry.getTarget(), CHOP_OVERRIDES))
                {
                    return true;
                }
                if (option.startsWith("Cut") && isAxeTarget(entry.getTarget(), CUT_OVERRIDES))
                {
                    return true;
                }
            }
            return checkPickaxe && "Mine".equals(option);
        }

        if (checkFishing && NPC_OPTION_TYPES.contains(type))
        {
            String option = entry.getOption();
            if (!FISHING_TOOLS.containsKey(option) || carriedFishingOptions.contains(option))
            {
                return false;
            }
            return !(bareHanded && HARPOON_OPTION.equals(option) && keepsBareHandedHarpoon(entry, entries));
        }
        return false;
    }

    // A kept bare-handed Harpoon entry is sunk when another option on the same spot has its tool
    // carried.
    private boolean demotesHarpoon(MenuEntry entry, MenuEntry[] entries)
    {
        return NPC_OPTION_TYPES.contains(entry.getType())
            && !carriedFishingOptions.contains(HARPOON_OPTION)
            && hasUsableFishingSibling(entry, entries);
    }

    // Whether a sibling is another fishing option on the same spot. Entries with no NPC have no
    // siblings, so they fall through to the lantern rule.
    private static boolean isFishingSibling(MenuEntry sibling, MenuEntry entry, NPC npc)
    {
        if (sibling == entry || !NPC_OPTION_TYPES.contains(sibling.getType())
            || !FISHING_TOOLS.containsKey(sibling.getOption()))
        {
            return false;
        }
        NPC other = sibling.getNpc();
        return other != null && other.getIndex() == npc.getIndex();
    }

    // Whether a Harpoon entry survives with no harpoon carried. Tempoross needs none; a Big Net
    // sibling marks a shark spot and anything else a tuna/swordfish one; a spot with no fishing
    // sibling at all is a squid spot (or something unknown), which keeps the entry on a lit lantern
    // or on shark levels, so an unrecognised spot can never be wrongly stripped.
    private boolean keepsBareHandedHarpoon(MenuEntry entry, MenuEntry[] entries)
    {
        NPC npc = entry.getNpc();
        if (npc != null && TEMPOROSS_SPOT_IDS.contains(npc.getId()))
        {
            return true;
        }

        boolean sharkTier = false;
        boolean anySibling = false;
        if (npc != null)
        {
            for (MenuEntry sibling : entries)
            {
                if (!isFishingSibling(sibling, entry, npc))
                {
                    continue;
                }
                anySibling = true;
                if (BIG_NET_OPTION.equals(sibling.getOption()))
                {
                    sharkTier = true;
                }
            }
        }

        if (!anySibling)
        {
            // Squid spots, which need a real harpoon, plus any harpoon-only
            // spot not known here. The lantern covers squid; the shark
            // levels keep this fail-open in case a harpoon-only shark spot
            // exists or gets added, since every known one pairs with Big Net.
            return hasLantern || meetsBareHandLevels(true);
        }

        return meetsBareHandLevels(sharkTier);
    }

    private boolean meetsBareHandLevels(boolean sharkTier)
    {
        int fishing = sharkTier ? BAREHAND_SHARK_FISHING_LEVEL : BAREHAND_FISHING_LEVEL;
        int strength = sharkTier ? BAREHAND_SHARK_STRENGTH_LEVEL : BAREHAND_STRENGTH_LEVEL;
        return client.getRealSkillLevel(Skill.FISHING) >= fishing
            && client.getRealSkillLevel(Skill.STRENGTH) >= strength;
    }

    // Another option on the same spot whose tool is actually carried.
    private boolean hasUsableFishingSibling(MenuEntry entry, MenuEntry[] entries)
    {
        NPC npc = entry.getNpc();
        if (npc == null)
        {
            return false;
        }
        for (MenuEntry sibling : entries)
        {
            if (isFishingSibling(sibling, entry, npc) && carriedFishingOptions.contains(sibling.getOption()))
            {
                return true;
            }
        }
        return false;
    }

    // Sinks a kept Harpoon entry to just above "Walk here" so a usable option on the same spot
    // keeps the left-click. Menu entries run bottom-to-top, so inserting one past the WALK index
    // lands above it (InstanceExitGuardFeature inserts AT that index instead, to land below it).
    // Works on the array left after the removals, so the rebuild can't resurrect them. Returns the
    // reordered array, or null when nothing moves.
    private MenuEntry[] demoteHarpoonEntries(MenuEntry[] entries, List<MenuEntry> harpoonEntries)
    {
        // walkIndex counts only the entries that stay, as the insert point.
        int demoteCount = 0;
        int restCount = 0;
        int walkIndex = -1;
        for (MenuEntry entry : entries)
        {
            if (containsIdentity(harpoonEntries, entry))
            {
                demoteCount++;
                continue;
            }
            if (walkIndex < 0 && entry.getType() == MenuAction.WALK)
            {
                walkIndex = restCount;
            }
            restCount++;
        }

        if (demoteCount == 0)
        {
            return null;
        }

        if (walkIndex < 0)
        {
            // No anchor to place it against; leave the priority alone rather
            // than guess. Worst case it isn't demoted, never that it's hidden.
            return null;
        }

        // The rest keep their order; the demoted entries go right after Walk.
        MenuEntry[] reordered = new MenuEntry[entries.length];
        int restSlot = 0;
        int demoteSlot = walkIndex + 1;
        for (MenuEntry entry : entries)
        {
            if (containsIdentity(harpoonEntries, entry))
            {
                reordered[demoteSlot++] = entry;
            }
            else
            {
                reordered[restSlot <= walkIndex ? restSlot : restSlot + demoteCount] = entry;
                restSlot++;
            }
        }
        return reordered;
    }

    // Reference equality: MenuEntry doesn't promise a useful equals().
    private boolean containsIdentity(List<MenuEntry> entries, MenuEntry target)
    {
        for (MenuEntry entry : entries)
        {
            if (entry == target)
            {
                return true;
            }
        }
        return false;
    }

    private boolean carriesAny(Set<Integer> itemIds)
    {
        return holdsAny(equippedItems, itemIds) || holdsAny(inventoryItems, itemIds);
    }

    private static boolean holdsAny(Item[] items, Set<Integer> itemIds)
    {
        for (Item item : items)
        {
            if (itemIds.contains(item.getId()))
            {
                return true;
            }
        }
        return false;
    }

    // Trees of any capitalisation, plus the tree-like objects the sets name. Takes the raw menu
    // target.
    private boolean isAxeTarget(String rawTarget, Set<String> overrides)
    {
        if (rawTarget.equals(lastAxeTarget) && overrides == lastAxeOverrides)
        {
            return lastAxeVerdict;
        }
        String target = Text.removeTags(rawTarget);
        lastAxeVerdict = target.toLowerCase(Locale.ROOT).contains("tree") || overrides.contains(target);
        lastAxeTarget = rawTarget;
        lastAxeOverrides = overrides;
        return lastAxeVerdict;
    }
}
