"""Modern Python port of ``etl.pl``: a MySQL schema-inference + SQL generator.

WHAT THE ORIGINAL PERL DOES
----------------------------
``etl.pl`` reads a CSV file (``prices.csv``, a NASDAQ end-of-day price
dump: ``ticker,date,open,high,low,close,vol``) and, in a single pass over
the rows, does two things at once:

1. Streams one ``INSERT INTO nasdaq_prices (...) VALUES (...);`` statement
   per data row out to ``mysqlInsertValues.sql``.
2. Incrementally infers a MySQL column type (``int`` / ``decimal`` /
   ``varchar``) and a size (length, or decimal precision/scale) for every
   column by inspecting every value ever seen in that column, and once EOF
   is reached, writes a single ``CREATE TABLE nasdaq_prices (...)``
   statement to ``mysqlCreateSchema.sql`` using the final, fully-observed
   types.

This module reproduces that behaviour with modern, typed, tested Python,
using :mod:`csv` for parsing instead of manual ``chop``/``chomp``/regex
byte-surgery, a small ``ColumnStats`` class instead of parallel arrays
(``@type``, ``@length``, ``@decimal_length1``, ``@decimal_length2`` in the
original), and a streaming generator-based pipeline that preserves the
original's single-pass, low-memory design.

HOW EACH PIECE OF THE PERL MAPS TO PYTHON
------------------------------------------
* ``@Field_Names`` / header chop-chain (etl.pl lines 25-44)
    -> :meth:`NasdaqPriceEtl.normalize_header`, using ``csv.reader`` on the
       header line instead of three manual ``chop`` calls.
* ``@type`` / ``@length`` / ``@decimal_length1`` / ``@decimal_length2``
  parallel arrays, one bucket of type-inference state per field
  (etl.pl lines 110-227)
    -> a single :class:`ColumnStats` dataclass per column, with an
       ``observe(value)`` method that is a direct, regex-for-regex port of
       the Perl ``if``/``elsif`` chain (see ``ColumnStats.observe`` for the
       line-by-line mapping and the bugs deliberately fixed along the way).
* The streaming "print VALUES ... while inferring types" loop
  (etl.pl lines 86-256)
    -> :meth:`NasdaqPriceEtl.run`, a single pass over ``csv.reader`` that
       writes each INSERT line immediately and updates ``ColumnStats``
       as it goes, exactly mirroring the original's one-pass-over-the-file
       design (important for a file this could plausibly be much larger
       than memory).
* The final ``CREATE TABLE`` dump (etl.pl lines 258-304)
    -> :func:`render_create_table`, called once after the row loop ends.

DELIBERATE DECISIONS: WHAT WAS PRESERVED VS. FIXED
----------------------------------------------------
This is legacy code with real bugs, and the brief asks for a *deliberate,
documented* decision on each quirky bit rather than a blind transliteration.

Preserved (genuine legacy *business* behaviour, kept for compatibiliy):

1. **Empty CSV values are recorded as ``"0"`` for type/length inference,
   but written out as an empty string in the actual ``INSERT`` statement.**
   (etl.pl lines 110-114 vs. 224-227.) This asymmetry is intentional and is
   replicated in :meth:`NasdaqPriceEtl.run` by feeding ``ColumnStats.observe``
   a blank-substituted-with-"0" copy of the value, while the *literal SQL
   text* uses the untouched (but quote-escaped) original.

   Subtlety found while tracing the Perl: because ``$Field_Values[$i]`` is
   mutated *in place* at line 113 (blank -> ``"0"``), the second blank-check
   at line 224 (``length(...) < 1``) can *never* be true again — the value
   is already ``"0"`` (length 1) by the time it's reached. Read completely
   literally, the shipped Perl therefore always emits the literal string
   ``"0"`` for a blank cell, never ``""``; the dedicated "blank it back out"
   block at lines 224-227 is dead code. Its presence, however, makes the
   *intent* unambiguous: someone wanted blanks preserved as blank in the
   output data while still contributing a sane "0" to type inference. We
   implement that intent (blank in -> blank INSERT literal) rather than the
   accidental collapse, because reproducing the collapse would mean
   silently writing fabricated "0" values into a database column for data
   that was actually missing — a data-correctness bug with no business
   justification, as opposed to the schema-shape quirks below which only
   affect inferred column sizing.

2. **A numeric-looking value containing more than one "." is classified as
   ``varchar``, not ``decimal``.** (etl.pl lines 155-170.) E.g. ``"1.2.3"``
   would flip that column to ``varchar`` rather than being rejected or
   raising an error. Kept verbatim in ``ColumnStats.observe`` — it's a
   defensible (if crude) "this doesn't look like a normal decimal, bail to
   the safe catch-all type" rule, and prices data will never trigger it in
   practice, so there's no reason to remove it.

3. **Minimal SQL string escaping**: only ``'`` is backslash-escaped
   (mirroring Perl's ``s/'/\\'/g``), see :func:`escape_sql_literal`. This is
   *not* a safe defense against SQL injection (it doesn't handle backslash
   itself, NUL bytes, encoding tricks, etc.) — the original script builds
   raw SQL text with no parameterization, and this port deliberately keeps
   that same "legacy dump tool" behaviour for output compatibility. A real
   loader should use parameterized queries / a driver's own escaping
   instead; this is flagged loudly in :func:`escape_sql_literal`'s
   docstring rather than silently fixed, since fixing it would change the
   textual output of every INSERT statement.

4. **Type-inference precedence / stickiness**: once a column is classified
   ``varchar`` it can never change again; ``decimal`` is never downgraded
   back to ``int``; a column only becomes ``decimal`` on a value with
   exactly one ``.``. This state machine (etl.pl lines 117-206) is real
   inference logic, not a bug, and is preserved exactly.

5. **A row with more/fewer fields than the header** is handled the way the
   original's field loop (bounded by the *header's* column count, not the
   row's own length) implicitly handles it: extra trailing fields are
   silently ignored, missing trailing fields are treated as blank. See
   ``NasdaqPriceEtl._align_row``.

Fixed (accidental Perl bugs/typos, not replicated):

6. **Dead "update max length" checks.** Lines 123, 141, 162 and 199 of
   etl.pl all read ``$length[$field_count] < 'length($Field_Values[...])'``
   — note the *single quotes* around what was clearly meant to be a
   ``length(...)`` function call. In Perl that makes the right-hand side a
   literal string, which numifies to ``0``, so those comparisons are
   permanently false and those four length-tracking updates never fire.
   (Column length tracking for plain ``varchar``-via-letters values still
   works by accident, because control falls through to the *correctly
   written* check at line 213 on the same row; but ``int``/varchar-via-dots/
   varchar-via-symbol values never get their max length updated at all —
   every such column would render as e.g. ``int ()`` with an empty length.)
   This is an obvious typo, not a deliberate rule, and reproducing it would
   make the tool fail at its one job (sizing columns), so
   ``ColumnStats.observe`` simply tracks ``max(len(value), ...)`` correctly
   for every branch.

7. **String vs. numeric comparison for decimal precision tracking.** Lines
   178 and 184 use Perl's string ``lt`` instead of numeric ``<`` to compare
   digit counts (e.g. ``"9" lt "10"`` is *false*, because it's a
   lexicographic compare of the first characters — a latent bug that would
   under-track precision whenever the digit count crosses a power of ten
   boundary out of order). Python's ``max()`` on ``int`` is used instead,
   which is simply the correct implementation of "track the largest digit
   count seen".

8. **Header-line truncation.** ``prices.csv`` uses CRLF line endings.
   etl.pl reads the header with ``<FILE>`` (keeps the ``\r\n``), then calls
   ``chop`` *three* times (lines 28, 30, 37) with a couple of no-op regexes
   in between. The first two chops correctly strip ``\n`` then ``\r``; the
   third chop then removes one more real character — the last character of
   the last column name (``vol`` -> ``vo``). Data rows only chop *twice*
   total (``chomp`` then one ``chop``, lines 91/97) and so are unaffected.
   This is a straightforward off-by-one artifact of doing CRLF handling by
   hand, fully obviated by using :mod:`csv`, which parses line endings and
   quoting correctly per RFC 4180. Not reproduced.

9. **No trailing statement terminator** on the original's ``CREATE TABLE``
   output (it ends the file with ``ENGINE=... DEFAULT CHARSET=...\n`` and
   no ``;``). This port always terminates both generated statements with
   ``;`` because that's simply valid, professional SQL — and it matches
   this repository's own reference ``mysqlCreateSchema.sql`` /
   ``mysqlInsertValues.sql``, which also carry the terminator (those
   reference files were evidently produced by a since-modified copy of the
   script, as several of these details show; they were used here only as a
   rough structural sanity check, per the brief, not as literal ground
   truth).

10. A dead branch at etl.pl lines 288-292 (``if ($count_columns ==
    $Field_Names_Count_Plus_One)``) can never be true given the enclosing
    loop's bound and prints nothing reachable; there is nothing to port.
"""

