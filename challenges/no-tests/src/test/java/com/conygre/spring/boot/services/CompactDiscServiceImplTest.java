package com.conygre.spring.boot.services;

import com.conygre.spring.boot.entities.CompactDisc;
import com.conygre.spring.boot.repos.CompactDiscRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CompactDiscServiceImpl}.
 *
 * <p>Scope: these are pure unit tests. {@link CompactDiscRepository} is
 * replaced with a Mockito mock so no Spring context and no database is
 * involved - only the logic actually written in
 * {@code CompactDiscServiceImpl} is exercised.
 *
 * <p>Mocking approach: mocks are created manually with
 * {@code Mockito.mock(...)} and injected via
 * {@link ReflectionTestUtils#setField} rather than using
 * {@code @ExtendWith(MockitoExtension.class)} + {@code @Mock}. This project's
 * pom.xml deliberately pins {@code mockito-core} to version 2.22.0, but
 * {@code spring-boot-starter-test} 2.5.3 pulls in {@code mockito-junit-jupiter}
 * at version 3.9.0 transitively - a mismatched pair that can throw
 * {@code NoSuchMethodError} at runtime if the JUnit 5 Mockito extension is
 * used. Manual mocking sidesteps that mismatched dependency entirely.
 *
 * <p>The service under test has no constructor/setter for its
 * {@code CompactDiscRepository dao} field (it relies solely on
 * {@code @Autowired} field injection), so {@code ReflectionTestUtils} is used
 * to set the mock directly onto the field - the standard Spring-test-provided
 * way of injecting into field-injected collaborators in a plain unit test.
 */
class CompactDiscServiceImplTest {

    private CompactDiscRepository repository;
    private CompactDiscServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(CompactDiscRepository.class);
        service = new CompactDiscServiceImpl();
        ReflectionTestUtils.setField(service, "dao", repository);
    }

    // ------------------------------------------------------------------
    // getCatalog()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getCatalog() delegates to repository.findAll() and returns exactly what it returns")
    void getCatalog_delegatesToRepositoryFindAll() {
        // Why this test exists: getCatalog() is a thin pass-through to
        // dao.findAll(). We verify the service does not filter, transform,
        // or copy the data - callers must be able to trust that the catalog
        // they get back is exactly the repository's data.
        CompactDisc cd1 = new CompactDisc("Is This It", 13.99, "The Strokes", 11);
        CompactDisc cd2 = new CompactDisc("Parachutes", 11.99, "Coldplay", 10);
        List<CompactDisc> discs = Arrays.asList(cd1, cd2);
        when(repository.findAll()).thenReturn(discs);

        Iterable<CompactDisc> result = service.getCatalog();

        assertSame(discs, result, "service should return the exact Iterable produced by the repository");
        verify(repository, times(1)).findAll();
    }

    @Test
    @DisplayName("getCatalog() returns an empty (not null) iterable when there are no discs")
    void getCatalog_whenNoDiscsExist_returnsEmptyIterable() {
        // Why this test exists: an empty catalog is a normal, expected state
        // (e.g. a freshly provisioned database) and must not be confused
        // with an error. The service must not, for example, wrap an empty
        // result in null or throw - callers can safely iterate zero times.
        when(repository.findAll()).thenReturn(new ArrayList<>());

        Iterable<CompactDisc> result = service.getCatalog();

        assertNotNull(result);
        assertFalse(result.iterator().hasNext(), "expected no elements in an empty catalog");
    }

    // ------------------------------------------------------------------
    // getCompactDiscById(int)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getCompactDiscById() returns the disc when the repository finds it")
    void getCompactDiscById_whenPresent_returnsDisc() {
        // Baseline happy-path check: a repository hit is unwrapped from its
        // Optional and returned as-is.
        CompactDisc cd = new CompactDisc("Mezzanine", 12.99, "Massive Attack", 11);
        cd.setId(15);
        when(repository.findById(15)).thenReturn(Optional.of(cd));

        CompactDisc result = service.getCompactDiscById(15);

        assertSame(cd, result);
    }

    @Test
    @DisplayName("getCompactDiscById() returns null (not an exception) when the id does not exist")
    void getCompactDiscById_whenAbsent_returnsNull() {
        // Why this test exists: this is a real, deliberate branch in the
        // production code (the if/else on discOptional.isPresent()). Unlike
        // many Spring apps that throw on a missing id, this service
        // silently returns null. Callers (notably the /404 endpoint in the
        // controller) depend on this exact contract. Locking it down with a
        // test protects against someone "fixing" it into an exception (or
        // an unwrapped Optional.get()) without realising the controller
        // layer relies on the null.
        when(repository.findById(999)).thenReturn(Optional.empty());

        CompactDisc result = service.getCompactDiscById(999);

        assertNull(result);
    }

    @ParameterizedTest(name = "id={0}")
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    @DisplayName("getCompactDiscById() has no special-case handling for boundary/invalid ids - it always defers to the repository")
    void getCompactDiscById_boundaryIds_areNotSpeciallyHandled(int id) {
        // Why this test exists: the service performs no validation on the id
        // parameter (no check for id <= 0, no range check). This test
        // documents that zero, negative and maximum-int ids are all passed
        // straight through to the repository and simply come back null when
        // not found, rather than raising an IllegalArgumentException. If
        // validation is ever added, this test will need to change - which
        // is exactly the point of having it.
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertNull(service.getCompactDiscById(id));
        verify(repository).findById(id);
    }

    // ------------------------------------------------------------------
    // addNewCompactDisc(CompactDisc)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addNewCompactDisc() forces the id to 0 before saving, even if the caller supplied a non-zero id")
    void addNewCompactDisc_alwaysResetsIdToZeroBeforeSaving() {
        // Why this test exists: this is the single most important edge case
        // in the service. The production code contains the line
        // "disc.setId(0); // assume it is not in the db" - i.e. it
        // unconditionally clears any id the caller set, whether that id was
        // a leftover from an earlier fetch, a client mistake, or a
        // maliciously spoofed id trying to overwrite an existing row via
        // the "add" endpoint. Without this test, a refactor could easily
        // drop that line (e.g. "simplify" it away) and silently reintroduce
        // the ability to overwrite arbitrary existing rows through POST.
        CompactDisc incoming = new CompactDisc("Spice World", 4.99, "Spice Girls", 11);
        incoming.setId(42); // pretend the caller supplied an existing id
        when(repository.save(any(CompactDisc.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addNewCompactDisc(incoming);

        ArgumentCaptor<CompactDisc> captor = ArgumentCaptor.forClass(CompactDisc.class);
        verify(repository).save(captor.capture());
        assertEquals(0, captor.getValue().getId(), "id must be reset to 0 before the entity reaches the repository");
    }

    @Test
    @DisplayName("addNewCompactDisc() returns exactly what the repository.save() returns")
    void addNewCompactDisc_returnsPersistedEntityFromRepository() {
        // Why this test exists: after save(), the entity returned by JPA
        // carries the database-generated id (identity strategy). The
        // service must return that saved reference (not the caller's
        // original object) so that consumers (e.g. the REST layer) can read
        // back the generated id. If the service accidentally returned the
        // original "incoming" object instead, callers would see id == 0.
        CompactDisc incoming = new CompactDisc("Echo Park", 13.99, "Feeder", 12);
        CompactDisc saved = new CompactDisc("Echo Park", 13.99, "Feeder", 12);
        saved.setId(14);
        when(repository.save(any(CompactDisc.class))).thenReturn(saved);

        CompactDisc result = service.addNewCompactDisc(incoming);

        assertSame(saved, result);
        assertEquals(14, result.getId());
    }

    // ------------------------------------------------------------------
    // updateCompactDisc(CompactDisc)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCompactDisc() calls repository.save() directly, with no existence check first")
    void updateCompactDisc_delegatesToSaveWithoutExistenceCheck() {
        // Why this test exists: unlike a "safe" update that would first
        // findById() to confirm the record exists (and 404 otherwise),
        // updateCompactDisc() simply calls dao.save(disc). Because JPA's
        // save() behaves as an upsert, calling update with an id that does
        // not exist in the database will silently INSERT a brand-new row
        // rather than failing. This test pins down that there is no
        // findById/exists check guarding the update path, which is
        // important production-readiness knowledge (it means the "update"
        // endpoint can never return a meaningful 404 without extra work).
        CompactDisc disc = new CompactDisc("White Ladder", 9.99, "David Gray", 10);
        disc.setId(12);
        when(repository.save(disc)).thenReturn(disc);

        CompactDisc result = service.updateCompactDisc(disc);

        assertSame(disc, result);
        verify(repository, never()).findById(anyInt());
        verify(repository).save(disc);
    }

    // ------------------------------------------------------------------
    // deleteCompactDisc(int)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleteCompactDisc(id) looks the entity up then deletes that exact entity")
    void deleteCompactDiscById_whenFound_deletesTheResolvedEntity() {
        // Baseline happy-path check: the int overload resolves the id to an
        // entity via findById() and passes that exact entity to delete().
        CompactDisc disc = new CompactDisc("Greatest Hits", 14.99, "Penelope", 14);
        disc.setId(13);
        when(repository.findById(13)).thenReturn(Optional.of(disc));

        service.deleteCompactDisc(13);

        verify(repository).delete(disc);
    }

    @Test
    @DisplayName("deleteCompactDisc(id) throws NoSuchElementException when the id does not exist, and never calls delete()")
    void deleteCompactDiscById_whenNotFound_throwsAndDoesNotDelete() {
        // Why this test exists: this exposes a real latent bug/edge case in
        // the production code. deleteCompactDisc(int) is implemented as
        // "dao.findById(id).get()" - calling Optional.get() on an empty
        // Optional throws an unchecked NoSuchElementException instead of
        // being handled gracefully (e.g. returning silently or throwing a
        // documented, catchable exception). This test does not "fix" the
        // bug (per the task instructions, production code is only touched
        // if a test forces a genuine fix); instead it captures the CURRENT,
        // real behaviour so that: (a) anyone reading the tests understands
        // the controller's DELETE /{id} endpoint will surface a 500 for an
        // unknown id rather than a clean 404 (proven in the controller
        // tests), and (b) the behaviour cannot silently change in the
        // future without a test failing.
        when(repository.findById(404)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.deleteCompactDisc(404));
        verify(repository, never()).delete(any(CompactDisc.class));
    }

    // ------------------------------------------------------------------
    // deleteCompactDisc(CompactDisc)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleteCompactDisc(entity) delegates directly to repository.delete() with no lookup")
    void deleteCompactDiscByEntity_delegatesDirectlyToRepository() {
        // Why this test exists: this overload trusts the caller-supplied
        // entity completely - there is no findById() round-trip to
        // re-verify the entity is the "real" persisted one. That is fine
        // when called internally by deleteCompactDisc(int) (which already
        // fetched a real managed/attached entity), but it also means any
        // external caller of this overload can ask the repository to
        // delete an arbitrary, possibly-detached or fabricated entity
        // instance. Verifying "exactly one repository interaction, and it
        // is delete()" documents that contract precisely.
        CompactDisc disc = new CompactDisc("Just Enough Education to Perform", 10.99, "Stereophonics", 11);
        disc.setId(10);

        service.deleteCompactDisc(disc);

        verify(repository).delete(disc);
        verifyNoMoreInteractions(repository);
    }
}
