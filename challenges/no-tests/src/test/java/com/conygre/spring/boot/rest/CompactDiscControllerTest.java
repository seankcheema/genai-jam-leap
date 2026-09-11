package com.conygre.spring.boot.rest;

import com.conygre.spring.boot.entities.CompactDisc;
import com.conygre.spring.boot.services.CompactDiscService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer slice tests for {@link CompactDiscController}.
 *
 * <p>{@code @WebMvcTest} boots only the MVC infrastructure for this one
 * controller (no repositories, no real database, no datasource - important
 * because the app's application.properties points at a MySQL server that
 * will not exist in a CI/test environment). {@link CompactDiscService} is
 * replaced with a Mockito mock via {@code @MockBean}, so these tests verify
 * only what the controller itself does: HTTP status codes, routing, and
 * request/response JSON (de)serialisation.
 *
 * <p>A recurring theme below is that this controller is a very thin,
 * largely unguarded layer over the service: it does not validate request
 * bodies, and it does not catch exceptions thrown by the service. Several
 * tests exist specifically to document (not necessarily to "approve of")
 * that behaviour, since knowing about it is exactly what a production
 * readiness review needs.
 */
@WebMvcTest(CompactDiscController.class)
class CompactDiscControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CompactDiscService service;

    private static final String BASE_URL = "/api/compactdiscs";

    private CompactDisc sampleDisc(int id) {
        CompactDisc disc = new CompactDisc("Is This It", 13.99, "The Strokes", 11);
        disc.setId(id);
        return disc;
    }

    // ------------------------------------------------------------------
    // GET /api/compactdiscs  (findAll)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/compactdiscs returns 200 with the full catalog as a JSON array")
    void findAll_returnsCatalogAsJsonArray() throws Exception {
        CompactDisc cd1 = sampleDisc(9);
        CompactDisc cd2 = new CompactDisc("Parachutes", 11.99, "Coldplay", 10);
        cd2.setId(11);
        when(service.getCatalog()).thenReturn(Arrays.asList(cd1, cd2));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].title").value("Is This It"))
                .andExpect(jsonPath("$[1].artist").value("Coldplay"));
    }

    @Test
    @DisplayName("GET /api/compactdiscs returns 200 with an empty JSON array when the catalog is empty")
    void findAll_whenCatalogEmpty_returnsEmptyArrayNot404() throws Exception {
        // Why this test exists: an empty catalog is a valid state (e.g. a
        // brand-new database), not an error condition. The endpoint must
        // keep returning 200 with "[]", never 404/500, so clients can
        // distinguish "no CDs yet" from "server/route problem".
        when(service.getCatalog()).thenReturn(new ArrayList<>());

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------
    // GET /api/compactdiscs/{id}  (getCdById - the NON-404 variant)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/compactdiscs/{id} returns 200 with the disc JSON when found")
    void getCdById_whenFound_returnsDiscJson() throws Exception {
        when(service.getCompactDiscById(9)).thenReturn(sampleDisc(9));

        mockMvc.perform(get(BASE_URL + "/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.title").value("Is This It"));
    }

    @Test
    @DisplayName("GET /api/compactdiscs/{id} returns 200 with an EMPTY body (not 404) when the id is unknown")
    void getCdById_whenNotFound_returns200WithEmptyBodyNot404() throws Exception {
        // Why this test exists: this is a genuine, notable API design flaw
        // worth flagging in a production review. getCdById() simply returns
        // whatever the service gives it (null for an unknown id, per
        // CompactDiscServiceImpl), and since the method has no explicit
        // ResponseEntity/@ResponseStatus handling, Spring MVC responds with
        // HTTP 200 and an empty body rather than 404. This is in direct
        // contrast to the dedicated /404/{id} endpoint (see below), which
        // gets it right. A test exists here to lock in the CURRENT
        // behaviour so a future contributor cannot accidentally change
        // which endpoint has correct semantics without a test noticing.
        when(service.getCompactDiscById(999)).thenReturn(null);

        mockMvc.perform(get(BASE_URL + "/999"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ------------------------------------------------------------------
    // GET /api/compactdiscs/404/{id}  (getByIdWith404 - the CORRECT variant)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/compactdiscs/404/{id} returns 200 with the disc JSON when found")
    void getByIdWith404_whenFound_returns200AndDisc() throws Exception {
        when(service.getCompactDiscById(11)).thenReturn(sampleDisc(11));

        mockMvc.perform(get(BASE_URL + "/404/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11));
    }

    @Test
    @DisplayName("GET /api/compactdiscs/404/{id} returns 404 with an empty body when not found")
    void getByIdWith404_whenNotFound_returns404() throws Exception {
        // Why this test exists: verifies the one endpoint in this
        // controller that DOES implement proper REST not-found semantics,
        // by explicitly checking for a null result and returning
        // ResponseEntity with HttpStatus.NOT_FOUND. This is the positive
        // counterpart to getCdById's test above.
        when(service.getCompactDiscById(12345)).thenReturn(null);

        mockMvc.perform(get(BASE_URL + "/404/12345"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    // ------------------------------------------------------------------
    // POST /api/compactdiscs  (addCd)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/compactdiscs with a valid body returns 200 and forwards the deserialised disc to the service")
    void addCd_withValidBody_forwardsDeserialisedDiscToService() throws Exception {
        CompactDisc requestDisc = new CompactDisc("Mezzanine", 12.99, "Massive Attack", 11);

        mockMvc.perform(post(BASE_URL)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(requestDisc)))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CompactDisc> captor = org.mockito.ArgumentCaptor.forClass(CompactDisc.class);
        verify(service).addNewCompactDisc(captor.capture());
        assertEquals("Mezzanine", captor.getValue().getTitle());
        assertEquals("Massive Attack", captor.getValue().getArtist());
        assertEquals(12.99, captor.getValue().getPrice());
        assertEquals(11, captor.getValue().getTracks());
    }

    @Test
    @DisplayName("POST /api/compactdiscs with fields missing from the JSON still returns 200 - the controller performs no input validation")
    void addCd_withMissingFields_stillAccepted() throws Exception {
        // Why this test exists: there is no @Valid / bean-validation
        // annotation anywhere on the CompactDisc entity or on the
        // controller's @RequestBody parameter. This test proves that a
        // request body missing "artist" and "price" entirely is still
        // accepted and forwarded to the service unchanged (with those
        // fields simply null). This is important production-readiness
        // knowledge: bad/incomplete data from a client will reach - and be
        // persisted by - the service layer unless validation is added.
        String incompleteJson = "{\"title\":\"Unknown Album\"}";

        mockMvc.perform(post(BASE_URL)
                        .contentType("application/json")
                        .content(incompleteJson))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CompactDisc> captor = org.mockito.ArgumentCaptor.forClass(CompactDisc.class);
        verify(service).addNewCompactDisc(captor.capture());
        assertEquals("Unknown Album", captor.getValue().getTitle());
        assertNull(captor.getValue().getArtist());
        assertNull(captor.getValue().getPrice());
    }

    @Test
    @DisplayName("POST /api/compactdiscs with unparsable JSON returns 400 (Spring's default message-conversion handling, not custom validation)")
    void addCd_withMalformedJson_returns400() throws Exception {
        // Why this test exists: contrasts with the previous test. Even
        // though the controller adds no validation of its own, Spring's
        // HttpMessageConverter layer still rejects syntactically invalid
        // JSON with 400 Bad Request before the controller method body ever
        // runs. Good to confirm the service is never invoked in this case.
        mockMvc.perform(post(BASE_URL)
                        .contentType("application/json")
                        .content("{not valid json"))
                .andExpect(status().isBadRequest());

        verify(service, never()).addNewCompactDisc(any(CompactDisc.class));
    }

    // ------------------------------------------------------------------
    // DELETE /api/compactdiscs/{id}  (deleteCd by id)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /api/compactdiscs/{id} returns 200 when the service deletes successfully")
    void deleteCdById_whenFound_returns200() throws Exception {
        doNothing().when(service).deleteCompactDisc(9);

        mockMvc.perform(delete(BASE_URL + "/9"))
                .andExpect(status().isOk());

        verify(service).deleteCompactDisc(9);
    }

    @Test
    @DisplayName("DELETE /api/compactdiscs/{id} for an unknown id results in a server error, because the service's NoSuchElementException is never caught")
    void deleteCdById_whenNotFound_resultsInServerError() throws Exception {
        // Why this test exists: this is the controller-level consequence of
        // the service bug/edge-case documented in
        // CompactDiscServiceImplTest#deleteCompactDiscById_whenNotFound_throwsAndDoesNotDelete.
        // There is no @ControllerAdvice / exception handler anywhere in
        // this application, so a NoSuchElementException thrown by the
        // service for an unknown id propagates all the way out of the
        // DispatcherServlet. In a real deployment this becomes an HTTP 500
        // with a stack trace, instead of a clean 404 - a genuine production
        // bug worth flagging. MockMvc re-throws the (wrapped) exception
        // from perform() rather than resolving it to a response, since no
        // resolver handles it, so we assert on the exception itself and
        // confirm its root cause is the expected NoSuchElementException.
        doThrow(new NoSuchElementException("no CD with that id")).when(service).deleteCompactDisc(404);

        Throwable thrown = assertThrows(Throwable.class, () ->
                mockMvc.perform(delete(BASE_URL + "/404")));

        Throwable cause = thrown;
        boolean foundExpectedCause = false;
        while (cause != null) {
            if (cause instanceof NoSuchElementException) {
                foundExpectedCause = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(foundExpectedCause, "expected NoSuchElementException somewhere in the cause chain, got: " + thrown);
    }

    // ------------------------------------------------------------------
    // DELETE /api/compactdiscs  (deleteCd by request body)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /api/compactdiscs with a JSON body returns 200 and forwards the deserialised disc to the service")
    void deleteCdByBody_returns200AndForwardsDisc() throws Exception {
        CompactDisc discToDelete = sampleDisc(16);

        mockMvc.perform(delete(BASE_URL)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(discToDelete)))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CompactDisc> captor = org.mockito.ArgumentCaptor.forClass(CompactDisc.class);
        verify(service).deleteCompactDisc(captor.capture());
        assertEquals(16, captor.getValue().getId());
    }
}
