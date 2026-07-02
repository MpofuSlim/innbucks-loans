package zw.co.reikan.loans.core.bulk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "bulk-ingestion")
public class BulkIngestionProperties {

    /** Applications per worker chunk (Spring Batch-style reader/writer unit). */
    private int chunkSize = 100;

    /** Hard cap on items per submitted batch. */
    private int maxBatchSize = 5_000;
}
