package eu.flora.essi.frost;

import java.math.BigDecimal;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wrapper class for FROST Server Location entity
 */
public class Location {
    private JSONObject json;

    public Location() {
	this.json = new JSONObject();
    }

    public Location(JSONObject json) {
	this.json = json;
    }

    public Location(String name, String description, BigDecimal longitude, BigDecimal latitude, BigDecimal elevation) {
	this();
	setName(name);
	setDescription(description);
	setPoint(longitude, latitude, elevation);
    }

    public String getName() {
	return json.optString("name", null);
    }

    public void setName(String name) {
	json.put("name", name);
    }

    public String getDescription() {
	return json.optString("description", null);
    }

    public void setDescription(String description) {
	json.put("description", description);
    }

    public String getEncodingType() {
	return json.optString("encodingType", null);
    }

    public void setEncodingType(String encodingType) {
	json.put("encodingType", encodingType);
    }

    public void setPoint(BigDecimal longitude, BigDecimal latitude) {
	setPoint(longitude, latitude, null);
    }

    public void setPoint(BigDecimal longitude, BigDecimal latitude, BigDecimal elevation) {
	JSONObject feature = new JSONObject();
	feature.put("type", "Point");
	JSONArray coordinates = new JSONArray();
	coordinates.put(0, longitude);
	coordinates.put(1, latitude);
	if (elevation != null) {
	    coordinates.put(2, elevation);
	}
	feature.put("coordinates", coordinates);
	json.put("location", feature);
	setEncodingType("application/geo+json");
    }

    public JSONObject getLocation() {
	return json.optJSONObject("location");
    }

    public JSONObject getProperties() {
	return json.optJSONObject("properties");
    }

    public void setProperties(JSONObject properties) {
	json.put("properties", properties);
    }

    public void setThingId(Long thingId) {
	JSONObject thing = new JSONObject();
	thing.put("@iot.id", thingId);
	json.put("Thing", thing);
    }

    public Long getId() {
	if (json.has("@iot.id")) {
	    return json.getLong("@iot.id");
	}
	return null;
    }

    public void setId(Long id) {
	json.put("@iot.id", id);
    }

    public JSONObject toJSON() {
	return json;
    }

    @Override
    public String toString() {
	return json.toString();
    }
}
