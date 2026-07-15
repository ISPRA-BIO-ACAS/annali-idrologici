package eu.flora.essi.frost;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wrapper class for FROST Server Observation entity
 */
public class Observation {
    private JSONObject json;

    public Observation() {
	this.json = new JSONObject();
    }

    public Observation(JSONObject json) {
	this.json = json;
    }

    public Observation(Object result, String phenomenonTime) {
	this();
	setResult(result);
	setPhenomenonTime(phenomenonTime);
    }

    public Object getResult() {
	return json.opt("result");
    }

    public void setResult(Object result) {
	json.put("result", result);
    }

    public String getPhenomenonTime() {
	return json.optString("phenomenonTime", null);
    }

    public void setPhenomenonTime(String phenomenonTime) {
	json.put("phenomenonTime", phenomenonTime);
    }

    public String getResultTime() {
	return json.optString("resultTime", null);
    }

    public void setResultTime(String resultTime) {
	json.put("resultTime", resultTime);
    }

    public void setDatastreamId(Long datastreamId) {
	JSONObject datastream = new JSONObject();
	datastream.put("@iot.id", datastreamId);
	json.put("Datastream", datastream);
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

    public JSONObject getParameters() {
	return json.optJSONObject("parameters");
    }

    public void setParameters(JSONObject parameters) {
	json.put("parameters", parameters);
    }

    public void setResultQuality(String flag, String flagDescription) {

	JSONObject qualityObject = new JSONObject();
	qualityObject.put("code", flag);
	qualityObject.put("label", flagDescription);

	JSONObject quality = new JSONObject();
	quality.put("DQ_Status", qualityObject);

	json.put("resultQuality", quality);

    }
}

