package eu.flora.essi.frost;

import org.json.JSONObject;

/**
 * Wrapper class for FROST Server ObservedProperty entity
 */
public class ObservedProperty {
    private JSONObject json;

    public ObservedProperty() {
        this.json = new JSONObject();
    }

    public ObservedProperty(JSONObject json) {
        this.json = json;
    }

    public ObservedProperty(String name, String description, String definition) {
        this();
        setName(name);
        setDescription(description);
        setDefinition(definition);
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

    public String getDefinition() {
        return json.optString("definition", null);
    }

    public void setDefinition(String definition) {
        json.put("definition", definition);
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
}



