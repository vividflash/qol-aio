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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Blocks Pickpocket when current HP is at or below the clicked NPC's maximum
 * failed-pickpocket (stun) damage plus a configurable margin, and the Pyramid
 * Plunder urn/speartrap/sarcophagus interactions that could kill you at low HP.
 */
@Slf4j
@Singleton
public class PickpocketGuardFeature implements Feature
{
    private static final int SARCOPHAGUS_OBJECT_ID = ObjectID.NTK_SARCOPHAGUS_MULTI;
    private static final int MAX_DAMAGE_URN = 4;
    private static final int MAX_DAMAGE_SPEARTRAP = 4;

    /**
     * Maximum damage a failed pickpocket (stun) can deal, keyed by the NPC's
     * menu-target name. Values are the max of the range given in the OSRS wiki
     * pickpocketing tables (https://oldschool.runescape.wiki/w/Pickpocketing,
     * https://oldschool.runescape.wiki/w/Thieving). Names are the in-game menu
     * target names the guard matches against, lower-cased for case-insensitive
     * lookup. Where several NPCs share the same stun damage they are listed
     * separately so exact menu names all resolve.
     */
    private static final Map<String, Integer> STUN_DAMAGE = new HashMap<>();

    static
    {
        STUN_DAMAGE.put("man", 1);
        STUN_DAMAGE.put("woman", 1);
        STUN_DAMAGE.put("farmer", 1);
        STUN_DAMAGE.put("gnome", 1);
        STUN_DAMAGE.put("cave goblin", 1);
        STUN_DAMAGE.put("digsite workman", 1);
        STUN_DAMAGE.put("h.a.m. member", 3);
        STUN_DAMAGE.put("warrior", 2);
        STUN_DAMAGE.put("al-kharid warrior", 2);
        STUN_DAMAGE.put("rogue", 2);
        STUN_DAMAGE.put("guard", 2);
        STUN_DAMAGE.put("fremennik citizen", 2);
        STUN_DAMAGE.put("villager", 2);
        STUN_DAMAGE.put("master farmer", 3);
        STUN_DAMAGE.put("wealthy citizen", 3);
        STUN_DAMAGE.put("desert bandit", 3);
        STUN_DAMAGE.put("bandit", 3);
        STUN_DAMAGE.put("knight of ardougne", 3);
        STUN_DAMAGE.put("knight of varlamore", 3);
        STUN_DAMAGE.put("pirate", 3);
        STUN_DAMAGE.put("yanille watchman", 3);
        STUN_DAMAGE.put("watchman", 3);
        STUN_DAMAGE.put("paladin", 3);
        STUN_DAMAGE.put("hero", 3);
        STUN_DAMAGE.put("tzhaar-hur", 4);
        STUN_DAMAGE.put("bearded pollnivnian bandit", 5);
        STUN_DAMAGE.put("pollnivnian bandit", 5);
        STUN_DAMAGE.put("menaphite thug", 5);
        STUN_DAMAGE.put("vyre", 5);
        STUN_DAMAGE.put("elf", 5);
    }

    /**
     * Fallback stun damage for any pickpocketable NPC not in {@link #STUN_DAMAGE}
     * (e.g. a new NPC or an unmapped name variant): assume the worst case
     * in the table so a misclick can't kill you at low HP.
     */
    private static final int MAX_STUN_DAMAGE = Collections.max(STUN_DAMAGE.values());

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        log.debug("[config] pickpocket enabled={} margin={} safePlunder={} blockSarcophagus={}",
            config.pickpocketGuard(), config.pickpocketDamageMargin(), config.pickpocketSafePlunder(),
            config.pickpocketBlockSarcophagus());
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
        if (!config.pickpocketGuard())
        {
            return;
        }

        String option = event.getMenuEntry().getOption();
        boolean urnOption = "Search".equals(option) || "Check for Snakes".equals(option);
        boolean speartrapOption = "Pass".equals(option);
        boolean sarcophagusOption = "Open".equals(option) && event.getId() == SARCOPHAGUS_OBJECT_ID;
        if (!"Pickpocket".equals(option) && !urnOption && !speartrapOption && !sarcophagusOption)
        {
            return;
        }

        int currentHealth = client.getBoostedSkillLevel(Skill.HITPOINTS);
        String target = Text.removeTags(event.getMenuTarget());

        if ("Pickpocket".equals(option))
        {
            String npcName = MenuText.stripCombatLevel(target.toLowerCase());
            int npcDamage = stunDamageFor(npcName);
            int blockThreshold = npcDamage + config.pickpocketDamageMargin();
            if (currentHealth <= blockThreshold)
            {
                block(event, MenuText.stripCombatLevel(target) + " can hit " + npcDamage
                    + " on a failed pickpocket - eat first"
                    + " (guarding at " + blockThreshold + " HP).");
            }
            return;
        }

        boolean urnRisk = urnOption && "Urn".equals(target) && currentHealth <= MAX_DAMAGE_URN;
        boolean speartrapRisk = speartrapOption && "Speartrap".equals(target) && currentHealth <= MAX_DAMAGE_SPEARTRAP;
        if ((urnRisk || speartrapRisk) && config.pickpocketSafePlunder())
        {
            block(event, "The " + target.toLowerCase() + " could kill you - eat first.");
            return;
        }

        if (sarcophagusOption && target.contains("Sarcophagus") && config.pickpocketBlockSarcophagus())
        {
            block(event, "Sarcophagus opening blocked.");
        }
    }

    /**
     * Max failed-pickpocket damage for the clicked NPC, keyed by its lower-cased
     * name with the combat level already stripped. Falls back to the highest
     * damage in the table for unknown NPCs (fail-safe: over-block rather than
     * let a misclick kill the player at low HP).
     */
    private int stunDamageFor(String npcName)
    {
        return STUN_DAMAGE.getOrDefault(npcName, MAX_STUN_DAMAGE);
    }

    private void block(MenuOptionClicked event, String message)
    {
        event.consume();
        log.debug("[pickpocket] blocked click: {}", message);
        if (config.showBlockMessages())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Safety] " + message, null);
        }
    }
}
