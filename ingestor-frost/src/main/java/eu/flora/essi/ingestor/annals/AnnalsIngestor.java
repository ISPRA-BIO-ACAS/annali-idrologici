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

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import eu.flora.essi.frost.Datastream;
import eu.flora.essi.frost.Datastream.UnitOfMeasurement;
import eu.flora.essi.frost.FROSTBatchUploadException;
import eu.flora.essi.frost.FROSTClient;
import eu.flora.essi.frost.FROSTServiceUnavailableException;
import eu.flora.essi.frost.Location;
import eu.flora.essi.frost.Observation;
import eu.flora.essi.frost.ObservedProperty;
import eu.flora.essi.frost.PagedResult;
import eu.flora.essi.frost.Sensor;
import eu.flora.essi.frost.Thing;
import eu.flora.essi.ingestor.sta.ObservationUploadStrategy;
import eu.flora.essi.ingestor.sta.STAtoFrostUploader;

public class AnnalsIngestor {

    private static final String ANNALS_SITE_ID = "annalsSiteId";
    private static final String ANNALS_SENSOR_ID = "annalsSensorId";
    private static final String ANNALS_DATASTREAM_ID = "annalsDatastreamId";
    private static final String WATERSHED = "watershed";
    private static final String DISCLAIMER = "disclaimer";
    private static final String FUNDING = "funding";
    private static final String DISTRICT = "district";
    private static final String TERRITORY_OF_ORIGIN = "territoryOfOrigin";
    private static final String DATA_SOURCE = "dataSource";
    private static final String LICENCE = "licence";
    private static final String RESULT_TYPE = "resultType";
    private static final String SAMPLED_MEDIUM = "sampledMedium";
    private static final String INTENDED_TIME_SPACING = "intendedTimeSpacing";
    private static final String AGGREGATION_PERIOD = "aggregationPeriod";
    private static final String AGGREGATION_STATISTIC = "aggregationStatistic";
    private static final String RESPONSIBLE_PARTIES = "responsibleParties";
    private static final String QUALIFIERS = "qualifiers";
    private static final String REFERENCE_CITATION = "referenceCitation";
    private static final String EDITOR_INDIVIDUAL = "editorIndividual";
    private static final String EDITOR_ORGANIZATION = "editorOrganization";
    private static final String EDITOR_ROLE = "editorRole";
    private static final String ANNAL_STATION_NAME = "annalStationName";
    private static final String ANNAL_BASIN_NAME = "annalBasinName";
    private static final String ANNAL_INSTRUMENT_TYPE = "annalInstrumentType";
    private static final String ANNAL_INSTRUMENT_QUOTE = "annalInstrumentQuote";
    private static final String SAMPLING_FEATURE_CODE = "samplingFeatureCode";
    private static final String VARIABLE_CODE = "variableCode";
    private static final String ANNALS_VERSION = "annalVersion";

    /** Number of concurrent observation batch uploads (different datastreams uploaded in parallel). */
    private static final int UPLOAD_PARALLELISM = Math.min(16, Math.max(2, Runtime.getRuntime().availableProcessors() * 2));

    private Compartments compartments;
    private QualityFlags qualityFlags;

    private GeneralMetadata generalMetadata;
    private ObservedProperties observedProperties;
    private Instruments instruments;
    private UnitsOfMeasurement units;
    private EditorOrganizations organizations;

    private List<File> observationFiles;

    private Stations stations = new Stations(null);

    private TimeSeries timeSeries = new TimeSeries(null);
    private FROSTClient client;
    private boolean fast;
    private boolean logRequests;

    // New fields for STA folder mapping and upload
    private final String localFolder;
    private final String frostUrl;
    private final int maxObservationsPerBatch;
    private final ObservationUploadStrategy uploadStrategy;
    private final boolean useGzipCompression;

    public Stations getStations() {
	return stations;
    }

    public EditorOrganizations getOrganizations() {
	return organizations;
    }

    public TimeSeries getTimeSeries() {
	return timeSeries;
    }

    public Compartments getCompartments() {
	return compartments;
    }

    public FROSTClient getClient() {
	return client;
    }

    public QualityFlags getQualityFlags() {
	return qualityFlags;
    }

    public GeneralMetadata getGeneralMetadata() {
	return generalMetadata;
    }

    public ObservedProperties getObservedProperties() {
	return observedProperties;
    }

    public Instruments getInstruments() {
	return instruments;
    }

    public UnitsOfMeasurement getUnits() {
	return units;
    }

    public AnnalsIngestor(File rawDataFolder, File processedDataFolder, String url, String localFolder, boolean fast, boolean logRequests,
	    int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy) throws Exception {
	this(rawDataFolder, processedDataFolder, url, localFolder, fast, logRequests, maxObservationsPerBatch, uploadStrategy, false);
    }

    /**
     * @param useGzipCompression enable gzip compression for HTTP requests (requires server support)
     */
    public AnnalsIngestor(File rawDataFolder, File processedDataFolder, String url, String localFolder, boolean fast, boolean logRequests,
	    int maxObservationsPerBatch, ObservationUploadStrategy uploadStrategy, boolean useGzipCompression) throws Exception {

	this.fast = fast;
	this.logRequests = logRequests;
	this.localFolder = localFolder;
	this.frostUrl = url;
	this.maxObservationsPerBatch = maxObservationsPerBatch;
	this.uploadStrategy = uploadStrategy != null ? uploadStrategy : ObservationUploadStrategy.DELETE_BEFORE_UPLOAD;
	this.useGzipCompression = useGzipCompression;
	FROSTClient.logRequests = logRequests;
	this.client = new FROSTClient(url);

	this.compartments = new Compartments(new File(rawDataFolder, "COMPARTIMENTO.csv"));
	this.qualityFlags = new QualityFlags(new File(rawDataFolder, "FLAG_QUALITY.csv"));
	this.generalMetadata = new GeneralMetadata(new File(rawDataFolder, "GENERAL_METADATA.csv"));
	this.observedProperties = new ObservedProperties(new File(rawDataFolder, "TIPO_GRANDEZZA.csv"));
	this.instruments = new Instruments(new File(rawDataFolder, "TIPO_STRUMENTO.csv"));
	this.units = new UnitsOfMeasurement(new File(rawDataFolder, "UNITA_MISURA_UDM.csv"));
	this.organizations = new EditorOrganizations(new File(rawDataFolder, "ENTE_COMPILATORE_rev.csv"));
	File[] files = processedDataFolder.listFiles();
	this.observationFiles = new ArrayList<File>();
	if (files != null) {
	    for (File file : files) {
		if (file.isDirectory()) {
		    File[] childrenFiles = file.listFiles();
		    if (childrenFiles == null) {
			continue;
		    }
		    for (File childFile : childrenFiles) {
			String childName = childFile.getName().toLowerCase();
			if (childName.contains("osservazioni")) {
			    observationFiles.add(childFile);
			} else if (childName.contains("serie")) {
			    timeSeries.loadCSVFile(childFile);
			} else if (childName.contains("stazioni")) {
			    stations.loadCSVFile(childFile);
			}
		    }
		}
	    }
	}
    }

