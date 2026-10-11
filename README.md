# Auto Shield Breaker (Fabric 1.21.11, client-side)

Swaps to your best hotbar axe when you attack a target that is blocking with a shield,
then swaps back. Toggle key: M (rebindable under Controls > Auto Shield Breaker).
Config: .minecraft/config/shieldbreaker.json

## Requirements
Minecraft 1.21.11, Fabric Loader 0.18+, Fabric API 0.141.x+1.21.11, Java 21.

## Build
1. Install JDK 21.
2. Add the Gradle wrapper. Easiest: generate a 1.21.11 template at https://fabricmc.net/develop/template
   and copy its `gradlew`, `gradlew.bat` and `gradle/` folder here (it ships a Gradle version that
   matches Loom). Also compare its gradle.properties with this one and use its versions if they differ.
3. `./gradlew build` -> build/libs/shieldbreaker-1.0.0.jar   (`./gradlew runClient` to test)
4. Put the jar and Fabric API in .minecraft/mods.

## Config options
enabled, switchDelayTicks (0-3), randomizeDelay, switchBack, switchBackDelayTicks,
targetPlayers, targetMobs, minAxeDurability

Many servers prohibit automated combat helpers. Use at your own risk.

## Build online with no install (GitHub Actions)
1. Create a free GitHub account and a new repository.
2. Upload all files from this folder (including the hidden `.github` folder).
3. Open the repo's Actions tab -> "Build mod" -> wait for the green check.
4. Open the finished run, download the `shieldbreaker-jar` artifact, unzip it to get the .jar.
