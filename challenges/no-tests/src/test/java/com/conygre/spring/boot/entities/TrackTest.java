package com.conygre.spring.boot.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Track} entity.
 *
 * <p>Track has three constructors with different field coverage, which is
 * exactly the kind of thing worth pinning down with tests: it is very easy
 * for a caller to reach for the wrong overload and end up with an entity
 * that is missing its cd_id (a NOT NULL column per sql/createTables.sql).
 */
class TrackTest {

    @Test
    @DisplayName("the 3-arg constructor sets id, title and cdId")
    void fullConstructor_setsAllFields() {
        // Baseline check that the 3-arg constructor maps each parameter to
        // the correct field (id, title, cdId, in that declared order).
        Track track = new Track(1, "Mama", 16);

        assertEquals(1, track.getId());
        assertEquals("Mama", track.getTitle());
        assertEquals(16, track.getCdId());
    }

    @Test
    @DisplayName("the title-only constructor leaves id null and cdId at its default of 0")
    void titleOnlyConstructor_leavesIdNullAndCdIdZero() {
        // Why this test exists: Track(String title) is a convenience
        // constructor, but the tracks table has "cd_id int not null" with a
        // foreign key to compact_discs(id) (see sql/createTables.sql). A
        // Track built with just this constructor has cdId defaulted to 0
        // and id left null - if such a track were persisted as-is without
        // the caller separately calling setCdId(...), it would violate the
        // NOT NULL/foreign-key constraint at the database level. This test
        // documents that the entity itself does nothing to prevent that -
        // there is no validation - so callers must remember to set cdId
        // themselves.
        Track track = new Track("Wannabe");

        assertEquals("Wannabe", track.getTitle());
        assertNull(track.getId());
        assertEquals(0, track.getCdId());
    }

    @Test
    @DisplayName("the no-arg constructor leaves every field at its Java default")
    void defaultConstructor_allFieldsAtJavaDefaults() {
        // Baseline check of the no-arg constructor's initial state.
        Track track = new Track();

        assertNull(track.getId());
        assertNull(track.getTitle());
        assertEquals(0, track.getCdId());
    }

    @Test
    @DisplayName("getters/setters round-trip correctly, including id going from null to a real value")
    void gettersAndSetters_roundTrip() {
        // Baseline check that every getter returns exactly what its matching
        // setter was given.
        Track track = new Track();
        track.setId(5);
        track.setTitle("Spice Up Your Life");
        track.setCdId(16);

        assertEquals(5, track.getId());
        assertEquals("Spice Up Your Life", track.getTitle());
        assertEquals(16, track.getCdId());
    }
}
