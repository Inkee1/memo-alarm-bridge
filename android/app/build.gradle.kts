import java.io.File

plugins { id("com.android.application"); kotlin("android") }
val developmentKey=layout.buildDirectory.file("debug-signing/debug.keystore")
val generateDevelopmentKey=tasks.register<Exec>("generateDevelopmentKey") {
 outputs.file(developmentKey)
 onlyIf { !developmentKey.get().asFile.exists() }
 doFirst { developmentKey.get().asFile.parentFile.mkdirs() }
 commandLine(File(System.getProperty("java.home"),"bin/keytool").absolutePath,"-genkeypair","-keystore",developmentKey.get().asFile.absolutePath,"-storetype","JKS","-storepass","android","-keypass","android","-alias","androiddebugkey","-dname","CN=Android Debug,O=MemoAlarm Bridge,C=US","-keyalg","RSA","-keysize","2048","-validity","365")
}
tasks.matching { it.name=="validateSigningDebug" }.configureEach { dependsOn(generateDevelopmentKey) }
android {
 namespace="io.github.inkee1.memoalarmbridge"
 compileSdk=36
 defaultConfig {applicationId="io.github.inkee1.memoalarmbridge";minSdk=31;targetSdk=36;versionCode=1;versionName="0.1.0-dev";testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"}
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)}}
 buildTypes {getByName("release") {isMinifyEnabled=false}}
 signingConfigs {getByName("debug"){storeFile=developmentKey.get().asFile;storePassword="android";keyAlias="androiddebugkey";keyPassword="android"}}
}
dependencies {
 implementation(project(":core"))
 implementation("com.google.code.gson:gson:2.13.1")
 implementation("androidx.work:work-runtime:2.10.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
