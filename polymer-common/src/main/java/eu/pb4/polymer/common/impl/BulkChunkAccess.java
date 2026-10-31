package eu.pb4.polymer.common.impl;

import eu.pb4.polymer.common.api.PolymerBulkEdits;

/** Loaded-chunk ownership for direct section replacement; never shared between chunk incarnations. */
public interface BulkChunkAccess {
    boolean polymer$bulkLoaded();
    long polymer$bulkRevision();
    void polymer$bulkChanged();
    PolymerBulkEdits.Token polymer$bulkToken();
    void polymer$bulkToken(PolymerBulkEdits.Token token);
    boolean polymer$bulkQuarantined();
    void polymer$bulkQuarantine();
}
