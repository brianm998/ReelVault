// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameDateInferenceTest {
    private fun infer(name: String, fmt: String = "YYYY-MM-DD", pos: String = "anywhere") =
        FilenameDateInference.inferDate(name, fmt, pos)

    @Test fun parsesIsoDate() =
        assertEquals(LocalDate.of(2024, 4, 24), infer("clip_2024-04-24_a.mov"))

    @Test fun parsesMonthDayYear() =
        assertEquals(LocalDate.of(2024, 4, 24), infer("04-24-2024.mp4", "MM-DD-YYYY"))

    @Test fun parsesDayMonthYear() =
        assertEquals(LocalDate.of(2024, 4, 24), infer("24.04.2024 trip.mp4", "DD-MM-YYYY"))

    @Test fun beginningPositionRequiresPrefix() {
        assertNull(infer("x2024-04-24.mov", pos = "beginning"))
        assertEquals(LocalDate.of(2024, 4, 24), infer("2024-04-24 x.mov", pos = "beginning"))
    }

    @Test fun endPositionIgnoresExtension() {
        assertEquals(LocalDate.of(2024, 4, 24), infer("x 2024-04-24.mov", pos = "end"))
        assertNull(infer("2024-04-24 x.mov", pos = "end"))
    }

    @Test fun rejectsOutOfRangeComponents() {
        assertNull(infer("2024-13-01.mov"))
        assertNull(infer("2024-00-10.mov"))
        assertNull(infer("1800-01-01.mov"))
    }

    @Test fun rejectsImpossibleCalendarDate() = assertNull(infer("2023-02-30.mov"))

    @Test fun unknownFormatReturnsNull() = assertNull(infer("2024-04-24.mov", "bogus"))

    @Test fun labelsFallBackToRawValue() {
        assertEquals("zzz", FilenameDateInference.formatLabel("zzz"))
        assertEquals("At the start of the name", FilenameDateInference.positionLabel("beginning"))
    }
}
