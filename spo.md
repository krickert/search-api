# SharePoint Online Crawling and Indexing Service Architecture

## I. Introduction

This report outlines the architecture and implementation plan for a microservices-based system designed to crawl and index content within SharePoint Online (SPO). The primary objective is to systematically discover items (files, folders, list items) within a specified SPO environment, retrieve their metadata, content, and access control lists (ACLs), and make this information available for downstream processing, potentially for search indexing or data analysis purposes.

The proposed solution leverages modern technologies and patterns, including the Micronaut framework (version 4.8.2 or later) with Java 21, asynchronous communication via Apache Kafka, efficient service-to-service interaction using gRPC, and state management persistence through MongoDB. Authentication and data retrieval from SharePoint Online will be handled via the Microsoft Graph API, utilizing administrative credentials for comprehensive access. The architecture emphasizes scalability, resilience, and efficient handling of potentially large data volumes and the inherent rate limiting of cloud APIs.

## II. System Architecture Overview

The system is designed as a distributed architecture composed of two core microservices: the **Crawler Service** and the **Indexer Service**. These services collaborate to perform the discovery and retrieval tasks, communicating asynchronously via Apache Kafka and synchronously via gRPC where appropriate. A MongoDB database, referred to as `crawldb`, serves as the central repository for managing crawl state and tracking item processing status.

* **Crawler Service:** Responsible for initiating crawls (full or incremental), discovering item IDs within SharePoint Online using the Microsoft Graph API, managing the overall crawl process state (e.g., start/end times, status) in `crawldb`, and dispatching crawl tasks (item IDs) to the Indexer Service via Kafka.
* **Indexer Service:** Consumes item IDs from the Kafka topic, retrieves detailed information for each item (metadata, content, ACLs) from SharePoint Online using the Microsoft Graph API, handles potential errors and throttling, updates the processing status for each item in `crawldb`, and potentially forwards processed data (including content blobs) to another Kafka topic for further consumption. It can also listen to a separate Kafka topic for live update events.
* **Communication Layer:**
    * **Kafka:** Used for decoupling the Crawler and Indexer services. The Crawler produces messages containing item IDs and associated crawl context onto a dedicated topic (`spo-crawl-tasks`). The Indexer consumes these messages for processing. This provides resilience and allows for independent scaling of the services. Processed data from the Indexer might be published to a separate topic (`spo-indexed-items`). A third topic (`spo-live-updates`) can be used to push near real-time change events for immediate processing by the Indexer.[1]
    * **gRPC:** Employed for direct, efficient service-to-service communication if needed (e.g., for specific control commands or status queries between services, although the primary workflow relies on Kafka). Protocol Buffers (Protobuf) define the message contracts for both gRPC and Kafka, ensuring consistency.
* **Data Persistence (`crawldb`):** A MongoDB database stores metadata about each crawl session (e.g., crawl ID, start time, status, type) and the status of each item discovered during a crawl (e.g., item ID, crawl ID, status, failure count, last updated timestamp).

This architecture promotes separation of concerns, allowing the Crawler to focus on discovery and the Indexer on retrieval and processing. The use of Kafka facilitates asynchronous processing, buffering, and resilience against temporary service unavailability.[2, 1] MongoDB provides a flexible and scalable data store suitable for tracking the dynamic state of the crawl process.[3, 4, 5]

## III. Microsoft Graph API Integration

The Microsoft Graph API serves as the primary interface for interacting with SharePoint Online. Secure and efficient utilization of this API is fundamental to the system's operation.

**A. Authentication Strategy**

The services will operate as daemon applications, authenticating non-interactively using the OAuth 2.0 client credentials grant flow.[4] This requires registering an application within Microsoft Entra ID (formerly Azure Active Directory) associated with the target SharePoint Online tenant.[3, 4]

1.  **Application Registration:** An application must be registered in the Microsoft Entra admin center. Key identifiers, including the **Application (client) ID** and **Directory (tenant) ID**, must be recorded during this process.[3, 4] The application should be configured for "Accounts in this organizational directory only".[4, 6]
2.  **Client Credentials:** The application needs credentials to prove its identity when requesting tokens.
    * **Client Secret:** A client secret can be generated within the App Registration. Its value must be securely stored and recorded immediately upon creation, as it cannot be retrieved later.[3, 7, 4] While suitable for testing, client secrets are not recommended for production environments.[4]
    * **Certificate:** Using a certificate is the recommended approach for production.[4] This involves associating a public key certificate with the App Registration and using the corresponding private key within the application for authentication. Secure management of the private key, potentially using Azure Key Vault, is essential.[7, 4]
3.  **API Permissions:** Since the services run without a user context, they require **Application permissions**, not Delegated permissions.[4, 8] Necessary permissions must be granted to the registered application via the "API permissions" section of the App Registration. For accessing SharePoint content, permissions like `Sites.Read.All` (to read all site collections) or potentially more granular permissions like `Sites.Selected` (if limiting access scope) are required.[9] `Files.Read.All` might also be necessary depending on the specific operations.[9] The principle of least privilege should be applied, granting only the permissions essential for the services' functions.[10] For comprehensive permission retrieval, `Sites.FullControl.All` might be needed.[11, 12] For subscribing to change notifications (webhooks), read permissions like `Files.Read.All` or `Sites.Read.All` are typically required.[13]
4.  **Admin Consent:** Application permissions require explicit consent from a tenant administrator. This consent is granted via the "API permissions" page in the App Registration portal.[4, 8] Without admin consent, the application will fail to acquire tokens for the requested permissions.
5.  **MSAL for Java:** The Microsoft Authentication Library (MSAL) for Java will be used to handle the token acquisition process using the client credentials flow.[3, 4] The application will be configured with the Client ID, Tenant ID, and the chosen credential (client secret or certificate details).

**B. Listing SharePoint Items (Crawler Service)**

The Crawler Service needs to discover items within SharePoint Online drives (document libraries).

1.  **Identifying Target Drives:** The crawl process typically starts from the root site or a specified set of sites/drives. The Graph API provides endpoints to list sites (`/sites`) and drives within a site (`/sites/{site-id}/drives`).
2.  **Listing Drive Items:** The primary mechanism for listing the contents of a drive or folder is the `/children` endpoint associated with a `driveItem`.[9]
    * `GET /drives/{drive-id}/items/{item-id}/children`
    * `GET /sites/{site-id}/drive/items/{item-id}/children`
    * (Similar endpoints exist for `/me`, `/users`, `/groups`)
    * The root folder can be accessed using the `root` shortcut: `GET /drives/{drive-id}/root/children`.[9, 14]
3.  **Pagination:** The `/children` endpoint returns results in pages (default size 200 items).[9, 15, 16] When more items exist on subsequent pages, the response includes an `@odata.nextLink` property containing the URL to fetch the next page.[9, 15] The Crawler must follow these links iteratively until no `@odata.nextLink` is returned to retrieve all items in a folder.
4.  **Recursive Traversal & Depth Limiting:** The Graph API's `/children` endpoint lists only immediate children.[9] To perform a full recursive crawl of a folder hierarchy, the Crawler service must implement client-side logic.[17, 14, 18, 19] This typically involves:
    * Fetching children of the current folder.
    * For each child `driveItem` that is a folder (`folder` facet is not null), add it to a queue or recursively call the listing function.
    * To implement the requested depth-limited crawl, the Crawler needs to track the current depth of traversal for each folder. When fetching children, it increments the depth. If the `current depth` reaches the configured `max subcrawl depth`, it should not enqueue or recurse into child folders found at that level. Instead, it should mark these folders as needing a deeper crawl later (e.g., setting a flag in `crawldb`). This allows the initial discovery phase to proceed more quickly for large, deep hierarchies, deferring deeper exploration. There is no built-in Graph API parameter for recursive depth limiting.[15, 16, 20, 21]
5.  **Item ID Retrieval:** Each `driveItem` resource returned by the API includes a unique `id` property. This ID is crucial for subsequent operations by the Indexer service.

