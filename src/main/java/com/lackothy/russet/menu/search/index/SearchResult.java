// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.search.index;

public final class SearchResult<T> {
    private final T item;
    private final double score;
    private final int documentId;

    SearchResult(T item, double score, int documentId) {
        this.item = item;
        this.score = score;
        this.documentId = documentId;
    }

    public T item() {
        return this.item;
    }

    public double score() {
        return this.score;
    }

    int documentId() {
        return this.documentId;
    }
}