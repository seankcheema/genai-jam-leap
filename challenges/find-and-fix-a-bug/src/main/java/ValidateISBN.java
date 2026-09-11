public class ValidateISBN {

	private static final int LONG_ISBN_MULTIPLIER = 10;
	private static final int SHORT_ISBN_MULTIPLIER = 11;
	private static final int SHORT_ISBN_LENGTH = 10;
	private static final int LONG_ISBN_LENGTH = 13;

	public boolean checkISBN(String isbn) {

		// Improvement: checkISBN() is a public entry point, so a null argument is a
		// realistic input (e.g. a caller reading an optional field from a form or a
		// database). Previously isbn.length() below would throw an undocumented
		// NullPointerException instead of the NumberFormatException that every other
		// invalid-input case throws, which is surprising for callers only expecting
		// NumberFormatException.
		if (isbn == null) {
			throw new NumberFormatException("ISBN number cannot be null");
		}

		if (isbn.length() == LONG_ISBN_LENGTH) {
			return isThisAValidLongISBN(isbn);
		}
		else if (isbn.length() == SHORT_ISBN_LENGTH) {
			return isThisAValidShortISBN(isbn);
		}
		throw new NumberFormatException("ISBN numbers must be 10 or 13 digits long");
	}

	// ISBN-10 check digit algorithm: each of the 10 characters is weighted by its
	// position (10 down to 1, left to right) and the weighted values are summed.
	// The ISBN is valid if that sum is exactly divisible by 11. The final character
	// is allowed to be the letter 'X', which represents the value 10 (this is how
	// the ISBN-10 standard encodes a check digit of 10, since a two-digit "10"
	// would not fit in a single character position).
	private boolean isThisAValidShortISBN(String isbn) {
		int total = 0;

		for (int i = 0; i < SHORT_ISBN_LENGTH; i++)
		{
			if (!Character.isDigit(isbn.charAt(i))) {
				if (i == 9 && isbn.charAt(i) == 'X') {
					// Weight at the last position (i = 9) is (SHORT_ISBN_LENGTH - i) = 1,
					// so this is equivalent to the old hard-coded "total += 10", but it is
					// now written the same way as the digit branch below so both paths
					// are obviously consistent with each other.
					total += 10 * (SHORT_ISBN_LENGTH - i);
				}
				else {
					throw new NumberFormatException(
							"ISBN numbers can only contain numeric digits (the final character of a 10-digit ISBN may also be 'X')");
				}
			}
			else {
				// BUG (this was the cause of the failing test): the original code did
				//     total += isbn.charAt(i) * (SHORT_ISBN_LENGTH - i);
				// `charAt(i)` returns a `char`, and in an arithmetic expression Java
				// implicitly widens that char to its numeric ASCII/Unicode code point
				// (e.g. '0' is 48, '7' is 55) rather than the digit's *value* (0-9).
				// So every digit was being weighted using the wrong number entirely.
				//
				// This bug did not show up in the "normal" 10-digit test cases because
				// the resulting error is a *constant* offset of 48 * sum(weights 1..10)
				// = 48 * 55 = 2640, and 2640 is itself an exact multiple of 11 (the
				// SHORT_ISBN_MULTIPLIER used in the "% 11 == 0" check below). Adding a
				// multiple of 11 never changes the result of "% 11", so purely-numeric
				// ISBNs happened to validate "correctly" by coincidence.
				//
				// The coincidence breaks for ISBNs ending in 'X', because the 'X' branch
				// above adds the real numeric value 10 directly, instead of going through
				// this char-code arithmetic. Mixing a char-code-based total for the first
				// 9 digits with a value-based total for a trailing 'X' throws the sum off
				// by an amount that is *not* a multiple of 11, so valid ISBNs ending in
				// 'X' (e.g. "012000030X") were incorrectly rejected.
				//
				// The fix: convert the character to its actual digit value with
				// Character.getNumericValue() before weighting it, so the arithmetic is
				// correct by construction rather than by a fragile numerical coincidence.
				total += Character.getNumericValue(isbn.charAt(i)) * (SHORT_ISBN_LENGTH - i);
			}
		}

		return (total % SHORT_ISBN_MULTIPLIER == 0);
	}

	// ISBN-13/EAN-13 check digit algorithm: each of the 13 digits is weighted
	// alternately by 1 and 3 (starting with 1 at position 0), the weighted values
	// are summed, and the ISBN is valid if that sum is exactly divisible by 10.
	// Unlike ISBN-10, there is no 'X' check digit for ISBN-13 - every character
	// must be a digit.
	private boolean isThisAValidLongISBN(String isbn) {
		int total = 0;

		for (int i = 0; i < LONG_ISBN_LENGTH; i++) {
			// Improvement: the original code had no validation at all here - it read
			// isbn.charAt(i) and fed it straight into the arithmetic below even if it
			// was a letter or symbol, silently producing a meaningless result instead
			// of raising an exception the way isThisAValidShortISBN() does for bad
			// input. That was also an accuracy problem for the exception Javadoc/README
			// question: "ISBN numbers can only contain numeric digits" was only ever
			// enforced for 10-digit ISBNs, not 13-digit ones.
			if (!Character.isDigit(isbn.charAt(i))) {
				throw new NumberFormatException("ISBN numbers can only contain numeric digits");
			}

			// Same underlying bug class as isThisAValidShortISBN(): using the raw char
			// (its ASCII code point) instead of Character.getNumericValue() to get the
			// digit's actual value. For this method it happened to still produce the
			// right pass/fail answer for well-formed numeric input - the constant
			// offset introduced (48 * sum(weights 0..12) = 48 * 25 = 1200) is also an
			// exact multiple of 10 (LONG_ISBN_MULTIPLIER) - but relying on that
			// coincidence is fragile and it is fixed here for correctness and
			// consistency with isThisAValidShortISBN().
			int digit = Character.getNumericValue(isbn.charAt(i));
			if (i % 2 == 0) {
				total += digit;
			}
			else {
				total += digit * 3;
			}
		}
		return (total % LONG_ISBN_MULTIPLIER == 0);
	}
}