**C. Retrieving Specific Item Details (Indexer Service)**

The Indexer Service uses the `driveItem` ID received from the Crawler to fetch detailed information.

1.  **Metadata:** Standard `driveItem` properties (name, size, timestamps, creator, modifier, etc.) can be retrieved using a GET request to the item's endpoint:
    * `GET /drives/{drive-id}/items/{item-id}`
    * `GET /sites/{site-id}/drive/items/{item-id}`
    Custom metadata associated with list items backing the `driveItem` can often be accessed via the `listItem` relationship and its `fields` facet, potentially requiring an `$expand` parameter [22, 23]:
    * `GET /drives/{drive-id}/items/{item-id}/listItem/fields`
    * `GET /sites/{site-id}/lists/{list-id}/items/{item-id}/fields` (if list context is known)
    Specific metadata like retention labels can be fetched using dedicated endpoints or `$expand`.[24]
2.  **Content Download:** For `driveItem` objects representing files (`file` facet is not null), the content can be downloaded.[25, 26]
    * **Standard Method (`/content`):** A `GET` request to `/items/{item-id}/content` typically returns an HTTP `302 Found` redirect response.[25, 27] The `Location` header of the 302 response contains a short-lived, pre-authenticated URL (`@microsoft.graph.downloadUrl`) that the application must follow to download the actual file bytes.[25, 27] This requires the HTTP client to handle redirects or the application to make a second request to the pre-authenticated URL. This URL does not require an `Authorization` header.[25] For JavaScript clients, directly querying the `@microsoft.graph.downloadUrl` property is recommended to avoid CORS issues with the 302 redirect.[25]
    * **Direct Streaming (`/contentStream` - Beta):** A newer beta endpoint, `GET /items/{item-id}/contentStream`, allows direct download of the file content in a single call, avoiding the 302 redirect and potentially improving performance and security.[28, 26] Consideration should be given to using this endpoint once it reaches general availability, but its beta status implies potential changes.
    * The Indexer service should retrieve the content as a stream or byte array (blob) to be passed downstream via Kafka.
3.  **Access Control Lists (ACLs) / Permissions:** The effective sharing permissions for a `driveItem` can be retrieved using the `/permissions` endpoint [29, 30, 31, 11, 32]:
    * `GET /drives/{drive-id}/items/{item-id}/permissions`
    * `GET /sites/{site-id}/drive/items/{item-id}/permissions`
    The response is a collection of `permission` resources. Each resource details who has access (user, group, sharing link) and the roles granted (e.g., read, write).[30, 11] The `inheritedFrom` property indicates if a permission is inherited from a parent item.[30, 11] Retrieving permissions for many items might require individual calls per item, as expanding permissions on a `/children` call might not always be supported or efficient.[20] Permissions like `Sites.Read.All` or potentially `Sites.FullControl.All` might be needed for comprehensive permission retrieval.[9, 11, 12]

**D. Incremental Crawls (Delta Queries)**

To efficiently capture changes since the last crawl without re-scanning everything, the Crawler Service should utilize delta queries.[33]

1.  **Delta Endpoint:** The `/delta` function is available for `driveItem` collections (typically at the root of a drive) [12]:
    * `GET /drives/{drive-id}/root/delta`
    * `GET /sites/{site-id}/drive/root/delta`
    (Similar endpoints exist for `/me`, `/users`, `/groups`).[34]
2.  **Initial Request:** The first call to `/delta` for a drive returns the current state of all items in the drive, paginated using `@odata.nextLink`. The final page of this initial sync includes an `@odata.deltaLink`.[33, 34] This `deltaLink` contains a state token representing the snapshot time.
3.  **Subsequent Requests:** To get changes since the state represented by the token, the Crawler makes a GET request using the previously obtained `@odata.deltaLink`.[33, 34, 35] The response will contain only the items that have been created, updated, or deleted since that token was issued. The response will again include either `@odata.nextLink` (if changes span multiple pages) or a new `@odata.deltaLink` representing the new state.
4.  **Token Management:** The Crawler service must securely store the latest `@odata.deltaLink` for each drive being monitored between crawl cycles (e.g., in the `crawldb` associated with the drive or site). If a delta token becomes invalid (e.g., due to being too old), the API might return an error (like HTTP 410 Gone), indicating that a full re-sync (starting with a fresh `/delta` call) is required.[34]
5.  **Deleted Items:** Items that have been deleted are represented in the delta response with a `deleted` facet.[12] The Indexer service should use this information to mark corresponding items for purging in `crawldb`.
6.  **Scope:** A delta query on `/root/delta` tracks changes for the *entire* drive, including nested items, not just the immediate children of the root.[12, 36]

**E. Key Considerations**

* **API Complexity:** Successfully navigating SharePoint hierarchies, handling pagination, managing delta tokens, and correctly interpreting permissions requires careful implementation against the Graph API's specific behaviors.
* **Error Handling:** Robust error handling is essential. Transient network issues, permission errors (`403 Forbidden`), items not found (`404 Not Found`), and especially throttling (`429 Too Many Requests`) must be anticipated and handled gracefully (see Section IX).
* **Performance vs. Completeness:** Recursive traversal can be time-consuming for large drives. The depth-limiting strategy helps manage initial crawl times, but a mechanism to trigger subsequent deep crawls for deferred folders is needed. Delta queries significantly reduce load for incremental updates but require careful state (token) management.[33, 34]

## IV. Service Implementation Details

This section details the specific responsibilities and logic within the Crawler and Indexer services, built using Micronaut (4.8.2+) and Java 21.

**A. Crawler Service**

The Crawler Service orchestrates the discovery process.

