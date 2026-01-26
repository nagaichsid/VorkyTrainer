# Vorky Trainer

RuneLite plugin that provides visual guidance for the Vorkath melee fight.

## Features
- Start indicator for sleeping Vorkath.
- Deadly dragonfire move tile suggestion.
- Acid tile highlighting and safe lane guidance.
- Zombified spawn hint arrow and overlay.
- Projectile-based prayer/phase callouts.
- Special attack highlight on Vorkath's tile.
- Low HP warning.

## Configuration
All options live under the `Vorky Trainer` config group.
- Show start indicator
- Show deadly dragonfire move tile
- Show acid tiles
- Show Woox walk lane
- Show projectile hints
- Show special attack hint
- Show zombified spawn hints
- Low HP threshold

## Mechanics Overview
- **Start indicator:** Highlights sleeping Vorkath while the fight NPC is absent.
- **Deadly dragonfire:** When the special projectile is fired, suggests a single tile two steps
  east or west along Vorkath's south edge, based on your position.
- **Acid tracking:** Rebuilds acid tiles each tick by scanning scene game objects with the acid ID.
- **Lane selection:** Picks a southward lane starting from Vorkath's south edge with minimum length 3,
  locks it during the acid phase to avoid flicker.
- **Projectiles:** Ranged/mage/anti-prayer/spawn projectiles display a short on-screen hint.
- **Low HP:** Displays an "EAT" warning when below the configured threshold.

## Development
Follow this guide to set up a local development environment: https://github.com/runelite/plugin-hub?tab=readme-ov-file

Run the client from the repo root:
```bash
./gradlew :runelite-client:run --args="--developer-mode --debug"
```

## File Layout
- `VorkyTrainerPlugin.java` - core logic and state
- `VorkyTrainerOverlay.java` - rendering
- `VorkyTrainerConfig.java` - config
