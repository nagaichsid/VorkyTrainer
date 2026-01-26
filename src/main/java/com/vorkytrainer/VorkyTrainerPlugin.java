package com.vorkytrainer;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Vorky Trainer",
	description = "Visual guidance for Vorkath melee mechanics (no automation).",
	enabledByDefault = true
)
public class VorkyTrainerPlugin extends Plugin
{
	// NPC/Projectile/Spotanim ids verified in-game.
	private static final int NPC_VORKATH_ASLEEP = 8059;
	private static final int NPC_VORKATH_FIGHT = 8061;
	private static final int NPC_ZOMBIFIED_SPAWN = 8063;

	private static final int PROJ_VENOM = 1470;
	private static final int PROJ_STANDARD = 393;
	private static final int PROJ_ANTIPRAYER = 1471;
	private static final int PROJ_RANGED = 1477;
	private static final int PROJ_MAGIC = 1479;
	private static final int PROJ_DEADLY_DF = 1481;
	private static final int PROJ_FREEZE_DF = 395;

	// Acid tiles are represented as scene game objects (not graphics objects).
	private static final int GFX_ACID_TILE = 32000;

	private static final int DEADLY_TILE_TTL_TICKS = 8;
	private static final int PROJECTILE_TTL_TICKS = 6;

	// Arena bounds are derived from Vorkath's center to handle instance coords.
	private static final int ARENA_SIZE = 20;

	enum VorkathPhase
	{
		IDLE,
		SLEEPING,
		FIGHT,
		ACID
	}

	enum ProjectileHintType
	{
		RANGED,
		MAGE,
		PRAYER_DISABLED,
		SPAWN
	}

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private VorkyTrainerOverlay overlay;

	@Inject
	private VorkyTrainerConfig config;

	@Getter
	private NPC vorkathSleeping;
	@Getter
	private NPC vorkath;
	@Getter
	private NPC spawn;

	@Getter
	private final Set<WorldPoint> acidTiles = new HashSet<>();
	@Getter
	private final List<WorldPoint> laneTiles = new ArrayList<>();
	// Keep a stable lane during acid to avoid mid-phase swapping.
	private LaneChoice lockedLane;

	@Getter
	private VorkathPhase phase = VorkathPhase.IDLE;

	@Getter
	private WorldPoint deadlySuggestedTile;
	private WorldPoint deadlyOrigin;
	private int deadlyExpireTick;
	private int lastDeadlyStartCycle = -1;

	@Getter
	private Projectile lastProjectile;
	@Getter
	private ProjectileHintType lastProjectileHint;
	private int projectileExpireTick;

	@Getter
	private boolean lowHp;

	private int lastAttackTick = -1000;
	private NPC hintArrowTarget;
	private WorldArea arenaArea;