from __future__ import annotations

import argparse
import csv
import enum
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

# ---------------------------------------------------------------------------
# Configuration - mirrors the hardcoded globals at the top of etl.pl
# (lines 4-6: $TABLE_NAME, $DATABASE_ENGINE, $DEFAULT_CHARSET).
# ---------------------------------------------------------------------------

DEFAULT_TABLE_NAME = "nasdaq_prices"
DEFAULT_ENGINE = "InnoDB"
DEFAULT_CHARSET = "latin1"

#: Default input/output file locations, colocated with this module so the
#: script "just works" when run from within challenges/perl/. Output
#: filenames are deliberately distinct from the pre-existing
#: mysqlCreateSchema.sql / mysqlInsertValues.sql reference files so running
#: this port never overwrites that reference output.
_HERE = Path(__file__).resolve().parent
DEFAULT_CSV_PATH = _HERE / "prices.csv"
DEFAULT_SCHEMA_SQL_PATH = _HERE / "python_create_schema.sql"
DEFAULT_INSERT_SQL_PATH = _HERE / "python_insert_values.sql"

# Regexes are direct translations of the Perl character-class tests used in
# the type-inference chain (etl.pl lines 117, 133, 148, 194), kept as named
# constants so ColumnStats.observe reads like a line-by-line mapping.
_HAS_LETTER = re.compile(r"[a-zA-Z]")  # Perl: $v =~ m/[a-zA-Z]/
_HAS_NON_LETTER = re.compile(r"[^a-zA-Z]")  # Perl: $v =~ m/[^a-zA-Z]/
_HAS_DIGIT_OR_DOT = re.compile(r"[0-9.]")  # Perl: $v =~ m/[0-9.]/
_HAS_NON_DIGIT_OR_DOT = re.compile(r"[^0-9.]")  # Perl: $v =~ m/[^0-9.]/


