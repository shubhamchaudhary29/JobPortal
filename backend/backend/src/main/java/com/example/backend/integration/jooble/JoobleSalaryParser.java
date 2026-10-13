package com.example.backend.integration.jooble;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Jooble's free-text salary only when it is unambiguous: an explicit rupee currency, an explicit annual
 * period (or LPA), and one or two amounts. Everything else stays unparsed, because jobs store no currency and the
 * UI shows annual rupees.
 */
final class JoobleSalaryParser {
    private static final Pattern RUPEES = Pattern.compile("(₹|\\binr\\b|\\brs\\.?(?=\\s|\\d))");
    private static final Pattern ANNUAL = Pattern.compile("\\b(per\\s+(year|annum)|a\\s+year|annual(ly)?|p\\.?\\s?a\\.?|yearly|lpa)\\b");
    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(lpa|lakhs?|lacs?|l\\b|k\\b|crores?|cr\\b)?");

    private JoobleSalaryParser() { }

    private static double multiplier(String unit) {
        if (unit == null) return 1;
        if (unit.startsWith("l")) return 100_000;
        if (unit.startsWith("k")) return 1_000;
        if (unit.startsWith("c")) return 10_000_000;
        return 1;
    }

    static Double[] parse(String text) {
        if (text == null || text.isBlank()) return new Double[2];
        String lower = text.toLowerCase(Locale.ROOT);
        if (!RUPEES.matcher(lower).find() || !ANNUAL.matcher(lower).find()) return new Double[2];
        List<Double> amounts = new ArrayList<>();
        List<String> units = new ArrayList<>();
        Matcher matcher = AMOUNT.matcher(lower);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            if (value <= 0) continue;
            amounts.add(value);
            units.add(matcher.group(2));
        }
        if (amounts.isEmpty() || amounts.size() > 2) return new Double[2];
        // In "8 - 12 LPA" the unit written once applies to the whole range.
        String rangeUnit = units.get(units.size() - 1) != null ? units.get(units.size() - 1) : units.get(0);
        if (rangeUnit == null && lower.matches(".*\\blpa\\b.*")) rangeUnit = "lpa";
        for (int index = 0; index < amounts.size(); index++) {
            String unit = units.get(index) != null ? units.get(index) : amounts.get(index) < 1_000 ? rangeUnit : null;
            amounts.set(index, amounts.get(index) * multiplier(unit));
        }
        double min = Math.min(amounts.get(0), amounts.get(amounts.size() - 1));
        double max = Math.max(amounts.get(0), amounts.get(amounts.size() - 1));
        if (min < 10_000 || max > 1_000_000_000) return new Double[2];
        return amounts.size() == 1 ? new Double[] {min, null} : new Double[] {min, max};
    }
}
