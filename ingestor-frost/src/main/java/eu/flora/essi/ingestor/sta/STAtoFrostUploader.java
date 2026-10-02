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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;

import eu.flora.essi.frost.Datastream;
import eu.flora.essi.frost.FROSTBatchUploadException;
import eu.flora.essi.frost.FROSTClient;
import eu.flora.essi.frost.FROSTServiceUnavailableException;
import eu.flora.essi.frost.Location;
import eu.flora.essi.frost.Observation;
import eu.flora.essi.frost.ObservedProperty;
import eu.flora.essi.frost.PagedResult;
import eu.flora.essi.frost.Sensor;
import eu.flora.essi.frost.Thing;

/**
 * Uploads STA JSON folder structure (things/datastreams/observations) to a FROST Server.
 * Uses parallel threads to batch-upload observations per datastream.
 */
public final class STAtoFrostUploader {

    private static final String THINGS_DIR = "things";
    private static final String THING_JSON = "Thing.json";
    private static final String LOCATION_JSON = "Location.json";
    private static final String DATASTREAM_JSON = "Datastream.json";
    private static final String OBSERVED_PROPERTY_JSON = "ObservedProperty.json";
    private static final String SENSOR_JSON = "Sensor.json";
    private static final String OBSERVATIONS_DIR = "observations";
    private static final int DEFAULT_OBSERVATION_UPLOAD_PARALLELISM = 8;
    private static final long OBSERVATION_UPLOAD_TIMEOUT_MINUTES = 30;
    private static final long UPLOAD_HEARTBEAT_SECONDS = 30;
    private static final long SLOW_BATCH_LOG_THRESHOLD_MS = 5_000;
    public static final int DEFAULT_MAX_OBSERVATIONS_PER_BATCH = 1000;

    private final FROSTClient client;
    private final Path staRoot;
    private final int maxObservationsPerBatch;
    private final ObservationUploadStrategy uploadStrategy;
    private final boolean uploadObservations;
    private final boolean forceUpload;
    private final int observationUploadParallelism;
    private final boolean uploadVerbose;

    public STAtoFrostUploader(String frostBaseUrl, Path staRoot) {
        this(frostBaseUrl, staRoot, DEFAULT_MAX_OBSERVATIONS_PER_BATCH, ObservationUploadStrategy.NONE, true, false);
    }

    public STAtoFrostUploader(String frostBaseUrl, Path staRoot, int maxObservationsPerBatch) {
        this(frostBaseUrl, staRoot, maxObservationsPerBatch, ObservationUploadStrategy.NONE, true, false);
    }

    public STAtoFrostUploader(String frostBaseUrl, Path staRoot, int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy) {
        this(frostBaseUrl, staRoot, maxObservationsPerBatch, uploadStrategy, true, false, DEFAULT_OBSERVATION_UPLOAD_PARALLELISM, false);
    }

    /**
     * @param uploadObservations if false, only Things, Locations, Datastreams (and Sensor, ObservedProperty) are uploaded; observation batches are skipped
     */
    public STAtoFrostUploader(String frostBaseUrl, Path staRoot, int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy, boolean uploadObservations) {
        this(frostBaseUrl, staRoot, maxObservationsPerBatch, uploadStrategy, uploadObservations, false, DEFAULT_OBSERVATION_UPLOAD_PARALLELISM, false);
    }

    /**
     * @param forceUpload if true, PATCH existing Thing/Location/Datastream metadata when entities already exist (e.g. after changing locations or datastream metadata)
     */
    public STAtoFrostUploader(String frostBaseUrl, Path staRoot, int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy, boolean uploadObservations, boolean forceUpload) {
        this(frostBaseUrl, staRoot, maxObservationsPerBatch, uploadStrategy, uploadObservations, forceUpload, DEFAULT_OBSERVATION_UPLOAD_PARALLELISM, false);
    }

    public STAtoFrostUploader(String frostBaseUrl, Path staRoot, int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy,
            boolean uploadObservations, boolean forceUpload, int observationUploadParallelism, boolean uploadVerbose) {
        this.client = new FROSTClient(frostBaseUrl);
        this.staRoot = staRoot;
        this.maxObservationsPerBatch = maxObservationsPerBatch;
        this.uploadStrategy = uploadStrategy != null ? uploadStrategy : ObservationUploadStrategy.NONE;
        this.uploadObservations = uploadObservations;
        this.forceUpload = forceUpload;
        this.observationUploadParallelism = Math.max(1, observationUploadParallelism);
        this.uploadVerbose = uploadVerbose;
    }

