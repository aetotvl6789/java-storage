/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage;

import static com.google.cloud.RetryHelper.runWithRetries;

import com.google.api.client.util.Preconditions;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Objects;

/** Default implementation for ReadChannel. */
class BlobReadChannel implements ReadChannel {

  private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

  private final StorageOptions serviceOptions;
  private final BlobId blob;
  private final Map<StorageRpc.Option, ?> requestOptions;
  private final RetryAlgorithmManager retryAlgorithmManager;
  private String lastEtag;
  private long position;
  private boolean isOpen;
  private boolean endOfStream;
  private int chunkSize = DEFAULT_CHUNK_SIZE;

  private final StorageRpc storageRpc;
  private final StorageObject storageObject;
  private int bufferPos;
  private byte[] buffer;
  private long limit;

  BlobReadChannel(
      StorageOptions serviceOptions, BlobId blob, Map<StorageRpc.Option, ?> requestOptions) {
    this.serviceOptions = serviceOptions;
    this.blob = blob;
    this.requestOptions = requestOptions;
    this.retryAlgorithmManager = serviceOptions.getRetryAlgorithmManager();
    isOpen = true;
    storageRpc = serviceOptions.getStorageRpcV1();
    storageObject = blob.toPb();
    this.limit = Long.MAX_VALUE;
  }

  @Override
  public RestorableState<ReadChannel> capture() {
    StateImpl.Builder builder =
        StateImpl.builder(serviceOptions, blob, requestOptions)
            .setPosition(position)
            .setIsOpen(isOpen)
            .setEndOfStream(endOfStream)
            .setChunkSize(chunkSize)
            .setLimit(limit);
    if (buffer != null) {
      builder.setPosition(position + bufferPos);
      builder.setEndOfStream(false);
    }
    return builder.build();
  }

  @Override
  public boolean isOpen() {
    return isOpen;
  }

  @Override
  public void close() {
    if (isOpen) {
      buffer = null;
      isOpen = false;
    }
  }

  private void validateOpen() throws ClosedChannelException {
    if (!isOpen) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public void seek(long position) throws IOException {
    validateOpen();
    this.position = position;
    buffer = null;
    bufferPos = 0;
    endOfStream = false;
  }

  @Override
  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  @Override
  public int read(ByteBuffer byteBuffer) throws IOException {
    validateOpen();
    if (buffer == null) {
      if (endOfStream) {
        return -1;
      }
      final int toRead =
          Math.toIntExact(Math.min(limit - position, Math.max(byteBuffer.remaining(), chunkSize)));
      if (toRead <= 0) {
        endOfStream = true;
        return -1;
      }
      try {
        ResultRetryAlgorithm<?> algorithm =
            retryAlgorithmManager.getForObjectsGet(storageObject, requestOptions);
        Tuple<String, byte[]> result =
            runWithRetries(
                () -> storageRpc.read(storageObject, requestOptions, position, toRead),
                serviceOptions.getRetrySettings(),
                algorithm,
                serviceOptions.getClock());
        String etag = result.x();
        byte[] bytes = result.y();
        if (bytes.length > 0 && lastEtag != null && !Objects.equals(etag, lastEtag)) {
          throw new IOException("Blob " + blob + " was updated while reading");
        }
        lastEtag = etag;
        buffer = bytes;
      } catch (RetryHelper.RetryHelperException e) {
        throw new IOException(e);
      }
      if (toRead > buffer.length) {
        endOfStream = true;
        if (buffer.length == 0) {
          buffer = null;
          return -1;
        }
      }
    }
    int toWrite = Math.min(buffer.length - bufferPos, byteBuffer.remaining());
    byteBuffer.put(buffer, bufferPos, toWrite);
    bufferPos += toWrite;
    if (bufferPos >= buffer.length) {
      position += buffer.length;
      buffer = null;
      bufferPos = 0;
    }
    return toWrite;
  }

  @Override
  public ReadChannel limit(long limit) {
    Preconditions.checkArgument(limit >= 0, "Limit must be >= 0");
    this.limit = limit;
    return this;
  }

  @Override
  public long limit() {
    return limit;
  }

  static class StateImpl implements RestorableState<ReadChannel>, Serializable {

    private static final long serialVersionUID = 3889420316004453706L;

    private final StorageOptions serviceOptions;
    private final BlobId blob;
    private final Map<StorageRpc.Option, ?> requestOptions;
    private final String lastEtag;
    private final long position;
    private final boolean isOpen;
    private final boolean endOfStream;
    private final int chunkSize;
    private final long limit;

    StateImpl(Builder builder) {
      this.serviceOptions = builder.serviceOptions;
      this.blob = builder.blob;
      this.requestOptions = builder.requestOptions;
      this.lastEtag = builder.lastEtag;
      this.position = builder.position;
      this.isOpen = builder.isOpen;
      this.endOfStream = builder.endOfStream;
      this.chunkSize = builder.chunkSize;
      this.limit = builder.limit;
    }

    static class Builder {
      private final StorageOptions serviceOptions;
      private final BlobId blob;
      private final Map<StorageRpc.Option, ?> requestOptions;
      private String lastEtag;
      private long position;
      private boolean isOpen;
      private boolean endOfStream;
      private int chunkSize;
      private long limit;

      private Builder(StorageOptions options, BlobId blob, Map<StorageRpc.Option, ?> reqOptions) {
        this.serviceOptions = options;
        this.blob = blob;
        this.requestOptions = reqOptions;
      }

      Builder setLastEtag(String lastEtag) {
        this.lastEtag = lastEtag;
        return this;
      }

      Builder setPosition(long position) {
        this.position = position;
        return this;
      }

      Builder setIsOpen(boolean isOpen) {
        this.isOpen = isOpen;
        return this;
      }

      Builder setEndOfStream(boolean endOfStream) {
        this.endOfStream = endOfStream;
        return this;
      }

      Builder setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        return this;
      }

      Builder setLimit(long limit) {
        this.limit = limit;
        return this;
      }

      RestorableState<ReadChannel> build() {
        return new StateImpl(this);
      }
    }

    static Builder builder(
        StorageOptions options, BlobId blob, Map<StorageRpc.Option, ?> reqOptions) {
      return new Builder(options, blob, reqOptions);
    }

    @Override
    public ReadChannel restore() {
      BlobReadChannel channel = new BlobReadChannel(serviceOptions, blob, requestOptions);
      channel.lastEtag = lastEtag;
      channel.position = position;
      channel.isOpen = isOpen;
      channel.endOfStream = endOfStream;
      channel.chunkSize = chunkSize;
      channel.limit = limit;
      return channel;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          serviceOptions,
          blob,
          requestOptions,
          lastEtag,
          position,
          isOpen,
          endOfStream,
          chunkSize,
          limit);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (!(obj instanceof StateImpl)) {
        return false;
      }
      final StateImpl other = (StateImpl) obj;
      return Objects.equals(this.serviceOptions, other.serviceOptions)
          && Objects.equals(this.blob, other.blob)
          && Objects.equals(this.requestOptions, other.requestOptions)
          && Objects.equals(this.lastEtag, other.lastEtag)
          && this.position == other.position
          && this.isOpen == other.isOpen
          && this.endOfStream == other.endOfStream
          && this.chunkSize == other.chunkSize
          && this.limit == other.limit;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("blob", blob)
          .add("position", position)
          .add("isOpen", isOpen)
          .add("endOfStream", endOfStream)
          .add("limit", limit)
          .toString();
    }
  }
}
