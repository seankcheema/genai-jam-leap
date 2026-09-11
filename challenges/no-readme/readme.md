# CompactDiscRestDataBoot

A Spring Boot REST API for managing a catalog of Compact Discs (CDs) and their tracks, backed by a MySQL database via Spring Data JPA/Hibernate. It exposes CRUD-style endpoints under `/api/compactdiscs`, documents them with Swagger UI, and ships a handful of static HTML/JS pages that demonstrate calling the API from the browser.

## Table of Contents

- [Tech Stack](#tech-stack)
- [Data Model](#data-model)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Database Setup](#database-setup)
- [Build](#build)
- [Run Locally](#run-locally)
- [Run in Docker](#run-in-docker)
- [REST API](#rest-api)
- [Swagger / API Documentation UI](#swagger--api-documentation-ui)
- [Example Requests](#example-requests)
- [Front-End Demo Pages](#front-end-demo-pages)
- [Project Structure](#project-structure)
- [Logging](#logging)
- [Monitoring & Observability](#monitoring--observability)
- [Known Limitations](#known-limitations)

## Tech Stack

- **Java 11**
- **Spring Boot 2.5.3** (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`)
- **Hibernate / JPA** for persistence
- **MySQL** (`mysql-connector-java` 8.0.22) as the target database
- **Log4j2** (`spring-boot-starter-log4j2`) for logging — the default Spring Boot Logback starter is explicitly excluded in `pom.xml`
- **Springfox Swagger 2** (`springfox-swagger2` / `springfox-swagger-ui` 2.9.2) for interactive API documentation
- **H2** and **Mockito**/**JUnit Vintage** are declared as test-scope dependencies, but there are currently no test classes under `src/test` in this project
- Maven (`pom.xml`) for build/dependency management — note there is no Maven Wrapper (`mvnw`) included in this module, so a local Maven install is required

The application entry point is `com.conygre.spring.boot.AppConfig` (set as `start-class` in `pom.xml`), which boots a standard `@SpringBootApplication` and imports `SwaggerConfig`.

## Data Model

Two JPA entities, mapped by annotations in `src/main/java/com/conygre/spring/boot/entities/`:

**`CompactDisc`** → table `compact_discs`

| Field | Column | Type | Notes |
|---|---|---|---|
| `id` | `id` | `int` | Primary key, auto-increment (`GenerationType.IDENTITY`) |
| `title` | `title` | `String` | |
| `artist` | `artist` | `String` | |
| `price` | `price` | `Double` | |
| `tracks` | `tracks` | `Integer` | Track *count*, stored directly on the CD row (separate from the `trackTitles` relationship below) |
| `trackTitles` | — | `List<Track>` | `@OneToMany` to `Track`, joined via `tracks.cd_id`, cascading `MERGE`/`PERSIST` |

A named query `compactdisc.getAll` is declared (`select cd from CompactDisc as cd where cd.price > :price`) but is not currently invoked anywhere in the codebase.

**`Track`** → table `tracks`

| Field | Column | Type | Notes |
|---|---|---|---|
| `id` | `id` | `Integer` | Primary key, auto-increment |
| `title` | `title` | `String` | |
| `cdId` | `cd_id` | `int` | Foreign key back to `compact_discs.id` |

Schema DDL lives in [`sql/createTables.sql`](sql/createTables.sql), which also seeds 8 sample CDs and a few sample tracks.

## Prerequisites

- JDK 11
- Apache Maven (3.6+ recommended) — install locally, as no `mvnw` wrapper ships with this module
- A running MySQL server (5.7/8.x) reachable from the app
- (Optional) Docker, if you want to containerize the app yourself — see [Run in Docker](#run-in-docker)

## Configuration

Datasource configuration is read from `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/conygre?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=c0nygre1
spring.datasource.driverClassName=com.mysql.cj.jdbc.Driver

logging.file=myapplication.log
```

These are the actual values checked into the repo (a local dev database named `conygre` on `localhost:3306`, with a `root`/`c0nygre1` credential pair) — treat them as **placeholder/development credentials only**, and override them (e.g. with environment-specific `application.properties`, environment variables, or `--spring.datasource.*` command-line args) for anything beyond local development. `server.port` and a `logging.level` override are present in the file but commented out — uncomment/edit them if you need a non-default port (default is `8080`) or a different log level.

There is also a `src/main/java/META-INF/persistence.xml` file with an (empty) JPA persistence-unit definition. It is not populated with real connection details and is not the mechanism actually used to connect to the database — Spring Boot's autoconfiguration (driven by `application.properties` and `spring-boot-starter-data-jpa`) sets up the datasource/`EntityManagerFactory` at runtime. Treat `persistence.xml` as legacy/unused scaffolding rather than active configuration.

## Database Setup

1. Ensure MySQL is running and reachable at the host/port configured above.
2. Run the provided script to create the database, tables, and seed data:

   ```bash
   mysql -u root -p < sql/createTables.sql
   ```

   This creates the `conygre` database, the `compact_discs` and `tracks` tables (with `tracks.cd_id` as a foreign key to `compact_discs.id`), and inserts 8 sample CDs plus 3 sample tracks.
3. Make sure the credentials in `application.properties` (or your override) match a MySQL user that can access the `conygre` database.

## Build

From this directory (`challenges/no-readme`):

```bash
mvn clean package
```

This produces an executable jar under `target/` (e.g. `target/CompactDiscRestDataBoot-0.0.1-SNAPSHOT.jar`) via the `spring-boot-maven-plugin`.

## Run Locally

With MySQL set up as above:

```bash
mvn spring-boot:run
```

or, after `mvn clean package`:

```bash
java -jar target/CompactDiscRestDataBoot-0.0.1-SNAPSHOT.jar
```

By default the app starts on `http://localhost:8080`.

## Run in Docker

`src/main/resources/application-docker.properties` provides an alternate datasource configuration intended for a containerized/Docker Compose setup:

```properties
spring.datasource.url=jdbc:mysql://cddb:3306/conygre
spring.datasource.username=root
spring.datasource.password=secret123
spring.datasource.driver-class-name=com.mysql.jdbc.Driver
```

This points at a MySQL host named `cddb` (e.g. a linked/Compose service, rather than `localhost`) with different credentials than the local profile. To use it, activate the `docker` Spring profile at runtime, for example:

```bash
java -jar target/CompactDiscRestDataBoot-0.0.1-SNAPSHOT.jar --spring.profiles.active=docker
```

**Note:** this module does not currently include a `Dockerfile` or `docker-compose.yml` — only the `application-docker.properties` config file exists. To actually run the app in Docker you will need to add your own `Dockerfile` (e.g. build the jar with `mvn clean package`, then `COPY` it into a `eclipse-temurin:11-jre`-based image and `ENTRYPOINT ["java","-jar","app.jar","--spring.profiles.active=docker"]`) and a MySQL container/service reachable at hostname `cddb` on the same Docker network.

## REST API

Base path: **`/api/compactdiscs`**. All endpoints are defined in `CompactDiscController` (`@CrossOrigin` is enabled, so cross-origin requests are allowed from any domain).

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/compactdiscs` | Returns all CDs in the catalog |
| `GET` | `/api/compactdiscs/{id}` | Returns a single CD by id. Returns `null`/empty body (HTTP 200) if not found — see note below |
| `GET` | `/api/compactdiscs/404/{id}` | Returns a single CD by id, but responds `404 Not Found` if it doesn't exist (an alternate lookup endpoint that handles the not-found case properly) |
| `POST` | `/api/compactdiscs` | Creates a new CD. Body: JSON `CompactDisc` (`title`, `artist`, `price`, `tracks`). The service forces `id` to `0` before saving, so any `id` in the request body is ignored |
| `DELETE` | `/api/compactdiscs/{id}` | Deletes a CD by id |
| `DELETE` | `/api/compactdiscs` | Deletes a CD, identified by a full `CompactDisc` JSON object in the request body |

There is no dedicated `PUT`/update endpoint exposed on the controller, even though `CompactDiscService.updateCompactDisc()` exists at the service layer — it is not currently wired to a REST route.

### Example `CompactDisc` JSON

```json
{
  "title": "Sweet Caroline",
  "artist": "Neil Diamond",
  "price": 13.99,
  "tracks": 1
}
```

## Swagger / API Documentation UI

Swagger 2 is configured via `SwaggerConfig` (imported by `AppConfig`, active on all profiles except `test`) using Springfox 2.9.2. Once the app is running, the interactive API docs are available at:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Raw API spec (JSON): `http://localhost:8080/v2/api-docs`

The Swagger group is named `compactdiscs`, titled "Album REST API with Swagger".

## Example Requests

Ready-to-run HTTP request files (compatible with the VS Code/IntelliJ HTTP Client, e.g. the `.rest`/REST Client extension) are provided in [`rest/`](rest/):

- [`rest/postcd.rest`](rest/postcd.rest) — `POST` a new CD to `http://localhost:8080/api/compactdiscs`
- [`rest/deletecd.rest`](rest/deletecd.rest) — `DELETE` CD with id `14` via `http://localhost:8080/api/compactdiscs/14`

You can also exercise the API with `curl`, e.g.:

```bash
curl http://localhost:8080/api/compactdiscs
curl http://localhost:8080/api/compactdiscs/9
curl -X DELETE http://localhost:8080/api/compactdiscs/14
curl -X POST http://localhost:8080/api/compactdiscs \
  -H "Content-Type: application/json" \
  -d '{"title":"Sweet Caroline","artist":"Neil Diamond","price":13.99,"tracks":1}'
```

## Front-End Demo Pages

`src/main/resources/static/` contains a set of small, standalone HTML/JS pages that are served as static content by Spring Boot and demonstrate different ways of calling the API from a browser. They are teaching examples rather than a real front end for the app:

- **`index.html`** — uses jQuery `$.ajax` to `GET /api/compactdiscs` and renders the results into an HTML table (excluding the `trackTitles` field).
- **`listcds.html`** — a fuller demo using jQuery + the [DataTables](https://datatables.net/) plugin to render `GET /api/compactdiscs` results in a sortable/searchable table, plus commented-out helper functions showing three different ways to call the API (`XMLHttpRequest`, jQuery `$.ajax`, and DataTables' built-in `ajax` option). Note: some of the code here (e.g. `json.compactDiscList`) assumes a response shape that doesn't match the API's actual response (a plain array), so not all functions in this file work as-is.
- **`constructorfunctionajax.html`** — demonstrates building a `CompactDisc` object with a JS constructor function and `POST`ing it via jQuery `$.ajax`, then re-fetching the list.
- **`promisefetch.html`** — demonstrates calling `GET /api/compactdiscs` using the native `fetch()` API and Promises (no jQuery).
- **`temp.html`** — an incomplete/broken scratch page (contains invalid JavaScript) demonstrating a CD "title" input form; not functional as-is.
- **`css/cd.css`** — shared stylesheet for the table-rendering pages.

## Project Structure

```
challenges/no-readme/
├── pom.xml                          # Maven build config (Spring Boot 2.5.3, Java 11)
├── sql/createTables.sql             # MySQL schema + seed data
├── rest/                            # Example .rest HTTP request files
│   ├── postcd.rest
│   └── deletecd.rest
└── src/main/
    ├── java/
    │   ├── META-INF/persistence.xml # Legacy/unused JPA persistence-unit stub
    │   └── com/conygre/spring/boot/
    │       ├── AppConfig.java              # @SpringBootApplication entry point
    │       ├── SwaggerConfig.java          # Springfox Swagger 2 configuration
    │       ├── entities/                   # CompactDisc, Track JPA entities
    │       ├── repos/                      # CompactDiscRepository (Spring Data JPA)
    │       ├── services/                   # CompactDiscService (+ Impl) — business logic
    │       └── rest/                       # CompactDiscController — REST endpoints
    └── resources/
        ├── application.properties          # Local/default datasource + logging config
        ├── application-docker.properties   # Datasource config for the "docker" profile
        ├── log4j2.properties               # Log4j2 logging configuration
        └── static/                         # Demo HTML/JS pages (see above)
```

## Logging

Logging is handled by **Log4j2** (`spring-boot-starter-log4j2`); Spring Boot's default Logback starter is explicitly excluded in `pom.xml` to avoid classpath conflicts.

- Configuration file: `src/main/resources/log4j2.properties`.
- A single **Console** appender (`STDOUT`) is configured with pattern `%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n`, and a `ThresholdFilter` at `info` level.
- Root logger level: `info`.
- The application's own package (`com.conygre.spring.boot`) is explicitly set to `info` level.
- Separately, `application.properties` sets `logging.file=myapplication.log` — this is a Spring Boot logging property that, under the default Logback setup, would additionally write logs to a file named `myapplication.log` in the working directory. Because this project uses Log4j2 (not Logback) and `log4j2.properties` only defines a console appender, this property does not currently result in file output in practice — logs go to the console/stdout only, unless `log4j2.properties` is extended with a `RollingFile`/`File` appender.
- Application code logs via `LogManager`/`Logger` from `org.apache.logging.log4j` — see `CompactDiscController` (logs each `GET /api/compactdiscs` call) and `CompactDiscServiceImpl` (logs catalog retrieval).

## Monitoring & Observability

Being transparent about what is and isn't currently built in:

- **No Spring Boot Actuator.** `pom.xml` does not include `spring-boot-starter-actuator`, so there are **no** `/actuator/health`, `/actuator/info`, `/actuator/metrics`, or similar endpoints available out of the box. If you need health checks, metrics, or readiness/liveness probes (e.g. for Docker/Kubernetes), you would need to add the Actuator starter (and likely a metrics registry such as Micrometer + Prometheus) yourself — this is not currently wired up.
- **Logging is the primary observability signal today.** All request-level and service-level logging goes to stdout via Log4j2 (see [Logging](#logging) above), at `info` level for `com.conygre.spring.boot.*`. In a container/orchestrated environment, capturing stdout via the platform's standard log collection (e.g. `docker logs`, a sidecar, or a centralized logging stack) is the current de facto way to monitor the app's behavior.
- **No dedicated `/health` endpoint of any kind.** A basic "is it up" check would have to rely on hitting a real endpoint such as `GET /api/compactdiscs` (which also implicitly exercises the DB connection) or the Swagger UI page, and checking for a `200` response.
- **Database errors surface as unhandled exceptions.** There is no explicit `@ControllerAdvice`/global exception handling in the codebase, so failures (e.g. DB connectivity issues, a missing record on delete) will typically propagate as Spring Boot's default error responses/stack traces rather than structured error payloads.

If production-grade monitoring is required, recommended next steps would be: add `spring-boot-starter-actuator` for health/info/metrics endpoints, add a Micrometer registry for metrics export, and extend `log4j2.properties` with a rolling file appender (and/or structured JSON logging) for durable, searchable logs.

## Known Limitations

- No `PUT`/update REST endpoint, despite `updateCompactDisc` existing on the service layer.
- `GET /api/compactdiscs/{id}` returns an empty/`null` body rather than a `404` for a missing CD (use `GET /api/compactdiscs/404/{id}` instead).
- Credentials in `application.properties` / `application-docker.properties` are plaintext, checked-in development defaults — do not reuse them in any shared or production environment.
- No `Dockerfile` or `docker-compose.yml` is included; `application-docker.properties` assumes you supply your own container setup with a MySQL service reachable at host `cddb`.
- No automated tests currently exist under `src/test`, despite JUnit/Mockito/H2 test dependencies being declared in `pom.xml`.
- Several of the static demo pages under `src/main/resources/static/` are illustrative/incomplete (e.g. `listcds.html` references a response shape the API doesn't actually return; `temp.html` contains invalid JavaScript).
