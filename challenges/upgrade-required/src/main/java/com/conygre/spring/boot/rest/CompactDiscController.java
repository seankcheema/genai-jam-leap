package com.conygre.spring.boot.rest;

import com.conygre.spring.boot.services.CompactDiscService;
import com.conygre.spring.boot.entities.CompactDisc;
// UPGRADE (Springfox -> springdoc-openapi): the old io.swagger.annotations.ApiOperation
// annotation comes from Swagger/OpenAPI 2.x annotations (io.swagger:swagger-annotations),
// which was only ever a transitive dependency of Springfox. springdoc-openapi is built on
// the OpenAPI 3 annotation set (io.swagger.core.v3:swagger-annotations-jakarta), whose
// equivalent annotation is io.swagger.v3.oas.annotations.Operation, using "summary" instead
// of "value" and "operationId" instead of "nickname". Everything else about the endpoint
// (path, HTTP method, return type) is picked up automatically by springdoc from the
// existing @RequestMapping/@RestController annotations - no other config is required.
import io.swagger.v3.oas.annotations.Operation;
//import org.apache.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

@RestController
@RequestMapping("/api/compactdiscs")
@CrossOrigin // allows requests from all domains
public class CompactDiscController {

	private static Logger logger = LogManager.getLogger(CompactDiscController.class);

	@Autowired
	private CompactDiscService service;

	@Operation(summary = "findAll", operationId = "findAll")
	@RequestMapping(method = RequestMethod.GET)
	public Iterable<CompactDisc> findAll() {
		logger.info("managed to call a Get request for findAll");
		return service.getCatalog();
	}

	@RequestMapping(method = RequestMethod.GET, value = "/{id}")
	public CompactDisc getCdById(@PathVariable("id") int id) {
		return service.getCompactDiscById(id);
	}

	@RequestMapping(method=RequestMethod.GET, value="/404/{id}")
	public ResponseEntity<CompactDisc> getByIdWith404(@PathVariable("id") int id) {
		CompactDisc disc = service.getCompactDiscById(id);
		if (disc == null) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		else {
			return new ResponseEntity<>(disc, HttpStatus.OK);
		}
	}


	@RequestMapping(method = RequestMethod.DELETE, value = "/{id}")
	public void deleteCd(@PathVariable("id") int id) {
		service.deleteCompactDisc(id);
	}

	@RequestMapping(method = RequestMethod.DELETE)
	public void deleteCd(@RequestBody CompactDisc disc) {
		service.deleteCompactDisc(disc);
	}

	@RequestMapping(method = RequestMethod.POST)
	public void addCd(@RequestBody CompactDisc disc) {
		service.addNewCompactDisc(disc);
	}

}
