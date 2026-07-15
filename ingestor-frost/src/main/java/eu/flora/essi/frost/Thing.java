package eu.flora.essi.frost;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wrapper class for FROST Server Thing entity
 */
public class Thing {
    private JSONObject json;

    public Thing() {
	this.json = new JSONObject();
    }

    public Thing(JSONObject json) {
	this.json = json;
    }

    public Thing(String name, String description) {
	this();
	setName(name);
	setDescription(description);
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

    public JSONObject getProperties() {
	return json.optJSONObject("properties");
    }

    public void setProperties(JSONObject properties) {
	json.put("properties", properties);
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

    public void setLocation(Location location) {
	JSONArray jsonArray = new JSONArray();
	jsonArray.put(location.toJSON());
	json.put("Locations", jsonArray);

    }

    public void setDatastreams(List<Datastream> datastreams) {
	JSONArray jsonArray = new JSONArray();
	for (Datastream datastream : datastreams) {
	    jsonArray.put(datastream.toJSON());
	}
	json.put("Datastreams", jsonArray);
    }
}


