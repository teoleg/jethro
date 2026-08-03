package io.muniworld.ingest;

import java.util.List;

/**
 * A registered source's fetcher (ADR-0004). Chosen by the source's access method (api / bulk_file / scrape /
 * document); every one produces immutable {@link RawArtifact} landings, which the parse→normalise→index
 * stages then consume. This is the SPI the ingestion scheduler drives.
 */
public interface SourceConnector {

    /** The source-registry id this connector fetches for (ADR-0003). */
    String sourceId();

    /** Fetch the source's current artifacts into the landing zone. */
    List<RawArtifact> fetch();
}
