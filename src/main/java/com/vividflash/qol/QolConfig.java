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
package com.vividflash.qol;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(QolConfig.GROUP)
public interface QolConfig extends Config
{
    // Config group name, also used by the features that reload on a change.
    String GROUP = "personalqol";

    @ConfigSection(
        name = "Use",
        description = "",
        position = 30,
        closedByDefault = true
    )
    String useSection = "useSection";

    @ConfigSection(
        name = "Withdraw Limits",
        description = "",
        position = 40,
        closedByDefault = true
    )
    String withdrawSection = "withdrawSection";

    @ConfigSection(
        name = "WL: Superglass Make",
        description = "",
        position = 50,
        closedByDefault = true
    )
    String withdrawSuperglassSection = "withdrawSuperglassSection";

    @ConfigSection(
        name = "WL: Fossil Island",
        description = "",
        position = 60,
        closedByDefault = true
    )
    String withdrawFossilIslandSection = "withdrawFossilIslandSection";

    @ConfigSection(
        name = "WL: Farming",
        description = "",
        position = 70,
        closedByDefault = true
    )
    String withdrawFarmingSection = "withdrawFarmingSection";

    @ConfigSection(
        name = "Block spells",
        description = "",
        position = 80,
        closedByDefault = true
    )
    String blockSpellsSection = "blockSpellsSection";

    @ConfigSection(
        name = "Chat filtering",
        description = "",
        position = 90,
        closedByDefault = true
    )
    String chatFilterSection = "chatFilterSection";

    @ConfigSection(
        name = "Require tool",
        description = "",
        position = 100,
        closedByDefault = true
    )
    String requireToolSection = "requireToolSection";

    @ConfigSection(
        name = "Potions",
        description = "",
        position = 110,
        closedByDefault = true
    )
    String potionsSection = "potionsSection";

    @ConfigSection(
        name = "Safety Guards",
        description = "",
        position = 120,
        closedByDefault = true
    )
    String safetySection = "safetySection";

    @ConfigSection(
        name = "Instance exit guard",
        description = "",
        position = 130,
        closedByDefault = true
    )
    String deprioLeaveSection = "deprioLeaveSection";

    @ConfigSection(
        name = "Hide & Mute",
        description = "",
        position = 140,
        closedByDefault = true
    )
    String hideMuteSection = "hideMuteSection";

    @ConfigSection(
        name = "Menu Swaps",
        description = "",
        position = 150,
        closedByDefault = true
    )
    String menuSwapsSection = "menuSwapsSection";

    @ConfigSection(
        name = "Reminders",
        description = "",
        position = 160,
        closedByDefault = true
    )
    String remindersSection = "remindersSection";

    @ConfigSection(
        name = "Sailing",
        description = "",
        position = 170,
        closedByDefault = true
    )
    String sailingSection = "sailingSection";

    @ConfigSection(
        name = "Repot Flash",
        description = "Flashes the inventory when a potion effect is about to run out.",
        position = 180,
        closedByDefault = true
    )
    String repotFlashSection = "repotFlashSection";

    @ConfigItem(
        keyName = "showBlockMessages",
        name = "Show block messages",
        description = "",
        position = 10
    )
    default boolean showBlockMessages()
    {
        return true;
    }

    @ConfigItem(
        keyName = "requireAxeToChop",
        name = "Require axe to chop",
        description = "",
        section = requireToolSection,
        position = 10
    )
    default boolean requireAxeToChop()
    {
        return true;
    }

    @ConfigItem(
        keyName = "requirePickaxeToMine",
        name = "Require pickaxe to mine",
        description = "",
        section = requireToolSection,
        position = 20
    )
    default boolean requirePickaxeToMine()
    {
        return true;
    }

    @ConfigItem(
        keyName = "requireFishingTool",
        name = "Require fishing tool",
        description = "",
        section = requireToolSection,
        position = 30
    )
    default boolean requireFishingTool()
    {
        return true;
    }

