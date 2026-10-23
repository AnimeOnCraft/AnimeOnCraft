plugins { base }

allprojects {
    group = "ru.animeoncraft.integration"
    version = "0.2.0-skeleton"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(if (project.name == "neoforge") 21 else 17)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped") }
    }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.0.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:6.0.2")
    }
}
