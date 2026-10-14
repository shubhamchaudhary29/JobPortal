package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.job.domain.GeoPoint;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.GeoStatus;
import com.example.backend.job.domain.LocationHint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves a free-text job location (plus an optional provider hint) to gazetteer places, offline.
 * Pure and thread-safe; never throws. Split candidates on {@code / ; | &} and " or "; commas inside a
 * candidate are a hierarchy ("Bengaluru, Karnataka, India") unless every segment is a major city.
 */
@Component
public class GeoResolver {
    public static final int PARSER_VERSION = 1;
    static final int MAX_INPUT_LENGTH = 300;
    static final int MAX_PLACES = 10;
    static final long LIST_MODE_MIN_POPULATION = 300_000;
    static final long ALTERNATE_NAME_MIN_POPULATION = 50_000;
    /** A state/UT name that is also a same-state city this large is a place (Delhi, Puducherry, Chandigarh). */
    static final long CITY_STATE_MIN_POPULATION = 300_000;

    private static final Pattern POSTAL_CODE = Pattern.compile("\\b\\d{6}\\b");
    private static final Pattern WORK_MODE_PARENTHETICAL = Pattern.compile(
            "\\(\\s*(hybrid|remote|fully remote|on[- ]?site|in[- ]?office|office|wfh|work from home)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORK_MODE_PREFIX = Pattern.compile(
            "^\\s*(hybrid|on[- ]?site|in[- ]?office|office)\\s*[-:\u2013\u2014]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE_PREFIX = Pattern.compile(
            "^\\s*(?:fully remote|remote|work from home|wfh)\\b[\\s\\-:,\u2013\u2014]*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CANDIDATE_SEPARATOR = Pattern.compile("\\s*(?:[/;|&]|\\s+or\\s+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> REMOTE_WORDS = Set.of("remote", "fully remote", "work from home", "wfh", "anywhere");
    private static final Set<String> WORK_MODE_WORDS = Set.of("hybrid", "onsite", "on site", "office", "in office");
    private static final Set<String> COUNTRY_PHRASES = Set.of("pan india", "anywhere in india", "all india",
            "across india");

    private final Gazetteer gazetteer;
    private final String version;

    public GeoResolver(Gazetteer gazetteer) {
        this.gazetteer = gazetteer;
        this.version = PARSER_VERSION + "-" + gazetteer.dataHash();
    }

    /** Parser version plus data hash: editing any data file changes it, which marks old resolutions stale. */
    public String version() { return version; }

    public GeoResolution resolve(String raw, LocationHint hint) {
        try {
            return resolveSafely(raw, hint);
        } catch (RuntimeException unexpected) {
            return new GeoResolution(GeoStatus.UNRESOLVED, List.of(), false, null, version);
        }
    }

    private GeoResolution resolveSafely(String raw, LocationHint hint) {
        Signals signals = new Signals();
        GeoPoint point = hint == null ? null : pointInIndia(hint.lat(), hint.lng());
        if (hint != null) applyHint(hint, signals);
        boolean blank = raw == null || raw.isBlank();
        if (blank && !signals.any()) return new GeoResolution(GeoStatus.EMPTY, List.of(), false, point, version);
        if (signals.places.isEmpty() && !blank) parse(raw.length() > MAX_INPUT_LENGTH
                ? raw.substring(0, MAX_INPUT_LENGTH) : raw, signals);
        List<String> placeIds = signals.places.stream().limit(MAX_PLACES).toList();
        return new GeoResolution(signals.status(), placeIds, signals.remote, point, version);
    }

    private void applyHint(LocationHint hint, Signals signals) {
        List<String> area = hint.area();
        if (area.isEmpty()) return;
        String admin1 = null;
        boolean anyState = false;
        boolean onlyIndia = true;
        for (String element : area) {
            String key = Gazetteer.key(element);
            if (!gazetteer.isIndia(key)) onlyIndia = false;
            if (isPureState(key)) {
                anyState = true;
                if (admin1 == null) admin1 = gazetteer.stateCode(key).orElse(null);
            }
        }
        // The most specific element is last; fall back towards the root when it is a locality we do not know.
        for (int index = area.size() - 1; index >= 0; index--) {
            String key = Gazetteer.key(area.get(index));
            if (key.isEmpty() || gazetteer.isIndia(key) || isPureState(key)) continue;
            Place place = lookup(key, admin1);
            if (place != null) { signals.places.add(place.id()); return; }
        }
        if (anyState) signals.state = true;
        else if (onlyIndia) signals.country = true;
    }

    private void parse(String raw, Signals signals) {
        String text = POSTAL_CODE.matcher(raw).replaceAll(" ");
        Matcher workMode = WORK_MODE_PARENTHETICAL.matcher(text);
        StringBuilder stripped = new StringBuilder();
        while (workMode.find()) {
            if (isRemoteWord(workMode.group(1))) signals.remote = true;
            workMode.appendReplacement(stripped, " ");
        }
        workMode.appendTail(stripped);
        text = stripped.toString();
        // "Jammu & Kashmir" / "Daman and Diu" must not be split on '&' into separate places.
        if (isPureState(Gazetteer.key(text))) { signals.state = true; return; }
        for (String candidate : CANDIDATE_SEPARATOR.split(text)) {
            if (!candidate.isBlank()) candidate(candidate, signals);
        }
    }

    private void candidate(String raw, Signals signals) {
        String text = WORK_MODE_PREFIX.matcher(raw).replaceFirst("");
        String key = Gazetteer.key(text);
        if (key.isEmpty() || WORK_MODE_WORDS.contains(key)) return;
        if (COUNTRY_PHRASES.contains(key) || gazetteer.isIndia(key)) { signals.country = true; return; }
        if (REMOTE_WORDS.contains(key)) { signals.remote = true; return; }
        Matcher remote = REMOTE_PREFIX.matcher(text);
        if (remote.matches()) {
            signals.remote = true;
            String rest = remote.group(1);
            String restKey = Gazetteer.key(rest);
            if (restKey.isEmpty() || gazetteer.isIndia(restKey) || gazetteer.isForeign(restKey)) return;
            text = rest;
            key = restKey;
        }
        if (isPureState(key)) { signals.state = true; return; }
        if (gazetteer.isForeign(key)) { signals.foreign = true; return; }
        segments(text, signals);
    }

    private void segments(String candidate, Signals signals) {
        List<String> segments = new ArrayList<>();
        for (String segment : candidate.split(",")) {
            String key = Gazetteer.key(segment);
            if (key.isEmpty() || WORK_MODE_WORDS.contains(key)) continue;
            if (REMOTE_WORDS.contains(key)) { signals.remote = true; continue; }
            segments.add(key);
        }
        if (segments.isEmpty()) return;
        for (int index = 1; index < segments.size(); index++) {
            if (gazetteer.isForeign(segments.get(index))) { signals.foreign = true; return; }
        }
        if (segments.size() >= 2 && segments.stream().allMatch(this::isMajorCity)) {
            segments.forEach(segment -> signals.places.add(lookup(segment, null).id()));
            return;
        }
        String admin1 = null;
        for (int index = 1; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (gazetteer.isIndia(segment)) continue;
            String state = gazetteer.stateCode(segment).orElse(null);
            if (state != null) { if (admin1 == null) admin1 = state; continue; }
            // Every qualifier must be Indian; anything else ("Delhi, NY") is not a place we can trust.
            if (lookup(segment, null) == null) return;
        }
        Place place = lookup(String.join(" ", segments), admin1);
        for (int index = 0; place == null && index < segments.size(); index++) {
            String segment = segments.get(index);
            if (gazetteer.isIndia(segment) || gazetteer.isForeign(segment) || isPureState(segment)) continue;
            place = lookup(segment, admin1);
        }
        if (place != null) signals.places.add(place.id());
        else if (segments.stream().anyMatch(this::isPureState)) signals.state = true;
        else if (segments.stream().anyMatch(gazetteer::isIndia)) signals.country = true;
    }

    private boolean isMajorCity(String key) {
        if (isPureState(key) || gazetteer.isIndia(key) || gazetteer.isForeign(key)) return false;
        Place place = lookupNamed(key, null);
        return place != null && place.population() >= LIST_MODE_MIN_POPULATION;
    }

    private boolean isPureState(String key) {
        String code = gazetteer.stateCode(key).orElse(null);
        if (code == null) return false;
        Place city = lookupNamed(key, code);
        return city == null || !code.equals(city.admin1()) || city.population() < CITY_STATE_MIN_POPULATION;
    }

    /** Alias, then primary name, then alternate name (large places only); state-filtered first when known. */
    private Place lookup(String key, String admin1) {
        if (admin1 != null) {
            Place filtered = lookupTiers(key, admin1, true);
            if (filtered != null) return filtered;
        }
        return lookupTiers(key, null, true);
    }

    private Place lookupNamed(String key, String admin1) {
        if (admin1 != null) {
            Place filtered = lookupTiers(key, admin1, false);
            if (filtered != null) return filtered;
        }
        return lookupTiers(key, null, false);
    }

    private Place lookupTiers(String key, String admin1, boolean includeAlternates) {
        Place place = best(gazetteer.aliasMatches(key), admin1, 0);
        if (place == null) place = best(gazetteer.primaryMatches(key), admin1, 0);
        if (place == null && includeAlternates) {
            place = best(gazetteer.alternateMatches(key), admin1, ALTERNATE_NAME_MIN_POPULATION);
        }
        return place;
    }

    private static Place best(List<Place> candidates, String admin1, long minimumPopulation) {
        return candidates.stream()
                .filter(place -> admin1 == null || admin1.equals(place.admin1()))
                .filter(place -> place.population() >= minimumPopulation)
                .min(Comparator.comparingLong(Place::population).reversed()
                        .thenComparingLong(place -> Long.parseLong(place.id())))
                .orElse(null);
    }

    private static GeoPoint pointInIndia(Double lat, Double lng) {
        if (lat == null || lng == null || lat.isNaN() || lng.isNaN()) return null;
        return lat >= 6 && lat <= 37.5 && lng >= 68 && lng <= 97.5 ? new GeoPoint(lat, lng) : null;
    }

    private static boolean isRemoteWord(String word) {
        String key = Gazetteer.key(word);
        return key.equals("remote") || key.equals("fully remote") || key.equals("wfh") || key.equals("work from home");
    }

    private static final class Signals {
        final Set<String> places = new LinkedHashSet<>();
        boolean remote;
        boolean state;
        boolean country;
        boolean foreign;

        boolean any() { return !places.isEmpty() || remote || state || country || foreign; }

        GeoStatus status() {
            if (!places.isEmpty()) return GeoStatus.PLACE;
            if (remote) return GeoStatus.REMOTE;
            if (state) return GeoStatus.STATE;
            if (country) return GeoStatus.COUNTRY;
            if (foreign) return GeoStatus.FOREIGN;
            return GeoStatus.UNRESOLVED;
        }
    }
}
