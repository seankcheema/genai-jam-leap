find-and-fix-a-bug (done directly) — ValidateISBN.isThisAValidShortISBN multiplied the raw char code instead of the digit's numeric value. This was invisible for all-digit ISBNs (the error term is a multiple of 11, so it cancelled out of the %11 check by coincidence), but broke for the X check-digit case, which uses a different, value-based path — hence the one failing test. Fixed both checksum methods to use Character.getNumericValue(), added missing digit validation to the 13-digit path, a null-input guard, and 5 new edge-case tests. 12/12 tests pass.

fix-an-unresponsive-website — the credits list rendered all 15 synchronous 100ms delays in one React render pass, blocking the main thread for ~1.5s and freezing menu clicks. Fixed by chunking the render one credit at a time via setTimeout, letting the browser regain control between items; delay untouched. Verified with Playwright: max main-thread freeze dropped from 3020ms to 403ms.

improve-performance — PrimeGenerator generated 100 independent 2000-bit primes sequentially on one thread. Parallelized across a thread pool sized to CPU cores with ThreadLocalRandom; ~2.3x speedup (53s → 23s avg), bit length untouched, correctness verified.

no-readme — wrote an accurate, production-grade README for the CD catalog Spring Boot API, based on actually reading the controller/service/entities/config rather than guessing (and honestly flagged what's not there, e.g. no Actuator, missing PUT endpoint, incomplete Docker setup).

no-tests — added 41 tests (service, controller slice, repository, entity) for the CD catalog app, deliberately documenting real bugs found along the way (silent null on not-found, unhandled exception on delete-of-missing-id, no request validation) as locked-in test cases rather than silently fixing them.

perl → python — ported the NASDAQ CSV→SQL schema-inference ETL to a typed, dataclass-based etl.py with 33 passing pytest tests. Found and documented several genuine Perl bugs (string-vs-numeric comparison typos causing dead code, an off-by-one chop truncating the last column header) and made a deliberate, commented decision on which quirks to preserve vs. fix.

upgrade-required — Spring Boot 2.5.3/Java 11 → Spring Boot 3.5.16/Java 25. Main breaking changes: javax.*→jakarta.* namespace, Springfox (dead since 2020) replaced with springdoc-openapi, MySQL connector coordinates renamed, logging.file→logging.file.name. mvn clean package succeeds; app boots correctly and fails only at the expected point (no local MySQL).

Nothing was committed — all changes are sitting in the working tree (git status above) for you to review before committing.