    /**
     * Walk the STA folder and upload Things, Locations, Datastreams (and their Sensor, ObservedProperty),
     * then batch-upload Observations in parallel.
     * 
     * Uses lazy loading and immediate task submission to minimize memory usage:
     * - Observations are only loaded when the task executes (not when collected)
     * - Tasks are submitted immediately to the executor
     * - Completed tasks can be garbage collected while others are still running
     */
    public void upload() throws FROSTServiceUnavailableException, FROSTBatchUploadException, Exception {
        Path thingsDir = staRoot.resolve(THINGS_DIR);
        
        if (!Files.isDirectory(thingsDir)) {
            Files.createDirectories(thingsDir);
            System.out.println("STA folder created (empty): " + thingsDir);
            return;
        }

        System.out.println("Checking FROST server availability at " + client.getBaseUrl());
        client.checkAvailability();
        System.out.println("FROST server is available.");

        // Collect datastream info (lightweight - just paths and IDs, no observations loaded yet)
        List<DatastreamUploadTask> datastreamTasks = new ArrayList<>();

        // Count total things for progress reporting
        List<Path> thingDirList = new ArrayList<>();
        try (DirectoryStream<Path> thingDirs = Files.newDirectoryStream(thingsDir, Files::isDirectory)) {
            for (Path thingDir : thingDirs) {
                thingDirList.add(thingDir);
            }
        }

        int totalThings = thingDirList.size();
        System.out.println("Processing " + totalThings + " thing(s)...");
        int thingCount = 0;
        int lastPrintedPct = -1;

        for (Path thingDir : thingDirList) {
            thingCount++;
            try {
                uploadThingAndCollectDatastreamTasks(thingDir, datastreamTasks);
            } catch (FROSTServiceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("Error uploading thing " + thingDir.getFileName() + ": " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println("  Cause: " + e.getCause().getMessage());
                }
            }
            int pct = totalThings > 0 ? (int) (100 * thingCount / totalThings) : 100;
            if (pct != lastPrintedPct && (pct % 10 == 0 || pct == 100)) {
                System.out.println("  Things: " + pct + "% (" + thingCount + "/" + totalThings + ")");
                lastPrintedPct = pct;
            }
        }

        if (!uploadObservations) {
            System.out.println("Structure-only upload: skipping observation batches.");
            return;
        }

        if (datastreamTasks.isEmpty()) {
            System.out.println("No datastreams with observations to upload.");
            return;
        }

        int totalDatastreams = datastreamTasks.size();
        System.out.println("Uploading observations for " + totalDatastreams + " datastream(s) in parallel ("
                + observationUploadParallelism + " threads)...");
        
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(observationUploadParallelism);
        AtomicInteger completedDatastreams = new AtomicInteger(0);
        AtomicInteger inProgressDatastreams = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger lastPrintedObsCount = new AtomicInteger(0);
        AtomicReference<FROSTServiceUnavailableException> serviceUnavailable = new AtomicReference<>();
        AtomicReference<FROSTBatchUploadException> fatalBatchError = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        Instant observationsPhaseStart = Instant.now();

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "upload-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            int completed = completedDatastreams.get();
            int inProgress = inProgressDatastreams.get();
            int failed = failedCount.get();
            int active = executor.getActiveCount();
            int queued = executor.getQueue().size();
            long elapsedSec = Duration.between(observationsPhaseStart, Instant.now()).getSeconds();
            System.out.println("  Upload heartbeat: elapsed=" + elapsedSec + "s completed=" + completed + "/"
                    + totalDatastreams + " inProgress=" + inProgress + " activeThreads=" + active + " queued=" + queued
                    + " failed=" + failed);
        }, UPLOAD_HEARTBEAT_SECONDS, UPLOAD_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        // Submit tasks immediately (lazy loading - observations loaded only when task executes)
        for (DatastreamUploadTask task : datastreamTasks) {
            int taskNumber = submittedCount.incrementAndGet();
            Future<?> future = executor.submit(() -> {
                if (serviceUnavailable.get() != null || fatalBatchError.get() != null) {
                    return;
                }
                String threadName = Thread.currentThread().getName();
                inProgressDatastreams.incrementAndGet();
                System.out.println("  [" + threadName + "] Starting datastream " + taskNumber + "/"
                        + totalDatastreams + ": " + task.datastreamIdProp);
                Instant taskStart = Instant.now();
                try {
                    executeDatastreamUpload(task);
                } catch (FROSTBatchUploadException e) {
                    fatalBatchError.compareAndSet(null, e);
                } catch (FROSTServiceUnavailableException e) {
                    serviceUnavailable.compareAndSet(null, e);
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    System.err.println("Datastream observation upload failed for " + task.datastreamIdProp + ": " + e.getMessage());
                    if (e.getCause() != null) {
                        System.err.println("  Cause: " + e.getCause().getMessage());
                    }
                } finally {
                    inProgressDatastreams.decrementAndGet();
                    int completed = completedDatastreams.incrementAndGet();
                    long taskMs = Duration.between(taskStart, Instant.now()).toMillis();
                    System.out.println("  [" + threadName + "] Finished datastream " + completed + "/"
                            + totalDatastreams + ": " + task.datastreamIdProp + " in " + taskMs + " ms");
                    int lastPrinted = lastPrintedObsCount.get();
                    if (completed - lastPrinted >= 25 || completed == totalDatastreams) {
                        if (lastPrintedObsCount.compareAndSet(lastPrinted, completed)) {
                            int pct = totalDatastreams > 0 ? (int) Math.round(100.0 * completed / totalDatastreams) : 100;
                            System.out.println("  Observations: " + pct + "% (" + completed + "/" + totalDatastreams
                                    + " datastreams, activeThreads=" + executor.getActiveCount() + ")");
                        }
                    }
                }
            });
            futures.add(future);
        }

