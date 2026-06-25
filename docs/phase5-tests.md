# Phase 5 — Tests

## Results

```
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Coverage Estimate

~65–70% line coverage. Core business logic layers are fully tested; infrastructure layers are not.

| Class | Tested |
|---|---|
| `NotificationService` | ✅ |
| `NotificationController` | ✅ |
| `NotificationRequestValidator` | ✅ |
| `NotificationRedisUtil` | ✅ |
| `GlobalExceptionHandler` | ✅ (via controller tests) |
| `NotificationCoreService` | ❌ |
| `NotificationMapper` | ❌ |
| `RateLimitInterceptor` | ❌ |
| `RequestLoggingInterceptor` | ❌ |
| `CorrelationIdFilter` | ❌ |
| `NotificationScheduler` | ✅ (sweeper) |

---

## NotificationServiceTest — 19 tests

| Test | Scenario |
|---|---|
| `create_newNotification_returnsFalseReplayed` | Normal create; replayed = false |
| `create_withIdempotencyKey_storesKey` | Create with idempotency key; resolveIdempotencyKey called |
| `create_withExistingIdempotencyKey_returnsReplayed` | Duplicate key; returns cached result, replayed = true, no insert |
| `create_idempotencyRaceResolvesAfterRetry` | Concurrent duplicate key; claim fails, spin-wait resolves, returns replayed |
| `create_scheduledNotification_skipsPublish` | scheduledAt set; MQ not called |
| `findById_cachedResult_returnsCached` | Cache hit; DB not queried |
| `findById_notCached_acquiresLockAndQueriesDb` | Cache miss; acquires mutex, queries DB, releases lock |
| `findById_lockContested_retriesAndFindsInCache` | Lock busy; retries, finds in cache without DB call |
| `findById_notFound_acquiresLockCachesNullResult` | DB miss; caches null sentinel, releases lock |
| `findById_nullCached_throwsWithoutHittingDb` | Null sentinel hit; throws immediately, no DB |
| `getRecent_cachedList_returnsCached` | Redis list non-empty; DB not queried |
| `getRecent_emptyCache_queriesDb` | Redis list empty; falls back to DB |
| `update_validRequest_updatesAndEvicts` | Fields updated; cache evicted |
| `delete_existingNotification_setsDeletedAt` | deletedAt set; cache evicted |
| `delete_notFound_throws` | ID does not exist; 404 exception |
| `retry_failedNotification_incrementsRetryCount` | FAILED status; retryCount + 1, MQ republished |
| `retry_nonFailedNotification_throwsApiException` | Non-FAILED status; ApiException (409) |
| `updateTracking_delivered_setsDeliveredAt` | DELIVERED event; deliveredAt set |
| `updateTracking_read_setsReadAt` | READ event; readAt set |

---

## NotificationControllerTest — 20 tests

| Test | Endpoint | Scenario |
|---|---|---|
| `create_validRequest_returns201` | POST / | Valid body → 201 |
| `create_replayedIdempotency_returns200WithHeader` | POST / | Duplicate key → 200 + `X-Idempotency-Replayed: true` |
| `create_missingRequiredField_returns400` | POST / | Missing `content` → 400 VALIDATION_ERROR |
| `create_invalidType_returns400` | POST / | `type: "fax"` → 400 VALIDATION_ERROR |
| `getById_existingId_returns200` | GET /{id} | Existing ID → 200 |
| `getById_notFound_returns404` | GET /{id} | Unknown ID → 404 NOTIFICATION_NOT_FOUND |
| `getRecent_returns200WithList` | GET /recent | Returns list → 200 |
| `update_validRequest_returns200` | PUT /{id} | Valid body → 200 |
| `update_missingContent_returns400` | PUT /{id} | Missing `content` → 400 VALIDATION_ERROR |
| `update_notFound_returns404` | PUT /{id} | Unknown ID → 404 |
| `delete_existingId_returns204` | DELETE /{id} | Existing ID → 204 |
| `delete_notFound_returns404` | DELETE /{id} | Unknown ID → 404 |
| `retry_failedNotification_returns200` | POST /{id}/retry | FAILED notification → 200 |
| `retry_nonFailedNotification_returns409` | POST /{id}/retry | Non-FAILED → 409 INVALID_STATUS_TRANSITION |
| `updateTracking_delivered_returns200` | PATCH /{id}/tracking | DELIVERED event → 200 |
| `updateTracking_invalidEvent_returns400` | PATCH /{id}/tracking | Unknown event → 400 VALIDATION_ERROR |
| `updateTracking_missingEvent_returns400` | PATCH /{id}/tracking | Empty body → 400 VALIDATION_ERROR |
| `wrongMethod_returns405` | DELETE /{id}/retry | Wrong HTTP method → 405 |
| `create_malformedJson_returns400` | POST / | Malformed JSON → 400 INVALID_ARGUMENT |
| `create_idempotencyKeyTooLong_returns400` | POST / | `X-Idempotency-Key` > 64 chars → 400 |

---

## NotificationRequestValidatorTest — 15 tests

| Test | Scenario |
|---|---|
| `email_validRecipient_passes` | Valid email address accepted |
| `email_invalidRecipient_fails` | Non-email string rejected |
| `email_validEmailOptions_passes` | Valid cc + contentType accepted |
| `email_invalidCcAddress_fails` | Invalid cc email rejected |
| `email_invalidContentType_fails` | `text/xml` rejected; only plain/html allowed |
| `sms_validE164Phone_passes` | `+886912345678` accepted |
| `sms_phoneWithoutPlus_fails` | `0912345678` (no country code) rejected |
| `sms_asciiContentWithinLimit_passes` | 160-char ASCII accepted |
| `sms_asciiContentTooLong_fails` | 161-char ASCII rejected |
| `sms_unicodeContentWithinLimit_passes` | 70-char Unicode accepted |
| `sms_unicodeContentTooLong_fails` | 71-char Unicode rejected |
| `scheduledAt_futureTime_passes` | Future datetime accepted |
| `scheduledAt_pastTime_fails` | Past datetime rejected |
| `nullType_returnsValid` | Null type short-circuits to valid (field-level @NotBlank handles it) |
| `nullRequest_returnsValid` | Null request short-circuits to valid |

---

## NotificationRedisUtilTest — 24 tests

| Test | Scenario |
|---|---|
| `cacheNotification_storesWithJitteredTtl` | TTL stored in range [50, 70] seconds |
| `getCachedNotification_hit_returnsResponse` | JSON in Redis → deserialized response |
| `getCachedNotification_miss_returnsEmpty` | Key absent → empty |
| `getCachedNotification_nullSentinel_returnsEmpty` | `__NULL__` value → empty, no deserialization attempt |
| `evictNotification_deletesKey` | DELETE called on correct key |
| `cacheNotFound_storesNullSentinel` | `__NULL__` stored with 30 s TTL |
| `isNullCached_sentinelPresent_returnsTrue` | `__NULL__` value → true |
| `isNullCached_absent_returnsFalse` | Key absent → false |
| `isNullCached_normalValue_returnsFalse` | JSON value → false |
| `tryAcquireLock_success_returnsTrue` | SET NX succeeds → true |
| `tryAcquireLock_alreadyLocked_returnsFalse` | SET NX fails → false |
| `releaseLock_deletesLockKey` | DELETE called on `lock:notification:{id}` |
| `tryClaimIdempotencyKey_notExists_returnsTrue` | SET NX succeeds → true |
| `tryClaimIdempotencyKey_alreadyClaimed_returnsFalse` | SET NX fails → false |
| `getIdempotencyResult_absent_returnsEmpty` | Key absent → empty |
| `getIdempotencyResult_pending_returnsEmpty` | PENDING value → empty |
| `getIdempotencyResult_resolved_returnsId` | Numeric value → Optional with ID |
| `resolveIdempotencyKey_setsIdWithTtl` | SET called with 24 h TTL |
| `getRecent_populated_returnsDeserializedList` | JSON list deserialized correctly |
| `getRecent_empty_returnsEmptyList` | Empty Redis list → empty Java list |
| `evictRecent_deletesKey` | DELETE called on recent key |
| `getIdempotencyResult_malformedValue_returnsEmpty` | Non-numeric stored value → empty, no exception |
| `tryAcquireSchedulerLock_success_returnsTrue` | SET NX succeeds → true |
| `tryAcquireSchedulerLock_alreadyLocked_returnsFalse` | SET NX fails → false |

---

## NotificationSchedulerTest — 2 tests

| Test | Scenario |
|---|---|
| `recoverStuckPending_withStuckNotifications_marksAsFailed` | Stuck PENDING rows → status set to FAILED, cache evicted |
| `recoverStuckPending_noStuckNotifications_doesNothing` | No stuck rows → no DB writes, no cache eviction |
