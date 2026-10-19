plugins { kotlin("jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation("com.google.code.gson:gson:2.13.1"); testImplementation("junit:junit:4.13.2") }
tasks.test { systemProperty("fixtures.dir", rootProject.projectDir.parentFile.resolve("protocol/fixtures").absolutePath) }
dependencyLocking {lockAllConfigurations()}
