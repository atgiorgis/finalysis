package com.finalysis.categorize;

/** A request names a category that doesn't exist. Mapped to 422 {@code category-invalid}. */
public class CategoryInvalidException extends RuntimeException {

    public CategoryInvalidException(long categoryId) {
        super("Category " + categoryId + " does not exist");
    }
}
