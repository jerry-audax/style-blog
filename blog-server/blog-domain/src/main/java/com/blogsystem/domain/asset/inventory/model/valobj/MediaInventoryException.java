package com.blogsystem.domain.asset.inventory.model.valobj;

/** Provider inventory was unavailable or returned an unsafe/inconsistent page. */
public class MediaInventoryException extends RuntimeException {
    public MediaInventoryException(String message) {
        super(message);
    }

    public MediaInventoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
