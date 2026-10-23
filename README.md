# VividFlash QoL AIO

A single RuneLite plugin that bundles a set of small quality-of-life and
misclick-guard tweaks, so you can enable just the ones you want from one place
instead of running many tiny plugins. Features are grouped into config
sections, and each feature has its own toggle.

## Features

**General**

- **Show block messages**: a guard that blocks a click posts a chat message
  saying why. Guards that only remove or reorder menu entries are silent.
  Default on.
- **Quest Food Item Guard**: removes `Eat`/`Drink` on a fixed set of quest
  items that are easy to misclick-consume and lose (Tomato, Banana, Onion,
  Cabbage, Cheese, Chocolate bar, Karamja rum, Redberry pie, Dwellberries,
  Jangerberries, the Ghosts Ahoy nettle tea and a few others). Default on.

**Use**

- **Use on player guard**: removes `Use [item] -> Player` so you can't
  misclick items onto other players. Spell casts on players are left alone.
  Default on.
- **Whitelist**: items that keep `Use` on players, comma or newline separated.
  The default list covers Heroes' Quest and Barbarian Assault items, plus
  Scroll, Neutralising potion and Old school bond.
- **Noted item Use protection**: when a noted item (or a Looting bag note or
  Rune pouch note) is selected, removes `Use` on players, ground items and on
  every NPC and object that is not bank related, and removes the `Examine`
  entries of NPCs, objects and ground items. Kept: bankers, the note exchange
  NPCs (Phials, Aisles, Piles, Tiles, Biles, Isles, Banknote Exchange
  Merchant), the Giant Clam, house servants (Maid, Cook, Butler, Demon
  butler), Elder Chaos druid, Virilis, Mercenary, Wesley, Zahur, Friendly
  Forester, Rick, bank booths and chests, and any other NPC or object with a
  `Bank` option. Default on.
- **Noted item Use protection: GE booths**: also keeps `Use` on Grand Exchange
  booths. Default off.
- **Noted item Use protection: tables**: also keeps `Use` on objects whose name
  ends in `table`, and on `Counter`. Default off. With a toggle off, those
  objects stay blocked even though most of them have a `Bank` option.
- **Noted item Use protection: custom list**: extra NPC and object names that
  keep `Use`, comma or newline separated. Default empty.
- **No use farm produce on misc**: removes `Use [farm produce] -> object` when
  the object name contains `patch`, `stone`, `allotment`, `herb`, `flower`,
  `hops`, `bush`, `tree`, `fruit tree`, `vine` or `cactus`. Misclicking the
  stone the Ardougne tool leprechaun stands on (or the patch beside him) while
  noting produce just walks you over and fizzles; removing the entries stops
  that and cuts menu clutter. Saplings are exempt from this rule (planting is
  legit) and are instead blocked on the scenery in **Sapling-blocked
  scenery**. Default on.
- **Farm produce list**: comma-separated item names with `*` wildcards. The
  default covers crops, herbs, fruit and `Grimy*` herbs.
- **Sapling-blocked scenery**: comma-separated object names with `*`
  wildcards. Default `Daisies`, the flowers beside Lletya's fruit tree patch.

**Withdraw Limits**

- **Generic items**: caps how much of each item in **Items list** you can
  carry after a bank withdraw. Default on.
- **Items list**: `Item name:max` pairs, comma or newline separated (default
  empty, e.g. `Pot:13`). Entries without a number above 0 are ignored. On this
  tab and on every WL tab, a withdraw option that would take out more than is
  left under the cap is blocked, `Withdraw-All` included, and so is any
  withdraw once the cap is reached. `Withdraw-X` goes through while you are
  under the cap. Noted copies you carry count toward the cap.

**WL: Superglass Make**

- **Superglass Make guard**: caps sand and seaweed withdrawals and blocks the
  Superglass Make cast unless you carry exactly **Sand amount** sand and
  **Seaweed amount** seaweed. Sand is stricter than the other caps: only the
  `Withdraw-` option matching **Sand amount**, or `Withdraw-X`, goes through,
  and only while you carry no sand. Seaweed follows the standard cap rule.
  Default on.
- **Sand amount**: default `18` (range 1 to 28).
- **Seaweed amount**: default `3` (range 1 to 28).

**WL: Fossil Island**

- **Equipment (max 1 each)**: caps Chisel, Hammer, Digsite pendant and
  Clockwork at 1 each. A jeweller's chisel counts as a chisel, an Imcando
  hammer (offhand included) as a hammer, and any charge of the Digsite pendant
  as the pendant. Default on.
