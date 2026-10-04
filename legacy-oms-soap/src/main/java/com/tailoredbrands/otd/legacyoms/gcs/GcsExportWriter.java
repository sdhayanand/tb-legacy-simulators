package com.tailoredbrands.otd.legacyoms.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes the nightly {@code <Orders>} extract to a GCS bucket - the "FTP drop" the batch reconciliation job
 * ({@code tb-order-events-dataflow} DailyReconciliationPipeline) reads from.
 *
 * <p>Degrades gracefully: when no Application Default Credentials are available (local docker-compose,
 * unit tests) {@link #write} throws {@link GcsUnavailableException} and the caller reports SKIPPED
 * instead of failing the request.
 */
@Component
public class GcsExportWriter {

    private static final Logger log = LoggerFactory.getLogger(GcsExportWriter.class);

    public String write(String bucket, String objectName, String xml) {
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new GcsUnavailableException("No Google Application Default Credentials: " + e.getMessage(), e);
        }
        try {
            Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                    .setContentType("application/xml")
                    .build();
            storage.create(blobInfo, xml.getBytes(StandardCharsets.UTF_8));
            String uri = "gs://" + bucket + "/" + objectName;
            log.info("Wrote OMS extract {} ({} bytes)", uri, xml.length());
            return uri;
        } catch (RuntimeException e) {
            throw new GcsUnavailableException("GCS write failed: " + e.getMessage(), e);
        }
    }

    public static class GcsUnavailableException extends RuntimeException {
        public GcsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