class ColumnType(enum.Enum):
    """The three MySQL column types etl.pl was capable of inferring."""

    INT = "int"
    DECIMAL = "decimal"
    VARCHAR = "varchar"


@dataclass
class ColumnStats:
    """Accumulates type/size information for one CSV column across all rows.

    This replaces the original's four parallel arrays (``@type``,
    ``@length``, ``@decimal_length1``, ``@decimal_length2``, each indexed by
    field position) with one object per column. :meth:`observe` is called
    once per row with that row's (already blank->``"0"``-substituted, see
    module docstring point 1) value for this column, and is a direct port
    of etl.pl lines 117-220.
    """

    name: str
    type: ColumnType | None = None
    max_length: int = 0  # varchar / int sizing ("$length[$i]" in Perl)
    max_int_digits: int = 0  # digits before '.', max across rows ("$decimal_length1[$i]")
    max_frac_digits: int = 0  # digits after '.', max across rows ("$decimal_length2[$i]")

    def observe(self, value: str) -> None:
        """Update inferred type/size from one (non-blank-substituted) value.

        ``value`` must never be an empty string here — callers are
        responsible for the etl.pl-line-113 "" -> "0" substitution before
        calling this, exactly as the original substitutes before running
        any of the regex checks below.
        """
        has_letters = bool(_HAS_LETTER.search(value))

        if has_letters:
            # etl.pl 117-128: any letter present -> varchar, permanently.
            self.type = ColumnType.VARCHAR
        elif self.type is not ColumnType.VARCHAR:
            # etl.pl 130: "if type != varchar" - only re-run numeric
            # inference while the column hasn't already been permanently
            # downgraded to varchar by an earlier row.

            # etl.pl 133-146: [^a-zA-Z] is always true here (value has no
            # letters and, by contract, is never empty), so this simplifies
            # to "become int, unless we're already decimal" - decimal is
            # never downgraded back to int.
            if _HAS_NON_LETTER.search(value) and self.type is not ColumnType.DECIMAL:
                self.type = ColumnType.INT

            # etl.pl 148-192: decimal-point counting.
            if _HAS_DIGIT_OR_DOT.search(value):
                # Perl uses `split("\\.", $v)` and takes $#array (the
                # trailing-empty-suppressing list-context split). We count
                # '.' characters directly instead, which is the more
                # correct and less surprising way to count decimal points
                # (it doesn't silently miscount a value with a trailing
                # "." such as "5." the way Perl's split would) - a small,
                # deliberate deviation for correctness on an edge case real
                # price data never actually hits.
                num_dots = value.count(".")
                if num_dots > 1:
                    # Preserved quirk (see module docstring #2): more than
                    # one '.' means "not really numeric" -> varchar.
                    self.type = ColumnType.VARCHAR
                    self.max_int_digits = 0
                    self.max_frac_digits = 0
                elif num_dots == 1:
                    self.type = ColumnType.DECIMAL
                    int_part, frac_part = value.split(".", 1)
                    self.max_int_digits = max(self.max_int_digits, len(int_part))
                    self.max_frac_digits = max(self.max_frac_digits, len(frac_part))

            # etl.pl 194-205: any character that isn't a digit or '.'
            # (e.g. "-", "+", "%") forces varchar.
            if _HAS_NON_DIGIT_OR_DOT.search(value):
                self.type = ColumnType.VARCHAR

        # etl.pl 213-217 (correct) vs. 123/141/162/199 (dead due to the
        # quoting typo - see module docstring #6): we always track the
        # running maximum length correctly, for every branch above.
        self.max_length = max(self.max_length, len(value))

    def sql_type_clause(self) -> str:
        """Render this column's inferred ``type(size)`` for CREATE TABLE."""
        if self.type is ColumnType.DECIMAL:
            precision = self.max_int_digits + self.max_frac_digits
            return f"decimal({precision},{self.max_frac_digits})"
        if self.type is ColumnType.INT:
            return f"int({self.max_length})"
        if self.type is ColumnType.VARCHAR:
            return f"varchar({self.max_length})"
        # A column that never observed a single row (e.g. an empty CSV
        # body). The original Perl would interpolate an undef $type here
        # and print the broken `` `col`  (0)``; we fall back to a valid,
        # explicit varchar(0) instead.
        return "varchar(0)"


