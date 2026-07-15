package eu.flora.essi.frost;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CRUD client for FROST Server (SensorThings API)
 * Base URL format: http://localhost:8080/FROST-Server/v1.1/
 */
public class FROSTClient {
    private String baseUrl;
    private HttpClient httpClient;
    public static boolean logRequests = false;
    /** Enable gzip compression for request bodies (reduces network transfer time for large payloads). 
     *  Disabled by default - requires server support for Content-Encoding: gzip on request bodies. */
    public static boolean useGzipCompression = false;
    /** Minimum body size (bytes) to apply gzip compression (smaller bodies may not benefit). */
    private static final int GZIP_THRESHOLD = 1024;

    public FROSTClient(String baseUrl) {
	// Ensure base URL ends with /
	if (!baseUrl.endsWith("/")) {
	    this.baseUrl = baseUrl + "/";
	} else {
	    this.baseUrl = baseUrl;
	}

	this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public String getBaseUrl() {
	return baseUrl;
    }

    /**
     * Verify that the FROST Server is reachable before starting a bulk upload.
     */
    public void checkAvailability() throws FROSTServiceUnavailableException, InterruptedException {
	try {
	    executeGet(baseUrl + "Things?$top=0");
	} catch (FROSTServiceUnavailableException e) {
	    throw e;
	} catch (IOException e) {
	    throw new FROSTServiceUnavailableException(baseUrl, e);
	}
    }

    public static boolean isServiceUnavailable(Throwable throwable) {
	for (Throwable current = throwable; current != null; current = current.getCause()) {
	    if (current instanceof FROSTServiceUnavailableException
		    || current instanceof ConnectException
		    || current instanceof HttpConnectTimeoutException
		    || current instanceof UnknownHostException
		    || current instanceof UnresolvedAddressException
		    || current instanceof SocketTimeoutException) {
		return true;
	    }
	}
	return false;
    }

    private static boolean isUnavailableHttpStatus(int statusCode) {
	return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
	    throws IOException, InterruptedException {
	try {
	    return httpClient.send(request, handler);
	} catch (IOException e) {
	    if (isServiceUnavailable(e)) {
		throw new FROSTServiceUnavailableException(baseUrl, e);
	    }
	    throw e;
	}
    }

    private IOException requestFailed(String method, String url, int statusCode, String errorBody) {
	if (isUnavailableHttpStatus(statusCode)) {
	    return new FROSTServiceUnavailableException(baseUrl, method + " request failed with status " + statusCode + ": " + errorBody);
	}
	return new IOException(method + " request at url " + url + " failed with status " + statusCode + ": " + errorBody);
    }

    // ========== Thing CRUD Operations ==========

    /**
     * GET /Things - Get all Things
     */
    public PagedResult<Thing> getThings() throws IOException, InterruptedException {
	return getThings(null);
    }

    /**
     * GET /Things - Get Things with filter
     */
    public PagedResult<Thing> getThings(String filter) throws IOException, InterruptedException {
	return get("Things", filter, Thing.class);
    }

    /**
     * GET /Things - Get Things filtered by property value equality
     * 
     * @param propertyKey The key in the properties object
     * @param value The string value to match
     * @return PagedResult containing matching Things
     */
    public PagedResult<Thing> getThingsByProperty(String propertyKey, String value) throws IOException, InterruptedException {
	return getThings(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Things - Get Things filtered by property value equality (numeric)
     * 
     * @param propertyKey The key in the properties object
     * @param value The numeric value to match
     * @return PagedResult containing matching Things
     */
    public PagedResult<Thing> getThingsByProperty(String propertyKey, Number value) throws IOException, InterruptedException {
	return getThings(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Things - Get Things filtered by property value equality (boolean)
     * 
     * @param propertyKey The key in the properties object
     * @param value The boolean value to match
     * @return PagedResult containing matching Things
     */
    public PagedResult<Thing> getThingsByProperty(String propertyKey, boolean value) throws IOException, InterruptedException {
	return getThings(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Things - Get Things filtered by property value in a list
     * 
     * @param propertyKey The key in the properties object
     * @param values Array of string values to match
     * @return PagedResult containing matching Things
     */
    public PagedResult<Thing> getThingsByPropertyIn(String propertyKey, String... values) throws IOException, InterruptedException {
	return getThings(FilterBuilder.propertyIn(propertyKey, values));
    }

    /**
     * GET /Things - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<Thing> getThingsByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, Thing.class);
    }

    /**
     * GET /Things(id) - Get Thing by ID
     */
    public Thing getThing(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Things(" + id + ")";
	JSONObject response = executeGet(url);
	return new Thing(response);
    }

    /**
     * POST /Things - Create a new Thing
     */
    public void postThing(Thing thing) throws IOException, InterruptedException {
	String url = baseUrl + "Things";
	executePost(url, thing.toJSON().toString());
    }

    /** POST Thing and return the created entity @iot.id */
    public Long postThingReturningId(Thing thing) throws IOException, InterruptedException {
	JSONObject res = executePostReturningJson(baseUrl + "Things", thing.toJSON().toString());
	return res.has("@iot.id") ? res.getLong("@iot.id") : null;
    }

    /**
     * POST /Things - Create a Thing with Locations and Datastreams in one request
     * This is an advanced method that allows creating related entities in a single POST.
     * 
     * @param thing The Thing to create
     * @param locations List of Locations to associate with the Thing (can be null or empty)
     * @param datastreams List of Datastreams to associate with the Thing (can be null or empty)
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void postThingWithRelations(Thing thing, List<Location> locations, List<Datastream> datastreams)
	    throws IOException, InterruptedException {
	String url = baseUrl + "Things";
	JSONObject requestBody = thing.toJSON();

	// Add Locations array if provided
	if (locations != null && !locations.isEmpty()) {
	    JSONArray locationsArray = new JSONArray();
	    for (Location location : locations) {
		locationsArray.put(location.toJSON());
	    }
	    requestBody.put("Locations", locationsArray);
	}

	// Add Datastreams array if provided
	if (datastreams != null && !datastreams.isEmpty()) {
	    JSONArray datastreamsArray = new JSONArray();
	    for (Datastream datastream : datastreams) {
		datastreamsArray.put(datastream.toJSON());
	    }
	    requestBody.put("Datastreams", datastreamsArray);
	}

	executePost(url, requestBody);
    }

    /**
     * PATCH /Things(id) - Update a Thing
     */
    public void patchThing(Long id, Thing thing) throws IOException, InterruptedException {
	patchThing(id, thing.toJSON());
    }

    /**
     * PATCH /Things(id) - Update a Thing with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the Thing to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated Thing
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchThing(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "Things(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /Things(id) - Delete a Thing
     */
    public void deleteThing(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Things(" + id + ")";
	executeDelete(url);
    }

    // ========== Datastream CRUD Operations ==========

    /**
     * GET /Datastreams - Get all Datastreams
     */
    public PagedResult<Datastream> getDatastreams() throws IOException, InterruptedException {
	return getDatastreams(null);
    }

    /**
     * GET /Datastreams - Get Datastreams with filter
     */
    public PagedResult<Datastream> getDatastreams(String filter) throws IOException, InterruptedException {
	return getDatastreams(filter, null);
    }

    /**
     * GET /Datastreams - Get Datastreams with filter and optional $expand (e.g. "Thing" to include Thing id in each Datastream).
     */
    public PagedResult<Datastream> getDatastreams(String filter, String expand) throws IOException, InterruptedException {
	return get("Datastreams", filter, expand, Datastream.class);
    }

    /**
     * GET /Datastreams - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<Datastream> getDatastreamsByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, Datastream.class);
    }

    /**
     * GET /Datastreams - Get Datastreams filtered by property value equality
     */
    public PagedResult<Datastream> getDatastreamsByProperty(String propertyKey, String value) throws IOException, InterruptedException {
	return getDatastreams(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Datastreams - Get Datastreams filtered by property value equality (numeric)
     */
    public PagedResult<Datastream> getDatastreamsByProperty(String propertyKey, Number value) throws IOException, InterruptedException {
	return getDatastreams(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Datastreams - Get Datastreams filtered by property value equality (boolean)
     */
    public PagedResult<Datastream> getDatastreamsByProperty(String propertyKey, boolean value) throws IOException, InterruptedException {
	return getDatastreams(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Datastreams(id) - Get Datastream by ID
     */
    public Datastream getDatastream(Long id) throws IOException, InterruptedException {
	return getDatastream(id, null);
    }

    /**
     * GET /Datastreams(id) - Get Datastream by ID with optional $expand (e.g. "Thing").
     */
    public Datastream getDatastream(Long id, String expand) throws IOException, InterruptedException {
	String url = baseUrl + "Datastreams(" + id + ")";
	if (expand != null && !expand.isEmpty()) {
	    url += "?$expand=" + java.net.URLEncoder.encode(expand, StandardCharsets.UTF_8);
	}
	JSONObject response = executeGet(url);
	return new Datastream(response);
    }

    /**
     * POST /Datastreams - Create a new Datastream
     */
    public void postDatastream(Datastream datastream) throws IOException, InterruptedException {
	String url = baseUrl + "Datastreams";
	executePost(url, datastream.toJSON());
    }

    /**
     * PATCH /Datastreams(id) - Update a Datastream
     */
    public void patchDatastream(Long id, Datastream datastream) throws IOException, InterruptedException {
	patchDatastream(id, datastream.toJSON());
    }

    /**
     * PATCH /Datastreams(id) - Update a Datastream with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the Datastream to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated Datastream
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchDatastream(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "Datastreams(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /Datastreams(id) - Delete a Datastream
     */
    public void deleteDatastream(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Datastreams(" + id + ")";
	executeDelete(url);
    }

    // ========== Observation CRUD Operations ==========

    /**
     * GET /Observations - Get all Observations
     */
    public PagedResult<Observation> getObservations() throws IOException, InterruptedException {
	return getObservations(null);
    }

    /**
     * GET /Observations - Get Observations with filter
     */
    public PagedResult<Observation> getObservations(String filter) throws IOException, InterruptedException {
	return get("Observations", filter, Observation.class);
    }

    /**
     * GET /Observations - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<Observation> getObservationsByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, Observation.class);
    }

    /**
     * GET /Observations - Get Observations filtered by result greater than
     */
    public PagedResult<Observation> getObservationsByResultGreaterThan(Number value) throws IOException, InterruptedException {
	return getObservations(FilterBuilder.resultGreaterThan(value));
    }

    /**
     * GET /Observations - Get Observations filtered by result less than
     */
    public PagedResult<Observation> getObservationsByResultLessThan(Number value) throws IOException, InterruptedException {
	return getObservations(FilterBuilder.resultLessThan(value));
    }

    /**
     * GET /Observations(id) - Get Observation by ID
     */
    public Observation getObservation(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Observations(" + id + ")";
	JSONObject response = executeGet(url);
	return new Observation(response);
    }

    /**
     * POST /Observations - Create a new Observation
     */
    public void postObservation(Observation observation) throws IOException, InterruptedException {
	String url = baseUrl + "Observations";
	executePost(url, observation.toJSON());
    }

    /**
     * POST /Datastreams(id)/Observations - Create Observation for a Datastream
     */
    public void postObservation(Long datastreamId, Observation observation) throws IOException, InterruptedException {
	String url = baseUrl + "Datastreams(" + datastreamId + ")/Observations";
	executePost(url, observation.toJSON());
    }

    /**
     * PATCH /Observations(id) - Update an Observation
     */
    public void patchObservation(Long id, Observation observation) throws IOException, InterruptedException {
	patchObservation(id, observation.toJSON());
    }

    /**
     * PATCH /Observations(id) - Update an Observation with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the Observation to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated Observation
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchObservation(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "Observations(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /Observations(id) - Delete an Observation
     */
    public void deleteObservation(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Observations(" + id + ")";
	executeDelete(url);
    }

    // ========== Sensor CRUD Operations ==========

    /**
     * GET /Sensors - Get all Sensors
     */
    public PagedResult<Sensor> getSensors() throws IOException, InterruptedException {
	return getSensors(null);
    }

    /**
     * GET /Sensors - Get Sensors with filter
     */
    public PagedResult<Sensor> getSensors(String filter) throws IOException, InterruptedException {
	return get("Sensors", filter, Sensor.class);
    }

    /**
     * GET /Sensors - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<Sensor> getSensorsByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, Sensor.class);
    }

    /**
     * GET /Sensors - Get Sensors filtered by name
     */
    public PagedResult<Sensor> getSensorsByName(String name) throws IOException, InterruptedException {
	return getSensors(FilterBuilder.nameEquals(name));
    }

    /**
     * GET /Sensors - Get Sensors filtered by name starts with
     */
    public PagedResult<Sensor> getSensorsByNameStartsWith(String prefix) throws IOException, InterruptedException {
	return getSensors(FilterBuilder.nameStartsWith(prefix));
    }

    public PagedResult<Sensor> getSensorsByProperty(String propertyKey, String value) throws IOException, InterruptedException {
	return getSensors(FilterBuilder.propertyEquals(propertyKey, value));
    }

    /**
     * GET /Sensors(id) - Get Sensor by ID
     */
    public Sensor getSensor(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Sensors(" + id + ")";
	JSONObject response = executeGet(url);
	return new Sensor(response);
    }

    /**
     * POST /Sensors - Create a new Sensor
     */
    public void postSensor(Sensor sensor) throws IOException, InterruptedException {
	String url = baseUrl + "Sensors";
	executePost(url, sensor.toJSON());
    }

    /**
     * PATCH /Sensors(id) - Update a Sensor
     */
    public void patchSensor(Long id, Sensor sensor) throws IOException, InterruptedException {
	patchSensor(id, sensor.toJSON());
    }

    /**
     * PATCH /Sensors(id) - Update a Sensor with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the Sensor to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated Sensor
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchSensor(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "Sensors(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /Sensors(id) - Delete a Sensor
     */
    public void deleteSensor(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Sensors(" + id + ")";
	executeDelete(url);
    }

    // ========== ObservedProperty CRUD Operations ==========

    /**
     * GET /ObservedProperties - Get all ObservedProperties
     */
    public PagedResult<ObservedProperty> getObservedProperties() throws IOException, InterruptedException {
	return getObservedProperties(null);
    }

    /**
     * GET /ObservedProperties - Get ObservedProperties with filter
     */
    public PagedResult<ObservedProperty> getObservedProperties(String filter) throws IOException, InterruptedException {
	return get("ObservedProperties", filter, ObservedProperty.class);
    }

    /**
     * GET /ObservedProperties - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<ObservedProperty> getObservedPropertiesByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, ObservedProperty.class);
    }

    /**
     * GET /ObservedProperties - Get ObservedProperties filtered by name
     */
    public PagedResult<ObservedProperty> getObservedPropertiesByName(String name) throws IOException, InterruptedException {
	return getObservedProperties(FilterBuilder.nameEquals(name));
    }

    public PagedResult<ObservedProperty> getObservedPropertiesByProperty(String name, String value)
	    throws IOException, InterruptedException {
	return getObservedProperties(FilterBuilder.propertyEquals(name, value));
    }

    /**
     * GET /ObservedProperties - Get ObservedProperties filtered by definition
     */
    public PagedResult<ObservedProperty> getObservedPropertiesByDefinition(String definition) throws IOException, InterruptedException {
	return getObservedProperties("definition eq " + FilterBuilder.encodeString(definition));
    }

    /**
     * GET /ObservedProperties(id) - Get ObservedProperty by ID
     */
    public ObservedProperty getObservedProperty(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "ObservedProperties(" + id + ")";
	JSONObject response = executeGet(url);
	return new ObservedProperty(response);
    }

    /**
     * POST /ObservedProperties - Create a new ObservedProperty
     */
    public void postObservedProperty(ObservedProperty observedProperty) throws IOException, InterruptedException {
	String url = baseUrl + "ObservedProperties";
	executePost(url, observedProperty.toJSON());
    }

    /**
     * PATCH /ObservedProperties(id) - Update an ObservedProperty
     */
    public void patchObservedProperty(Long id, ObservedProperty observedProperty) throws IOException, InterruptedException {
	patchObservedProperty(id, observedProperty.toJSON());
    }

    /**
     * PATCH /ObservedProperties(id) - Update an ObservedProperty with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the ObservedProperty to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated ObservedProperty
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchObservedProperty(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "ObservedProperties(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /ObservedProperties(id) - Delete an ObservedProperty
     */
    public void deleteObservedProperty(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "ObservedProperties(" + id + ")";
	executeDelete(url);
    }

    // ========== Location CRUD Operations ==========

    /**
     * GET /Locations - Get all Locations
     */
    public PagedResult<Location> getLocations() throws IOException, InterruptedException {
	return getLocations(null);
    }

    /**
     * GET /Locations - Get Locations with filter
     */
    public PagedResult<Location> getLocations(String filter) throws IOException, InterruptedException {
	return get("Locations", filter, Location.class);
    }

    /**
     * GET /Locations - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<Location> getLocationsByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, Location.class);
    }

    /**
     * GET /Locations(id) - Get Location by ID
     */
    public Location getLocation(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Locations(" + id + ")";
	JSONObject response = executeGet(url);
	return new Location(response);
    }

    /**
     * GET /Things(id)/Datastreams - Get Datastreams linked to a Thing
     */
    public PagedResult<Datastream> getThingDatastreams(Long thingId) throws IOException, InterruptedException {
	return get("Things(" + thingId + ")/Datastreams", null, null, Datastream.class);
    }

    /**
     * GET /Things(id)/Locations - Get Locations linked to a Thing
     */
    public PagedResult<Location> getThingLocations(Long thingId) throws IOException, InterruptedException {
	return get("Things(" + thingId + ")/Locations", Location.class);
    }

    /**
     * POST /Locations - Create a new Location
     */
    public void postLocation(Location location) throws IOException, InterruptedException {
	String url = baseUrl + "Locations";
	executePost(url, location.toJSON());
    }

    /**
     * POST /Things(id)/Locations - Create Location for a Thing
     */
    public void postLocation(Long thingId, Location location) throws IOException, InterruptedException {
	String url = baseUrl + "Things(" + thingId + ")/Locations";
	executePost(url, location.toJSON().toString());
    }

    /** POST Sensor and return the created entity @iot.id */
    public Long postSensorReturningId(Sensor sensor) throws IOException, InterruptedException {
	JSONObject res = executePostReturningJson(baseUrl + "Sensors", sensor.toJSON().toString());
	return sensor.getId();
    }

    /** POST ObservedProperty and return the created entity @iot.id */
    public Long postObservedPropertyReturningId(ObservedProperty observedProperty) throws IOException, InterruptedException {
	JSONObject res = executePostReturningJson(baseUrl + "ObservedProperties", observedProperty.toJSON().toString());
	return observedProperty.getId();
    }

    /** POST Datastream and return the created entity @iot.id.
     * If the server returns an empty response, the ID is taken from the posted JSON (e.g. deterministic @iot.id). */
    public Long postDatastreamReturningId(Datastream datastream) throws IOException, InterruptedException {
	JSONObject body = datastream.toJSON();
	JSONObject res = executePostReturningJson(baseUrl + "Datastreams", body.toString());
	if (res != null && res.has("@iot.id")) return res.getLong("@iot.id");
	return body.has("@iot.id") ? body.getLong("@iot.id") : null;
    }

    /**
     * PATCH /Locations(id) - Update a Location
     */
    public void patchLocation(Long id, Location location) throws IOException, InterruptedException {
	patchLocation(id, location.toJSON());
    }

    /**
     * PATCH /Locations(id) - Update a Location with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the Location to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated Location
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchLocation(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "Locations(" + id + ")";
	executePatch(url, patch);

    }

    /**
     * DELETE /Locations(id) - Delete a Location
     */
    public void deleteLocation(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "Locations(" + id + ")";
	executeDelete(url);
    }

    // ========== FeatureOfInterest CRUD Operations ==========

    /**
     * GET /FeaturesOfInterest - Get all FeaturesOfInterest
     */
    public PagedResult<FeatureOfInterest> getFeaturesOfInterest() throws IOException, InterruptedException {
	return getFeaturesOfInterest(null);
    }

    /**
     * GET /FeaturesOfInterest - Get FeaturesOfInterest with filter
     */
    public PagedResult<FeatureOfInterest> getFeaturesOfInterest(String filter) throws IOException, InterruptedException {
	return get("FeaturesOfInterest", filter, FeatureOfInterest.class);
    }

    /**
     * GET /FeaturesOfInterest - Fetch the page referenced by an @iot.nextLink
     */
    public PagedResult<FeatureOfInterest> getFeaturesOfInterestByNextLink(String nextLink) throws IOException, InterruptedException {
	return fetchPagedResult(nextLink, FeatureOfInterest.class);
    }

    /**
     * GET /FeaturesOfInterest(id) - Get FeatureOfInterest by ID
     */
    public FeatureOfInterest getFeatureOfInterest(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "FeaturesOfInterest(" + id + ")";
	JSONObject response = executeGet(url);
	return new FeatureOfInterest(response);
    }

    /**
     * POST /FeaturesOfInterest - Create a new FeatureOfInterest
     */
    public void postFeatureOfInterest(FeatureOfInterest featureOfInterest) throws IOException, InterruptedException {
	String url = baseUrl + "FeaturesOfInterest";
	executePost(url, featureOfInterest.toJSON());
    }

    /**
     * PATCH /FeaturesOfInterest(id) - Update a FeatureOfInterest
     */
    public void patchFeatureOfInterest(Long id, FeatureOfInterest featureOfInterest) throws IOException, InterruptedException {
	patchFeatureOfInterest(id, featureOfInterest.toJSON());
    }

    /**
     * PATCH /FeaturesOfInterest(id) - Update a FeatureOfInterest with partial JSON
     * Only include the fields that need to be updated in the patch object.
     * 
     * @param id The ID of the FeatureOfInterest to update
     * @param patch JSONObject containing only the fields to update
     * @return The updated FeatureOfInterest
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public void patchFeatureOfInterest(Long id, JSONObject patch) throws IOException, InterruptedException {
	String url = baseUrl + "FeaturesOfInterest(" + id + ")";
	executePatch(url, patch);
    }

    /**
     * DELETE /FeaturesOfInterest(id) - Delete a FeatureOfInterest
     */
    public void deleteFeatureOfInterest(Long id) throws IOException, InterruptedException {
	String url = baseUrl + "FeaturesOfInterest(" + id + ")";
	executeDelete(url);
    }

    // ========== HTTP Request Execution Methods ==========

    private JSONObject executeGet(String url) throws IOException, InterruptedException {
	HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
		.header("Accept", "application/json");
	if (useGzipCompression) {
	    builder.header("Accept-Encoding", "gzip");
	}
	HttpRequest request = builder.GET().build();
	if (logRequests) System.out.println("GET "+url);
	
	HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
	if (logRequests) System.out.println("GOT "+url);
	
	if (response.statusCode() >= 200 && response.statusCode() < 300) {
	    String responseBody = decodeResponse(response);
	    return new JSONObject(responseBody);
	} else {
	    String errorBody = decodeResponse(response);
	    System.err.println("Error for URL "+url);
	    throw requestFailed("GET", url, response.statusCode(), errorBody);
	}
    }

    private void executePost(String url, JSONObject body) throws IOException, InterruptedException {
	executePost(url, body.toString());
    }

    private void executePost(String url, JSONArray body) throws IOException, InterruptedException {
	executePost(url, body.toString());
    }

    private void executePost(String url, String body) throws IOException, InterruptedException {
	executePostReturningJson(url, body);
    }

    /**
     * POST and return the response body as JSON (e.g. to read @iot.id of the created entity).
     * Uses gzip compression for request body if enabled and body exceeds threshold.
     */
    public JSONObject executePostReturningJson(String url, String body) throws IOException, InterruptedException {
	HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
		.header("Accept", "application/json");
	
	if (useGzipCompression) {
	    builder.header("Accept-Encoding", "gzip");
	}
	
	// Compress request body if enabled and large enough
	if (useGzipCompression && body.length() >= GZIP_THRESHOLD) {
	    byte[] compressedBody = gzipCompress(body);
	    builder.header("Content-Type", "application/json")
		   .header("Content-Encoding", "gzip")
		   .POST(HttpRequest.BodyPublishers.ofByteArray(compressedBody));
	    if (logRequests) System.out.println("POST "+url+" (gzip: " + body.length() + " -> " + compressedBody.length + " bytes)");
	} else {
	    builder.header("Content-Type", "application/json")
		   .POST(HttpRequest.BodyPublishers.ofString(body));
	    if (logRequests) System.out.println("POST "+url);
	}
	
	HttpRequest request = builder.build();
	HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
	if (logRequests) System.out.println("POSTED "+url);
	
	if (response.statusCode() < 200 || response.statusCode() >= 300) {
	    String errorBody = decodeResponse(response);
	    System.err.println("Error with request to " + url + ", body:");
	    System.err.println(body);
	    throw requestFailed("POST", url, response.statusCode(), errorBody);
	}
	String responseBody = decodeResponse(response);
	return (responseBody == null || responseBody.isEmpty()) ? new JSONObject() : new JSONObject(responseBody);
    }

    private void executePatch(String url, JSONObject body) throws IOException, InterruptedException {
	Set<String> keys = body.keySet();
	List<String> toRemove = new ArrayList<>();
	for (String key : keys) {
	    if (key.contains("@")) {
		toRemove.add(key);
	    }
	}
	for (String key : toRemove) {
	    body.remove(key);
	}
	String bodyStr = body.toString();
	HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
		.header("Accept", "application/json");
	
	if (useGzipCompression) {
	    builder.header("Accept-Encoding", "gzip");
	}
	
	if (useGzipCompression && bodyStr.length() >= GZIP_THRESHOLD) {
	    byte[] compressedBody = gzipCompress(bodyStr);
	    builder.header("Content-Type", "application/json")
		   .header("Content-Encoding", "gzip")
		   .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(compressedBody));
	} else {
	    builder.header("Content-Type", "application/json")
		   .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyStr));
	}
	
	HttpRequest request = builder.build();
	if (logRequests) System.out.println("PATCH "+url);
	HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
	if (logRequests) System.out.println("PATCHED "+url);
	if (response.statusCode() >= 200 && response.statusCode() < 300) {
	    return;
	} else {
	    String errorBody = decodeResponse(response);
	    System.err.println("Error with PATCH request to " + url );
	    throw requestFailed("PATCH", url, response.statusCode(), errorBody);
	}
    }

    private void executeDelete(String url) throws IOException, InterruptedException {
	HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
	if (logRequests) System.out.println("DELETE "+url);
	HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
	if (logRequests) System.out.println("DELETED "+url);
	if (response.statusCode() < 200 || response.statusCode() >= 300) {
	    System.err.println("Error with DELETE request to " + url );
	    throw requestFailed("DELETE", url, response.statusCode(), response.body());
	}
    }

    // ========== Gzip Compression Helpers ==========

    /**
     * Compress a string to gzip bytes.
     */
    private static byte[] gzipCompress(String data) throws IOException {
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
	    gzos.write(data.getBytes(StandardCharsets.UTF_8));
	}
	return baos.toByteArray();
    }

    /**
     * Decode an HTTP response body, handling gzip decompression if needed.
     */
    private static String decodeResponse(HttpResponse<byte[]> response) throws IOException {
	byte[] body = response.body();
	if (body == null || body.length == 0) {
	    return "";
	}
	
	// Check if response is gzip-encoded
	String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
	if (contentEncoding.equalsIgnoreCase("gzip")) {
	    return gzipDecompress(body);
	}
	return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Decompress gzip bytes to a string.
     */
    private static String gzipDecompress(byte[] compressed) throws IOException {
	try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed));
	     Reader reader = new InputStreamReader(gzis, StandardCharsets.UTF_8)) {
	    StringBuilder sb = new StringBuilder();
	    char[] buffer = new char[4096];
	    int len;
	    while ((len = reader.read(buffer)) != -1) {
		sb.append(buffer, 0, len);
	    }
	    return sb.toString();
	}
    }

    // ========== Generic Methods ==========

    /**
     * Generic method to get entities with optional filter.
     * 
     * @param <T> The entity type
     * @param entityPath The entity path (e.g., "Things", "Datastreams", "Observations")
     * @param filter Optional filter expression (can be null)
     * @param entityClass The class of the entity type
     * @return PagedResult containing the entities
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public <T> PagedResult<T> get(String entityPath, String filter, Class<T> entityClass) throws IOException, InterruptedException {
	return get(entityPath, filter, null, entityClass);
    }

    /**
     * Generic method to get entities with optional filter and $expand.
     * 
     * @param <T> The entity type
     * @param entityPath The entity path (e.g., "Things", "Datastreams", "Observations")
     * @param filter Optional filter expression (can be null)
     * @param expand Optional $expand value (e.g. "Thing") (can be null)
     * @param entityClass The class of the entity type
     * @return PagedResult containing the entities
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public <T> PagedResult<T> get(String entityPath, String filter, String expand, Class<T> entityClass) throws IOException, InterruptedException {
	String url = baseUrl + entityPath;
	boolean first = true;
	if (filter != null && !filter.isEmpty()) {
	    url += "?$filter=" + FilterBuilder.urlEncode(filter);
	    first = false;
	}
	if (expand != null && !expand.isEmpty()) {
	    url += (first ? "?" : "&") + "$expand=" + java.net.URLEncoder.encode(expand, StandardCharsets.UTF_8);
	}
	return fetchPagedResult(url, entityClass);
    }

    /**
     * Generic method to get entities without filter.
     * 
     * @param <T> The entity type
     * @param entityPath The entity path (e.g., "Things", "Datastreams", "Observations")
     * @param entityClass The class of the entity type
     * @return PagedResult containing the entities
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    public <T> PagedResult<T> get(String entityPath, Class<T> entityClass) throws IOException, InterruptedException {
	return get(entityPath, null, entityClass);
    }

    // ========== Helper Methods ==========

    private <T> PagedResult<T> fetchPagedResult(String url, Class<T> entityClass) throws IOException, InterruptedException {
	JSONObject response = executeGet(url);
	return parsePagedResult(response, entityClass);
    }

    @SuppressWarnings("unchecked")
    private <T> PagedResult<T> parsePagedResult(JSONObject response, Class<T> entityClass) {
	List<T> entities = new ArrayList<>();

	if (response.has("value")) {
	    JSONArray array = response.getJSONArray("value");
	    for (int i = 0; i < array.length(); i++) {
		JSONObject entityJson = array.getJSONObject(i);

		if (entityClass == Thing.class) {
		    entities.add((T) new Thing(entityJson));
		} else if (entityClass == Datastream.class) {
		    entities.add((T) new Datastream(entityJson));
		} else if (entityClass == Observation.class) {
		    entities.add((T) new Observation(entityJson));
		} else if (entityClass == Sensor.class) {
		    entities.add((T) new Sensor(entityJson));
		} else if (entityClass == ObservedProperty.class) {
		    entities.add((T) new ObservedProperty(entityJson));
		} else if (entityClass == Location.class) {
		    entities.add((T) new Location(entityJson));
		} else if (entityClass == FeatureOfInterest.class) {
		    entities.add((T) new FeatureOfInterest(entityJson));
		}
	    }
	}

	String nextLink = response.optString("@iot.nextLink", null);
	if (nextLink != null && nextLink.isBlank()) {
	    nextLink = null;
	}

	Long count = null;
	if (response.has("@iot.count")) {
	    count = response.getLong("@iot.count");
	}

	return new PagedResult<>(entities, nextLink, count);
    }

    public static void main(String[] args) throws Exception {
	FROSTClient client = new FROSTClient("http://localhost:8080/FROST-Server/v1.1/");
	// Thing thing = new Thing("test", "description");
	// JSONObject properties = new JSONObject();
	// properties.put("annals-identifier", "xyz");
	// thing.setProperties(properties );
	// client.postThing(thing);

	PagedResult<Thing> response = client.getThingsByProperty("annals-identifier", "xyz");
	Thing thing = response.getItems().get(0);
	System.out.println(thing.toJSON());
	thing.getProperties().put("prop2", "value");
	client.patchThing(thing.getId(), thing);
	// client.deleteAllThings();
	// client.patchThing(, thing)
	// client.deleteAllThings();
	// client.deleteThing(13l);
	// for (int i = 0; i < 10000; i++) {
	// client.postThing(new Thing("test", "description"));
	// }

    }

    public void deleteAllThings() throws Exception {
	PagedResult<Thing> page = getThings();
	while (page != null) {
	    for (Thing thing : page.getItems()) {
		deleteThing(thing.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getThingsByNextLink(page.getNextLink());
	}
    }

    public void deleteAllDatastreams() throws Exception {
	PagedResult<Datastream> page = getDatastreams();
	while (page != null) {
	    for (Datastream datastream : page.getItems()) {
		deleteDatastream(datastream.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getDatastreamsByNextLink(page.getNextLink());
	}
    }

    public void deleteAllObservations() throws Exception {
	PagedResult<Observation> page = getObservations();
	while (page != null) {
	    for (Observation observation : page.getItems()) {
		deleteObservation(observation.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getObservationsByNextLink(page.getNextLink());
	}
    }

    public void deleteAllSensors() throws Exception {
	PagedResult<Sensor> page = getSensors();
	while (page != null) {
	    for (Sensor sensor : page.getItems()) {
		deleteSensor(sensor.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getSensorsByNextLink(page.getNextLink());
	}
    }

    public void deleteAllObservedProperties() throws Exception {
	PagedResult<ObservedProperty> page = getObservedProperties();
	while (page != null) {
	    for (ObservedProperty observedProperty : page.getItems()) {
		deleteObservedProperty(observedProperty.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getObservedPropertiesByNextLink(page.getNextLink());
	}
    }

    public void deleteAllLocations() throws Exception {
	PagedResult<Location> page = getLocations();
	while (page != null) {
	    for (Location location : page.getItems()) {
		deleteLocation(location.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getLocationsByNextLink(page.getNextLink());
	}
    }

    public void deleteAllFeaturesOfInterest() throws Exception {
	PagedResult<FeatureOfInterest> page = getFeaturesOfInterest();
	while (page != null) {
	    for (FeatureOfInterest featureOfInterest : page.getItems()) {
		deleteFeatureOfInterest(featureOfInterest.getId());
	    }
	    if (!page.hasNext()) {
		break;
	    }
	    page = getFeaturesOfInterestByNextLink(page.getNextLink());
	}
    }

    /**
     * Delete all entities from the FROST Server.
     * Deletes in order: Observations, Datastreams, Things, Locations, Sensors, ObservedProperties, FeaturesOfInterest
     * This order ensures dependent entities are deleted before their dependencies.
     * 
     * @throws Exception if any deletion fails
     */
    public void deleteAll() throws Exception {
	System.out.println("Deleting all Observations...");
	deleteAllObservations();

	System.out.println("Deleting all Datastreams...");
	deleteAllDatastreams();

	System.out.println("Deleting all Things...");
	deleteAllThings();

	System.out.println("Deleting all Locations...");
	deleteAllLocations();

	System.out.println("Deleting all Sensors...");
	deleteAllSensors();

	System.out.println("Deleting all ObservedProperties...");
	deleteAllObservedProperties();

	System.out.println("Deleting all FeaturesOfInterest...");
	deleteAllFeaturesOfInterest();

	System.out.println("All entities deleted successfully.");
    }

    private static final int BATCH_DELETE_SIZE = 1000;

    /**
     * Delete all observations for a specific datastream using batch operations.
     * @param datastreamId The ID of the datastream whose observations should be deleted
     */
    public void deleteObservationsByDatastream(Long datastreamId) throws Exception {
	String filter = "Datastream/id eq " + datastreamId;
	int totalDeleted = 0;
	
	PagedResult<Observation> page = getObservations(filter);
	while (page != null && !page.getItems().isEmpty()) {
	    List<Long> idsToDelete = new ArrayList<>();
	    for (Observation observation : page.getItems()) {
		idsToDelete.add(observation.getId());
	    }
	    
	    // Batch delete in chunks
	    for (int i = 0; i < idsToDelete.size(); i += BATCH_DELETE_SIZE) {
		int toIndex = Math.min(i + BATCH_DELETE_SIZE, idsToDelete.size());
		List<Long> batch = idsToDelete.subList(i, toIndex);
		batchDeleteObservations(batch);
		totalDeleted += batch.size();
	    }
	    
	    if (!page.hasNext()) {
		break;
	    }
	    page = getObservationsByNextLink(page.getNextLink());
	}
	
	if (totalDeleted > 0 && logRequests) {
	    System.out.println("Deleted " + totalDeleted + " existing observation(s) for datastream " + datastreamId);
	}
    }

    /**
     * Batch delete observations by their IDs using the $batch endpoint.
     */
    private void batchDeleteObservations(List<Long> observationIds) throws IOException, InterruptedException {
	if (observationIds.isEmpty()) return;
	
	String url = baseUrl + "$batch";
	JSONObject body = new JSONObject();
	JSONArray requests = new JSONArray();
	
	for (int i = 0; i < observationIds.size(); i++) {
	    JSONObject request = new JSONObject();
	    request.put("id", "" + i);
	    request.put("method", "delete");
	    request.put("url", "Observations(" + observationIds.get(i) + ")");
	    requests.put(request);
	}
	
	body.put("requests", requests);
	
	if (logRequests) {
	    System.out.println("Batch deleting " + observationIds.size() + " observation(s)...");
	}
	executePost(url, body);
    }

    public void postObservations(Long id, List<Observation> observations) throws Exception {
	// for (Observation observation : observations) {
	// postObservation(id,observation);
	// }
	if (logRequests){
	    System.out.println("Posting observations... (ds "+id+") (size "+observations.size()+")");
	}
	String url = baseUrl + "$batch";
	JSONObject body = new JSONObject();
	JSONArray requests = new JSONArray();
	for (int i = 0; i < observations.size(); i++) {
	    Observation observation = observations.get(i);
	    JSONObject request = new JSONObject();
	    request.put("id", "" + i);
	    request.put("method", "post");
	    request.put("url", "Datastreams(" + id + ")/Observations");
	    request.put("body", observation.toJSON());
	    requests.put(request);
	}
	body.put("requests", requests);
	executePost(url, body);

    }

    /**
     * Post pre-serialized observations (as raw JSONObjects) to a datastream.
     * More efficient than postObservations when observations are already serialized
     * with @iot.id included (no need to parse and re-serialize).
     * 
     * @param datastreamId the FROST server datastream ID
     * @param observationJsons list of pre-serialized observation JSONObjects
     */
    public void postObservationsRaw(Long datastreamId, List<JSONObject> observationJsons) throws Exception {
	if (logRequests){
	    System.out.println("Posting observations (raw)... (ds "+datastreamId+") (size "+observationJsons.size()+")");
	}
	String url = baseUrl + "$batch";
	JSONObject body = new JSONObject();
	JSONArray requests = new JSONArray();
	for (int i = 0; i < observationJsons.size(); i++) {
	    JSONObject obsJson = observationJsons.get(i);
	    JSONObject request = new JSONObject();
	    request.put("id", "" + i);
	    request.put("method", "post");
	    request.put("url", "Datastreams(" + datastreamId + ")/Observations");
	    request.put("body", obsJson);
	    requests.put(request);
	}
	body.put("requests", requests);
	executePost(url, body);
    }

    /**
     * Post a pre-serialized $batch request body string directly.
     * Most efficient method when the entire batch body is already serialized
     * (no JSON parsing or re-serialization needed during upload).
     * 
     * @param batchBodyString the pre-serialized $batch request body as a raw string
     */
    public void postBatchRaw(String batchBodyString) throws IOException, InterruptedException {
	if (logRequests){
	    System.out.println("Posting batch (raw string)...");
	}
	String url = baseUrl + "$batch";
	executePost(url, batchBodyString);
    }
}
