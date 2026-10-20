package com.example

import com.example.util.PersianDownloadBrowserLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RepositoryHygieneTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun `applicationId and Persian Unicode escapes are intact and free of mojibake`() {
        assertEquals("io.github.ahmadzmvd.catchapp", BuildConfig.APPLICATION_ID)

        val suffix = PersianDownloadBrowserLauncher.PERSIAN_DOWNLOAD_SUFFIX
        assertEquals(6, suffix.length)
        assertEquals(
            listOf(0x062F, 0x0627, 0x0646, 0x0644, 0x0648, 0x062F),
            suffix.map { it.code }
        )
        assertFalse("Suffix must not contain UTF-8 replacement character", suffix.contains('\uFFFD'))

        val url = PersianDownloadBrowserLauncher.buildGoogleDownloadSearchUrl("Midnight City M83")
        assertTrue(
            "URL must contain UTF-8 percent-encoded Persian download suffix",
            url.endsWith("+%D8%AF%D8%A7%D9%86%D9%84%D9%88%D8%AF")
        )
    }

    @Test
    fun `repository contains GitHub workflows and zero legacy or sandbox template files`() {
        val root = resolveProjectRoot()

        val ciWorkflow = File(root, ".github/workflows/ci.yml")
        val releaseWorkflow = File(root, ".github/workflows/release.yml")
        assertTrue("Missing .github/workflows/ci.yml", ciWorkflow.isFile && ciWorkflow.length() > 100)
        assertTrue("Missing .github/workflows/release.yml", releaseWorkflow.isFile && releaseWorkflow.length() > 100)

        val forbiddenPaths = listOf(
            ".env.example",
            "app/src/main/java/com/example/util/OneUiPermissionHelper.kt",
            "app/src/main/java/com/example/CatchSongApplication.kt",
            "app/src/main/java/com/example/ui/CatchSongViewModel.kt",
            "app/src/test/java/com/example/ExampleUnitTest.kt",
            "app/src/test/java/com/example/ExampleRobolectricTest.kt",
            "app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt"
        )
        for (relativePath in forbiddenPaths) {
            assertFalse(
                "Forbidden legacy/template file still exists: $relativePath",
                File(root, relativePath).exists()
            )
        }
    }

    @Test
    fun `all Kotlin source and test files are pure 7-bit ASCII with Unicode escapes to prevent encoding corruption`() {
        val root = resolveProjectRoot()
        val kotlinDirs = listOf(
            File(root, "app/src/main/java"),
            File(root, "app/src/test/java")
        )

        for (dir in kotlinDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val content = file.readText(Charsets.UTF_8)
                    val nonAsciiIndex = content.indexOfFirst { it.code > 0x7F }
                    assertEquals(
                        "File ${file.relativeTo(root)} contains raw non-ASCII character at index $nonAsciiIndex; use \\uXXXX escapes",
                        -1,
                        nonAsciiIndex
                    )
                }
        }
    }
}
