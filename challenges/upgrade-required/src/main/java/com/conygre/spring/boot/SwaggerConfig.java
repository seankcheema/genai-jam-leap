package com.conygre.spring.boot;

import org.springframework.context.annotation.Bean;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

/*
 * UPGRADE (Springfox -> springdoc-openapi), Spring Boot 2.5.3 -> 3.5.16:
 *
 * This class used to be built entirely around Springfox's @EnableSwagger2 annotation and its
 * Docket builder API (springfox.documentation.spring.web.plugins.Docket /
 * springfox.documentation.swagger2.annotations.EnableSwagger2). That code has been deleted -
 * it will not even compile against Spring Boot 3.x, because:
 *
 *   1. Springfox is unmaintained (no release since 2020) and was never updated to support
 *      Jakarta EE 9+, so it cannot resolve against Spring Boot 3's jakarta.servlet.* classpath
 *      at all.
 *   2. Separately (and this would bite even on Spring Boot 2.6+ while still on javax.*),
 *      Springfox reflects on Spring MVC's internal path-matching implementation to enumerate
 *      controller routes. Spring Framework 5.3.16 switched the default MVC path-matching
 *      strategy from AntPathMatcher to PathPatternParser, which breaks that reflection with a
 *      NullPointerException during context startup ("Failed to start bean
 *      'documentationPluginsBootstrapper'"). This is the single most common reason Springfox
 *      "just stops working" when a Spring Boot 2.5 app is upgraded.
 *
 * The fix used here is to swap Springfox out for springdoc-openapi (see pom.xml), which
 * generates the OpenAPI 3 document and Swagger UI straight from Spring's own
 * RequestMappingHandlerMapping metadata instead of reflecting into internals. For a basic
 * setup springdoc needs *no* manual @Bean at all - adding the
 * springdoc-openapi-starter-webmvc-ui dependency is enough to get a working /swagger-ui.html
 * and /v3/api-docs. The one @Bean kept below is purely optional: it reproduces the same
 * title/description/contact metadata that the old Docket's ApiInfoBuilder used to supply, via
 * the OpenAPI 3 equivalent (io.swagger.v3.oas.models.OpenAPI/Info/Contact). The @Profile("!test")
 * workaround that used to sit on this class is gone too - it existed only to dodge a Springfox
 * bug where it broke Spring's test context; springdoc has no such issue and there are no test
 * sources in this project to worry about either way.
 */
public class SwaggerConfig {

	@Bean
	public OpenAPI compactDiscOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Album REST API with Swagger")
						.description("This API allows you to interact with albums. It is a CRUD API")
						.contact(new Contact()
								.name("Nick Todd")
								.url("http://www.conygre.com")
								.email("nick.todd@conygre.com")));
	}
}