    @ConfigItem(
        keyName = "harpoonBareHanded",
        name = "Allow bare-handed harpooning",
        description = "Keeps Harpoon on tuna/swordfish (55 Fishing, 35 Strength) and shark (96/76) spots once you have the levels. If another tool you carry works on the spot, Harpoon moves just above Walk here.",
        section = requireToolSection,
        position = 40
    )
    default boolean harpoonBareHanded()
    {
        return true;
    }

    @ConfigItem(
        keyName = "chatSpamFilterEnabled",
        name = "Spam filter",
        description = "",
        section = chatFilterSection,
        position = 10
    )
    default boolean chatSpamFilterEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "chatSpamFilterPhrases",
        name = "Blocked phrases",
        description = "Comma-separated, case-insensitive. A message containing any of them is blocked.",
        section = chatFilterSection,
        position = 20
    )
    default String chatSpamFilterPhrases()
    {
        return "enough inventory space, spellbook reordering";
    }

    @ConfigItem(
        keyName = "noEmptyPotion",
        name = "No Empty Potion",
        description = "",
        section = potionsSection,
        position = 10
    )
    default boolean noEmptyPotion()
    {
        return true;
    }

    @ConfigItem(
        keyName = "noMisclickRepot",
        name = "Potion re-drink guard",
        description = "Removes Drink while that skill's boost is still active.",
        section = potionsSection,
        position = 20
    )
    default boolean noMisclickRepot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "noMisclickRepotThreshold",
        name = "Repot boost margin",
        description = "Blocks while the boosted level is at least this many levels above base.",
        section = potionsSection,
        position = 30
    )
    @Range(min = 1, max = 20)
    default int noMisclickRepotThreshold()
    {
        return 2;
    }

    @ConfigItem(
        keyName = "questFoodGuard",
        name = "Quest Food Item Guard",
        description = "Removes Eat/Drink on quest items.",
        position = 20
    )
    default boolean questFoodGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "useItemNoUsePlayer",
        name = "Use on player guard",
        description = "Removes Use on players. Spell casts are left alone.",
        section = useSection,
        position = 10
    )
    default boolean useItemNoUsePlayer()
    {
        return true;
    }

    @ConfigItem(
        keyName = "noUsePlayerWhitelist",
        name = "Whitelist",
        description = "Items still usable on players, e.g. for Heroes' Quest or Barbarian Assault.",
        section = useSection,
        position = 20
    )
    default String noUsePlayerWhitelist()
    {
        return "Phoenix crossbow, Miscellaneous key, Candlestick, Half certificate, Broken shield, Weapon store key, "
            + "Yellow egg, Poisoned egg, Spiked/pois. egg, Omega egg, "
            + "Healing vial(1), Healing vial(2), Healing vial(3), Healing vial(4), Scroll, "
            + "Neutralising potion, Old school bond";
    }

    @ConfigItem(
        keyName = "easyUnnote",
        name = "Noted item Use protection",
        description = "Noted item selected: keeps Use only on bank-related NPCs and objects, and removes Examine entries.",
        section = useSection,
        position = 30
    )
    default boolean easyUnnote()
    {
        return true;
    }

    @ConfigItem(
        keyName = "easyUnnoteEnableGeBooths",
        name = "Noted item Use protection: GE booths",
        description = "",
        section = useSection,
        position = 40
    )
    default boolean easyUnnoteEnableGeBooths()
    {
        return false;
    }

    @ConfigItem(
        keyName = "easyUnnoteEnableTables",
        name = "Noted item Use protection: tables",
        description = "",
        section = useSection,
        position = 50
    )
    default boolean easyUnnoteEnableTables()
    {
        return false;
    }

    @ConfigItem(
        keyName = "easyUnnoteCustomList",
        name = "Noted item Use protection: custom list",
        description = "Comma or newline-separated NPC and object names.",
        section = useSection,
        position = 60
    )
    default String easyUnnoteCustomList()
    {
        return "";
    }

    @ConfigItem(
        keyName = "noUsePlantsOnStone",
        name = "No use farm produce on misc",
        description = "Removes Use for the listed produce on farming patches and stone, and for saplings on the listed scenery.",
        section = useSection,
        position = 70
    )
    default boolean noUsePlantsOnStone()
    {
        return true;
    }

    @ConfigItem(
        keyName = "noUsePlantsOnStoneList",
        name = "Farm produce list",
        description = "Comma-separated item names, * wildcards.",
        section = useSection,
        position = 80
    )
    default String noUsePlantsOnStoneList()
    {
        return "Potato*, Onion*, Cabbage*, Tomato*, Sweetcorn*, Strawberr*, Watermelon*, Snape grass*, "
            + "Guam*, Marrentill*, Tarromin*, Harralander*, Ranarr*, Toadflax*, Irit*, Avantoe*, Kwuarm*, "
            + "Snapdragon*, Cadantine*, Lantadyme*, Dwarf weed*, Torstol*, Grape*, Jute*, Apple*, Banana*, "
            + "Orange*, Curry*, Pineapple*, Papaya*, Coconut*, Whiteberr*, Poison ivy*, Cactus*, Grimy*";
    }

    @ConfigItem(
        keyName = "noUseSaplingsOnSceneryList",
        name = "Sapling-blocked scenery",
        description = "Comma-separated object names, * wildcards. The default is the Daisies beside Lletya's fruit tree patch.",
        section = useSection,
        position = 90
    )
    default String noUseSaplingsOnSceneryList()
    {
        return "Daisies";
    }

    @ConfigItem(
        keyName = "withdrawLimitEnabled",
        name = "Generic items",
        description = "",
        section = withdrawSection,
        position = 10
    )
    default boolean withdrawLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "withdrawLimits",
        name = "Items list",
        description = "'Item name:max count' pairs, e.g. 'Pot:13'.",
        section = withdrawSection,
        position = 20
    )
    default String withdrawLimits()
    {
        return "";
    }

    @ConfigItem(
        keyName = "preventBadSuperglassMake",
        name = "Superglass Make guard",
        description = "Caps sand and seaweed withdrawals and blocks the cast unless the amounts are exact.",
        section = withdrawSuperglassSection,
        position = 10
    )
    default boolean preventBadSuperglassMake()
    {
        return true;
    }

    @ConfigItem(
        keyName = "superglassMakeSandAmount",
        name = "Sand amount",
        description = "",
        section = withdrawSuperglassSection,
        position = 20
    )
    @Range(min = 1, max = 28)
    default int superglassMakeSandAmount()
    {
        return 18;
    }

    @ConfigItem(
        keyName = "superglassMakeSeaweedAmount",
        name = "Seaweed amount",
        description = "",
        section = withdrawSuperglassSection,
        position = 30
    )
    @Range(min = 1, max = 28)
    default int superglassMakeSeaweedAmount()
    {
        return 3;
    }

    @ConfigItem(
        keyName = "birdhouseEquipmentLimitEnabled",
        name = "Equipment (max 1 each)",
        description = "Chisel, Hammer, Digsite pendant and Clockwork. A jeweller's chisel shares the chisel cap, an Imcando hammer the hammer cap.",
        section = withdrawFossilIslandSection,
        position = 10
    )
    default boolean birdhouseEquipmentLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsMax",
        name = "Logs max amount",
        description = "Shared by each log type below.",
        section = withdrawFossilIslandSection,
        position = 20
    )
    @Range(min = 1, max = 28)
    default int birdhouseLogsMax()
    {
        return 4;
    }

    @ConfigItem(
        keyName = "birdhouseLogsRegularEnabled",
        name = "Logs: Regular",
        description = "",
        section = withdrawFossilIslandSection,
        position = 30
    )
    default boolean birdhouseLogsRegularEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsOakEnabled",
        name = "Logs: Oak",
        description = "",
        section = withdrawFossilIslandSection,
        position = 40
    )
    default boolean birdhouseLogsOakEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsWillowEnabled",
        name = "Logs: Willow",
        description = "",
        section = withdrawFossilIslandSection,
        position = 50
    )
    default boolean birdhouseLogsWillowEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsTeakEnabled",
        name = "Logs: Teak",
        description = "",
        section = withdrawFossilIslandSection,
        position = 60
    )
    default boolean birdhouseLogsTeakEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsMapleEnabled",
        name = "Logs: Maple",
        description = "",
        section = withdrawFossilIslandSection,
        position = 70
    )
    default boolean birdhouseLogsMapleEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsMahoganyEnabled",
        name = "Logs: Mahogany",
        description = "",
        section = withdrawFossilIslandSection,
        position = 80
    )
    default boolean birdhouseLogsMahoganyEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsYewEnabled",
        name = "Logs: Yew",
        description = "",
        section = withdrawFossilIslandSection,
        position = 90
    )
    default boolean birdhouseLogsYewEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsMagicEnabled",
        name = "Logs: Magic",
        description = "",
        section = withdrawFossilIslandSection,
        position = 100
    )
    default boolean birdhouseLogsMagicEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "birdhouseLogsRedwoodEnabled",
        name = "Logs: Redwood",
        description = "",
        section = withdrawFossilIslandSection,
        position = 110
    )
    default boolean birdhouseLogsRedwoodEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "volcanicAshWithdrawLimitEnabled",
        name = "Volcanic ash",
        description = "",
        section = withdrawFarmingSection,
        position = 10
    )
    default boolean volcanicAshWithdrawLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "volcanicAshWithdrawLimitAmount",
        name = "Volcanic ash amount",
        description = "Total in inventory.",
        section = withdrawFarmingSection,
        position = 20
    )
    @Range(min = 1, max = 10000)
    default int volcanicAshWithdrawLimitAmount()
    {
        return 50;
    }

    @ConfigItem(
        keyName = "spadeWithdrawLimitEnabled",
        name = "Spade (max 1)",
        description = "",
        section = withdrawFarmingSection,
        position = 30
    )
    default boolean spadeWithdrawLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "coralElkhornWithdrawLimitEnabled",
        name = "Elkhorn coral",
        description = "",
        section = withdrawFarmingSection,
        position = 40
    )
    default boolean coralElkhornWithdrawLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "coralPillarWithdrawLimitEnabled",
        name = "Pillar coral",
        description = "",
        section = withdrawFarmingSection,
        position = 50
    )
    default boolean coralPillarWithdrawLimitEnabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "coralWithdrawLimitAmount",
        name = "Coral amount",
        description = "Per coral type, in inventory.",
        section = withdrawFarmingSection,
        position = 60
    )
    @Range(min = 1, max = 28)
    default int coralWithdrawLimitAmount()
    {
        return 10;
    }

    @ConfigItem(
        keyName = "preventBadSinisterOffering",
        name = "Sinister Offering guard",
        description = "Does not apply at the Fountain of Rune.",
        section = blockSpellsSection,
        position = 10
    )
    default boolean preventBadSinisterOffering()
    {
        return true;
    }

    @ConfigItem(
        keyName = "sinisterOfferingMinBones",
        name = "Min bones",
        description = "",
        section = blockSpellsSection,
        position = 20
    )
    @Range(min = 1, max = 28)
    default int sinisterOfferingMinBones()
    {
        return 3;
    }

    @ConfigItem(
        keyName = "preventBadDemonicOffering",
        name = "Demonic Offering guard",
        description = "Does not apply at the Fountain of Rune.",
        section = blockSpellsSection,
        position = 30
    )
    default boolean preventBadDemonicOffering()
    {
        return true;
    }

    @ConfigItem(
        keyName = "demonicOfferingMinAshes",
        name = "Min ashes",
        description = "",
        section = blockSpellsSection,
        position = 40
    )
    @Range(min = 1, max = 28)
    default int demonicOfferingMinAshes()
    {
        return 3;
    }

    @ConfigItem(
        keyName = "preventEssenceLoss",
        name = "Neypotzli essence guard",
        description = "Blocks teleports and the dungeon exit while you carry sulphurous essence inside Neypotzli.",
        section = blockSpellsSection,
        position = 50
    )
    default boolean preventEssenceLoss()
    {
        return true;
    }

    @ConfigItem(
        keyName = "essenceLossThreshold",
        name = "Min essence to guard",
        description = "",
        section = blockSpellsSection,
        position = 60
    )
    @Range(min = 1, max = 28)
    default int essenceLossThreshold()
    {
        return 1;
    }

    @ConfigItem(
        keyName = "alchProtect",
        name = "Alch protection",
        description = "Hides protected items while alching, by spell or Explorer's ring.",
        section = blockSpellsSection,
        position = 70
    )
    default boolean alchProtect()
    {
        return true;
    }

    @ConfigItem(
        keyName = "alchProtectUntradeables",
        name = "Protect untradeables",
        description = "",
        section = blockSpellsSection,
        position = 80
    )
    default boolean alchProtectUntradeables()
    {
        return true;
    }

    @ConfigItem(
        keyName = "alchProtectCollectionLog",
        name = "Protect collection log items",
        description = "",
        section = blockSpellsSection,
        position = 90
    )
    default boolean alchProtectCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "alchProtectList",
        name = "Protected items",
        description = "Comma-separated item names.",
        section = blockSpellsSection,
        position = 100
    )
    default String alchProtectList()
    {
        return "";
    }

    @ConfigItem(
        keyName = "pickpocketGuard",
        name = "Pickpocket Guard",
        description = "Blocks Pickpocket while your HP is at or below the NPC's max stun damage plus the margin.",
        section = safetySection,
        position = 10
    )
    default boolean pickpocketGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "pickpocketDamageMargin",
        name = "Pickpocket damage margin",
        description = "E.g. margin 1 vs a Master Farmer (max 3) blocks at 4 HP or below. Unlisted NPCs count as 5.",
        section = safetySection,
        position = 20
    )
    @Range(min = 0, max = 20)
    default int pickpocketDamageMargin()
    {
        return 1;
    }

    @ConfigItem(
        keyName = "pickpocketSafePlunder",
        name = "Safe urns & speartraps",
        description = "Blocks Pyramid Plunder urns and speartraps at or below 4 HP.",
        section = safetySection,
        position = 30
    )
    default boolean pickpocketSafePlunder()
    {
        return true;
    }

    @ConfigItem(
        keyName = "pickpocketBlockSarcophagus",
        name = "Block sarcophagus",
        description = "Pyramid Plunder.",
        section = safetySection,
        position = 40
    )
    default boolean pickpocketBlockSarcophagus()
    {
        return false;
    }

    @ConfigItem(
        keyName = "foundryTempGuard",
        name = "Giants' Foundry temp guard",
        description = "Blocks Trip hammer, Grindstone and Polishing wheel while the temperature is outside the band for the current stage.",
        section = safetySection,
        position = 50
    )
    default boolean foundryTempGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "foundryOverworkGuard",
        name = "Giants' Foundry overwork guard",
        description = "Blocks re-clicking the current station after the bonus window has closed.",
        section = safetySection,
        position = 60
    )
    default boolean foundryOverworkGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "worldhopImplingGuard",
        name = "Block world-hop near impling",
        description = "Blocks the world switcher while a selected impling is in the loaded scene.",
        section = safetySection,
        position = 70
    )
    default boolean worldhopImplingGuard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "worldhopImplingTypes",
        name = "Impling types to guard",
        description = "Comma-separated names, * wildcards.",
        section = safetySection,
        position = 80
    )
    default String worldhopImplingTypes()
    {
        return "Dragon impling, Lucky impling, Crystal impling, Ninja impling, Magpie impling";
    }

    @ConfigItem(
        keyName = "deprioLeave",
        name = "Instance exit guard",
        description = "While valuable loot is on the ground in an instance, moves exit/leave options below Walk here.",
        section = deprioLeaveSection,
        position = 10
    )
    default boolean deprioLeave()
    {
        return true;
    }

    @ConfigItem(
        keyName = "deprioLeaveLootValue",
        name = "Loot value over",
        description = "Per ground item or stack.",
        section = deprioLeaveSection,
        position = 20
    )
    @Range(min = 1, max = Integer.MAX_VALUE)
    default int deprioLeaveLootValue()
    {
        return 10000000;
    }

    @ConfigItem(
        keyName = "deprioLeaveWhitelist",
        name = "Loot whitelist",
        description = "Comma-separated item names that always count, * wildcards.",
        section = deprioLeaveSection,
        position = 30
    )
    default String deprioLeaveWhitelist()
    {
        return "*(elite), *(hard), *(master), Twisted bow";
    }

    @ConfigItem(
        keyName = "deprioLeaveOptions",
        name = "Object options",
        description = "Comma-separated menu options, * wildcards.",
        section = deprioLeaveSection,
        position = 40
    )
    default String deprioLeaveOptions()
    {
        return "exit,leave,quick-exit,quick-leave,pass-through,travel,quick-travel";
    }

    @ConfigItem(
        keyName = "deprioLeaveItemOptions",
        name = "Item/spell options",
        description = "Comma-separated inventory, equipment and spell options, * wildcards.",
        section = deprioLeaveSection,
        position = 50
    )
    default String deprioLeaveItemOptions()
    {
        return "break,nardah";
    }

    @ConfigItem(
        keyName = "takeBeforeAttack",
        name = "Take before Attack",
        description = "When a menu holds Take and Attack on a monster listed below, moves Attack below Walk here so Take gets the left-click.",
        section = menuSwapsSection,
        position = 10
    )
    default boolean takeBeforeAttack()
    {
        return true;
    }

    @ConfigItem(
        keyName = "takeBeforeAttackMonsters",
        name = "Enable: monster list",
        description = "Comma or newline-separated NPC names, * wildcards.",
        section = menuSwapsSection,
        position = 20
    )
    default String takeBeforeAttackMonsters()
    {
        return "Brutal black dragon";
    }

    @ConfigItem(
        keyName = "takeBeforeAttackMonstersExclude",
        name = "Disable: monster exclusions",
        description = "Comma or newline-separated NPC names, * wildcards. Wins over the enable list.",
        section = menuSwapsSection,
        position = 30
    )
    default String takeBeforeAttackMonstersExclude()
    {
        return "";
    }

    @ConfigItem(
        keyName = "takeBeforeAttackItemExclude",
        name = "Ground-item exclusions",
        description = "Comma or newline-separated item names, * wildcards. 'Bones' matches plain Bones only.",
        section = menuSwapsSection,
        position = 40
    )
    default String takeBeforeAttackItemExclude()
    {
        return "Bones";
    }

    @ConfigItem(
        keyName = "clueReminder",
        name = "Clue held-count reminder",
        description = "One chat reminder per clue tier.",
        section = remindersSection,
        position = 10
    )
    default boolean clueReminder()
    {
        return true;
    }

    @ConfigItem(
        keyName = "clueReminderThreshold",
        name = "Reminder threshold",
        description = "Clue scrolls of a single tier.",
        section = remindersSection,
        position = 20
    )
    @Range(min = 2, max = 5)
    default int clueReminderThreshold()
    {
        return 2;
    }

    @ConfigItem(
        keyName = "muteSkilling",
        name = "Mute skilling sounds",
        description = "Chopping, mining, cooking, smithing, firemaking, plant picking, bone burying, GE plops, item pickup and drop.",
        section = hideMuteSection,
        position = 10
    )
    default boolean muteSkilling()
    {
        return false;
    }

    @ConfigItem(
        keyName = "mutePrayer",
        name = "Mute prayer sounds",
        description = "",
        section = hideMuteSection,
        position = 20
    )
    default boolean mutePrayer()
    {
        return false;
    }

    @ConfigItem(
        keyName = "muteTownCrier",
        name = "Mute town crier",
        description = "",
        section = hideMuteSection,
        position = 30
    )
    default boolean muteTownCrier()
    {
        return false;
    }

    @ConfigItem(
        keyName = "muteTeleport",
        name = "Mute teleport sounds",
        description = "",
        section = hideMuteSection,
        position = 40
    )
    default boolean muteTeleport()
    {
        return false;
    }

    @ConfigItem(
        keyName = "muteOthersOnly",
        name = "Mute other players only",
        description = "The mutes above then apply only to sounds from other players.",
        section = hideMuteSection,
        position = 50
    )
    default boolean muteOthersOnly()
    {
        return false;
    }

    @ConfigItem(
        keyName = "muteOthersWildernessExempt",
        name = "Never mute others in the Wilderness",
        description = "Also on PvP and Deadman worlds. Overrides the mutes above.",
        section = hideMuteSection,
        position = 60
    )
    default boolean muteOthersWildernessExempt()
    {
        return true;
    }

    @ConfigItem(
        keyName = "sailingAttackFilter",
        name = "Attack only port-task targets",
        description = "Removes Attack on sea bounty monsters that none of your port-task slots target.",
        section = sailingSection,
        position = 10
    )
    default boolean sailingAttackFilter()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashAntifire",
        name = "Antifire",
        description = "Includes extended. Last ~18 seconds.",
        section = repotFlashSection,
        position = 10
    )
    default boolean repotFlashAntifire()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashSuperAntifire",
        name = "Super antifire",
        description = "Includes extended. Last ~12 seconds.",
        section = repotFlashSection,
        position = 20
    )
    default boolean repotFlashSuperAntifire()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashPoisonMode",
        name = "Poison/venom",
        description = "Which immunity to watch. Last ~18 seconds. Venom immunity decays into poison immunity.",
        section = repotFlashSection,
        position = 30
    )
    default PoisonFlashMode repotFlashPoisonMode()
    {
        return PoisonFlashMode.POISON;
    }

    @ConfigItem(
        keyName = "repotFlashOverload",
        name = "Overload",
        description = "NMZ, Chambers of Xeric and DMM blighted. Last ~15 seconds.",
        section = repotFlashSection,
        position = 40
    )
    default boolean repotFlashOverload()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashAbsorption",
        name = "NMZ absorption",
        description = "At or below the threshold.",
        section = repotFlashSection,
        position = 50
    )
    default boolean repotFlashAbsorption()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashAbsorptionThreshold",
        name = "Absorption threshold (points)",
        description = "Each dose gives 50, cap 1000.",
        section = repotFlashSection,
        position = 60
    )
    @Range(min = 0, max = 1000)
    default int repotFlashAbsorptionThreshold()
    {
        return 100;
    }

    @ConfigItem(
        keyName = "repotFlashMenaphite",
        name = "Menaphite remedy",
        description = "Last ~15 seconds.",
        section = repotFlashSection,
        position = 70
    )
    default boolean repotFlashMenaphite()
    {
        return true;
    }

    @ConfigItem(
        keyName = "repotFlashStyle",
        name = "Flash style",
        description = "",
        section = repotFlashSection,
        position = 80
    )
    default RepotFlashStyle repotFlashStyle()
    {
        return RepotFlashStyle.POTION_ITEMS;
    }

    @Alpha
    @ConfigItem(
        keyName = "repotFlashColor1",
        name = "Flash colour 1",
        description = "",
        section = repotFlashSection,
        position = 90
    )
    default Color repotFlashColor1()
    {
        return new Color(255, 0, 0, 90);
    }

    @Alpha
    @ConfigItem(
        keyName = "repotFlashColor2",
        name = "Flash colour 2",
        description = "",
        section = repotFlashSection,
        position = 100
    )
    default Color repotFlashColor2()
    {
        return new Color(255, 255, 0, 90);
    }

    @ConfigItem(
        keyName = "repotFlashIntervalMs",
        name = "Flash interval (ms)",
        description = "Time per colour.",
        section = repotFlashSection,
        position = 110
    )
    @Range(min = 100, max = 1000)
    default int repotFlashIntervalMs()
    {
        return 500;
    }

    @ConfigItem(
        keyName = "repotFlashGraceSeconds",
        name = "Keep flashing after expiry (s)",
        description = "Flashing always stops when you repot.",
        section = repotFlashSection,
        position = 120
    )
    @Range(min = 0, max = 60)
    default int repotFlashGraceSeconds()
    {
        return 10;
    }
}