- **Logs max amount**: cap shared by each birdhouse log type, default `4`
  (range 1 to 28).
- **Logs: Regular**, **Logs: Oak**, **Logs: Willow**, **Logs: Teak**,
  **Logs: Maple**, **Logs: Mahogany**, **Logs: Yew**, **Logs: Magic**,
  **Logs: Redwood**: one toggle per log type, all on by default.

**WL: Farming**

- **Volcanic ash**: capped at **Volcanic ash amount** carried in total,
  withdrawable in any increments up to the cap. Default on.
- **Volcanic ash amount**: default `50` (range 1 to 10000).
- **Spade (max 1)**: caps Spade at 1. Default on.
- **Elkhorn coral** and **Pillar coral**: each capped at **Coral amount**.
  Default on.
- **Coral amount**: cap per coral type, default `10` (range 1 to 28).

**Block spells**

- **Sinister Offering guard**: blocks casting without at least **Min bones**
  bones in the inventory. Not applied while the Fountain of Rune effect is
  active. Default on.
- **Min bones**: default `3` (range 1 to 28).
- **Demonic Offering guard**: blocks casting without at least **Min ashes**
  ashes in the inventory. Not applied while the Fountain of Rune effect is
  active. Default on.
- **Min ashes**: default `3` (range 1 to 28).
- **Neypotzli essence guard**: inside Neypotzli, blocks the dungeon exit
  (`Pass-through`), any option containing `teleport`, `Cast` on spells whose
  name contains `teleport`, `Break` on teleport tablets and `Rub` on teleport
  jewellery while you carry at least **Min
  essence to guard** sulphurous essence. Default on.
- **Min essence to guard**: default `1` (range 1 to 28).
- **Alch protection**: hides protected items from the inventory while Low or
  High Level Alchemy is selected and while the Explorer's ring alchemy
  interface is open. Noted items are checked as their unnoted form. Default
  on.
- **Protect untradeables**: default on.
- **Protect collection log items**: default on.
- **Protected items**: comma-separated item names that are also protected.
  Default empty.

**Chat filtering**

- **Spam filter**: hides game messages (including examine text) containing any
  of the **Blocked phrases**. Player chat is never filtered. Default on.
- **Blocked phrases**: case-insensitive substrings, comma or newline
  separated, default `enough inventory space, spellbook reordering`.

**Require tool**

- **Require axe to chop**: removes `Chop` on trees and tree-like objects such
  as vines and roots, and `Cut` on Sulliuscep, when no axe is carried or
  equipped. Default on.
- **Require pickaxe to mine**: removes `Mine` on rocks when no pickaxe is
  carried or equipped. Default on.
- **Require fishing tool**: removes `Small Net`, `Big Net`, `Bait`, `Lure`,
  `Rod`, `Fish`, `Cage` and `Harpoon` on fishing spots when the matching tool
  (net, big net, fishing rod or oily fishing rod, fly fishing rod, barbarian
  rod, karambwan vessel, lobster pot, harpoon) is not carried or equipped.
  Default on.
- **Allow bare-handed harpooning**: with **Require fishing tool** on, once
  you're levelled for Barbarian Training, `Harpoon` survives without a harpoon
  (55 Fishing/35 Strength, or 96/76 at shark spots). If another tool you carry
  works on the same spot, `Harpoon` drops just above `Walk here` so it can't
  take the left-click. Tempoross is always exempt; squid spots keep `Harpoon`
  only with a lit lantern (or shark-tier levels). Default on.

**Potions**

- **No Empty Potion**: removes `Empty` on potions of any dose count (item
  names ending in `(1)` to `(9)`) so you can't waste doses. Default on.
- **Potion re-drink guard**: removes `Drink` on ranging, magic, strength,
  attack, defence, hunter and fishing potions (including super, bastion,
  battlemage and divine versions) while that skill's boost is at least
  **Repot boost margin** levels above base. Combo potions are not covered.
  Default on.
- **Repot boost margin**: default `2` (range 1 to 20).

**Safety Guards**

- **Pickpocket Guard**: blocks `Pickpocket` while your HP is at or below the
  NPC's maximum failed-pickpocket damage plus **Pickpocket damage margin**.
  NPCs not in its table count as 5 damage. The two Pyramid Plunder options
  below only work while this guard is on. Default on.
- **Pickpocket damage margin**: default `1` (range 0 to 20). With a Master
  Farmer (max 3) it blocks at 4 HP or below.
