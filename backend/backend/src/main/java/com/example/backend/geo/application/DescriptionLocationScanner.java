package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the locality a job description names for the job's one city (GEO-10C), as evidence only: it never names a
 * place and never yields a point. Pure and thread-safe.
 *
 * <p>Evidence is PIN codes anywhere in the text that {@code in-pincode-localities.tsv} maps into that city, and known
 * locality names of that city inside a cue line, a line or sentence saying where the work is ("Office address: …",
 * "Work location: …", "based in …"). A cue keeps a list of perks or a company history from counting. The answer is a
 * locality only when every piece of evidence names the same one: two localities ("offices in Whitefield and Electronic
 * City"), a locality of another city, or no cue at all give nothing. One tech park plus the suburb around it ("Prestige
 * Tech Park, Marathahalli": every other locality within {@value #NESTED_KM} km of the park) is one place named at two
 * levels, and the park wins. At most {@value #MAX_CHARS} characters are read.
 */
public final class DescriptionLocationScanner {
    static final int MAX_CHARS = 20_000;
    static final int MAX_WORDS = 5;
    /** Shorter windows ("in", "park") are too easily ordinary words. */
    static final int MIN_KEY_LENGTH = 4;
    static final double NESTED_KM = 2.0;
    private static final Set<String> TECH_PARK_KINDS = Set.of("techhub", "tech_park");
    private static final Pattern BLOCK_TAG = Pattern.compile("(?i)<\\s*(?:br|/p|/li|/div|/h[1-6]|/tr|/ul|/ol)[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(?:nbsp|amp|#160);", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNITS = Pattern.compile("[\\r\\n]+|(?<=[.!?;])\\s+");
    private static final Pattern CUE = Pattern.compile(
            "\\b(?:address|location|locations|work location|offices?|venue|campus|based in)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIN = Pattern.compile("(?<!\\d)[1-9]\\d{5}(?!\\d)");

    private final Gazetteer gazetteer;
    private final BiFunction<String, Place, LocalityIndex.Locality> localityOf;

    /** {@code localityOf}: the resolver's own matcher (names, aliases, block/phase stripping) for a key in a city. */
    public DescriptionLocationScanner(Gazetteer gazetteer, BiFunction<String, Place, LocalityIndex.Locality> localityOf) {
        this.gazetteer = gazetteer;
        this.localityOf = localityOf;
    }

    /** The one locality id the description gives for the job's single place, or empty (several places, none, or conflict). */
    public Optional<String> scan(String description, List<String> placeIds) {
        if (description == null || description.isBlank() || placeIds == null || placeIds.size() != 1) return Optional.empty();
        Place city = gazetteer.byId(placeIds.get(0)).orElse(null);
        if (city == null) return Optional.empty();
        String text = plainText(description.length() > MAX_CHARS * 2 ? description.substring(0, MAX_CHARS * 2) : description);
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        Set<String> evidence = new LinkedHashSet<>();
        Matcher pins = PIN.matcher(text);
        while (pins.find()) {
            gazetteer.pinLocality(pins.group()).filter(locality -> locality.placeId().equals(city.id()))
                    .ifPresent(locality -> evidence.add(locality.id()));
        }
        Set<String> cityKeys = gazetteer.cityKeys(city.id());
        for (String unit : UNITS.split(text)) {
            if (CUE.matcher(unit).find()) evidence.addAll(localitiesIn(unit, city, cityKeys));
        }
        if (evidence.size() == 1) return Optional.of(evidence.iterator().next());
        return nestedTechPark(evidence);
    }

    /** The one tech park among {@code ids} when every other locality lies within {@value #NESTED_KM} km of it. */
    private Optional<String> nestedTechPark(Set<String> ids) {
        List<LocalityIndex.Locality> localities = ids.stream().map(gazetteer::localityById).flatMap(Optional::stream).toList();
        List<LocalityIndex.Locality> parks = localities.stream().filter(locality -> TECH_PARK_KINDS.contains(locality.kind()))
                .toList();
        if (parks.size() != 1 || localities.size() != ids.size()) return Optional.empty();
        LocalityIndex.Locality park = parks.get(0);
        boolean nested = localities.stream().allMatch(locality ->
                MapJobService.haversineKm(locality.lat(), locality.lng(), park.lat(), park.lng()) <= NESTED_KM);
        return nested ? Optional.of(park.id()) : Optional.empty();
    }

    /** Known localities of {@code city} named in {@code unit}, longest name first, without overlaps. */
    private List<String> localitiesIn(String unit, Place city, Set<String> cityKeys) {
        String[] words = Gazetteer.key(unit).split(" ");
        List<String> found = new ArrayList<>();
        int start = 0;
        while (start < words.length) {
            int matched = 0;
            for (int length = Math.min(MAX_WORDS, words.length - start); length >= 1 && matched == 0; length--) {
                String key = String.join(" ", java.util.Arrays.copyOfRange(words, start, start + length));
                if (key.length() < MIN_KEY_LENGTH || cityKeys.contains(key)) continue;
                LocalityIndex.Locality locality = localityOf.apply(key, city);
                if (locality != null) {
                    found.add(locality.id());
                    matched = length;
                }
            }
            start += Math.max(1, matched);
        }
        return found;
    }

    /** Tags removed (block tags become line breaks, so lines stay lines), common entities as text. */
    static String plainText(String html) {
        String text = BLOCK_TAG.matcher(html).replaceAll("\n");
        text = TAG.matcher(text).replaceAll(" ");
        return ENTITY.matcher(text).replaceAll(match -> match.group().equalsIgnoreCase("&amp;") ? "&" : " ");
    }
}
