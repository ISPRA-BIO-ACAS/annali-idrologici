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

import java.util.Collections;
import java.util.List;

/**
 * Wrapper for paginated SensorThings API responses.
 *
 * @param <T> Type of entity contained in the response page
 */
public class PagedResult<T> {

    private final List<T> items;
    private final String nextLink;
    private final Long count;

    public PagedResult(List<T> items, String nextLink, Long count) {
        this.items = items == null ? Collections.emptyList() : Collections.unmodifiableList(items);
        this.nextLink = nextLink;
        this.count = count;
    }

    /**
     * Entities contained in this page.
     */
    public List<T> getItems() {
        return items;
    }

    /**
     * Link to the next page, if the server indicates more data is available.
     */
    public String getNextLink() {
        return nextLink;
    }

    /**
     * Total number of entities available on the server (only set when the
     * {@code $count} query option is used).
     */
    public Long getCount() {
        return count;
    }

    /**
     * Convenience method to check whether another page can be fetched.
     */
    public boolean hasNext() {
        return nextLink != null && !nextLink.isBlank();
    }
}


