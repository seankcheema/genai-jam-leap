"""Unit tests for etl.py, the Python port of the legacy etl.pl ETL script.

Tests are grouped to mirror the module docstring in etl.py:

* Header parsing/normalization.
* ColumnStats.observe type inference (int / decimal / varchar / quirks).
* The empty-value "0"-for-stats-vs-blank-for-INSERT asymmetry.
* Multi-row max-length / decimal precision tracking.
* End-to-end CSV -> SQL generation against small, precise synthetic fixtures.

Each test builds tiny, hand-crafted inputs so the expected output can be
reasoned about exactly, rather than asserting against the large real
prices.csv (which is used only for a separate manual end-to-end sanity
check, not for these unit tests).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from etl import (
    ColumnStats,
    ColumnType,
    ETLConfig,
    NasdaqPriceEtl,
    escape_sql_literal,
    render_create_table,
    render_insert_statement,
)


# ---------------------------------------------------------------------------
# Header parsing / normalization
# ---------------------------------------------------------------------------


class TestNormalizeHeader:
    def test_simple_header_is_unchanged(self) -> None:
        assert NasdaqPriceEtl.normalize_header(["ticker", "date", "open"]) == ["ticker", "date", "open"]

    def test_spaces_become_underscores(self) -> None:
        # etl.pl line 40: `$columns =~ s/ /_/g;`
        assert NasdaqPriceEtl.normalize_header(["closing price", "trade date"]) == [
            "closing_price",
            "trade_date",
        ]

    def test_surrounding_double_quotes_are_stripped(self) -> None:
        # Generalizes etl.pl's crude single-stray-quote-removal (line 35)
        # to properly strip wrapping quotes from any/all header fields.
        assert NasdaqPriceEtl.normalize_header(['"ticker"', 'date']) == ["ticker", "date"]

    def test_surrounding_whitespace_is_stripped(self) -> None:
        assert NasdaqPriceEtl.normalize_header([" ticker ", " date"]) == ["ticker", "date"]

    def test_last_column_name_is_not_truncated(self) -> None:
        # Regression test for the bug documented in etl.py's module
        # docstring point 8: the original's third, spurious `chop` on the
        # header line truncated the LAST column's name by one character
        # (e.g. "vol" -> "vo") on a CRLF file. Our csv-module-based parsing
        # must not reproduce that.
        assert NasdaqPriceEtl.normalize_header(["ticker", "date", "vol"])[-1] == "vol"


# ---------------------------------------------------------------------------
# ColumnStats.observe: int / decimal / varchar inference
# ---------------------------------------------------------------------------


class TestColumnStatsIntInference:
    def test_all_digit_values_infer_int(self) -> None:
        stats = ColumnStats(name="vol")
        for value in ("200", "16400", "1300"):
            stats.observe(value)
        assert stats.type is ColumnType.INT

    def test_int_tracks_max_length_across_rows(self) -> None:
        stats = ColumnStats(name="vol")
        for value in ("200", "16400000", "7"):
            stats.observe(value)
        assert stats.type is ColumnType.INT
        assert stats.max_length == len("16400000")

    def test_single_digit_value_is_int(self) -> None:
        stats = ColumnStats(name="x")
        stats.observe("2")
        assert stats.type is ColumnType.INT
        assert stats.max_length == 1


class TestColumnStatsDecimalInference:
    def test_single_dot_value_infers_decimal(self) -> None:
        stats = ColumnStats(name="open")
        stats.observe("9.73")
        assert stats.type is ColumnType.DECIMAL
        assert stats.max_int_digits == 1
        assert stats.max_frac_digits == 2

    def test_decimal_precision_tracks_max_across_rows(self) -> None:
        stats = ColumnStats(name="open")
        for value in ("1.5", "12.75", "100.2"):
            stats.observe(value)
        assert stats.type is ColumnType.DECIMAL
        # Widest integer part is "100" (3 digits), widest fractional part
        # is "75"/"20" (2 digits) - independently tracked maxima, matching
        # etl.pl's decimal_length1 / decimal_length2 semantics.
        assert stats.max_int_digits == 3
        assert stats.max_frac_digits == 2

    def test_decimal_is_not_downgraded_by_a_later_plain_int_value(self) -> None:
        # etl.pl line 136: int is only assigned "if type != decimal" -
        # a column already classified decimal stays decimal even if a
        # later value happens to have no dot.
        stats = ColumnStats(name="close")
        stats.observe("18.2933")
        stats.observe("42")
        assert stats.type is ColumnType.DECIMAL

    def test_decimal_scale_and_precision_used_for_sql_clause(self) -> None:
        stats = ColumnStats(name="open")
        stats.observe("343.25")
        # precision = int digits + frac digits, scale = frac digits,
        # matching etl.pl's `$decimal_length1 + $decimal_length2` / `$decimal_length2`.
        assert stats.sql_type_clause() == "decimal(5,2)"


class TestColumnStatsVarcharInference:
    def test_letters_infer_varchar(self) -> None:
        stats = ColumnStats(name="ticker")
        stats.observe("AAPL")
        assert stats.type is ColumnType.VARCHAR
        assert stats.max_length == 4

    def test_varchar_tracks_max_length_across_rows(self) -> None:
        stats = ColumnStats(name="ticker")
        for value in ("AAC", "AACOW", "AA"):
            stats.observe(value)
        assert stats.max_length == len("AACOW")

    def test_more_than_one_dot_is_varchar_not_decimal(self) -> None:
        # Preserved quirk from etl.pl lines 155-170.
        stats = ColumnStats(name="weird")
        stats.observe("1.2.3")
        assert stats.type is ColumnType.VARCHAR
        assert stats.max_length == len("1.2.3")

    def test_non_digit_non_dot_symbol_infers_varchar(self) -> None:
        # e.g. a value like "-5" or "N/A": no letters, but a symbol outside
        # [0-9.] forces varchar (etl.pl lines 194-205).
        stats = ColumnStats(name="weird")
        stats.observe("-5")
        assert stats.type is ColumnType.VARCHAR

    def test_varchar_is_sticky_once_set_by_letters(self) -> None:
        stats = ColumnStats(name="mixed")
        stats.observe("N/A")  # letters -> varchar
        stats.observe("123")  # purely numeric, but must not un-set varchar
        assert stats.type is ColumnType.VARCHAR

    def test_varchar_is_sticky_regardless_of_value_order(self) -> None:
        stats = ColumnStats(name="mixed")
        stats.observe("123")
        stats.observe("456")
        stats.observe("ABC")  # now becomes varchar
        stats.observe("7")  # must stay varchar
        assert stats.type is ColumnType.VARCHAR
        assert stats.max_length == len("ABC")


# ---------------------------------------------------------------------------
# Empty-value handling
# ---------------------------------------------------------------------------


class TestEmptyValueHandling:
    def test_blank_field_contributes_zero_for_stats(self) -> None:
        # A column that is blank on every row should still be classified,
        # via the "" -> "0" stats substitution (etl.pl lines 110-114).
        stats = ColumnStats(name="maybe_blank")
        stats.observe("0")  # caller is responsible for the "" -> "0" substitution
        assert stats.type is ColumnType.INT
        assert stats.max_length == 1

    def test_end_to_end_blank_field_is_int_in_schema_but_empty_in_insert(
        self, tmp_path: Path
    ) -> None:
        csv_path = tmp_path / "prices.csv"
        csv_path.write_text("ticker,note\nAAPL,\nMSFT,\n", encoding="utf-8", newline="")
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        result = NasdaqPriceEtl().run(csv_path, schema_out, insert_out)

        assert result.row_count == 2
        schema_sql = schema_out.read_text(encoding="utf-8")
        insert_sql = insert_out.read_text(encoding="utf-8")

        # Stats saw "0" for every blank -> inferred as int(1).
        assert "`note` int(1)" in schema_sql
        # But the literal written into each INSERT is genuinely blank.
        assert "VALUES ('AAPL', '');" in insert_sql
        assert "VALUES ('MSFT', '');" in insert_sql


# ---------------------------------------------------------------------------
# SQL literal escaping
# ---------------------------------------------------------------------------


class TestEscapeSqlLiteral:
    def test_single_quote_is_backslash_escaped(self) -> None:
        assert escape_sql_literal("O'Brien") == "O\\'Brien"

    def test_value_without_quotes_is_unchanged(self) -> None:
        assert escape_sql_literal("AAPL") == "AAPL"

    def test_multiple_quotes_are_all_escaped(self) -> None:
        assert escape_sql_literal("'a'b'") == "\\'a\\'b\\'"


# ---------------------------------------------------------------------------
# Rendering helpers
# ---------------------------------------------------------------------------


class TestRenderInsertStatement:
    def test_basic_rendering(self) -> None:
        stmt = render_insert_statement("nasdaq_prices", ["ticker", "vol"], ["AAPL", "100"])
        assert stmt == "INSERT INTO nasdaq_prices (ticker, vol) VALUES ('AAPL', '100');\n"


class TestRenderCreateTable:
    def test_basic_rendering(self) -> None:
        ticker = ColumnStats(name="ticker", type=ColumnType.VARCHAR, max_length=4)
        vol = ColumnStats(name="vol", type=ColumnType.INT, max_length=3)
        sql = render_create_table("nasdaq_prices", [ticker, vol], "InnoDB", "latin1")
        assert sql == (
            "CREATE TABLE `nasdaq_prices` (\n"
            "  `ticker` varchar(4),\n"
            "  `vol` int(3)\n"
            ") ENGINE=InnoDB DEFAULT CHARSET=latin1;\n"
        )

    def test_decimal_column_rendering(self) -> None:
        price = ColumnStats(name="open", type=ColumnType.DECIMAL, max_int_digits=3, max_frac_digits=2)
        sql = render_create_table("t", [price], "InnoDB", "latin1")
        assert "`open` decimal(5,2)" in sql

    def test_never_observed_column_falls_back_to_varchar_zero(self) -> None:
        empty_col = ColumnStats(name="mystery")
        sql = render_create_table("t", [empty_col], "InnoDB", "latin1")
        assert "`mystery` varchar(0)" in sql


# ---------------------------------------------------------------------------
# End-to-end CSV -> SQL generation
# ---------------------------------------------------------------------------


class TestEndToEnd:
    def _write_csv(self, tmp_path: Path, text: str) -> Path:
        csv_path = tmp_path / "prices.csv"
        # newline="" so we control line endings exactly (CRLF, matching the
        # real prices.csv, to also exercise that code path).
        csv_path.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
        return csv_path

    def test_small_mixed_type_csv(self, tmp_path: Path) -> None:
        csv_text = (
            "ticker,date,open,high,low,close,vol\n"
            "AAC,20110112,9.73,9.73,9.73,9.73,200\n"
            "AACOW,20110112,0.35,0.35,0.35,0.35,700\n"
            "AAON,20110112,18.3,18.54,18.2,18.2933,43100\n"
        )
        csv_path = self._write_csv(tmp_path, csv_text)
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        result = NasdaqPriceEtl().run(csv_path, schema_out, insert_out)

        assert result.row_count == 3
        assert result.column_count == 7

        schema_sql = schema_out.read_text(encoding="utf-8")
        assert "CREATE TABLE `nasdaq_prices` (" in schema_sql
        assert "`ticker` varchar(5)" in schema_sql  # "AACOW" is 5 chars, the longest
        assert "`date` int(8)" in schema_sql
        # close sees 4 fractional digits ("18.2933") -> precision widens accordingly.
        assert "`close` decimal(6,4)" in schema_sql
        assert "`vol` int(5)" in schema_sql  # "43100" is 5 digits
        assert schema_sql.rstrip().endswith(
            ") ENGINE=InnoDB DEFAULT CHARSET=latin1;"
        )

        insert_sql = insert_out.read_text(encoding="utf-8")
        lines = insert_sql.strip().splitlines()
        assert len(lines) == 3
        assert lines[0] == (
            "INSERT INTO nasdaq_prices (ticker, date, open, high, low, close, vol) "
            "VALUES ('AAC', '20110112', '9.73', '9.73', '9.73', '9.73', '200');"
        )
        assert lines[2] == (
            "INSERT INTO nasdaq_prices (ticker, date, open, high, low, close, vol) "
            "VALUES ('AAON', '20110112', '18.3', '18.54', '18.2', '18.2933', '43100');"
        )

    def test_ragged_rows_are_padded_and_truncated(self, tmp_path: Path) -> None:
        # Second row is missing the last column; third row has an extra
        # trailing column. Mirrors etl.pl's field loop being bounded by the
        # header's column count (see NasdaqPriceEtl._align_row).
        csv_text = "a,b,c\n" "1,2,3\n" "4,5\n" "6,7,8,9\n"
        csv_path = self._write_csv(tmp_path, csv_text)
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        NasdaqPriceEtl().run(csv_path, schema_out, insert_out)

        insert_sql = insert_out.read_text(encoding="utf-8")
        lines = insert_sql.strip().splitlines()
        assert lines[1] == "INSERT INTO nasdaq_prices (a, b, c) VALUES ('4', '5', '');"
        assert lines[2] == "INSERT INTO nasdaq_prices (a, b, c) VALUES ('6', '7', '8');"

    def test_quote_in_value_is_escaped_in_insert_output(self, tmp_path: Path) -> None:
        csv_text = 'name,vol\n' "O'Brien,100\n"
        csv_path = self._write_csv(tmp_path, csv_text)
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        NasdaqPriceEtl().run(csv_path, schema_out, insert_out)

        insert_sql = insert_out.read_text(encoding="utf-8")
        assert "O\\'Brien" in insert_sql

    def test_custom_table_name_engine_and_charset(self, tmp_path: Path) -> None:
        csv_path = self._write_csv(tmp_path, "a\n1\n")
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        config = ETLConfig(table_name="widgets", engine="MyISAM", charset="utf8mb4")
        NasdaqPriceEtl(config).run(csv_path, schema_out, insert_out)

        schema_sql = schema_out.read_text(encoding="utf-8")
        insert_sql = insert_out.read_text(encoding="utf-8")
        assert "CREATE TABLE `widgets`" in schema_sql
        assert "ENGINE=MyISAM DEFAULT CHARSET=utf8mb4;" in schema_sql
        assert insert_sql.startswith("INSERT INTO widgets")

    def test_missing_csv_header_raises(self, tmp_path: Path) -> None:
        csv_path = tmp_path / "empty.csv"
        csv_path.write_text("", encoding="utf-8")
        with pytest.raises(ValueError):
            NasdaqPriceEtl().run(csv_path, tmp_path / "s.sql", tmp_path / "i.sql")

    def test_header_only_csv_produces_zero_rows_and_fallback_columns(self, tmp_path: Path) -> None:
        csv_path = self._write_csv(tmp_path, "a,b\n")
        schema_out = tmp_path / "schema.sql"
        insert_out = tmp_path / "insert.sql"

        result = NasdaqPriceEtl().run(csv_path, schema_out, insert_out)

        assert result.row_count == 0
        assert result.column_count == 2
        assert insert_out.read_text(encoding="utf-8") == ""
        schema_sql = schema_out.read_text(encoding="utf-8")
        assert "`a` varchar(0)" in schema_sql
        assert "`b` varchar(0)" in schema_sql
