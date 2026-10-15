package com.example.backend.shared.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompanyNamesTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Acme Pvt Ltd|acme",
            "ACME|acme",
            "Acme Software Technologies Pvt. Ltd.|acme",
            "Larsen & Toubro|larsen and toubro",
            "Bundl Technologies Private Limited|swiggy",
            "Grofers India Pvt Ltd|blinkit",
            "Ｆｕｌｌｗｉｄｔｈ Ｃｏｒｐ|fullwidth corp",
            "C++ Labs|c++ labs",
    })
    void normalizesSuffixesAliasesAndUnicode(String company, String expected) {
        assertEquals(expected, CompanyNames.key(company));
    }

    @Test
    void blankAndNullAreEmpty() {
        assertEquals("", CompanyNames.key(null));
        assertEquals("", CompanyNames.key("   "));
        assertEquals("", CompanyNames.key("--"));
    }

    @Test
    void variantsOfOneCompanyShareAKey() {
        assertEquals(CompanyNames.key("ACME"), CompanyNames.key("Acme Pvt Ltd"));
        assertEquals(CompanyNames.key("One97 Communications Ltd"), CompanyNames.key("Paytm"));
    }
}
