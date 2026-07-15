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


