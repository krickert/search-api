# Java-Based Confluence Cloud Crawling and Event Handling for Indexing

## I. Introduction

### Purpose

This report provides a technical guide for experienced Java developers aiming to build a comprehensive crawling and indexing solution for Atlassian Confluence Cloud. The focus is on utilizing the Confluence REST API to systematically retrieve detailed information about content entities (pages, blog posts, attachments, comments, etc.) and leveraging Confluence webhooks to capture real-time changes for integration with a downstream system, such as Apache Kafka. The objective is to detail the data extraction process via the REST API and the event capture mechanism via webhooks, providing a foundation for building a robust indexing pipeline.

### API Focus

The primary focus of this guide is the **Confluence Cloud REST API v2** (often identified by URI paths starting with `/wiki/api/v2/`).\[1\] This version represents Atlassian's strategic direction for cloud APIs and offers improvements like cursor-based pagination.\[1\] However, it's crucial to acknowledge that API v2 is still evolving and may not yet provide endpoints or features equivalent to all functionalities found in the older REST API v1 (often `/rest/api/`) or the Confluence Server/Data Center APIs.\[2, 3\] Consequently, this report will reference API v1 or Server/DC documentation \[4, 5, 6, 7, 8, 9, 10, 11\] where necessary to provide context, illustrate concepts (like the `expand` parameter), or highlight potential workarounds for features currently missing in the public Cloud API v2, such as detailed content restriction retrieval.\[12\] Developers should be aware that documentation can sometimes be ambiguous, mixing different product versions or API tiers.\[13, 14\] Therefore, the official Confluence Cloud REST API v2 documentation \[1\] should always be the primary reference point.

### Target Audience

This document is intended for experienced Java developers familiar with REST API concepts, JSON data handling, and potentially messaging systems like Kafka. It assumes a solid understanding of Java development practices and HTTP communication.

## II. Confluence REST API Fundamentals (Cloud Focus)

### API Versions (v1 vs. v2)

Confluence Cloud exposes two major versions of its REST API. API v1, typically accessed via base paths like `/rest/api/`, has been available for a longer time. API v2, using paths like `/wiki/api/v2/`, is the newer iteration designed for the cloud platform.\[1, 13\] Atlassian is actively developing and promoting v2 as the future standard.\[2\]

However, v2 does not yet offer a complete replacement for all v1 functionalities.\[3\] For instance, retrieving certain content types beyond pages and blog posts within a space, or fetching detailed content restrictions, might still require falling back to v1 endpoints or specific `expand` parameters available only in v1.\[3, 12, 15\] This coexistence introduces complexity. Developers must carefully evaluate which API version supports the specific data points needed for their integration. Relying solely on v2 might lead to incomplete data capture, while mixing v1 and v2 calls increases the implementation and maintenance burden due to differing base URLs, pagination methods, and potentially authentication nuances. Careful verification against the official Cloud v2 documentation \[1\] is paramount, treating v1 endpoints as potential (but potentially less stable) fallbacks.

### Authentication (API Tokens Recommended)

For programmatic access, especially for backend scripts and integrations like a crawler, the recommended authentication method for Confluence Cloud REST APIs is **Basic Authentication using an Atlassian API Token**.\[1, 9, 11\] This involves:

1. Generating an API Token through the user's Atlassian Account settings (<https://id.atlassian.com/manage-profile/security/api-tokens>). API tokens function like passwords but are specific to API usage and can be revoked independently.

2. Constructing the `Authorization` HTTP header using the user's email address and the generated API token, Base64 encoded.
```java
    // Conceptual Java Snippet for Basic Auth Header with API Token
    import java.util.Base64;
    import java.net.HttpURLConnection;

    //... inside your request setup method...
    String email = "your-email@example.com";
    String apiToken = "YOUR_API_TOKEN"; // Replace with actual token
    String credentials = email + ":" + apiToken;
    String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());

    HttpURLConnection connection = /\*... get connection object... \*/;
    connection.setRequestProperty("Authorization", "Basic " + encodedCredentials);
    //... continue setting up and sending the request...
```
While straightforward, using API tokens grants the script the same permissions as the user who generated the token. For applications distributed to multiple users or requiring more granular control, **OAuth 2.0** (specifically 3LO - 3-legged OAuth) is the preferred method, particularly for Atlassian Connect apps.\[1, 16\] OAuth 2.0 allows applications to request specific permission scopes (e.g., read pages, write comments) rather than inheriting all user permissions, adhering to the principle of least privilege and enhancing security.\[16\] JWT authentication is also used, primarily within the context of Atlassian Connect apps.\[1, 17\] For a server-side crawling script run internally, the API Token method is often sufficient and simpler to implement.\[9, 11\]

### Making Requests in Java (Conceptual)

Interacting with the REST API from Java involves standard HTTP client operations:

1. **Choose an HTTP Client:** Java's built-in `java.net.HttpURLConnection` can be used, but libraries like Apache HttpClient (<https://hc.apache.org/httpcomponents-client-ga/>) or OkHttp (<https://square.github.io/okhttp/>) offer more features, flexibility, and ease of use.

2. **Build the Request:** Construct the target URL (including base URL, API path, path parameters, and query parameters like `limit`, `cursor`, `body-format`). Set the HTTP method (e.g., `GET`, `POST`). Add necessary headers, including `Authorization` (see above) and `Accept: application/json`.

3. **Execute the Request:** Send the request to the Confluence Cloud instance.

4. **Handle the Response:** Process the HTTP status code and parse the response body.

### Handling Responses

