package com.finalysis.categorize;

public record CategoryResponse(Long id, String name, CategoryKind kind, Long parentId) {

    static CategoryResponse from(Category category) {
        // Reading the id of a lazy parent proxy doesn't load the parent row.
        Category parent = category.getParent();
        return new CategoryResponse(category.getId(), category.getName(), category.getKind(),
                parent == null ? null : parent.getId());
    }
}
