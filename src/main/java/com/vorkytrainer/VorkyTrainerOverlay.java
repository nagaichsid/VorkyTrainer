package com.vorkytrainer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.Perspective;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

class VorkyTrainerOverlay extends Overlay
{
	// Overlay palette
	private static final Color START_COLOR = new Color(0, 188, 212, 175);
	private static final Color DEADLY_MOVE_COLOR = new Color(255, 167, 38);
	private static final Color ACID_COLOR = new Color(0, 150, 136);
	private static final Color LANE_BORDER_COLOR = new Color(255, 213, 79, 150);
	private static final Color SPAWN_COLOR = new Color(66, 165, 245);
	private static final Color LOW_HP_COLOR = new Color(229, 57, 53, 175);
	private static final Color SPECIAL_COLOR = new Color(255, 235, 59);

	private static final int VORKATH_BASE_ANIM = -1;
	private static final int VORKATH_SPECIAL_ANIM = 7960;
	private static final int VORKATH_MELEE_ANIM = 7951;
	private static final int VORKATH_ATTACK_ANIM =7952;
	private static final int VORKATH_ACID_ANIM = 7957;

	private final Client client;
	private final VorkyTrainerPlugin plugin;
	private final VorkyTrainerConfig config;

	@Inject
	private VorkyTrainerOverlay(Client client, VorkyTrainerPlugin plugin, VorkyTrainerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Order roughly matches player decision flow.
		renderStartIndicator(graphics);
		renderSpecialAttackHint(graphics);
		renderDeadlyMoveTile(graphics);
		renderAcidTiles(graphics);
		renderWooxGuide(graphics);
		renderSpawnHint(graphics);
		renderProjectileHint(graphics);
		renderLowHpWarning(graphics);
		return null;
	}

	private void renderStartIndicator(Graphics2D graphics)
	{
		if (!config.showStartIndicator())
		{
			return;
		}

		NPC asleep = plugin.getVorkathSleeping();
		NPC fighting = plugin.getVorkath();
		// Only show START when sleeping Vorkath is present and the fight NPC is absent.
		if (asleep != null && fighting == null)
		{
			OverlayUtil.renderActorOverlay(graphics, asleep, "START", START_COLOR);
		}
	}

	private void renderDeadlyMoveTile(Graphics2D graphics)
	{
		if (!config.showDeadlyMoveTile())
		{
			return;
		}

		// Single suggested tile during deadly dragonfire.
		WorldPoint tile = plugin.getDeadlySuggestedTile();
		if (tile != null)
		{
			renderTileWithText(graphics, tile, "MOVE", DEADLY_MOVE_COLOR);
		}
	}

	private void renderAcidTiles(Graphics2D graphics)
	{
		if (!config.showAcidTiles())
		{
			return;
		}

		// Acid tiles render as simple outlines to avoid covering the player's tile.
		for (WorldPoint tile : plugin.getAcidTiles())
		{
			renderTile(graphics, tile, ACID_COLOR);
		}
	}

	private void renderWooxGuide(Graphics2D graphics)
	{
		if (!config.showWooxGuide())
		{
			return;
		}

		// Melee lane guidance only; no step/attack indicators.
		for (WorldPoint tile : plugin.getLaneTiles())
		{
			renderTileOutline(graphics, tile, LANE_BORDER_COLOR, 2f);
		}

	}

	private void renderSpawnHint(Graphics2D graphics)
	{
		if (!config.showSpawnHints())
		{
			return;
		}

		// Zombified spawn outline and label.
		NPC spawn = plugin.getSpawn();
		if (spawn != null)
		{
			OverlayUtil.renderActorOverlay(graphics, spawn, "Crumble Undead", SPAWN_COLOR);
		}
	}

	private void renderProjectileHint(Graphics2D graphics)
	{
		if (!config.showProjectiles())
		{
			return;
		}

		Projectile projectile = plugin.getLastProjectile();
		VorkyTrainerPlugin.ProjectileHintType hint = plugin.getLastProjectileHint();
		if (projectile == null || hint == null)
		{
			return;
		}

		Color color;
		String label;
		switch (hint)
		{
			case RANGED:
				color = new Color(255, 167, 38);
				label = "PRAY RANGE";
				break;
			case MAGE:
				color = new Color(66, 165, 245);
				label = "PRAY MAGE";
				break;
			case SPAWN:
				color = SPAWN_COLOR;
				label = "CRUMBLE UNDEAD";
				break;
			case PRAYER_DISABLED:
			default:
				color = LOW_HP_COLOR;
				label = "PRAYER DISABLED";
				break;
		}

		// Render text at the projectile position.
		int x = (int) projectile.getX();
		int y = (int) projectile.getY();
		LocalPoint projectilePoint = new LocalPoint(x, y);
		Point textLocation = Perspective.getCanvasTextLocation(client, graphics, projectilePoint, label, 0);
		if (textLocation != null)
		{
			OverlayUtil.renderTextLocation(graphics, textLocation, label, color);
		}
		OverlayUtil.renderTextLocation(graphics, new Point(20, 20), label, color);
	}

	private void renderLowHpWarning(Graphics2D graphics)
	{
		if (plugin.isLowHp())
		{
			OverlayUtil.renderTextLocation(graphics, new Point(20, 40), "EAT", LOW_HP_COLOR);
		}
	}

	private void renderSpecialAttackHint(Graphics2D graphics)
	{
		if (!config.showSpecialAttackHint())
		{
			return;
		}

		NPC vorkath = plugin.getVorkath();
		if (vorkath == null)
		{
			return;
		}

		// Animation-based special cues
		if (vorkath.getAnimation() == VORKATH_SPECIAL_ANIM)
		{
			OverlayUtil.renderActorOverlay(graphics, vorkath, "SPECIAL", SPECIAL_COLOR);
		}
		else if (vorkath.getAnimation() == VORKATH_MELEE_ANIM)
		{
			OverlayUtil.renderActorOverlay(graphics, vorkath, "MELEE", LOW_HP_COLOR);
		}
	}

	// Used for acid tile highlights
	private void renderTile(Graphics2D graphics, WorldPoint worldPoint, Color color)
	{
		Polygon poly = getCanvasTilePoly(worldPoint);
		if (poly != null)
		{
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	// Used for woox lane outline
	private void renderTileOutline(Graphics2D graphics, WorldPoint worldPoint, Color color, float strokeWidth)
	{
		Polygon poly = getCanvasTilePoly(worldPoint);
		if (poly == null)
		{
			return;
		}

		Stroke originalStroke = graphics.getStroke();
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(strokeWidth));
		graphics.draw(poly);
		graphics.setStroke(originalStroke);
	}

	// Used for suggested tile on deadly dragon fire attack
	private void renderTileWithText(Graphics2D graphics, WorldPoint worldPoint, String text, Color color)
	{
		Polygon poly = getCanvasTilePoly(worldPoint);
		if (poly != null)
		{
			OverlayUtil.renderPolygon(graphics, poly, color);
			Point textPoint = new Point(poly.getBounds().x, poly.getBounds().y);
			OverlayUtil.renderTextLocation(graphics, textPoint, text, color);
		}
	}

	private Polygon getCanvasTilePoly(WorldPoint worldPoint)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
		if (localPoint == null)
		{
			return null;
		}
		return Perspective.getCanvasTilePoly(client, localPoint);
	}
}
