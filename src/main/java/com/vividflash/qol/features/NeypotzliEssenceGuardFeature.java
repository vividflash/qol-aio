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
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Blocks teleporting/leaving Neypotzli while carrying sulphurous essence, since
 * it disintegrates on exit ("The resources from Neypotzli fizzle to dust...").
 *
 * Region id 5782, from the Neypotzli map point (1440, 9632).
 *
 * Coverage is deliberately wider than a plain "teleport" option match: the
 * essence is only lost on actually LEAVING Neypotzli (or on death); moving
 * between its internal rooms is safe, so this must never touch anything but
 * genuine exit/teleport actions, and every check runs only inside that region.
 * Escape routes covered:
 *  - "Pass-through": the dungeon exit itself.
 *  - Any option containing "teleport": scrolls (e.g. treasure-trail
 *    "X teleport" scrolls use a "Teleport" option) and worn items whose
 *    option says "Teleport".
 *  - Spellbook teleports: the option is "Cast" and the spell name in the
 *    target carries "teleport" (e.g. "Varrock Teleport").
 *  - Teleport tablets don't say "teleport" in the option, only in the item
 *    name (e.g. "Varrock teleport", "Teleport to house"). The option is
 *    always "Break", so it needs a name check.
 *  - Teleport jewellery (games necklace, ring of dueling, amulet of glory,
 *    skills necklace, ring of wealth, combat bracelet, xeric's talisman,
 *    burning amulet, etc.) is activated from the inventory with "Rub", not
 *    a "teleport"-named option, so it also needs a name check.
 */
@Slf4j
@Singleton
public class NeypotzliEssenceGuardFeature implements Feature
{
    private static final int NEYPOTZLI_REGION_ID = 5782;

    // Inventory activation option for teleport tablets/scrolls of this shape
    // (e.g. "Varrock teleport", "Teleport to house"), matched by name since
    // the menu option itself is just "Break".
    private static final String TABLET_BREAK_OPTION = "break";

    // Inventory activation option for teleport jewellery, matched by name
    // since the menu option itself is just "Rub".
    private static final String JEWELLERY_RUB_OPTION = "rub";

    // Spellbook option for every spell, teleports included; the spell name is
    // in the target.
    private static final String SPELL_CAST_OPTION = "cast";

    // Known teleport jewellery names, matched case-insensitively so
    // charged/uncharged and (t)/(e) variants all hit.
    private static final Set<String> TELEPORT_JEWELLERY_NAMES = Set.of(
        "amulet of glory",
        "amulet of eternal glory",
        "ring of dueling",
        "games necklace",
        "ring of wealth",
        "skills necklace",
        "combat bracelet",
        "burning amulet",
        "xeric's talisman",
        "digsite pendant",
        "necklace of passage",
        "ring of returning",
        "slayer ring"
    );

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        if (log.isDebugEnabled())
        {
            log.debug("[neypotzli] [config] enabled={} threshold={} blockMessages={}",
                config.preventEssenceLoss(), config.essenceLossThreshold(), config.showBlockMessages());
        }
        eventBus.register(this);
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!config.preventEssenceLoss())
        {
            return;
        }

        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return;
        }

        WorldPoint location = local.getWorldLocation();
        if (location == null || location.getRegionID() != NEYPOTZLI_REGION_ID)
        {
            return;
        }

        int essence = inventoryCount(ItemID.SULPHUROUS_ESSENCE);
        if (essence < config.essenceLossThreshold())
        {
            return;
        }

        String option = Text.removeTags(event.getMenuOption());
        boolean isTeleport = option.toLowerCase(Locale.ROOT).contains("teleport");
        boolean isPassThrough = "Pass-through".equalsIgnoreCase(option);
        // A use-on cast ("High Level Alchemy -> Varrock teleport") names the
        // spell before the arrow and the target item after it, so only a plain
        // cast counts as a spellbook teleport.
        boolean isTeleportSpell = SPELL_CAST_OPTION.equalsIgnoreCase(option)
            && !event.getMenuTarget().contains("->") && isTeleportTablet(event.getMenuTarget());
        boolean isTeleportItem = isTeleportSpell
            || (TABLET_BREAK_OPTION.equalsIgnoreCase(option) && isTeleportTablet(event.getMenuTarget()))
            || (JEWELLERY_RUB_OPTION.equalsIgnoreCase(option) && isTeleportJewellery(event.getMenuTarget()));
        if (isTeleport || isPassThrough || isTeleportItem)
        {
            event.consume();
            log.debug("[neypotzli] blocked exit option={} target={} essence={} teleport={} passThrough={} teleportItem={}",
                option, Text.removeTags(event.getMenuTarget()), essence, isTeleport, isPassThrough, isTeleportItem);
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Neypotzli] " + essence + " sulphurous essence would fizzle away if you left now.", null);
            }
        }
    }

    // Tablets are named after their destination (e.g. "Varrock teleport",
    // "Teleport to house") but are always activated with a plain "Break":
    // the option alone can't tell a teleport tablet apart from any other
    // breakable item, so the item name has to carry the signal instead.
    // Spellbook teleports are read the same way from the "Cast" target.
    private boolean isTeleportTablet(String menuTarget)
    {
        // Every teleport tablet/scroll name spells out "teleport" in full
        // (e.g. "Varrock teleport", "Teleport to house"); there's no
        // abbreviated "tele" naming to account for, so matching just the
        // full word avoids false positives on other "Break"-able items
        // (e.g. Expeditious bracelet, Amulet of chemistry).
        String name = Text.removeTags(menuTarget).toLowerCase(Locale.ROOT);
        return name.contains("teleport");
    }

    // Teleport jewellery is activated from the inventory with a plain "Rub",
    // so match against the known set of teleport-capable names instead.
    private boolean isTeleportJewellery(String menuTarget)
    {
        String name = Text.removeTags(menuTarget).toLowerCase(Locale.ROOT);
        for (String jewellery : TELEPORT_JEWELLERY_NAMES)
        {
            if (name.contains(jewellery))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Carried amount, read from the inventory container rather than the
     * inventory widget: the container holds real quantities and stays readable
     * while another interface is drawn over the inventory.
     */
    private int inventoryCount(int itemId)
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        return inventory == null ? 0 : inventory.count(itemId);
    }
}
