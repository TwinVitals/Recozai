package sn.recozai.shared;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long totalElements) {
    public Page { items=List.copyOf(items); }
}
