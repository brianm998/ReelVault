// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFilterTest {
    @Test fun negatePrefixIsRecordSeparator() {
        assertEquals("\u001E", METADATA_NEGATE_PREFIX)
        assertEquals(1, METADATA_NEGATE_PREFIX.length)
    }

    @Test fun valueSeparatorIsUnitSeparator() {
        assertEquals("\u001F", METADATA_VALUE_SEPARATOR)
    }

    @Test fun plainColumnIsNotPrefixed() {
        val w = MetadataColumn("camera", setOf("A")).toWireValue()
        assertEquals("A", w)
        assertFalse(w.startsWith(METADATA_NEGATE_PREFIX))
    }

    @Test fun negatedColumnIsPrefixed() {
        val w = MetadataColumn("camera", setOf("A"), negate = true).toWireValue()
        assertEquals(METADATA_NEGATE_PREFIX + "A", w)
    }

    @Test fun multipleValuesJoinedBySeparator() {
        val w = MetadataColumn("iso", linkedSetOf("100", "200")).toWireValue()
        assertEquals("100\u001F200", w)
    }

    @Test fun negatedMultiValue() {
        val w = MetadataColumn("iso", linkedSetOf("100", "200"), negate = true).toWireValue()
        assertEquals("\u001E100\u001F200", w)
    }

    @Test fun smartColumnEncodingMatchesMetadataColumn() {
        assertEquals(
            MetadataColumn("x", linkedSetOf("a", "b"), negate = true).toWireValue(),
            SmartCollectionColumn("x", listOf("a", "b"), negate = true).toWireValue(),
        )
        assertEquals("a", SmartCollectionColumn("x", listOf("a")).toWireValue())
    }

    @Test fun derivedFiltersEmptyWhenAny() {
        assertTrue(derivedAttributeMetadataFilters(AttributeFilterState.Any, OrientationFilterState.Any).isEmpty())
    }

    @Test fun derivedAudioYesNo() {
        assertEquals(
            listOf(MetadataFilter("has_audio", "yes")),
            derivedAttributeMetadataFilters(AttributeFilterState.Yes, OrientationFilterState.Any),
        )
        assertEquals(
            listOf(MetadataFilter("has_audio", "no")),
            derivedAttributeMetadataFilters(AttributeFilterState.No, OrientationFilterState.Any),
        )
    }

    @Test fun derivedOrientation() {
        assertEquals(
            listOf(MetadataFilter("orientation", "portrait")),
            derivedAttributeMetadataFilters(AttributeFilterState.Any, OrientationFilterState.Portrait),
        )
        assertEquals(
            listOf(MetadataFilter("orientation", "landscape")),
            derivedAttributeMetadataFilters(AttributeFilterState.Any, OrientationFilterState.Landscape),
        )
    }

    @Test fun derivedBothInOrder() {
        val f = derivedAttributeMetadataFilters(AttributeFilterState.Yes, OrientationFilterState.Landscape)
        assertEquals(listOf("has_audio", "orientation"), f.map { it.key })
    }
}
