import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ValidateISBNTest {

	@Test
	public void checkAValid10DigitISBN() {
		ValidateISBN validator = new ValidateISBN();
		boolean result = validator.checkISBN("0140449116");
		assertTrue(result,"first value");
		result = validator.checkISBN("0140177396");
		assertTrue(result, "second value");
	}
	
	@Test
	public void checkAValid13DigitISBN() {
		ValidateISBN validator = new ValidateISBN();
		boolean result = validator.checkISBN("9781853260087");
		assertTrue(result,"first value");
		result = validator.checkISBN("9781853267338");
		assertTrue(result, "second value");
	}
	
	@Test
	public void TenDigitISBNNumbersEndingInAnXAreValid() {
		ValidateISBN validator = new ValidateISBN();
		boolean result = validator.checkISBN("012000030X");
		assertTrue(result);
	}

	@Test
	public void checkAnInvalid10DigitISBN() {
		ValidateISBN validator = new ValidateISBN();
		boolean result = validator.checkISBN("0140449117");
		assertFalse(result);
	}
	
	@Test
	public void checkAnInvalid13DigitISBN() {
		ValidateISBN validator = new ValidateISBN();
		boolean result = validator.checkISBN("9781853267336");
		assertFalse(result);
	}
	
	@Test
	public void nineDigitISBNsAreNotAllowed() {
		ValidateISBN validator = new ValidateISBN();
		assertThrows(NumberFormatException.class, 
				() -> {
					validator.checkISBN("123456789");
				});
	}
	
	@Test
	public void nonNumericISBNsAreNotAllowed() {
		ValidateISBN validator = new ValidateISBN();
		assertThrows(NumberFormatException.class,
				() -> {
					validator.checkISBN("helloworld");
				});
	}

	// --- Additional tests added while fixing the checksum bug ---
	// The original test suite only exercised a 10-digit ISBN ending in "X" where
	// every other digit was 0 (012000030X). Because the char-code bug's error term
	// is a fixed offset, that test alone couldn't distinguish "totally broken X
	// handling" from "works except when other digits are non-zero" - it happened to
	// pass or fail as a block. These extra cases use real, varied digits around the
	// 'X' check digit so a regression here is actually caught.
	@Test
	public void tenDigitISBNWithNonZeroDigitsEndingInAnXAreValid() {
		ValidateISBN validator = new ValidateISBN();
		// A real ISBN-10 (Programming Perl) whose check digit is X.
		assertTrue(validator.checkISBN("080442957X"));
	}

	@Test
	public void tenDigitISBNWithNonZeroDigitsEndingInAnXCanBeInvalid() {
		ValidateISBN validator = new ValidateISBN();
		// Same as above but with one digit changed, so the checksum no longer holds.
		assertFalse(validator.checkISBN("080442958X"));
	}

	@Test
	public void lowercaseXIsNotAValidCheckDigit() {
		// The ISBN-10 standard's check-digit letter is upper-case 'X'; this locks in
		// the existing (and correct) behaviour that a lower-case 'x' is rejected as
		// a non-numeric character rather than silently being treated the same as 'X'.
		ValidateISBN validator = new ValidateISBN();
		assertThrows(NumberFormatException.class,
				() -> {
					validator.checkISBN("012000030x");
				});
	}

	@Test
	public void thirteenDigitISBNsWithNonDigitCharactersAreNotAllowed() {
		// Before this fix, isThisAValidLongISBN() had no digit validation at all and
		// would silently use the character's code point in the checksum instead of
		// throwing - this test guards against that regression.
		ValidateISBN validator = new ValidateISBN();
		assertThrows(NumberFormatException.class,
				() -> {
					validator.checkISBN("978185326X087");
				});
	}

	@Test
	public void nullISBNsAreNotAllowed() {
		ValidateISBN validator = new ValidateISBN();
		assertThrows(NumberFormatException.class,
				() -> {
					validator.checkISBN(null);
				});
	}

}

