// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PathCompletionTest {
    private lateinit var root: File
    private val base get() = root.absolutePath.trimEnd('/')

    @Before fun setUp() {
        root = Files.createTempDirectory("pathcompletion").toFile()
        File(root, "Videos").mkdir()
        File(root, "vacation.txt").writeText("x")
        File(root, "Music").mkdir()
        File(root, ".hidden").mkdir()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun emptyInputHasNoMatches() {
        val r = PathCompletion.complete("")
        assertEquals(emptyList<String>(), r.matches)
        assertNull(r.bestMatch)
    }

    @Test fun prefixMatchIsCaseSensitiveAndMarksDirectories() {
        val r = PathCompletion.complete("$base/V")
        assertEquals(listOf("$base/Videos/"), r.matches)
        assertEquals("$base/Videos/", r.bestMatch)
    }

    @Test fun trailingSlashListsChildrenWithoutDotfiles() {
        val r = PathCompletion.complete("$base/")
        assertEquals(listOf("$base/Music/", "$base/vacation.txt", "$base/Videos/"), r.matches)
    }

    @Test fun dotPrefixIncludesDotfiles() =
        assertEquals(listOf("$base/.hidden/"), PathCompletion.complete("$base/.h").matches)

    @Test fun nonexistentParentYieldsNothing() =
        assertNull(PathCompletion.complete("$base/nope/x").bestMatch)

    @Test fun displayNameKeepsTrailingSlash() {
        assertEquals("Downloads/", PathCompletion.displayName("/Users/b/Downloads/"))
        assertEquals("a.mov", PathCompletion.displayName("/x/a.mov"))
    }

    @Test fun longestCommonPrefix() {
        assertEquals("/a/ab", PathCompletion.longestCommonPrefix(listOf("/a/abc", "/a/abd", "/a/ab")))
        assertEquals("", PathCompletion.longestCommonPrefix(listOf("x", "y")))
        assertEquals("", PathCompletion.longestCommonPrefix(emptyList()))
    }
}