@dataclass(frozen=True)
class ETLConfig:
    """User-facing knobs, mirroring etl.pl's three hardcoded globals."""

    table_name: str = DEFAULT_TABLE_NAME
    engine: str = DEFAULT_ENGINE
    charset: str = DEFAULT_CHARSET


@dataclass(frozen=True)
class ETLResult:
    """Summary of one run, for the "Processed N columns and M lines" message."""

    row_count: int
    column_count: int


def escape_sql_literal(value: str) -> str:
    """Backslash-escape single quotes, mirroring etl.pl's ``s/'/\\\\'/g``.

    WARNING: this is preserved for output *compatibility* with the legacy
    tool, not because it's good practice. It only escapes ``'`` and does
    nothing about backslashes, NUL bytes, or any other MySQL special
    handling, so it is **not** a safe defense against SQL injection. The
    original script builds raw SQL text with no parameterization at all;
    a real loader should use parameterized queries (e.g. a DB-API driver's
    ``cursor.execute(sql, params)``) instead of ever formatting values into
    SQL text by hand. This function exists only so this port's generated
    ``.sql`` files are textually compatible with the legacy tool's output.
    """
    return value.replace("'", "\\'")


def render_insert_statement(table_name: str, columns: Sequence[str], values: Sequence[str]) -> str:
    """Render one ``INSERT INTO ... VALUES (...);`` line for a single row.

    Ports etl.pl lines 231-251 (the per-field ``print VALUES`` calls that,
    across one row, build up a single statement). The original prints the
    statement incrementally, field by field, with a lowercase two-line
    ``insert into ... \\nvalues (...)`` style. We build the same statement
    in one shot with an uppercase, single-line, semicolon-terminated style
    instead - a purely cosmetic modernization (it matches this repo's own
    reference ``mysqlInsertValues.sql`` formatting) that changes no data.
    """
    columns_clause = ", ".join(columns)
    values_clause = ", ".join(f"'{value}'" for value in values)
    return f"INSERT INTO {table_name} ({columns_clause}) VALUES ({values_clause});\n"


