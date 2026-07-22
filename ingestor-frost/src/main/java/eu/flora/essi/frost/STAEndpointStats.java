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
package eu.flora.essi.frost;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Prints summary statistics for an OGC SensorThings API (STA) endpoint using {@link FROSTClient}.
 * <p>
 * Collects data first (request logs may appear during collection), then prints a clean summary:
 * entity counts, Locations bbox and elevation, Datastream temporal coverage / empty-thin stats,
 * property tallies ({@code aggregationPeriod}, {@code aggregationStatistic}, {@code referenceTimeOfDay},
 * units, observation types), and ObservedProperties with observation counts.
 * <p>
 * Usage:
 * {@code java ... STAEndpointStats <frostBaseUrl>
 *   [--skip-observations-count] [--skip-observations-per-datastream] [--no-log-requests]}
 */
public class STAEndpointStats {

    private static final String[] COLLECTIONS = {
	    "Things",
	    "Locations",
	    "HistoricalLocations",
	    "Sensors",
	    "ObservedProperties",
	    "Datastreams",
	    "FeaturesOfInterest",
	    "Observations"
    };

    public static void main(String[] args) throws Exception {
	if (args.length < 1) {
	    System.err.println("Usage: STAEndpointStats <frostBaseUrl>"
		    + " [--skip-observations-count] [--skip-observations-per-datastream] [--no-log-requests]");
	    System.exit(1);
	}

	String baseUrl = args[0];
	boolean skipObservationsCount = false;
	boolean skipObservationsPerDatastream = false;
	boolean logRequests = true;
	for (int i = 1; i < args.length; i++) {
	    if ("--skip-observations-count".equals(args[i])) {
		skipObservationsCount = true;
	    } else if ("--skip-observations-per-datastream".equals(args[i])) {
		skipObservationsPerDatastream = true;
	    } else if ("--no-log-requests".equals(args[i])) {
		logRequests = false;
	    } else {
		System.err.println("Unknown argument: " + args[i]);
		System.exit(1);
	    }
	}

	if (!baseUrl.endsWith("/")) {
	    baseUrl = baseUrl + "/";
	}

	FROSTClient.logRequests = logRequests;
	FROSTClient client = new FROSTClient(baseUrl);
	if (logRequests) {
	    System.out.println("Request logging enabled (FROSTClient.logRequests). Use --no-log-requests to disable.");
	    System.out.println("Collecting statistics (summary printed at the end)...");
	    System.out.println();
	}
	run(client, skipObservationsCount, skipObservationsPerDatastream);
    }

    public static void run(FROSTClient client, boolean skipObservationsCount) throws Exception {
	run(client, skipObservationsCount, false);
    }

    public static void run(FROSTClient client, boolean skipObservationsCount,
	    boolean skipObservationsPerDatastream) throws Exception {
	StatsReport report = collect(client, skipObservationsCount, skipObservationsPerDatastream);
	printReport(report);
    }

