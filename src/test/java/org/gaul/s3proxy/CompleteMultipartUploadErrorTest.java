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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.util.ForwardingBlobStore;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A completion that fails after the status is committed must still give the
 * client a whole response it can classify, not a truncated result body.
 */
public final class CompleteMultipartUploadErrorTest {
    private static final String CONTAINER = "container";

    private BlobStoreContext context;
    private S3Proxy s3Proxy;
    private AmazonS3 client;

    @Before
    public void setUp() throws Exception {
        context = ContextBuilder
                .newBuilder("transient")
                .credentials("identity", "credential")
                .modules(List.of(new SLF4JLoggingModule()))
                .build(BlobStoreContext.class);
        BlobStore blobStore = new FailingCompleteBlobStore(
                context.getBlobStore());
        blobStore.createContainerInLocation(null, CONTAINER);

        s3Proxy = S3Proxy.builder()
                .blobStore(blobStore)
                .endpoint(URI.create("http://127.0.0.1:0"))
                .build();
        s3Proxy.start();
        while (!s3Proxy.getState().equals(AbstractLifeCycle.STARTED)) {
            Thread.sleep(1);
        }

        client = AmazonS3ClientBuilder.standard()
                .withClientConfiguration(
                        new ClientConfiguration().withMaxErrorRetry(0))
                .withCredentials(new AWSStaticCredentialsProvider(
                        new AnonymousAWSCredentials()))
                .withEndpointConfiguration(new EndpointConfiguration(
                        "http://127.0.0.1:" + s3Proxy.getPort(),
                        "us-east-1"))
                .withPathStyleAccessEnabled(true)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (s3Proxy != null) {
            s3Proxy.stop();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void testFailedCompletionReturnsAnErrorDocument() {
        String uploadId = client.initiateMultipartUpload(
                new InitiateMultipartUploadRequest(CONTAINER, "object"))
                .getUploadId();
        byte[] content = new byte[1024];
        PartETag partETag = client.uploadPart(new UploadPartRequest()
                .withBucketName(CONTAINER)
                .withKey("object")
                .withUploadId(uploadId)
                .withPartNumber(1)
                .withInputStream(new ByteArrayInputStream(content))
                .withPartSize(content.length))
                .getPartETag();

        AmazonS3Exception e = null;
        try {
            client.completeMultipartUpload(new CompleteMultipartUploadRequest(
                    CONTAINER, "object", uploadId, List.of(partETag)));
        } catch (AmazonS3Exception ase) {
            e = ase;
        }

        assertThat(e).isNotNull();
        assertThat(e.getStatusCode()).isEqualTo(200);
        assertThat(e.getErrorCode()).isEqualTo(
                S3ErrorCode.INTERNAL_ERROR.getErrorCode());
        assertThat(e.getErrorMessage()).doesNotContain("compose");
    }

    private static final class FailingCompleteBlobStore
            extends ForwardingBlobStore {
        FailingCompleteBlobStore(BlobStore blobStore) {
            super(blobStore);
        }

        @Override
        public String completeMultipartUpload(MultipartUpload mpu,
                List<MultipartPart> parts) {
            throw new IllegalStateException("compose failed");
        }
    }
}
