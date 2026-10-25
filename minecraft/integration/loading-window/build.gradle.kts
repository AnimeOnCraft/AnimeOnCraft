plugins { `java-library` }
version = "1.0.0"
base { archivesName.set("animeoncraft-loading") }
repositories { maven("https://maven.neoforged.net/releases") }
dependencies {
    compileOnly("net.neoforged.fancymodloader:earlydisplay:4.0.45") { isTransitive = false }
    compileOnly("net.neoforged.fancymodloader:loader:4.0.45") { isTransitive = false }
    for (library in listOf("lwjgl", "lwjgl-opengl", "lwjgl-glfw", "lwjgl-stb")) {
        compileOnly("org.lwjgl:$library:3.3.3") { isTransitive = false }
    }
}
tasks.jar {
    manifest.attributes("Automatic-Module-Name" to "ru.animeoncraft.loading", "FMLModType" to "LIBRARY")
}
