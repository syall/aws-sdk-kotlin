/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
import aws.sdk.kotlin.gradle.dsl.configurePublishing
import aws.sdk.kotlin.gradle.kmp.*
import aws.sdk.kotlin.gradle.util.typedProp
import java.time.LocalDateTime

plugins {
    `maven-publish`
    @Suppress("DSL_SCOPE_VIOLATION") // TODO: Remove once https://youtrack.jetbrains.com/issue/KTIJ-19369 is fixed
    alias(libs.plugins.dokka)
}

val sdkVersion: String by project

val optinAnnotations = listOf(
    "aws.smithy.kotlin.runtime.InternalApi",
    "aws.sdk.kotlin.runtime.InternalSdkApi",
    "kotlin.RequiresOptIn",
)

// capture locally - scope issue with custom KMP plugin
val libraries = libs

subprojects {
    group = "aws.sdk.kotlin"
    version = sdkVersion

    apply {
        plugin("org.jetbrains.kotlin.multiplatform")
        plugin("org.jetbrains.dokka")
    }

    logger.info("configuring: $project")

    kotlin {
        explicitApi()

        sourceSets {
            all {
                // have generated sdk's opt-in to internal runtime features
                optinAnnotations.forEach { languageSettings.optIn(it) }
            }

            getByName("commonMain") {
                kotlin.srcDir("generated-src/main/kotlin")
            }

            getByName("commonTest") {
                kotlin.srcDir("generated-src/test")

                dependencies {
                    implementation(libraries.kotlinx.coroutines.test)
                }
            }
        }

        if (project.file("e2eTest").exists()) {
            jvm().compilations {
                val e2eTest by creating {
                    defaultSourceSet {
                        kotlin.srcDir("e2eTest/src")
                        resources.srcDir("e2eTest/test-resources")
                        dependsOn(sourceSets.getByName("commonMain"))
                        dependsOn(sourceSets.getByName("jvmMain"))

                        dependencies {
                            api(libraries.smithy.kotlin.testing)
                            implementation(libraries.kotlin.test)
                            implementation(libraries.kotlin.test.junit5)
                            implementation(project(":tests:e2e-test-util"))
                            implementation(libraries.slf4j.simple)
                        }
                    }

                    kotlinOptions {
                        // Enable coroutine runTests in 1.6.10
                        // NOTE: may be removed after coroutines-test runTests becomes stable
                        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
                    }

                    tasks.register<Test>("e2eTest") {
                        description = "Run e2e service tests"
                        group = "verification"

                        // Run the tests with the classpath containing the compile dependencies (including 'main'),
                        // runtime dependencies, and the outputs of this compilation:
                        classpath = compileDependencyFiles + runtimeDependencyFiles + output.allOutputs

                        // Run only the tests from this compilation's outputs:
                        testClassesDirs = output.classesDirs

                        useJUnitPlatform()
                        testLogging {
                            events("passed", "skipped", "failed")
                            showStandardStreams = true
                            showStackTraces = true
                            showExceptions = true
                            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                        }

                        // model a random input to enable re-running e2e tests back to back without
                        // up-to-date checks or cache getting in the way
                        inputs.property("integration.datetime", LocalDateTime.now())
                        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", "WARN"))
                    }
                }
            }
        }
    }

    dependencies {
        dokkaPlugin(project(":dokka-aws"))
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            allWarningsAsErrors = false // FIXME Tons of errors occur in generated code
            jvmTarget = "1.8" // fixes outgoing variant metadata: https://github.com/awslabs/smithy-kotlin/issues/258
        }
    }

    configurePublishing("aws-sdk-kotlin")
    publishing {
        publications.all {
            if (this !is MavenPublication) return@all
            project.afterEvaluate {
                val sdkId = project.typedProp<String>("aws.sdk.id") ?: error("service build `${project.name}` is missing `aws.sdk.id` property required for publishing")
                pom.properties.put("aws.sdk.id", sdkId)
            }
        }
    }
}
