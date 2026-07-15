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
package eu.flora.essi.ingestor.annals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import eu.flora.essi.frost.Datastream;
import eu.flora.essi.frost.Location;
import eu.flora.essi.frost.Observation;
import eu.flora.essi.frost.ObservedProperty;
import eu.flora.essi.frost.Sensor;
import eu.flora.essi.frost.Thing;
import eu.flora.essi.ingestor.sta.DeterministicIdGenerator;

/**
 * Writes Annals data to STA folder structure for later upload via STAtoFrostUploader.
 * 
 * Folder structure:
 *   sta/
 *     things/
 *       {thingId}/
 *         Thing.json
 *         Location.json
 *         {datastreamId}/
 *           Datastream.json
 *           ObservedProperty.json
 *           Sensor.json
 *           observations/
 *             {timestamp}.json
 */
public class AnnalsToStaFolderWriter {

    private static final String THINGS_DIR = "things";
    private static final String OBSERVATIONS_DIR = "observations";
    /** Default number of observations per batch file. */
    public static final int DEFAULT_OBSERVATIONS_PER_BATCH_FILE = 1000;
    private static final String OBSERVATIONS_BATCH_PREFIX = "observations_";

    private final Path staRoot;
    private final Map<String, Path> thingDirs = new HashMap<>();
    private final Map<String, Path> datastreamDirs = new HashMap<>();
    private final int observationsPerBatchFile;
    /** Buffered observations per observations folder; flushed when batch size reached. */
    private final Map<Path, List<JSONObject>> observationBuffers = new HashMap<>();
    /** Batch file counter per observations folder. */
    private final Map<Path, Integer> batchCounters = new HashMap<>();
    /** Maps observations folder path to its deterministic datastream ID (for pre-serialized batch body). */
    private final Map<Path, Long> datastreamIdByObsFolder = new HashMap<>();

    public AnnalsToStaFolderWriter(Path staRoot) throws IOException {
        this(staRoot, DEFAULT_OBSERVATIONS_PER_BATCH_FILE);
    }

    public AnnalsToStaFolderWriter(Path staRoot, int observationsPerBatchFile) throws IOException {
        this.staRoot = staRoot;
        this.observationsPerBatchFile = observationsPerBatchFile > 0 ? observationsPerBatchFile : DEFAULT_OBSERVATIONS_PER_BATCH_FILE;
        Files.createDirectories(staRoot.resolve(THINGS_DIR));
    }

    /**
     * Write a Thing with Location to the STA folder.
     * Sets deterministic @iot.id for pre-serialization.
     * @return The directory path for the thing
     */
    public Path writeThing(String thingId, Thing thing, Location location) throws IOException {
        Path thingDir = staRoot.resolve(THINGS_DIR).resolve(sanitizeForPath(thingId));
        Files.createDirectories(thingDir);
        
        // Set deterministic ID for pre-serialization
        thing.toJSON().put("@iot.id", DeterministicIdGenerator.thingId(thingId));
        
        // Write Thing.json
        writeJson(thingDir.resolve("Thing.json"), thing.toJSON());
        
        // Write Location.json if present
        if (location != null) {
            location.toJSON().put("@iot.id", DeterministicIdGenerator.locationId(thingId));
            writeJson(thingDir.resolve("Location.json"), location.toJSON());
        }
        
        thingDirs.put(thingId, thingDir);
        return thingDir;
    }

    /**
     * Write a Datastream with Sensor and ObservedProperty to the STA folder.
     * Sets deterministic @iot.id for all entities for pre-serialization.
     * @return The directory path for the datastream
     */
    public Path writeDatastream(String thingId, String datastreamId, Datastream datastream, 
            Sensor sensor, ObservedProperty observedProperty) throws IOException {
        
        Path thingDir = thingDirs.get(thingId);
        if (thingDir == null) {
            throw new IOException("Thing not found: " + thingId + ". Call writeThing first.");
        }
        
        Path dsDir = thingDir.resolve(sanitizeForPath(datastreamId));
        Files.createDirectories(dsDir);
        Files.createDirectories(dsDir.resolve(OBSERVATIONS_DIR));
        
        // Set deterministic IDs for pre-serialization
        Long deterministicDsId = DeterministicIdGenerator.datastreamId(datastreamId);
        datastream.toJSON().put("@iot.id", deterministicDsId);
        
        // Store the deterministic datastream ID for pre-serialized batch body
        datastreamIdByObsFolder.put(dsDir.resolve(OBSERVATIONS_DIR), deterministicDsId);
        
        // Write Datastream.json
        writeJson(dsDir.resolve("Datastream.json"), datastream.toJSON());
        
        // Write Sensor.json
        if (sensor != null) {
            sensor.toJSON().put("@iot.id", DeterministicIdGenerator.sensorId(datastreamId));
            writeJson(dsDir.resolve("Sensor.json"), sensor.toJSON());
        }
        
        // Write ObservedProperty.json
        if (observedProperty != null) {
            // Use variableCode from properties if available, otherwise use datastreamId
            String propCode = observedProperty.getProperties() != null 
                    ? observedProperty.getProperties().optString("variableCode", datastreamId) 
                    : datastreamId;
            observedProperty.toJSON().put("@iot.id", DeterministicIdGenerator.observedPropertyId(propCode));
            writeJson(dsDir.resolve("ObservedProperty.json"), observedProperty.toJSON());
        }
        
        datastreamDirs.put(datastreamId, dsDir);
        return dsDir;
    }