Confluence REST API responses are typically formatted in JSON.\[4, 9, 15, 18, 19\] Java applications need a JSON processing library, such as Jackson (<https://github.com/FasterXML/jackson>) or Gson (<https://github.com/google/gson>), to parse the JSON response body into Java objects (or maps/lists).

Crucially, always check the HTTP status code of the response:

* **2xx (e.g., 200 OK, 204 No Content):** Indicates success. Proceed with parsing the response body (if present). \[20\]

* **4xx (e.g., 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found):** Indicates a client-side error. The request might be malformed, lack proper authentication/authorization, or target a non-existent resource. Log the error and potentially adjust the request or skip the resource. \[20\]

* **5xx (e.g., 500 Internal Server Error, 503 Service Unavailable):** Indicates a server-side error on Confluence's end. Implement retry logic with exponential backoff for these errors.

### Pagination

Fetching collections of resources (like spaces, pages, comments) often involves pagination to manage response sizes. Confluence Cloud APIs use different pagination mechanisms:

* **API v1:** Typically uses `start` (0-based index) and `limit` (max results per page) query parameters.\[4, 5, 7, 15, 18, 21\] The client calculates the `start` value for subsequent pages (`start = previous_start + limit`).

* **API v2:** Employs **cursor-based pagination**, which is generally more robust for large, changing datasets.\[1, 22, 23, 24, 25, 26, 27\]

  * The client sends an initial request, optionally specifying a `limit`.

  * If more results exist, the response includes a `next` link. This link might be in the `Link` HTTP header \[1\] or within a `_links` object in the JSON body.\[22, 27\]

  * The `next` URL contains a `cursor` parameter encoding the position for the next request.

  * The client simply makes a `GET` request to this `next` URL to retrieve the subsequent page.

  * This continues until no `next` link is provided in the response.

Offset-based pagination (v1) can be problematic during long crawls; if items are added or deleted while iterating, pages might be skipped or duplicated. Cursor-based pagination (v2) typically uses opaque tokens that point to a specific position or item, making it less susceptible to such issues and better suited for the goal of a comprehensive site crawl.\[1\]
```java
    // Conceptual Java Loop for V2 Cursor Pagination
    import com.fasterxml.jackson.databind.JsonNode; // Example using Jackson
    import com.fasterxml.jackson.databind.ObjectMapper;
    //... Assume 'httpClient' is configured (e.g., Apache HttpClient)
    //... Assume 'objectMapper' is an ObjectMapper instance

    String nextUrl = "[https://your-domain.atlassian.net/wiki/api/v2/pages?limit=50](https://your-domain.atlassian.net/wiki/api/v2/pages?limit=50)"; // Initial URL

    while (nextUrl!= null) {
        // Make GET request to nextUrl using httpClient
        // String responseBody = executeRequest(nextUrl); // Placeholder for actual request execution

        // Parse the JSON response
        // JsonNode responseJson = objectMapper.readTree(responseBody);

        // Process results from responseJson.get("results")
        // processPageResults(responseJson.get("results")); // Placeholder

        // Check for the next link
        JsonNode linksNode = responseJson.get("_links");
        if (linksNode!= null && linksNode.has("next")) {
            // Construct the full next URL if it's relative
            String nextPath = linksNode.get("next").asText();
            if (nextPath.startsWith("/")) {
                 // Assuming 'baseUrl' is like "[https://your-domain.atlassian.net/wiki](https://your-domain.atlassian.net/wiki)"
                 // nextUrl = baseUrl + nextPath;
            } else {
                 nextUrl = nextPath; // If already absolute
            }
        } else {
            nextUrl = null; // No more pages
        }

        // Optional: Add delay to respect rate limits
        // Thread.sleep(100);
    }

```
### Expansion (`expand` v1 / `include` v2)

To minimize the number of separate HTTP requests needed to gather related data for an entity (like a page's space, version, attachments, comments), Confluence APIs support expansion.\[5, 7, 9, 15, 21\]

* **API v1:** Uses the `expand` query parameter. It accepts a comma-separated list of properties to embed in the response. Dot notation allows expanding nested properties (e.g., `body.storage` expands the body and includes its storage representation).\[5\] Key v1 `expand` values relevant for indexing include: `space`, `version`, `history`, `ancestors`, `body.storage` (essential for parsing), `children.page`, `children.attachment`, `children.comment`, `restrictions.read.restrictions.user`, `restrictions.read.restrictions.group`, `metadata.labels`.\[5, 15\]

* **API v2:** The expansion mechanism in v2 appears different and possibly less mature or comprehensive. Some endpoints might use parameters like `includeProperties`, `includeLabels`, `includeOperations`, `includeLikes`.\[28\] However, documentation suggests limitations, such as included results not supporting pagination or sorting, and having a maximum count.\[28\] The `body-format` parameter \[29\] in v2 achieves a similar goal for retrieving specific body representations.

Using expansion drastically reduces network latency by fetching multiple related data types in one go. However, it can result in significantly larger JSON responses, increasing parsing complexity and memory usage on the client side. Conversely, fetching each related piece of data via separate API calls is simpler to parse but incurs much higher network overhead. The optimal approach involves identifying the essential related data needed immediately (e.g., `body.storage` for parsing links/headings) and fetching less critical or bulkier related data (like full comment threads or attachment lists) via separate calls if the expanded responses become too unwieldy.

## III. Java Client Approaches

Several approaches exist for interacting with the Confluence REST API from Java:

### Direct HTTP Client Usage

Using standard Java HTTP clients (`java.net.HttpURLConnection`, Apache HttpClient, OkHttp) provides maximum control. The developer manually constructs requests, handles headers (including authentication), parses JSON responses, implements pagination logic, and manages errors.

* **Pros:** No extra dependencies beyond the chosen HTTP client and a JSON library. Full control over request/response details.

* **Cons:** Verbose, requires significant boilerplate code for common tasks (authentication, pagination, JSON mapping, error handling). Higher potential for errors in implementation details.\[30\]

### Official `confluence-rest-client` Library

Atlassian provides a Maven artifact `com.atlassian.confluence:confluence-rest-client` \[30, 31\], available in their public Maven repository.\[31\]

* **Pros:** Officially published by Atlassian. Might offer some pre-built model classes and service wrappers.

* **Cons:** This library suffers from a severe lack of documentation and usage examples, a fact repeatedly highlighted in community forums.\[30, 32, 33\] It appears significantly less developed and supported than its Jira counterpart (`jira-rest-java-client`).\[30, 32, 34\] There are indications it may not handle API Token (PAT) authentication well \[35, 36\] and might be primarily focused on older Confluence Server versions or the v1 API, making it unsuitable for modern Cloud v2 development. The consistent lack of official examples or guidance strongly suggests this library is not actively maintained for general developer use.\[32, 34\]

### Generating Client via OpenAPI Spec (Swagger-Codegen)

A powerful approach involves using tools like Swagger Codegen (<https://swagger.io/tools/swagger-codegen/>) or OpenAPI Generator (<https://openapi-generator.tech/>) along with the official Confluence Cloud OpenAPI specification file.\[37\] Atlassian provides this specification, downloadable from the REST API documentation pages.\[37\]

* **Process:**

  1. Download the Confluence Cloud OpenAPI spec (e.g., `swagger.v3.json`).\[37\]

  2. Run the chosen generator tool, providing the spec file, specifying `java` as the language, and configuring options (like target directory, Java package names, choice of HTTP library like Jersey, OkHttp, etc.) via a configuration file.\[37\]

  3. The tool generates Java source code, including model classes representing API resources (like `Page`, `Comment`), API client classes with methods for each endpoint, and supporting infrastructure (serialization, authentication setup).

* **Pros:** Automatically generates type-safe client code, significantly reducing boilerplate. Handles request building, response parsing, and model mapping. Can be easily regenerated if the API specification is updated. Supports various underlying HTTP libraries and customization.\[37\]

* **Cons:** Requires installing and learning the generator tool. The quality of the generated code depends heavily on the accuracy and completeness of the official OpenAPI specification. Generated code might sometimes feel less idiomatic than hand-written code. Note that while a Cloud spec exists, an official OpenAPI spec for Confluence Server/DC does not.\[33\] Community libraries based on this or direct implementation also exist \[38, 39\], but carry the usual risks of third-party maintenance and support.

### Recommendation

For building a robust Java integration with the **Confluence Cloud REST API v2**, the recommended approach is **generating a client library using the official OpenAPI specification**.\[37\] This offers the best balance of type safety, reduced boilerplate, and maintainability, aligning with modern API development practices.

Reliance on the official `com.atlassian.confluence:confluence-rest-client` library is discouraged due to the persistent lack of documentation and community-reported difficulties.\[30, 32, 33\] Direct HTTP client usage should be reserved for very simple use cases or as a fallback if the generated client proves insufficient for specific needs.

## IV. Full Site Crawling Strategy

Crawling an entire Confluence site involves discovering all relevant content entities (primarily pages and blog posts) across all accessible spaces.

### Listing Spaces

The starting point is to obtain a list of all spaces the authenticated user can access.

1. Make `GET` requests to the `/wiki/api/v2/spaces` endpoint.\[27\]

2. Handle pagination using the cursor mechanism described previously (checking `_links.next` or the `Link` header).\[1, 27\]

3. For each space object in the results, extract its `id` and `key`.\[27\] Keep in mind that only spaces the user has permission to view will be returned.\[27\]

### Iterating Content per Space

Once you have the list of space IDs, you need to retrieve the content within each space. This presents a challenge because API v2 lacks a direct equivalent to the v1 endpoint `/rest/api/space/{spaceKey}/content` that could fetch all content types at the root level.\[3\] Several strategies can be employed:

1. **Fetch Specific Types (v2):** Use dedicated v2 endpoints for known types:

   * `GET /wiki/api/v2/spaces/{id}/pages` to get top-level pages.\[20\]

   * `GET /wiki/api/v2/spaces/{id}/blogposts` to get top-level blog posts.

   * Handle pagination for both. This is the most "v2-native" approach but might miss other root-level content types.

2. **Use v1 Descendant Endpoint (Potential Fallback):** The v1 endpoint `GET /rest/api/content/{contentId}/descendant` can retrieve all descendants of a given content item.\[3\]

   * First, identify the root content ID(s) for the space (e.g., the space homepage ID).

   * Then, call `/rest/api/content/{rootContentId}/descendant`. This endpoint might return various content types, potentially including newer ones like whiteboards or databases, although this is not explicitly documented and relies on potentially deprecated functionality.\[3\] Using `expand=page,blogpost,attachment,...` can enrich the results. However, this approach depends on a v1 endpoint and there have been reports of reliability issues, such as skipped pages.\[40\]

3. **Recursive Child Fetching (v2):**

   * Start by fetching the root pages and blog posts using Strategy 1.

   * For each page ID obtained, recursively call `GET /wiki/api/v2/pages/{id}/children` to get its direct children.

   * Repeat for blog posts using `GET /wiki/api/v2/blogposts/{id}/children`.

   * This approach uses v2 endpoints but can lead to a very large number of API calls, especially for deeply nested structures or spaces with many pages, increasing the risk of hitting rate limits and slowing down the crawl significantly.

The choice of strategy depends on the requirement for completeness versus adherence to the v2 API. Strategy 1 is the safest in terms of using documented v2 endpoints but might be incomplete. Strategy 2 potentially offers more completeness but relies on v1. Strategy 3 uses v2 but can be inefficient. A hybrid approach might be necessary, potentially using Strategy 1 for pages/blogposts and supplementing with targeted v1 calls or specific v2 endpoints if other content types (like attachments, comments) are not fetched via expansion later. The challenge of reliably discovering *all* content types (especially newer ones like databases, whiteboards \[3\]) using only documented v2 APIs remains a significant hurdle. Developers may need to accept limitations based on the current API capabilities.

### Handling Hierarchy (Tree Location)

Understanding the document tree location (parent-child relationships) is crucial for many indexing scenarios.

* The `parentId` field in the `Page` object \[22\] directly indicates the immediate parent. Storing this allows reconstructing the tree structure.

* The `GET /wiki/api/v2/pages/{id}/ancestors` endpoint provides the full path from a specific page up to the root.\[23\] This is useful for quickly determining the location of a known page without traversing the entire tree downwards.

* Recursive fetching (Strategy 3 above) inherently discovers the hierarchy level by level.

* Performance can degrade significantly in spaces with very deep nesting or pages with thousands of direct children when using recursive strategies.

### Java Crawler Structure Example

A conceptual structure for a Java crawler might look like this:
```java
    // Assume ConfluenceApiClient, Space, SpaceCollection, ConfluenceEntity, ApiException etc. are defined or generated
    import java.util.List;
    import java.util.ArrayList;
    import java.util.Collections;

    public class ConfluenceCrawler {

        private final ConfluenceApiClient apiClient; // Your generated/implemented API client

        public ConfluenceCrawler(ConfluenceApiClient apiClient) {
            this.apiClient = apiClient;
        }

        public void crawlSite() {
            List<Space> spaces = listAllSpaces();
            for (Space space : spaces) {
                System.out.println("Crawling Space: " + space.getKey());
                List<String> rootContentIds = findRootContentIds(space.getId()); // Implement logic
                for (String contentId : rootContentIds) {
                    crawlContentTree(contentId);
                }
                // Or use space-level content fetching if preferred
                // List<Content> spaceContent = getContentForSpace(space.getId());
                // processContentList(spaceContent);
            }
        }

        private List<Space> listAllSpaces() {
            List<Space> allSpaces = new ArrayList<>();
            String nextUrl = apiClient.getBaseUrl() + "/wiki/api/v2/spaces?limit=50"; // Adjust limit
            while (nextUrl!= null) {
                ApiResponse<SpaceCollection> response = apiClient.executeGet(nextUrl, SpaceCollection.class);
                if (response.isSuccess()) {
                    allSpaces.addAll(response.getBody().getResults());
                    nextUrl = response.getNextLink(); // Extract next link from response
                } else {
                    // Handle error (log, retry, etc.)
                    System.err.println("Error listing spaces: " + response.getStatusCode());
                    break;
                }
                // Add delay
            }
            return allSpaces;
        }

         private void crawlContentTree(String contentId) {
            // 1. Fetch and process the current content item
            ConfluenceEntity entity = processContent(contentId);
            if (entity == null) return; // Skip if processing failed

            // 2. Fetch children (example for pages)
            if ("page".equals(entity.getType())) {
                 List<String> childIds = getChildPageIds(contentId);
                 for (String childId : childIds) {
                     crawlContentTree(childId); // Recursive call
                 }
            }
            // Add logic for other content types if needed
        }


        private ConfluenceEntity processContent(String contentId) {
            try {
                // Fetch full entity data using methods from Section V
                ConfluenceEntity entity = apiClient.getComprehensiveEntityData(contentId);

                // Send to indexer/saver
                // indexer.indexEntity(entity); // Placeholder

                return entity;
            } catch (ApiException e) {
                System.err.println("Error processing content ID " + contentId + ": " + e.getMessage());
                // Handle specific errors (404, 403, etc.)
                return null;
            }
        }

        // Placeholder methods for API interactions (to be implemented using the chosen client)
        private List<String> findRootContentIds(String spaceId) { /*... API call... */ return Collections.emptyList();}
        private List<String> getChildPageIds(String parentId) { /*... API call to /pages/{id}/children... */ return Collections.emptyList();}

        // Inner classes for API response mapping (or use generated ones)
        // private static class Space { String id; String key; /*... */ }
        // private static class SpaceCollection { List<Space> results; /*... */ }
        //... other response/model classes...
    }

    // Assume ConfluenceApiClient handles actual HTTP calls, auth, pagination details, JSON parsing
    // Assume ConfluenceEntity is the data model from Section V

```
This structure highlights the need for robust error handling (network issues, API errors like 4xx/5xx, rate limiting) and careful management of recursion or iteration state. For large sites, consider parallel processing across spaces or content branches, ensuring thread safety and proper rate limit handling.

## V. Extracting Comprehensive Entity Data

The core of the indexing process involves fetching detailed data for each discovered content entity and mapping it to a unified Java object.

### Proposed Java Data Model (`ConfluenceEntity`)

A central Java class can hold all the required information.
```java

    import java.time.OffsetDateTime;
    import java.util.List;
    import java.util.Map;

    public class ConfluenceEntity {
        // Core Metadata
        private String id;
        private String type; // "page", "blogpost", "attachment", "comment"
        private String status; // "current", "trashed", "archived", etc.
        private String title;
        private String spaceId;
        private String uri; // Web UI link
        private String authorId; // Initial creator
        private OffsetDateTime createdAt;
        private OffsetDateTime lastUpdatedAt;
        private String lastUpdatedByAuthorId; // Author of the latest version

        // Hierarchy
        private ParentInfo parentInfo; // Immediate parent
        private List<AncestorInfo> ancestors; // Full path to root

        // Content Body Details
        private BodyContent bodyContent;
        private List<String> extractedLinks; // Links found in body
        private Map<String, List<String>> headings; // e.g., {"h1": ["Heading 1"], "h2":}
        private String chartsAsCsv; // Placeholder for potential CSV chart data (if extractable)

        // Associated Data
        private List<AttachmentInfo> attachments;
        private List<CommentInfo> comments; // Includes footer and inline
        private List<VersionInfo> history;
        private AclInfo acls; // Access Control List details

        // Getters and Setters omitted for brevity

        // --- Inner Helper Classes ---

        public static class ParentInfo {
            String id;
            String type;
            // Getters/Setters
        }

        public static class AncestorInfo {
            String id;
            String type; // Typically "page"
            String title; // Optional: Fetch title if needed
            // Getters/Setters
        }

        public static class BodyContent {
            String storageFormatValue; // XHTML or ADF
            String viewFormatValue; // Rendered HTML (optional)
            String representation; // "storage", "view", "adf"
            // Getters/Setters
        }

        public static class AttachmentInfo {
            String id;
            String title;
            String mediaType;
            long fileSize;
            String downloadUrl;
            String comment;
            String authorId;
            OffsetDateTime createdAt;
            // Getters/Setters
        }

        public static class CommentInfo {
            String id;
            String type; // "footer", "inline"
            String status;
            String title; // Often null/empty for comments
            BodyContent body; // Nested BodyContent for comment body
            String authorId;
            OffsetDateTime createdAt;
            OffsetDateTime lastUpdatedAt;
            int versionNumber;
            // Getters/Setters
        }

        public static class VersionInfo {
            int versionNumber;
            String message; // Edit comment
            String authorId;
            OffsetDateTime createdAt;
            boolean minorEdit;
            // Getters/Setters
        }

        public static class AclInfo {
            // Represents read/update restrictions
            // Structure depends heavily on how data is fetched (e.g., v1 expand)
            List<String> readUsers;
            List<String> readGroups;
            List<String> updateUsers;
            List<String> updateGroups;
            boolean restricted; // Flag indicating if any restrictions apply
            // Getters/Setters
        }
    }

```
This model provides fields for all requested data points, using nested classes for clarity.

### Fetching Core Details

Use the specific content GET endpoints: `GET /wiki/api/v2/pages/{id}` \[22\] or `GET /wiki/api/v2/blogposts/{id}`.

* Map the response fields (`id`, `status`, `title`, `spaceId`, `parentId`, `authorId`, `createdAt`) directly to the `ConfluenceEntity`.\[22\]

* The `lastUpdatedAt` and `lastUpdatedByAuthorId` typically come from the `version` object within the page/blogpost response (specifically `version.createdAt` and `version.authorId`).\[22, 24\]

* Construct the `uri` field using the `_links.webui` value from the response \[22\], prepending the Confluence base URL if necessary.

### Getting & Parsing Body Content

The content body is essential for extracting links, headings, and potentially chart data.

1. **Retrieve Storage Format:** When calling `GET /pages/{id}` (or blogposts), include the query parameter `?body-format=storage`.\[29\] This requests the body content in its raw storage representation (typically XHTML-based for older content, potentially Atlassian Document Format - ADF - for newer content). The storage format is more structured and reliable for parsing than the rendered `view` format. The response will contain the body within `body.storage.value`.\[22\]

2. **Parse with JSoup:** Use the JSoup library (<https://jsoup.org/>) \[41, 42\] to parse the `body.storage.value`. JSoup excels at handling real-world HTML/XHTML, including potentially malformed markup often found in wikis.\[41, 42, 43\]

   * Configure JSoup to parse in XML mode if dealing with XHTML storage format to correctly handle self-closing tags and case sensitivity: `Jsoup.parse(htmlString, baseUrl, Parser.xmlParser())`. You may also need to adjust output settings if writing the parsed content back out, ensuring XHTML compliance.\[44, 45\] Handling specific Confluence entities or CDATA sections might require careful parser setup.\[46\]

3. **Extract Links:** Use JSoup's CSS selector syntax: `doc.select("a[href]")`. Iterate through the resulting `Elements` collection and extract the `abs:href` attribute to get absolute URLs (JSoup resolves relative links based on the `baseUrl` provided during parsing).\[41\] Store these in the `extractedLinks` list.

4. **Extract Headings:** Use selectors like `doc.select("h1, h2, h3, h4, h5, h6")`. Iterate through the results, get the tag name (e.g., `h1`) and the element's text content (`element.text()`).\[41\] Populate the `headings` map.

**Table: Common JSoup Selectors for Confluence Elements**

| Element Type | JSoup Selector | Notes |
| :------------------- | :---------------------------------- | :-------------------------------------------------------------------- |
| Links | `a[href]` | Use `element.attr("abs:href")` for absolute URL. |
| Headings (H1-H6) | `h1`, `h2`, `h3`, `h4`, `h5`, `h6` | Use `element.tagName()` for level, `element.text()` for content. |
| Tables | `table` | Iterate `tr` (rows), then `th` (header cells) or `td` (data cells). |
| List Items | `li` | Found within `ul` (unordered) or `ol` (ordered) lists. |
| Images | `img[src]` | Use `element.attr("abs:src")` for absolute URL. |
| Confluence Macros | `ac:structured-macro` | Parse `ac:name` attribute for macro type, `ac:parameter` for params. |
| User Mentions | `a.confluence-userlink` | Extract `data-username` or `data-accountid` attributes. |
| Expand Macro | `div.expand-container` | Contains `div.expand-title` and `div.expand-content`. |
| Code Block Macro | `div.codehilite` or `pre.code` | Extract text content. Check `ac:name="code"` macro parameters. |

*(Note: Specific selectors might vary slightly based on Confluence version, theme, or specific macro implementations. Inspecting the storage format directly is recommended.)*

### Handling Charts (Challenges)

Extracting chart data *as CSV* directly via the standard REST API is generally **not feasible**. Charts are typically rendered by macros that interpret data defined elsewhere (e.g., within the macro body or from an attached file).

* **Option 1 (Table-Based Charts):** If a chart macro visualizes data from a standard `<table>` element within the page's storage format, use JSoup to find that specific table (e.g., via an ID, CSS class, or proximity to the chart macro). Iterate through its `<tr>` (rows) and `<td>`/`<th>` (cells) elements, extracting text content and formatting it as CSV. This requires reliable identification of the source table.

* **Option 2 (Macro Data Parsing):** Examine the storage format (`body.storage.value`) for the chart macro itself (e.g., `<ac:structured-macro ac:name="chart">...</ac:structured-macro>`). The data might be embedded within `<ac:parameter>` tags or inside the macro body (`<ac:rich-text-body>`), potentially as an escaped table or JSON structure.\[47\] Parsing this requires understanding the specific macro's storage format, which can vary and be complex.

* **Option 3 (Third-Party APIs):** Some marketplace charting apps might expose their own APIs for data access, but this falls outside the standard Confluence API.

Set expectations: Extracting chart data reliably as CSV is a complex, often macro-specific task that may require significant custom parsing logic beyond standard API calls.

### Getting Hierarchy (Ancestors)

Use `GET /wiki/api/v2/pages/{id}/ancestors`.\[23\] The response provides a list of ancestor objects, each containing an `id`.\[23\] Populate the `ancestors` list in the `ConfluenceEntity` model. Handle pagination if the ancestor chain is extremely long, though this is less common than paginating children or comments.\[23\]

### Fetching Attachments (List & Links)

Use `GET /wiki/api/v2/pages/{id}/attachments` \[25\] (or equivalent for blog posts).

* Iterate through the results using cursor pagination.\[25\]

* For each attachment object in the `results` array, extract relevant fields: `id`, `title`, `mediaType`, `fileSize`, `comment`, `version.authorId`, `version.createdAt`.\[25\]

* Crucially, extract the download link from `_links.download`.\[25\]

* Create `AttachmentInfo` objects and add them to the `attachments` list in `ConfluenceEntity`.

### Fetching Comments (List & Details)

Confluence v2 separates comments by location. Use:

* `GET /wiki/api/v2/pages/{id}/footer-comments` for page-level comments.\[26\]

* `GET /wiki/api/v2/pages/{id}/inline-comments` for comments attached to specific text within the page.\[26\]

* (Equivalent endpoints exist for blog posts).

* Iterate through results using cursor pagination for each endpoint.\[26\]

* For each comment object, extract: `id`, `status`, `title`, `body` (request `?body-format=storage` if parsing is needed), `version.authorId`, `version.createdAt`, `version.number`.\[26\] Determine comment type ("footer" or "inline") based on the endpoint used.

* Create `CommentInfo` objects and add them to the `comments` list.

### Fetching History (Versions)

Use `GET /wiki/api/v2/pages/{id}/versions` \[24\] (or equivalent for blog posts).

* Iterate through results using cursor pagination.\[24\]

* For each version object, extract: `number`, `message` (edit summary), `authorId`, `createdAt`, `minorEdit`.\[24\]

* Create `VersionInfo` objects and add them to the `history` list.

### Fetching Permissions/ACLs (API Limitations)

Retrieving detailed page-level Access Control Lists (ACLs) – who can read or update specific content – is a **significant challenge** with the current public Confluence Cloud REST API v2.

* **The Gap:** There is **no documented, stable v2 endpoint** equivalent to `GET /pages/{id}/restrictions` that returns the specific users and groups with read/update permissions.\[3, 12, 48\] The existing v2 page endpoints \[1, 20\] do not include this data, and community forums and issue trackers confirm this limitation.\[12, 48\] A feature request exists for such an endpoint.\[12\]

* **v1 Fallback (Risky):** The v1 API offered a way to get this information using the `expand` parameter: `GET /rest/api/content/{id}?expand=restrictions.read.restrictions.user,restrictions.read.restrictions.group,restrictions.update.restrictions.user,restrictions.update.restrictions.group`.\[15\] This endpoint *might* still function in Cloud, but relying on v1 APIs carries the risk of future deprecation or changes without notice.

* **Workarounds/Alternatives (Limited):**

  * *Space Permissions:* Fetch space-level permissions using `GET /wiki/api/v2/spaces/{id}/permissions`. This shows default permissions but misses page-specific overrides.

  * *Check Restriction Existence (v1):* The v1 endpoint `GET /rest/api/content/{id}/restriction` might indicate *if* restrictions exist but may not list the specific users/groups comprehensively.

  * *Unsupported Methods:* Using internal APIs or simulating UI actions via Admin Keys \[12\] is highly discouraged, unsupported, and prone to breaking.

  * *Webhooks:* The `content_permissions_updated` webhook \[49\] signals a change occurred, but the payload likely lacks the full ACL state, necessitating a follow-up call (which currently has no v2 target). The `space_permissions_updated` webhook has also been reported as potentially unreliable.\[50\]

* **Recommendation:** Clearly acknowledge this limitation. The most practical, albeit risky, approach for obtaining detailed ACLs currently is to attempt using the **`v1 GET /rest/api/content/{id}?expand=restrictions...`** endpoint. If strict adherence to v2 is required, or if the v1 endpoint proves unreliable/is removed, then detailed ACL information must be omitted from the indexed data until Atlassian provides a supported v2 solution. The `AclInfo` object in the Java model should reflect this uncertainty, perhaps storing only a boolean `restricted` flag if only the existence (but not details) of restrictions can be determined reliably. The absence of a reliable v2 ACL endpoint is a critical gap for security-trimmed search implementations.

**Table: API Endpoints for Requested Data Points (Cloud v1/v2)**

| Data Point | Confluence Cloud v2 Endpoint/Method | v1 Endpoint/Method (Fallback/Alternative) | Key Parameters | Notes |
| :---------------------- | :--------------------------------------------------- | :--------------------------------------------------------------------------- | :-------------------------------------------------------------------------- | :--------------------------------------------------------------------------------------------------- |
| Document Tree Location | `parentId` field in GET `/pages/{id}` \[22\]; GET `/pages/{id}/ancestors` \[23\] | `ancestors` in `expand` on GET `/content/{id}` \[15\] | `id` | Build tree from parent IDs or fetch ancestors. |
| Document ID | `id` field in GET `/pages/{id}` \[22\] | `id` field in GET `/content/{id}` \[15\] | `id` | Primary identifier. |
| URI | `_links.webui` in GET `/pages/{id}` \[22\] | `_links.webui` in GET `/content/{id}` \[15\] | `id` | Construct full URL from base + webui path. |
| Links (in body) | Parse `body.storage.value` from GET `/pages/{id}` | Parse `body.storage.value` from GET `/content/{id}` | `id`, `body-format=storage` \[29\] | Requires HTML/XHTML parsing (e.g., JSoup \[41\]). |
| Headings (H1, H2 etc) | Parse `body.storage.value` from GET `/pages/{id}` | Parse `body.storage.value` from GET `/content/{id}` | `id`, `body-format=storage` \[29\] | Requires HTML/XHTML parsing (e.g., JSoup \[41\]). |
| Charts as CSV | *Not directly supported* | *Not directly supported* | `id`, `body-format=storage` | Requires custom parsing of tables or macro data within storage format. Highly complex. |
| Title | `title` field in GET `/pages/{id}` \[22\] | `title` field in GET `/content/{id}` \[15\] | `id` | |
| Author (Creator) | `authorId` field in GET `/pages/{id}` \[22\] | `history.createdBy` in `expand` on GET `/content/{id}` \[15\] | `id` | Account ID of the initial creator. |
| Last Updated (Date) | `version.createdAt` in GET `/pages/{id}` \[22\] | `version.when` in `expand` on GET `/content/{id}` \[15\] | `id` | Timestamp of the latest version. |
| Last Updated (Author) | `version.authorId` in GET `/pages/{id}` \[22\] | `version.by` in `expand` on GET `/content/{id}` \[15\] | `id` | Account ID of the author of the latest version. |
| History (Versions) | GET `/pages/{id}/versions` \[24\] | `history` in `expand` on GET `/content/{id}` \[15\] | `id`, `cursor`, `limit` | Paginated list of version details. |
| ACLs (Permissions) | **No stable v2 endpoint** \[12, 48\] | GET `/content/{id}?expand=restrictions...` \[15\] | `id`, `expand` | **Major Limitation in v2.** v1 expand is the primary (risky) method for detailed user/group lists. |
| Comments | GET `/pages/{id}/footer-comments` \[26\], GET `/pages/{id}/inline-comments` \[26\] | `children.comment` in `expand` on GET `/content/{id}` \[15\] | `id`, `cursor`, `limit`, `body-format` | v2 separates footer/inline. Paginated lists. |
| Attachments | GET `/pages/{id}/attachments` \[25\] | `children.attachment` in `expand` on GET `/content/{id}` \[15\] | `id`, `cursor`, `limit` | Paginated list of attachment metadata and download links. |

### Populating the Java Model (Code Examples)

Here’s a conceptual snippet using a hypothetical generated client (`apiClient`) to fetch attachments and populate the model:
```java
    import java.util.ArrayList;
    //... other imports

    public ConfluenceEntity populateAttachments(ConfluenceEntity entity) {
        List<AttachmentInfo> attachments = new ArrayList<>();
        String nextPageUrl = apiClient.getBaseUrl() + "/wiki/api/v2/pages/" + entity.getId() + "/attachments?limit=100";

        while (nextPageUrl!= null) {
            try {
                // Assume apiClient.getAttachments returns a response object
                // containing List<AttachmentResponse> results and the next link
                ApiResponse<AttachmentCollection> response = apiClient.getAttachments(nextPageUrl); // Pass full URL

                if (response.isSuccess() && response.getBody()!= null) {
                    for (AttachmentResponse rawAttachment : response.getBody().getResults()) {
                        AttachmentInfo info = new AttachmentInfo();
                        info.setId(rawAttachment.getId());
                        info.setTitle(rawAttachment.getTitle());
                        info.setMediaType(rawAttachment.getMediaType());
                        info.setFileSize(rawAttachment.getFileSize());
                        info.setDownloadUrl(rawAttachment.getLinks().getDownload()); // Adjust based on actual generated model
                        if (rawAttachment.getVersion()!= null) {
                            info.setAuthorId(rawAttachment.getVersion().getAuthorId());
                            info.setCreatedAt(OffsetDateTime.parse(rawAttachment.getVersion().getCreatedAt())); // Parse date
                        }
                        info.setComment(rawAttachment.getComment());
                        attachments.add(info);
                    }
                    nextPageUrl = response.getNextLink(); // Get next link from response
                } else {
                    System.err.println("Failed to fetch attachments for page " + entity.getId() + ": Status " + response.getStatusCode());
                    nextPageUrl = null; // Stop pagination on error
                }
            } catch (ApiException e) {
                System.err.println("API Exception fetching attachments for page " + entity.getId() + ": " + e.getMessage());
                nextPageUrl = null; // Stop pagination on exception
            }
             // Add delay
             // try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        entity.setAttachments(attachments);
        return entity;
    }

    // Assume AttachmentResponse, AttachmentCollection, ApiResponse are part of the generated client or mapped manually
    // Assume AttachmentInfo is the inner class within ConfluenceEntity
```

Similar logic would apply to fetching comments, history, and ancestors, handling pagination and mapping response data to the corresponding `Info` objects within the `ConfluenceEntity`.

## VI. Real-time Updates via Webhooks

Webhooks enable Confluence Cloud to send real-time notifications to an external application when specific events occur, eliminating the need for constant polling.\[16, 17, 51, 52\]

### Webhook Fundamentals

When a registered event happens (e.g., a page is updated), Confluence sends an HTTP POST request containing a JSON payload to a predefined URL endpoint hosted by the receiving application.\[17\] While generally reliable, webhook delivery isn't guaranteed; network issues or downtime on the receiving end can cause events to be missed.\[17\] Confluence may attempt retries for failed deliveries.\[53\] Webhooks are significantly more efficient than polling for changes.\[16\]

### Registration

Webhooks can be registered in two main ways:
1.  **Atlassian Connect Apps:** Declare webhooks within the app's descriptor file (`atlassian-connect.json`). Specify the `event` to listen for and the relative `url` within the app that will handle the POST request.\[17, 51\] The Atlassian Connect Express (ACE) framework simplifies handling authentication (JWT) and routing.\[51\]
2.  **REST API / UI:** For standalone integrations, webhooks can often be registered via the Confluence administration UI \[54, 55\] or programmatically using a REST API endpoint (e.g., `/rest/webhooks/1.0/webhook` might be the Cloud equivalent of the Server `/rest/api/webhooks` endpoint \[52, 53\]). This requires appropriate administrative permissions. Security can be enhanced by configuring a secret token during registration, which is then used to generate an HMAC signature (e.g., `X-Hub-Signature` header) for validating payload authenticity.\[53\]

### Relevant Event Types

Based on the user query for tracking Create, Update, Delete, Move, and Permission changes, the following Confluence Cloud webhook events are relevant (event names confirmed from \[49\]):
* **Page Events:** `page_created`, `page_updated`, `page_deleted` (or `page_removed`/`page_trashed`), `page_moved`, `page_restored`, `page_archived`, `page_unarchived`. \[17, 49, 56\]
* **Comment Events:** `comment_created`, `comment_updated`, `comment_deleted` (or `comment_removed`). \[17, 49\]
* **Attachment Events:** `attachment_created`, `attachment_deleted` (or `attachment_removed`), `attachment_updated`, `attachment_restored`, `attachment_trashed`, `attachment_archived`, `attachment_unarchived`. \[17, 49, 53\]
* **Permission Events:** `content_permissions_updated`, `space_permissions_updated`. \[49\] (Note: `space_permissions_updated` has reported issues \[50\]).
* **Label Events:** `label_added`, `label_created`, `label_deleted`, `label_removed`. \[49\]

It's advisable to use a tool like the Connect Inspector \[17\] or set up a simple test endpoint to receive and log webhook payloads to confirm exact event names and their corresponding JSON structures.

### Payload Analysis

Webhook payloads are delivered as JSON in the body of the HTTP POST request.\[17\] A typical payload includes:
* `timestamp`: Time of the event.
* User Information: Details about the user who triggered the event (e.g., `userAccountId`).\[17, 49\]
* Entity Object: A JSON object representing the entity involved (e.g., a `page` object, `comment` object, `attachment` object).\[17, 49\]
* Event Context: The specific event type is often implied by the webhook registration. Some payloads might contain explicit fields indicating the trigger (e.g., `page_updated` payloads might have an `updateTrigger` field like `link_refactoring` \[57\]).

**Extracting Key Information:**
* **Content ID:** Found within the entity object, usually as the `id` field (e.g., `payload.page.id`, `payload.comment.id`). Note the temporary existence of `idAsString` fields for compatibility with large IDs migrated from Server.\[49\]
* **Event Type:** Primarily determined by which event the webhook was registered for. If a single endpoint handles multiple events, analyze the payload structure (e.g., presence of `page` vs. `comment` object) or look for specific trigger fields if available.

**Sample Payloads \[17, 49, 57\]:**

* **`page_created` / `page_updated`:**
    ```json
    {
      "timestamp": 1678886400000,
      "userAccountId": "accountid:...",
      "page": {
        "id": "12345678", // or idAsString
        "spaceId": "87654",
        "title": "Updated Page Title",
        "version": { "number": 5,... },
        //... other minimal page details
      },
      "updateTrigger": "user_edit" // Example for page_updated
      // webhookEvent: "page_updated" // May not always be present
    }
    ```
* **`page_deleted` (Conceptual):**
    ```json
    {
      "timestamp": 1678886500000,
      "userAccountId": "accountid:...",
      "page": { // Contains details of the page *before* deletion
        "id": "12345678",
        "spaceId": "87654",
        "title": "Page To Be Deleted",
        //...
      }
      // webhookEvent: "page_deleted"
    }
    ```
* **`comment_created`:**
    ```json
    {
      "timestamp": 1678886600000,
      "userAccountId": "accountid:...",
      "comment": {
        "id": "98765432", // or idAsString
        "parent": { "id": "12345678" }, // ID of the page/blogpost
        //... other minimal comment details
      }
      // webhookEvent: "comment_created"
    }
    ```
* **`attachment_created` (Conceptual - often lacks issue/page context \[58\]):**
    ```json
    {
      "timestamp": 1678886700000,
      "userAccountId": "accountid:...",
      "attachment": {
        "id": "55544433", // or idAsString
        "title": "document.pdf",
        "mediaType": "application/pdf",
        // Might lack direct page/blogpost ID in some cases
        //... other minimal attachment details
      }
      // webhookEvent: "attachment_created"
    }
    ```

A critical point is that webhook payloads are typically **lightweight notifications**, containing identifiers and basic context, but **not the full entity data**.[57, 58] Receiving a `page_updated` event means the page changed, but to get the *actual updated content*, title, version, etc., for indexing, the receiving application must make a subsequent `GET /pages/{id}` API call using the ID from the webhook payload. The webhook serves as a trigger, not a complete data source.

**Table: Key Information in Common Confluence Webhook Payloads**

| Event Type | Typical Payload Root Object | How to get Content ID | How to get Event Type | Other Useful Info |
| :-------------------------- | :-------------------------- | :--------------------------- | :--------------------------- | :--------------------------------------------------- |
| `page_created` | `page` | `page.id` / `page.idAsString` | Implicit from registration | `timestamp`, `userAccountId`, `page.spaceId` |
| `page_updated` | `page` | `page.id` / `page.idAsString` | Implicit, `updateTrigger`? [57] | `timestamp`, `userAccountId` |
| `page_deleted` | `page` | `page.id` / `page.idAsString` | Implicit | `timestamp`, `userAccountId` |
| `page_moved` | `page` | `page.id` / `page.idAsString` | Implicit | `timestamp`, `userAccountId`, old/new parent info? |
| `comment_created` | `comment` | `comment.id` / `comment.idAsString` | Implicit | `timestamp`, `userAccountId`, `comment.parent.id` |
| `comment_updated` | `comment` | `comment.id` / `comment.idAsString` | Implicit | `timestamp`, `userAccountId` (Missing trigger? [57]) |
| `comment_deleted` | `comment` | `comment.id` / `comment.idAsString` | Implicit | `timestamp`, `userAccountId` |
| `attachment_created` | `attachment` | `attachment.id` / `attachment.idAsString` | Implicit | `timestamp`, `userAccountId` (Page ID may be missing [58]) |
| `attachment_deleted` | `attachment` | `attachment.id` / `attachment.idAsString` | Implicit | `timestamp`, `userAccountId` |
| `content_permissions_updated` | `content`? `page`? | `content.id`? | Implicit | `timestamp`, `userAccountId` (Payload details needed) |

*(Note: Payload structure can vary. Always test or use Connect Inspector [17] for definitive structures.)*

### Handling Webhooks in Java

1.  **Create Endpoint:** Implement an HTTP endpoint (e.g., using Spring Boot `@RestController` with `@PostMapping`, or JAX-RS) listening at the URL registered for the webhook.
2.  **Validate Request:** If using secrets, validate the `X-Hub-Signature` HMAC hash.[53] If using Connect/ACE, the framework typically handles JWT validation.[17] Reject unauthenticated/unvalidated requests.
3.  **Parse Payload:** Read the JSON body of the POST request and parse it into a Java object (e.g., using Jackson/Gson) or a generic map structure.
4.  **Extract Data:** Identify the entity ID (`id`/`idAsString`) and determine the event type based on registration context or payload analysis.
```java
    // Conceptual Spring Boot Webhook Receiver
    import org.springframework.web.bind.annotation.\*;
    import org.springframework.http.ResponseEntity;
    import org.springframework.http.HttpStatus;
    // Assume KafkaProducerService is injected

    @RestController
    public class ConfluenceWebhookReceiver {

        private final KafkaProducerService kafkaProducer;
        private final String WEBHOOK_SECRET = "your-configured-secret"; // Load securely

        public ConfluenceWebhookReceiver(KafkaProducerService kafkaProducer) {
            this.kafkaProducer = kafkaProducer;
        }

        @PostMapping("/confluence-webhook-endpoint")
        public ResponseEntity<String> handleWebhook(
                @RequestBody String payload, // Raw JSON payload
                @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                @RequestHeader(value = "X-Atlassian-Webhook-Identifier", required = false) String webhookId // For deduplication [53]
                // Add headers for event type if provided by Confluence, e.g., X-Event-Key
               ) {

            // 1. Validate Signature (if using secrets)
            // if (!isValidSignature(payload, signature, WEBHOOK_SECRET)) {
            //     return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            // }

            // 2. Parse Payload (using Jackson/Gson)
            // JsonNode jsonPayload = parseJson(payload); // Placeholder

            // 3. Extract ID and Event Type
            String entityId = extractEntityId(jsonPayload); // Implement logic based on payload structure
            String eventType = determineEventType(jsonPayload /*, headers? */); // Implement logic

            if (entityId!= null && eventType!= null) {
                // 4. Send to Kafka
                kafkaProducer.sendConfluenceEvent(entityId, eventType);
                return ResponseEntity.ok("Event received");
            } else {
                System.err.println("Could not extract ID or event type from payload: " + payload);
                return ResponseEntity.badRequest().body("Could not process payload");
            }
        }

        // Helper methods: isValidSignature, parseJson, extractEntityId, determineEventType
        //...
    }
```

### Mapping to Kafka Events

Construct a simple message for Kafka, containing the essential information for the downstream processor:
* **Entity ID:** The ID extracted from the webhook payload.
* **Event Type:** A standardized string representing the event (e.g., `CONFLUENCE_PAGE_UPDATED`, `CONFLUENCE_COMMENT_DELETED`, `CONfluence_ATTACHMENT_CREATED`, `CONFLUENCE_PERMISSIONS_CHANGED`).

Send this message to the designated Kafka topic.

```java

    // Conceptual Kafka Message Sending
    public class KafkaProducerService {
        // Assume KafkaTemplate is configured and injected

        private KafkaTemplate<String, String> kafkaTemplate;
        private final String KAFKA_TOPIC = "confluence-events";

        public void sendConfluenceEvent(String entityId, String eventType) {
            // Create a simple message structure (e.g., JSON string)
            String message = String.format("{\"entityId\": \"%s\", \"eventType\": \"%s\", \"timestamp\": %d}",
                                         entityId, eventType, System.currentTimeMillis());

            kafkaTemplate.send(KAFKA_TOPIC, entityId, message); // Use entityId as key for partitioning?
            System.out.println("Sent event to Kafka: " + message);
        }
    }
```

### Retrieving Full Data on Event Trigger

The downstream consumer listening to the Kafka topic receives the `{entityId, eventType}` message. Its responsibility is to:
1.  Read the message from Kafka.
2.  Based on the `eventType`:
    * If Create/Update/Move/Permissions Change: Use the `entityId` to call the appropriate Confluence REST API endpoint (`GET /pages/{id}`, `GET /comments/{id}`, etc., using methods from Section V) to fetch the *complete, current state* of that entity.
    * If Delete: Use the `entityId` to signal the indexer to remove the corresponding document.
3.  Pass the fully fetched data (for updates) or the delete instruction to the search indexer for processing.

This two-step process (webhook notification -> API fetch) ensures that the index reflects the actual state of the content at the time the event was processed, leveraging the efficiency of webhooks for triggering and the comprehensive nature of the REST API for data retrieval.

## VII. Conclusion

### Summary

This report outlines a strategy for building a Java-based Confluence Cloud crawler and real-time update handler for indexing purposes. The recommended approach involves:
* Primarily utilizing the **Confluence Cloud REST API v2**, supplemented by v1 endpoints where v2 lacks functionality (notably for detailed content restrictions).
* Employing **API Token-based authentication** for the backend crawler script.
* **Generating a type-safe Java client** using the official Confluence Cloud OpenAPI specification and tools like Swagger Codegen/OpenAPI Generator for robust API interaction.
* Implementing a **crawling strategy** starting with listing spaces, then fetching content (pages, blog posts primarily via v2, potentially using v1 `/descendant` or recursive calls for broader scope), handling v2 cursor pagination diligently.
* Parsing the `body.storage` format (retrieved via `?body-format=storage`) using **JSoup** to extract links, headings, and potentially table-based chart data.
* Populating a comprehensive **`ConfluenceEntity` Java model** with core metadata, hierarchy, parsed body content, attachments, comments, history, and ACLs (acknowledging limitations).
* Registering and handling **webhooks** for relevant events (page/comment/attachment create/update/delete, permissions changes, moves).
* Extracting the **entity ID and event type** from webhook payloads and sending them as messages to **Kafka**.
* Designing the downstream Kafka consumer to **fetch the full entity data via REST API** upon receiving an event, before updating the search index.

### Key Considerations

Developers undertaking this task should be mindful of several critical factors:

* **API Rate Limiting:** Confluence Cloud enforces rate limits on API calls. The crawler must implement respectful delays between requests and robust backoff-and-retry strategies for transient errors (like 429 Too Many Requests or 5xx server errors) to avoid being blocked. Monitor API usage through available dashboards or logs.
* **Error Handling:** Comprehensive error handling is essential. Anticipate network timeouts, DNS issues, various HTTP error codes (401, 403, 404, 500, 503), unexpected JSON structures, and parsing failures. The crawler should be resilient and log errors effectively without halting unnecessarily.
* **API Versioning & Changes:** The Confluence Cloud API landscape is dynamic, particularly with the ongoing development of v2.[2] Monitor Atlassian's developer documentation and announcements for API changes, deprecations, and new feature introductions. Be prepared to regenerate the API client [37] and adapt the code as the API evolves. The current gaps in v2 (e.g., ACLs, comprehensive content type fetching) may be addressed in the future.[3, 12]
* **Data Consistency:** A full site crawl can be lengthy. Content may be updated or deleted *during* the crawl. While webhooks capture changes occurring *after* the listener is active, there's a potential window for missed updates between the start of the initial crawl and the activation of real-time event handling. Strategies to mitigate this include periodic full or partial re-crawls, comparing `lastUpdatedAt` timestamps, or designing the initial crawl to enable the webhook listener as early as possible.
* **Permissions Complexity (ACLs):** The current inability to reliably fetch detailed, page-level user/group restrictions via the public v2 API remains the most significant technical hurdle.[3, 12, 48] Relying on the v1 `expand=restrictions...` parameter is the most direct workaround but carries risks. Solutions requiring security-trimmed search results must carefully consider this limitation and decide whether to accept the risk of using v1, omit detailed ACLs, or implement alternative (potentially less precise) permission checks based on space-level permissions.

### Final Recommendations

Begin implementation by focusing on the core content types (pages, blog posts) using the documented v2 API endpoints. Leverage OpenAPI client generation for efficiency and maintainability. Prioritize robust pagination, rate limiting, and error handling from the outset. Use JSoup for parsing the storage format. For real-time updates, implement webhook handling to capture essential events and trigger subsequent full data fetches via the API before indexing. Acknowledge the current limitations, especially regarding detailed ACL retrieval via v2, and choose the appropriate mitigation strategy based on project requirements and risk tolerance. Continuous monitoring of Atlassian API updates will be crucial for long-term maintenance.