    private HashMap<String, Long> sensorIdentifiers = new HashMap<String, Long>();

    private HashMap<String, Long> observedPropertyIdentifiers = new HashMap<String, Long>();

    private HashMap<String, Thing> thingsCache = new HashMap<String, Thing>();

    public void ingestData() throws Exception {
	Instant overallStart = Instant.now();
	System.out.println("=== Annals ingestion started at " + overallStart + " ===");
	ExecutorService uploadExecutor = null;
	List<Future<?>> pendingUploads = new ArrayList<>();
	AtomicLong totalObservationsSubmitted = new AtomicLong(0);
	Set<Long> datastreamIdsWithUploads = new HashSet<>();
	try {

	    System.out.println("Starting ingestion");
	    uploadExecutor = Executors.newFixedThreadPool(UPLOAD_PARALLELISM);
	    System.out.println("Ingesting sensors");
	    HashSet<String> instrumentClasses = instruments.getClasses();
	    for (String instrumentClass : instrumentClasses) {
		String instrumentLabel = instruments.getClassLabelByClass(instrumentClass);
		Long id = ensureSensor(instrumentClass, instrumentLabel);
		sensorIdentifiers.put(instrumentClass, id);
	    }
	    System.out.println("Ingesting observed properties");
	    Set<String> observedPropCodes = observedProperties.getKeys();
	    for (String observedPropCode : observedPropCodes) {
		String uri = observedProperties.getObservedPropertyURI(observedPropCode);
		String label = uri;
		switch (uri) {
		case "http://codes.wmo.int/wmdr/ObservedVariableAtmosphere/210":
		    label = "Amount of precipitation";
		    break;
		case "http://codes.wmo.int/wmdr/ObservedVariableAtmosphere/224":
		    label = "Air temperature (at specified distance from reference surface)";
		    break;
		default:
		    label = uri;
		    break;
		}

		Long id = ensureObservedProperty(uri, label);
		observedPropertyIdentifiers.put(uri, id);

	    }

	    Set<Entry<String, CSVRecord>> entries = stations.getMap().entrySet();

	    boolean ingestThingsAtBoot = !fast;
	    if (ingestThingsAtBoot) {
		System.out.println("Ingesting things (" + entries.size() + ")");
		int t = 0;
		for (Entry<String, CSVRecord> entry : entries) {
		    if (t % 100 == 0) {
			System.out.print(t + " ... ");
		    }
		    CSVRecord record = entry.getValue();
		    String compartment = record.get("Compartimento");
		    String basin = record.get("ALIAS_BACINO");
		    String station = record.get("ALIAS_STAZIONE");
		    ingestThing(compartment, basin, station);
		    t++;
		}
		System.out.println("Ingested " + t + " things");
	    }
	    long observationPhaseStartMs = System.currentTimeMillis();
	    System.out.println("Ingesting observations (parallel uploads: " + UPLOAD_PARALLELISM + ")");
	    for (int i = 0; i < observationFiles.size(); i++) {
		File observationFile = observationFiles.get(i);
		System.out.println("At file: " + observationFile.getName() + " (" + (i + 1) + "/" + observationFiles.size() + ")");
		System.out.println(new Date());
		Path path = Paths.get(observationFile.toURI());
		long totalLines = Files.lines(path).count();
		Reader in = new FileReader(observationFile);
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in);
		int total = 0;
		List<Observation> observations = new ArrayList<Observation>();
		Long targetDatastream = null;
		String citationAuthor = generalMetadata.getReferenceAuthor();
		String citationTitle = generalMetadata.getReferenceTitle();
		String dataSource = generalMetadata.getDataSource();
		ZoneId rome = ZoneId.of(generalMetadata.getTimeZone());
		for (CSVRecord record : records) {
		    try {
			total++;
			if (fast && datastreamIdsWithUploads.size() > 5) {
			    return;
			}
			if (datastreamIdsWithUploads.size() != 0 && datastreamIdsWithUploads.size() % 10 == 0) {
			    double percent = ((double) total / (double) totalLines) * 100.0;
			    BigDecimal p = new BigDecimal(percent);
			    BigDecimal rounded = p.setScale(2, RoundingMode.HALF_UP);
			    long elapsedMs = System.currentTimeMillis() - observationPhaseStartMs;
			    double rs = elapsedMs > 0 ? 1000.0 * totalObservationsSubmitted.get() / elapsedMs : 0;
			    BigDecimal roundedRss = new BigDecimal(rs).setScale(2, RoundingMode.HALF_UP);
			    System.out.print(rounded + "% (" + roundedRss + " res/sec) ... (DS " + datastreamIdsWithUploads.size() + ")");
			}
			String compartment = record.get("COMPARTIMENTO");
			String basin = record.get("ALIAS_BACINO");
			String station = record.get("ALIAS_STAZIONE");

			String annalPart = record.get("PARTE_ANNALE");
			String page = record.get("PAGINA");
			String orderTable = record.get("TABELLA_ORDINE");
			String annalStationName = record.get("NOME_STAZIONE_ANNALE");
			String annalBasinName = record.get("NOME_BACINO_ANNALE");
			String annalInstrumentType = record.get("TIPO_STRUMENTO_ANNALE");
			String annalInstrumentQuote = record.get("QUOTA_STRUMENTO_ANNALE");

			Integer year = Integer.parseInt(record.get("ANNO"));
			Integer month = Integer.parseInt(record.get("MESE"));
			Integer day = Integer.parseInt(record.get("GIORNO"));
			String hour = record.get("ORA");
			String observedProperty = record.get("GRANDEZZA");
			BigDecimal value = new BigDecimal(record.get("VALORE"));
			String flag = record.get("FLAG_VALORE");
			String editorOrganizationCode = record.get("SIGLA_ENTE_COMPILATORE");
			String editorName = record.get("NOME_COMPILATORE");

			ingestThing(compartment, basin, station);

			Datastream ds = getDataStream(compartment, basin, station, observedProperty);

			String[] hourSplit = hour.split(":");
			Integer h = Integer.parseInt(hourSplit[0]);
			Integer m = Integer.parseInt(hourSplit[1]);
			LocalDateTime localDateTime;
			try {
			    localDateTime = LocalDateTime.of(year, month, day, h, m);
			} catch (Exception e) {
			    System.err.println("Problem with date: " + year + "-" + month + "-" + h + ":" + m);
			    System.out.println("... at file " + observationFile.getName() + ", line " + total);
			    continue;
			}
			ZonedDateTime romeTime = localDateTime.atZone(rome);
			ZonedDateTime utcTime = romeTime.withZoneSameInstant(ZoneOffset.UTC);
			String time = utcTime.format(DateTimeFormatter.ISO_INSTANT);

			Observation o = new Observation();
			o.setPhenomenonTime(time);
			o.setResult(value);
			String flagDescription = qualityFlags.getFlagDescription(flag);
			if (flagDescription == null) {
			    System.err.println(
				    "Problem with unexpected flag " + flag + " at " + observationFile.getName() + " line " + total);
			    flagDescription = flag;
			}
			o.setResultQuality(flag, flagDescription);

			JSONObject obsProperties = new JSONObject();
			obsProperties.put(REFERENCE_CITATION,
				citationAuthor + ". " + citationTitle + ". Compartimento di " + compartments.getName(
					compartment) + ". Anno " + year + ". Parte " + annalPart + ". Pagina " + page + ". Posizione nella Pagina " + orderTable + ". Disponibile online: " + dataSource);
			obsProperties.put(EDITOR_INDIVIDUAL, editorName);
			String editorOrganizationName = organizations.getName(editorOrganizationCode);
			obsProperties.put(EDITOR_ORGANIZATION, editorOrganizationName);
			String editorOrganizationRole = organizations.getRole(editorOrganizationCode);
			obsProperties.put(EDITOR_ROLE, editorOrganizationRole);

			obsProperties.put(ANNAL_STATION_NAME, annalStationName);
			obsProperties.put(ANNAL_BASIN_NAME, annalBasinName);
			obsProperties.put(ANNAL_INSTRUMENT_TYPE, annalInstrumentType);
			obsProperties.put(ANNAL_INSTRUMENT_QUOTE, annalInstrumentQuote);
			o.setParameters(obsProperties);

			boolean needToPatchDSMetadata = false;
			JSONObject dsQualifiers = ds.getProperties().optJSONObject(QUALIFIERS);
			if (dsQualifiers == null) {
			    needToPatchDSMetadata = true;
			    dsQualifiers = new JSONObject();
			    ds.getProperties().put(QUALIFIERS, dsQualifiers);
			}
			String flagDesc = dsQualifiers.optString(flag);
			if (flagDesc.isEmpty()) {
			    dsQualifiers.put(flag, flagDescription);
			    needToPatchDSMetadata = true;
			}
			JSONArray dsOrganizations = ds.getProperties().getJSONArray(RESPONSIBLE_PARTIES);
			JSONObject org = null;
			for (int j = 0; j < dsOrganizations.length(); j++) {
			    JSONObject dsOrg = dsOrganizations.getJSONObject(j);
			    String existingOrgName = dsOrg.optString("organizationName");
			    if (existingOrgName.equals(editorOrganizationName)) {
				org = dsOrg;
			    }
			}

			if (org == null) {
			    org = organizations.getJSONObject(editorOrganizationCode);
			    dsOrganizations.put(org);
			    needToPatchDSMetadata = true;
			} else {
			    boolean added = organizations.addIndividualNameToOrganization(org, editorName);
			    if (added) {
				needToPatchDSMetadata = true;
			    }
			}
			if (needToPatchDSMetadata) {
			    Long dsid = ds.getId();
			    client.patchDatastream(dsid, ds);
			    ds = client.getDatastream(dsid);
			    String id = generateDatastreamId(compartment, basin, station, observedProperty);
			    datastreamCache.put(id, ds);
			}

			if (targetDatastream == null) {
			    targetDatastream = ds.getId();
			} else {
			    if (!targetDatastream.equals(ds.getId())) {
				final Long batchDatastreamId = targetDatastream;
				datastreamIdsWithUploads.add(batchDatastreamId);
				final List<Observation> batch = new ArrayList<>(observations);
				pendingUploads.add(uploadExecutor.submit(() -> {
				    try {
					client.postObservations(batchDatastreamId, batch);
					totalObservationsSubmitted.addAndGet(batch.size());
				    } catch (Exception e) {
					throw new RuntimeException(e);
				    }
				}));
				targetDatastream = ds.getId();
				observations.clear();
			    }
			}
			observations.add(o);

		    } catch (Exception e) {
			e.printStackTrace();
			System.out.println("Problems at file " + observationFile.getName() + ", line " + total);
			return;
		    }

		}
		if (!observations.isEmpty()) {
		    final Long batchDatastreamId = targetDatastream;
		    datastreamIdsWithUploads.add(batchDatastreamId);
		    final List<Observation> lastBatch = new ArrayList<>(observations);
		    pendingUploads.add(uploadExecutor.submit(() -> {
			try {
			    client.postObservations(batchDatastreamId, lastBatch);
			    totalObservationsSubmitted.addAndGet(lastBatch.size());
			} catch (Exception e) {
			    throw new RuntimeException(e);
			}
		    }));
		}
	    }
	} finally {
	    for (Future<?> f : pendingUploads) {
		try {
		    f.get(10, TimeUnit.MINUTES);
		} catch (ExecutionException e) {
		    throw new RuntimeException("Observation batch upload failed: " + e.getCause().getMessage(), e.getCause());
		} catch (InterruptedException e) {
		    Thread.currentThread().interrupt();
		    throw new RuntimeException("Interrupted while waiting for uploads", e);
		} catch (TimeoutException e) {
		    throw new RuntimeException("Observation batch upload timed out", e);
		}
	    }
	    if (uploadExecutor != null) {
		uploadExecutor.shutdown();
		try {
		    if (!uploadExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
			uploadExecutor.shutdownNow();
		    }
		} catch (InterruptedException e) {
		    Thread.currentThread().interrupt();
		    uploadExecutor.shutdownNow();
		}
	    }
	    System.out.println("Ended ingestion");
	    System.out.println("=== Total datastreams ingested: " + datastreamIdsWithUploads.size() + " ===");
	    System.out.println("=== Total observations ingested: " + totalObservationsSubmitted.get() + " ===");
	    Instant overallEnd = Instant.now();
	    Duration overallDuration = Duration.between(overallStart, overallEnd);
	    long seconds = overallDuration.getSeconds();
	    long absSeconds = Math.abs(seconds);
	    String humanDuration = String.format("%d:%02d:%02d.%03d", //
		    absSeconds / 3600, //
		    (absSeconds % 3600) / 60, //
		    absSeconds % 60, //
		    overallDuration.toMillisPart());
	    System.out.println("=== Annals ingestion ended at " + overallEnd + " ===");
	    System.out.println("=== Annals ingestion duration: " + humanDuration + " (HH:mm:ss.SSS) ===");
	}
    }

    private Map<String, Datastream> datastreamCache = new HashMap<>();

    private Datastream getDataStream(String compartment, String basin, String station, String observedProperty) throws Exception {
	String id = generateDatastreamId(compartment, basin, station, observedProperty);
	Datastream ret = datastreamCache.get(id);
	if (ret != null) {
	    return ret;
	}
	PagedResult<Datastream> response = client.getDatastreamsByProperty(ANNALS_DATASTREAM_ID, id);
	if (response.getItems().isEmpty()) {
	    return null;
	} else {
	    Datastream ds = response.getItems().get(0);
	    datastreamCache.put(id, ds);
	    return ds;
	}
    }

    private Thing getThing(String compartment, String basin, String station) throws Exception {

	String id = generateThingId(compartment, basin, station);

	Thing ret = thingsCache.get(id);
	if (ret != null) {
	    return ret;
	}

	PagedResult<Thing> response = client.getThingsByProperty(ANNALS_SITE_ID, id);
	if (response.getItems().isEmpty()) {
	    return null;
	} else {
	    ret = response.getItems().get(0);
	    thingsCache.put(id, ret);
	    return ret;
	}

    }

    private void addThingProperty(String compartment, String basin, String station, String propertyName, String propertyValue)
	    throws Exception {

	Thing thing = getThing(compartment, basin, station);

	JSONObject properties = thing.getProperties();
	if (properties == null) {
	    properties = new JSONObject();
	    thing.setProperties(properties);
	}
	properties.put(propertyName, propertyValue);
	client.patchThing(thing.getId(), thing);
    }

    private Thing ingestThing(String compartment, String basin, String station) throws Exception {

	Thing thing = getThing(compartment, basin, station);

	if (thing != null) {
	    return thing;
	}

	String description = "Compartimento: " + compartment + ", bacino: " + basin + ", stazione: " + station;
	JSONArray orgs = new JSONArray();
	for (String code : organizations.getCodes()) {
	    boolean general = organizations.isGeneral(code);
	    if (general) {
		JSONObject org = organizations.getJSONObject(code);
		orgs.put(org);
	    }
	}
	thing = new Thing();
	JSONObject properties = new JSONObject();
	String thingId = generateThingId(compartment, basin, station);
	properties.put(ANNALS_SITE_ID, thingId);
	properties.put(WATERSHED, basin);
	properties.put(DISTRICT, compartments.getName(compartment));
	properties.put(TERRITORY_OF_ORIGIN, generalMetadata.getTerritoryName());
	properties.put(DISCLAIMER, generalMetadata.getDisclaimer());
	properties.put(FUNDING, generalMetadata.getFunding());
	properties.put(DATA_SOURCE, generalMetadata.getDataSource());
	properties.put(LICENCE, generalMetadata.getLicence());
	properties.put(SAMPLING_FEATURE_CODE, thingId);
	properties.put(RESPONSIBLE_PARTIES, orgs);
	thing.setProperties(properties);
	thing.setName(station);
	thing.setDescription(description);
	BigDecimal lon = stations.getXLong(compartment, basin, station);
	BigDecimal lat = stations.getYLat(compartment, basin, station);
	BigDecimal elevation = stations.getZmslm(compartment, basin, station);
	Location location = new Location(station, description, lon, lat, elevation);
	thing.setLocation(location);

	List<Datastream> datastreams = new ArrayList<Datastream>();
	Set<String> observedProps = timeSeries.getObservedProperties(compartment, basin, station);

	for (String observedProperty : observedProps) {
	    String version = timeSeries.getVersion(compartment, basin, station, observedProperty);
	    String instrumentClass = observedProperties.getInstrumentClass(observedProperty);
	    String uri = observedProperties.getObservedPropertyURI(observedProperty);
	    String medium = observedProperties.getSampleMedium(observedProperty);
	    String interpolationType = observedProperties.getInterpolationType(observedProperty);
	    String aggregationPeriod = observedProperties.getAggregationPeriod(observedProperty);
	    String observedPropertyDescription = observedProperties.getObservedPropertyDescription(observedProperty);

	    String uomSymbol = units.getUnitsOfMeasurement(instrumentClass);
	    String uomName = units.getUnitsOfMeasurementDescription(instrumentClass);

	    UnitOfMeasurement uom = new UnitOfMeasurement(uomName, uomSymbol);
	    String dsName = station + " - " + observedPropertyDescription;
	    Datastream ds = new Datastream(dsName, observedPropertyDescription,
		    "http://www.opengis.net/def/observationType/OGC-OM/2.0/OM_Measurement", uom);

	    Long observedPropertyId = observedPropertyIdentifiers.get(uri);
	    ds.setObservedPropertyId(observedPropertyId);
	    Long sensorId = sensorIdentifiers.get(instrumentClass);
	    ds.setSensorId(sensorId);
	    JSONObject dsProperties = new JSONObject();
	    dsProperties.put(RESULT_TYPE, "Timeseries");
	    dsProperties.put(SAMPLED_MEDIUM, medium);
	    dsProperties.put(ANNALS_DATASTREAM_ID, generateDatastreamId(compartment, basin, station, observedProperty));
	    dsProperties.put(ANNALS_VERSION, version);
	    dsProperties.put(INTENDED_TIME_SPACING, aggregationPeriod);
	    dsProperties.put(AGGREGATION_STATISTIC, interpolationType);
	    dsProperties.put(AGGREGATION_PERIOD, aggregationPeriod);
	    dsProperties.put(DISCLAIMER, generalMetadata.getDisclaimer());
	    dsProperties.put(WATERSHED, basin);
	    dsProperties.put(DISTRICT, compartments.getName(compartment));
	    dsProperties.put(TERRITORY_OF_ORIGIN, generalMetadata.getTerritoryName());
	    dsProperties.put(FUNDING, generalMetadata.getFunding());
	    dsProperties.put(LICENCE, generalMetadata.getLicence());

	    dsProperties.put(RESPONSIBLE_PARTIES, orgs);

	    ds.setProperties(dsProperties);

	    datastreams.add(ds);
	}
	thing.setDatastreams(datastreams);
	client.postThing(thing);

	return getThing(compartment, basin, station);

    }

    private Long ensureObservedProperty(String uri, String label) throws Exception {
	PagedResult<ObservedProperty> ops = client.getObservedPropertiesByDefinition(uri);
	if (ops.getItems().isEmpty()) {
	    ObservedProperty op = new ObservedProperty();
	    op.setDefinition(uri);
	    op.setName(label);
	    op.setDescription(label);
	    JSONObject props = new JSONObject();
	    props.put(VARIABLE_CODE, uri);
	    op.setProperties(props);
	    client.postObservedProperty(op);
	    ops = client.getObservedPropertiesByDefinition(uri);
	    return ops.getItems().get(0).getId();
	} else {
	    return ops.getItems().get(0).getId();
	}
    }

    private Long ensureSensor(String instrumentClass, String instrumentLabel) throws Exception {
	PagedResult<Sensor> sensors = client.getSensorsByProperty(ANNALS_SENSOR_ID, instrumentClass);
	if (sensors.getItems().isEmpty()) {
	    Sensor sensor = new Sensor(instrumentClass, instrumentLabel, "application/json", "");
	    JSONObject props = new JSONObject();
	    props.put(ANNALS_SENSOR_ID, instrumentClass);
	    sensor.setProperties(props);
	    client.postSensor(sensor);
	    sensors = client.getSensorsByProperty(ANNALS_SENSOR_ID, instrumentClass);
	    return sensors.getItems().get(0).getId();
	} else {
	    return sensors.getItems().get(0).getId();
	}
    }

    private String generateThingId(String compartment, String basin, String station) {
	return compartment + "-" + basin + "-" + station;

    }

    private String generateDatastreamId(String compartment, String basin, String station, String observedProperty) {
	return compartment + "-" + basin + "-" + station + "-" + observedProperty;

    }

    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC);

    /**
     * Prepare raw Annals data: copy regional CSV files, extract ZIP archives, and sort
     * OSSERVAZIONI files into {@code <rawDataFolder>/processed/}.
     */
    public static void prepare(File rawDataFolder, boolean forceOverwrite) throws Exception {
	AnnalsDataPreparer.prepare(rawDataFolder.toPath(), forceOverwrite);
    }

    /**
     * Map Annals CSV data to STA folder structure (without uploading to FROST).
     */
    public void map() {
	Instant start = Instant.now();
	System.out.println("Started mapping at " + ISO_TIME.format(start) + " UTC");

	try {
	    mapToStaFolder();
	} catch (Exception e) {
	    System.err.println("Error during mapping: " + e.getMessage());
	    e.printStackTrace(System.err);
	} finally {
	    printDuration(start);
	}
    }

    /**
     * Upload already-mapped STA data to FROST server using STAtoFrostUploader.
     */
    public void upload() throws FROSTServiceUnavailableException, FROSTBatchUploadException {
	Instant start = Instant.now();
	System.out.println("Started upload at " + ISO_TIME.format(start) + " UTC");

	// Configure gzip compression (must be set before creating FROSTClient instances)
	FROSTClient.useGzipCompression = this.useGzipCompression;
	if (useGzipCompression) {
	    System.out.println("Gzip compression enabled for HTTP requests");
	}

	try {
	    Path staRoot = Paths.get(localFolder, "sta");
	    if (!Files.isDirectory(staRoot)) {
		System.out.println("STA folder not found: " + staRoot + ". Run mapping first.");
		return;
	    }
	    System.out.println("Uploading STA data to FROST server " + frostUrl);
	    System.out.println("  Max observations per batch: " + maxObservationsPerBatch);
	    System.out.println("  Duplicate handling strategy: " + uploadStrategy);
	    int uploadParallelism = getIntEnv("ANNALS_UPLOAD_PARALLELISM", 8);
	    boolean uploadVerbose = getBooleanEnv("ANNALS_UPLOAD_VERBOSE", false);
	    System.out.println("  Observation upload parallelism: " + uploadParallelism);
	    System.out.println("  Observation upload verbose logging: " + uploadVerbose);
	    STAtoFrostUploader uploader = new STAtoFrostUploader(frostUrl, staRoot, maxObservationsPerBatch, uploadStrategy,
		    true, false, uploadParallelism, uploadVerbose);
	    uploader.upload();
	    System.out.println("Upload finished.");
	} catch (FROSTBatchUploadException e) {
	    e.printFatalBanner(System.err);
	    throw e;
	} catch (FROSTServiceUnavailableException e) {
	    System.err.println(e.getMessage());
	    throw e;
	} catch (Exception e) {
	    System.err.println("Error during upload: " + e.getMessage());
	    e.printStackTrace(System.err);
	} finally {
	    printDuration(start);
	}
    }

    private void printDuration(Instant start) {
	Instant end = Instant.now();
	Duration duration = Duration.between(start, end);
	long totalMillis = duration.toMillis();
	long hours = totalMillis / 3_600_000;
	long minutes = (totalMillis % 3_600_000) / 60_000;
	long seconds = (totalMillis % 60_000) / 1_000;
	long millis = totalMillis % 1_000;
	System.out.println("Ended at " + ISO_TIME.format(end) + " UTC");
	System.out.println("Duration: " + String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis));
    }

    /**
     * Map all Annals data to STA folder structure.
     */
    private void mapToStaFolder() throws Exception {
	Path staRoot = Paths.get(localFolder, "sta");
	AnnalsToStaFolderWriter writer = new AnnalsToStaFolderWriter(staRoot, maxObservationsPerBatch);

	System.out.println("Mapping Annals data to STA folder: " + staRoot);
	System.out.println("  Observations per batch file: " + maxObservationsPerBatch);

	// Create sensors map (instrumentClass -> Sensor)
	Map<String, Sensor> sensorsMap = new HashMap<>();
	HashSet<String> instrumentClasses = instruments.getClasses();
	for (String instrumentClass : instrumentClasses) {
	    String instrumentLabel = instruments.getClassLabelByClass(instrumentClass);
	    Sensor sensor = new Sensor(instrumentClass, instrumentLabel, "application/json", "");
	    JSONObject props = new JSONObject();
	    props.put(ANNALS_SENSOR_ID, instrumentClass);
	    sensor.setProperties(props);
	    sensorsMap.put(instrumentClass, sensor);
	}

	// Create observed properties map (uri -> ObservedProperty)
	Map<String, ObservedProperty> observedPropsMap = new HashMap<>();
	Set<String> observedPropCodes = observedProperties.getKeys();
	for (String observedPropCode : observedPropCodes) {
	    String uri = observedProperties.getObservedPropertyURI(observedPropCode);
	    ObservedProperty op = new ObservedProperty();
	    op.setDefinition(uri);
	    String label = uri;
	    switch (uri) {
	    case "http://codes.wmo.int/wmdr/ObservedVariableAtmosphere/210":
		label = "Amount of precipitation";
		break;
	    case "http://codes.wmo.int/wmdr/ObservedVariableAtmosphere/224":
		label = "Air temperature (at specified distance from reference surface)";
		break;
	    default:
		label = uri;
		break;
	    }
	    op.setName(label);
	    op.setDescription(uri);
	    JSONObject props = new JSONObject();
	    props.put(VARIABLE_CODE, uri);
	    op.setProperties(props);
	    observedPropsMap.put(observedPropCode, op);
	}

	// Map Things, Datastreams
	Set<Entry<String, CSVRecord>> entries = stations.getMap().entrySet();
	System.out.println("Mapping " + entries.size() + " thing(s)...");
	int thingCount = 0;
	Set<String> mappedDatastreams = new HashSet<>();

	for (Entry<String, CSVRecord> entry : entries) {
	    thingCount++;
	    if (thingCount % 100 == 0) {
		System.out.print(thingCount + " ... ");
	    }
	    CSVRecord record = entry.getValue();
	    String compartment = record.get("Compartimento");
	    String basin = record.get("ALIAS_BACINO");
	    String station = record.get("ALIAS_STAZIONE");

	    String thingId = generateThingId(compartment, basin, station);

	    // Check if already mapped
	    if (!writer.thingExists(thingId)) {
		Thing thing = createThing(compartment, basin, station);
		Location location = createLocation(compartment, basin, station);
		writer.writeThing(thingId, thing, location);
	    } else {
		writer.loadExistingThing(thingId);
	    }

	    // Map datastreams for this thing
	    Set<String> observedProps = timeSeries.getObservedProperties(compartment, basin, station);
	    for (String observedProperty : observedProps) {
		String datastreamId = generateDatastreamId(compartment, basin, station, observedProperty);
		if (!writer.datastreamExists(thingId, datastreamId) && !mappedDatastreams.contains(datastreamId)) {
		    Datastream ds = createDatastream(compartment, basin, station, observedProperty);
		    String instrumentClass = observedProperties.getInstrumentClass(observedProperty);
		    Sensor sensor = sensorsMap.get(instrumentClass);
		    ObservedProperty op = observedPropsMap.get(observedProperty);
		    writer.writeDatastream(thingId, datastreamId, ds, sensor, op);
		    mappedDatastreams.add(datastreamId);
		} else {
		    writer.loadExistingDatastream(thingId, datastreamId);
		    mappedDatastreams.add(datastreamId);
		}
	    }
	}
	System.out.println("\nMapped " + thingCount + " things, " + mappedDatastreams.size() + " datastreams.");

	// Map observations
	System.out.println("Mapping observations from " + observationFiles.size() + " file(s)...");
	ZoneId rome = ZoneId.of(generalMetadata.getTimeZone());
	long totalObservations = 0;

	for (int i = 0; i < observationFiles.size(); i++) {
	    File observationFile = observationFiles.get(i);
	    System.out.println("Processing file: " + observationFile.getName() + " (" + (i + 1) + "/" + observationFiles.size() + ")");

	    Path path = Paths.get(observationFile.toURI());
	    long totalLines = Files.lines(path).count();
	    Reader in = new FileReader(observationFile);
	    Iterable<CSVRecord> records = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in);

	    int lineCount = 0;
	    int lastPrintedPct = -1;
	    String citationAuthor = generalMetadata.getReferenceAuthor();
	    String citationTitle = generalMetadata.getReferenceTitle();
	    String dataSource = generalMetadata.getDataSource();

	    for (CSVRecord csvRecord : records) {
		lineCount++;
		try {
		    String compartment = csvRecord.get("COMPARTIMENTO");
		    String basin = csvRecord.get("ALIAS_BACINO");
		    String station = csvRecord.get("ALIAS_STAZIONE");
		    String observedProperty = csvRecord.get("GRANDEZZA");
		    String datastreamId = generateDatastreamId(compartment, basin, station, observedProperty);

		    // Skip if datastream wasn't mapped (station not in stations file)
		    if (!mappedDatastreams.contains(datastreamId)) {
			continue;
		    }

		    String annalPart = csvRecord.get("PARTE_ANNALE");
		    String page = csvRecord.get("PAGINA");
		    String orderTable = csvRecord.get("TABELLA_ORDINE");
		    String annalStationName = csvRecord.get("NOME_STAZIONE_ANNALE");
		    String annalBasinName = csvRecord.get("NOME_BACINO_ANNALE");
		    String annalInstrumentType = csvRecord.get("TIPO_STRUMENTO_ANNALE");
		    String annalInstrumentQuote = csvRecord.get("QUOTA_STRUMENTO_ANNALE");

		    Integer year = Integer.parseInt(csvRecord.get("ANNO"));
		    Integer month = Integer.parseInt(csvRecord.get("MESE"));
		    Integer day = Integer.parseInt(csvRecord.get("GIORNO"));
		    String hour = csvRecord.get("ORA");
		    BigDecimal value = new BigDecimal(csvRecord.get("VALORE"));
		    String flag = csvRecord.get("FLAG_VALORE");
		    String editorOrganizationCode = csvRecord.get("SIGLA_ENTE_COMPILATORE");
		    String editorName = csvRecord.get("NOME_COMPILATORE");

		    String[] hourSplit = hour.split(":");
		    Integer h = Integer.parseInt(hourSplit[0]);
		    Integer m = Integer.parseInt(hourSplit[1]);
		    LocalDateTime localDateTime;
		    try {
			localDateTime = LocalDateTime.of(year, month, day, h, m);
		    } catch (Exception e) {
			continue; // Skip invalid dates
		    }
		    ZonedDateTime romeTime = localDateTime.atZone(rome);
		    ZonedDateTime utcTime = romeTime.withZoneSameInstant(ZoneOffset.UTC);
		    String time = utcTime.format(DateTimeFormatter.ISO_INSTANT);

		    Observation o = new Observation();
		    o.setPhenomenonTime(time);
		    o.setResult(value);
		    String flagDescription = qualityFlags.getFlagDescription(flag);
		    if (flagDescription == null) {
			flagDescription = flag;
		    }
		    o.setResultQuality(flag, flagDescription);

		    JSONObject obsProperties = new JSONObject();
		    obsProperties.put(REFERENCE_CITATION,
			    citationAuthor + ". " + citationTitle + ". Compartimento di " + compartments.getName(
				    compartment) + ". Anno " + year + ". Parte " + annalPart + ". Pagina " + page + ". Posizione nella Pagina " + orderTable + ". Disponibile online: " + dataSource);
		    obsProperties.put(EDITOR_INDIVIDUAL, editorName);
		    String editorOrganizationName = organizations.getName(editorOrganizationCode);
		    obsProperties.put(EDITOR_ORGANIZATION, editorOrganizationName);
		    String editorOrganizationRole = organizations.getRole(editorOrganizationCode);
		    obsProperties.put(EDITOR_ROLE, editorOrganizationRole);
		    obsProperties.put(ANNAL_STATION_NAME, annalStationName);
		    obsProperties.put(ANNAL_BASIN_NAME, annalBasinName);
		    obsProperties.put(ANNAL_INSTRUMENT_TYPE, annalInstrumentType);
		    obsProperties.put(ANNAL_INSTRUMENT_QUOTE, annalInstrumentQuote);
		    o.setParameters(obsProperties);

		    writer.writeObservation(datastreamId, o);
		    totalObservations++;

		} catch (Exception e) {
		    System.err.println("Error at line " + lineCount + ": " + e.getMessage());
		}

		int pct = totalLines > 0 ? (int) (100 * lineCount / totalLines) : 100;
		if (pct != lastPrintedPct && pct % 10 == 0) {
		    System.out.println("  " + pct + "% (" + lineCount + "/" + totalLines + " lines)");
		    lastPrintedPct = pct;
		}

		if (fast && totalObservations > 1000) {
		    System.out.println("Fast mode: stopping after " + totalObservations + " observations.");
		    break;
		}
	    }
	    in.close();

	    if (fast && totalObservations > 1000)
		break;
	}

	// Flush remaining buffered observations to batch files
	writer.flushAllObservations();

	System.out.println("Mapping finished. Total observations mapped: " + totalObservations);
    }

    private Thing createThing(String compartment, String basin, String station) {
	String description = "Compartimento: " + compartment + ", bacino: " + basin + ", stazione: " + station;
	JSONArray orgs = new JSONArray();
	for (String code : organizations.getCodes()) {
	    boolean general = organizations.isGeneral(code);
	    if (general) {
		JSONObject org = organizations.getJSONObject(code);
		orgs.put(org);
	    }
	}
	Thing thing = new Thing();
	JSONObject properties = new JSONObject();
	String thingId = generateThingId(compartment, basin, station);
	properties.put(ANNALS_SITE_ID, thingId);
	properties.put("siteId", thingId); // For STAtoFrostUploader compatibility
	properties.put(WATERSHED, basin);
	properties.put(DISTRICT, compartments.getName(compartment));
	properties.put(TERRITORY_OF_ORIGIN, generalMetadata.getTerritoryName());
	properties.put(DISCLAIMER, generalMetadata.getDisclaimer());
	properties.put(FUNDING, generalMetadata.getFunding());
	properties.put(DATA_SOURCE, generalMetadata.getDataSource());
	properties.put(LICENCE, generalMetadata.getLicence());
	properties.put(SAMPLING_FEATURE_CODE, thingId);
	properties.put(RESPONSIBLE_PARTIES, orgs);
	thing.setProperties(properties);
	thing.setName(station);
	thing.setDescription(description);
	return thing;
    }

    private Location createLocation(String compartment, String basin, String station) {
	String description = "Compartimento: " + compartment + ", bacino: " + basin + ", stazione: " + station;
	BigDecimal lon = stations.getXLong(compartment, basin, station);
	BigDecimal lat = stations.getYLat(compartment, basin, station);
	BigDecimal elevation = stations.getZmslm(compartment, basin, station);
	return new Location(station, description, lon, lat, elevation);
    }

    private Datastream createDatastream(String compartment, String basin, String station, String observedProperty) {
	String version = timeSeries.getVersion(compartment, basin, station, observedProperty);
	String instrumentClass = observedProperties.getInstrumentClass(observedProperty);
	String medium = observedProperties.getSampleMedium(observedProperty);
	String interpolationType = observedProperties.getInterpolationType(observedProperty);
	String aggregationPeriod = observedProperties.getAggregationPeriod(observedProperty);
	String observedPropertyDescription = observedProperties.getObservedPropertyDescription(observedProperty);

	String uomSymbol = units.getUnitsOfMeasurement(instrumentClass);
	String uomName = units.getUnitsOfMeasurementDescription(instrumentClass);

	UnitOfMeasurement uom = new UnitOfMeasurement(uomName, uomSymbol);
	String dsName = station + " - " + observedPropertyDescription;
	Datastream ds = new Datastream(dsName, observedPropertyDescription,
		"http://www.opengis.net/def/observationType/OGC-OM/2.0/OM_Measurement", uom);

	JSONArray orgs = new JSONArray();
	for (String code : organizations.getCodes()) {
	    if (organizations.isGeneral(code)) {
		orgs.put(organizations.getJSONObject(code));
	    }
	}

	JSONObject dsProperties = new JSONObject();
	dsProperties.put(RESULT_TYPE, "Timeseries");
	dsProperties.put(SAMPLED_MEDIUM, medium);
	String datastreamId = generateDatastreamId(compartment, basin, station, observedProperty);
	dsProperties.put(ANNALS_DATASTREAM_ID, datastreamId);
	dsProperties.put("datastreamId", datastreamId); // For STAtoFrostUploader compatibility
	dsProperties.put(ANNALS_VERSION, version);
	dsProperties.put(INTENDED_TIME_SPACING, aggregationPeriod);
	dsProperties.put(AGGREGATION_STATISTIC, interpolationType);
	dsProperties.put(AGGREGATION_PERIOD, aggregationPeriod);
	dsProperties.put(DISCLAIMER, generalMetadata.getDisclaimer());
	dsProperties.put(WATERSHED, basin);
	dsProperties.put(DISTRICT, compartments.getName(compartment));
	dsProperties.put(TERRITORY_OF_ORIGIN, generalMetadata.getTerritoryName());
	dsProperties.put(FUNDING, generalMetadata.getFunding());
	dsProperties.put(LICENCE, generalMetadata.getLicence());
	dsProperties.put(RESPONSIBLE_PARTIES, orgs);

	ds.setProperties(dsProperties);
	return ds;
    }

    private static boolean getBooleanEnv(String name, boolean defaultValue) {
	String val = System.getenv(name);
	if (val == null || val.isEmpty()) {
	    return defaultValue;
	}
	val = val.trim().toLowerCase();
	return val.equals("true") || val.equals("1") || val.equals("yes") || val.equals("y");
    }

    private static int getIntEnv(String name, int defaultValue) {
	String val = System.getenv(name);
	if (val == null || val.isEmpty()) {
	    return defaultValue;
	}
	try {
	    return Integer.parseInt(val.trim());
	} catch (NumberFormatException e) {
	    return defaultValue;
	}
    }

    private static ObservationUploadStrategy getUploadStrategyEnv(String name, ObservationUploadStrategy defaultStrategy) {
	String val = System.getenv(name);
	if (val == null || val.isEmpty()) {
	    return defaultStrategy;
	}
	try {
	    return ObservationUploadStrategy.valueOf(val.trim().toUpperCase());
	} catch (IllegalArgumentException e) {
	    return defaultStrategy;
	}
    }

    public static void main(String[] args) throws Exception {
	System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());

	String frostBase = System.getenv("FROST_BASE_URL");

	if (frostBase == null || frostBase.isEmpty()) {
	    System.err.println("Please set FROST_BASE_URL environment variable");
	    System.exit(1);
	}

	String url = frostBase.replaceAll("/$", "") + "/FROST-Server/v1.1/";

	String dataFolderStr = System.getenv("ANNALS_DATA_FOLDER");
	if (dataFolderStr == null || dataFolderStr.isEmpty()) {
	    dataFolderStr = "data";
	}
	Path rawDataFolder = Paths.get(dataFolderStr);
	Path processedDataFolder = rawDataFolder.resolve(AnnalsDataPreparer.PROCESSED_DIR);

	String localFolder = System.getenv("ANNALS_LOCAL_FOLDER");
	if (localFolder == null || localFolder.isEmpty()) {
	    localFolder = processedDataFolder.toString();
	}
	boolean fast = getBooleanEnv("ANNALS_FAST", false);
	boolean logRequests = getBooleanEnv("ANNALS_LOG_REQUESTS", false);
	int maxObservationsPerBatch = getIntEnv("ANNALS_MAX_OBSERVATIONS_PER_BATCH",
		AnnalsToStaFolderWriter.DEFAULT_OBSERVATIONS_PER_BATCH_FILE);

	// Strategy to handle duplicate observations:
	// - NONE: no duplicate handling (may create duplicates)
	// - DELETE_BEFORE_UPLOAD: delete existing observations + use deterministic IDs (most thorough)
	// - DETERMINISTIC_ID: only use deterministic IDs (no delete)
	ObservationUploadStrategy uploadStrategy = getUploadStrategyEnv("ANNALS_UPLOAD_STRATEGY",
		ObservationUploadStrategy.DETERMINISTIC_ID);

	// Enable gzip compression for HTTP requests/responses (requires server support)
	// Set to true if your FROST server supports Content-Encoding: gzip for request bodies
	boolean useGzipCompression = getBooleanEnv("ANNALS_USE_GZIP", false);

	boolean doPrepare = getBooleanEnv("ANNALS_PREPARE", false);
	boolean prepareForce = getBooleanEnv("ANNALS_PREPARE_FORCE", false);
	boolean doMap = getBooleanEnv("ANNALS_MAP", false);
	boolean doUpload = getBooleanEnv("ANNALS_UPLOAD", true);

	System.out.println("AnnalsIngestor configuration:");
	System.out.println("  FROST_BASE_URL=" + frostBase);
	System.out.println("  FROST URL=" + url);
	System.out.println("  ANNALS_DATA_FOLDER=" + rawDataFolder.toAbsolutePath());
	System.out.println("  ANNALS_PROCESSED_FOLDER=" + processedDataFolder.toAbsolutePath());
	System.out.println("  ANNALS_LOCAL_FOLDER=" + localFolder);
	System.out.println("  ANNALS_PREPARE=" + doPrepare);
	System.out.println("  ANNALS_PREPARE_FORCE=" + prepareForce);
	System.out.println("  ANNALS_FAST=" + fast);
	System.out.println("  ANNALS_LOG_REQUESTS=" + logRequests);
	System.out.println("  ANNALS_MAX_OBSERVATIONS_PER_BATCH=" + maxObservationsPerBatch);
	System.out.println("  ANNALS_UPLOAD_STRATEGY=" + uploadStrategy);
	System.out.println("  ANNALS_USE_GZIP=" + useGzipCompression);
	System.out.println("  ANNALS_MAP=" + doMap);
	System.out.println("  ANNALS_UPLOAD=" + doUpload);

	if (doPrepare) {
	    prepare(rawDataFolder.toFile(), prepareForce);
	}

	AnnalsIngestor ingestor = new AnnalsIngestor(rawDataFolder.toFile(), processedDataFolder.toFile(), url, localFolder, fast,
		logRequests, maxObservationsPerBatch, uploadStrategy, useGzipCompression);

	// Full ingestion (legacy - direct upload without STA folder):
	// ingestor.ingestData();

	// New two-phase approach controlled via env:
	if (doMap) {
	    ingestor.map(); // map CSV data to STA folder structure
	}
	if (doUpload) {
	    try {
		ingestor.upload(); // upload STA folder to FROST server
	    } catch (FROSTBatchUploadException e) {
		e.printFatalBanner(System.err);
		System.exit(1);
	    } catch (FROSTServiceUnavailableException e) {
		System.exit(1);
	    }
	}
    }

    private void printStations() {
	Set<String> keys = stations.getKeys();
	for (String key : keys) {
	    CSVRecord record = stations.getRecord(key);
	    System.out.println("Station " + key + ": " + record.get(Stations.X_LONG) + " " + record.get(Stations.Y_LAT) + " " + record.get(
		    Stations.Z_MSLM));
	}

    }

    public void deleteAll() throws Exception {
	client.deleteAll();

    }
}
