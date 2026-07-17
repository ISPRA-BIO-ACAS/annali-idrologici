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
 * Generates deterministic IDs for SensorThings API entities.
 * IDs are based on stable string keys and produce positive Long values.
 * Different entity types use different offset ranges to avoid collisions.
 */
public final class DeterministicIdGenerator {

    // Offset ranges for different entity types to avoid ID collisions
    private static final long THING_OFFSET = 1_000_000_000L;
    private static final long LOCATION_OFFSET = 2_000_000_000L;
    private static final long DATASTREAM_OFFSET = 3_000_000_000L;
    private static final long SENSOR_OFFSET = 4_000_000_000L;
    private static final long OBSERVED_PROPERTY_OFFSET = 5_000_000_000L;
    private static final long OBSERVATION_OFFSET = 6_000_000_000L;
    /** Time component width when packing (datastreamSlot * 2^32 + timeHash). */
    private static final long TIME_HASH_MASK = 0xFFFF_FFFFL;

    private DeterministicIdGenerator() {
        // Utility class
    }

    /**
     * Generate a deterministic ID for a Thing based on siteId.
     */
    public static Long thingId(String siteId) {
        return generateId(siteId, THING_OFFSET);
    }

    /**
     * Generate a deterministic ID for a Location based on siteId.
     */
    public static Long locationId(String siteId) {
        return generateId(siteId, LOCATION_OFFSET);
    }

    /**
     * Generate a deterministic ID for a Datastream based on datastreamIdProp.
     * @param datastreamIdProp typically "siteId|propertyCode|interpolationType|aggregationPeriod"
     */
    public static Long datastreamId(String datastreamIdProp) {
        return generateId(datastreamIdProp, DATASTREAM_OFFSET);
    }

    /**
     * Generate a deterministic ID for a Sensor based on sensor identifier.
     */
    public static Long sensorId(String sensorKey) {
        return generateId(sensorKey, SENSOR_OFFSET);
    }

    /**
     * Generate a deterministic ID for an ObservedProperty based on property code/URI.
     */
    public static Long observedPropertyId(String propertyKey) {
        return generateId(propertyKey, OBSERVED_PROPERTY_OFFSET);
    }

    /**
     * Generate a deterministic ID for an Observation based on datastreamIdProp + phenomenonTime.
     * <p>
     * IDs are packed as {@code OBSERVATION_OFFSET + datastreamSlot * 2^32 + hash(phenomenonTime)},
     * where {@code datastreamSlot} is the unique slot of the parent datastream. Different datastreams
     * therefore cannot produce the same observation ID, even at the same phenomenonTime.
     */
    public static Long observationId(String datastreamIdProp, String phenomenonTime) {
        long dsSlot = datastreamId(datastreamIdProp) - DATASTREAM_OFFSET;
        long timeHash = betterHash(phenomenonTime != null ? phenomenonTime : "") & TIME_HASH_MASK;
        return OBSERVATION_OFFSET + dsSlot * (TIME_HASH_MASK + 1L) + timeHash;
    }

    /**
     * Core ID generation for entity types with relatively few instances (things, datastreams, …).
     * Produces a positive Long from a string key in a 1-billion slot range above {@code offset}.
     */
    private static Long generateId(String key, long offset) {
        if (key == null) {
            key = "";
        }
        // Use a better hash than String.hashCode() for more uniform distribution
        long hash = betterHash(key);
        // Make it positive and add offset
        return Math.abs(hash % 1_000_000_000L) + offset;
    }

    /**
     * A better hash function than String.hashCode() for more uniform distribution.
     * Based on FNV-1a hash algorithm.
     */
    private static long betterHash(String s) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }
}
