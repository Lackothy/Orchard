// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.search.index;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.List;
import java.util.Map;

interface SearchIndexContext<T> {
    List<T> items();

    List<String> normalizedTexts();

    List<Map<String, Integer>> documentTermCounts();

    Map<String, IntArrayList> invertedIndex();

    Map<String, Double> idfWeights();

    SearchNormalizer normalizer();

    NgramGenerator ngramGenerator();

    int size();

    int maxResults();

    double minScore();

    boolean rerankWithEditDistance();

    int rerankLimit();

    double rerankWeight();
}