def render_create_table(
    table_name: str,
    columns: Sequence[ColumnStats],
    engine: str,
    charset: str,
) -> str:
    """Render the full ``CREATE TABLE ...;`` statement.

    Ports etl.pl lines 258-304.
    """
    column_lines = ",\n".join(f"  `{column.name}` {column.sql_type_clause()}" for column in columns)
    return (
        f"CREATE TABLE `{table_name}` (\n"
        f"{column_lines}\n"
        f") ENGINE={engine} DEFAULT CHARSET={charset};\n"
    )


class NasdaqPriceEtl:
    """Streaming CSV -> (CREATE TABLE, INSERT ...) SQL generator.

    This is the Python analogue of etl.pl end to end: read the header,
    stream data rows while writing INSERT statements and accumulating
    per-column type/size stats, then write out one CREATE TABLE statement
    once the whole file has been observed.
    """

    def __init__(self, config: ETLConfig | None = None) -> None:
        self.config = config or ETLConfig()

    @staticmethod
    def normalize_header(raw_fields: Iterable[str]) -> list[str]:
        """Clean up raw header cells into SQL-identifier-friendly column names.

        Ports etl.pl lines 25-44 (the header ``chop``/regex chain), but
        using ``csv.reader`` for the actual line splitting/dequoting
        (see module docstring #8 for why the original's three manual
        ``chop`` calls are not reproduced) and stripping surrounding
        whitespace/quotes per field rather than the original's single
        line-wide ``s/\"//`` (which only ever removed one stray double
        quote character from the whole line, wherever it happened to be).

        Spaces in a column name become underscores (etl.pl line 40,
        ``s/ /_/g``), replicated exactly since that's a real requirement
        for using the name unquoted-ish as a MySQL identifier.
        """
        return [raw.strip().strip('"').replace(" ", "_") for raw in raw_fields]

    @staticmethod
    def _align_row(row: Sequence[str], expected_len: int) -> list[str]:
        """Pad/truncate a data row to the header's column count.

        etl.pl's per-field loop (line 102: ``while ($field_count <=
        $Field_Names_Count)``) is bounded by the *header's* field count,
        not the row's own length. In Perl, reading past the end of
        ``@Field_Values`` yields ``undef``, which string-ops treat as ``""``.
        We replicate the net effect: rows shorter than the header are
        padded with empty strings, rows longer than the header have their
        extra trailing fields silently ignored.
        """
        values = list(row[:expected_len])
        if len(values) < expected_len:
            values.extend([""] * (expected_len - len(values)))
        return values

    def run(
        self,
        csv_path: Path = DEFAULT_CSV_PATH,
        schema_sql_path: Path = DEFAULT_SCHEMA_SQL_PATH,
        insert_sql_path: Path = DEFAULT_INSERT_SQL_PATH,
    ) -> ETLResult:
        """Execute the full ETL: stream INSERTs, then write CREATE TABLE.

        Ports the whole ``open ... while (<FILE>) { ... }`` body of
        etl.pl (lines 12-256) plus the trailing CREATE TABLE dump
        (lines 258-304), as one cohesive, testable method.
        """
        csv_path = Path(csv_path)
        schema_sql_path = Path(schema_sql_path)
        insert_sql_path = Path(insert_sql_path)

        with csv_path.open(newline="", encoding="utf-8") as csv_file:
            reader = csv.reader(csv_file)
            try:
                raw_header = next(reader)
            except StopIteration as exc:
                raise ValueError(f"{csv_path} is empty; expected a header row") from exc

            column_names = self.normalize_header(raw_header)
            columns = [ColumnStats(name=name) for name in column_names]

            row_count = 0
            with insert_sql_path.open("w", encoding="utf-8") as insert_file:
                for raw_row in reader:
                    if not raw_row:
                        continue  # skip genuinely blank lines (e.g. trailing EOF newline)

                    row_values = self._align_row(raw_row, len(columns))
                    insert_values: list[str] = []

                    for column, raw_value in zip(columns, row_values):
                        # etl.pl line 107: escape quotes before anything else.
                        escaped = escape_sql_literal(raw_value)
                        # etl.pl lines 110-114: blank -> "0" *for stats only*
                        # (see module docstring #1 for the full story on why
                        # the INSERT literal below deliberately does NOT
                        # follow suit).
                        stats_value = escaped if escaped else "0"
                        column.observe(stats_value)
                        insert_values.append(escaped)

                    insert_file.write(
                        render_insert_statement(self.config.table_name, column_names, insert_values)
                    )
                    row_count += 1

        schema_sql_path.write_text(
            render_create_table(self.config.table_name, columns, self.config.engine, self.config.charset),
            encoding="utf-8",
        )

        return ETLResult(row_count=row_count, column_count=len(columns))