    private static StatsReport collect(FROSTClient client, boolean skipObservationsCount,
	    boolean skipObservationsPerDatastream) throws Exception {
	StatsReport report = new StatsReport();
	report.endpoint = client.getBaseUrl();
	report.skipObservationsPerDatastream = skipObservationsPerDatastream;

	for (String name : COLLECTIONS) {
	    if ("Observations".equals(name) && skipObservationsCount) {
		report.counts.put(name, null);
		continue;
	    }
	    try {
		report.counts.put(name, client.count(name));
	    } catch (Exception e) {
		report.countErrors.put(name, e.getMessage());
	    }
	}

	List<double[]> points = new ArrayList<>();
	List<Double> elevations = new ArrayList<>();
	int locationsWithoutElevation = 0;
	PagedResult<Location> locPage = client.getLocations();
	while (true) {
	    for (Location location : locPage.getItems()) {
		List<double[]> locationPoints = new ArrayList<>();
		List<Double> locationElevations = new ArrayList<>();
		collectCoordinates(location.getLocation(), locationPoints, locationElevations);
		points.addAll(locationPoints);
		if (locationElevations.isEmpty()) {
		    locationsWithoutElevation++;
		} else {
		    elevations.addAll(locationElevations);
		}
	    }
	    if (!locPage.hasNext()) {
		break;
	    }
	    locPage = client.getLocationsByNextLink(locPage.getNextLink());
	}
	report.locationPointCount = points.size();
	report.locationsWithoutElevation = locationsWithoutElevation;
	if (!points.isEmpty()) {
	    double minLon = Double.POSITIVE_INFINITY;
	    double maxLon = Double.NEGATIVE_INFINITY;
	    double minLat = Double.POSITIVE_INFINITY;
	    double maxLat = Double.NEGATIVE_INFINITY;
	    for (double[] p : points) {
		minLon = Math.min(minLon, p[0]);
		maxLon = Math.max(maxLon, p[0]);
		minLat = Math.min(minLat, p[1]);
		maxLat = Math.max(maxLat, p[1]);
	    }
	    report.bboxN = maxLat;
	    report.bboxW = minLon;
	    report.bboxS = minLat;
	    report.bboxE = maxLon;
	    report.hasBbox = true;
	}
	if (!elevations.isEmpty()) {
	    elevations.sort(Comparator.naturalOrder());
	    report.hasElevation = true;
	    report.elevationCount = elevations.size();
	    report.elevationMin = elevations.get(0);
	    report.elevationMax = elevations.get(elevations.size() - 1);
	    report.elevationMedian = medianDouble(elevations);
	    double sum = 0;
	    for (double e : elevations) {
		sum += e;
	    }
	    report.elevationMean = sum / elevations.size();
	}

	List<Duration> durations = new ArrayList<>();
	List<DatastreamRef> datastreamRefs = new ArrayList<>();
	PagedResult<Datastream> dsPage = client.getDatastreams();
	while (true) {
	    for (Datastream ds : dsPage.getItems()) {
		report.datastreamsScanned++;
		datastreamRefs.add(new DatastreamRef(ds.getId(), ds.getName(), datastreamLink(client, ds)));

		tallyDatastreamProperty(report.aggregationPeriods, ds, "aggregationPeriod");
		tallyDatastreamProperty(report.aggregationStatistics, ds, "aggregationStatistic");
		tallyDatastreamProperty(report.referenceTimesOfDay, ds, "referenceTimeOfDay");
		tallyValue(report.observationTypes, blankToMissing(ds.getObservationType()));
		tallyValue(report.unitsOfMeasurement, unitKey(ds));

		String phenomenonTime = ds.getPhenomenonTime();
		if (phenomenonTime == null || phenomenonTime.isBlank()) {
		    report.missingPhenomenonTime++;
		    continue;
		}
		PhenomenonInterval interval = parsePhenomenonInterval(phenomenonTime);
		if (interval == null) {
		    report.unparseablePhenomenonTime++;
		    continue;
		}
		Duration duration = interval.duration();
		if (duration.isZero()) {
		    report.zeroLengthPhenomenonTime++;
		}
		durations.add(duration);
		String link = datastreamLink(client, ds);
		if (report.shortest == null || duration.compareTo(report.shortest.duration) < 0) {
		    report.shortest = new PeriodExtreme(ds.getId(), ds.getName(), phenomenonTime, duration, link);
		}
		if (report.longest == null || duration.compareTo(report.longest.duration) > 0) {
		    report.longest = new PeriodExtreme(ds.getId(), ds.getName(), phenomenonTime, duration, link);
		}
		if (report.overallMin == null || interval.start().isBefore(report.overallMin)) {
		    report.overallMin = interval.start();
		}
		if (report.overallMax == null || interval.end().isAfter(report.overallMax)) {
		    report.overallMax = interval.end();
		}
	    }
	    if (!dsPage.hasNext()) {
		break;
	    }
	    dsPage = client.getDatastreamsByNextLink(dsPage.getNextLink());
	}
	report.periodSampleCount = durations.size();
	if (!durations.isEmpty()) {
	    report.median = medianDuration(durations);
	}

	if (!skipObservationsPerDatastream && !datastreamRefs.isEmpty()) {
	    List<Long> obsCounts = new ArrayList<>(datastreamRefs.size());
	    for (DatastreamRef ref : datastreamRefs) {
		if (ref.id == null) {
		    continue;
		}
		try {
		    long obsCount = client.getObservationCountForDatastream(ref.id);
		    obsCounts.add(obsCount);
		    if (obsCount == 0) {
			report.emptyObservationDatastreams++;
		    }
		    if (report.minObs == null || obsCount < report.minObs.count) {
			report.minObs = new CountExtreme(ref.id, ref.name, obsCount, ref.link);
		    }
		    if (report.maxObs == null || obsCount > report.maxObs.count) {
			report.maxObs = new CountExtreme(ref.id, ref.name, obsCount, ref.link);
		    }
		} catch (Exception e) {
		    report.observationCountErrors++;
		}
	    }
	    if (!obsCounts.isEmpty()) {
		obsCounts.sort(Comparator.naturalOrder());
		report.hasObsPerDatastream = true;
		report.obsPerDatastreamMin = obsCounts.get(0);
		report.obsPerDatastreamMax = obsCounts.get(obsCounts.size() - 1);
		report.obsPerDatastreamMedian = medianLong(obsCounts);
		long sum = 0;
		for (long c : obsCounts) {
		    sum += c;
		}
		report.obsPerDatastreamMean = (double) sum / obsCounts.size();
		report.obsPerDatastreamSampleSize = obsCounts.size();
	    }
	}

	PagedResult<ObservedProperty> opPage = client.getObservedProperties();
	while (true) {
	    report.observedProperties.addAll(opPage.getItems());
	    if (!opPage.hasNext()) {
		break;
	    }
	    opPage = client.getObservedPropertiesByNextLink(opPage.getNextLink());
	}
	report.observedProperties.sort(Comparator.comparing(
		p -> p.getName() == null ? "" : p.getName(),
		String.CASE_INSENSITIVE_ORDER));

	for (ObservedProperty prop : report.observedProperties) {
	    ObservedPropertyStats stats = new ObservedPropertyStats(prop);
	    Long id = prop.getId();
	    if (id != null) {
		try {
		    stats.datastreamCount = client.count("ObservedProperties(" + id + ")/Datastreams");
		} catch (Exception e) {
		    stats.datastreamCountError = e.getMessage();
		}
		try {
		    String filter = "Datastream/ObservedProperty/@iot.id eq " + id;
		    stats.observationCount = client.count(
			    "Observations?$filter=" + FilterBuilder.urlEncode(filter));
		} catch (Exception e) {
		    stats.observationCountError = e.getMessage();
		}
	    }
	    report.observedPropertyStats.add(stats);
	}

	return report;
    }

