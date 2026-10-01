# Tombstone-Cleanup-Addon

Tombstone-Cleanup-Addon is an unofficial, community-made server-side utility for administrators who use [Corail Tombstone](https://www.curseforge.com/minecraft/mc-mods/corail-tombstone). It provides temporary cleanup for Corail Tombstone item stacks and enchantments.

This is a separate community project. It is not an official Corail Tombstone mod or release, and it is not created, maintained, affiliated with, supported, or endorsed by Corail or the Tombstone developers. Corail Tombstone is a separate mod; this add-on is simply a tool that can be used with it. The add-on contains no client classes or client registrations, so clients do not install it.

## Safety and scope

**Before using `/tombstonecleanup execute`, stop the server and make and verify a complete backup of the world and server configuration.** The execute command repeats this warning and requires a short-lived confirmation token. Review a scan and its report before confirming.

The cleaner removes items registered in the `tombstone` namespace except `tombstone:grave_key`. It removes only `tombstone`-namespace enchantments from normal items, retaining the item and its other data components, including non-Tombstone enchantments. Grave Keys are preserved wherever the cleaner encounters them. A Tombstone container that contains other items or a Grave Key is retained and reported rather than deleted with its contents.

The scan covers:

- Online player inventory slots, including armor and offhand; Ender Chests; and nested item containers that can be safely reconstructed.
- Offline files in the current world's `players/data` directory, first migrated with Minecraft's player DataFixer and then decoded with Minecraft 26.1's `ItemStackWithSlot` codec and live registries. Online UUIDs are skipped. A changed file is copied and hash-verified under `tombstone-cleanup-backups/<timestamp>/players/data/` before an atomic replacement. Files that cannot be decoded, encoded, safely backed up, or atomically replaced are left unchanged and reported.
- Server chunks that NeoForge reports as loaded and that remain loaded when the command runs. This includes full chunks outside the entity-ticking range. Chunk load and unload events maintain the scan set; the cleaner does not load or generate chunks by itself.
- Item inventories exposed by vanilla `Container` or NeoForge's transactional item `ResourceHandler` API, plus dropped item entities in those loaded chunks. Tombstone-owned block entities are skipped entirely so normal graves and their stored player items are not changed; the report counts those skipped block entities.
- Active AE2 networks discoverable through loaded AE2 grid hosts. Each distinct grid is processed once using AE2's public `MEStorage` API; extraction and reinsertion are simulated, exact amounts are checked, and failed transformations attempt a verified rollback. Network contents in undiscovered or inaccessible networks are not modified.

Unloaded chunks and non-exposed mod storage are not scanned. For a controlled pass over previously generated areas, an operator may use vanilla `/forceload add` on a small, known set of chunk coordinates, then run `scan` and (after reviewing it) `execute`. Only force-load chunks that are known to exist: vanilla may generate a chunk if a coordinate was never generated. The server's force-loaded-chunk limit still applies. Remove temporary tickets with `/forceload remove` after the pass. The report's loaded-chunk count shows the chunks included; it is not a full-world count.

Bundles are inspected, but if a bundle contains a target stack, the cleaner reports it as unsupported and leaves that bundle unchanged. Minecraft 26.1 exposes no public lossless reconstruction operation that preserves a bundle's selected item and ordering. Excessively deep nested containers are also skipped and reported. Make a full backup even though offline player files receive individual backups: loaded world containers and AE2 networks are changed in place.

## Build

Requirements: Java 25 and the included Gradle 9.2.1 wrapper. Network access is needed the first time Gradle resolves game and mod artifacts.

```powershell
./gradlew.bat test
./gradlew.bat build
```

The JAR is written to `build/libs/tombstone-cleanup-addon-1.0.0.jar`.

The unit suite exercises the registry-namespace policy and enchantment-identifier filtering helper. Minecraft's actual ItemStack component rewriting, live server inventory traversal, and AE2 network mutation are compiled against the pinned game APIs but are not simulated by these JUnit helper tests.

## Install and run

1. Stop the dedicated server and make a full, restorable backup of the world and configuration.
2. Copy `tombstone-cleanup-addon-1.0.0.jar` into the server's `mods` directory. Keep the existing Minecraft 26.1.2 and NeoForge 26.1.2 installation; AE2 may be installed normally on the server.
3. Start the server and run `/tombstonecleanup scan` as an administrator. Read the summary and the detailed `tombstone-cleanup/reports/` file under the world's root directory.
4. If the scan is acceptable, run `/tombstonecleanup execute`. The server returns a confirmation token. Run `/tombstonecleanup execute <token>` within 60 seconds from the same operator identity. Execution is refused while the server is saving.
5. Run `/tombstonecleanup report` to view the most recent scan or execution result. Reports include category counts, applied removals and transformations, Grave Keys preserved, failures, unsupported storage, loaded-chunk scope, offline-player work, and AE2 operations. Inventory contents are not written to reports.
6. Run `/tombstonecleanup scan` again and review the post-cleanup counts. Address reported unsupported or unloaded storage separately.
7. Save and stop the server, remove the temporary cleanup JAR, then start the server normally.

All commands require Minecraft's `COMMANDS_ADMIN` permission or higher. Reports are saved in `<world-root>/tombstone-cleanup/reports/`. Only offline player files that are changed receive individual backups; the administrator must separately back up the world before execution.

## APIs and versions

- Minecraft 26.1.2; Java 25.
- NeoForge 26.1.2.111 and ModDevGradle 2.0.148.
- Applied Energistics 2 API 26.1.12-beta is compile-only and optional at runtime. It is a server-side optional mod metadata dependency.
- Corail Tombstone 9.8.4 for Minecraft 26.1.2 NeoForge is identified by its registry namespace only; it is not a code dependency and is not required at runtime.

The exact resolved mod versions are pinned in `gradle.properties`; Minecraft and NeoForge requirements are constrained in `META-INF/neoforge.mods.toml`.

## License

The source, compiled mod, documentation, and original assets are covered by the [Tombstone-Cleanup-Addon Custom Community License](LICENSE).