    /**
     * Buffer an observation for batch writing. Observations are accumulated in memory
     * and written to batch files (observations_001.json, observations_002.json, etc.)
     * when the buffer reaches observationsPerBatchFile.
     * 
     * Generates a deterministic @iot.id based on datastreamId + phenomenonTime,
     * pre-serializing for efficient upload (no re-processing needed during upload phase).
     */
    public void writeObservation(String datastreamId, Observation observation) throws IOException {
        Path dsDir = datastreamDirs.get(datastreamId);
        if (dsDir == null) {
            throw new IOException("Datastream not found: " + datastreamId + ". Call writeDatastream first.");
        }
        
        Path obsDir = dsDir.resolve(OBSERVATIONS_DIR);
        
        // Generate and set deterministic ID for idempotent uploads
        Long deterministicId = DeterministicIdGenerator.observationId(datastreamId, observation.getPhenomenonTime());
        observation.setId(deterministicId);
        
        // Get or create buffer for this observations folder
        List<JSONObject> buffer = observationBuffers.computeIfAbsent(obsDir, k -> new ArrayList<>());
        buffer.add(observation.toJSON());
        
        // Flush batch if buffer is full
        if (buffer.size() >= observationsPerBatchFile) {
            flushObservationBatch(obsDir);
        }
    }

    /**
     * Flush the observation buffer for a folder, writing a batch file.
     * Reorders so the first two observations are the oldest and newest in that batch.
     */
    private void flushObservationBatch(Path observationsDir) throws IOException {
        List<JSONObject> buffer = observationBuffers.get(observationsDir);
        if (buffer == null || buffer.isEmpty()) return;

        int batchNum = batchCounters.getOrDefault(observationsDir, 0) + 1;
        batchCounters.put(observationsDir, batchNum);

        Long datastreamId = datastreamIdByObsFolder.get(observationsDir);
        if (datastreamId == null) throw new IOException("No datastream ID found for observations folder: " + observationsDir);

        List<JSONObject> ordered = orderFirstBatchWithExtentFirst(buffer, batchNum);

        JSONArray requests = new JSONArray();
        for (int i = 0; i < ordered.size(); i++) {
            JSONObject request = new JSONObject();
            request.put("id", String.valueOf(i));
            request.put("method", "post");
            request.put("url", "Datastreams(" + datastreamId + ")/Observations");
            request.put("body", ordered.get(i));
            requests.put(request);
        }
        JSONObject batchBody = new JSONObject();
        batchBody.put("requests", requests);
        Files.writeString(observationsDir.resolve(String.format("%s%04d.json", OBSERVATIONS_BATCH_PREFIX, batchNum)), batchBody.toString(), StandardCharsets.UTF_8);
        buffer.clear();
    }

    /** Reorder so the first two observations are the oldest and newest in this batch by phenomenonTime. */
    private static List<JSONObject> orderFirstBatchWithExtentFirst(List<JSONObject> buffer, int batchNum) {
        if (buffer.size() < 2) {
            return new ArrayList<>(buffer);
        }
        int iMin = 0, iMax = 0;
        String ptMin = optPhenomenonTime(buffer.get(0));
        String ptMax = ptMin;
        for (int i = 1; i < buffer.size(); i++) {
            String pt = optPhenomenonTime(buffer.get(i));
            if (pt != null && (ptMin == null || pt.compareTo(ptMin) < 0)) { iMin = i; ptMin = pt; }
            if (pt != null && (ptMax == null || pt.compareTo(ptMax) > 0)) { iMax = i; ptMax = pt; }
        }
        List<JSONObject> ordered = new ArrayList<>(buffer.size());
        ordered.add(buffer.get(iMin));
        if (iMin != iMax) ordered.add(buffer.get(iMax));
        for (int i = 0; i < buffer.size(); i++) {
            if (i != iMin && i != iMax) ordered.add(buffer.get(i));
        }
        return ordered;
    }

