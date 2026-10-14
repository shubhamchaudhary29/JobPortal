package com.example.backend.job.domain;

/** Outcome of resolving a free-text job location against the offline gazetteer. */
public enum GeoStatus { PLACE, REMOTE, STATE, COUNTRY, FOREIGN, UNRESOLVED, EMPTY }
