/*
 * Ingestor
 * Copyright (C) 2026 National Research Council of Italy (CNR)/Institute of Technologies and Environmental Intelligence (ITIAm)/ESSI-Lab
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
