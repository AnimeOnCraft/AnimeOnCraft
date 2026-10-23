# Compatibility data pack

Minecraft 1.21.1 data pack format 48. Dungeons and Taverns v4.4.4 declares two advancement parents as `minecraft:root`, but that root is absent from vanilla and the selected pack. This small original definition supplies an invisible root with an impossible criterion, allowing Minecraft to attach those existing advancements to its tree. It does not copy mod assets, replace their criteria/rewards, or modify their JAR.

`prepare-modpack.mjs` places pack.mcmeta and the root definition under the configured local world's `datapacks/animeoncraft-compat/`. Validate with a real server startup and absence of the two previous unresolved-parent errors. Remove/reassess this compatibility pack if the original mod or Minecraft version changes.