    private static String unitKey(Datastream ds) {
	Datastream.UnitOfMeasurement uom = ds.getUnitOfMeasurement();
	if (uom == null) {
	    return "(missing)";
	}
	String symbol = uom.getSymbol();
	String name = uom.getName();
	if ((symbol == null || symbol.isBlank()) && (name == null || name.isBlank())) {
	    return "(empty)";
	}
	if (symbol != null && !symbol.isBlank() && name != null && !name.isBlank()) {
	    if (symbol.equals(name)) {
		return symbol;
	    }
	    return symbol + " (" + name + ")";
	}
	return symbol != null && !symbol.isBlank() ? symbol : name;
    }

    private static void tallyDatastreamProperty(Map<String, Long> tallies, Datastream ds, String key) {
	JSONObject properties = ds.getProperties();
	String value = "(missing)";
	if (properties != null && properties.has(key) && !properties.isNull(key)) {
	    Object raw = properties.get(key);
	    if (raw != null) {
		String text = String.valueOf(raw).trim();
		value = text.isEmpty() ? "(empty)" : text;
	    }
	}
	tallyValue(tallies, value);
    }

    private static void tallyValue(Map<String, Long> tallies, String value) {
	tallies.merge(value, 1L, Long::sum);
    }

    private static String blankToMissing(String value) {
	if (value == null || value.isBlank()) {
	    return "(missing)";
	}
	return value.trim();
    }

    private static String datastreamLink(FROSTClient client, Datastream ds) {
	String selfLink = ds.getSelfLink();
	if (selfLink != null && !selfLink.isBlank()) {
	    return selfLink;
	}
	if (ds.getId() != null) {
	    return client.getBaseUrl() + "Datastreams(" + ds.getId() + ")";
	}
	return null;
    }

