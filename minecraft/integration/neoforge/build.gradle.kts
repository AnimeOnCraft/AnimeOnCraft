plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

version = "0.3.0-friends-dev"
base { archivesName.set("animeoncraft-admission") }
neoForge {
    enable {
        version = "21.1.262"
        setDisableRecompilation(true)
    }
    mods {
        create("animeoncraft") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    compileOnly("info.journeymap:journeymap-api-neoforge:2.0.0-1.21.1")
    compileOnly("maven.modrinth:xaeros-world-map:SnQ4SoDe") { isTransitive = false }
    compileOnly("maven.modrinth:xaeros-minimap:irjR7dHk") { isTransitive = false }
    compileOnly("maven.modrinth:create:UjX6dr61") { isTransitive = false }
    compileOnly("maven.modrinth:lightmans-currency:XMHRtEIk") { isTransitive = false }
    compileOnly("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1") { isTransitive = false }
    testImplementation("com.google.code.gson:gson:2.11.0")
}

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.createmod.net") { content { includeGroup("net.createmod.ponder") } }
    maven("https://maven.blamejared.com") {
        content { includeGroup("info.journeymap") }
    }
}

tasks.test {
    inputs.file("../../server/clan-world.json")
}

val retireAtlasImage = tasks.register<Delete>("retireAtlasImage") {
    // Retire only the previous generated resource; private Xaero data is never touched.
    delete(layout.buildDirectory.file("resources/main/assets/animeoncraft/atlas/relief.jpg"))
}
tasks.processResources {
    dependsOn(retireAtlasImage)
    exclude("assets/animeoncraft/atlas/*.jpg", "assets/animeoncraft/atlas/*.png")
}

// Exercise Minecraft's actual NBT reader/writer used by servers.dat.
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

// Build disposable-world probes without replacing the packaged public admission JAR.
tasks.register<Jar>("lifecycleProbeJar") {
    archiveClassifier.set("lifecycle-probe")
    from(sourceSets.main.get().output)
    dependsOn(tasks.classes)
}

// Test-only entry point: use the real outbox/HTTP classes against an isolated loopback API.
tasks.register<Jar>("serverMenuClientProbeJar") {
    archiveClassifier.set("server-menu-client-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/ServerMenuClientProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocmenuclientprobe"
            version="1.0.0"
            displayName="AOC disposable server menu probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("explorationProbeJar") {
    archiveClassifier.set("exploration-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) { include("ru/animeoncraft/explorationprobe/**") }
    into("META-INF") {
        from(resources.text.fromString("""
            modLoader="javafml"
            loaderVersion="[4,)"
            license="Private test fixture"
            [[mods]]
            modId="aocexplorationprobe"
            version="1.0.0"
            displayName="AOC disposable exploration probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("betaMultiplayerProbeJar") {
    archiveClassifier.set("beta-multiplayer-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/BetaMultiplayerProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocbetamultiplayerprobe"
            version="1.0.0"
            displayName="AOC disposable multiplayer cosmetics probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("emoteMultiplayerProbeJar") {
    archiveClassifier.set("emote-multiplayer-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/EmoteMultiplayerProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocemoteprobe"
            version="1.0.0"
            displayName="AOC disposable emote multiplayer probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("socialEconomyProbeJar") {
    archiveClassifier.set("social-economy-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/SocialEconomyProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocsocialprobe"
            version="1.0.0"
            displayName="AOC disposable social and economy probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<JavaExec>("deliverOutboxFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ru.animeoncraft.integration.neoforge.OutboxHttpDeliveryFixture")
}

tasks.register<Jar>("betaPassCommandProbeJar") {
    archiveClassifier.set("beta-pass-command-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/BetaPassCommandProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocbetapasscommandprobe"
            version="1.0.0"
            displayName="AOC disposable beta pass command probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("clanMarketProbeJar") {
    archiveClassifier.set("clan-market-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/ClanMarketProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocmarketprobe"
            version="1.0.0"
            displayName="AOC disposable clan market protection probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("spawnTeleportProbeJar") {
    archiveClassifier.set("spawn-teleport-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.main.get().output) { exclude("META-INF/neoforge.mods.toml") }
    from(sourceSets.test.get().output) { include("**/SpawnTeleportProbe*.class") }
    into("META-INF") {
        from(resources.text.fromString(file("src/main/resources/META-INF/neoforge.mods.toml").readText()+"\n"+"""
            [[mods]]
            modId="aocspawnprobe"
            version="1.0.0"
            displayName="AOC disposable spawn teleport probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}

tasks.register<Jar>("adventureClientProbeJar") {
    archiveClassifier.set("adventure-client-probe")
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) { include("**/AdventureClientProbe.class") }
    into("META-INF") {
        from(resources.text.fromString("""
            modLoader="javafml"
            loaderVersion="[4,)"
            license="Private test fixture"
            [[mods]]
            modId="aocclientprobe"
            version="1.0.0"
            displayName="AOC disposable client probe"
        """.trimIndent())) { rename { "neoforge.mods.toml" } }
    }
}
