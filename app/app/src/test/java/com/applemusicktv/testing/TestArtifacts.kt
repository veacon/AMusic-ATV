package com.applemusicktv.testing

import java.io.File

/** Gradle supplies a writable module-local path on developer machines and CI. */
object TestArtifacts {
    val root: File get() = File(requireNotNull(System.getProperty("amtv.test.artifacts")) {
        "Run tests through the Android Gradle test task to configure the artifact directory"
    }).apply { mkdirs() }
}