    private static void printReport(StatsReport report) {
	System.out.println();
	System.out.println("=".repeat(60));
	System.out.println("STA endpoint statistics");
	System.out.println("=".repeat(60));
	System.out.println("STA endpoint: " + report.endpoint);

	printSection("Entity counts");
	for (String name : COLLECTIONS) {
	    if (report.countErrors.containsKey(name)) {
		System.out.printf("  %-22s ERROR: %s%n", name, report.countErrors.get(name));
	    } else if (!report.counts.containsKey(name)) {
		continue;
	    } else if (report.counts.get(name) == null) {
		System.out.printf("  %-22s (skipped)%n", name);
	    } else {
		System.out.printf("  %-22s %,d%n", name, report.counts.get(name));
	    }
	}

	printSection("Locations bounding box (N-W-S-E)");
	if (!report.hasBbox) {
	    System.out.println("  No Location geometries found.");
	} else {
	    System.out.printf("  Locations with coordinates: %,d%n", report.locationPointCount);
	    System.out.printf("  N (max lat): %.6f%n", report.bboxN);
	    System.out.printf("  W (min lon): %.6f%n", report.bboxW);
	    System.out.printf("  S (min lat): %.6f%n", report.bboxS);
	    System.out.printf("  E (max lon): %.6f%n", report.bboxE);
	    System.out.println("  Corner points:");
	    System.out.printf("    NW: (%.6f, %.6f)%n", report.bboxW, report.bboxN);
	    System.out.printf("    NE: (%.6f, %.6f)%n", report.bboxE, report.bboxN);
	    System.out.printf("    SE: (%.6f, %.6f)%n", report.bboxE, report.bboxS);
	    System.out.printf("    SW: (%.6f, %.6f)%n", report.bboxW, report.bboxS);
	}

	printSection("Location elevation");
	if (!report.hasElevation) {
	    System.out.println("  No elevations found in Location coordinates.");
	    if (report.locationsWithoutElevation > 0) {
		System.out.printf("  Locations without elevation: %,d%n", report.locationsWithoutElevation);
	    }
	} else {
	    System.out.printf("  Elevations present: %,d%n", report.elevationCount);
	    System.out.printf("  Locations without elevation: %,d%n", report.locationsWithoutElevation);
	    System.out.printf("  Min:    %.3f%n", report.elevationMin);
	    System.out.printf("  Max:    %.3f%n", report.elevationMax);
	    System.out.printf("  Median: %.3f%n", report.elevationMedian);
	    System.out.printf("  Mean:   %.3f%n", report.elevationMean);
	}

	printSection("Datastream phenomenonTime periods");
	System.out.printf("  Datastreams scanned: %,d%n", report.datastreamsScanned);
	System.out.printf("  Missing phenomenonTime: %,d%n", report.missingPhenomenonTime);
	System.out.printf("  Unparseable phenomenonTime: %,d%n", report.unparseablePhenomenonTime);
	System.out.printf("  Zero-length phenomenonTime: %,d%n", report.zeroLengthPhenomenonTime);
	if (report.periodSampleCount == 0) {
	    System.out.println("  No phenomenonTime intervals found.");
	} else {
	    System.out.printf("  Datastreams with phenomenonTime: %,d%n", report.periodSampleCount);
	    if (report.overallMin != null && report.overallMax != null) {
		Duration overallSpan = Duration.between(report.overallMin, report.overallMax);
		System.out.println("  Overall range: " + report.overallMin + " / " + report.overallMax
			+ " (" + formatDuration(overallSpan) + ")");
	    }
	    System.out.println("  Shortest: " + formatDuration(report.shortest.duration)
		    + " (" + report.shortest.duration + ")");
	    printPeriodExtreme("    ", report.shortest);
	    System.out.println("  Longest:  " + formatDuration(report.longest.duration)
		    + " (" + report.longest.duration + ")");
	    printPeriodExtreme("    ", report.longest);
	    System.out.println("  Median:   " + formatDuration(report.median)
		    + " (" + report.median + ")");
	}

	printSection("Observations per Datastream");
	if (report.skipObservationsPerDatastream) {
	    System.out.println("  (skipped; omit --skip-observations-per-datastream to enable)");
	} else if (!report.hasObsPerDatastream) {
	    System.out.println("  No observation counts collected.");
	} else {
	    System.out.printf("  Datastreams counted: %,d%n", report.obsPerDatastreamSampleSize);
	    if (report.observationCountErrors > 0) {
		System.out.printf("  Count errors: %,d%n", report.observationCountErrors);
	    }
	    System.out.printf("  Empty (0 observations): %,d%n", report.emptyObservationDatastreams);
	    System.out.printf("  Min:    %,d%n", report.obsPerDatastreamMin);
	    printCountExtreme("    ", report.minObs);
	    System.out.printf("  Max:    %,d%n", report.obsPerDatastreamMax);
	    printCountExtreme("    ", report.maxObs);
	    System.out.printf("  Median: %,d%n", report.obsPerDatastreamMedian);
	    System.out.printf("  Mean:   %.1f%n", report.obsPerDatastreamMean);
	}

	printSection("Datastream properties");
	System.out.printf("  Datastreams scanned: %,d%n", report.datastreamsScanned);
	printPropertyTally("aggregationPeriod", report.aggregationPeriods, report.datastreamsScanned);
	printPropertyTally("aggregationStatistic", report.aggregationStatistics, report.datastreamsScanned);
	printPropertyTally("referenceTimeOfDay", report.referenceTimesOfDay, report.datastreamsScanned);
	printPropertyTally("unitOfMeasurement", report.unitsOfMeasurement, report.datastreamsScanned);
	printPropertyTally("observationType", report.observationTypes, report.datastreamsScanned);

	printSection("ObservedProperties");
	if (report.observedPropertyStats.isEmpty()) {
	    System.out.println("  (none)");
	} else {
	    System.out.printf("  Total: %,d%n", report.observedPropertyStats.size());
	    long totalObservations = 0;
	    boolean allObservationCountsKnown = true;
	    for (ObservedPropertyStats stats : report.observedPropertyStats) {
		printObservedProperty(stats);
		if (stats.observationCount != null) {
		    totalObservations += stats.observationCount;
		} else {
		    allObservationCountsKnown = false;
		}
	    }
	    if (allObservationCountsKnown) {
		System.out.printf("  Sum of observations across ObservedProperties: %,d%n", totalObservations);
	    }
	}
	System.out.println();
    }

