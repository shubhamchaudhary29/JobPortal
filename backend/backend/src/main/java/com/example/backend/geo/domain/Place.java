package com.example.backend.geo.domain;

/** One GeoNames populated place; {@code id} is the GeoNames geonameid. */
public record Place(String id, String name, String admin1, double lat, double lng, long population,
                    String featureCode) { }
