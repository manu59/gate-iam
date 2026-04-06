plugins {
    java
    // org.sonarqube plugin is declared but the `sonar` task is not used in CI yet.
    // SonarCloud Automatic Analysis is active. Re-enable ./gradlew sonar in ci.yml
    // once JaCoCo is configured to provide coverage data.
    alias(libs.plugins.sonarqube)
}

sonar {
    properties {
        property("sonar.projectKey", "manu59_gate-iam")
        property("sonar.organization", "Kinto")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core:3.27.3")
        "testImplementation"("org.mockito:mockito-core:5.23.0")
        "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.1")
        "testImplementation"("org.awaitility:awaitility:4.2.2")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        }
    }
}
