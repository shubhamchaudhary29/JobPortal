package com.example.backend.integration.jooble;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JoobleSalaryParserTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', nullValues = "null", value = {
            "₹ 12,00,000 - ₹ 18,00,000 per year; 1200000; 1800000",
            "INR 600000 a year; 600000; null",
            "Rs. 5,00,000 - 7,00,000 per annum; 500000; 700000",
            "₹8 - ₹12 LPA; 800000; 1200000",
            "12 - 18 Lakhs per annum INR; 1200000; 1800000",
            "₹ 30,000 - ₹ 40,000 per month; null; null",
            "$90,000 - $120,000 per year; null; null",
            "17,600 UAH; null; null",
            "₹ 50,000; null; null",
            "Competitive salary per year in ₹; null; null",
            "₹ 1 - 2 - 3 per year; null; null",
            "null; null; null"
    })
    void parsesOnlyUnambiguousAnnualRupeeSalaries(String text, Double min, Double max) {
        Double[] parsed = JoobleSalaryParser.parse(text);
        assertEquals(min, parsed[0], text);
        assertEquals(max, parsed[1], text);
    }
}
