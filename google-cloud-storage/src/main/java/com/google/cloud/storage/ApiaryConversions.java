/*
 * Copyright 2022 Google LLC
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

import static com.google.cloud.storage.Utils.durationMillisCodec;
import static com.google.cloud.storage.Utils.ifNonNull;
import static com.google.cloud.storage.Utils.lift;
import static com.google.cloud.storage.Utils.toImmutableListOf;
import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.api.client.util.Data;
import com.google.api.client.util.DateTime;
import com.google.api.core.InternalApi;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Billing;
import com.google.api.services.storage.model.Bucket.Encryption;
import com.google.api.services.storage.model.Bucket.IamConfiguration.UniformBucketLevelAccess;
import com.google.api.services.storage.model.Bucket.Lifecycle;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule.Action;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule.Condition;
import com.google.api.services.storage.model.Bucket.RetentionPolicy;
import com.google.api.services.storage.model.Bucket.Versioning;
import com.google.api.services.storage.model.Bucket.Website;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.StorageObject.Owner;
import com.google.cloud.storage.Acl.Domain;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Acl.Group;
import com.google.cloud.storage.Acl.Project;
import com.google.cloud.storage.Acl.RawEntity;
import com.google.cloud.storage.Acl.Role;
import com.google.cloud.storage.Acl.User;
import com.google.cloud.storage.BlobInfo.CustomerEncryption;
import com.google.cloud.storage.BucketInfo.AgeDeleteRule;
import com.google.cloud.storage.BucketInfo.BuilderImpl;
import com.google.cloud.storage.BucketInfo.CreatedBeforeDeleteRule;
import com.google.cloud.storage.BucketInfo.IamConfiguration;
import com.google.cloud.storage.BucketInfo.IsLiveDeleteRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.LifecycleRule.DeleteLifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleAction;
import com.google.cloud.storage.BucketInfo.LifecycleRule.LifecycleCondition;
import com.google.cloud.storage.BucketInfo.LifecycleRule.SetStorageClassLifecycleAction;
import com.google.cloud.storage.BucketInfo.Logging;
import com.google.cloud.storage.BucketInfo.NumNewerVersionsDeleteRule;
import com.google.cloud.storage.BucketInfo.PublicAccessPrevention;
import com.google.cloud.storage.BucketInfo.RawDeleteRule;
import com.google.cloud.storage.Conversions.Codec;
import com.google.cloud.storage.Cors.Origin;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.HmacKey.HmacKeyState;
import com.google.cloud.storage.NotificationInfo.EventType;
import com.google.cloud.storage.NotificationInfo.PayloadFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

@InternalApi
final class ApiaryConversions {
  static final ApiaryConversions INSTANCE = new ApiaryConversions();

  private final Codec<Entity, String> entityCodec =
      Codec.of(this::entityEncode, this::entityDecode);
  private final Codec<Acl, ObjectAccessControl> objectAclCodec =
      Codec.of(this::objectAclEncode, this::objectAclDecode);
  private final Codec<Acl, BucketAccessControl> bucketAclCodec =
      Codec.of(this::bucketAclEncode, this::bucketAclDecode);
  private final Codec<HmacKeyMetadata, com.google.api.services.storage.model.HmacKeyMetadata>
      hmacKeyMetadataCodec = Codec.of(this::hmacKeyMetadataEncode, this::hmacKeyMetadataDecode);
  private final Codec<HmacKey, com.google.api.services.storage.model.HmacKey> hmacKeyCodec =
      Codec.of(this::hmacKeyEncode, this::hmacKeyDecode);
  private final Codec<ServiceAccount, com.google.api.services.storage.model.ServiceAccount>
      serviceAccountCodec = Codec.of(this::serviceAccountEncode, this::serviceAccountDecode);
  private final Codec<Cors, Bucket.Cors> corsCodec = Codec.of(this::corsEncode, this::corsDecode);
  private final Codec<Logging, Bucket.Logging> loggingCodec =
      Codec.of(this::loggingEncode, this::loggingDecode);
  private final Codec<IamConfiguration, Bucket.IamConfiguration> iamConfigurationCodec =
      Codec.of(this::iamConfigEncode, this::iamConfigDecode);
  private final Codec<LifecycleRule, Rule> lifecycleRuleCodec =
      Codec.of(this::lifecycleRuleEncode, this::lifecycleRuleDecode);

  @SuppressWarnings("deprecation")
  private final Codec<BucketInfo.DeleteRule, Rule> deleteRuleCodec =
      Codec.of(this::deleteRuleEncode, this::deleteRuleDecode);

  private final Codec<BucketInfo, Bucket> bucketInfoCodec =
      Codec.of(this::bucketInfoEncode, this::bucketInfoDecode);
  private final Codec<CustomerEncryption, StorageObject.CustomerEncryption>
      customerEncryptionCodec =
          Codec.of(this::customerEncryptionEncode, this::customerEncryptionDecode);
  private final Codec<BlobId, StorageObject> blobIdCodec =
      Codec.of(this::blobIdEncode, this::blobIdDecode);
  private final Codec<BlobInfo, StorageObject> blobInfoCodec =
      Codec.of(this::blobInfoEncode, this::blobInfoDecode);

  private final Codec<NotificationInfo, com.google.api.services.storage.model.Notification>
      notificationInfoCodec = Codec.of(this::notificationEncode, this::notificationDecode);

  @VisibleForTesting
  final Codec<OffsetDateTime, DateTime> dateTimeCodec =
      Codec.of(
          odt -> {
            ZoneOffset offset = odt.getOffset();
            int i = Math.toIntExact(TimeUnit.SECONDS.toMinutes(offset.getTotalSeconds()));
            return new DateTime(odt.toInstant().toEpochMilli(), i);
          },
          dt -> {
            long milli = dt.getValue();
            int timeZoneShiftMinutes = dt.getTimeZoneShift();

            Duration timeZoneShift = Duration.of(timeZoneShiftMinutes, ChronoUnit.MINUTES);

            int hours = Math.toIntExact(timeZoneShift.toHours());
            int minutes =
                Math.toIntExact(
                    timeZoneShift.minusHours(timeZoneShift.toHours()).getSeconds() / 60);
            ZoneOffset offset = ZoneOffset.ofHoursMinutes(hours, minutes);

            return Instant.ofEpochMilli(milli).atOffset(offset);
          });

  private ApiaryConversions() {}

  Codec<Entity, String> entity() {
    return entityCodec;
  }

  Codec<Acl, ObjectAccessControl> objectAcl() {
    return objectAclCodec;
  }

  Codec<Acl, BucketAccessControl> bucketAcl() {
    return bucketAclCodec;
  }

  Codec<HmacKeyMetadata, com.google.api.services.storage.model.HmacKeyMetadata> hmacKeyMetadata() {
    return hmacKeyMetadataCodec;
  }

  Codec<HmacKey, com.google.api.services.storage.model.HmacKey> hmacKey() {
    return hmacKeyCodec;
  }

  Codec<ServiceAccount, com.google.api.services.storage.model.ServiceAccount> serviceAccount() {
    return serviceAccountCodec;
  }

  Codec<Cors, Bucket.Cors> cors() {
    return corsCodec;
  }

  Codec<Logging, Bucket.Logging> logging() {
    return loggingCodec;
  }

  Codec<IamConfiguration, Bucket.IamConfiguration> iamConfiguration() {
    return iamConfigurationCodec;
  }

  Codec<LifecycleRule, Rule> lifecycleRule() {
    return lifecycleRuleCodec;
  }

  @SuppressWarnings("deprecation")
  Codec<BucketInfo.DeleteRule, Rule> deleteRule() {
    return deleteRuleCodec;
  }

  Codec<BucketInfo, com.google.api.services.storage.model.Bucket> bucketInfo() {
    return bucketInfoCodec;
  }

  Codec<CustomerEncryption, StorageObject.CustomerEncryption> customerEncryption() {
    return customerEncryptionCodec;
  }

  Codec<BlobId, StorageObject> blobId() {
    return blobIdCodec;
  }

  Codec<BlobInfo, StorageObject> blobInfo() {
    return blobInfoCodec;
  }

  Codec<NotificationInfo, com.google.api.services.storage.model.Notification> notificationInfo() {
    return notificationInfoCodec;
  }

  private StorageObject blobInfoEncode(BlobInfo from) {
    StorageObject to = blobIdEncode(from.getBlobId());
    ifNonNull(from.getAcl(), toImmutableListOf(objectAcl()::encode), to::setAcl);
    ifNonNull(from.getDeleteTimeOffsetDateTime(), dateTimeCodec::encode, to::setTimeDeleted);
    ifNonNull(from.getUpdateTimeOffsetDateTime(), dateTimeCodec::encode, to::setUpdated);
    ifNonNull(from.getCreateTimeOffsetDateTime(), dateTimeCodec::encode, to::setTimeCreated);
    ifNonNull(from.getCustomTimeOffsetDateTime(), dateTimeCodec::encode, to::setCustomTime);
    ifNonNull(from.getSize(), BigInteger::valueOf, to::setSize);
    ifNonNull(
        from.getOwner(),
        lift(this::entityEncode).andThen(o -> new Owner().setEntity(o)),
        to::setOwner);
    ifNonNull(from.getStorageClass(), StorageClass::toString, to::setStorageClass);
    ifNonNull(
        from.getTimeStorageClassUpdatedOffsetDateTime(),
        dateTimeCodec::encode,
        to::setTimeStorageClassUpdated);
    ifNonNull(
        from.getCustomerEncryption(), this::customerEncryptionEncode, to::setCustomerEncryption);
    ifNonNull(
        from.getRetentionExpirationTimeOffsetDateTime(),
        dateTimeCodec::encode,
        to::setRetentionExpirationTime);
    to.setKmsKeyName(from.getKmsKeyName());
    to.setEventBasedHold(from.getEventBasedHold());
    to.setTemporaryHold(from.getTemporaryHold());
    // Do not use, #getMetadata(), it can not return null, which is important to our logic here
    Map<String, String> pbMetadata = from.metadata;
    if (pbMetadata != null && !Data.isNull(pbMetadata)) {
      pbMetadata = Maps.newHashMapWithExpectedSize(from.getMetadata().size());
      for (Map.Entry<String, String> entry : from.getMetadata().entrySet()) {
        pbMetadata.put(entry.getKey(), firstNonNull(entry.getValue(), Data.nullOf(String.class)));
      }
    }
    to.setMetadata(pbMetadata);
    to.setCacheControl(from.getCacheControl());
    to.setContentEncoding(from.getContentEncoding());
    to.setCrc32c(from.getCrc32c());
    to.setContentType(from.getContentType());
    to.setMd5Hash(from.getMd5());
    to.setMediaLink(from.getMediaLink());
    to.setMetageneration(from.getMetageneration());
    to.setContentDisposition(from.getContentDisposition());
    to.setComponentCount(from.getComponentCount());
    to.setContentLanguage(from.getContentLanguage());
    to.setEtag(from.getEtag());
    to.setId(from.getGeneratedId());
    to.setSelfLink(from.getSelfLink());
    return to;
  }

  private BlobInfo blobInfoDecode(StorageObject from) {
    BlobInfo.Builder to = BlobInfo.newBuilder(blobIdDecode(from));
    ifNonNull(from.getCacheControl(), to::setCacheControl);
    ifNonNull(from.getContentEncoding(), to::setContentEncoding);
    ifNonNull(from.getCrc32c(), to::setCrc32c);
    ifNonNull(from.getContentType(), to::setContentType);
    ifNonNull(from.getMd5Hash(), to::setMd5);
    ifNonNull(from.getMediaLink(), to::setMediaLink);
    ifNonNull(from.getMetageneration(), to::setMetageneration);
    ifNonNull(from.getContentDisposition(), to::setContentDisposition);
    ifNonNull(from.getComponentCount(), to::setComponentCount);
    ifNonNull(from.getContentLanguage(), to::setContentLanguage);
    ifNonNull(from.getEtag(), to::setEtag);
    ifNonNull(from.getId(), to::setGeneratedId);
    ifNonNull(from.getSelfLink(), to::setSelfLink);
    ifNonNull(from.getMetadata(), to::setMetadata);
    ifNonNull(from.getTimeDeleted(), dateTimeCodec::decode, to::setDeleteTimeOffsetDateTime);
    ifNonNull(from.getUpdated(), dateTimeCodec::decode, to::setUpdateTimeOffsetDateTime);
    ifNonNull(from.getTimeCreated(), dateTimeCodec::decode, to::setCreateTimeOffsetDateTime);
    ifNonNull(from.getCustomTime(), dateTimeCodec::decode, to::setCustomTimeOffsetDateTime);
    ifNonNull(from.getSize(), BigInteger::longValue, to::setSize);
    ifNonNull(from.getOwner(), lift(Owner::getEntity).andThen(this::entityDecode), to::setOwner);
    ifNonNull(from.getAcl(), toImmutableListOf(objectAcl()::decode), to::setAcl);
    if (from.containsKey("isDirectory")) {
      to.setIsDirectory(Boolean.TRUE);
    }
    ifNonNull(
        from.getCustomerEncryption(), this::customerEncryptionDecode, to::setCustomerEncryption);
    ifNonNull(from.getStorageClass(), StorageClass::valueOf, to::setStorageClass);
    ifNonNull(
        from.getTimeStorageClassUpdated(),
        dateTimeCodec::decode,
        to::setTimeStorageClassUpdatedOffsetDateTime);
    ifNonNull(from.getKmsKeyName(), to::setKmsKeyName);
    ifNonNull(from.getEventBasedHold(), to::setEventBasedHold);
    ifNonNull(from.getTemporaryHold(), to::setTemporaryHold);
    ifNonNull(
        from.getRetentionExpirationTime(),
        dateTimeCodec::decode,
        to::setRetentionExpirationTimeOffsetDateTime);
    return to.build();
  }

  private StorageObject blobIdEncode(BlobId from) {
    StorageObject to = new StorageObject();
    to.setBucket(from.getBucket());
    to.setName(from.getName());
    to.setGeneration(from.getGeneration());
    return to;
  }

  private BlobId blobIdDecode(StorageObject from) {
    return BlobId.of(from.getBucket(), from.getName(), from.getGeneration());
  }

  private StorageObject.CustomerEncryption customerEncryptionEncode(CustomerEncryption from) {
    return new StorageObject.CustomerEncryption()
        .setEncryptionAlgorithm(from.getEncryptionAlgorithm())
        .setKeySha256(from.getKeySha256());
  }

  private CustomerEncryption customerEncryptionDecode(StorageObject.CustomerEncryption from) {
    return new CustomerEncryption(from.getEncryptionAlgorithm(), from.getKeySha256());
  }

  private Bucket bucketInfoEncode(BucketInfo from) {
    Bucket to = new Bucket();
    ifNonNull(from.getAcl(), toImmutableListOf(bucketAcl()::encode), to::setAcl);
    ifNonNull(from.getCors(), toImmutableListOf(cors()::encode), to::setCors);
    ifNonNull(from.getCreateTimeOffsetDateTime(), dateTimeCodec::encode, to::setTimeCreated);
    ifNonNull(
        from.getDefaultAcl(), toImmutableListOf(objectAcl()::encode), to::setDefaultObjectAcl);
    ifNonNull(from.getLocation(), to::setLocation);
    ifNonNull(from.getLocationType(), to::setLocationType);
    ifNonNull(from.getMetageneration(), to::setMetageneration);
    ifNonNull(
        from.getOwner(),
        lift(this::entityEncode).andThen(o -> new Bucket.Owner().setEntity(o)),
        to::setOwner);
    ifNonNull(from.getRpo(), Rpo::toString, to::setRpo);
    ifNonNull(from.getStorageClass(), StorageClass::toString, to::setStorageClass);
    ifNonNull(from.getUpdateTimeOffsetDateTime(), dateTimeCodec::encode, to::setUpdated);
    ifNonNull(from.versioningEnabled(), b -> new Versioning().setEnabled(b), to::setVersioning);
    to.setEtag(from.getEtag());
    to.setId(from.getGeneratedId());
    to.setName(from.getName());
    to.setSelfLink(from.getSelfLink());

    ifNonNull(from.requesterPays(), b -> new Bucket.Billing().setRequesterPays(b), to::setBilling);
    if (from.getIndexPage() != null || from.getNotFoundPage() != null) {
      Website website = new Website();
      website.setMainPageSuffix(from.getIndexPage());
      website.setNotFoundPage(from.getNotFoundPage());
      to.setWebsite(website);
    }

    @SuppressWarnings("deprecation")
    List<? extends BucketInfo.DeleteRule> deleteRules = from.getDeleteRules();
    // Do not use, #getLifecycleRules, it can not return null, which is important to our logic here
    List<? extends LifecycleRule> lifecycleRules = from.lifecycleRules;
    if (deleteRules != null || lifecycleRules != null) {
      Lifecycle lifecycle = new Lifecycle();

      // Here we determine if we need to "clear" any defined Lifecycle rules by explicitly setting
      // the Rule list of lifecycle to the empty list.
      // In order for us to clear the rules, one of the three following must be true:
      //   1. deleteRules is null while lifecycleRules is non-null and empty
      //   2. lifecycleRules is null while deleteRules is non-null and empty
      //   3. lifecycleRules is non-null and empty while deleteRules is non-null and empty
      // If none of the above three is true, we will interpret as the Lifecycle rules being
      // updated to the defined set of DeleteRule and LifecycleRule.
      if ((deleteRules == null && lifecycleRules.isEmpty())
          || (lifecycleRules == null && deleteRules.isEmpty())
          || (deleteRules != null && deleteRules.isEmpty() && lifecycleRules.isEmpty())) {
        lifecycle.setRule(Collections.emptyList());
      } else {
        Set<Rule> rules = new HashSet<>();
        ifNonNull(deleteRules, r -> r.stream().map(deleteRule()::encode).forEach(rules::add));
        ifNonNull(lifecycleRules, r -> r.stream().map(lifecycleRule()::encode).forEach(rules::add));
        if (!rules.isEmpty()) {
          lifecycle.setRule(ImmutableList.copyOf(rules));
        }
      }

      to.setLifecycle(lifecycle);
    }

    ifNonNull(from.getDefaultEventBasedHold(), to::setDefaultEventBasedHold);
    ifNonNull(
        from.getDefaultKmsKeyName(),
        k -> new Encryption().setDefaultKmsKeyName(k),
        to::setEncryption);
    ifNonNull(from.getLabels(), to::setLabels);
    Duration retentionPeriod = from.getRetentionPeriodDuration();
    if (retentionPeriod == null) {
      to.setRetentionPolicy(Data.nullOf(Bucket.RetentionPolicy.class));
    } else {
      Bucket.RetentionPolicy retentionPolicy = new Bucket.RetentionPolicy();
      retentionPolicy.setRetentionPeriod(durationMillisCodec.encode(retentionPeriod));
      ifNonNull(
          from.getRetentionEffectiveTimeOffsetDateTime(),
          dateTimeCodec::encode,
          retentionPolicy::setEffectiveTime);
      ifNonNull(from.retentionPolicyIsLocked(), retentionPolicy::setIsLocked);
      to.setRetentionPolicy(retentionPolicy);
    }
    ifNonNull(from.getIamConfiguration(), this::iamConfigEncode, to::setIamConfiguration);
    ifNonNull(from.getLogging(), this::loggingEncode, to::setLogging);
    return to;
  }

  @SuppressWarnings("deprecation")
  private BucketInfo bucketInfoDecode(com.google.api.services.storage.model.Bucket from) {
    BucketInfo.Builder to = new BuilderImpl(from.getName());
    ifNonNull(from.getAcl(), toImmutableListOf(bucketAcl()::decode), to::setAcl);
    ifNonNull(from.getCors(), toImmutableListOf(cors()::decode), to::setCors);
    ifNonNull(
        from.getDefaultObjectAcl(), toImmutableListOf(objectAcl()::decode), to::setDefaultAcl);
    ifNonNull(from.getEtag(), to::setEtag);
    ifNonNull(from.getId(), to::setGeneratedId);
    ifNonNull(from.getLocation(), to::setLocation);
    ifNonNull(from.getLocationType(), to::setLocationType);
    ifNonNull(from.getMetageneration(), to::setMetageneration);
    ifNonNull(
        from.getOwner(), lift(Bucket.Owner::getEntity).andThen(this::entityDecode), to::setOwner);
    ifNonNull(from.getRpo(), Rpo::valueOf, to::setRpo);
    ifNonNull(from.getSelfLink(), to::setSelfLink);
    ifNonNull(from.getStorageClass(), StorageClass::valueOf, to::setStorageClass);
    ifNonNull(from.getTimeCreated(), dateTimeCodec::decode, to::setCreateTimeOffsetDateTime);
    ifNonNull(from.getUpdated(), dateTimeCodec::decode, to::setUpdateTimeOffsetDateTime);
    ifNonNull(from.getVersioning(), Versioning::getEnabled, to::setVersioningEnabled);
    ifNonNull(from.getWebsite(), Website::getMainPageSuffix, to::setIndexPage);
    ifNonNull(from.getWebsite(), Website::getNotFoundPage, to::setNotFoundPage);
    ifNonNull(
        from.getLifecycle(),
        lift(Lifecycle::getRule).andThen(toImmutableListOf(lifecycleRule()::decode)),
        to::setLifecycleRules);
    // preserve mapping to deprecated property
    ifNonNull(
        from.getLifecycle(),
        lift(Lifecycle::getRule).andThen(toImmutableListOf(deleteRule()::decode)),
        to::setDeleteRules);
    ifNonNull(from.getDefaultEventBasedHold(), to::setDefaultEventBasedHold);
    ifNonNull(from.getLabels(), to::setLabels);
    ifNonNull(from.getBilling(), Billing::getRequesterPays, to::setRequesterPays);
    Encryption encryption = from.getEncryption();
    if (encryption != null
        && encryption.getDefaultKmsKeyName() != null
        && !encryption.getDefaultKmsKeyName().isEmpty()) {
      to.setDefaultKmsKeyName(encryption.getDefaultKmsKeyName());
    }

    RetentionPolicy retentionPolicy = from.getRetentionPolicy();
    if (retentionPolicy != null && retentionPolicy.getEffectiveTime() != null) {
      to.setRetentionEffectiveTimeOffsetDateTime(
          dateTimeCodec.decode(retentionPolicy.getEffectiveTime()));
    }
    ifNonNull(retentionPolicy, RetentionPolicy::getIsLocked, to::setRetentionPolicyIsLocked);
    ifNonNull(retentionPolicy, RetentionPolicy::getRetentionPeriod, to::setRetentionPeriod);
    ifNonNull(from.getIamConfiguration(), this::iamConfigDecode, to::setIamConfiguration);
    ifNonNull(from.getLogging(), this::loggingDecode, to::setLogging);

    return to.build();
  }

  @SuppressWarnings("deprecation")
  private Rule deleteRuleEncode(BucketInfo.DeleteRule from) {
    if (from instanceof RawDeleteRule) {
      RawDeleteRule raw = (RawDeleteRule) from;
      Rule rule = raw.getRule();
      String msg =
          "The lifecycle condition "
              + resolveRuleActionType(rule)
              + " is not currently supported. Please update to the latest version of google-cloud-java."
              + " Also, use LifecycleRule rather than the deprecated DeleteRule.";
      // manually construct a log record, so we maintain class name and method name
      // from the old implicit values.
      LogRecord record = new LogRecord(Level.WARNING, msg);
      record.setSourceClassName(BucketInfo.RawDeleteRule.class.getName());
      record.setSourceMethodName("populateCondition");
      BucketInfo.log.log(record);
      return rule;
    }
    Rule to = new Rule();
    to.setAction(new Rule.Action().setType(BucketInfo.DeleteRule.SUPPORTED_ACTION));
    Rule.Condition condition = new Rule.Condition();
    if (from instanceof CreatedBeforeDeleteRule) {
      CreatedBeforeDeleteRule r = (CreatedBeforeDeleteRule) from;
      ifNonNull(
          r.getTime(),
          lift(dateTimeCodec::encode).andThen(this::truncateToDateWithNoTzDrift),
          condition::setCreatedBefore);
    } else if (from instanceof AgeDeleteRule) {
      AgeDeleteRule r = (AgeDeleteRule) from;
      condition.setAge(r.getDaysToLive());
    } else if (from instanceof NumNewerVersionsDeleteRule) {
      NumNewerVersionsDeleteRule r = (NumNewerVersionsDeleteRule) from;
      condition.setNumNewerVersions(r.getNumNewerVersions());
    } else if (from instanceof IsLiveDeleteRule) {
      IsLiveDeleteRule r = (IsLiveDeleteRule) from;
      condition.setIsLive(r.isLive());
    } // else would be RawDeleteRule which is handled above
    to.setCondition(condition);
    return to;
  }

  @SuppressWarnings("deprecation")
  private BucketInfo.DeleteRule deleteRuleDecode(Rule from) {
    if (from.getAction() != null
        && BucketInfo.DeleteRule.SUPPORTED_ACTION.endsWith(resolveRuleActionType(from))) {
      Rule.Condition condition = from.getCondition();
      Integer age = condition.getAge();
      if (age != null) {
        return new BucketInfo.AgeDeleteRule(age);
      }
      DateTime dateTime = condition.getCreatedBefore();
      if (dateTime != null) {
        return new BucketInfo.CreatedBeforeDeleteRule(dateTimeCodec.decode(dateTime));
      }
      Integer numNewerVersions = condition.getNumNewerVersions();
      if (numNewerVersions != null) {
        return new BucketInfo.NumNewerVersionsDeleteRule(numNewerVersions);
      }
      Boolean isLive = condition.getIsLive();
      if (isLive != null) {
        return new BucketInfo.IsLiveDeleteRule(isLive);
      }
    }
    return new RawDeleteRule(from);
  }

  private Bucket.IamConfiguration iamConfigEncode(IamConfiguration from) {
    Bucket.IamConfiguration to = new Bucket.IamConfiguration();
    to.setUniformBucketLevelAccess(ublaEncode(from));
    ifNonNull(
        from.getPublicAccessPrevention(),
        PublicAccessPrevention::getValue,
        to::setPublicAccessPrevention);
    return to;
  }

  private IamConfiguration iamConfigDecode(Bucket.IamConfiguration from) {
    Bucket.IamConfiguration.UniformBucketLevelAccess ubla = from.getUniformBucketLevelAccess();

    IamConfiguration.Builder to =
        IamConfiguration.newBuilder().setIsUniformBucketLevelAccessEnabled(ubla.getEnabled());
    ifNonNull(
        ubla.getLockedTime(),
        dateTimeCodec::decode,
        to::setUniformBucketLevelAccessLockedTimeOffsetDateTime);
    ifNonNull(
        from.getPublicAccessPrevention(),
        PublicAccessPrevention::parse,
        to::setPublicAccessPrevention);
    return to.build();
  }

  private UniformBucketLevelAccess ublaEncode(IamConfiguration from) {
    UniformBucketLevelAccess to = new UniformBucketLevelAccess();
    to.setEnabled(from.isUniformBucketLevelAccessEnabled());
    ifNonNull(
        from.getUniformBucketLevelAccessLockedTimeOffsetDateTime(),
        dateTimeCodec::encode,
        to::setLockedTime);
    return to;
  }

  private Rule lifecycleRuleEncode(LifecycleRule from) {
    Rule to = new Rule();
    to.setAction(ruleActionEncode(from.getLifecycleAction()));
    to.setCondition(ruleConditionEncode(from.getLifecycleCondition()));
    return to;
  }

  private Condition ruleConditionEncode(LifecycleCondition from) {
    Condition to =
        new Condition()
            .setAge(from.getAge())
            .setIsLive(from.getIsLive())
            .setNumNewerVersions(from.getNumberOfNewerVersions())
            .setDaysSinceNoncurrentTime(from.getDaysSinceNoncurrentTime())
            .setDaysSinceCustomTime(from.getDaysSinceCustomTime());
    ifNonNull(from.getCreatedBefore(), this::truncateToDateWithNoTzDrift, to::setCreatedBefore);
    ifNonNull(
        from.getNoncurrentTimeBefore(),
        this::truncateToDateWithNoTzDrift,
        to::setNoncurrentTimeBefore);
    ifNonNull(
        from.getCustomTimeBefore(), this::truncateToDateWithNoTzDrift, to::setCustomTimeBefore);
    ifNonNull(
        from.getMatchesStorageClass(),
        toImmutableListOf(Object::toString),
        to::setMatchesStorageClass);
    return to;
  }

  private Action ruleActionEncode(LifecycleAction from) {
    Action to = new Action().setType(from.getActionType());
    if (from.getActionType().equals(SetStorageClassLifecycleAction.TYPE)) {
      to.setStorageClass(((SetStorageClassLifecycleAction) from).getStorageClass().toString());
    }
    return to;
  }

  private LifecycleRule lifecycleRuleDecode(Rule from) {
    LifecycleAction lifecycleAction;

    Rule.Action action = from.getAction();

    switch (action.getType()) {
      case DeleteLifecycleAction.TYPE:
        lifecycleAction = LifecycleAction.newDeleteAction();
        break;
      case SetStorageClassLifecycleAction.TYPE:
        lifecycleAction =
            LifecycleAction.newSetStorageClassAction(
                StorageClass.valueOf(action.getStorageClass()));
        break;
      default:
        BucketInfo.log.warning(
            "The lifecycle action "
                + action.getType()
                + " is not supported by this version of the library. "
                + "Attempting to update with this rule may cause errors. Please "
                + "update to the latest version of google-cloud-storage.");
        lifecycleAction = LifecycleAction.newLifecycleAction("Unknown action");
    }

    Rule.Condition condition = from.getCondition();

    LifecycleCondition.Builder conditionBuilder =
        LifecycleCondition.newBuilder()
            .setAge(condition.getAge())
            .setCreatedBefore(condition.getCreatedBefore())
            .setIsLive(condition.getIsLive())
            .setNumberOfNewerVersions(condition.getNumNewerVersions())
            .setDaysSinceNoncurrentTime(condition.getDaysSinceNoncurrentTime())
            .setNoncurrentTimeBefore(condition.getNoncurrentTimeBefore())
            .setCustomTimeBefore(condition.getCustomTimeBefore())
            .setDaysSinceCustomTime(condition.getDaysSinceCustomTime());
    ifNonNull(
        condition.getMatchesStorageClass(),
        toImmutableListOf(StorageClass::valueOf),
        conditionBuilder::setMatchesStorageClass);

    return new LifecycleRule(lifecycleAction, conditionBuilder.build());
  }

  private Bucket.Logging loggingEncode(Logging from) {
    Bucket.Logging to;
    if (from.getLogBucket() != null || from.getLogObjectPrefix() != null) {
      to = new Bucket.Logging();
      to.setLogBucket(from.getLogBucket());
      to.setLogObjectPrefix(from.getLogObjectPrefix());
    } else {
      to = Data.nullOf(Bucket.Logging.class);
    }
    return to;
  }

  private Logging loggingDecode(Bucket.Logging from) {
    return Logging.newBuilder()
        .setLogBucket(from.getLogBucket())
        .setLogObjectPrefix(from.getLogObjectPrefix())
        .build();
  }

  private Bucket.Cors corsEncode(Cors from) {
    Bucket.Cors to = new Bucket.Cors();
    to.setMaxAgeSeconds(from.getMaxAgeSeconds());
    to.setResponseHeader(from.getResponseHeaders());
    ifNonNull(from.getMethods(), toImmutableListOf(Object::toString), to::setMethod);
    ifNonNull(from.getOrigins(), toImmutableListOf(Object::toString), to::setOrigin);
    return to;
  }

  private Cors corsDecode(Bucket.Cors from) {
    Cors.Builder to = Cors.newBuilder().setMaxAgeSeconds(from.getMaxAgeSeconds());
    ifNonNull(
        from.getMethod(),
        m ->
            m.stream()
                .map(String::toUpperCase)
                .map(HttpMethod::valueOf)
                .collect(ImmutableList.toImmutableList()),
        to::setMethods);
    ifNonNull(from.getOrigin(), toImmutableListOf(Origin::of), to::setOrigins);
    to.setResponseHeaders(from.getResponseHeader());
    return to.build();
  }

  private com.google.api.services.storage.model.ServiceAccount serviceAccountEncode(
      ServiceAccount from) {
    return new com.google.api.services.storage.model.ServiceAccount()
        .setEmailAddress(from.getEmail());
  }

  private ServiceAccount serviceAccountDecode(
      com.google.api.services.storage.model.ServiceAccount from) {
    return ServiceAccount.of(from.getEmailAddress());
  }

  private com.google.api.services.storage.model.HmacKey hmacKeyEncode(HmacKey from) {
    com.google.api.services.storage.model.HmacKey to =
        new com.google.api.services.storage.model.HmacKey();
    to.setSecret(from.getSecretKey());
    ifNonNull(from.getMetadata(), this::hmacKeyMetadataEncode, to::setMetadata);
    return to;
  }

  private HmacKey hmacKeyDecode(com.google.api.services.storage.model.HmacKey from) {
    return HmacKey.newBuilder(from.getSecret())
        .setMetadata(hmacKeyMetadataDecode(from.getMetadata()))
        .build();
  }

  private com.google.api.services.storage.model.HmacKeyMetadata hmacKeyMetadataEncode(
      HmacKeyMetadata from) {
    com.google.api.services.storage.model.HmacKeyMetadata to =
        new com.google.api.services.storage.model.HmacKeyMetadata();
    to.setAccessId(from.getAccessId());
    to.setEtag(from.getEtag());
    to.setId(from.getId());
    to.setProjectId(from.getProjectId());
    ifNonNull(from.getServiceAccount(), ServiceAccount::getEmail, to::setServiceAccountEmail);
    ifNonNull(from.getState(), Object::toString, to::setState);
    ifNonNull(from.getCreateTimeOffsetDateTime(), dateTimeCodec::encode, to::setTimeCreated);
    ifNonNull(from.getUpdateTimeOffsetDateTime(), dateTimeCodec::encode, to::setUpdated);
    return to;
  }

  private HmacKeyMetadata hmacKeyMetadataDecode(
      com.google.api.services.storage.model.HmacKeyMetadata from) {
    return HmacKeyMetadata.newBuilder(ServiceAccount.of(from.getServiceAccountEmail()))
        .setAccessId(from.getAccessId())
        .setCreateTimeOffsetDateTime(dateTimeCodec.decode(from.getTimeCreated()))
        .setEtag(from.getEtag())
        .setId(from.getId())
        .setProjectId(from.getProjectId())
        .setState(HmacKeyState.valueOf(from.getState()))
        .setUpdateTimeOffsetDateTime(dateTimeCodec.decode(from.getUpdated()))
        .build();
  }

  private String entityEncode(Entity from) {
    if (from instanceof RawEntity) {
      return from.getValue();
    } else if (from instanceof User) {
      switch (from.getValue()) {
        case User.ALL_AUTHENTICATED_USERS:
          return User.ALL_AUTHENTICATED_USERS;
        case User.ALL_USERS:
          return User.ALL_USERS;
        default:
          break;
      }
    }

    // intentionally not an else so that if the default is hit above it will fall through to here
    return from.getType().name().toLowerCase() + "-" + from.getValue();
  }

  private Entity entityDecode(String from) {
    if (from.startsWith("user-")) {
      return new User(from.substring(5));
    }
    if (from.equals(User.ALL_USERS)) {
      return User.ofAllUsers();
    }
    if (from.equals(User.ALL_AUTHENTICATED_USERS)) {
      return User.ofAllAuthenticatedUsers();
    }
    if (from.startsWith("group-")) {
      return new Group(from.substring(6));
    }
    if (from.startsWith("domain-")) {
      return new Domain(from.substring(7));
    }
    if (from.startsWith("project-")) {
      int idx = from.indexOf('-', 8);
      String team = from.substring(8, idx);
      String projectId = from.substring(idx + 1);
      return new Project(Project.ProjectRole.valueOf(team.toUpperCase()), projectId);
    }
    return new RawEntity(from);
  }

  private Acl objectAclDecode(ObjectAccessControl from) {
    Role role = Role.valueOf(from.getRole());
    Entity entity = entityDecode(from.getEntity());
    return Acl.newBuilder(entity, role).setEtag(from.getEtag()).setId(from.getId()).build();
  }

  private Acl bucketAclDecode(BucketAccessControl from) {
    Role role = Role.valueOf(from.getRole());
    Entity entity = entityDecode(from.getEntity());
    return Acl.newBuilder(entity, role).setEtag(from.getEtag()).setId(from.getId()).build();
  }

  private BucketAccessControl bucketAclEncode(Acl from) {
    return new BucketAccessControl()
        .setEntity(from.getEntity().toString())
        .setRole(from.getRole().toString())
        .setId(from.getId())
        .setEtag(from.getEtag());
  }

  private ObjectAccessControl objectAclEncode(Acl from) {
    return new ObjectAccessControl()
        .setEntity(entityEncode(from.getEntity()))
        .setRole(from.getRole().name())
        .setId(from.getId())
        .setEtag(from.getEtag());
  }

  private com.google.api.services.storage.model.Notification notificationEncode(
      NotificationInfo from) {
    com.google.api.services.storage.model.Notification to =
        new com.google.api.services.storage.model.Notification();

    to.setEtag(from.getEtag());
    to.setSelfLink(from.getSelfLink());
    to.setTopic(from.getTopic());
    ifNonNull(from.getNotificationId(), to::setId);
    ifNonNull(from.getCustomAttributes(), to::setCustomAttributes);
    ifNonNull(from.getObjectNamePrefix(), to::setObjectNamePrefix);

    List<EventType> eventTypes = from.getEventTypes();
    if (eventTypes != null && eventTypes.size() > 0) {
      List<String> eventTypesPb = new ArrayList<>();
      for (EventType eventType : eventTypes) {
        eventTypesPb.add(eventType.toString());
      }
      to.setEventTypes(eventTypesPb);
    }

    PayloadFormat payloadFormat = from.getPayloadFormat();
    if (payloadFormat != null) {
      to.setPayloadFormat(payloadFormat.toString());
    } else {
      to.setPayloadFormat(PayloadFormat.NONE.toString());
    }
    return to;
  }

  private NotificationInfo notificationDecode(
      com.google.api.services.storage.model.Notification from) {
    NotificationInfo.Builder builder = new NotificationInfo.BuilderImpl(from.getTopic());
    ifNonNull(from.getId(), builder::setNotificationId);
    ifNonNull(from.getEtag(), builder::setEtag);
    ifNonNull(from.getCustomAttributes(), builder::setCustomAttributes);
    ifNonNull(from.getSelfLink(), builder::setSelfLink);
    ifNonNull(from.getObjectNamePrefix(), builder::setObjectNamePrefix);
    ifNonNull(from.getPayloadFormat(), PayloadFormat::valueOf, builder::setPayloadFormat);

    if (from.getEventTypes() != null) {
      List<String> eventTypesPb = from.getEventTypes();
      EventType[] eventTypes = new EventType[eventTypesPb.size()];
      for (int index = 0; index < eventTypesPb.size(); index++) {
        eventTypes[index] = EventType.valueOf(eventTypesPb.get(index));
      }
      builder.setEventTypes(eventTypes);
    }
    return builder.build();
  }

  private DateTime truncateToDateWithNoTzDrift(DateTime dt) {
    return new DateTime(true, dt.getValue(), 0);
  }

  private String resolveRuleActionType(Rule rule) {
    if (rule != null && rule.getAction() != null) {
      return rule.getAction().getType();
    } else {
      return null;
    }
  }
}