- **Safe urns & speartraps**: blocks `Search`/`Check for Snakes` on urns and
  `Pass` on speartraps at 4 HP or below. Default on.
- **Block sarcophagus**: blocks `Open` on the Pyramid Plunder sarcophagus.
  Default off.
- **Giants' Foundry temp guard**: blocks Trip hammer, Grindstone and Polishing
  wheel while the preform's temperature is outside the band the current
  crafting stage needs. Heating and quenching stay clickable. Default on.
- **Giants' Foundry overwork guard**: blocks the next click on a station once
  its bonus window has opened and closed. Default on.
- **Block world-hop near impling**: blocks world switcher hops while an
  impling from **Impling types to guard** is in the loaded scene. Default on.
- **Impling types to guard**: comma-separated names with `*` wildcards.
  Default `Dragon impling, Lucky impling, Crystal impling, Ninja impling,
  Magpie impling`.

**Instance exit guard**

- **Instance exit guard**: while valuable loot is on the ground inside an
  instance, moves exit and leave options below `Walk here`. Nothing is
  removed, the options stay in the right-click menu. Default on.
- **Loot value over**: a ground item or stack counts as valuable when its price
  times quantity reaches this value. Default `10000000`.
- **Loot whitelist**: item names that always count, with `*` wildcards.
  Default `*(elite), *(hard), *(master), Twisted bow`.
- **Object options**: object menu options that are moved, with `*` wildcards.
  Default `exit,leave,quick-exit,quick-leave,pass-through,travel,quick-travel`.
- **Item/spell options**: inventory, equipment and spell options that are
  moved, with `*` wildcards. Default `break,nardah`.

**Hide & Mute**

- **Mute skilling sounds**: chopping, mining, cooking, smithing, firemaking,
  plant picking, bone burying, Grand Exchange offer sounds, item pickup and
  drop. Default off.
- **Mute prayer sounds**: default off.
- **Mute town crier**: default off.
- **Mute teleport sounds**: default off.
- **Mute other players only**: the mute toggles then only silence sounds
  sourced from other players. Default off.
- **Never mute others in the Wilderness**: other players' sounds stay audible
  in the Wilderness and on PvP and Deadman worlds, overriding the mute
  toggles. Default on.

**Menu Swaps**

- **Take before Attack**: when the menu holds a `Take` on a ground item and an
  `Attack` on a monster from **Enable: monster list**, moves `Attack` to the
  bottom of the menu (below `Walk here`) so the `Take` wins the left-click.
  Default on.
- **Enable: monster list**: NPC names with `*` wildcards, comma or newline
  separated. Default `Brutal black dragon`.
- **Disable: monster exclusions**: NPC names exempted from the swap, taking
  priority over the enable list. Default empty.
- **Ground-item exclusions**: items whose `Take` does not trigger the swap.
  Default `Bones`, which matches plain Bones only.

**Reminders**

- **Clue held-count reminder**: posts a chat message once per clue tier
  (beginner to master) when you carry at least **Reminder threshold** clue
  scrolls of that tier. It re-arms when the count drops below the threshold.
  Default on.
- **Reminder threshold**: default `2` (range 2 to 5).

**Sailing**

- **Attack only port-task targets**: while aboard a boat, removes `Attack` on
  sea bounty monsters when none of your five port-task slots currently targets
  that monster. Monsters outside the port-task bounty pool are never touched.
  Default on.

**Repot Flash**

Flashes the potions in your inventory when a potion effect is about to run
out. Nothing shows while you carry no matching potion.

- **Antifire**: includes extended. Flashes in the final pulse of the effect,
  about 18 seconds. Default on.
- **Super antifire**: includes extended, about 12 seconds. Default on.
- **Poison/venom**: `Off`, `Poison immunity` or `Venom immunity`, about 18
  seconds. Default `Poison immunity`.
- **Overload**: NMZ, Chambers of Xeric and DMM blighted, about 15 seconds.
  Default on.
- **NMZ absorption**: flashes inside the NMZ arena while your absorption
  points are at or below **Absorption threshold (points)**. Default on.
- **Absorption threshold (points)**: default `100` (range 0 to 1000).
- **Menaphite remedy**: about 15 seconds. Default on.
- **Flash style**: `Potion items`, `Full inventory` or `Both`. Default
  `Potion items`.
- **Flash colour 1** and **Flash colour 2**: default red and yellow.
- **Flash interval (ms)**: time per colour, default `500` (range 100 to 1000).
- **Keep flashing after expiry (s)**: flashing continues this long after the
  effect ends and stops when you repot. Default `10` (range 0 to 60).
