package com.vorkytrainer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("vorkytrainer")
public interface VorkyTrainerConfig extends Config
{
	// Visual guidance toggles.
	@ConfigItem(
		keyName = "showStartIndicator",
		name = "Show start indicator",
		description = "Highlight sleeping Vorkath with a START label before the fight."
	)
	default boolean showStartIndicator()
	{
		return true;
	}

	// Deadly dragonfire move tile.
	@ConfigItem(
		keyName = "showDeadlyMoveTile",
		name = "Show deadly dragonfire move tile",
		description = "Highlight a suggested tile two steps away when deadly dragonfire is fired."
	)
	default boolean showDeadlyMoveTile()
	{
		return true;
	}

	// Acid hazard tiles.
	@ConfigItem(
		keyName = "showAcidTiles",
		name = "Show acid tiles",
		description = "Highlight acid hazard tiles during the acid phase."
	)
	default boolean showAcidTiles()
	{
		return true;
	}

	// Lane guidance only; no step/attack hints.
	@ConfigItem(
		keyName = "showWooxLane",
		name = "Show Woox walk lane",
		description = "Suggest a safe lane during acid."
	)
	default boolean showWooxGuide()
	{
		return true;
	}

	// Projectile callouts for prayer/phase cues.
	@ConfigItem(
		keyName = "showProjectiles",
		name = "Show projectile hints",
		description = "Show projectile highlights and prayer banners for key attacks."
	)
	default boolean showProjectiles()
	{
		return true;
	}

	// NPC model outline during specials.
	@ConfigItem(
		keyName = "showSpecialAttackHint",
		name = "Show special attack hint",
		description = "Highlight Vorkath during special attack animations."
	)
	default boolean showSpecialAttackHint()
	{
		return true;
	}

	// Zombified spawn hint arrow and label.
	@ConfigItem(
		keyName = "showSpawnHints",
		name = "Show zombified spawn hints",
		description = "Set a hint arrow and overlay on the zombified spawn."
	)
	default boolean showSpawnHints()
	{
		return true;
	}

	// Low HP warning threshold.
	@ConfigItem(
		keyName = "lowHpThreshold",
		name = "Low HP threshold",
		description = "HP value at or below which the plugin warns you to eat."
	)
	@Range(min = 1, max = 99)
	default int lowHpThreshold()
	{
		return 35;
	}
}
