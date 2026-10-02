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
     * Delete existing Thing, Location, Datastream, Sensor, ObservedProperty, and Observation
     * records for each site, then upload them again from the STA folder.
     * An ObservedProperty still referenced by another Datastream is updated in place.
     * Deterministic {@code @iot.id} values are reused. Data is missing until each entity is posted again.
     */
    DELETE_BEFORE_UPLOAD,
    
    /**
     * Use deterministic IDs based on datastream ID and phenomenonTime.
     * Re-uploading an observation that already has that {@code @iot.id} is treated as success.
     */
    DETERMINISTIC_ID
}
