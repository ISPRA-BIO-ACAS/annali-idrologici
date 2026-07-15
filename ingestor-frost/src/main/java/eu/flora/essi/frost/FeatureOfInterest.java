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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wrapper class for FROST Server FeatureOfInterest entity
 */
public class FeatureOfInterest {
    private JSONObject json;

    public FeatureOfInterest() {
        this.json = new JSONObject();
    }

    public FeatureOfInterest(JSONObject json) {
        this.json = json;
    }

    public FeatureOfInterest(String name, String description, String encodingType, double longitude, double latitude) {
        this();
        setName(name);
        setDescription(description);
        setEncodingType(encodingType);
        setPoint(longitude, latitude);
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

    public void setPoint(double longitude, double latitude) {
        JSONObject feature = new JSONObject();
        feature.put("type", "Point");
        JSONArray coordinates = new JSONArray();
        coordinates.put(0, longitude);
        coordinates.put(1, latitude);
        feature.put("coordinates", coordinates);
        json.put("feature", feature);
    }

    public JSONObject getFeature() {
        return json.optJSONObject("feature");
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
        return new JSONObject(json.toString());
    }

    @Override
    public String toString() {
        return json.toString();
    }
}

