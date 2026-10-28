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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves a free-text job location (plus an optional provider hint) to gazetteer places, offline.
 * Pure and thread-safe; never throws. Split candidates on {@code / ; | &} and " or "; commas inside a
 * candidate are a hierarchy ("Bengaluru, Karnataka, India") unless every segment is a major city.
 * A remote marker is scoped by its own candidate: "Remote - United States" or "United States (Remote)" is
 * foreign-only remote (FOREIGN when nothing else places the job); "Remote" and "Remote - India" stay REMOTE.
 *
 * <p>Localities (GEO-8): once a candidate has a city, its other segments are looked up as OpenStreetMap localities of
 * that city only ("Whitefield, Bengaluru"); a locality may be fused with the city ("Noida Sector 62", "Gurgaon Cyber
 * City"). A locality alone ("Whitefield") resolves only through the curated {@code locality-aliases.tsv}. An Adzuna
 * point at least {@value #SNAP_MIN_CENTRE_KM} km from its single city's centre snaps to a locality within
 * {@value #SNAP_MAX_KM} km; the raw point is never a pin. Every locality belongs to one of the resolved places.
 *
 * <p>Site labels (GEO-9A): ATS feeds name the office after the city ("Mumbai, Urmi", "Bengaluru- We Work"). After a
 * large city, an unknown comma segment is kept as the job's {@code siteLabel} instead of discarding the whole location;
 * a candidate that placed nothing is retried split on {@code - – — ~ :} ("India-Bangalore"). The label is text only,
 * never geocoded, and only a fallback: anything that resolved before GEO-9 resolves to the same places.
 *
 * <p>Locality evidence (GEO-10): when the text names no locality, a PIN code in the text
 * ({@code in-pincode-localities.tsv}), the description ({@link DescriptionLocationScanner}) and the provider point
 * ({@link #snap}) may each name one locality of a resolved place. A locality is added only when that evidence agrees
 * on exactly one; disagreement keeps the job at the city. None of them is ever a point of its own.
 */
@Component
public class GeoResolver {
    /**
     * 2: remote markers scoped to a foreign country with no Indian location are FOREIGN, not REMOTE (GEO-6A).
     * 3: OpenStreetMap localities inside the resolved city, curated locality aliases, Adzuna point snapping (GEO-8B).
     * 4: edge-city split and City, Site labels; siteLabel (GEO-9A).
     * 5: PIN code, description and curated tech-park/corridor localities; office kinds are no site label (GEO-10).
     */
    public static final int PARSER_VERSION = 5;
    static final int MAX_INPUT_LENGTH = 300;
    static final int MAX_PLACES = 10;
    static final int MAX_LOCALITIES = 10;
    static final double SNAP_MIN_CENTRE_KM = 1.5;
    static final double SNAP_MAX_KM = 2.0;
    /** A leftmost place smaller than this gives way to a later city that has it as a locality ("Powai, Mumbai"). */
    static final long LOCALITY_PLACE_MAX_POPULATION = 100_000;
    static final long LIST_MODE_MIN_POPULATION = 300_000;
    static final long ALTERNATE_NAME_MIN_POPULATION = 50_000;
    /** A state/UT name that is also a same-state city this large is a place (Delhi, Puducherry, Chandigarh). */
    static final long CITY_STATE_MIN_POPULATION = 300_000;
    static final int MAX_SITE_LABEL_LENGTH = 80;

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
    /** Separators of the edge-city split ("Bengaluru- We Work", "India-Bangalore", "BENGALURU-BLDG 1 ~ Hitech"). */
    private static final Pattern EDGE_SEPARATOR = Pattern.compile("\\s*[-\u2013\u2014~:]\\s*");
    private static final Pattern EDGE_SEPARATOR_PRESENT = Pattern.compile("[-\u2013\u2014~:]");
    private static final Set<String> COUNTRY_TOKENS = Set.of("ind", "in", "india");
    private static final Pattern SITE_LABEL_EDGES = Pattern.compile("^[\\s\\-\u2013\u2014~:,/]+|[\\s\\-\u2013\u2014~:,/]+$");
    /**
     * Generic words an ATS puts where a site would go; never a site label. Office kinds ("Head Office", "HQ") name no
     * particular building (GEO-10).
     */
    private static final Set<String> NOT_SITE_WORDS = Set.of("location", "locations", "multiple locations",
            "various locations", "other", "others", "tbd", "na", "n a", "any", "head office", "corporate office",
            "registered office", "regional office", "branch office", "hq", "head quarters", "headquarters");
    /** Trailing block/stage/phase/sector parts, stripped one at a time when the full locality name is unknown. */
    private static final Pattern LOCALITY_SUFFIX = Pattern.compile(
            "\\s+(?:\\d+(?:st|nd|rd|th)?\\s+(?:block|stage|phase|sector)|(?:block|stage|phase|sector)\\s+\\d+[a-z]?)$");

    private final Gazetteer gazetteer;
    private final String version;
    private final DescriptionLocationScanner descriptions;

    public GeoResolver(Gazetteer gazetteer) {
        this.gazetteer = gazetteer;
        this.version = PARSER_VERSION + "-" + gazetteer.dataHash();
        this.descriptions = new DescriptionLocationScanner(gazetteer, this::locality);
    }

    /** Parser version plus data hash: editing any data file changes it, which marks old resolutions stale. */
    public String version() { return version; }

    public GeoResolution resolve(String raw, LocationHint hint) {
        return resolve(raw, hint, null);
    }

    /**
     * {@link #resolve(String, LocationHint)} plus the job description as locality evidence (GEO-10C): it can only pick
     * a locality of the one place the location already resolved to, never a place.
     */
    public GeoResolution resolve(String raw, LocationHint hint, String description) {
        try {
            return resolveSafely(raw, hint, description);
        } catch (RuntimeException unexpected) {
            return new GeoResolution(GeoStatus.UNRESOLVED, List.of(), false, null, version);
        }
    }

    private GeoResolution resolveSafely(String raw, LocationHint hint, String description) {
        Signals signals = new Signals();
        GeoPoint point = hint == null ? null : pointInIndia(hint.lat(), hint.lng());
        if (hint != null) applyHint(hint, signals);
        boolean blank = raw == null || raw.isBlank();
        if (blank && !signals.any()) return new GeoResolution(GeoStatus.EMPTY, List.of(), false, point, version);
        if (signals.places.isEmpty() && !blank) parse(raw.length() > MAX_INPUT_LENGTH
                ? raw.substring(0, MAX_INPUT_LENGTH) : raw, signals);
        List<String> placeIds = signals.places.stream().limit(MAX_PLACES).toList();
        if (signals.localities.isEmpty() && !placeIds.isEmpty()) {
            // Evidence beyond the location text; each source names at most one locality, and they must agree.
            String postalCode = hint == null || hint.postalCode() == null ? "" : " " + hint.postalCode();
            Set<String> evidence = new LinkedHashSet<>(pinLocalities((blank ? "" : raw) + postalCode, placeIds));
            if (placeIds.size() == 1) {
                if (point != null) snapLocality(point, placeIds.get(0)).ifPresent(evidence::add);
                // An ATS street address (GEO-10E) is a cue line by definition.
                String address = hint == null || hint.address() == null ? "" : "\nAddress: " + hint.address();
                descriptions.scan((description == null ? "" : description) + address, placeIds).ifPresent(evidence::add);
            }
            if (evidence.size() == 1) signals.localities.addAll(evidence);
        }
        // Invariant: a locality belongs to one of the job's places.
        List<String> localityIds = signals.localities.stream()
                .filter(id -> gazetteer.localityById(id).map(locality -> placeIds.contains(locality.placeId())).orElse(false))
                .limit(MAX_LOCALITIES).toList();
        String siteLabel = placeIds.isEmpty() ? null : signals.siteLabel;
        return new GeoResolution(signals.status(), placeIds, signals.remote(), point, version, localityIds, siteLabel);
    }

    /**
     * Adzuna sends a point; one at the city centre is Adzuna's own city centroid, not an office. A point far enough from
     * the centre snaps to the nearest known locality within {@value #SNAP_MAX_KM} km, never to the raw point.
     */
    private Optional<String> snapLocality(GeoPoint point, String placeId) {
        Place place = gazetteer.byId(placeId).orElse(null);
        if (place == null || MapJobService.haversineKm(point.lat(), point.lng(), place.lat(), place.lng()) < SNAP_MIN_CENTRE_KM) {
            return Optional.empty();
        }
        return gazetteer.nearestLocality(placeId, point.lat(), point.lng(), SNAP_MAX_KM).map(LocalityIndex.Locality::id);
    }

    /**
     * Localities that the location text's PIN codes name inside the resolved places (GEO-10B), read before parsing
     * strips them. A PIN of another city ("Mumbai 560066") or an unknown PIN adds nothing; two PINs naming different
     * localities give two, which the caller treats as disagreement.
     */
    private Set<String> pinLocalities(String raw, List<String> placeIds) {
        Set<String> found = new LinkedHashSet<>();
        Matcher pins = POSTAL_CODE.matcher(raw.length() > MAX_INPUT_LENGTH + 7 ? raw.substring(0, MAX_INPUT_LENGTH + 7) : raw);
        while (pins.find()) {
            gazetteer.pinLocality(pins.group()).filter(locality -> placeIds.contains(locality.placeId()))
                    .ifPresent(locality -> found.add(locality.id()));
        }
        return found;
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
            if (place != null) {
                signals.places.add(place.id());
                // More specific elements we could not place may be localities of this city (["…","Bangalore","Whitefield"]).
                for (int finer = index + 1; finer < area.size(); finer++) {
                    LocalityIndex.Locality locality = locality(Gazetteer.key(area.get(finer)), place);
                    if (locality != null) signals.localities.add(locality.id());
                }
                return;
            }
        }
        if (anyState) signals.state = true;
        else if (onlyIndia) signals.country = true;
    }

    private void parse(String raw, Signals signals) {
        String text = POSTAL_CODE.matcher(raw).replaceAll(" ");
        // "Jammu & Kashmir" / "Daman and Diu" must not be split on '&' into separate places.
        Matcher workMode = WORK_MODE_PARENTHETICAL.matcher(text);
        boolean remoteMarker = false;
        while (workMode.find()) remoteMarker |= isRemoteWord(workMode.group(1));
        if (isPureState(Gazetteer.key(workMode.replaceAll(" ")))) {
            signals.state = true;
            signals.remoteOpen |= remoteMarker;
            return;
        }
        List<String> unplaced = new ArrayList<>();
        for (String candidate : CANDIDATE_SEPARATOR.split(text)) {
            if (candidate.isBlank()) continue;
            Signals own = candidate(candidate);
            // Fallback only: a candidate that placed nothing at all is retried split on - – — ~ : (GEO-9A).
            if (!own.any()) own = edgeSplit(candidate);
            if (!own.any()) unplaced.add(Gazetteer.key(candidate));
            signals.merge(own);
        }
        // "Baner / Kharadi, Pune": a candidate that placed nothing may be a locality of a city another candidate named.
        for (String key : unplaced) {
            for (String placeId : List.copyOf(signals.places)) {
                LocalityIndex.Locality locality = gazetteer.byId(placeId).map(place -> locality(key, place)).orElse(null);
                if (locality != null) { signals.localities.add(locality.id()); break; }
            }
        }
    }

    /** Signals of one candidate on their own, so its remote marker can be scoped by its own location. */
    private Signals candidate(String raw) {
        Signals signals = new Signals();
        Matcher workMode = WORK_MODE_PARENTHETICAL.matcher(raw);
        StringBuilder stripped = new StringBuilder();
        while (workMode.find()) {
            if (isRemoteWord(workMode.group(1))) signals.remoteOpen = true;
            workMode.appendReplacement(stripped, " ");
        }
        workMode.appendTail(stripped);
        String text = WORK_MODE_PREFIX.matcher(stripped.toString()).replaceFirst("");
        String key = Gazetteer.key(text);
        if (key.isEmpty() || WORK_MODE_WORDS.contains(key)) return signals;
        if (COUNTRY_PHRASES.contains(key) || gazetteer.isIndia(key)) { signals.country = true; return signals; }
        if (REMOTE_WORDS.contains(key)) { signals.remoteOpen = true; return signals; }
        Matcher remote = REMOTE_PREFIX.matcher(text);
        if (remote.matches()) {
            signals.remoteOpen = true;
            String rest = remote.group(1);
            String restKey = Gazetteer.key(rest);
            if (restKey.isEmpty() || gazetteer.isIndia(restKey)) return signals;
            if (gazetteer.isForeign(restKey)) { signals.foreign = true; return signals.scopeRemote(); }
            text = rest;
            key = restKey;
        }
        if (isPureState(key)) { signals.state = true; return signals; }
        if (gazetteer.isForeign(key)) { signals.foreign = true; return signals.scopeRemote(); }
        segments(text, signals);
        return signals.scopeRemote();
    }

    /**
     * The edge-city split (GEO-9A), for a candidate that placed nothing: "Bengaluru- We Work", "India-Bangalore",
     * "Pune - Remote". Country tokens at either end are dropped; the first part that is a place wins, else the last.
     * The other parts are a locality of that place, a work mode or remote marker, or else the site label. A foreign part
     * ("London - Bengaluru") places nothing.
     */
    private Signals edgeSplit(String raw) {
        Signals signals = new Signals();
        String text = WORK_MODE_PARENTHETICAL.matcher(raw).replaceAll(" ");
        if (!EDGE_SEPARATOR_PRESENT.matcher(text).find()) return signals;
        List<String> parts = new ArrayList<>();
        for (String part : EDGE_SEPARATOR.split(text)) {
            if (!Gazetteer.key(part).isEmpty()) parts.add(part.trim());
        }
        while (!parts.isEmpty() && COUNTRY_TOKENS.contains(Gazetteer.key(parts.get(0)))) parts.remove(0);
        while (!parts.isEmpty() && COUNTRY_TOKENS.contains(Gazetteer.key(parts.get(parts.size() - 1)))) {
            parts.remove(parts.size() - 1);
        }
        if (parts.isEmpty() || parts.stream().anyMatch(part -> gazetteer.isForeign(Gazetteer.key(part)))) return signals;
        int placeIndex = edgePlace(parts.get(0)) != null ? 0
                : parts.size() > 1 && edgePlace(parts.get(parts.size() - 1)) != null ? parts.size() - 1 : -1;
        if (placeIndex < 0) return signals;
        Place place = edgePlace(parts.get(placeIndex));
        List<String> remainder = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            if (index == placeIndex) continue;
            String part = parts.get(index);
            String key = Gazetteer.key(part);
            if (WORK_MODE_WORDS.contains(key) || gazetteer.isIndia(key)) continue;
            if (remoteInIndia(part)) { signals.remoteOpen = true; continue; }
            remainder.add(part);
        }
        signals.places.add(place.id());
        if (remainder.isEmpty()) return signals;
        // Trailing state / India / postal-code segments say where, not which office ("We Work, Karnataka").
        List<String> commaSegments = new ArrayList<>(List.of(String.join(" - ", remainder).split(",")));
        while (commaSegments.size() > 1 && isQualifier(Gazetteer.key(commaSegments.get(commaSegments.size() - 1)))) {
            commaSegments.remove(commaSegments.size() - 1);
        }
        String rest = String.join(",", commaSegments);
        LocalityIndex.Locality locality = locality(Gazetteer.key(rest), place);
        if (locality == null) locality = locality(Gazetteer.key(commaSegments.get(0)), place);
        if (locality != null) signals.localities.add(locality.id());
        else signals.siteLabel = siteLabel(rest, gazetteer);
        return signals;
    }

    /** "Remote", "WFH", "Remote - India": an open remote marker, never a site label. */
    private boolean remoteInIndia(String part) {
        if (REMOTE_WORDS.contains(Gazetteer.key(part))) return true;
        Matcher remote = REMOTE_PREFIX.matcher(part);
        if (!remote.matches()) return false;
        String rest = Gazetteer.key(remote.group(1));
        return rest.isEmpty() || gazetteer.isIndia(rest);
    }

    /** One part of the edge-city split as a place: an ordinary lookup, never India, a state, a mode or remote word. */
    private Place edgePlace(String part) {
        String key = Gazetteer.key(part);
        if (key.isEmpty() || isQualifier(key) || WORK_MODE_WORDS.contains(key) || REMOTE_WORDS.contains(key)) return null;
        return lookup(key, null);
    }

    /**
     * The normalized site label, or null when it names nothing useful: trimmed, whitespace collapsed, edge punctuation
     * ({@code - – — ~ : , /}) stripped and cut to {@value #MAX_SITE_LABEL_LENGTH} characters on a word boundary; null
     * when shorter than two characters, only digits (a postal code), a remote or work-mode word, or a state, India or
     * a known place name, which say where rather than which office.
     */
    static String siteLabel(String raw, Gazetteer gazetteer) {
        if (raw == null) return null;
        String label = SITE_LABEL_EDGES.matcher(raw.trim().replaceAll("\\s+", " ")).replaceAll("");
        if (label.length() > MAX_SITE_LABEL_LENGTH) {
            int cut = label.lastIndexOf(' ', MAX_SITE_LABEL_LENGTH);
            label = SITE_LABEL_EDGES.matcher(label.substring(0, cut > 0 ? cut : MAX_SITE_LABEL_LENGTH)).replaceAll("");
        }
        String key = Gazetteer.key(label);
        if (label.length() < 2 || key.isEmpty() || key.replace(" ", "").chars().allMatch(Character::isDigit)) return null;
        if (REMOTE_WORDS.contains(key) || WORK_MODE_WORDS.contains(key) || isRemoteWord(label) || NOT_SITE_WORDS.contains(key)
                || COUNTRY_PHRASES.contains(key)) {
            return null;
        }
        if (gazetteer.isIndia(key) || gazetteer.stateCode(key).isPresent() || !gazetteer.aliasMatches(key).isEmpty()
                || !gazetteer.primaryMatches(key).isEmpty()) {
            return null;
        }
        return label;
    }

    private void segments(String candidate, Signals signals) {
        List<String> segments = new ArrayList<>();
        // The segment as written, for the site label ("Kochar Jade", not "kochar jade").
        List<String> written = new ArrayList<>();
        for (String segment : candidate.split(",")) {
            String key = Gazetteer.key(segment);
            if (key.isEmpty() || WORK_MODE_WORDS.contains(key)) continue;
            if (REMOTE_WORDS.contains(key)) { signals.remoteOpen = true; continue; }
            segments.add(key);
            written.add(segment.trim());
        }
        if (segments.isEmpty()) return;
        // Any foreign segment ("San Francisco, CA, United States", or "United States, Remote") means no Indian place.
        for (String segment : segments) {
            if (gazetteer.isForeign(segment)) { signals.foreign = true; return; }
        }
        if (segments.size() >= 2 && segments.stream().allMatch(this::isMajorCity)) {
            segments.forEach(segment -> signals.places.add(lookup(segment, null).id()));
            return;
        }
        String admin1 = null;
        List<String> siteParts = new ArrayList<>();
        for (int index = 1; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (gazetteer.isIndia(segment)) continue;
            String state = gazetteer.stateCode(segment).orElse(null);
            if (state != null) { if (admin1 == null) admin1 = state; continue; }
            // Every qualifier must be Indian; anything else ("Delhi, NY") is not a place we can trust. A locality of a city
            // named in another segment is Indian too ("Bengaluru, Whitefield"), and so is an unknown part followed by a
            // place, since hierarchies grow to the right ("Sector V, Salt Lake, Kolkata").
            if (lookup(segment, null) == null && !localityOfAnotherSegment(segments, index)
                    && !placeFollows(segments, index)) {
                // After a large city, an unknown segment is the ATS's office name ("Mumbai, Urmi"), not a reason to drop
                // the city (GEO-9A); a two-letter or listed foreign qualifier still is ("Delhi, NY", "Hyderabad, Sindh").
                if (!siteSegment(segments, index)) return;
                siteParts.add(written.get(index));
            }
        }
        List<String> names = segments.stream().filter(segment -> !isQualifier(segment)).toList();
        if (names.size() == 1) {
            // A bare locality resolves only through the curated aliases ("Whitefield", "HITEC City, Telangana").
            LocalityIndex.Locality alias = gazetteer.localityAlias(names.get(0)).orElse(null);
            Place aliasPlace = alias == null ? null : gazetteer.byId(alias.placeId()).orElse(null);
            if (aliasPlace != null && (admin1 == null || admin1.equals(aliasPlace.admin1()))) {
                signals.places.add(aliasPlace.id());
                signals.localities.add(alias.id());
                return;
            }
        }
        int placeIndex = -1;
        Place place = lookup(String.join(" ", segments), admin1);
        for (int index = 0; place == null && index < segments.size(); index++) {
            String segment = segments.get(index);
            if (isQualifier(segment)) continue;
            place = lookup(segment, admin1);
            if (place != null) placeIndex = index;
        }
        // A small place first ("Powai, Mumbai") gives way to a later city that has it as a locality.
        if (place != null && placeIndex >= 0 && place.population() < LOCALITY_PLACE_MAX_POPULATION) {
            for (int later = placeIndex + 1; later < segments.size(); later++) {
                Place city = isQualifier(segments.get(later)) ? null : lookup(segments.get(later), admin1);
                if (city != null && !city.id().equals(place.id()) && locality(segments.get(placeIndex), city) != null) {
                    place = city;
                    placeIndex = later;
                    break;
                }
            }
        }
        LocalityIndex.Locality fusedLocality = null;
        // "Noida Sector 62", "Sec 62 Noida", "Gurgaon Cyber City": a city fused with one of its localities.
        for (int index = 0; place == null && index < segments.size(); index++) {
            if (isQualifier(segments.get(index))) continue;
            Fused fused = fused(segments.get(index), admin1);
            if (fused != null) {
                place = fused.city();
                placeIndex = index;
                fusedLocality = fused.locality();
            }
        }
        if (place == null) {
            if (segments.stream().anyMatch(this::isPureState)) signals.state = true;
            else if (segments.stream().anyMatch(gazetteer::isIndia)) signals.country = true;
            return;
        }
        signals.places.add(place.id());
        if (!siteParts.isEmpty()) signals.siteLabel = siteLabel(String.join(", ", siteParts), gazetteer);
        if (fusedLocality != null) signals.localities.add(fusedLocality.id());
        // All segments together named the city ("New Delhi"): none of them is a locality.
        if (placeIndex < 0) return;
        for (int index = 0; index < segments.size(); index++) {
            if (index == placeIndex || isQualifier(segments.get(index))) continue;
            LocalityIndex.Locality locality = locality(segments.get(index), place);
            if (locality != null) signals.localities.add(locality.id());
        }
    }

    /**
     * Rule A (GEO-9A): segment {@code index}, unknown and not a locality, may be a site label only after a first segment
     * that is a city of at least {@value #LIST_MODE_MIN_POPULATION} people, and only when it is not foreign, not a state,
     * not a listed foreign region or city and not two letters (a US state or country code: "Delhi, NY", "Bengaluru, CA").
     */
    private boolean siteSegment(List<String> segments, int index) {
        String first = segments.get(0);
        String segment = segments.get(index);
        if (isQualifier(first)) return false;
        Place city = lookup(first, null);
        return city != null && city.population() >= LIST_MODE_MIN_POPULATION && !gazetteer.isForeign(segment)
                && !gazetteer.isForeignQualifier(segment) && gazetteer.stateCode(segment).isEmpty()
                && segment.replace(" ", "").length() > 2;
    }

    /** India, a state, or a postal-code-only segment: says where, but never names a city or locality. */
    private boolean isQualifier(String segment) {
        return gazetteer.isIndia(segment) || isPureState(segment) || segment.chars().allMatch(Character::isDigit);
    }

    private boolean placeFollows(List<String> segments, int index) {
        for (int later = index + 1; later < segments.size(); later++) {
            String segment = segments.get(later);
            if (!isQualifier(segment) && lookup(segment, null) != null) return true;
        }
        return false;
    }

    private boolean localityOfAnotherSegment(List<String> segments, int index) {
        for (int other = 0; other < segments.size(); other++) {
            if (other == index) continue;
            Place city = lookup(segments.get(other), null);
            if (city != null && locality(segments.get(index), city) != null) return true;
        }
        return false;
    }

    /**
     * A locality of {@code city} named by {@code key}: its name (or English/alternate name), a curated alias of a
     * locality in this city, then the same after stripping a trailing block/stage/phase/sector part
     * ("Koramangala 12th Block" → Koramangala, "Hinjewadi Phase 4" → Hinjewadi). Never a locality of another city.
     */
    LocalityIndex.Locality locality(String key, Place city) {
        String current = LocalityIndex.localityKey(key);
        while (!current.isEmpty()) {
            List<LocalityIndex.Locality> named = gazetteer.localityMatches(current, city.id());
            if (!named.isEmpty()) return named.get(0);
            LocalityIndex.Locality alias = gazetteer.localityAlias(current).orElse(null);
            if (alias != null && alias.placeId().equals(city.id())) return alias;
            String stripped = LOCALITY_SUFFIX.matcher(current).replaceFirst("");
            if (stripped.equals(current)) return null;
            current = stripped;
        }
        return null;
    }

    /** Longest city name first, as a prefix or a suffix of the segment, and only when the rest is one of its localities. */
    private Fused fused(String segment, String admin1) {
        String[] words = segment.split(" ");
        for (int cityWords = words.length - 1; cityWords >= 1; cityWords--) {
            String prefix = String.join(" ", java.util.Arrays.copyOfRange(words, 0, cityWords));
            String afterPrefix = String.join(" ", java.util.Arrays.copyOfRange(words, cityWords, words.length));
            String suffix = String.join(" ", java.util.Arrays.copyOfRange(words, words.length - cityWords, words.length));
            String beforeSuffix = String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - cityWords));
            for (String[] split : List.of(new String[] {prefix, afterPrefix}, new String[] {suffix, beforeSuffix})) {
                if (isQualifier(split[0])) continue;
                Place city = lookupNamed(split[0], admin1);
                LocalityIndex.Locality locality = city == null ? null : locality(split[1], city);
                if (locality != null) return new Fused(city, locality);
            }
        }
        return null;
    }

    private record Fused(Place city, LocalityIndex.Locality locality) { }

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
        /** Locality ids in the order found; filtered to {@link #places} at the end. */
        final Set<String> localities = new LinkedHashSet<>();
        /** Remote with no scope, or scoped to India. */
        boolean remoteOpen;
        /** Remote scoped to a foreign country only ("Remote - United States"). */
        boolean remoteForeign;
        boolean state;
        boolean country;
        boolean foreign;
        /** The first site label found (GEO-9A); kept only when the job has a place. */
        String siteLabel;

        boolean any() { return !places.isEmpty() || remoteOpen || remoteForeign || state || country || foreign; }

        boolean remote() { return remoteOpen || remoteForeign; }

        /** Within one candidate: a remote marker whose only location is foreign is foreign-only remote. */
        Signals scopeRemote() {
            if (remoteOpen && foreign && places.isEmpty() && !state && !country) {
                remoteOpen = false;
                remoteForeign = true;
            }
            return this;
        }

        void merge(Signals other) {
            places.addAll(other.places);
            localities.addAll(other.localities);
            remoteOpen |= other.remoteOpen;
            remoteForeign |= other.remoteForeign;
            state |= other.state;
            country |= other.country;
            foreign |= other.foreign;
            if (siteLabel == null) siteLabel = other.siteLabel;
        }

        GeoStatus status() {
            if (!places.isEmpty()) return GeoStatus.PLACE;
            if (remoteOpen) return GeoStatus.REMOTE;
            if (state) return GeoStatus.STATE;
            if (country) return GeoStatus.COUNTRY;
            if (foreign || remoteForeign) return GeoStatus.FOREIGN;
            return GeoStatus.UNRESOLVED;
        }
    }
}