def build_arg_parser() -> argparse.ArgumentParser:
    """Build the CLI argument parser for running this module as a script."""
    parser = argparse.ArgumentParser(
        description=(
            "Infer a MySQL schema from a CSV file and emit CREATE TABLE / "
            "INSERT SQL for it (Python port of the legacy etl.pl tool)."
        )
    )
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV_PATH, help="Path to the source CSV file.")
    parser.add_argument(
        "--schema-out", type=Path, default=DEFAULT_SCHEMA_SQL_PATH, help="Where to write the CREATE TABLE statement."
    )
    parser.add_argument(
        "--insert-out",
        type=Path,
        default=DEFAULT_INSERT_SQL_PATH,
        help="Where to write the INSERT statements.",
    )
    parser.add_argument("--table-name", default=DEFAULT_TABLE_NAME, help="Target table name.")
    parser.add_argument("--engine", default=DEFAULT_ENGINE, help="MySQL storage engine.")
    parser.add_argument("--charset", default=DEFAULT_CHARSET, help="MySQL default charset.")
    return parser


def main(argv: Sequence[str] | None = None) -> ETLResult:
    """CLI entry point: parse args, run the ETL, print the legacy-style summary."""
    args = build_arg_parser().parse_args(argv)
    config = ETLConfig(table_name=args.table_name, engine=args.engine, charset=args.charset)
    result = NasdaqPriceEtl(config).run(args.csv, args.schema_out, args.insert_out)
    # Mirrors etl.pl line 300: `print "Processed $column_count columns and $count lines.\n";`
    print(f"Processed {result.column_count} columns and {result.row_count} lines.")
    return result


if __name__ == "__main__":
    main()
