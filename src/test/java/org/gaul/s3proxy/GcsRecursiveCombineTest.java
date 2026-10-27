/*
 * Copyright 2014-2025 Andrew Gaul <andrew@gaul.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.s3proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.blobstore.util.ForwardingBlobStore;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class GcsRecursiveCombineTest {
    private static final String CONTAINER = "container";
    private static final String UPLOAD_ID = "upload";

    private BlobStoreContext context;
    private GcsComposeBlobStore blobStore;

    @Before
    public void setUp() {
        context = ContextBuilder
                .newBuilder("transient")
                .credentials("identity", "credential")
                .modules(List.of(new SLF4JLoggingModule()))
                .build(BlobStoreContext.class);
        blobStore = new GcsComposeBlobStore(context.getBlobStore());
        blobStore.createContainerInLocation(null, CONTAINER);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void testUploadWithinOneGroup() {
        assertCompletes(5);
    }

    @Test
    public void testUploadAtTheOldLimit() {
        assertCompletes(1024);
    }

    @Test
    public void testUploadPastTheOldLimit() {
        assertCompletes(1025);
    }

    @Test
    public void testUploadAtTheS3PartLimit() {
        assertCompletes(10_000);
    }

    /**
     * Upload {@code partCount} parts as handleUploadPart stores them, then
     * complete as handleCompleteMultipartUpload does.
     */
    private void assertCompletes(int partCount) {
        var expected = new StringBuilder();
        for (int partNumber = 1; partNumber <= partCount; ++partNumber) {
            String content = String.format("part %05d%n", partNumber);
            expected.append(content);
            String groupId = String.format("%s_%08d", UPLOAD_ID,
                    ((partNumber - 1) / 32) + 1);
            blobStore.putBlob(CONTAINER, blobStore.blobBuilder(
                    String.format("%s_%08d", groupId,
                            ((partNumber - 1) % 32) + 1))
                    .payload(content.getBytes(StandardCharsets.UTF_8))
                    .build());
        }
        BlobMetadata metadata = blobStore.blobBuilder("object")
                .payload(new byte[0]).build().getMetadata();
        MultipartUpload mpu = MultipartUpload.create(CONTAINER, "object",
                UPLOAD_ID, metadata, new PutOptions());

        List<MultipartPart> parts = new ArrayList<>();
        MultipartUpload finalMpu = S3ProxyHandler.combineGcsParts(blobStore,
                mpu, CONTAINER, metadata, new PutOptions(), parts);
        blobStore.completeMultipartUpload(finalMpu, parts);

        assertThat(read("object")).isEqualTo(expected.toString());
        assertThat(blobStore.countBlobs(CONTAINER)).isEqualTo(1);
    }

    private String read(String name) {
        try (InputStream is = blobStore.getBlob(CONTAINER, name).getPayload()
                .openStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    /**
     * Multipart the way jclouds' google-cloud-storage provider does it: part
     * N of an upload is the object {@code <id>_<N>}, and completing composes
     * those objects into the upload's blob, then deletes them.  Like GCS
     * compose, completion rejects more than 32 sources.
     */
    private static final class GcsComposeBlobStore
            extends ForwardingBlobStore {
        GcsComposeBlobStore(BlobStore blobStore) {
            super(blobStore);
        }

        @Override
        public String completeMultipartUpload(MultipartUpload mpu,
                List<MultipartPart> parts) {
            if (parts.size() > S3ProxyHandler.GCS_COMPOSE_MAX_SOURCES) {
                throw new IllegalArgumentException(
                        "compose takes at most 32 sources, got " +
                        parts.size());
            }
            var composed = new ByteArrayOutputStream();
            List<String> sources = new ArrayList<>();
            for (MultipartPart part : parts) {
                String source = String.format("%s_%08d", mpu.id(),
                        part.partNumber());
                try (InputStream is = getBlob(mpu.containerName(), source)
                        .getPayload().openStream()) {
                    is.transferTo(composed);
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
                sources.add(source);
            }
            String eTag = putBlob(mpu.containerName(),
                    blobBuilder(mpu.blobName())
                            .payload(composed.toByteArray()).build());
            removeBlobs(mpu.containerName(), sources);
            return eTag;
        }

        @Override
        public List<MultipartPart> listMultipartUpload(MultipartUpload mpu) {
            List<MultipartPart> parts = new ArrayList<>();
            for (StorageMetadata sm : list(mpu.containerName(),
                    new ListContainerOptions().prefix(mpu.id() + "_"))) {
                String name = sm.getName();
                parts.add(MultipartPart.create(
                        Integer.parseInt(name.substring(
                                name.lastIndexOf('_') + 1)),
                        sm.getSize(), sm.getETag(), sm.getLastModified()));
            }
            return parts;
        }
    }
}
