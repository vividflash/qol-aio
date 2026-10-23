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

import com.google.inject.Provides;
import com.vividflash.qol.features.AlchProtectFeature;
import com.vividflash.qol.features.BirdhouseWithdrawLimitFeature;
import com.vividflash.qol.features.ChatSpamFilterFeature;
import com.vividflash.qol.features.ClueReminderFeature;
import com.vividflash.qol.features.Feature;
import com.vividflash.qol.features.GiantsFoundryTempFeature;
import com.vividflash.qol.features.InstanceExitGuardFeature;
import com.vividflash.qol.features.NeypotzliEssenceGuardFeature;
import com.vividflash.qol.features.NoEmptyPotionFeature;
import com.vividflash.qol.features.NoUseFarmProduceOnMiscFeature;
import com.vividflash.qol.features.NotedItemUseProtectionFeature;
import com.vividflash.qol.features.PickpocketGuardFeature;
import com.vividflash.qol.features.PortTaskAttackFilterFeature;
import com.vividflash.qol.features.PotionRedrinkGuardFeature;
import com.vividflash.qol.features.QuestIngredientProtectionFeature;
import com.vividflash.qol.features.RepotFlashFeature;
import com.vividflash.qol.features.RequireToolFeature;
import com.vividflash.qol.features.SoundEffectMutesFeature;
import com.vividflash.qol.features.SpellIngredientGuardFeature;
import com.vividflash.qol.features.TakeBeforeAttackFeature;
import com.vividflash.qol.features.UseOnPlayerGuardFeature;
import com.vividflash.qol.features.WithdrawLimitFeature;
import com.vividflash.qol.features.WorldhopImplingFeature;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
    name = "VividFlash QoL AIO",
    description = "QoL and misclick guards in one plugin",
    tags = {"qol", "misclick", "quality of life", "guard", "bank"}
)
public class QolPlugin extends Plugin
{
    @Inject
    private UseOnPlayerGuardFeature useOnPlayerGuardFeature;

    @Inject
    private NotedItemUseProtectionFeature notedItemUseProtectionFeature;

    @Inject
    private SpellIngredientGuardFeature spellIngredientGuardFeature;

    @Inject
    private WithdrawLimitFeature withdrawLimitFeature;

    @Inject
    private NeypotzliEssenceGuardFeature neypotzliEssenceGuardFeature;

    @Inject
    private BirdhouseWithdrawLimitFeature birdhouseWithdrawLimitFeature;

    @Inject
    private NoEmptyPotionFeature noEmptyPotionFeature;

    @Inject
    private QuestIngredientProtectionFeature questIngredientProtectionFeature;

    @Inject
    private ChatSpamFilterFeature chatSpamFilterFeature;

    @Inject
    private RequireToolFeature requireToolFeature;

    @Inject
    private PickpocketGuardFeature pickpocketGuardFeature;

    @Inject
    private InstanceExitGuardFeature instanceExitGuardFeature;

    @Inject
    private AlchProtectFeature alchProtectFeature;

    @Inject
    private SoundEffectMutesFeature soundEffectMutesFeature;

    @Inject
    private NoUseFarmProduceOnMiscFeature noUseFarmProduceOnMiscFeature;

    @Inject
    private GiantsFoundryTempFeature giantsFoundryTempFeature;

    @Inject
    private TakeBeforeAttackFeature takeBeforeAttackFeature;

    @Inject
    private PotionRedrinkGuardFeature potionRedrinkGuardFeature;

    @Inject
    private RepotFlashFeature repotFlashFeature;

    @Inject
    private ClueReminderFeature clueReminderFeature;

    @Inject
    private WorldhopImplingFeature worldhopImplingFeature;

    @Inject
    private PortTaskAttackFilterFeature portTaskAttackFilterFeature;

    private final List<Feature> features = new ArrayList<>();

    @Override
    protected void startUp()
    {
        features.add(useOnPlayerGuardFeature);
        features.add(notedItemUseProtectionFeature);
        features.add(spellIngredientGuardFeature);
        features.add(withdrawLimitFeature);
        features.add(neypotzliEssenceGuardFeature);
        features.add(birdhouseWithdrawLimitFeature);
        features.add(noEmptyPotionFeature);
        features.add(questIngredientProtectionFeature);
        features.add(chatSpamFilterFeature);
        features.add(requireToolFeature);
        features.add(pickpocketGuardFeature);
        features.add(instanceExitGuardFeature);
        features.add(alchProtectFeature);
        features.add(soundEffectMutesFeature);
        features.add(noUseFarmProduceOnMiscFeature);
        features.add(giantsFoundryTempFeature);
        features.add(takeBeforeAttackFeature);
        features.add(potionRedrinkGuardFeature);
        features.add(repotFlashFeature);
        features.add(clueReminderFeature);
        features.add(worldhopImplingFeature);
        features.add(portTaskAttackFilterFeature);
        features.forEach(Feature::startUp);
    }

    @Override
    protected void shutDown()
    {
        features.forEach(Feature::shutDown);
        features.clear();
    }

    @Provides
    QolConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(QolConfig.class);
    }
}