	@Provides
	VorkyTrainerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(VorkyTrainerConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		resetState();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clearSpawnHint();
		resetState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			resetState();
			clearSpawnHint();
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		switch (npc.getId())
		{
			case NPC_VORKATH_ASLEEP:
				vorkathSleeping = npc;
				break;
			case NPC_VORKATH_FIGHT:
				vorkath = npc;
				break;
			case NPC_ZOMBIFIED_SPAWN:
				spawn = npc;
				updateSpawnHint();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		switch (npc.getId())
		{
			case NPC_VORKATH_ASLEEP:
				if (npc == vorkathSleeping)
				{
					vorkathSleeping = null;
				}
				break;
			case NPC_VORKATH_FIGHT:
				if (npc == vorkath)
				{
					vorkath = null;
				}
				break;
			case NPC_ZOMBIFIED_SPAWN:
				if (npc == spawn)
				{
					spawn = null;
				}
				clearSpawnHint();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile projectile = event.getProjectile();
		if (projectile == null)
		{
			return;
		}

		int id = projectile.getId();
		// Snapshot deadly DF on first movement cycle.
		if (id == PROJ_DEADLY_DF)
		{
			int startCycle = projectile.getStartCycle();
			if (startCycle != lastDeadlyStartCycle)
			{
				lastDeadlyStartCycle = startCycle;
				recordDeadlyDragonfireTile();
			}
			return;
		}

		// Short-lived projectile hints (prayer/phase cues).
		if (id == PROJ_RANGED || id == PROJ_MAGIC || id == PROJ_VENOM || id == PROJ_STANDARD || id == PROJ_ANTIPRAYER || id == PROJ_FREEZE_DF)
		{
			lastProjectile = projectile;
			projectileExpireTick = client.getTickCount() + PROJECTILE_TTL_TICKS;
			if (id == PROJ_RANGED)
			{
				lastProjectileHint = ProjectileHintType.RANGED;
			}
			else if (id == PROJ_MAGIC || id == PROJ_VENOM || id == PROJ_STANDARD)
			{
				lastProjectileHint = ProjectileHintType.MAGE;
			}
			else if (id == PROJ_FREEZE_DF)
			{
				lastProjectileHint = ProjectileHintType.SPAWN;
			}
			else
			{
				lastProjectileHint = ProjectileHintType.PRAYER_DISABLED;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Per-tick state rebuild for robustness across region/loading jitter.
		syncNpcRefs();
		updateArena();
		rebuildAcidTiles(localPlayer);
		updatePhase();
		updateTimers();
		updateSpawnHint();
		updateLowHp(localPlayer);
		updateAttackTick(localPlayer);
		updateWooxGuide(localPlayer);
	}

	private void resetState()
	{
		// Clear all fight state and hints.
		vorkathSleeping = null;
		vorkath = null;
		spawn = null;
		acidTiles.clear();
		laneTiles.clear();
		lockedLane = null;
		deadlySuggestedTile = null;
		deadlyOrigin = null;
		deadlyExpireTick = 0;
		lastDeadlyStartCycle = -1;
		lastProjectile = null;
		lastProjectileHint = null;
		projectileExpireTick = 0;
		phase = VorkathPhase.IDLE;
		lowHp = false;
		lastAttackTick = -1000;
		arenaArea = null;
	}

	private void syncNpcRefs()
	{
		// Resolve fresh NPC references every tick to avoid stale pointers.
		NPC sleeping = null;
		NPC fighting = null;
		NPC spawned = null;

		for (NPC npc : client.getNpcs())
		{
			switch (npc.getId())
			{
				case NPC_VORKATH_ASLEEP:
					sleeping = npc;
					break;
				case NPC_VORKATH_FIGHT:
					fighting = npc;
					break;
				case NPC_ZOMBIFIED_SPAWN:
					spawned = npc;
					break;
				default:
					break;
			}
		}

		vorkathSleeping = sleeping;
		vorkath = fighting;
		spawn = spawned;
	}

	private void rebuildAcidTiles(Player localPlayer)
	{
		// Acid tracking: rebuild the set from scene game objects every tick for stability.
		acidTiles.clear();
		WorldView worldView = localPlayer.getWorldView();
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = worldView.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return;
		}

		WorldArea arena = getArena();
		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return;
		}
		for (int x = 0; x < planeTiles.length; x++)
		{
			Tile[] row = planeTiles[x];
			if (row == null)
			{
				continue;
			}
			for (int y = 0; y < row.length; y++)
			{
				Tile tile = row[y];
				if (tile == null)
				{
					continue;
				}

				GameObject[] objects = tile.getGameObjects();
				if (objects == null)
				{
					continue;
				}

				for (GameObject object : objects)
				{
					if (object != null && object.getId() == GFX_ACID_TILE)
					{
						WorldPoint point = tile.getWorldLocation();
						if (arena.contains2D(point))
						{
							acidTiles.add(point);
						}
						break;
					}
				}
			}
		}
	}

	private void updatePhase()
	{
		// Phase is inferred from NPC presence and acid tiles.
		if (vorkath != null)
		{
			phase = acidTiles.isEmpty() ? VorkathPhase.FIGHT : VorkathPhase.ACID;
			return;
		}

		if (vorkathSleeping != null)
		{
			phase = VorkathPhase.SLEEPING;
			return;
		}

		phase = VorkathPhase.IDLE;
	}

	private void updateTimers()
	{
		// TTL cleanup for temporary hints.
		int tick = client.getTickCount();
		if (deadlySuggestedTile != null && tick > deadlyExpireTick)
		{
			deadlySuggestedTile = null;
		}

		if (lastProjectile != null && tick > projectileExpireTick)
		{
			lastProjectile = null;
			lastProjectileHint = null;
		}
	}

	private void updateSpawnHint()
	{
		// Only one hint arrow at a time; clear on despawn or config off.
		if (!config.showSpawnHints())
		{
			clearSpawnHint();
			return;
		}

		if (spawn != null && spawn != hintArrowTarget)
		{
			client.setHintArrow(spawn);
			hintArrowTarget = spawn;
		}
		else if (spawn == null)
		{
			clearSpawnHint();
		}
	}

	private void clearSpawnHint()
	{
		if (hintArrowTarget != null)
		{
			client.clearHintArrow();
			hintArrowTarget = null;
		}
	}

	private void updateLowHp(Player localPlayer)
	{
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		lowHp = hp <= config.lowHpThreshold();
	}

	private void updateAttackTick(Player localPlayer)
	{
		if (vorkath == null)
		{
			return;
		}

		// Proxy for "last attack": melee range and interacting with Vorkath.
		if (localPlayer.getInteracting() == vorkath
			&& localPlayer.getWorldArea().isInMeleeDistance(vorkath.getWorldArea()))
		{
			lastAttackTick = client.getTickCount();
		}
	}

	private void updateWooxGuide(Player localPlayer)
	{
		laneTiles.clear();

		if (!config.showWooxGuide() || phase != VorkathPhase.ACID)
		{
			lockedLane = null;
			return;
		}

		WorldPoint playerLocation = localPlayer.getWorldLocation();
		// Lane selection: southward lane from Vorkath's south edge, min length 3.
		LaneChoice candidate = findBestLane(playerLocation);
		LaneChoice lane = resolveLockedLane(candidate);
		if (lane == null || lane.tiles.isEmpty())
		{
			return;
		}

		// Only lane tiles are rendered; no step/attack hints.
		laneTiles.addAll(lane.tiles);
	}

	private void recordDeadlyDragonfireTile()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Snapshot player origin and compute a single suggested move tile.
		deadlyOrigin = localPlayer.getWorldLocation();
		WorldPoint candidate = findDeadlySafeTile(deadlyOrigin, localPlayer.getWorldLocation());
		if (candidate == null)
		{
			return;
		}

		deadlySuggestedTile = candidate;
		deadlyExpireTick = client.getTickCount() + DEADLY_TILE_TTL_TICKS;
	}

	private WorldPoint findDeadlySafeTile(WorldPoint origin, WorldPoint playerLocation)
	{
		// Prefer Vorkath-anchored east/west step on his south edge.
		if (vorkath != null)
		{
			WorldPoint anchored = findDeadlyFromVorkath(playerLocation);
			if (anchored != null)
			{
				return anchored;
			}
		}

		List<WorldPoint> candidates = new ArrayList<>();
		int[] deltas = {-2, 0, 2};
		for (int dx : deltas)
		{
			for (int dy : deltas)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				if (Math.max(Math.abs(dx), Math.abs(dy)) != 2)
				{
					continue;
				}
				WorldPoint point = new WorldPoint(origin.getX() + dx, origin.getY() + dy, origin.getPlane());
				if (!getArena().contains2D(point))
				{
					continue;
				}
				if (acidTiles.contains(point))
				{
					continue;
				}
				if (isWalkable(point))
				{
					candidates.add(point);
				}
			}
		}

		if (candidates.isEmpty())
		{
			return null;
		}

		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint candidate : candidates)
		{
			int distance = candidate.distanceTo2D(playerLocation);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	private WorldPoint findDeadlyFromVorkath(WorldPoint playerLocation)
	{
		WorldArea area = vorkath.getWorldArea();
		int frontY = area.getY() - 1;
		int centerX = area.getX() + area.getWidth() / 2;

		// If the player is at/left of center, step east; otherwise step west.
		boolean preferEast = playerLocation.getX() <= centerX;
		int targetX = preferEast ? playerLocation.getX() + 2 : playerLocation.getX() - 2;
		WorldPoint candidate = new WorldPoint(targetX, frontY, area.getPlane());

		if (!getArena().contains2D(candidate))
		{
			return null;
		}

		if (acidTiles.contains(candidate) || !isWalkable(candidate))
		{
			return null;
		}

		return candidate;
	}

	private boolean isWalkable(WorldPoint point)
	{
		// Basic collision check against the current scene.
		WorldView wv = client.findWorldViewFromWorldPoint(point);
		if (wv == null)
		{
			return false;
		}
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || point.getPlane() < 0 || point.getPlane() >= maps.length)
		{
			return true;
		}

		CollisionData map = maps[point.getPlane()];
		if (map == null)
		{
			return true;
		}

		LocalPoint localPoint = LocalPoint.fromWorld(wv, point);
		if (localPoint == null)
		{
			return false;
		}

		int[][] flags = map.getFlags();
		int sceneX = localPoint.getSceneX();
		int sceneY = localPoint.getSceneY();
		if (sceneX < 0 || sceneY < 0 || sceneX >= flags.length || sceneY >= flags[sceneX].length)
		{
			return false;
		}

		return (flags[sceneX][sceneY] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	private LaneChoice findBestLane(WorldPoint playerLocation)
	{
		// Scan vertical segments and select the best southward lane from Vorkath's south edge.
		WorldArea arena = getArena();
		NPC target = vorkath;
		if (target == null)
		{
			return null;
		}

		WorldArea vorkathArea = target.getWorldArea();
		LaneChoice best = null;
		int bestScore = Integer.MIN_VALUE;

		for (int x = arena.getX(); x < arena.getX() + arena.getWidth(); x++)
		{
			List<LaneSegment> segments = buildColumnSegments(x);
			for (LaneSegment segment : segments)
			{
				LaneChoice choice = buildSouthLane(segment, vorkathArea, playerLocation);
				if (choice == null)
				{
					continue;
				}

				int score = choice.score;
				if (score > bestScore)
				{
					bestScore = score;
					best = choice;
				}
			}
		}

		return best;
	}

	private LaneChoice buildSouthLane(LaneSegment segment, WorldArea vorkathArea, WorldPoint playerLocation)
	{
		// Only lanes that start at Vorkath's south edge and extend south are valid.
		if (segment.length < 3)
		{
			return null;
		}

		int frontY = vorkathArea.getY() - 1;
		WorldPoint bestAttack = null;
		int bestAttackScore = Integer.MIN_VALUE;
		for (WorldPoint tile : segment.toTiles())
		{
			if (!vorkathArea.isInMeleeDistance(tile.toWorldArea()))
			{
				continue;
			}
			if (tile.getY() != frontY)
			{
				continue;
			}

			int southLength = tile.getY() - segment.start + 1;
			if (southLength < 3)
			{
				continue;
			}

			int distance = tile.distanceTo2D(playerLocation);
			int score = (southLength >= 4 ? 10000 : 0) + (southLength * 100) - distance;
			if (score > bestAttackScore)
			{
				bestAttackScore = score;
				bestAttack = tile;
			}
		}

		if (bestAttack == null)
		{
			return null;
		}

		List<WorldPoint> tiles = new ArrayList<>();
		for (int y = bestAttack.getY(); y >= segment.start; y--)
		{
			tiles.add(new WorldPoint(segment.fixed, y, segment.plane));
		}

		WorldPoint backTile = tiles.get(tiles.size() - 1);
		return new LaneChoice(tiles, bestAttack, backTile, bestAttackScore);
	}

	private List<LaneSegment> buildColumnSegments(int x)
	{
		List<LaneSegment> segments = new ArrayList<>();
		int start = -1;
		WorldArea arena = getArena();
		for (int y = arena.getY(); y < arena.getY() + arena.getHeight(); y++)
		{
			WorldPoint point = new WorldPoint(x, y, arena.getPlane());
			boolean safe = !acidTiles.contains(point) && isWalkable(point);
			if (safe && start == -1)
			{
				start = y;
			}
			else if (!safe && start != -1)
			{
				segments.add(new LaneSegment(false, x, start, y - 1, arena.getPlane()));
				start = -1;
			}
		}
		if (start != -1)
		{
			segments.add(new LaneSegment(false, x, start, arena.getY() + arena.getHeight() - 1, arena.getPlane()));
		}
		return segments;
	}

	private static class LaneChoice
	{
		private final List<WorldPoint> tiles;
		private final WorldPoint attackTile;
		private final WorldPoint backTile;
		private final int score;

		private LaneChoice(List<WorldPoint> tiles, WorldPoint attackTile, WorldPoint backTile, int score)
		{
			this.tiles = tiles;
			this.attackTile = attackTile;
			this.backTile = backTile;
			this.score = score;
		}
	}


	private LaneChoice resolveLockedLane(LaneChoice candidate)
	{
		// Keep the first valid lane to reduce flicker.
		if (lockedLane != null && isLaneStillValid(lockedLane))
		{
			return lockedLane;
		}

		lockedLane = candidate != null && isLaneStillValid(candidate) ? candidate : null;
		return lockedLane;
	}

	private boolean isLaneStillValid(LaneChoice lane)
	{
		// Validate the locked lane against current acid tiles and collision.
		if (lane == null || lane.tiles.isEmpty() || vorkath == null)
		{
			return false;
		}

		WorldArea arena = getArena();
		WorldArea vorkathArea = vorkath.getWorldArea();
		int frontY = vorkathArea.getY() - 1;

		if (lane.attackTile == null
			|| lane.attackTile.getY() != frontY
			|| !vorkathArea.isInMeleeDistance(lane.attackTile.toWorldArea()))
		{
			return false;
		}

		for (WorldPoint tile : lane.tiles)
		{
			if (!arena.contains2D(tile))
			{
				return false;
			}
			if (acidTiles.contains(tile) || !isWalkable(tile))
			{
				return false;
			}
		}

		return lane.tiles.size() >= 3;
	}

	private static class LaneSegment
	{
		private final boolean horizontal;
		private final int fixed;
		private final int start;
		private final int end;
		private final int length;
		private final int plane;

		private LaneSegment(boolean horizontal, int fixed, int start, int end, int plane)
		{
			this.horizontal = horizontal;
			this.fixed = fixed;
			this.start = start;
			this.end = end;
			this.length = end - start + 1;
			this.plane = plane;
		}

		private List<WorldPoint> toTiles()
		{
			List<WorldPoint> tiles = new ArrayList<>();
			if (horizontal)
			{
				for (int x = start; x <= end; x++)
				{
					tiles.add(new WorldPoint(x, fixed, plane));
				}
			}
			else
			{
				for (int y = start; y <= end; y++)
				{
					tiles.add(new WorldPoint(fixed, y, plane));
				}
			}
			return tiles;
		}
	}

	private void updateArena()
	{
		NPC anchor = vorkath != null ? vorkath : vorkathSleeping;
		if (anchor == null)
		{
			return;
		}

		WorldArea area = anchor.getWorldArea();
		int centerX = area.getX() + area.getWidth() / 2;
		int centerY = area.getY() + area.getHeight() / 2;
		int half = ARENA_SIZE / 2;
		arenaArea = new WorldArea(centerX - half, centerY - half, ARENA_SIZE, ARENA_SIZE, area.getPlane());
	}

	private WorldArea getArena()
	{
		if (arenaArea != null)
		{
			return arenaArea;
		}
		return new WorldArea(2265, 4050, ARENA_SIZE, ARENA_SIZE, 0);
	}
}
