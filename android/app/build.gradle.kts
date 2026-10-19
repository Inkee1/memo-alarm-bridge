plugins { id("com.android.application"); kotlin("android") }
android {
 namespace="io.github.inkee1.memoalarmbridge"
 compileSdk=36
 defaultConfig {applicationId="io.github.inkee1.memoalarmbridge";minSdk=31;targetSdk=36;versionCode=1;versionName="0.1.0-dev";testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"}
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlin {compilerOptions {jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)}}
 buildTypes {getByName("release") {isMinifyEnabled=false}}
}
dependencies {
 implementation(project(":core"))
 implementation("com.google.code.gson:gson:2.13.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