    private static String optPhenomenonTime(JSONObject obs) {
        if (obs == null) return null;
        try {
            Object v = obs.opt("phenomenonTime");
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Flush all remaining buffered observations to batch files. */
    public void flushAllObservations() throws IOException {
        for (Path observationsDir : new ArrayList<>(observationBuffers.keySet())) {
            flushObservationBatch(observationsDir);
        }
        observationBuffers.clear();
    }

    /**
     * Check if a thing directory already exists.
     */
    public boolean thingExists(String thingId) {
        Path thingDir = staRoot.resolve(THINGS_DIR).resolve(sanitizeForPath(thingId));
        return Files.isDirectory(thingDir);
    }

    /**
     * Check if a datastream directory already exists.
     */
    public boolean datastreamExists(String thingId, String datastreamId) {
        Path thingDir = staRoot.resolve(THINGS_DIR).resolve(sanitizeForPath(thingId));
        Path dsDir = thingDir.resolve(sanitizeForPath(datastreamId));
        return Files.isDirectory(dsDir);
    }

    /**
     * Load existing thing directory into cache (for resuming).
     */
    public void loadExistingThing(String thingId) {
        Path thingDir = staRoot.resolve(THINGS_DIR).resolve(sanitizeForPath(thingId));
        if (Files.isDirectory(thingDir)) {
            thingDirs.put(thingId, thingDir);
        }
    }

    /**
     * Load existing datastream directory into cache (for resuming).
     * Also restores the deterministic FROST datastream ID required when flushing observation batches.
     */
    public void loadExistingDatastream(String thingId, String datastreamId) {
        Path thingDir = staRoot.resolve(THINGS_DIR).resolve(sanitizeForPath(thingId));
        Path dsDir = thingDir.resolve(sanitizeForPath(datastreamId));
        if (!Files.isDirectory(dsDir)) {
            return;
        }

        datastreamDirs.put(datastreamId, dsDir);
        Path obsDir = dsDir.resolve(OBSERVATIONS_DIR);
        try {
            datastreamIdByObsFolder.put(obsDir, resolveDatastreamIotId(dsDir, datastreamId));
            restoreBatchCounter(obsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load existing datastream " + datastreamId + " from " + dsDir, e);
        }
    }

    private Long resolveDatastreamIotId(Path dsDir, String datastreamId) throws IOException {
        Path dsFile = dsDir.resolve("Datastream.json");
        if (Files.isRegularFile(dsFile)) {
            JSONObject json = new JSONObject(Files.readString(dsFile, StandardCharsets.UTF_8));
            if (json.has("@iot.id")) {
                return json.getLong("@iot.id");
            }
        }
        return DeterministicIdGenerator.datastreamId(datastreamId);
    }

    private void restoreBatchCounter(Path observationsDir) throws IOException {
        if (!Files.isDirectory(observationsDir)) {
            return;
        }

        int maxBatch = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(observationsDir, p -> {
            String name = p.getFileName().toString();
            return name.startsWith(OBSERVATIONS_BATCH_PREFIX) && name.endsWith(".json");
        })) {
            for (Path batchFile : stream) {
                String name = batchFile.getFileName().toString();
                String numberPart = name.substring(OBSERVATIONS_BATCH_PREFIX.length(), name.length() - ".json".length());
                try {
                    maxBatch = Math.max(maxBatch, Integer.parseInt(numberPart));
                } catch (NumberFormatException ignored) {
                    // Ignore unexpected batch file names
                }
            }
        }

        if (maxBatch > 0) {
            batchCounters.put(observationsDir, maxBatch);
        }
    }

    private void writeJson(Path path, JSONObject json) throws IOException {
        Files.writeString(path, json.toString(2), StandardCharsets.UTF_8);
    }

    private String sanitizeForPath(String input) {
        if (input == null) return "null";
        // Replace characters that are problematic in file paths
        return input.replaceAll("[:/\\\\<>\"\\|\\?\\*]", "_");
    }

    public Path getStaRoot() {
        return staRoot;
    }
}
