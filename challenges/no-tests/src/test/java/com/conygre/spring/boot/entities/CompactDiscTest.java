package com.conygre.spring.boot.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link CompactDisc} entity itself.
 *
 * <p>There is very little "logic" in this class (no equals/hashCode, no
 * validation annotations), but the parameterised constructor's argument
 * order is genuinely easy to get wrong when reading the class, so it gets a
 * dedicated test. The default-initialised trackTitles list is also worth
 * pinning down since it prevents a very common NullPointerException.
 */
class CompactDiscTest {

    @Test
    @DisplayName("the 4-arg constructor maps parameters in the DECLARED order (title, price, artist, tracks) - not the more intuitive (title, artist, price, tracks)")
    void constructor_mapsParametersInActualDeclaredOrder() {
        // Why this test exists: the constructor signature is
        // "CompactDisc(String t, double p, String a, int tr)" - price comes
        // BEFORE artist. That is an easy trap: a caller (or a future
        // maintainer skimming the field declaration order title/artist/
        // price/tracks) could plausibly assume the constructor takes
        // (title, artist, price, tracks) and silently swap artist and price
        // when constructing a disc. This test locks in the real, current
        // mapping so such a mistake fails loudly.
        CompactDisc disc = new CompactDisc("Is This It", 13.99, "The Strokes", 11);

        assertEquals("Is This It", disc.getTitle());
        assertEquals(13.99, disc.getPrice());
        assertEquals("The Strokes", disc.getArtist());
        assertEquals(11, disc.getTracks());
    }

    @Test
    @DisplayName("a newly constructed CompactDisc has a non-null, empty trackTitles list")
    void defaultTrackTitles_isEmptyListNotNull() {
        // Why this test exists: trackTitles is field-initialised to
        // "new ArrayList<Track>()" rather than left null. Callers (and
        // JSON serialisation of a brand new disc before tracks are added)
        // can therefore safely call getTrackTitles().size()/iterate without
        // a null check. If someone changed the field initialiser to remove
        // the default, this test catches the resulting NPE risk.
        CompactDisc disc = new CompactDisc();

        assertNotNull(disc.getTrackTitles());
        assertTrue(disc.getTrackTitles().isEmpty());
    }

    @Test
    @DisplayName("all getters/setters round-trip the values passed to them")
    void gettersAndSetters_roundTrip() {
        CompactDisc disc = new CompactDisc();
        disc.setId(7);
        disc.setTitle("White Ladder");
        disc.setArtist("David Gray");
        disc.setPrice(9.99);
        disc.setTracks(10);
        Track track = new Track("This Year's Love");
        disc.setTrackTitles(new ArrayList<>(Collections.singletonList(track)));

        assertEquals(7, disc.getId());
        assertEquals("White Ladder", disc.getTitle());
        assertEquals("David Gray", disc.getArtist());
        assertEquals(9.99, disc.getPrice());
        assertEquals(10, disc.getTracks());
        assertEquals(1, disc.getTrackTitles().size());
        assertSame(track, disc.getTrackTitles().get(0));
    }

    @Test
    @DisplayName("price and tracks are boxed (Double/Integer) and default to null when never set, not 0.0/0")
    void unsetBoxedFields_defaultToNullNotZero() {
        // Why this test exists: price is a Double and tracks is an Integer
        // (boxed types, not primitive double/int) on the entity, even
        // though the constructor takes primitive double/int. That means a
        // CompactDisc built via the no-arg constructor and never given a
        // price/tracks has getPrice()/getTracks() returning null, which
        // matters for JSON serialisation (field omitted/"null" rather than
        // "0") and for any code that unboxes these values without a null
        // check risking a NullPointerException.
        CompactDisc disc = new CompactDisc();

        assertNull(disc.getPrice());
        assertNull(disc.getTracks());
    }
}