        System.out.println("  Submitted " + futures.size() + " observation upload task(s) to thread pool.");

        // Clear the task list to allow GC of task metadata while waiting
        datastreamTasks.clear();

        // Wait for all tasks to complete
        try {
            for (Future<?> f : futures) {
                FROSTServiceUnavailableException unavailable = serviceUnavailable.get();
                if (unavailable != null) {
                    executor.shutdownNow();
                    throw unavailable;
                }
                FROSTBatchUploadException batchFatal = fatalBatchError.get();
                if (batchFatal != null) {
                    executor.shutdownNow();
                    throw batchFatal;
                }
                try {
                    f.get(OBSERVATION_UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                } catch (Exception e) {
                    // Error already logged in the task
                }
            }
        } finally {
            heartbeat.shutdownNow();
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        }

        FROSTServiceUnavailableException unavailable = serviceUnavailable.get();
        if (unavailable != null) {
            throw unavailable;
        }
        FROSTBatchUploadException batchFatal = fatalBatchError.get();
        if (batchFatal != null) {
            throw batchFatal;
        }
        
        int failed = failedCount.get();
        if (failed > 0) {
            System.err.println("Observation uploads finished with " + failed + " failed datastream(s).");
        } else {
            System.out.println("Observation uploads finished.");
        }
    }

    /**
     * Lightweight container for datastream upload task info.
     * Does NOT hold observations - they are loaded lazily when the task executes.
     */
    private static class DatastreamUploadTask {
        final Long datastreamId;
        final String datastreamIdProp;
        final Path observationsDir;

        DatastreamUploadTask(Long datastreamId, String datastreamIdProp, Path observationsDir) {
            this.datastreamId = datastreamId;
            this.datastreamIdProp = datastreamIdProp;
            this.observationsDir = observationsDir;
        }
    }

    private static final String OBSERVATIONS_BATCH_PREFIX = "observations_";

    /**
     * Sort batch files by number, then upload first batch, then last batch, then the rest in order.
     * Example for 5 batches: 1, 5, 2, 3, 4.
     */
    private static List<Path> orderBatchFilesForUpload(List<Path> batchFiles) {
        batchFiles.sort(Comparator.comparingInt(STAtoFrostUploader::batchFileNumber));
        if (batchFiles.size() <= 2) {
            return batchFiles;
        }
        List<Path> ordered = new ArrayList<>(batchFiles.size());
        ordered.add(batchFiles.get(0));
        ordered.add(batchFiles.get(batchFiles.size() - 1));
        for (int i = 1; i < batchFiles.size() - 1; i++) {
            ordered.add(batchFiles.get(i));
        }
        return ordered;
    }

