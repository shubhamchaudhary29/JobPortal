package com.example.backend.job.domain;

/** A WGS84 coordinate supplied by a provider; stored for GEO-6, never indexed in GEO-1. */
public record GeoPoint(double lat, double lng) { }
