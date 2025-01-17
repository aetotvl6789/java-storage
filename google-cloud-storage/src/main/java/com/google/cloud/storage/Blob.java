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

import static com.google.cloud.storage.Blob.BlobSourceOption.toGetOptions;
import static com.google.cloud.storage.Blob.BlobSourceOption.toSourceOptions;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.services.storage.model.StorageObject;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.Storage.CopyRequest;
import com.google.cloud.storage.Storage.SignUrlOption;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.Key;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * An object in Google Cloud Storage. A {@code Blob} object includes the {@code BlobId} instance,
 * the set of properties inherited from the {@link BlobInfo} class and the {@code Storage} instance.
 * The class provides methods to perform operations on the object. Reading a property value does not
 * issue any RPC calls. The object content is not stored within the {@code Blob} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects of this class are immutable. Operations that modify the blob like {@link #update} and
 * {@link #copyTo} return a new object. Any changes to the object in Google Cloud Storage made after
 * creation of the {@code Blob} are not visible in the {@code Blob}. To get a {@code Blob} object
 * with the most recent information use {@link #reload}.
 *
 * <p>Example of getting the content of the object in Google Cloud Storage:
 *
 * <pre>{@code
 * BlobId blobId = BlobId.of(bucketName, blobName);
 * Blob blob = storage.get(blobId);
 * long size = blob.getSize(); // no RPC call is required
 * byte[] content = blob.getContent(); // one or multiple RPC calls will be issued
 * }</pre>
 */
public class Blob extends BlobInfo {

  private static final long serialVersionUID = -6806832496717441434L;

  private final StorageOptions options;
  private final RetryAlgorithmManager retryAlgorithmManager;
  private transient Storage storage;

  /** Class for specifying blob source options when {@code Blob} methods are used. */
  public static class BlobSourceOption extends Option {

    private static final long serialVersionUID = 214616862061934846L;

    private BlobSourceOption(StorageRpc.Option rpcOption) {
      super(rpcOption, null);
    }

    private BlobSourceOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    private Storage.BlobSourceOption toSourceOptions(BlobInfo blobInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobSourceOption.generationMatch(blobInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobSourceOption.generationNotMatch(blobInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobSourceOption.metagenerationMatch(blobInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobSourceOption.metagenerationNotMatch(blobInfo.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobSourceOption.decryptionKey((String) getValue());
        case USER_PROJECT:
          return Storage.BlobSourceOption.userProject((String) getValue());
        case RETURN_RAW_INPUT_STREAM:
          return Storage.BlobSourceOption.shouldReturnRawInputStream((boolean) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private Storage.BlobGetOption toGetOption(BlobInfo blobInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobGetOption.generationMatch(blobInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobGetOption.generationNotMatch(blobInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobGetOption.metagenerationMatch(blobInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobGetOption.metagenerationNotMatch(blobInfo.getMetageneration());
        case USER_PROJECT:
          return Storage.BlobGetOption.userProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobGetOption.decryptionKey((String) getValue());
        case RETURN_RAW_INPUT_STREAM:
          return Storage.BlobGetOption.shouldReturnRawInputStream((boolean) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobSourceOption generationMatch() {
      return new BlobSourceOption(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobSourceOption generationNotMatch() {
      return new BlobSourceOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobSourceOption metagenerationMatch() {
      return new BlobSourceOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobSourceOption metagenerationNotMatch() {
      return new BlobSourceOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobSourceOption decryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobSourceOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobSourceOption decryptionKey(String key) {
      return new BlobSourceOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, key);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobSourceOption userProject(String userProject) {
      return new BlobSourceOption(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option for whether the request should return the raw input stream, instead of
     * automatically decompressing the content. By default, this is false for Blob.downloadTo(), but
     * true for ReadChannel.read().
     */
    public static BlobSourceOption shouldReturnRawInputStream(boolean shouldReturnRawInputStream) {
      return new BlobSourceOption(
          StorageRpc.Option.RETURN_RAW_INPUT_STREAM, shouldReturnRawInputStream);
    }

    static Storage.BlobSourceOption[] toSourceOptions(
        BlobInfo blobInfo, BlobSourceOption... options) {
      Storage.BlobSourceOption[] convertedOptions = new Storage.BlobSourceOption[options.length];
      int index = 0;
      for (BlobSourceOption option : options) {
        convertedOptions[index++] = option.toSourceOptions(blobInfo);
      }
      return convertedOptions;
    }

    static Storage.BlobGetOption[] toGetOptions(BlobInfo blobInfo, BlobSourceOption... options) {
      Storage.BlobGetOption[] convertedOptions = new Storage.BlobGetOption[options.length];
      int index = 0;
      for (BlobSourceOption option : options) {
        convertedOptions[index++] = option.toGetOption(blobInfo);
      }
      return convertedOptions;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param path destination
   * @param options blob read options
   * @throws StorageException upon failure
   */
  public void downloadTo(Path path, BlobSourceOption... options) {
    storage.downloadTo(getBlobId(), path, BlobSourceOption.toSourceOptions(this, options));
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param outputStream
   * @param options
   */
  public void downloadTo(OutputStream outputStream, BlobSourceOption... options) {
    storage.downloadTo(getBlobId(), outputStream, BlobSourceOption.toSourceOptions(this, options));
  }

  /**
   * Downloads this blob to the given file path.
   *
   * <p>This method is replaced with {@link #downloadTo(Path, BlobSourceOption...)}, but is kept
   * here for binary compatibility with the older versions of the client library.
   *
   * @param path destination
   * @throws StorageException upon failure
   */
  public void downloadTo(Path path) {
    downloadTo(path, new BlobSourceOption[0]);
  }

  /** Builder for {@code Blob}. */
  public static class Builder extends BlobInfo.Builder {

    private final Storage storage;
    private final BlobInfo.BuilderImpl infoBuilder;

    Builder(Blob blob) {
      this.storage = blob.getStorage();
      this.infoBuilder = new BlobInfo.BuilderImpl(blob);
    }

    @Override
    public Builder setBlobId(BlobId blobId) {
      infoBuilder.setBlobId(blobId);
      return this;
    }

    @Override
    Builder setGeneratedId(String generatedId) {
      infoBuilder.setGeneratedId(generatedId);
      return this;
    }

    @Override
    public Builder setContentType(String contentType) {
      infoBuilder.setContentType(contentType);
      return this;
    }

    @Override
    public Builder setContentDisposition(String contentDisposition) {
      infoBuilder.setContentDisposition(contentDisposition);
      return this;
    }

    @Override
    public Builder setContentLanguage(String contentLanguage) {
      infoBuilder.setContentLanguage(contentLanguage);
      return this;
    }

    @Override
    public Builder setContentEncoding(String contentEncoding) {
      infoBuilder.setContentEncoding(contentEncoding);
      return this;
    }

    @Override
    Builder setComponentCount(Integer componentCount) {
      infoBuilder.setComponentCount(componentCount);
      return this;
    }

    @Override
    public Builder setCacheControl(String cacheControl) {
      infoBuilder.setCacheControl(cacheControl);
      return this;
    }

    @Override
    public Builder setAcl(List<Acl> acl) {
      infoBuilder.setAcl(acl);
      return this;
    }

    @Override
    Builder setOwner(Entity owner) {
      infoBuilder.setOwner(owner);
      return this;
    }

    @Override
    Builder setSize(Long size) {
      infoBuilder.setSize(size);
      return this;
    }

    @Override
    Builder setEtag(String etag) {
      infoBuilder.setEtag(etag);
      return this;
    }

    @Override
    Builder setSelfLink(String selfLink) {
      infoBuilder.setSelfLink(selfLink);
      return this;
    }

    @Override
    public Builder setMd5(String md5) {
      infoBuilder.setMd5(md5);
      return this;
    }

    @Override
    public Builder setMd5FromHexString(String md5HexString) {
      infoBuilder.setMd5FromHexString(md5HexString);
      return this;
    }

    @Override
    public Builder setCrc32c(String crc32c) {
      infoBuilder.setCrc32c(crc32c);
      return this;
    }

    @Override
    public Builder setCrc32cFromHexString(String crc32cHexString) {
      infoBuilder.setCrc32cFromHexString(crc32cHexString);
      return this;
    }

    @Override
    Builder setMediaLink(String mediaLink) {
      infoBuilder.setMediaLink(mediaLink);
      return this;
    }

    @Override
    public Builder setMetadata(Map<String, String> metadata) {
      infoBuilder.setMetadata(metadata);
      return this;
    }

    @Override
    public Builder setStorageClass(StorageClass storageClass) {
      infoBuilder.setStorageClass(storageClass);
      return this;
    }

    @Override
    public Builder setTimeStorageClassUpdated(Long timeStorageClassUpdated) {
      infoBuilder.setTimeStorageClassUpdated(timeStorageClassUpdated);
      return this;
    }

    @Override
    Builder setMetageneration(Long metageneration) {
      infoBuilder.setMetageneration(metageneration);
      return this;
    }

    @Override
    Builder setDeleteTime(Long deleteTime) {
      infoBuilder.setDeleteTime(deleteTime);
      return this;
    }

    @Override
    Builder setUpdateTime(Long updateTime) {
      infoBuilder.setUpdateTime(updateTime);
      return this;
    }

    @Override
    Builder setCreateTime(Long createTime) {
      infoBuilder.setCreateTime(createTime);
      return this;
    }

    @Override
    public Builder setCustomTime(Long customTime) {
      infoBuilder.setCustomTime(customTime);
      return this;
    }

    @Override
    Builder setIsDirectory(boolean isDirectory) {
      infoBuilder.setIsDirectory(isDirectory);
      return this;
    }

    @Override
    Builder setCustomerEncryption(CustomerEncryption customerEncryption) {
      infoBuilder.setCustomerEncryption(customerEncryption);
      return this;
    }

    @Override
    Builder setKmsKeyName(String kmsKeyName) {
      infoBuilder.setKmsKeyName(kmsKeyName);
      return this;
    }

    @Override
    public Builder setEventBasedHold(Boolean eventBasedHold) {
      infoBuilder.setEventBasedHold(eventBasedHold);
      return this;
    }

    @Override
    public Builder setTemporaryHold(Boolean temporaryHold) {
      infoBuilder.setTemporaryHold(temporaryHold);
      return this;
    }

    @Override
    Builder setRetentionExpirationTime(Long retentionExpirationTime) {
      infoBuilder.setRetentionExpirationTime(retentionExpirationTime);
      return this;
    }

    @Override
    public Blob build() {
      return new Blob(storage, infoBuilder);
    }
  }

  Blob(Storage storage, BlobInfo.BuilderImpl infoBuilder) {
    super(infoBuilder);
    this.storage = checkNotNull(storage);
    this.options = storage.getOptions();
    this.retryAlgorithmManager = storage.getOptions().getRetryAlgorithmManager();
  }

  /**
   * Checks if this blob exists.
   *
   * <p>Example of checking if the blob exists.
   *
   * <pre>{@code
   * boolean exists = blob.exists();
   * if (exists) {
   *   // the blob exists
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param options blob read options
   * @return true if this blob exists, false otherwise
   * @throws StorageException upon failure
   */
  public boolean exists(BlobSourceOption... options) {
    int length = options.length;
    Storage.BlobGetOption[] getOptions = Arrays.copyOf(toGetOptions(this, options), length + 1);
    getOptions[length] = Storage.BlobGetOption.fields();
    return storage.get(getBlobId(), getOptions) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example of reading all bytes of the blob, if its generation matches the {@link
   * Blob#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobSourceOption.generationMatch());
   * }</pre>
   *
   * @param options blob read options
   * @throws StorageException upon failure
   */
  public byte[] getContent(BlobSourceOption... options) {
    return storage.readAllBytes(getBlobId(), toSourceOptions(this, options));
  }

  /**
   * Fetches the latest blob properties. Returns {@code null} if the blob no longer exists.
   *
   * <p>{@code options} parameter can contain the preconditions. For example, the user might want to
   * get the blob properties only if the content has not been updated externally. {@code
   * StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example of retrieving the blob's latest information only if the content is not updated
   * externally:
   *
   * <pre>{@code
   * Blob blob = storage.get(BlobId.of(bucketName, blobName));
   *
   * doSomething();
   *
   * try {
   *   blob = blob.reload(Blob.BlobSourceOption.generationMatch());
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the content was updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param options preconditions to use on reload, see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
   *     for more information.
   * @return a {@code Blob} object with latest information or {@code null} if no longer exists.
   * @throws StorageException upon failure
   */
  public Blob reload(BlobSourceOption... options) {
    // BlobId with generation unset is needed to retrieve the latest version of the Blob
    BlobId idWithoutGeneration = BlobId.of(getBucket(), getName());
    return storage.get(idWithoutGeneration, toGetOptions(this, options));
  }

  /**
   * Updates the blob properties. The {@code options} parameter contains the preconditions for
   * applying the update. To update the properties call {@link #toBuilder()}, set the properties you
   * want to change, build the new {@code Blob} instance, and then call {@link
   * #update(BlobTargetOption...)}.
   *
   * <p>The property update details are described in {@link Storage#update(BlobInfo)}. {@link
   * Storage#update(BlobInfo, BlobTargetOption...)} describes how to specify preconditions.
   *
   * <p>Example of updating the content type:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * Blob blob = storage.get(blobId);
   * blob.toBuilder().setContentType("text/plain").build().update();
   * }</pre>
   *
   * @param options preconditions to apply the update
   * @return the updated {@code Blob}
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  public Blob update(BlobTargetOption... options) {
    return storage.update(this, options);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example of deleting the blob, if its generation matches the {@link Blob#getGeneration()}
   * value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.delete(BlobSourceOption.generationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param options blob delete options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean delete(BlobSourceOption... options) {
    return storage.delete(getBlobId(), toSourceOptions(this, options));
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some of the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyTo(BlobId.of(bucketName, blobName));
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param targetBlob target blob's id
   * @param options source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyTo(BlobId targetBlob, BlobSourceOption... options) {
    CopyRequest copyRequest =
        CopyRequest.newBuilder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toSourceOptions(this, options))
            .setTarget(targetBlob)
            .build();
    return storage.copy(copyRequest);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some of the metadata (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.copyTo(bucketName);
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param targetBucket target bucket's name
   * @param options source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyTo(String targetBucket, BlobSourceOption... options) {
    return copyTo(targetBucket, getName(), options);
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some of the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyTo(bucketName, blobName);
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example of moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.copyTo(destBucket, destBlob);
   * Blob copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.delete();
   * }</pre>
   *
   * @param targetBucket target bucket's name
   * @param targetBlob target blob's name
   * @param options source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyTo(String targetBucket, String targetBlob, BlobSourceOption... options) {
    return copyTo(BlobId.of(targetBucket, targetBlob), options);
  }

  /**
   * Returns a {@code ReadChannel} object for reading this blob's content.
   *
   * <p>Example of reading the blob's content through a reader.
   *
   * <pre>{@code
   * try (ReadChannel reader = blob.reader()) {
   *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
   *   while (reader.read(bytes) > 0) {
   *     bytes.flip();
   *     // do something with bytes
   *     bytes.clear();
   *   }
   * }
   * }</pre>
   *
   * <p>Example of reading just a portion of the blob's content.
   *
   * <pre>{@code
   * int start = 1;
   * int end = 8;
   * try (ReadChannel reader = blob.reader()) {
   *   reader.seek(start);
   *   ByteBuffer bytes = ByteBuffer.allocate(end - start);
   *   reader.read(bytes);
   *   return bytes.array();
   * }
   * }</pre>
   *
   * @param options blob read options
   * @throws StorageException upon failure
   */
  public ReadChannel reader(BlobSourceOption... options) {
    return storage.reader(getBlobId(), toSourceOptions(this, options));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options.
   *
   * <p>Example of writing the blob's content through a writer.
   *
   * <pre>{@code
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * try (WriteChannel writer = blob.writer()) {
   *     writer.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * blob = blob.reload();
   * }</pre>
   *
   * @param options target blob options
   * @throws StorageException upon failure
   */
  public WriteChannel writer(BlobWriteOption... options) {
    return storage.writer(this, options);
  }

  /**
   * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
   * this blob, you can use this method to generate a URL that is only valid within a certain time
   * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
   * want to require users to explicitly log in. Signing a URL requires a service account signer. If
   * an instance of {@link com.google.auth.ServiceAccountSigner} was passed to {@link
   * StorageOptions}' builder via {@code setCredentials(Credentials)} or the default credentials are
   * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
   * application is running in App Engine, then {@code signUrl} will use that credentials to sign
   * the URL. If the credentials passed to {@link StorageOptions} do not implement {@link
   * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
   * Google Cloud SDK credentials) then {@code signUrl} will throw an {@link IllegalStateException}
   * unless an implementation of {@link ServiceAccountSigner} is passed using the {@link
   * SignUrlOption#signWith(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link SignUrlOption#signWith(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example of creating a signed URL for the blob that is valid for 2 weeks, using the default
   * credentials for signing the URL:
   *
   * <pre>{@code
   * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example of creating a signed URL for the blob passing the {@link
   * SignUrlOption#signWith(ServiceAccountSigner)} option, that will be used to sign the URL:
   *
   * <pre>{@code
   * String keyPath = "/path/to/key.json";
   * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS, SignUrlOption.signWith(
   *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
   * }</pre>
   *
   * <p>Example of creating a signed URL for a blob generation:
   *
   * <pre>{@code
   * URL signedUrl = blob.signUrl(1, TimeUnit.HOURS,
   *     SignUrlOption.withQueryParams(ImmutableMap.of("generation", "1576656755290328")));
   * }</pre>
   *
   * @param duration time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param unit time unit of the {@code duration} parameter
   * @param options optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link SignUrlOption#signWith(ServiceAccountSigner)} was not
   *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL signUrl(long duration, TimeUnit unit, SignUrlOption... options) {
    return storage.signUrl(this, duration, unit, options);
  }

  /**
   * Returns the ACL entry for the specified entity on this blob or {@code null} if not found.
   *
   * <p>Example of getting the ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = blob.getAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl getAcl(Entity entity) {
    return storage.getAcl(getBlobId(), entity);
  }

  /**
   * Deletes the ACL entry for the specified entity on this blob.
   *
   * <p>Example of deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = blob.deleteAcl(User.ofAllAuthenticatedUsers());
   * if (deleted) {
   *   // the acl entry was deleted
   * } else {
   *   // the acl entry was not found
   * }
   * }</pre>
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean deleteAcl(Entity entity) {
    return storage.deleteAcl(getBlobId(), entity);
  }

  /**
   * Creates a new ACL entry on this blob.
   *
   * <p>Example of creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.createAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createAcl(Acl acl) {
    return storage.createAcl(getBlobId(), acl);
  }

  /**
   * Updates an ACL entry on this blob.
   *
   * <p>Example of updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.updateAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl updateAcl(Acl acl) {
    return storage.updateAcl(getBlobId(), acl);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example of listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = blob.listAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> listAcls() {
    return storage.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return storage;
  }

  @Override
  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * Returns true if obj instance {@code Blob.toPb()} metadata representation and {@code
   * Blob.options} instance of StorageOptions are both equal.
   */
  @Override
  public final boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null || !obj.getClass().equals(Blob.class)) {
      return false;
    }
    Blob other = (Blob) obj;
    return Objects.equals(toPb(), other.toPb()) && Objects.equals(options, other.options);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), options);
  }

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    this.storage = options.getService();
  }

  static Blob fromPb(Storage storage, StorageObject storageObject) {
    BlobInfo info = BlobInfo.fromPb(storageObject);
    return new Blob(storage, new BlobInfo.BuilderImpl(info));
  }
}
