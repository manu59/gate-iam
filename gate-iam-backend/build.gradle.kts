plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

dependencies {
    implementation(project(":gate-iam-domain"))
    implementation(project(":gate-iam-application"))
    implementation(project(":gate-iam-infrastructure"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<Test>("test") {
    // If DOCKER_HOST is already set (e.g. Colima, OrbStack, remote daemon), honour it — no override.
    // Otherwise, auto-detect Rancher Desktop's containerd socket (~/.rd/docker.sock) and disable Ryuk:
    // containerd does not support the privileged bind-mount that Ryuk requires.
    if (System.getenv("DOCKER_HOST") == null) {
        val rancherSocket = file("${System.getProperty("user.home")}/.rd/docker.sock")
        if (rancherSocket.exists()) {
            environment("DOCKER_HOST", "unix://${rancherSocket.absolutePath}")
            environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        }
    }
}
