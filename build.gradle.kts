import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.NodeChild
import groovy.xml.slurpersupport.NodeChildren
import org.gradle.tooling.internal.protocol.ProjectVersion3

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version("8.1.1") apply false
    `maven-publish`
    id("com.palantir.git-version") version("3.0.0")
}

val gitVersion: groovy.lang.Closure<String> by extra

java {
    withSourcesJar()
}


fun getProjectVersionGit(): Provider<String> {
    return provider {
        try {
            gitVersion()
        } catch (_: java.lang.Exception) {
            error("Git version not found and RELEASE_VERSION environment variable is not set!")
        }
    }
}

fun getProjectVersion(): Provider<String> {
    return provider {
        System.getenv("RELEASE_VERSION")
    }.orElse(getProjectVersionGit())
}

version = getProjectVersion().get()

val jomlVersion = "1.10.1"
val lwjglVersion = "3.3.2"
val lwjglNatives = Pair(
        System.getProperty("os.name")!!,
        System.getProperty("os.arch")!!
).let { (name, arch) ->
    when {
        arrayOf("Linux", "SunOS", "Unit").any { name.startsWith(it) } ->
            if (arrayOf("arm", "aarch64").any { arch.startsWith(it) })
                "natives-linux${if (arch.contains("64") || arch.startsWith("armv8")) "-arm64" else "-arm32"}"
            else if (arch.startsWith("ppc"))
                "natives-linux-ppc64le"
            else if (arch.startsWith("riscv"))
                "natives-linux-riscv64"
            else
                "natives-linux"
        arrayOf("Mac OS X", "Darwin").any { name.startsWith(it) }     ->
            "natives-macos${if (arch.startsWith("aarch64")) "-arm64" else ""}"
        arrayOf("Windows").any { name.startsWith(it) }                ->
            if (arch.contains("64"))
                "natives-windows${if (arch.startsWith("aarch64")) "-arm64" else ""}"
            else
                "natives-windows-x86"
        else                                                                            ->
            throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
    }
}

allprojects {
    apply {
        plugin("java")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    repositories {
        mavenCentral()
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    compileOnly(platform("org.lwjgl:lwjgl-bom:${lwjglVersion}"))
    compileOnly("org.lwjgl:lwjgl")
    compileOnly("org.lwjgl:lwjgl-vulkan")
    compileOnly("org.lwjgl:lwjgl-glfw")
    compileOnly("org.lwjgl:lwjgl-vma")
    compileOnly("org.lwjgl:lwjgl-openxr")
    compileOnly("org.joml:joml:${jomlVersion}")

    testImplementation(platform("org.lwjgl:lwjgl-bom:${lwjglVersion}"))
    testImplementation("org.lwjgl:lwjgl")
    testImplementation("org.lwjgl:lwjgl-vulkan")
    testImplementation("org.lwjgl:lwjgl-glfw")
    testImplementation("org.lwjgl:lwjgl-vma")
    testImplementation("org.lwjgl:lwjgl-openxr")
    testImplementation("org.joml:joml:${jomlVersion}")

    testRuntimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-vma::$lwjglNatives")
    if (lwjglNatives == "natives-macos" || lwjglNatives == "natives-macos-arm64") testRuntimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglNatives")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}


project(":samples") {
    apply {
        plugin("com.github.johnrengelman.shadow")
    }
    dependencies {
        implementation(project(":"))

        implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
        implementation("org.lwjgl:lwjgl")
        implementation("org.lwjgl:lwjgl-vma")
        implementation("org.lwjgl:lwjgl-vulkan")
        implementation("org.lwjgl:lwjgl-glfw")
        implementation("org.lwjgl:lwjgl-openxr")
        implementation("org.joml:joml:${jomlVersion}")

        runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
        runtimeOnly("org.lwjgl:lwjgl-vma::$lwjglNatives")
        if (lwjglNatives != "natives-macos" && lwjglNatives != "natives-macos-arm64") runtimeOnly("org.lwjgl:lwjgl-openxr::$lwjglNatives")
        if (lwjglNatives == "natives-macos" || lwjglNatives == "natives-macos-arm64") runtimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglNatives")
    }
    tasks {
        named<ShadowJar>("shadowJar") {
            manifest {
                attributes(mapOf("Main-Class" to "com.github.knokko.boiler.samples.TerrainPlayground"))
            }
        }
    }
}


data class Credentials(val username: String?, val password: String?)

fun getMavenCredentialsFromEnv(): Credentials? {
    val username = System.getenv("MAVEN_DEPLOY_USER")
    val password = System.getenv("MAVEN_DEPLOY_PASSWORD")
    if (username == null || password == null) {
        return null
    } else {
        return Credentials(username, password)
    }
}

group = "com.falsepattern"

publishing {
    publications {
        create<MavenPublication>("vk-boiler") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "mvnpattern"
            url = uri("https://mvn.falsepattern.com/releases/")
            val creds = getMavenCredentialsFromEnv()
            if (creds != null) {
                credentials {
                    username = creds.username
                    password = creds.password
                }
            } else {
                credentials(PasswordCredentials::class)
            }
        }
    }
}