package com.example.backend.integration.adzuna;

/** Safe database-facing ingestion failure; underlying database details stay in the cause only. */
public class AdzunaPersistenceException extends RuntimeException {
    public AdzunaPersistenceException(Throwable cause) { super("Adzuna job could not be stored", cause); }
}