    private static int batchFileNumber(Path batchFile) {
        String name = batchFile.getFileName().toString();
        if (!name.startsWith(OBSERVATIONS_BATCH_PREFIX) || !name.endsWith(".json")) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(OBSERVATIONS_BATCH_PREFIX.length(), name.length() - ".json".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Count batch requests by parsing the pre-serialized file (send uses ofFile, not this string). */
    private static int countBatchRequests(Path batchFile) throws IOException {
        return new JSONObject(Files.readString(batchFile, StandardCharsets.UTF_8)).getJSONArray("requests").length();
    }

    /**
     * Execute a datastream upload task - loads and uploads observations in batches.
     * Supports two file formats:
     * 1. Batch files (observations_XXXX.json) containing pre-serialized $batch request body (preferred)
     *    These are sent directly as raw strings - no JSON parsing or serialization during upload!
     * 2. Legacy individual observation files (*.json) containing single JSON objects (backward compatible)
     * 
     * Batch files are processed with maximum efficiency: just read file → send to FROST.
     */
    private void executeDatastreamUpload(DatastreamUploadTask task) throws Exception {
        // Separate batch files from legacy individual files
        List<Path> batchFiles = new ArrayList<>();
        List<Path> legacyFiles = new ArrayList<>();
        try (DirectoryStream<Path> obsFiles = Files.newDirectoryStream(task.observationsDir, p -> p.toString().endsWith(".json"))) {
            for (Path p : obsFiles) {
                String fileName = p.getFileName().toString();
                if (fileName.startsWith(OBSERVATIONS_BATCH_PREFIX)) {
                    batchFiles.add(p);
                } else {
                    legacyFiles.add(p);
                }
            }
        }

        if (batchFiles.isEmpty() && legacyFiles.isEmpty()) return;

        batchFiles = orderBatchFilesForUpload(batchFiles);

        if (uploadVerbose && batchFiles.size() > 2) {
            StringBuilder order = new StringBuilder();
            for (int i = 0; i < batchFiles.size(); i++) {
                if (i > 0) {
                    order.append(", ");
                }
                order.append(batchFiles.get(i).getFileName());
            }
            System.out.println("  Upload order for " + task.datastreamIdProp + " (" + batchFiles.size()
                    + " batches, first/last/middle): " + order);
        }

        if (uploadVerbose) {
            System.out.println("  Uploading " + task.datastreamIdProp + ": " + batchFiles.size() + " batch file(s), "
                    + legacyFiles.size() + " legacy file(s)");
        }

        // Delete existing observations immediately before uploading (to maximize availability)
        if (uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD) {
            Instant deleteStart = Instant.now();
            try {
                client.deleteObservationsByDatastream(task.datastreamId);
            } catch (FROSTServiceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("Error deleting existing observations for datastream " + task.datastreamId 
                        + " (" + task.datastreamIdProp + "): " + e.getMessage());
                // Continue with upload anyway - deterministic IDs will prevent duplicates
            } finally {
                long deleteMs = Duration.between(deleteStart, Instant.now()).toMillis();
                if (uploadVerbose || deleteMs >= SLOW_BATCH_LOG_THRESHOLD_MS) {
                    System.out.println("  Deleted existing observations for " + task.datastreamIdProp + " in " + deleteMs + " ms");
                }
            }
        }

        // Process batch files first - stream file bytes directly (curl --data-binary equivalent)
        for (int batchIndex = 0; batchIndex < batchFiles.size(); batchIndex++) {
            Path batchFile = batchFiles.get(batchIndex);
            Instant batchStart = Instant.now();
            try {
                long batchBytes = Files.size(batchFile);
                int requestCount = countBatchRequests(batchFile);
                client.postBatchFile(batchFile, task.datastreamId, requestCount, task.datastreamIdProp,
                        batchFile.getFileName().toString());
                long batchMs = Duration.between(batchStart, Instant.now()).toMillis();
                System.out.println("  Posted " + batchFile.getFileName() + " (upload " + (batchIndex + 1) + "/"
                        + batchFiles.size() + ") for " + task.datastreamIdProp + ", " + batchBytes + " bytes, "
                        + requestCount + " observations in " + batchMs + " ms");
            } catch (Exception e) {
                System.err.println("Error posting batch file " + batchFile.getFileName() 
                        + " for datastream " + task.datastreamIdProp + ": " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println("  Cause: " + e.getCause().getMessage());
                }
                throw e;
            }
        }

        // Process legacy individual files (backward compatibility - still need to generate IDs)
        if (!legacyFiles.isEmpty()) {
            boolean useDeterministicIds = uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD || 
                                           uploadStrategy == ObservationUploadStrategy.DETERMINISTIC_ID;
            int totalFiles = legacyFiles.size();
            int totalBatches = (totalFiles + maxObservationsPerBatch - 1) / maxObservationsPerBatch;

            for (int batchStart = 0; batchStart < totalFiles; batchStart += maxObservationsPerBatch) {
                int batchEnd = Math.min(batchStart + maxObservationsPerBatch, totalFiles);
                int batchNum = (batchStart / maxObservationsPerBatch) + 1;

                List<Observation> obsBatch = new ArrayList<>(batchEnd - batchStart);
                for (int i = batchStart; i < batchEnd; i++) {
                    Path p = legacyFiles.get(i);
                    Observation obs = new Observation(new JSONObject(Files.readString(p, StandardCharsets.UTF_8)));
                    
                    if (useDeterministicIds) {
                        Long deterministicId = DeterministicIdGenerator.observationId(task.datastreamIdProp,
                                obs.getPhenomenonTime());
                        obs.setId(deterministicId);
                    }
                    obsBatch.add(obs);
                }

                try {
                    client.postObservations(task.datastreamId, obsBatch);
                } catch (Exception e) {
                    System.err.println("Error posting " + obsBatch.size() + " legacy observation(s) for datastream " + task.datastreamId 
                            + " (" + task.datastreamIdProp + ") batch " + batchNum + "/" + totalBatches + ": " + e.getMessage());
                    if (e.getCause() != null) {
                        System.err.println("  Cause: " + e.getCause().getMessage());
                    }
                    throw e;
                }
            }
        }
    }

    private void uploadThingAndCollectDatastreamTasks(Path thingDir, List<DatastreamUploadTask> datastreamTasks) throws Exception {
        Path thingFile = thingDir.resolve(THING_JSON);
        Path locationFile = thingDir.resolve(LOCATION_JSON);
        if (!Files.isRegularFile(thingFile)) return;

        Thing thing = new Thing(new JSONObject(Files.readString(thingFile, StandardCharsets.UTF_8)));
        String siteId = thing.getProperties() != null ? thing.getProperties().optString("siteId", null) : null;
        if (siteId == null || siteId.isEmpty()) {
            System.err.println("Thing missing siteId in " + thingDir + ", skipping.");
            return;
        }

        try {
            PagedResult<Thing> existing = client.getThingsByProperty("siteId", siteId);
            if (!existing.getItems().isEmpty()) {
                Long thingId = existing.getItems().get(0).getId();
                if (uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD) {
                    deleteBeforeReupload(thingDir, thingId);
                    postThingWithDatastreamsAndCollectTasks(thingDir, thing, locationFile, datastreamTasks);
                } else if (forceUpload) {
                    updateExistingThingAndCollectTasks(thingDir, thing, locationFile, thingId, datastreamTasks);
                } else {
                    try (DirectoryStream<Path> dsDirs = Files.newDirectoryStream(thingDir, Files::isDirectory)) {
                        for (Path dsDir : dsDirs) {
                            Path dsFile = dsDir.resolve(DATASTREAM_JSON);
                            if (!Files.isRegularFile(dsFile)) continue;
                            try {
                                collectDatastreamTask(thingId, dsDir, datastreamTasks);
                            } catch (FROSTServiceUnavailableException e) {
                                throw e;
                            } catch (Exception e) {
                                System.err.println("Error processing datastream " + dsDir.getFileName() + " (thing " + thingDir.getFileName() + "): " + e.getMessage());
                            }
                        }
                    }
                }
            } else {
                if (uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD) {
                    deleteBeforeReupload(thingDir, null);
                }
                postThingWithDatastreamsAndCollectTasks(thingDir, thing, locationFile, datastreamTasks);
            }
        } catch (Exception e) {
            System.err.println("Error creating/fetching Thing " + siteId + " (" + thingDir.getFileName() + "): " + e.getMessage());
            throw e;
        }
    }

    /**
     * When forceUpload is true and Thing already exists: PATCH Thing, Location(s), and Datastreams with local metadata, then collect datastream tasks.
     */
    private void updateExistingThingAndCollectTasks(Path thingDir, Thing thing, Path locationFile, Long thingId,
            List<DatastreamUploadTask> datastreamTasks) throws Exception {
        client.patchThing(thingId, thing);

        if (Files.isRegularFile(locationFile)) {
            Location location = new Location(new JSONObject(Files.readString(locationFile, StandardCharsets.UTF_8)));
            PagedResult<Location> existingLocations = client.getThingLocations(thingId);
            if (!existingLocations.getItems().isEmpty()) {
                Long locationId = existingLocations.getItems().get(0).getId();
                client.patchLocation(locationId, location);
            } else {
                client.postLocation(thingId, location);
            }
        }

        try (DirectoryStream<Path> dsDirs = Files.newDirectoryStream(thingDir, Files::isDirectory)) {
            for (Path dsDir : dsDirs) {
                Path dsFile = dsDir.resolve(DATASTREAM_JSON);
                if (!Files.isRegularFile(dsFile)) continue;
                try {
                    collectDatastreamTask(thingId, dsDir, datastreamTasks);
                } catch (FROSTServiceUnavailableException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("Error processing datastream " + dsDir.getFileName() + " (thing " + thingDir.getFileName() + "): " + e.getMessage());
                }
            }
        }
    }

    /**
     * Post one Thing with Location and all Datastreams (each with embedded Sensor and ObservedProperty),
     * then collect datastream upload tasks.
     */
    private void postThingWithDatastreamsAndCollectTasks(Path thingDir, Thing thing, Path locationFile,
            List<DatastreamUploadTask> datastreamTasks) throws Exception {
        List<Datastream> datastreamsForPost = new ArrayList<>();
        List<Path> datastreamDirs = new ArrayList<>();
        try (DirectoryStream<Path> dsDirs = Files.newDirectoryStream(thingDir, Files::isDirectory)) {
            for (Path dsDir : dsDirs) {
                Path dsFile = dsDir.resolve(DATASTREAM_JSON);
                Path opFile = dsDir.resolve(OBSERVED_PROPERTY_JSON);
                Path sensorFile = dsDir.resolve(SENSOR_JSON);
                if (!Files.isRegularFile(dsFile) || !Files.isRegularFile(opFile) || !Files.isRegularFile(sensorFile)) continue;
                Datastream ds = new Datastream(new JSONObject(Files.readString(dsFile, StandardCharsets.UTF_8)));
                String datastreamIdProp = ds.getProperties() != null ? ds.getProperties().optString("datastreamId", null) : null;
                if (datastreamIdProp == null || datastreamIdProp.isEmpty()) continue;
                ds.removeThingReference();
                Sensor sensor = new Sensor(new JSONObject(Files.readString(sensorFile, StandardCharsets.UTF_8)));
                ObservedProperty observedProperty = new ObservedProperty(new JSONObject(Files.readString(opFile, StandardCharsets.UTF_8)));
                if (uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD) {
                    attachSensorForReplace(ds, sensor);
                    attachObservedPropertyForReplace(ds, observedProperty);
                } else {
                    ds.setSensor(sensor);
                    ds.setObservedProperty(observedProperty);
                }
                datastreamsForPost.add(ds);
                datastreamDirs.add(dsDir);
            }
        }
        if (datastreamsForPost.isEmpty()) return;

        Location location = null;
        if (Files.isRegularFile(locationFile)) {
            location = new Location(new JSONObject(Files.readString(locationFile, StandardCharsets.UTF_8)));
            if (uploadStrategy == ObservationUploadStrategy.DELETE_BEFORE_UPLOAD) {
                location = locationForReplace(location);
            }
        }
        client.postThingWithRelations(thing, location != null ? List.of(location) : null, datastreamsForPost);

        // Entities are already posted via postThingWithRelations, just collect tasks
        // We already have the parsed Datastreams, so extract the info we need
        for (int i = 0; i < datastreamsForPost.size(); i++) {
            Datastream ds = datastreamsForPost.get(i);
            Path dsDir = datastreamDirs.get(i);
            Path observationsDir = dsDir.resolve(OBSERVATIONS_DIR);
            
            if (!Files.isDirectory(observationsDir)) continue;
            
            String datastreamIdProp = ds.getProperties() != null ? ds.getProperties().optString("datastreamId", null) : null;
            if (datastreamIdProp == null || datastreamIdProp.isEmpty()) continue;
            
            // Get the deterministic ID from the already-parsed datastream
            Long deterministicDsId = ds.toJSON().has("@iot.id") ? ds.toJSON().getLong("@iot.id") : null;
            
            // Use deterministic ID for the task (batch files have this ID pre-serialized)
            if (deterministicDsId != null) {
                datastreamTasks.add(new DatastreamUploadTask(deterministicDsId, datastreamIdProp, observationsDir));
            } else {
                // Fallback: need to query FROST for the server-assigned ID
                try {
                    PagedResult<Datastream> existingDs = client.getDatastreamsByProperty("datastreamId", datastreamIdProp);
                    if (!existingDs.getItems().isEmpty()) {
                        Long dsId = existingDs.getItems().get(0).getId();
                        datastreamTasks.add(new DatastreamUploadTask(dsId, datastreamIdProp, observationsDir));
                    }
                } catch (Exception e) {
                    System.err.println("Error collecting task for datastream " + dsDir.getFileName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Collect lightweight datastream task info (no observations loaded yet).
     * Observations will be loaded lazily when the task executes.
     * 
     * Uses deterministic @iot.id from the Datastream.json file when available.
     * This allows batch files (which already have the datastream ID pre-serialized) to work correctly.
     */
    private void collectDatastreamTask(Long thingId, Path datastreamDir, List<DatastreamUploadTask> datastreamTasks)
	    throws IOException, InterruptedException {
        Path dsFile = datastreamDir.resolve(DATASTREAM_JSON);
        Path opFile = datastreamDir.resolve(OBSERVED_PROPERTY_JSON);
        Path sensorFile = datastreamDir.resolve(SENSOR_JSON);
        Path observationsDir = datastreamDir.resolve(OBSERVATIONS_DIR);

        JSONObject dsJson = new JSONObject(Files.readString(dsFile, StandardCharsets.UTF_8));
        Datastream datastream = new Datastream(dsJson);
        String datastreamIdProp = datastream.getProperties() != null ? datastream.getProperties().optString("datastreamId", null) : null;
        if (datastreamIdProp == null || datastreamIdProp.isEmpty()) {
            System.err.println("Datastream missing datastreamId in " + datastreamDir + ", skipping.");
            return;
        }

        // Check if we have a pre-computed deterministic @iot.id in the JSON
        Long deterministicDsId = dsJson.has("@iot.id") ? dsJson.getLong("@iot.id") : null;

        Long dsId;
        try {
            PagedResult<Datastream> existingDs = client.getDatastreamsByProperty("datastreamId", datastreamIdProp);
            if (!existingDs.getItems().isEmpty()) {
                Datastream existing = existingDs.getItems().get(0);
                dsId = existing.getId();
                if (forceUpload) {
                    // PATCH existing Datastream, Sensor, ObservedProperty with local metadata
                    JSONObject existingJson = existing.toJSON();
                    Long sensorId = existingJson.has("Sensor") ? existingJson.getJSONObject("Sensor").optLong("@iot.id", 0) : null;
                    if (sensorId != null && sensorId != 0 && Files.isRegularFile(sensorFile)) {
                        Sensor sensor = new Sensor(new JSONObject(Files.readString(sensorFile, StandardCharsets.UTF_8)));
                        client.patchSensor(sensorId, sensor);
                    }
                    Long opId = existingJson.has("ObservedProperty") ? existingJson.getJSONObject("ObservedProperty").optLong("@iot.id", 0) : null;
                    if (opId != null && opId != 0 && Files.isRegularFile(opFile)) {
                        ObservedProperty op = new ObservedProperty(new JSONObject(Files.readString(opFile, StandardCharsets.UTF_8)));
                        client.patchObservedProperty(opId, op);
                    }
                    client.patchDatastream(dsId, datastream);
                }
            } else {
                Long sensorId = null;
                if (!Files.isRegularFile(sensorFile) || !Files.isRegularFile(opFile)) {
                    System.out.println("Missing Sensor or ObservedProperty in " + datastreamDir + ", skipping.");
                } else {
                    Sensor sensor = new Sensor(new JSONObject(Files.readString(sensorFile, StandardCharsets.UTF_8)));
                    Long deterministicSensorId = sensor.toJSON().has("@iot.id") ? sensor.toJSON().getLong("@iot.id") : DeterministicIdGenerator.sensorId(datastreamIdProp);
                    try {
                        client.getSensor(deterministicSensorId);
                        sensorId = deterministicSensorId;
                    } catch (IOException e) {
                        sensorId = client.postSensorReturningId(sensor);
                    }
                }
                ObservedProperty observedProperty = new ObservedProperty(
                        new JSONObject(Files.readString(opFile, StandardCharsets.UTF_8)));
                Long deterministicOpId = observedProperty.toJSON().has("@iot.id") ? observedProperty.toJSON().getLong("@iot.id") : DeterministicIdGenerator.observedPropertyId(datastreamIdProp);
                Long observedPropertyId;
                try {
                    client.getObservedProperty(deterministicOpId);
                    observedPropertyId = deterministicOpId;
                } catch (IOException e) {
                    observedPropertyId = client.postObservedPropertyReturningId(observedProperty);
                }
                datastream.setThingId(thingId);
                datastream.setSensorId(sensorId);
                datastream.setObservedPropertyId(observedPropertyId);
                dsId = client.postDatastreamReturningId(datastream);
                if (dsId == null) throw new IOException("Failed to create Datastream");
            }
        } catch (Exception e) {
            System.err.println("Error creating/fetching Datastream " + datastreamIdProp + " (" + datastreamDir.getFileName() + "): " + e.getMessage());
            throw e;
        }

        // Only collect task if observations directory exists
        if (!Files.isDirectory(observationsDir)) return;

        // For batch files with pre-serialized $batch body, use the deterministic ID
        // (the batch files already have this ID embedded in the URL)
        Long taskDsId = (deterministicDsId != null) ? deterministicDsId : dsId;

        // Collect lightweight task info - observations NOT loaded here (lazy loading)
        datastreamTasks.add(new DatastreamUploadTask(taskDsId, datastreamIdProp, observationsDir));
    }

    /**
     * Remove STA entities that would block a fresh insert of this Thing.
     * Datastream deletion also removes that datastream's observations.
     * An ObservedProperty shared with another Thing is left in place and updated later.
     */
    private void deleteBeforeReupload(Path thingDir, Long serverThingId) throws Exception {
        Set<Long> thingIds = new LinkedHashSet<>();
        Set<Long> datastreamIds = new LinkedHashSet<>();
        Set<Long> sensorIds = new LinkedHashSet<>();
        Set<Long> observedPropertyIds = new LinkedHashSet<>();
        Set<Long> locationIds = new LinkedHashSet<>();

        if (serverThingId != null) {
            thingIds.add(serverThingId);
            collectIdsFromServer(serverThingId, datastreamIds, sensorIds, observedPropertyIds, locationIds);
        }
        collectIdsFromLocalFiles(thingDir, thingIds, datastreamIds, sensorIds, observedPropertyIds, locationIds);

        int deletedDatastreams = 0;
        int deletedThings = 0;
        int deletedLocations = 0;
        int deletedSensors = 0;
        int deletedObservedProperties = 0;

        for (Long datastreamId : datastreamIds) {
            if (deleteDatastreamForReplace(datastreamId)) {
                deletedDatastreams++;
            }
        }
        for (Long thingId : thingIds) {
            try {
                client.deleteThing(thingId);
                deletedThings++;
            } catch (FROSTServiceUnavailableException e) {
                throw e;
            } catch (IOException e) {
                if (!isNotFound(e)) {
                    throw e;
                }
            }
        }
        for (Long locationId : locationIds) {
            if (deleteIfPresent("Location", locationId, () -> client.deleteLocation(locationId))) {
                deletedLocations++;
            }
        }
        for (Long sensorId : sensorIds) {
            if (deleteIfPresent("Sensor", sensorId, () -> client.deleteSensor(sensorId))) {
                deletedSensors++;
            }
        }
        for (Long observedPropertyId : observedPropertyIds) {
            if (deleteIfPresent("ObservedProperty", observedPropertyId, () -> client.deleteObservedProperty(observedPropertyId))) {
                deletedObservedProperties++;
            }
        }

        if (serverThingId != null || deletedDatastreams + deletedThings + deletedLocations + deletedSensors + deletedObservedProperties > 0) {
            System.out.println("DELETE_BEFORE_UPLOAD: replaced entities for " + thingDir.getFileName()
                    + " (things=" + deletedThings
                    + ", locations=" + deletedLocations
                    + ", datastreams=" + deletedDatastreams
                    + ", sensors=" + deletedSensors
                    + ", observedProperties=" + deletedObservedProperties + ")");
        }
    }

    private void collectIdsFromServer(Long thingId, Set<Long> datastreamIds, Set<Long> sensorIds,
            Set<Long> observedPropertyIds, Set<Long> locationIds) throws IOException, InterruptedException {
        PagedResult<Datastream> datastreams = client.get("Things(" + thingId + ")/Datastreams", null,
                "Sensor,ObservedProperty", Datastream.class);
        while (datastreams != null) {
            for (Datastream datastream : datastreams.getItems()) {
                if (datastream.getId() != null) {
                    datastreamIds.add(datastream.getId());
                }
                addNavigationId(sensorIds, datastream.toJSON(), "Sensor");
                addNavigationId(observedPropertyIds, datastream.toJSON(), "ObservedProperty");
            }
            if (!datastreams.hasNext()) {
                break;
            }
            datastreams = client.getDatastreamsByNextLink(datastreams.getNextLink());
        }

        PagedResult<Location> locations = client.getThingLocations(thingId);
        while (locations != null) {
            for (Location location : locations.getItems()) {
                if (location.getId() != null) {
                    locationIds.add(location.getId());
                }
            }
            if (!locations.hasNext()) {
                break;
            }
            locations = client.getLocationsByNextLink(locations.getNextLink());
        }
    }

    private void collectIdsFromLocalFiles(Path thingDir, Set<Long> thingIds, Set<Long> datastreamIds,
            Set<Long> sensorIds, Set<Long> observedPropertyIds, Set<Long> locationIds) throws IOException {
        addFileId(thingIds, thingDir.resolve(THING_JSON));
        addFileId(locationIds, thingDir.resolve(LOCATION_JSON));
        try (DirectoryStream<Path> dsDirs = Files.newDirectoryStream(thingDir, Files::isDirectory)) {
            for (Path dsDir : dsDirs) {
                addFileId(datastreamIds, dsDir.resolve(DATASTREAM_JSON));
                addFileId(sensorIds, dsDir.resolve(SENSOR_JSON));
                addFileId(observedPropertyIds, dsDir.resolve(OBSERVED_PROPERTY_JSON));
            }
        }
    }

    private static void addFileId(Set<Long> ids, Path jsonFile) throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            return;
        }
        addJsonId(ids, new JSONObject(Files.readString(jsonFile, StandardCharsets.UTF_8)));
    }

    private static void addNavigationId(Set<Long> ids, JSONObject parent, String key) {
        JSONObject navigation = parent.optJSONObject(key);
        if (navigation != null) {
            addJsonId(ids, navigation);
        }
    }

    private static void addJsonId(Set<Long> ids, JSONObject json) {
        if (json != null && json.has("@iot.id")) {
            ids.add(json.getLong("@iot.id"));
        }
    }

    private static Long jsonId(JSONObject json) {
        return json != null && json.has("@iot.id") ? json.getLong("@iot.id") : null;
    }

    /**
     * Delete a datastream. If observations block the delete, remove them and retry.
     * @return true when a datastream was deleted
     */
    private boolean deleteDatastreamForReplace(Long datastreamId) throws Exception {
        try {
            client.deleteDatastream(datastreamId);
            return true;
        } catch (FROSTServiceUnavailableException e) {
            throw e;
        } catch (IOException e) {
            if (isNotFound(e)) {
                return false;
            }
            client.deleteObservationsByDatastream(datastreamId);
            try {
                client.deleteDatastream(datastreamId);
                return true;
            } catch (IOException retry) {
                if (isNotFound(retry)) {
                    return false;
                }
                throw retry;
            }
        }
    }

    @FunctionalInterface
    private interface FrostCall {
        void run() throws Exception;
    }

    /** @return true when the entity was deleted */
    private boolean deleteIfPresent(String label, Long id, FrostCall call) throws Exception {
        try {
            call.run();
            return true;
        } catch (FROSTServiceUnavailableException e) {
            throw e;
        } catch (IOException e) {
            if (isNotFound(e)) {
                return false;
            }
            System.out.println("  Kept " + label + " " + id + " for in-place update (" + e.getMessage() + ")");
            return false;
        }
    }

    private void attachSensorForReplace(Datastream datastream, Sensor sensor) throws IOException, InterruptedException {
        Long id = jsonId(sensor.toJSON());
        if (id != null && entityExists(() -> client.getSensor(id))) {
            client.patchSensor(id, sensor);
            datastream.setSensorId(id);
        } else {
            datastream.setSensor(sensor);
        }
    }

    private void attachObservedPropertyForReplace(Datastream datastream, ObservedProperty observedProperty)
            throws IOException, InterruptedException {
        Long id = jsonId(observedProperty.toJSON());
        if (id != null && entityExists(() -> client.getObservedProperty(id))) {
            client.patchObservedProperty(id, observedProperty);
            datastream.setObservedPropertyId(id);
        } else {
            datastream.setObservedProperty(observedProperty);
        }
    }

    /**
     * Link a Location that is still on the server after updating it. Shared observed properties use the same pattern.
     */
    private Location locationForReplace(Location location) throws IOException, InterruptedException {
        Long id = jsonId(location.toJSON());
        if (id != null && entityExists(() -> client.getLocation(id))) {
            client.patchLocation(id, location);
            return new Location(new JSONObject().put("@iot.id", id));
        }
        return location;
    }

    @FunctionalInterface
    private interface ExistenceCheck {
        void run() throws IOException, InterruptedException;
    }

    private boolean entityExists(ExistenceCheck check) throws IOException, InterruptedException {
        try {
            check.run();
            return true;
        } catch (FROSTServiceUnavailableException e) {
            throw e;
        } catch (IOException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    private static boolean isNotFound(IOException e) {
        String message = e.getMessage();
        return message != null && message.contains("status 404");
    }
}
