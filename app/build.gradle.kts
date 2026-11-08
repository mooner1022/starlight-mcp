import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("io.github.mooner1022.slplugin")
}

android {
    namespace = "dev.mooner.starlightmcp"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mooner.starlightmcp"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        // The plugin version in the generated starlight.json.
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Tests have Ktor and SLF4J without the Ktor plugin's provider.
            test.systemProperty("slf4j.provider", "org.slf4j.helpers.NOP_FallbackServiceProvider")
            // Lets the test client send headers such as Origin.
            test.systemProperty("sun.net.http.allowRestrictedHeaders", "true")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    // Generates assets/starlight.json; the version is versionName.
    manifest {
        id = "mcp"
        name = "MCP Server"
        mainClass = "dev.mooner.starlightmcp.StarlightMcpPlugin"
        // Worked out from the code, it would be 1.0.1, the oldest release PluginCore's API table records; the code
        // needs nothing newer than 1.0.0, so StarLight 1.0.0 keeps loading the plugin. slplugin checks it either way.
        apiVersion = "1.0.0"
        authors.add("mooner")
        description = "Model Context Protocol server exposing StarLight and tools defined by projects"
        category = "devtools"
        // StarLight checks only major.minor: KtorServers (3.6.2) is checked in code.
        dependency("ktor", "3.6.0")
        dependency("workspace", "1.0.0")
    }
    deploy {
        // Pass -Pstarlight.device=<serial or ip:port> or set STARLIGHT_DEVICE.
        // Restarting force-stops StarLight on the device; opt in per run with -Pstarlight.restartApp=true.
    }
}

val ktorVersion = "3.6.0"

dependencies {
    // Provided by StarLight at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly("androidx.core:core-ktx:1.16.0")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("com.google.android.material:material:1.12.0")
    // StarLight's NeonJS engine: handlers' JS values and promises arrive as NeonValues.
    compileOnly("dev.mooner.neonjs:neonjs-core:0.1.7")
    // Provided by the Ktor plugin (dependency "ktor@3.6.0"); keep the versions equal.
    compileOnly("io.ktor:ktor-server-cio:$ktorVersion")
    // KtorServers, from the Ktor plugin's apiJar output.
    compileOnly(files("libs/ktor-api-3.6.2.jar"))
    // Provided by the Workspace plugin (dependency "workspace@1.0.0"); the jar is its apiJar output.
    compileOnly(files("libs/workspace-api-1.0.0.jar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("dev.mooner.neonjs:neonjs-core:0.1.7")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")
    testImplementation(files("libs/ktor-api-3.6.2.jar"))
    // Checks tools written in Rhino, StarLight's legacy engine.
    testImplementation("org.mozilla:rhino:1.7.15")
    testImplementation(files("libs/workspace-api-1.0.0.jar"))
}