    private static void printPropertyTally(String propertyName, Map<String, Long> tallies, int total) {
	System.out.println("  " + propertyName + ":");
	if (tallies.isEmpty()) {
	    System.out.println("    (none)");
	    return;
	}
	List<Map.Entry<String, Long>> entries = new ArrayList<>(tallies.entrySet());
	entries.sort(Comparator
		.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
		.thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
	for (Map.Entry<String, Long> entry : entries) {
	    long count = entry.getValue();
	    double pct = total > 0 ? (100.0 * count / total) : 0.0;
	    System.out.printf("    %-70s %,d (%.1f%%)%n", entry.getKey(), count, pct);
	}
    }

    private static void printPeriodExtreme(String indent, PeriodExtreme extreme) {
	if (extreme == null) {
	    return;
	}
	if (extreme.name != null && !extreme.name.isBlank()) {
	    System.out.println(indent + "name: " + extreme.name);
	}
	if (extreme.phenomenonTime != null) {
	    System.out.println(indent + "phenomenonTime: " + extreme.phenomenonTime);
	}
	if (extreme.link != null) {
	    System.out.println(indent + "link: " + extreme.link);
	} else if (extreme.id != null) {
	    System.out.println(indent + "id: " + extreme.id);
	}
    }

    private static void printCountExtreme(String indent, CountExtreme extreme) {
	if (extreme == null) {
	    return;
	}
	if (extreme.name != null && !extreme.name.isBlank()) {
	    System.out.println(indent + "name: " + extreme.name);
	}
	if (extreme.link != null) {
	    System.out.println(indent + "link: " + extreme.link);
	} else if (extreme.id != null) {
	    System.out.println(indent + "id: " + extreme.id);
	}
    }

    private static void printObservedProperty(ObservedPropertyStats stats) {
	ObservedProperty prop = stats.property;
	String name = prop.getName() != null ? prop.getName() : "(no name)";
	String definition = prop.getDefinition();
	String description = prop.getDescription();
	String code = null;
	JSONObject properties = prop.getProperties();
	if (properties != null) {
	    if (properties.has("variableCode")) {
		code = properties.optString("variableCode", null);
	    } else if (properties.has("code")) {
		code = properties.optString("code", null);
	    }
	}

	StringBuilder extras = new StringBuilder();
	if (code != null && !code.isBlank()) {
	    extras.append("code=").append(code);
	}
	if (definition != null && !definition.isBlank()) {
	    if (extras.length() > 0) {
		extras.append("; ");
	    }
	    extras.append("def=").append(definition);
	}

	System.out.print("  - [" + prop.getId() + "] " + name);
	if (extras.length() > 0) {
	    System.out.print("  [" + extras + "]");
	}
	System.out.println();
	if (description != null && !description.isBlank() && !description.equals(name)) {
	    System.out.println("      " + description);
	}
	if (stats.datastreamCountError != null) {
	    System.out.println("      Datastreams: ERROR: " + stats.datastreamCountError);
	} else if (stats.datastreamCount != null) {
	    System.out.printf("      Datastreams: %,d%n", stats.datastreamCount);
	}
	if (stats.observationCountError != null) {
	    System.out.println("      Observations: ERROR: " + stats.observationCountError);
	} else if (stats.observationCount != null) {
	    System.out.printf("      Observations: %,d%n", stats.observationCount);
	}
    }

    private static void collectCoordinates(JSONObject geometry, List<double[]> points,
	    List<Double> elevations) {
	if (geometry == null) {
	    return;
	}
	Object coords = geometry.opt("coordinates");
	if (coords != null) {
	    collectCoordinatePairs(coords, points, elevations);
	}
    }

    private static void collectCoordinatePairs(Object node, List<double[]> points,
	    List<Double> elevations) {
	if (!(node instanceof JSONArray array) || array.isEmpty()) {
	    return;
	}
	Object first = array.get(0);
	if (first instanceof Number) {
	    if (array.length() >= 2) {
		points.add(new double[] { array.getDouble(0), array.getDouble(1) });
		if (array.length() >= 3 && !array.isNull(2)) {
		    elevations.add(array.getDouble(2));
		}
	    }
	    return;
	}
	for (int i = 0; i < array.length(); i++) {
	    collectCoordinatePairs(array.get(i), points, elevations);
	}
    }

    static PhenomenonInterval parsePhenomenonInterval(String value) {
	if (value == null || value.isBlank()) {
	    return null;
	}
	value = value.trim();
	try {
	    if (!value.contains("/")) {
		Instant instant = Instant.parse(normalizeInstant(value));
		return new PhenomenonInterval(instant, instant, Duration.ZERO);
	    }
	    String[] parts = value.split("/", 2);
	    if (parts[0].startsWith("P") || parts[1].startsWith("P")) {
		return null;
	    }
	    Instant start = Instant.parse(normalizeInstant(parts[0]));
	    Instant end = Instant.parse(normalizeInstant(parts[1]));
	    if (end.isBefore(start)) {
		Instant tmp = start;
		start = end;
		end = tmp;
	    }
	    return new PhenomenonInterval(start, end, Duration.between(start, end));
	} catch (Exception e) {
	    return null;
	}
    }

    private static String normalizeInstant(String value) {
	String v = value.trim();
	if (v.endsWith("Z") || v.contains("+") || v.lastIndexOf('-') > 10) {
	    return v;
	}
	return v + "Z";
    }

    private static Duration medianDuration(List<Duration> durations) {
	List<Duration> sorted = new ArrayList<>(durations);
	sorted.sort(Comparator.naturalOrder());
	int n = sorted.size();
	if (n % 2 == 1) {
	    return sorted.get(n / 2);
	}
	long a = sorted.get(n / 2 - 1).toNanos();
	long b = sorted.get(n / 2).toNanos();
	return Duration.ofNanos((a + b) / 2);
    }

    private static long medianLong(List<Long> values) {
	int n = values.size();
	if (n % 2 == 1) {
	    return values.get(n / 2);
	}
	return (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
    }

    private static double medianDouble(List<Double> values) {
	int n = values.size();
	if (n % 2 == 1) {
	    return values.get(n / 2);
	}
	return (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
    }

    private static String formatDuration(Duration duration) {
	long totalSeconds = duration.getSeconds();
	String sign = totalSeconds < 0 ? "-" : "";
	totalSeconds = Math.abs(totalSeconds);
	long totalDays = totalSeconds / 86_400;
	long remSeconds = totalSeconds % 86_400;
	long years = totalDays / 365;
	long days = totalDays % 365;

	if (years > 0) {
	    return sign + years + "y + " + days + "d";
	}
	if (totalDays > 0) {
	    if (remSeconds == 0) {
		return sign + totalDays + "d";
	    }
	    long hours = remSeconds / 3_600;
	    long minutes = (remSeconds % 3_600) / 60;
	    long seconds = remSeconds % 60;
	    return sign + totalDays + "d " + hours + "h " + minutes + "m " + seconds + "s";
	}
	long hours = remSeconds / 3_600;
	long minutes = (remSeconds % 3_600) / 60;
	long seconds = remSeconds % 60;
	if (hours > 0) {
	    return sign + hours + "h " + minutes + "m " + seconds + "s";
	}
	if (minutes > 0) {
	    return sign + minutes + "m " + seconds + "s";
	}
	return sign + seconds + "s";
    }

    private static void printSection(String title) {
	System.out.println();
	System.out.println(title);
	System.out.println("-".repeat(title.length()));
    }

    private static final class StatsReport {
	String endpoint;
	final Map<String, Long> counts = new LinkedHashMap<>();
	final Map<String, String> countErrors = new LinkedHashMap<>();
	boolean hasBbox;
	int locationPointCount;
	double bboxN;
	double bboxW;
	double bboxS;
	double bboxE;
	boolean hasElevation;
	int elevationCount;
	int locationsWithoutElevation;
	double elevationMin;
	double elevationMax;
	double elevationMedian;
	double elevationMean;
	int periodSampleCount;
	int datastreamsScanned;
	int missingPhenomenonTime;
	int unparseablePhenomenonTime;
	int zeroLengthPhenomenonTime;
	Instant overallMin;
	Instant overallMax;
	PeriodExtreme shortest;
	PeriodExtreme longest;
	Duration median;
	boolean skipObservationsPerDatastream;
	boolean hasObsPerDatastream;
	int obsPerDatastreamSampleSize;
	int emptyObservationDatastreams;
	int observationCountErrors;
	long obsPerDatastreamMin;
	long obsPerDatastreamMax;
	long obsPerDatastreamMedian;
	double obsPerDatastreamMean;
	CountExtreme minObs;
	CountExtreme maxObs;
	final Map<String, Long> aggregationPeriods = new HashMap<>();
	final Map<String, Long> aggregationStatistics = new HashMap<>();
	final Map<String, Long> referenceTimesOfDay = new HashMap<>();
	final Map<String, Long> unitsOfMeasurement = new HashMap<>();
	final Map<String, Long> observationTypes = new HashMap<>();
	final List<ObservedProperty> observedProperties = new ArrayList<>();
	final List<ObservedPropertyStats> observedPropertyStats = new ArrayList<>();
    }

    private static final class ObservedPropertyStats {
	final ObservedProperty property;
	Long datastreamCount;
	String datastreamCountError;
	Long observationCount;
	String observationCountError;

	ObservedPropertyStats(ObservedProperty property) {
	    this.property = property;
	}
    }

    private record PhenomenonInterval(Instant start, Instant end, Duration duration) {
    }

    private record DatastreamRef(Long id, String name, String link) {
    }

    private static final class PeriodExtreme {
	final Long id;
	final String name;
	final String phenomenonTime;
	final Duration duration;
	final String link;

	PeriodExtreme(Long id, String name, String phenomenonTime, Duration duration, String link) {
	    this.id = id;
	    this.name = name;
	    this.phenomenonTime = phenomenonTime;
	    this.duration = duration;
	    this.link = link;
	}
    }

    private static final class CountExtreme {
	final Long id;
	final String name;
	final long count;
	final String link;

	CountExtreme(Long id, String name, long count, String link) {
	    this.id = id;
	    this.name = name;
	    this.count = count;
	    this.link = link;
	}
    }
}
