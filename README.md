# Search API

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java](https://img.shields.io/badge/java-17%2B-blue.svg)
![gRPC](https://img.shields.io/badge/grpc-1.42.1-blue.svg)

## Overview

The **Search API** is a high-performance gRPC-based service designed exclusively for executing advanced search queries over indexed documents in Apache Solr. This API leverages semantic vector embeddings to enhance search accuracy and relevance, making it ideal for applications requiring intelligent and context-aware document retrieval.

**Note:** Document indexing is handled by a separate service. This API focuses solely on search functionalities.

## Features

- **gRPC-Based Communication:** High-performance, low-latency communication using gRPC.
- **Semantic Vector Search:** Utilize vector embeddings for semantic understanding and accurate search results.
- **Keyword Search:** Traditional keyword-based search capabilities.
- **Caching Mechanism:** In-memory caching of vector embeddings to optimize performance and reduce redundant calculations, with persistence to disk.
- **Flexible Search Strategies:** Combine semantic and keyword search strategies using logical operators.
- **Highlighting:** Highlight matched terms and snippets in search results.
- **Faceted Search:** Advanced faceting options for refined search results.

## Technology Stack

- **Programming Language:** Java 11+
- **Framework:** Micronaut
- **Search Engine:** Apache Solr (Version 9.7.0)
- **Communication Protocol:** gRPC
- **Containerization:** Docker
- **Testing:** JUnit 5, Testcontainers
- **Build Tool:** Maven

## Getting Started

### Prerequisites

- **Java:** Ensure Java 17 or higher is installed.
- **Docker:** Required for running containerized services.
- **Maven:** For building the project.

### Installation

1. **Clone the Repository:**
   ```bash
   git clone https://github.com/krickert/search-api.git
   cd search-api
   ./mvnw install package

# Search API Requirements

## Overview
The Search API is designed to provide a comprehensive search functionality over indexed documents in a Solr-based search engine. The API will support both keyword and semantic searches and will integrate with an Embedding Service for vector-based querying.

## Functional Requirements

### 1. Document Indexing
- **Inline Vectors:** Support indexing where the vector representation is included within the same document.
- **Embedded Documents:** Allow chunking of fields and embedding documents within the main document.
- **Outside Join:** Index chunked fields into a new collection for advanced queries.

### 2. Search Functionality
The API will support various query types to enhance search capabilities:
- **Semantic Matching:** Utilize vector-based search for retrieving documents that are semantically similar to the query.
- **Keyword Matching:** Perform traditional keyword-based search queries.
- **Keyword with Semantic Boost:** Combine keyword search with an additional boost from semantic vectors.
- **Semantic with Keyword Boost:** Boost semantic search results using keyword matching.

### 3. Query Configuration
- **Dynamic Configuration:** Allow dynamic configuration of Solr collections and query parameters.
- **Vector Configuration:** Support inline, embedded, or external collection configurations for vector fields.

### 4. Query Execution
- **Support for Filter Queries:** Implement filtering (`fq`) for refined search results.
- **Highlighting:** Use Solr's highlighting capabilities to highlight matched snippets in search results.
- **Matched Snippets:** Return matched snippets from either chunked or inline text.

### 5. Vector Embedding
- **Embedding Service Integration:** Integrate with an `EmbeddingService` to generate embeddings for text.
- **GRPC Protocol:** Use gRPC for communication with the embedding service.
- **Caching:** Implement caching for embeddings to minimize redundant calculations. The cache should:
    - Be in-memory and shared between tests.
    - Persist to disk at the end of the execution.
    - Use document IDs as keys for vector retrieval.

### 6. Performance
- **Scalability:** Ensure the API can handle a large number of requests and documents efficiently.
- **Asynchronous Processing:** Support asynchronous processing for queries and embedding generation.

## Non-Functional Requirements

### 1. Security
- **Authentication:** Implement secure authentication mechanisms for accessing the API.
- **Data Protection:** Ensure data integrity and protection during transmission.

### 2. Logging and Monitoring
- **Logging:** Implement logging for debugging and monitoring purposes.
- **Metrics:** Collect metrics for API usage, performance, and error rates.

### 3. Documentation
- **API Documentation:** Provide clear and comprehensive documentation for all API endpoints and usage examples.
- **Developer Guides:** Include developer guides for setup, configuration, and integration.

## Technical Requirements

### 1. Technology Stack
- **Search Engine:** Apache Solr (Version 9.7.0)
- **Programming Language:** Java
- **Frameworks:** Micronaut for building the API.
- **Containerization:** Use Docker for service containerization.
- **gRPC:** For communication with the embedding service.

### 2. Environment Setup
- **Development Environment:** Ensure a local development setup for testing and debugging.
- **Test Containers:** Utilize Testcontainers for integration testing with Solr.

## Testing Requirements

### 1. Unit Testing
- **Component Tests:** Ensure that each component of the API is unit tested for functionality.

### 2. Integration Testing
- **End-to-End Tests:** Implement integration tests that cover end-to-end scenarios, including indexing, querying, and caching.

### 3. Performance Testing
- **Load Testing:** Conduct load testing to ensure the API can handle expected traffic.

## Future Enhancements
- **User Feedback Integration:** Gather user feedback to improve search relevance and API usability.
- **Advanced Query Features:** Explore advanced query features like faceting, sorting, and recommendation systems.

## Conclusion
This document outlines the comprehensive requirements for the development of the Search API. The focus is on delivering a robust, efficient, and user-friendly API that leverages the power of Solr and embedding technologies for enhanced search capabilities.

Below is a set of requirements 

## Micronaut 4.6.2 Documentation

- [User Guide](https://docs.micronaut.io/4.6.2/guide/index.html)
- [API Reference](https://docs.micronaut.io/4.6.2/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/4.6.2/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
---

- [Micronaut Maven Plugin documentation](https://micronaut-projects.github.io/micronaut-maven-plugin/latest/)
## Feature hamcrest documentation

- [https://hamcrest.org/JavaHamcrest/](https://hamcrest.org/JavaHamcrest/)


## Feature openapi-explorer documentation

- [Micronaut OpenAPI Explorer View documentation](https://micronaut-projects.github.io/micronaut-openapi/latest/guide/#openapiExplorer)

- [https://github.com/Authress-Engineering/openapi-explorer](https://github.com/Authress-Engineering/openapi-explorer)


## Feature micronaut-aot documentation

- [Micronaut AOT documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)


## Feature test-resources documentation

- [Micronaut Test Resources documentation](https://micronaut-projects.github.io/micronaut-test-resources/latest/guide/)


## Feature testcontainers documentation

- [https://www.testcontainers.org/](https://www.testcontainers.org/)


## Feature validation documentation

- [Micronaut Validation documentation](https://micronaut-projects.github.io/micronaut-validation/latest/guide/)


## Feature annotation-api documentation

- [https://jakarta.ee/specifications/annotations/](https://jakarta.ee/specifications/annotations/)


## Feature security-oauth2 documentation

- [Micronaut Security OAuth 2.0 documentation](https://micronaut-projects.github.io/micronaut-security/latest/guide/index.html#oauth)


## Feature openapi documentation

- [Micronaut OpenAPI Support documentation](https://micronaut-projects.github.io/micronaut-openapi/latest/guide/index.html)

- [https://www.openapis.org](https://www.openapis.org)


## Feature mockito documentation

- [https://site.mockito.org](https://site.mockito.org)


## Feature http-client documentation

- [Micronaut HTTP Client documentation](https://docs.micronaut.io/latest/guide/index.html#nettyHttpClient)


## Feature management documentation

- [Micronaut Management documentation](https://docs.micronaut.io/latest/guide/index.html#management)


## Feature swagger-ui documentation

- [Micronaut Swagger UI documentation](https://micronaut-projects.github.io/micronaut-openapi/latest/guide/index.html)

- [https://swagger.io/tools/swagger-ui/](https://swagger.io/tools/swagger-ui/)


## Feature awaitility documentation

- [https://github.com/awaitility/awaitility](https://github.com/awaitility/awaitility)


## Feature maven-enforcer-plugin documentation

- [https://maven.apache.org/enforcer/maven-enforcer-plugin/](https://maven.apache.org/enforcer/maven-enforcer-plugin/)


## Feature assertj documentation

- [https://assertj.github.io/doc/](https://assertj.github.io/doc/)


