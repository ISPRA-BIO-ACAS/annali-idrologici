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


