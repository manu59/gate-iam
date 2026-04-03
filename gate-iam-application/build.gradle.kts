plugins {
    java
}

dependencies {
    implementation(project(":gate-iam-domain"))
    // Common test deps (JUnit, AssertJ, Mockito, ArchUnit, Awaitility) come from root build.gradle.kts
}
