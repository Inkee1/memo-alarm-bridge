import java.io.File
import java.security.MessageDigest
import groovy.json.JsonOutput

plugins { id("com.android.application"); kotlin("android") }
val developmentKey=layout.buildDirectory.file("debug-signing/debug.keystore")
val generateDevelopmentKey=tasks.register<Exec>("generateDevelopmentKey") {
 outputs.file(developmentKey)
 onlyIf { !developmentKey.get().asFile.exists() }
 doFirst { developmentKey.get().asFile.parentFile.mkdirs() }
 commandLine(File(System.getProperty("java.home"),"bin/keytool").absolutePath,"-genkeypair","-keystore",developmentKey.get().asFile.absolutePath,"-storetype","JKS","-storepass","android","-keypass","android","-alias","androiddebugkey","-dname","CN=Android Debug,O=MemoAlarm Bridge,C=US","-keyalg","RSA","-keysize","2048","-validity","365")
}
tasks.matching { it.name=="validateSigningDebug" }.configureEach { dependsOn(generateDevelopmentKey) }
val generatedNotices=layout.buildDirectory.dir("generated/notices-assets")
val copyNotices=tasks.register<Sync>("copyThirdPartyNotices") {
 from(rootProject.file("../THIRD_PARTY_NOTICES.md"))
 from(rootProject.file("../LICENSES")) { into("LICENSES") }
 into(generatedNotices)
}
tasks.matching { it.name=="preBuild" }.configureEach { dependsOn(copyNotices) }
android {
 namespace="io.github.inkee1.memoalarmbridge"
 compileSdk=36
 defaultConfig {applicationId="io.github.inkee1.memoalarmbridge";minSdk=31;targetSdk=36;versionCode=1;versionName="0.1.0-dev";testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"}
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)}}
 buildTypes {getByName("release") {isMinifyEnabled=false}}
 signingConfigs {getByName("debug"){storeFile=developmentKey.get().asFile;storePassword="android";keyAlias="androiddebugkey";keyPassword="android"}}
 sourceSets.getByName("main").assets.srcDir(generatedNotices)
}
dependencies {
 implementation(project(":core"))
 implementation("com.google.code.gson:gson:2.13.1")
 implementation("androidx.work:work-runtime:2.10.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
dependencyLocking {lockAllConfigurations()}
tasks.register("runtimeDependencyInventory") {
    doLast {
        val entries=configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val id=artifact.moduleVersion.id
            val digest=MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString(""){"%02x".format(it)}
            mapOf("group" to id.group,"name" to id.name,"version" to id.version,"artifact" to artifact.file.name,"sha256" to digest)
        }.sortedBy {it.getValue("group")+":"+it.getValue("name")}
        val target=layout.buildDirectory.file("reports/runtime-dependencies.json").get().asFile
        target.parentFile.mkdirs();target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entries)))
        println("Runtime dependency inventory: ${entries.size} resolved artifacts")
    }
}
