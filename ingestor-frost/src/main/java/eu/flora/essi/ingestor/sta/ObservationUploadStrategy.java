package eu.flora.essi.ingestor.sta;

/**
 * Strategy for handling observation uploads to avoid duplicates.
 */
public enum ObservationUploadStrategy {
    
    /**
     * Default strategy: just upload observations without any duplicate handling.
     * May result in duplicate observations if the same data is uploaded multiple times.
     */
    NONE,
    
    /**
     * Delete all existing observations for the datastream before uploading new ones.
     * Also uses deterministic IDs as a fallback in case delete was incomplete.
     * Ensures a clean state but temporarily removes all data during upload.
     */
    DELETE_BEFORE_UPLOAD,
    
    /**
     * Use deterministic IDs based on datastream ID and phenomenonTime.
     * If the server supports client-defined IDs, this ensures idempotent uploads.
     * Observations with the same ID will be rejected or updated (depending on server config).
     */
    DETERMINISTIC_ID
}
