package com.finalysis.categorize;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    // Sorted here rather than with ORDER BY: the database's order depends on its collation
    // ("HOA & Condo Fees" sorts before "Health" under C, after it under en_US.utf8).
    private static final Comparator<CategoryResponse> BY_NAME =
            Comparator.comparing(CategoryResponse::name, String.CASE_INSENSITIVE_ORDER);

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categories.findAll().stream().map(CategoryResponse::from).sorted(BY_NAME).toList();
    }
}