1.  **Initiation:** A crawl can be triggered via an API call, a scheduled job, or an internal event. It creates a new record in the `crawl_sessions` collection in `crawldb`, assigning a unique `crawl_id` and setting the initial status (e.g., 'STARTED').
2.  **Discovery Logic (Full Crawl):**
    * Starts at the specified root (e.g., a site's default document library drive).
    * Uses the Graph API `/children` endpoint to list items.[9]
    * Handles pagination using `@odata.nextLink`.[9, 15]
    * Maintains a queue or stack for folders to visit, tracking the `current depth` for each.
    * For each discovered item (file or folder):
        * Creates a record in the `item_status` collection in `crawldb` with status 'DISCOVERED', linking it to the current `crawl_id`.
        * If the item is a folder and `current depth` < `max subcrawl depth`, adds it to the processing queue/stack.
        * If the item is a folder and `current depth` == `max subcrawl depth`, marks it in `crawldb` as needing a deeper crawl (`needs_deep_crawl = true`).
        * If the item is a file, prepares a task message.
    * Sends a Protobuf message containing the `driveItem` ID, `crawl_id`, and discovery timestamp to the `spo-crawl-tasks` Kafka topic.
    * Updates the corresponding `item_status` record in `crawldb` to 'SENT_TO_INDEXER'.
3.  **Discovery Logic (Incremental Crawl):**
    * Retrieves the stored `@odata.deltaLink` for the target drive from the previous crawl session (stored in `crawldb`).
    * Calls the Graph API `/delta` endpoint using the stored token.[33, 34, 35]
    * Handles pagination (`@odata.nextLink`) for the delta results.
    * For each item returned in the delta response:
        * If the item has a `deleted` facet [12], prepares a message indicating deletion (e.g., `TaskType.PURGE` in the Kafka message) or directly updates `crawldb` status to 'PURGED'.
        * If the item is new or updated, creates/updates the record in `item_status` with status 'DISCOVERED' and sends a task message to Kafka (with `TaskType.FULL_PROCESS`), updating the status to 'SENT_TO_INDEXER'.
    * Stores the new `@odata.deltaLink` returned in the final page of the delta response for the next incremental crawl.
4.  **State Management:** Continuously updates the `crawl_sessions` record (e.g., item counts, status changes to 'COMPLETED' or 'FAILED'). Tracks discovered items and their states in the `item_status` collection.
5.  **Error Handling:** Implements retries for Graph API calls, respecting `Retry-After` headers for throttling (429 errors).[37, 38] Handles other errors (e.g., 403 Forbidden, 404 Not Found) by logging and potentially marking items/sessions as failed in `crawldb`.

**B. Indexer Service**

The Indexer Service performs the detailed retrieval and processing for each item.

1.  **Task Consumption:** Listens to the `spo-crawl-tasks` Kafka topic using a Micronaut `@KafkaListener`.[39, 2, 40, 1, 41] Consumes Protobuf messages containing `driveItem` ID, `crawl_id`, and `TaskType`.
2.  **Processing Logic:** For each consumed message:
    * Updates the corresponding `item_status` record in `crawldb` to 'INDEXING' (if `TaskType` is `FULL_PROCESS`) or 'PURGING' (if `TaskType` is `PURGE`) and records the `processing_start_time`.
    * If `TaskType` is `PURGE`:
        * Marks the item status as 'PURGED' in `crawldb`.
        * Optionally, sends a message to a downstream topic indicating the purge.
    * If `TaskType` is `FULL_PROCESS`:
        * Uses the `driveItem` ID to make Graph API calls:
            * Fetch metadata (`GET /items/{item-id}`, potentially with `$expand=listItem/fields`).[22, 23]
            * Fetch permissions (`GET /items/{item-id}/permissions`).[30, 11, 32]
            * If it's a file, download content (`GET /items/{item-id}/content` or `/contentStream`) as a byte stream/blob.[25, 27, 28, 26]
        * Handles potential errors during Graph API calls:
            * **Throttling (429):** Respect `Retry-After` header, increment failure count if retries exceed a threshold.[37, 38]
            * **Not Found (404):** If an item ID from Kafka is not found in SPO, mark the item status as 'PURGED' in `crawldb`. This handles cases where an item was deleted between discovery and indexing.
            * **Permissions (403):** Log the error, increment failure count, mark as failed.
            * **Other Transient Errors:** Retry a configured number of times with backoff.
        * **Failure Handling:** Increments the `failure_count` in the `item_status` record for each failed (retryable) attempt. If `failure_count` exceeds the configured `failure_allowance`, marks the status as 'FAILED_TERMINAL' and logs the final error.
        * **Success:** If all data is retrieved successfully:
            * Assembles the final data package (metadata, ACLs, content blob).
            * Optionally, sends this package as a Protobuf message to a downstream Kafka topic (e.g., `spo-indexed-items`).
            * Updates the `item_status` record in `crawldb` to 'COMPLETED', resets `failure_count` to 0, and records `processing_end_time`.
3.  **Live Update Consumption:** Optionally, listens to the `spo-live-updates` Kafka topic (see Section VIII) and processes near real-time change events similarly to `FULL_PROCESS` or `PURGE` tasks.

**C. Micronaut Framework Integration (v4.8.2+, Java 21)**

* **Project Setup:** Use Micronaut CLI (`mn create-app`) or Micronaut Launch to create the service projects, specifying Java 21, Gradle (Kotlin DSL is the default since 4.2.0 [42]), and necessary features (`kafka`, `grpc`, `data-mongodb`, etc.).[39, 2, 43, 44, 45, 41, 46, 47]
* **Dependency Injection:** Leverage Micronaut's compile-time DI for managing beans (services, repositories, clients).[48, 49, 50, 1]
* **Configuration:** Utilize `application.yml` (or `.properties`) for configuring Kafka brokers, MongoDB URI, Graph API credentials, crawl parameters, etc..[39, 44, 1, 51, 46, 47] Micronaut's configuration system supports environment variables and profiles.
* **AOT Compilation:** Benefit from Micronaut's Ahead-of-Time compilation for faster startup and lower memory footprint compared to reflection-based frameworks.[48, 49, 52, 50, 53, 1]
* **Java 21:** Utilize Java 21 features. Consider enabling virtual threads in Micronaut (via configuration, e.g., setting `micronaut.executors.io.type=virtual`) to potentially simplify handling of blocking I/O operations (like synchronous MongoDB calls or Graph API calls) without blocking platform threads.[54]

**D. Key Considerations**

* **Idempotency:** The Indexer should ideally be idempotent. If it processes the same item ID multiple times (e.g., due to Kafka re-delivery), it should not cause adverse effects. Checking the item's status in `crawldb` before processing can help achieve this.
* **State Consistency:** Ensuring consistency between Kafka messages and the state in `crawldb` requires careful handling, especially around failures and retries. Transactional operations might be considered if strict consistency is paramount, although this adds complexity (Micronaut Data supports transactions [44, 46, 47, 55]).
* **Resource Management:** The Indexer, particularly content download, can be resource-intensive (network bandwidth, memory for blobs). Configure Kafka consumer concurrency and service resources appropriately.

## V. Communication: gRPC and Kafka

Effective communication between the Crawler and Indexer, and potentially with external systems, is crucial. The design employs both gRPC for synchronous request/response interactions and Kafka for asynchronous, decoupled message passing. Protocol Buffers (Protobuf) serve as the common language for data serialization in both communication methods.

**A. Protocol Buffers (Protobuf) Schema Definition**

A `.proto` file will define the structure of messages exchanged between services and sent over Kafka. This ensures a consistent data contract.

*Example `spo_messages.proto` (simplified):*
```protobuf
syntax = "proto3";

package spo_crawler;

option java_package = "com.example.spo.crawler.protobuf";
option java_multiple_files = true;

import "google/protobuf/timestamp.proto";

// Message sent from Crawler to Indexer via Kafka (`spo-crawl-tasks`)
message CrawlTask {
  string crawl_id = 1; // Unique ID for the crawl session
  string item_id = 2;  // SharePoint driveItem ID
  google.protobuf.Timestamp discovery_time = 3;
  TaskType task_type = 4; // Instructs Indexer on action
}

// Message sent via Kafka for live updates (`spo-live-updates`)
message LiveUpdateTask {
  string item_id = 1; // SharePoint driveItem ID
  ChangeType change_type = 2; // CREATE, UPDATE, DELETE
  google.protobuf.Timestamp event_time = 3;
}

enum TaskType {
  TASK_TYPE_UNKNOWN = 0;
  FULL_PROCESS = 1; // Index metadata, content, ACLs
  PURGE = 2;        // Item was deleted (discovered via delta)
}

enum ChangeType {
  CHANGE_TYPE_UNKNOWN = 0;
  CREATED = 1;
  UPDATED = 2;
  DELETED = 3;
}

// Potential message for downstream systems after indexing (`spo-indexed-items`)
message IndexedItem {
  string crawl_id = 1; // Can be null/empty for live updates not tied to a crawl
  string item_id = 2;
  google.protobuf.Timestamp processing_time = 3;
  map<string, string> metadata = 4; // Simplified metadata
  repeated PermissionInfo acls = 5;
  bytes content_blob = 6; // The actual file content
  bool deleted = 7; // Flag if item was purged
}

message PermissionInfo {
  string grantee_id = 1; // User/Group ID
  string grantee_type = 2; // USER, GROUP, LINK
  repeated string roles = 3; // read, write, etc.
}

// Potential gRPC service definition (Example)
service CrawlControlService {
  rpc GetCrawlStatus(CrawlStatusRequest) returns (CrawlStatusResponse);
  // Add other control methods if needed
}

message CrawlStatusRequest {
  string crawl_id = 1;
}

message CrawlStatusResponse {
  string crawl_id = 1;
  string status = 2; // STARTED, RUNNING, COMPLETED, FAILED
  int64 items_discovered = 3;
  int64 items_processed = 4;
  int64 items_failed = 5;
  google.protobuf.Timestamp start_time = 6;
  google.protobuf.Timestamp end_time = 7;
}

```
This `.proto` file defines the `CrawlTask` and `LiveUpdateTask` messages sent via Kafka, and potentially an `IndexedItem` message for downstream consumers. It also includes an example gRPC service definition (`CrawlControlService`).

**B. gRPC for Service-to-Service Communication**

While the primary workflow uses Kafka, gRPC provides an efficient mechanism for any required direct, synchronous communication between the Crawler and Indexer or for exposing control/status APIs.

1.  **Integration:** The `micronaut-grpc` module integrates gRPC into Micronaut.[45, 50, 56, 57, 53, 58, 59, 60]
2.  **Build Configuration (Gradle Kotlin DSL - `build.gradle.kts`):** The `com.google.protobuf` Gradle plugin is required to generate Java code from `.proto` files. Dependencies like `micronaut-grpc-runtime` (or server/client specific runtimes) and gRPC libraries must be added.[45, 49]
    ```kotlin
    import com.google.protobuf.gradle.* // Required import for protobuf block

    plugins {
        //... other plugins
        alias(libs.plugins.protobuf) // Using version catalog alias
        kotlin("kapt") version "..." // Ensure kapt is applied if using Kotlin annotation processors
    }

    // Ensure versions are defined in libs.versions.toml or gradle.properties
    val protobufVersion: String by project
    val grpcVersion: String by project
    val grpcKotlinVersion: String by project // If using Kotlin stubs

    dependencies {
        //... other dependencies
        implementation(libs.micronaut.grpc.runtime) // Or client/server specific from version catalog
        implementation(libs.grpc.protobuf)
        implementation(libs.grpc.stub)
        // Potentially needed for runtime compilation if using Java sources directly
        // compileOnly(libs.grpc.netty.shaded)
        // runtimeOnly(libs.grpc.netty.shaded)

        // If using Kotlin stubs
        // implementation(libs.grpc.kotlin.stub)
    }

    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:${protobufVersion}"
        }
        plugins {
            id("grpc") { // Use id() for plugins in Kotlin DSL
                artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
            }
            // If using Kotlin stubs
            // id("grpckt") {
            //     artifact = "io.grpc:protoc-gen-grpc-kotlin:${grpcKotlinVersion}:jdk8@jar"
            // }
        }
        generateProtoTasks {
            all().forEach { // Use forEach in Kotlin DSL
                it.plugins {
                    id("grpc") {} // Use id()
                    // If using Kotlin stubs
                    // id("grpckt") {}
                }
                // If using Kotlin stubs, configure kotlin builtin
                // it.builtins {
                //     id("kotlin") {}
                // }
            }
        }
    }

    // Ensure generated sources are included in compilation
    sourceSets {
        main {
            java { // Or kotlin if using Kotlin stubs primarily
                srcDirs("build/generated/source/proto/main/grpc")
                srcDirs("build/generated/source/proto/main/java")
                // If using Kotlin stubs
                // srcDirs("build/generated/source/proto/main/grpckt")
                // srcDirs("build/generated/source/proto/main/kotlin")
            }
        }
    }
    ```
    Ensure versions (`protobufVersion`, `grpcVersion`, `grpcKotlinVersion`) are managed, potentially via Micronaut's BOM or a version catalog (`libs.versions.toml`).[53, 61, 62]
3.  **Service Implementation:** gRPC services are implemented by extending the generated base class (e.g., `CrawlControlServiceImplBase`) and annotating the implementation with `@Singleton`.[45, 49]
4.  **Client Implementation:** gRPC clients are typically created within a Micronaut `@Factory` class. The factory method injects a `ManagedChannel` configured via `@GrpcChannel` (pointing to the target service's configuration) and returns the generated client stub (e.g., `CrawlControlServiceStub`).[45, 49]
5.  **Configuration:** Server port and other options are configured under `grpc.server.*` in `application.yml`. Client channels are configured under `grpc.channels.<channel-name>.*`, specifying the target address and other options like plaintext communication or security settings.[45, 49, 58, 59, 60]

**C. Kafka for Asynchronous Messaging**

Kafka serves as the backbone for decoupling the discovery and indexing processes, as well as handling live updates.

1.  **Integration:** The `micronaut-kafka` module provides Kafka integration.[39, 2, 40, 50, 53, 1, 41, 63, 64, 65, 66]
2.  **Dependencies (Gradle Kotlin DSL - `build.gradle.kts`):** Add `io.micronaut.kafka:micronaut-kafka` to the build.[39, 2, 1]
    ```kotlin
    dependencies {
        //... other dependencies
        implementation(libs.micronaut.kafka) // Using version catalog alias
        // Required for Protobuf serialization with Kafka
        implementation(libs.protobuf.java)
        implementation(libs.confluent.kafka.protobuf.serializer) // Example: Confluent Protobuf Serializer
    }
    ```
3.  **Configuration:** Configure Kafka bootstrap servers in `application.yml` under `kafka.bootstrap.servers`.[39, 63] Additional producer and consumer properties (retries, acknowledgments, group IDs, serializers/deserializers etc.) can be configured under `kafka.producers.<client-id>.*` and `kafka.consumers.<group-id>.*` respectively.[39, 1, 67, 65]
4.  **Producer (Crawler Service):**
    * Define an interface annotated with `@KafkaClient`. Micronaut generates the implementation.[39, 40, 1, 63]
    * Define methods annotated with `@Topic("spo-crawl-tasks")` to specify the target topic.
    * Method parameters map to the Kafka message key (`@KafkaKey`) and value (`@Body` or inferred). The value will be the Protobuf `CrawlTask` object.[39, 40, 1, 63]
    * Example:
        ```java
        import io.micronaut.configuration.kafka.annotation.KafkaClient;
        import io.micronaut.configuration.kafka.annotation.KafkaKey;
        import io.micronaut.configuration.kafka.annotation.Topic;
        import com.example.spo.crawler.protobuf.CrawlTask; // Generated Protobuf class

        @KafkaClient(id = "crawl-task-producer") // Optional ID for specific config
        public interface CrawlTaskProducer {
            @Topic("spo-crawl-tasks")
            void sendTask(@KafkaKey String itemId, CrawlTask task);
        }
        ```
5.  **Consumer (Indexer Service):**
    * Create a class annotated with `@KafkaListener`. Micronaut handles the consumer setup.[39, 2, 40, 1, 41]
    * Define methods annotated with `@Topic(...)` to listen to the relevant topics (`spo-crawl-tasks`, `spo-live-updates`).
    * Method parameters map to the Kafka message key (`@KafkaKey`) and value (`@Body` or inferred), which will be the deserialized Protobuf objects (`CrawlTask`, `LiveUpdateTask`).[39, 2, 40, 1, 41]
    * Example:
        ```java
        import io.micronaut.configuration.kafka.annotation.KafkaKey;
        import io.micronaut.configuration.kafka.annotation.KafkaListener;
        import io.micronaut.configuration.kafka.annotation.OffsetReset;
        import io.micronaut.configuration.kafka.annotation.Topic;
        import com.example.spo.crawler.protobuf.CrawlTask; // Generated Protobuf class
        import com.example.spo.crawler.protobuf.LiveUpdateTask; // Generated Protobuf class
        import jakarta.inject.Inject;
        // Assuming IndexerLogic is a service bean handling the processing
        import com.example.spo.indexer.service.IndexerLogic;

        @KafkaListener(groupId = "spo-indexer-group", offsetReset = OffsetReset.EARLIEST) // Configure group ID
        public class IndexerKafkaConsumers {

            @Inject
            private IndexerLogic indexerLogic;

            @Topic("spo-crawl-tasks")
            public void receiveCrawlTask(@KafkaKey String itemId, CrawlTask task) {
                System.out.println("Received Crawl Task for Item ID: " + itemId + ", Crawl ID: " + task.getCrawlId());
                indexerLogic.processCrawlTask(task);
            }

            @Topic("spo-live-updates")
            public void receiveLiveUpdate(@KafkaKey String itemId, LiveUpdateTask update) {
                System.out.println("Received Live Update for Item ID: " + itemId + ", Change: " + update.getChangeType());
                indexerLogic.processLiveUpdate(update);
            }
        }
        ```
6.  **Protobuf Serialization/Deserialization:**
    * Explicit configuration of Protobuf serializers and deserializers is required for Kafka in Micronaut.[39, 1, 67] Micronaut's default is typically JSON.[39]
    * Include Protobuf Java runtime (`com.google.protobuf:protobuf-java`) [67] and a Kafka Protobuf serializer/deserializer library (e.g., `io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer` and `KafkaProtobufDeserializer` from Confluent Platform, or alternatives).[39, 67]
    * Configure these classes in `application.yml` for the relevant producers and consumers [39, 1, 67, 65]:
        ```yaml
        kafka:
          producers:
            # Configuration for the crawl-task-producer (or use 'default')
            crawl-task-producer:
              key:
                serializer: org.apache.kafka.common.serialization.StringSerializer
              value:
                serializer: io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer
              properties:
                # Add schema.registry.url property if using Confluent Schema Registry
                schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
                # Optional: Specific Protobuf serializer settings
                # value.subject.name.strategy: io.confluent.kafka.serializers.subject.TopicRecordNameStrategy

          consumers:
            # Configuration for the spo-indexer-group
            spo-indexer-group:
              key:
                deserializer: org.apache.kafka.common.serialization.StringDeserializer
              value:
                deserializer: io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer
              properties:
                # Add schema.registry.url property if using Confluent Schema Registry
                schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
                # Required for Protobuf deserializer if not using Schema Registry's type inference
                # specific.protobuf.value.type: com.example.spo.crawler.protobuf.CrawlTask
                # specific.protobuf.key.type: com.google.protobuf.StringValue # If key was protobuf
        ```
    * The use of a Schema Registry (like Confluent Schema Registry) is common with Protobuf/Avro in Kafka ecosystems to manage schema evolution and ensure compatibility between producers and consumers.[39, 1, 67]

**D. Key Considerations**

* **Schema Evolution:** Using Protobuf provides strong typing but requires managing schema changes over time. Backward and forward compatibility strategies are essential, especially for Kafka messages that might persist or be consumed by older/newer service versions. Schema Registry helps manage this.[67]
* **Serialization Overhead:** Protobuf is generally more efficient in terms of size and speed compared to JSON, which is beneficial for high-throughput Kafka topics and gRPC calls.[67]
* **Kafka vs. gRPC Use Cases:** Kafka is ideal for the main asynchronous workflow (Crawler -> Indexer, Live Updates -> Indexer) due to its decoupling, buffering, and replay capabilities.[2, 1] gRPC is suitable for optional, direct interactions where immediate response or control is needed (e.g., querying crawl status).

## VI. Data Persistence: MongoDB (`crawldb`)

MongoDB is selected as the persistence layer (`crawldb`) to store and manage the state of crawl sessions and individual item processing statuses. Its flexible document model is well-suited for storing potentially varied metadata associated with crawls and items.[3, 4, 5, 68, 69, 70]

**A. Rationale for MongoDB**

* **Schema Flexibility:** The nature of crawl data and status tracking can evolve. MongoDB's document model accommodates changes without requiring rigid schema migrations upfront.[3, 4, 71]
* **Scalability:** MongoDB offers horizontal scalability features suitable for handling potentially large volumes of item status records generated during extensive crawls.
* **Rich Querying:** Supports complex queries needed for monitoring, reporting, and managing crawl states (e.g., finding all failed items for a specific crawl).[4, 5]
* **Micronaut Integration:** Micronaut Data provides robust integration with MongoDB, simplifying data access code through repositories.[44, 72, 50, 51, 46, 47, 73, 55, 74]

**B. Database Schema Design**

The schema design prioritizes the common query patterns: tracking overall crawl progress and managing the status of individual items within a crawl. Following MongoDB best practices, the design considers workload and relationships.[4, 5, 75, 69, 71, 76, 77] Two primary collections are proposed:

1.  **`crawl_sessions` Collection:** Stores metadata for each crawl execution.
    * `_id`: ObjectId (MongoDB default, unique identifier for the session) - *Primary Key*
    * `type`: String ('FULL' | 'INCREMENTAL') - *Indexed*
    * `start_time`: ISODate - *Indexed*
    * `end_time`: ISODate (null until completion)
    * `status`: String ('STARTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL_FAILURE') - *Indexed*
    * `target_scope`: String (Identifier for the root target, e.g., Site ID or Drive ID)
    * `parameters`: Embedded Document (e.g., `{ "max_depth": 5, "failure_allowance": 3 }`)
    * `statistics`: Embedded Document (e.g., `{ "items_discovered": 10000, "items_sent_to_indexer": 9990, "items_completed": 9900, "items_failed": 5, "items_purged": 2 }`)
    * `last_delta_token`: String (Stored here after a successful incremental crawl for the specific `target_scope`)

2.  **`item_status` Collection:** Tracks the state of each discovered SharePoint item within a specific crawl session. This collection can become very large.
    * `_id`: ObjectId (MongoDB default) - *Primary Key*
    * `item_id`: String (SPO Item ID) - **Indexed**
    * `crawl_id`: ObjectId (Reference to `crawl_sessions._id`, can be null for live updates) - **Indexed** (Compound index with `status` likely beneficial)
    * `status`: String ('DISCOVERED' | 'SENT_TO_INDEXER' | 'INDEXING' | 'COMPLETED' | 'FAILED' | 'PURGED' | 'FAILED_TERMINAL' | 'PURGING') - **Indexed** (Compound index with `crawl_id` likely beneficial)
    * `last_updated`: ISODate - *Indexed* (Potentially for TTL or cleanup)
    * `discovery_time`: ISODate (null for live updates not from a crawl)
    * `processing_start_time`: ISODate (null until Indexer picks it up)
    * `processing_end_time`: ISODate (null until Indexer finishes)
    * `failure_count`: Integer (default: 0)
    * `last_failure_reason`: String (optional, stores error message on failure)
    * `needs_deep_crawl`: Boolean (default: false) - *Indexed* (If querying for folders needing deeper crawl is required)
    * `item_metadata_snapshot`: Embedded Document (optional, store key metadata like name, path, type at time of processing for debugging/reporting)
    * `acl_snapshot`: Array of Embedded Documents (optional, store key ACLs for debugging/reporting)

**Table: CrawlDB MongoDB Schema Summary**

| Collection Name | Field Name | Data Type | Description/Purpose | Indexing Notes |
| :--------------- | :---------------------- | :------------------ | :-------------------------------------------------------- | :-------------------------------------------------- |
| `crawl_sessions` | `_id` | ObjectId | Unique identifier for the crawl session | Primary Key |
| | `type` | String | Type of crawl (FULL, INCREMENTAL) | Indexed |
| | `start_time` | ISODate | Timestamp when the crawl started | Indexed |
| | `end_time` | ISODate | Timestamp when the crawl finished (null if running) | |
| | `status` | String | Overall status of the crawl | Indexed |
| | `target_scope` | String | Root target identifier (e.g., Site/Drive ID) | |
| | `parameters` | Embedded Document | Crawl configuration (max_depth, failure_allowance) | |
| | `statistics` | Embedded Document | Running counts of item statuses | |
| | `last_delta_token` | String | Token for the next incremental crawl for this scope | |
| `item_status` | `_id` | ObjectId | Unique identifier for the item status record | Primary Key |
| | `item_id` | String | SharePoint Online Item ID | Indexed |
| | `crawl_id` | ObjectId | Reference to `crawl_sessions._id` (nullable) | Indexed (Consider compound with `status`) |
| | `status` | String | Processing status of the item | Indexed (Consider compound with `crawl_id`) |
| | `last_updated` | ISODate | Timestamp of the last status update | Indexed (Potential TTL index candidate) |
| | `discovery_time` | ISODate | Timestamp when discovered by Crawler (nullable) | |
| | `processing_start_time` | ISODate | Timestamp when Indexer started processing | |
| | `processing_end_time` | ISODate | Timestamp when Indexer finished processing | |
| | `failure_count` | Integer | Number of processing failures for this item | |
| | `last_failure_reason` | String | Details of the last failure (optional) | |
| | `needs_deep_crawl` | Boolean | Flag indicating folder needs deeper traversal later | Indexed (If queried) |
| | `item_metadata_snapshot`| Embedded Document | Optional snapshot of key item metadata | |
| | `acl_snapshot` | Array of Emb. Docs. | Optional snapshot of key item ACLs | |

**C. Indexing Strategy**

Effective indexing is critical for performance, especially as the `item_status` collection grows.[5, 71] Indexes should support the primary query patterns:
* **`crawl_sessions`:** Index `_id` (default), `status`, `start_time`, `type`.
* **`item_status`:**
    * Index `_id` (default).
    * Index `item_id` (for looking up status across crawls or for live updates).
    * Index `crawl_id` (for retrieving all items in a crawl).
    * Index `status` (for finding items in specific states).
    * **Compound Index:** A compound index on `{ crawl_id: 1, status: 1 }` will be highly beneficial for efficiently finding items in a specific state (e.g., 'FAILED', 'INDEXING') within a particular crawl session. A similar index on `{ item_id: 1, last_updated: -1 }` might be useful for finding the latest status of an item regardless of crawl.
    * Index `needs_deep_crawl` if queries specifically target these items.
    * Index `last_updated` if TTL (Time-To-Live) indexes are used for automatic data expiration or for cleanup queries.

**D. Micronaut Data MongoDB Integration**

Micronaut Data simplifies interaction with MongoDB.[44, 72, 51, 46, 47, 73, 55]

1.  **Dependencies (Gradle Kotlin DSL - `build.gradle.kts`):** Include `micronaut-data-mongodb` (which pulls in either `micronaut-mongo-reactive` or `micronaut-mongo-sync`) and the annotation processor `micronaut-data-document-processor`.[72, 51, 46]
    ```kotlin
    plugins {
        kotlin("kapt") version "..." // Ensure kapt is applied
        //... other plugins
    }

    dependencies {
        //... other dependencies
        implementation(libs.micronaut.data.mongodb) // Sync or Reactive based on feature selection
        kapt(libs.micronaut.data.document.processor) // Annotation processor
    }
    ```
2.  **Configuration:** Set the MongoDB connection string in `application.yml` using the `mongodb.uri` property.[44, 72, 51, 46] For development and testing, Micronaut Test Resources can automatically manage a MongoDB container and configure the URI.[72, 51, 46]
3.  **Entities:** Define Java classes (`CrawlSession`, `ItemStatus`) mirroring the collections. Annotate them with `@MappedEntity` and fields with `@Id`, `@GeneratedValue`, validation constraints, etc., as needed.[44, 46, 47]
4.  **Repositories:** Create interfaces (e.g., `CrawlSessionRepository`, `ItemStatusRepository`) annotated with `@MongoRepository`.[44, 46, 47] Extend appropriate base interfaces from Micronaut Data (e.g., `CrudRepository<Entity, IdType>`, `PageableRepository`, or reactive counterparts like `ReactiveStreamsCrudRepository`) to get standard CRUD methods.[44, 46, 47] Define custom finder methods following Micronaut Data conventions (e.g., `findByCrawlIdAndStatus(ObjectId crawlId, String status)`) for specific query needs.[44, 46, 47]
5.  **Driver Choice (Sync vs. Reactive):**
    * **Synchronous (`micronaut-mongo-sync` / `data-mongodb` feature):** Offers a more traditional, imperative programming style.[44, 72, 51, 46, 47] Blocking database calls must be executed off the main event loop, typically by annotating repository methods or service methods calling them with `@ExecuteOn(TaskExecutors.IO)` to avoid blocking Netty threads.[44, 47] With Java 21's virtual threads enabled in Micronaut (`micronaut.executors.io.type=virtual`), the impact of blocking might be lessened, making the sync driver simpler to manage.[54]
    * **Reactive (`micronaut-mongo-reactive` / `data-mongodb-reactive` feature):** Provides a non-blocking driver using Reactive Streams (compatible with Project Reactor or RxJava).[72, 51, 46] This integrates seamlessly if the rest of the service (e.g., reactive Kafka clients) uses reactive types. It generally offers better resource utilization under high concurrency on traditional thread models but introduces the complexity of reactive programming paradigms.
    * The decision depends on the overall architecture and team familiarity. If the application heavily uses reactive patterns, the reactive driver is a natural fit. If aiming for simpler imperative code and leveraging Java 21's virtual threads, the synchronous driver with proper thread management might be preferred.

**E. Key Considerations**

* **Schema Design is Query-Driven:** The effectiveness of the MongoDB schema hinges on how well it supports the required queries for status tracking, error handling, and reporting.[4, 5, 75, 71] The proposed separation of sessions and item statuses allows flexible querying of item states across different crawls or live updates.
* **Handling Large Scale:** The `item_status` collection has the potential to grow extremely large. Efficient indexing is non-negotiable.[5, 71] Long-term strategies might involve data archiving or using TTL indexes on `last_updated` to automatically purge old status records if they are not needed indefinitely. The 16MB document size limit is unlikely to be an issue for status documents but applies to individual documents.[68, 71, 70]
* **Atomicity and Consistency:** Standard MongoDB operations on a single document are atomic.[4, 5] If operations require updating multiple documents atomically (e.g., updating `crawl_sessions` statistics and multiple `item_status` records simultaneously), MongoDB transactions would be needed, adding complexity. The current design largely relies on eventual consistency, suitable for status tracking.

## VII. Live Updates via Change Notifications

While the delta query mechanism provides efficient batch updates, incorporating near real-time updates can further enhance the system's responsiveness. This can be achieved by leveraging Microsoft Graph change notifications (webhooks) or another mechanism to push immediate change events into a dedicated Kafka topic.

**A. Microsoft Graph Subscriptions (Webhooks)**

Microsoft Graph allows applications to subscribe to changes on specific resources.[13, 78, 79, 80, 81] When a change occurs (create, update, delete), Graph sends a notification to a pre-configured webhook endpoint.[13, 78, 79]

1.  **Supported Resources:** Subscriptions are supported for various resources, including `driveItem` changes within the root of a OneDrive or SharePoint drive (`/drives/{id}/root` or `/users/{id}/drive/root`).[79, 80] Subscriptions directly on specific subfolders are generally not supported.[82]
2.  **Webhook Endpoint:** A publicly accessible HTTPS endpoint must be created (e.g., as part of a dedicated Micronaut service or potentially the Indexer service itself) to receive these notifications.[78, 83, 84, 85, 86, 87, 88, 89, 90] This endpoint must handle an initial validation request from Microsoft Graph upon subscription creation.[13, 78, 91]
3.  **Subscription Creation:** The application creates a subscription via a POST request to `/subscriptions`, specifying the resource to monitor (e.g., `/drives/{drive-id}/root`), the change types (`created`, `updated`, `deleted`), the notification URL (the webhook endpoint), an expiration time, and optionally a `clientState` for validation.[13, 78, 91, 92, 93] Rich notifications can be requested to include resource data directly in the payload, requiring encryption configuration.[93, 94]
4.  **Notification Handling:** When a change occurs, Graph POSTs a notification to the webhook endpoint. The payload typically includes the `subscriptionId`, `clientState`, `resource` (ID of the changed item), and `changeType`.[13, 94, 95] The webhook handler should:
    * Acknowledge receipt quickly (e.g., HTTP 202 Accepted).[78, 81]
    * Validate the `clientState`.[13, 78]
    * Push a simplified message (e.g., `LiveUpdateTask` Protobuf) containing the `item_id` and `changeType` to the `spo-live-updates` Kafka topic for the Indexer service to process.
5.  **Subscription Lifecycle:** Subscriptions have a maximum lifetime (e.g., ~3 days for directory objects, ~30 days for OneDrive/SharePoint driveItems) and must be renewed periodically using a PATCH request to `/subscriptions/{id}` before they expire.[91, 92, 96, 97, 98, 99] Lifecycle notifications can be configured (`lifecycleNotificationUrl`) to alert the application about impending expirations or reauthorization requirements.[78, 92, 95, 96, 100, 101]
6.  **Limitations:** Webhooks provide near real-time updates but might have limitations regarding resource scope (often root-level), potential delays, or missed notifications (requiring reconciliation, possibly via delta queries).[78, 82] Subscription limits per tenant/app also apply.[102, 80, 99, 103, 104, 105]

**B. Kafka Listener for Live Updates (Indexer Service)**

The Indexer service (or a dedicated listener service) consumes messages from the `spo-live-updates` topic.

1.  **Consumer Implementation:** A Micronaut `@KafkaListener` method is configured to listen to the `spo-live-updates` topic.[39, 2, 40, 1, 41]
    ```java
    // Inside IndexerKafkaConsumers class from Section V.C.5
    @Topic("spo-live-updates")
    public void receiveLiveUpdate(@KafkaKey String itemId, LiveUpdateTask update) {
        System.out.println("Received Live Update for Item ID: " + itemId + ", Change: " + update.getChangeType());
        indexerLogic.processLiveUpdate(update); // Delegate to processing logic
    }
    ```
2.  **Processing Logic:** The `indexerLogic.processLiveUpdate` method handles the incoming `LiveUpdateTask`.
    * If `changeType` is `CREATED` or `UPDATED`, it triggers the same core indexing logic used for `CrawlTask` with `TaskType.FULL_PROCESS`: fetch metadata, permissions, content (if applicable), and update the `item_status` in `crawldb` to 'COMPLETED' or 'FAILED_TERMINAL'. It should handle potential race conditions where a crawl task for the same item might be in progress.
    * If `changeType` is `DELETED`, it triggers the purge logic: mark the `item_status` as 'PURGED' in `crawldb`.
    * The `crawl_id` field in the `item_status` record would likely be null or have a special value for updates originating from the live feed rather than a specific crawl session.

**C. Combining Approaches**

Using both delta queries (for periodic, comprehensive synchronization and reconciliation) and live updates (for low-latency changes) provides a robust system. The Indexer needs to handle potential overlaps or race conditions between processing a batch task from a delta crawl and a live update for the same item (e.g., using timestamps or status checks in `crawldb`).

## VIII. Operational Considerations and Best Practices

Deploying and operating this distributed system requires attention to several key areas, particularly concerning interactions with the external Graph API and managing the stateful crawl processes.

**A. Handling Microsoft Graph API Throttling**

Throttling by the Microsoft Graph API is an expected behavior when making numerous requests, not an exceptional condition.[10, 37, 106, 38, 107, 108] Both the Crawler and Indexer services must be designed to handle HTTP 429 "Too Many Requests" responses gracefully.

1.  **Respect `Retry-After`:** The primary mechanism for handling throttling is to inspect the `Retry-After` header in the 429 response. The value indicates the number of seconds the client should wait before retrying the *exact same* request.[37, 38] Immediate retries should be avoided as they accrue against usage limits.[37, 109, 38, 107]
2.  **Exponential Backoff:** As a fallback or supplement, implement an exponential backoff strategy. If a request fails (with 429 or other transient errors), wait for a base duration, then retry. If it fails again, double the wait time, continuing this pattern up to a maximum number of retries or wait time.[10, 37, 38]
3.  **Optimize API Usage:** Minimize the number and complexity of Graph API calls:
    * Use `$select` to retrieve only necessary fields.[10, 102, 38]
    * Use `$filter` server-side where possible.[10, 102]
    * Prefer delta queries (`/delta`) for incremental updates over full scans.[33, 34, 37, 38]
    * Use batching (`/$batch`) cautiously. While it can reduce HTTP overhead, Graph API applies throttling limits to *individual requests within the batch*, and strict limits often apply (e.g., 4 requests per mailbox for Exchange Online resources, general batch limit might be 20).[10, 37, 109, 38, 107] Evaluate if batching provides significant benefits for the diverse requests made during crawling.
4.  **Traffic Decoration:** Configure the Graph API client (e.g., via MSAL or the Graph SDK) to send a descriptive `User-Agent` string.[106] This helps Microsoft identify the application's traffic, which can be beneficial for support and diagnostics if persistent throttling issues arise.
5.  **Proactive Monitoring:** Monitor HTTP response headers like `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` (provided by some SharePoint APIs [106]) or potentially `X-Ms-Throttle-Usage` (mentioned for Entra ID reports [102], check applicability). These headers can provide early warnings about approaching limits, allowing the services to proactively slow down requests before hitting hard throttling.
6.  **Understand Limits:** Throttling limits are complex and vary based on the API endpoint, the type of operation (read vs. write), the scope (per app per tenant, per tenant all apps), and the specific service (SharePoint, Exchange, Entra ID, etc.).[37, 106, 102, 38, 108] There are often multiple limits applied concurrently. Limits generally cannot be increased.[109, 107]

The inherent nature of Graph API throttling means it fundamentally dictates the maximum throughput of the entire system. The architecture must accommodate this reality rather than treating throttling as an edge case.

**B. Managing Long-Running Crawl Processes**

Full crawls, especially on large tenants, can take significant time.

1.  **Checkpointing:** The Crawler service should periodically persist its state (e.g., the current queue of folders to visit, the last processed item ID, current depth levels) to `crawldb`. If the service instance restarts unexpectedly, it can query `crawldb` to find items still in 'DISCOVERED' or 'SENT_TO_INDEXER' state for the active `crawl_id` and resume processing, avoiding a full restart from the beginning.
2.  **Monitoring and Metrics:** Implement comprehensive monitoring using tools like Micronaut Micrometer [53, 42] integrated with a monitoring system (e.g., Prometheus, Grafana). Key metrics include:
    * Crawler: Items discovered/sent per unit time, Graph API call latency, 429 error rate.
    * Indexer: Kafka consumer lag (for `spo-crawl-tasks` and `spo-live-updates`), items processed/failed per unit time, Graph API call latency, 429 error rate, `crawldb` interaction latency.
    * Kafka: Topic message rates, partition lag.
    * MongoDB: Query performance, connection pool usage, resource utilization.
    * Service Instances: CPU, memory, network I/O, JVM metrics.
    Observing these metrics is crucial for identifying bottlenecks (e.g., Graph throttling, slow DB queries, insufficient Indexer instances causing Kafka lag) and tuning system parameters.
3.  **Resource Allocation:** Configure adequate CPU and memory resources for the service containers or instances, anticipating peak load during large crawls or bursts of activity detected by delta queries or live updates.

**C. Error Handling and Retry Mechanisms**

Robust error handling is vital for a distributed system interacting with external APIs and databases.

1.  **Categorization:** Differentiate between:
    * **Retryable Transient Errors:** Network timeouts, temporary service unavailability (e.g., DB connection issues), Graph API 5xx errors, throttling (429). These should trigger retry mechanisms (respecting `Retry-After` for 429s).
    * **Non-Retryable Errors:** Invalid credentials (401), insufficient permissions (403), bad requests (400), item permanently not found (404 after initial discovery). These should generally result in marking the item/crawl as failed or purged without excessive retries.
    * **Terminal Failures:** Items that consistently fail even after retries (exceeding `failure_allowance`) should be marked as 'FAILED_TERMINAL' in `crawldb`.
2.  **Kafka Consumer Retries:** Configure Micronaut's Kafka listener retry mechanism (`kafka.consumers.<group>.retry.attempts`, `kafka.consumers.<group>.retry.delay`) to handle transient failures during message processing by the Indexer (e.g., temporary inability to connect to `crawldb` or Graph API).[39] Consider configuring a Dead Letter Queue (DLQ) topic to send messages that fail repeatedly, preventing them from blocking the main topic processing.
3.  **Failure Allowance:** The Indexer must correctly implement the `failure_allowance` logic by using the `failure_count` field in the `item_status` collection.

**D. Configuration Management**

Micronaut provides flexible configuration management.[110, 48, 49, 50, 1]

1.  **Externalized Configuration:** Use `application.yml` (or `.properties`) for base configuration. Override settings using environment variables or system properties for different deployment environments (dev, test, prod). Consider a centralized configuration server (like Consul, Spring Cloud Config) if managing many microservices.
2.  **Key Parameters:** Define clear configuration keys for all tunable parameters.

**Table: Key Configuration Parameters**

| Parameter Name | Service | Description | Example Value / Source |
| :------------------------------------------- | :-------------- | :----------------------------------------------------------------------- | :----------------------------------------- |
| `micronaut.application.name` | Both | Service name | `spo-crawler`, `spo-indexer` (`app.yml`) |
| `micronaut.server.port` | Both | HTTP server port (if applicable, e.g., for health checks) | `8080`, `8081` (`app.yml`) |
| `grpc.server.port` | Both | gRPC server port (if applicable) | `50051` (`app.yml`) |
| `grpc.channels.<name>.address` | Both | Target address for gRPC client channel | `dns:///spo-crawler:50051` (`app.yml`) |
| `kafka.bootstrap.servers` | Both | Kafka broker list | `kafka-broker-1:9092,...` (Env Var/`app.yml`) |
| `kafka.producers.<id>.value.serializer` | Crawler | Serializer class for Kafka message value (Protobuf) | `io.confluent...KafkaProtobufSerializer` (`app.yml`) |
| `kafka.consumers.<group>.value.deserializer` | Indexer | Deserializer class for Kafka message value (Protobuf) | `io.confluent...KafkaProtobufDeserializer` (`app.yml`) |
| `kafka.consumers.<group>.threads` | Indexer | Number of consumer threads for the listener | `5` (Env Var/`app.yml`) |
| `mongodb.uri` | Both | MongoDB connection string | `mongodb://user:pass@host:port/db` (Env Var) |
| `graph.client.id` | Both | Microsoft Entra App Registration Client ID | (Env Var / Secret Management) |
| `graph.tenant.id` | Both | Microsoft Entra Tenant ID | (Env Var / Secret Management) |
| `graph.client.secret` or cert details | Both | Application credential | (Env Var / Secret Management) |
| `crawl.max.depth` | Crawler | Maximum depth for initial recursive crawl | `10` (`app.yml`) |
| `indexer.failure.allowance` | Indexer | Max retries per item before marking as FAILED_TERMINAL | `3` (`app.yml`) |
| `throttling.max.retries` | Both | Max retries specifically for 429 errors | `5` (`app.yml`) |
| `throttling.backoff.initial.ms` | Both | Initial backoff delay for non-429 retryable errors | `1000` (`app.yml`) |
| `webhook.notification.url` | (Webhook Service) | URL for Graph to send notifications | `https://your-service.com/api/webhook` (`app.yml`) |

**E. Key Considerations**

* **Delta Query State Management:** The reliability of incremental crawls depends heavily on correctly storing and retrieving the `last_delta_token` for each monitored drive.[12, 33, 34] Failure to do so necessitates a potentially costly full re-scan. This state must be managed transactionally with the crawl session completion if possible.
* **Webhook Reliability & State:** Webhook notifications are not guaranteed to be delivered or ordered. The system must be resilient to missed or delayed notifications, potentially relying on delta queries for eventual consistency.[78] Managing webhook subscription lifecycles (renewal, reauthorization) is crucial.[91, 92, 96, 97, 98, 100, 101]
* **Monitoring is Non-Negotiable:** Given the complexities of distributed processing, external API interactions (and throttling), Kafka messaging, and potentially large data volumes, effective monitoring is not optional but essential for understanding performance, diagnosing issues, and tuning the system.[53, 42] Without it, operators will be blind to critical problems like persistent throttling or growing Kafka backlogs.

## IX. Conclusion and Recommendations

**A. Architecture Summary**

The proposed architecture provides a robust and scalable solution for crawling and indexing SharePoint Online content. It utilizes a two-service model (Crawler and Indexer) built on Micronaut (4.8.2+) and Java 21, leveraging the Microsoft Graph API for SPO interactions. Asynchronous communication via Kafka (with Protobuf messages) decouples the services, enhancing resilience and scalability.[1] gRPC offers an option for efficient synchronous communication if needed. MongoDB (`crawldb`) serves as a flexible persistence layer for managing crawl state and item status.[4, 5] Key features include support for full and incremental (delta query) crawls, depth-limited initial traversal, optional near real-time updates via Kafka/webhooks, configurable failure handling, and built-in considerations for Graph API throttling. The build system utilizes Gradle with Kotlin DSL.

**B. Key Considerations & Potential Challenges**

* **Microsoft Graph API Throttling:** This remains the most significant operational challenge and performance bottleneck. The system's design incorporates best practices (Retry-After, backoff, optimized calls), but careful monitoring and potential tuning of crawl aggressiveness will be necessary.[10, 37, 106, 109, 38, 107]
* **Scalability:** Handling very large SPO tenants (billions of items) will test the scalability of all components: Graph API interaction patterns, Kafka throughput, MongoDB performance (indexing is critical), and the resource allocation for the services themselves.
* **Schema and API Evolution:** Both the internal Protobuf schema and the external Microsoft Graph API can change. Maintaining compatibility, especially for Kafka messages, requires careful planning (e.g., using Schema Registry).[67] Graph API changes may necessitate code updates.
* **Error Handling Complexity:** The distributed nature and reliance on external APIs introduce numerous potential failure points. Implementing comprehensive and nuanced error handling (transient vs. permanent, throttling, permissions) is complex but crucial for reliability.
* **Live Update Reliability:** If implementing live updates via webhooks, managing subscription lifecycles and handling potential missed notifications adds complexity.[78, 92, 96, 97]
* **Credential Security:** Secure storage and management of the Microsoft Entra application credentials (preferably certificates for production) are paramount.[7, 4]

**C. Recommendations for Next Steps**

1.  **Proof of Concept (PoC):** Prioritize building a focused PoC to validate core technical integrations and assumptions using Java 21, Micronaut 4.8.2+, and Gradle Kotlin DSL. This should include:
    * Graph API: Authentication (client credentials), recursive item listing with pagination, basic delta query usage (token retrieval/use), content download (handling 302 or using `/contentStream`), and permission retrieval for a single item.
    * Micronaut: Basic Kafka producer (`@KafkaClient`) and consumer (`@KafkaListener`) using Protobuf serialization (configure serializers/deserializers). Basic gRPC client/server interaction (if needed).
    * MongoDB: Basic CRUD operations using Micronaut Data MongoDB (`@MappedEntity`, `@MongoRepository`) with the synchronous driver (consider `@ExecuteOn(TaskExecutors.IO)` or virtual threads).
2.  **Detailed Schema Refinement:** Finalize the MongoDB schema based on a thorough analysis of all anticipated query patterns for monitoring, error recovery, and potential reporting needs. Confirm indexing strategies.
3.  **Infrastructure Planning:** Plan the deployment and management strategy for Kafka and MongoDB (self-hosted vs. managed services). Establish procedures for managing application credentials (certificates, secrets) securely.
4.  **Throttling Simulation and Testing:** Develop specific tests or simulation strategies to verify the system's behavior under Graph API throttling conditions (handling 429s, respecting `Retry-After`).
5.  **Incremental Development:** Build the full services incrementally. Start with the core crawl/index flow for files, then layer in folder handling, depth limiting, delta queries, detailed error handling, and comprehensive monitoring. Implement the live update listener (Section VII) if required. Test thoroughly at each stage.
6.  **Technology Stack Verification:** Confirm Micronaut's integration with Java 21 virtual threads (`micronaut.executors.io.type=virtual`) and assess the practical benefits for the chosen MongoDB driver (sync vs. reactive) within this context.[54] Ensure Gradle Kotlin DSL configurations for Protobuf and other plugins are correct.[45, 111]
