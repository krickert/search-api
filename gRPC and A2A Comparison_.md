# Building Agent-to-Agent Communication: gRPC vs. Google's A2A Protocol

The rise of AI agents brings exciting possibilities, but how do these agents talk to each other effectively? Agent-to-Agent (A2A) communication is crucial for building complex, collaborative AI systems. Two prominent approaches are emerging: using the high-performance gRPC framework with its native streaming capabilities, and adhering to Google's newer, web-standard-based A2A protocol specification.

This blog post provides a hands-on guide to implementing a simple agent chat system using both approaches. We'll explore:

1.  **A pure gRPC implementation:** Leveraging bidirectional streaming for real-time, efficient communication.
2.  **Adapting to Google's A2A specification:** Using standard web technologies like HTTP, JSON-RPC, and Server-Sent Events (SSE) for broader interoperability.

We'll show code examples in Python and Java (using Micronaut, JDK 21, and Gradle with Kotlin DSL), demonstrating various interoperability scenarios (Python-Python, Python-Java, Java-Java, Java-Python) for both methods. We'll also include Docker setups using `docker-compose` to make running the examples easier.

**Target Audience:** Developers interested in multi-agent systems, gRPC, and the new A2A protocol, who are comfortable with Python or Java and want a practical, hands-on comparison.

**Disclaimer:** While inspired by the *concept* of Agent-to-Agent communication, the first part focuses on a pure gRPC implementation. The second part adapts this concept to align with the *principles* and *technologies* (HTTP/SSE/JSON) of Google's A2A protocol specification, but may not implement every single detail of the formal spec due to its evolving nature and complexity.

---

## Part 1: The gRPC Implementation - Speed and Structure

### 1a. Introduction to gRPC for A2A

gRPC is a high-performance, open-source RPC framework developed by Google. It uses Protocol Buffers (Protobuf) as its Interface Definition Language (IDL) and runs on HTTP/2.

**Why gRPC for A2A?**

* **Performance:** HTTP/2 and Protobuf binary serialization offer significant speed advantages over traditional REST/JSON.
* **Streaming:** gRPC has first-class support for bidirectional streaming, perfect for continuous, two-way conversations between agents.
* **Strong Typing:** Protobuf enforces a strict contract between client and server, reducing integration errors.
* **Polyglot:** Excellent support and code generation for many languages (including Python and Java).

We'll implement a simple chat service where agents can send and receive messages in real-time.

### 1b. Defining the Communication Contract: The `.proto` File

First, we define our service and message structures using Protobuf.

    // src/main/proto/agent_comm.proto
    syntax = "proto3";

    package agentcomm;

    // Option for Java: Generate multiple files, specify package
    option java_multiple_files = true;
    option java_package = "com.example.agentcomm.grpc";
    option java_outer_classname = "AgentCommProto";

    // The agent communication service definition.
    service AgentComm {
      // Bidirectional stream for agent-to-agent messaging
      rpc Communicate (stream AgentMessage) returns (stream AgentMessage);
    }

    // Message structure for agent communication
    message AgentMessage {
      string agent_id = 1;    // Identifier for the sending agent
      string content = 2;     // The message content
      int64 timestamp = 3;    // Unix timestamp of the message
    }

This file defines:

* An `AgentMessage` structure.
* An `AgentComm` service.
* A bidirectional streaming RPC method `Communicate` that accepts a stream of `AgentMessage` and returns a stream of `AgentMessage`.

### 1c. Generating Code

Before writing server/client logic, we need to generate the language-specific code from our `.proto` file.

**Python:**

    # Make sure you have grpcio-tools installed: pip install grpcio-tools
    python -m grpc_tools.protoc -I./src/main/proto --python_out=./python/src --grpc_python_out=./python/src ./src/main/proto/agent_comm.proto

This generates `agent_comm_pb2.py` and `agent_comm_pb2_grpc.py` in `python/src`.

**Java (Micronaut with Gradle):**

The Micronaut Gradle plugin handles this automatically when configured correctly in `build.gradle.kts` (shown later in the Java sections). Running `./gradlew build` will generate the necessary Java source files.

### 1d. Python-to-Python gRPC Example

Let's create a simple Python gRPC server and client.

**Python Server (`python/src/server.py`)**

    import grpc
    import time
    import agent_comm_pb2
    import agent_comm_pb2_grpc
    from concurrent import futures
    import threading

    # Dictionary to hold message queues for active clients (simplified)
    client_queues = {}
    lock = threading.Lock()

    class AgentCommServicer(agent_comm_pb2_grpc.AgentCommServicer):
        def Communicate(self, request_iterator, context):
            peer_id = context.peer() # Identify client (simplified)
            print(f"Client connected: {peer_id}")
            output_queue = futures.Queue()

            with lock:
                client_queues[peer_id] = output_queue

            def process_incoming():
                try:
                    for msg in request_iterator:
                        print(f"Received from {peer_id}: [{msg.agent_id}] {msg.content}")
                        # Broadcast message to other connected clients (simplified)
                        broadcast_msg = agent_comm_pb2.AgentMessage(
                            agent_id=msg.agent_id,
                            content=msg.content,
                            timestamp=msg.timestamp
                        )
                        with lock:
                            for pid, queue in client_queues.items():
                                if pid != peer_id: # Don't send back to sender
                                    try:
                                        queue.put(broadcast_msg)
                                    except Exception as e:
                                        print(f"Error queueing for {pid}: {e}")
                except grpc.RpcError as e:
                     print(f"Client {peer_id} stream error: {e.code()}")
                finally:
                    print(f"Incoming stream ended for {peer_id}")
                    with lock:
                        if peer_id in client_queues:
                             del client_queues[peer_id]
                             print(f"Removed queue for {peer_id}")


            # Start a thread to process incoming messages
            incoming_thread = threading.Thread(target=process_incoming, daemon=True)
            incoming_thread.start()

            # Yield messages from the output queue
            try:
                while True:
                     message = output_queue.get() # Blocks until message available
                     if message is None: # Signal to stop
                         break
                     print(f"Sending to {peer_id}: [{message.agent_id}] {message.content}")
                     yield message
            except GeneratorExit:
                 print(f"Client {peer_id} disconnected (GeneratorExit)")
            finally:
                 print(f"Outgoing stream ended for {peer_id}")
                 incoming_thread.join(timeout=1) # Attempt to join thread cleanly
                 # Ensure queue is removed if not already done by incoming_thread
                 with lock:
                     if peer_id in client_queues:
                         client_queues[peer_id].put(None) # Signal queue reader to exit if stuck
                         del client_queues[peer_id]
                         print(f"Cleaned up queue for {peer_id}")


    def serve():
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        agent_comm_pb2_grpc.add_AgentCommServicer_to_server(AgentCommServicer(), server)
        server.add_insecure_port('[::]:50051')
        print("Starting gRPC server on port 50051...")
        server.start()
        try:
            while True:
                time.sleep(86400) # One day
        except KeyboardInterrupt:
            print("Stopping server...")
            # Graceful shutdown - wait for active RPCs to complete
            server.stop(grace=5.0).wait() # Wait up to 5 seconds
            print("Server stopped.")


    if __name__ == '__main__':
        serve()

**Python Client (`python/src/client.py`)**

    import grpc
    import agent_comm_pb2
    import agent_comm_pb2_grpc
    import time
    import threading
    import random
    import string
    import sys

    AGENT_ID = f"py-client-{''.join(random.choices(string.ascii_lowercase + string.digits, k=4))}"

    def generate_messages():
        """Generator function to send messages periodically."""
        while True:
            try:
                content = input(f"[{AGENT_ID}] Enter message (or 'quit'): ")
                if content.lower() == 'quit':
                    print("Exiting...")
                    return # Stop generation
                message = agent_comm_pb2.AgentMessage(
                    agent_id=AGENT_ID,
                    content=content,
                    timestamp=int(time.time())
                )
                yield message
                time.sleep(0.1) # Small delay to prevent tight loop on input error
            except EOFError:
                 print("\nInput stream closed. Exiting message sender.")
                 return # Stop generation on EOF


    def run(server_address):
        print(f"Connecting to {server_address} as agent {AGENT_ID}")
        channel = grpc.insecure_channel(server_address)
        stub = agent_comm_pb2_grpc.AgentCommStub(channel)

        message_iterator = generate_messages()

        try:
            response_iterator = stub.Communicate(message_iterator)

            # Start a thread to print received messages
            def print_responses():
                try:
                    for response in response_iterator:
                         print(f"\nReceived: [{response.agent_id}] {response.content} (at {response.timestamp})\n[{AGENT_ID}] Enter message (or 'quit'): ", end='')
                except grpc.RpcError as e:
                     print(f"\nError receiving message: {e.code()} - {e.details()}")
                except Exception as e:
                     print(f"\nAn unexpected error occurred while receiving: {e}")
                finally:
                     print("\nResponse stream ended.")

            response_thread = threading.Thread(target=print_responses, daemon=True)
            response_thread.start()

            # Keep the main thread alive while the response thread is running
            # The generate_messages() runs implicitly when the stub call iterates over it.
            # We just need to wait for the response thread to finish (or KeyboardInterrupt)
            response_thread.join()

        except grpc.RpcError as e:
            print(f"Could not connect or communicate: {e.code()} - {e.details()}")
        except Exception as e:
            print(f"An unexpected error occurred: {e}")
        finally:
            channel.close()
            print("Connection closed.")

    if __name__ == '__main__':
        target = "localhost:50051"
        if len(sys.argv) > 1:
            target = sys.argv[1]
        run(target)

**Dockerfile (Python Server)**

    # Dockerfile.python-server
    FROM python:3.11-slim

    WORKDIR /app

    COPY ./python/requirements.txt /app/
    RUN pip install --no-cache-dir -r requirements.txt

    COPY ./python/src /app/src
    COPY ./src/main/proto /app/src/main/proto # Copy proto for potential dynamic use (though pre-generated is better)

    # Generate gRPC code (if not already done)
    RUN python -m grpc_tools.protoc -I./src/main/proto --python_out=./src --grpc_python_out=./src ./src/main/proto/agent_comm.proto

    EXPOSE 50051

    CMD ["python", "src/server.py"]

**Dockerfile (Python Client)**

    # Dockerfile.python-client
    FROM python:3.11-slim

    WORKDIR /app

    COPY ./python/requirements.txt /app/
    RUN pip install --no-cache-dir -r requirements.txt

    COPY ./python/src /app/src
    COPY ./src/main/proto /app/src/main/proto

    # Generate gRPC code
    RUN python -m grpc_tools.protoc -I./src/main/proto --python_out=./src --grpc_python_out=./src ./src/main/proto/agent_comm.proto

    # Default command runs client connecting to 'grpc-server' hostname
    CMD ["python", "src/client.py", "grpc-server:50051"]

**requirements.txt (for Python)**

    grpcio>=1.60.0,<2.0.0
    grpcio-tools>=1.60.0,<2.0.0
    protobuf>=4.25.0,<5.0.0
    futures; python_version < '3.2' # Only needed for older Python, included in stdlib now

**docker-compose.yml (Python-Python)**

    version: '3.8'
    services:
      grpc-server:
        build:
          context: .
          dockerfile: Dockerfile.python-server
        ports:
          - "50051:50051"
        networks:
          - agentnet

      grpc-client-1:
        build:
          context: .
          dockerfile: Dockerfile.python-client
        depends_on:
          - grpc-server
        networks:
          - agentnet
        stdin_open: true # Keep stdin open for input
        tty: true        # Allocate pseudo-TTY for interaction

      grpc-client-2:
        build:
          context: .
          dockerfile: Dockerfile.python-client
        depends_on:
          - grpc-server
        networks:
          - agentnet
        stdin_open: true
        tty: true

    networks:
      agentnet:
        driver: bridge

**To Run (Python-Python):**

1.  Place the files in the correct directory structure.
2.  Run `docker-compose up --build`.
3.  You'll see logs from the server. To interact with the clients, use `docker attach <container_id_or_name>` for `grpc-client-1` and `grpc-client-2` in separate terminals. Type messages and press Enter. Messages sent by one client should appear on the other. Type `quit` to disconnect a client.

---

### 1e. Python Server to Java Client (Micronaut)

Now, let's create a Java client using Micronaut to connect to our Python gRPC server. We'll use the `reactive-grpc` library for a clean, reactive implementation.

**Micronaut Project Setup:**

1.  Go to [Micronaut Launch](https://launch.micronaut.io/).
2.  Choose:
    * Application Type: `Application`
    * Java Version: `21`
    * Language: `Java`
    * Build: `Gradle (Kotlin DSL)`
    * Test: `JUnit`
    * Features: Add `graalvm` (for native image options later), `grpc`, `reactor`, `annotation-api`.
3.  Generate and download the project. Unzip it.
4.  Copy the `src/main/proto/agent_comm.proto` file into the Micronaut project's `src/main/proto` directory.

**build.gradle.kts (Micronaut Project)**

Modify the generated `build.gradle.kts` to include the `reactive-grpc` plugin and dependencies.

    plugins {
        id("com.google.protobuf") version "0.9.4" // Protobuf plugin
        // ... other plugins (application, java, kotlin etc.)
        alias(libs.plugins.micronaut.application) // Or id("io.micronaut.application") version "..."
        alias(libs.plugins.protobuf) // Recommended alias approach
    }

    version = "0.1"
    group = "com.example"

    repositories {
        mavenCentral()
    }

    // Define versions (can also be in libs.versions.toml)
    val protobufVersion = "3.25.1" // Use a recent version
    val grpcVersion = "1.62.2"     // Use a recent version
    val reactiveGrpcVersion = "1.2.4" // Use a recent version

    micronaut {
        version.set(libs.versions.micronaut.platform) // Use version from catalog if available
        runtime("netty")
        testRuntime("junit5")
        processing {
            incremental(true)
            annotations("com.example.*")
        }
        aot { // If GraalVM feature was added
            // Configure AOT options if needed
        }
    }

    dependencies {
        protobuf(libs.managed.protobuf.java.util) // Or "com.google.protobuf:protobuf-java-util:$protobufVersion"

        implementation(libs.managed.grpc.protobuf) // Or "io.grpc:grpc-protobuf:$grpcVersion"
        implementation(libs.managed.grpc.stub)     // Or "io.grpc:grpc-stub:$grpcVersion"
        implementation("jakarta.annotation:jakarta.annotation-api")
        implementation(libs.micronaut.grpc.client.runtime) // Or "io.micronaut.grpc:micronaut-grpc-client-runtime"
        implementation(libs.micronaut.reactor) // Or "io.micronaut.reactor:micronaut-reactor" Needed for Flux/Mono support

        // Reactive gRPC dependencies
        implementation("com.salesforce.servicelibs:reactive-grpc-common:${reactiveGrpcVersion}")
        implementation("com.salesforce.servicelibs:reactor-grpc-stub:${reactiveGrpcVersion}")

        runtimeOnly("ch.qos.logback:logback-classic")
        // ... other dependencies
    }

    java {
        sourceCompatibility = JavaVersion.toVersion("21")
        targetCompatibility = JavaVersion.toVersion("21")
    }

    // Protobuf Configuration
    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:${protobufVersion}"
        }
        plugins {
            // Standard gRPC Java plugin
            id("grpc") {
                artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
            }
            // Reactive gRPC Reactor plugin
            id("reactor") {
                artifact = "com.salesforce.servicelibs:reactor-grpc:${reactiveGrpcVersion}"
            }
        }
        generateProtoTasks {
            all().forEach { task ->
                task.plugins {
                    // Apply both plugins
                    id("grpc") {}
                    id("reactor") {}
                }
                // Optional: Configure output directories if needed, Micronaut usually handles this
                 task.builtins {
                     id("java") { // Ensure standard java messages are generated
                         option("lite")
                     }
                 }
            }
        }
    }

    // Ensure generated sources are included (Micronaut usually configures this)
    sourceSets {
        main {
            java {
                srcDirs("build/generated/source/proto/main/grpc", "build/generated/source/proto/main/reactor", "build/generated/source/proto/main/java")
            }
        }
    }

    // Docker build configuration (Micronaut Gradle Plugin)
    tasks.named<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuild") {
        images.set(listOf("${project.group}/${project.name}:${project.version}"))
    }

    tasks.named<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuildNative") { // If GraalVM
        images.set(listOf("${project.group}/${project.name}:${project.version}-native"))
    }


*(Note: Adapt dependency versions and plugin aliases based on your Micronaut version and `libs.versions.toml`)*

**application.yml (Micronaut Project)**

Configure the gRPC client channel to connect to the Python server (running as `grpc-server` in Docker).

    micronaut:
      application:
        name: javaGrpcClient
    grpc:
      channels:
        agentcomm: # Name used in @GrpcChannel
          address: 'grpc-server:50051' # Connect to Python server hostname in Docker
          plaintext: true # Use true for insecure connection in this example

**Java Reactive gRPC Client Factory (`src/main/java/com/example/GrpcClientFactory.java`)**

This factory creates the reactive gRPC stub using Micronaut's DI.

    package com.example;

    import io.grpc.ManagedChannel;
    import io.micronaut.context.annotation.Factory;
    import io.micronaut.grpc.annotation.GrpcChannel;
    import jakarta.inject.Singleton;

    // Import the *reactor* generated stub
    import reactor.grpc.stub.ReactorCalls; // Utility, may not be needed directly
    import com.example.agentcomm.grpc.ReactorAgentCommGrpc; // Generated by reactive-grpc

    @Factory
    public class GrpcClientFactory {

        @Singleton
        ReactorAgentCommGrpc.ReactorAgentCommStub reactiveAgentCommStub(
                @GrpcChannel("agentcomm") ManagedChannel channel // Inject channel named 'agentcomm' from application.yml
        ) {
            // Create the reactive stub using the injected channel
            return ReactorAgentCommGrpc.newReactorStub(channel);
        }
    }

**Java Client Runner (`src/main/java/com/example/ClientRunner.java`)**

This component uses the injected stub to communicate.

    package com.example;

    import com.example.agentcomm.grpc.AgentMessage;
    import com.example.agentcomm.grpc.ReactorAgentCommGrpc; // Generated by reactive-grpc
    import io.micronaut.context.annotation.Context;
    import jakarta.annotation.PostConstruct;
    import jakarta.inject.Inject;
    import jakarta.inject.Singleton;
    import reactor.core.Disposable;
    import reactor.core.publisher.Flux;
    import reactor.core.publisher.Sinks;
    import reactor.core.scheduler.Schedulers;

    import java.io.BufferedReader;
    import java.io.InputStreamReader;
    import java.time.Instant;
    import java.util.Random;

    @Singleton
    @Context // Eagerly initialize this bean
    public class ClientRunner {

        @Inject
        private ReactorAgentCommGrpc.ReactorAgentCommStub clientStub; // Inject the reactive stub

        private final String agentId = "java-client-" + new Random().nextInt(1000);
        private Disposable subscription;
        private final Sinks.Many<AgentMessage> sendSink = Sinks.many().unicast().onBackpressureBuffer();

        @PostConstruct
        public void startCommunication() {
            System.out.println("Starting communication as agent: " + agentId);

            Flux<AgentMessage> outgoingMessages = sendSink.asFlux();

            // Call the bidirectional streaming method
            Flux<AgentMessage> incomingMessages = clientStub.communicate(outgoingMessages);

            // Subscribe to incoming messages on a separate thread
            subscription = incomingMessages
                    .subscribeOn(Schedulers.boundedElastic()) // Use an appropriate scheduler
                    .subscribe(
                            this::handleIncomingMessage,
                            this::handleError,
                            this::handleCompletion);

            // Start reading console input on a separate thread
            Thread inputThread = new Thread(this::readConsoleInput, "console-input-thread");
            inputThread.setDaemon(true); // Allow application to exit if this is the only thread left
            inputThread.start();
        }

        private void handleIncomingMessage(AgentMessage message) {
            System.out.printf("\nReceived: [%s] %s (at %d)\n[%s] Enter message (or 'quit'): ",
                    message.getAgentId(), message.getContent(), message.getTimestamp(), agentId);
        }

        private void handleError(Throwable error) {
            System.err.println("\nError in communication stream: " + error.getMessage());
            // Optionally attempt to clean up or signal termination
            sendSink.tryEmitComplete(); // Signal completion if error occurs
        }

        private void handleCompletion() {
            System.out.println("\nCommunication stream completed by server.");
            // Optionally attempt to clean up or signal termination
            sendSink.tryEmitComplete(); // Ensure sink is completed
        }

        private void readConsoleInput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.print("[" + agentId + "] Enter message (or 'quit'): ");
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("quit".equalsIgnoreCase(line.trim())) {
                        System.out.println("Exiting...");
                        sendSink.tryEmitComplete(); // Signal completion to the gRPC call
                        break;
                    }
                    AgentMessage msg = AgentMessage.newBuilder()
                            .setAgentId(agentId)
                            .setContent(line)
                            .setTimestamp(Instant.now().getEpochSecond())
                            .build();

                    // Emit the message to the gRPC stream
                    Sinks.EmitResult result = sendSink.tryEmitNext(msg);
                    if (result.isFailure()) {
                        System.err.println("Failed to send message: " + result);
                        break; // Stop if sink fails
                    }
                    System.out.print("[" + agentId + "] Enter message (or 'quit'): ");
                }
            } catch (Exception e) {
                System.err.println("Error reading console input: " + e.getMessage());
                sendSink.tryEmitError(e); // Signal error to the gRPC call
            } finally {
                 // Ensure sink is completed if loop finishes naturally or via exception
                 sendSink.tryEmitComplete();
                 // Wait briefly for cleanup if needed, or manage lifecycle externally
                 if (subscription != null && !subscription.isDisposed()) {
                     System.out.println("Disposing subscription...");
                     //subscription.dispose(); // Be careful disposing here, might cut off server completion message
                 }
            }
             System.out.println("Input reading finished.");
        }
    }

**Main Application Class (`src/main/java/com/example/Application.java`)**

    package com.example;

    import io.micronaut.runtime.Micronaut;

    public class Application {
        public static void main(String[] args) {
            Micronaut.run(Application.class, args);
            // Keep main thread alive if needed (e.g., if ClientRunner wasn't @Context)
            // Or rely on daemon threads and shutdown hooks
             System.out.println("Micronaut application started... ClientRunner should be active.");
             // Add a shutdown hook for clean exit if needed
             Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                 System.out.println("Shutdown hook triggered.");
                 // Perform any final cleanup if necessary
             }));

             // Keep alive simplistic way for demo (better ways exist for real apps)
             try {
                 Thread.sleep(Long.MAX_VALUE);
             } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
                 System.err.println("Main thread interrupted.");
             }
        }
    }

**docker-compose.yml (Python Server, Java Client)**

    version: '3.8'
    services:
      grpc-server: # Python Server
        build:
          context: .
          dockerfile: Dockerfile.python-server
        ports:
          - "50051:50051"
        networks:
          - agentnet

      java-grpc-client: # Micronaut Java Client
        build:
          context: ./java-grpc-client # Path to your Micronaut project directory
          dockerfile: Dockerfile # Use Micronaut's generated Dockerfile
        depends_on:
          - grpc-server
        networks:
          - agentnet
        environment:
          # Ensure Micronaut picks up the correct gRPC channel address
          # This might be redundant if application.yml already points to 'grpc-server:50051'
          # GRPC_CHANNELS_AGENTCOMM_ADDRESS: 'grpc-server:50051' # Example env var override
          MICRONAUT_APPLICATION_NAME: javaGrpcClient # Example
        stdin_open: true # Keep stdin open for input
        tty: true        # Allocate pseudo-TTY for interaction

    networks:
      agentnet:
        driver: bridge


*(Ensure your Micronaut project has a suitable Dockerfile, typically generated by `mn create-app ... --features docker` or configurable via the Gradle plugin)*

**To Run (Python Server, Java Client):**

1.  Build the Micronaut project: `cd java-grpc-client && ./gradlew dockerBuild`.
2.  In the parent directory, run `docker-compose up --build`.
3.  Attach to the `java-grpc-client` container (`docker attach <container_id>`) to send messages from the Java client. Messages should appear on the Python server logs and vice-versa.

---

### 1f. Java-to-Java gRPC Example (Micronaut)

Now let's implement the server in Java/Micronaut as well.

**Micronaut Server Project Setup:**

* Similar to the client setup, create a new Micronaut application (`java-grpc-server`) using Micronaut Launch or CLI with the same features (`graalvm`, `grpc`, `reactor`, `annotation-api`).
* Copy `agent_comm.proto` to `src/main/proto`.

**build.gradle.kts (Micronaut Server)**

* Very similar to the client's `build.gradle.kts`, but include the `micronaut-grpc-server-runtime` dependency instead of the client one. Ensure the `reactive-grpc` plugins and dependencies are configured.

    // ... plugins, repositories, versions ...

    dependencies {
        // ... other dependencies
        protobuf(libs.managed.protobuf.java.util)
        implementation(libs.managed.grpc.protobuf)
        implementation(libs.managed.grpc.stub)
        implementation("jakarta.annotation:jakarta.annotation-api")
        implementation(libs.micronaut.grpc.server.runtime) // SERVER runtime
        implementation(libs.micronaut.reactor)

        // Reactive gRPC dependencies
        implementation("com.salesforce.servicelibs:reactive-grpc-common:${reactiveGrpcVersion}")
        implementation("com.salesforce.servicelibs:reactor-grpc-stub:${reactiveGrpcVersion}") // Needed for base class

        runtimeOnly("ch.qos.logback:logback-classic")
    }

    // ... java config, protobuf config (ensure reactor plugin is included) ...

    // Docker build config...

**Java Reactive gRPC Server Implementation (`src/main/java/com/example/AgentCommEndpoint.java`)**

Implement the service by extending the `ReactorAgentCommGrpc.AgentCommImplBase` generated by `reactive-grpc`.

    package com.example;

    import com.example.agentcomm.grpc.AgentMessage;
    import com.example.agentcomm.grpc.ReactorAgentCommGrpc; // Generated reactive base class
    import io.grpc.stub.StreamObserver; // Still needed sometimes for context/errors? Check reactive-grpc docs. Or maybe not.
    import jakarta.inject.Singleton;
    import reactor.core.publisher.Flux;
    import reactor.core.publisher.Sinks;
    import java.time.Instant;
    import java.util.concurrent.ConcurrentHashMap;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    @Singleton
    public class AgentCommEndpoint extends ReactorAgentCommGrpc.AgentCommImplBase {

        private static final Logger LOG = LoggerFactory.getLogger(AgentCommEndpoint.class);

        // A simple way to broadcast messages to all connected clients
        // In a real app, use a more robust pub/sub system (Kafka, Nats, etc.)
        private final Sinks.Many<AgentMessage> broadcastSink = Sinks.many().multicast().onBackpressureBuffer();
        private final Flux<AgentMessage> broadcastFlux = broadcastSink.asFlux();

        // Keep track of connected clients (for debugging/logging)
        private final ConcurrentHashMap<String, Flux<AgentMessage>> clientStreams = new ConcurrentHashMap<>();


        @Override
        public Flux<AgentMessage> communicate(Flux<AgentMessage> requestFlux) {
            // Use a sink to push messages received from this specific client to the broadcaster
            Sinks.Many<AgentMessage> clientSink = Sinks.many().unicast().onBackpressureBuffer();

            // Handle incoming messages from this client
            requestFlux
                .doOnNext(msg -> {
                    LOG.info("Server received: [{}] {}", msg.getAgentId(), msg.getContent());
                    // Broadcast the received message to ALL clients (including sender in this simple model)
                    broadcastSink.tryEmitNext(msg);
                })
                .doOnError(error -> LOG.error("Error in client request stream: {}", error.getMessage()))
                .doOnComplete(() -> LOG.info("Client request stream completed."))
                .subscribe(); // Subscribe to start processing the incoming flux


            // Return the broadcast flux, so this client receives messages from all clients
            // Note: This simple model echoes messages back to the sender.
            // A more complex model might filter broadcastFlux based on the client ID.
            return broadcastFlux
                   .doOnSubscribe(subscription -> LOG.info("New client subscribed to broadcast."))
                   .doOnCancel(() -> LOG.warn("Client cancelled subscription."))
                   .doOnError(error -> LOG.error("Error in broadcast stream for client: {}", error.getMessage()))
                   .doOnComplete(() -> LOG.info("Broadcast stream completed for client.")); // This might not happen if broadcast is long-lived

            // Potential alternative: Filter broadcast to not send back to self
            // String clientId = "some-unique-id-per-connection"; // How to get this reliably? Context?
            // return broadcastFlux.filter(msg -> !msg.getAgentId().equals(clientId));

        }
    }

*(Note: Identifying unique clients reliably in the reactive server implementation to prevent echo requires careful handling, possibly involving gRPC interceptors or context propagation, which is beyond this basic example.)*

**application.yml (Micronaut Server)**

    micronaut:
      application:
        name: javaGrpcServer
    grpc:
      server:
        port: 50051 # Default gRPC port
    # Add any other server configurations (TLS, etc.) if needed

**docker-compose.yml (Java Server, Java Client)**

    version: '3.8'
    services:
      java-grpc-server: # Micronaut Java Server
        build:
          context: ./java-grpc-server # Path to server project
          dockerfile: Dockerfile
        ports:
          - "50051:50051"
        networks:
          - agentnet

      java-grpc-client: # Micronaut Java Client
        build:
          context: ./java-grpc-client # Path to client project
          dockerfile: Dockerfile
        depends_on:
          - java-grpc-server
        networks:
          - agentnet
        environment:
          # Make sure client connects to the java server
          GRPC_CHANNELS_AGENTCOMM_ADDRESS: 'java-grpc-server:50051'
        stdin_open: true
        tty: true

    networks:
      agentnet:
        driver: bridge

**To Run (Java-Java):**

1.  Build both Micronaut projects: `./gradlew dockerBuild` in each directory.
2.  Run `docker-compose up --build`.
3.  Attach to the `java-grpc-client` to send messages. Observe logs on both server and client.

---

### 1g. Java Server (Micronaut) to Python Client Example

This involves running the Java server built above and configuring the original Python client to connect to it.

**docker-compose.yml (Java Server, Python Client)**

    version: '3.8'
    services:
      java-grpc-server: # Micronaut Java Server
        build:
          context: ./java-grpc-server
          dockerfile: Dockerfile
        ports:
          - "50051:50051"
        networks:
          - agentnet

      python-grpc-client: # Python Client
        build:
          context: .
          dockerfile: Dockerfile.python-client
        # Modify the command to connect to the java server
        command: ["python", "src/client.py", "java-grpc-server:50051"]
        depends_on:
          - java-grpc-server
        networks:
          - agentnet
        stdin_open: true
        tty: true

    networks:
      agentnet:
        driver: bridge

**To Run (Java Server, Python Client):**

1.  Build the Java server project: `cd java-grpc-server && ./gradlew dockerBuild`.
2.  Run `docker-compose up --build` (using the compose file above).
3.  Attach to `python-grpc-client` to send/receive messages.

---

### 1h. gRPC: Future Considerations and Why It's a Good Choice

**Strengths:**

* **Performance:** Unmatched for low-latency, high-throughput internal communication (e.g., microservices). Binary serialization and HTTP/2 multiplexing are key.
* **Streaming:** Native, efficient support for unary, client-streaming, server-streaming, and bidirectional streaming makes it ideal for real-time data, long-lived connections, and chat-like interactions.
* **Contract-First:** Protobuf enforces API contracts, catching integration errors early. Code generation simplifies development across languages.
* **Ecosystem:** Mature libraries, tooling (gRPC-Web, gRPC-Gateway for HTTP bridging), and community support.

**Challenges:**

* **Web Browser Support:** Requires proxies like gRPC-Web or gRPC-Gateway for direct browser communication, adding complexity compared to native HTTP APIs.
* **Network Traversal:** HTTP/2 might face challenges with some older proxies or restrictive firewalls compared to standard HTTP/1.1.
* **Human Readability:** Protobuf binary format is not human-readable, making debugging sometimes harder than with JSON (though tools exist).
* **Complexity:** Can be slightly more complex to set up initially compared to a simple REST API.

**When is gRPC a good choice for A2A?**

* When performance and low latency are critical.
* For communication primarily between backend services or agents you control.
* When robust, efficient bidirectional streaming is a core requirement.
* In polyglot environments where code generation provides significant benefits.

---

## Part 2: Adapting to Google's A2A Protocol Specification

While gRPC offers excellent performance, Google's A2A protocol specification aims for broader interoperability using standard web technologies. Let's see how we can adapt our agent chat concept to align with its principles.

### 2a. Understanding the A2A Specification Approach

Google's A2A protocol focuses on:

* **Transport:** HTTP/1.1 or HTTP/2.
* **Messaging:** JSON-RPC 2.0 for request/response structures.
* **Streaming:** Server-Sent Events (SSE) for pushing updates/messages from the server to the client (`text/event-stream`).
* **Discovery:** `Agent Card` (a JSON file, often at `/.well-known/agent.json`) describing agent capabilities, endpoints, auth, etc.
* **Task Management:** A defined lifecycle (`submitted`, `working`, etc.) for managing potentially long-running interactions initiated via specific HTTP endpoints (like `/tasks/sendSubscribe`).

Instead of a persistent gRPC bidirectional stream, the A2A pattern often involves:

1.  Client discovers Agent via Agent Card.
2.  Client sends an initial message/task request via HTTP POST (e.g., to `/tasks/sendSubscribe`).
3.  The server responds immediately, potentially acknowledging the task.
4.  The client then connects to an associated SSE endpoint (or the initial response *is* the SSE stream) to receive asynchronous updates (status changes, messages from other agents, artifacts).
5.  The client sends subsequent messages via further HTTP POST requests referencing the ongoing Task ID.

### 2b. How This Translates from gRPC

The *concept* of sending messages and receiving a stream of messages remains, but the *mechanism* changes:

* **gRPC:** `rpc Communicate(stream Req) returns (stream Resp)` - single, persistent connection handling two-way flow.
* **A2A:**
    * Send: HTTP POST `/tasks/sendSubscribe` (or similar) with JSON payload. Repeat POST for subsequent messages.
    * Receive: Connect to an SSE stream (`text/event-stream`) and listen for events containing JSON data.

### 2c. Python-to-Python (A2A Spec Style)

We'll use FastAPI for the server (good async/SSE support) and `requests` + `requests-sse` for the client.

**(Representative) JSON Structures:**

    // Agent Card (/.well-known/agent.json - simplified)
    {
      "name": "SimpleChatAgent",
      "description": "A basic agent using A2A-style communication",
      "url": "http://a2a-py-server:8000", // Base URL
      "capabilities": {
        "streaming": true // Supports SSE via /tasks/sendSubscribe
      },
      "skills": [
        {
          "id": "chat",
          "name": "Simple Chat",
          "description": "Engage in basic text chat"
        }
      ]
    }

    // Task Request Body (POST /tasks/sendSubscribe)
    {
      "jsonrpc": "2.0",
      "method": "tasks/sendSubscribe", // Or just use URL path
      "params": {
        "taskId": "unique-task-id-123", // Optional: client generates or server assigns
        "message": {
          "role": "user", // Or "agent"
          "parts": [
            { "type": "TextPart", "text": "Hello from agent X!" }
          ]
        }
        // Could include agentId, etc.
      },
      "id": "request-id-1" // JSON-RPC request ID
    }

    // SSE Event Format (Simplified)
    // event: TaskArtifactUpdateEvent / TaskStatusUpdateEvent / MessageEvent etc.
    // data: {"taskId": "unique-task-id-123", "message": {"role":"agent", "parts": [...]}}
    // id: event-id-abc
    // retry: 5000

**Python A2A Server (FastAPI - `python_a2a/src/server_a2a.py`)**

    import asyncio
    import uvicorn
    from fastapi import FastAPI, Request, HTTPException
    from fastapi.responses import JSONResponse, StreamingResponse
    from pydantic import BaseModel, Field # For request body validation
    import time
    import json
    import random
    import string
    from typing import List, Dict, Any, AsyncGenerator

    app = FastAPI()

    # In-memory storage for active streams and tasks (Replace with DB/Redis in production)
    active_streams: Dict[str, asyncio.Queue] = {}
    task_states: Dict[str, str] = {}


    class TextPart(BaseModel):
        type: str = "TextPart"
        text: str

    class MessagePart(BaseModel):
        role: str # "user" or "agent"
        parts: List[TextPart]

    class TaskParams(BaseModel):
        taskId: str | None = None # Optional task ID
        message: MessagePart
        agent_id: str = "unknown" # Get from auth ideally

    class JsonRpcRequest(BaseModel):
        jsonrpc: str = "2.0"
        method: str
        params: TaskParams
        id: str | int


    async def event_generator(task_id: str, initial_message: 'AgentMessage') -> AsyncGenerator[str, None]:
        """Generates SSE events for a given task queue."""
        queue = asyncio.Queue()
        active_streams[task_id] = queue
        print(f"SSE Stream opened for task: {task_id}")

        # Immediately send the initial message back or process it
        await queue.put(initial_message) # Example: Echo back

        last_event_id = 0
        try:
            while True:
                message: AgentMessage = await queue.get()
                if message is None: # Sentinel to close stream
                    break

                # Format as SSE
                last_event_id += 1
                sse_event = f"event: TaskArtifactUpdateEvent\n" \
                            f"id: {task_id}-{last_event_id}\n" \
                            f"data: {message.json()}\n\n" # Send AgentMessage as JSON
                yield sse_event
                queue.task_done()
        except asyncio.CancelledError:
             print(f"SSE Stream cancelled for task: {task_id}")
        finally:
            print(f"SSE Stream closing for task: {task_id}")
            if task_id in active_streams:
                del active_streams[task_id]
            if task_id in task_states:
                 task_states[task_id] = "completed" # Or failed/cancelled


    async def broadcast_message(sender_task_id: str, message: 'AgentMessage'):
        """Sends message to all *other* active streams."""
        print(f"Broadcasting from {message.agent_id} (task: {sender_task_id})")
        for task_id, queue in active_streams.items():
            if task_id != sender_task_id: # Don't broadcast back to sender
                try:
                    await queue.put(message)
                except Exception as e:
                     print(f"Error broadcasting to task {task_id}: {e}")


    # Simplified AgentMessage for internal use/broadcast
    class AgentMessage(BaseModel):
        agent_id: str
        content: str
        timestamp: int


    @app.post("/tasks/sendSubscribe")
    async def handle_task_send_subscribe(req_body: JsonRpcRequest, request: Request):
        """Handles initial task creation and subsequent messages."""
        params = req_body.params
        task_id = params.taskId or f"task_{''.join(random.choices(string.ascii_lowercase + string.digits, k=8))}"

        # Extract message content
        message_text = " ".join([part.text for part in params.message.parts if part.type == "TextPart"])

        print(f"Received for task {task_id} from {params.agent_id}: {message_text}")

        current_message = AgentMessage(
            agent_id=params.agent_id,
            content=message_text,
            timestamp=int(time.time())
        )

        if task_id not in task_states:
            # New task - initiate SSE stream
            task_states[task_id] = "working"
            print(f"New task created: {task_id}")
            # Start broadcasting this message
            asyncio.create_task(broadcast_message(task_id, current_message))
            # Return SSE stream
            return StreamingResponse(event_generator(task_id, current_message), media_type="text/event-stream")
        else:
            # Existing task - just broadcast message and return simple ack
            if task_id in active_streams:
                await broadcast_message(task_id, current_message)
                return JSONResponse(content={
                    "jsonrpc": "2.0",
                    "result": {"taskId": task_id, "status": "message_received"},
                    "id": req_body.id
                })
            else:
                raise HTTPException(status_code=404, detail="Task stream not found or closed")


    @app.get("/.well-known/agent.json")
    async def get_agent_card():
        # In real app, get base_url dynamically
        base_url = "http://a2a-py-server:8000" # Use service name from docker-compose
        return {
            "name": "SimpleChatAgent-Python-A2A",
            "description": "A basic agent using A2A-style communication (FastAPI)",
            "url": base_url,
            "capabilities": {
                "streaming": True # Supports SSE via /tasks/sendSubscribe
            },
            "skills": [
                {
                "id": "chat",
                "name": "Simple Chat",
                "description": "Engage in basic text chat"
                }
            ],
             "authentication": { # Example placeholder
                  "type": "none"
              }
        }

    if __name__ == "__main__":
        uvicorn.run(app, host="0.0.0.0", port=8000)

**Python A2A Client (`python_a2a/src/client_a2a.py`)**

    import requests
    import json
    import time
    import threading
    import random
    import string
    import sys
    from requests_sse import EventSource # Using requests-sse

    AGENT_ID = f"py-a2a-client-{''.join(random.choices(string.ascii_lowercase + string.digits, k=4))}"
    server_base_url = "http://localhost:8000" # Default, override with arg
    task_id = None
    session = requests.Session() # Use session for potential keep-alive

    def send_message(content: str):
        global task_id

        url = f"{server_base_url}/tasks/sendSubscribe" # Always use this endpoint in this simple model

        payload = {
            "jsonrpc": "2.0",
            "method": "tasks/sendSubscribe",
            "params": {
                "taskId": task_id, # Will be None for the first message
                "message": {
                    "role": "user",
                    "parts": [{"type": "TextPart", "text": content}]
                },
                "agent_id": AGENT_ID
            },
            "id": f"req_{random.randint(1000, 9999)}"
        }

        try:
            headers = {'Content-Type': 'application/json', 'Accept': 'text/event-stream, application/json'}

            if task_id is None:
                # First message - expect SSE stream in response
                print("Sending initial message and connecting to SSE stream...")
                response = session.post(url, json=payload, headers=headers, stream=True)
                response.raise_for_status() # Raise exception for bad status codes (4xx or 5xx)

                if 'text/event-stream' in response.headers.get('Content-Type', ''):
                     print("SSE stream established.")
                     # Process the SSE stream in a separate thread
                     sse_thread = threading.Thread(target=process_sse_stream, args=(response,), daemon=True)
                     sse_thread.start()
                     # We need the task ID from the first event if server assigns it
                     # For now, assume client generates or it's handled within process_sse_stream
                else:
                     print("Error: Expected SSE stream but received different content type.")
                     # Handle JSON response if it contains task ID or error
                     try:
                         result = response.json()
                         print(f"Received JSON response: {result}")
                         if result.get("result", {}).get("taskId"):
                              task_id = result["result"]["taskId"]
                     except json.JSONDecodeError:
                          print("Could not decode JSON response.")
                     return False # Indicate failure to start SSE

            else:
                # Subsequent messages - expect simple JSON ack
                print(f"Sending subsequent message for task {task_id}...")
                headers['Accept'] = 'application/json' # Prefer JSON ack
                response = session.post(url, json=payload, headers=headers)
                response.raise_for_status()
                try:
                     ack = response.json()
                     print(f"Received ACK: {ack}")
                     if ack.get("error"):
                         print(f"Error from server: {ack['error']}")
                         return False
                except json.JSONDecodeError:
                     print(f"Received non-JSON ACK (Status: {response.status_code})")

            return True

        except requests.exceptions.RequestException as e:
            print(f"Error sending message: {e}")
            return False
        except Exception as e:
             print(f"An unexpected error occurred during send: {e}")
             return False


    def process_sse_stream(response):
        global task_id
        print("SSE Processor Started.")
        try:
            # Use requests_sse EventSource to wrap the streaming response
            # It requires the raw response object
            event_source = EventSource(response)
            for event in event_source:
                # Assuming event.data is a JSON string of our AgentMessage
                if event.data:
                    try:
                         # Try extracting task ID from first message if server assigned it
                         # This logic is speculative and depends on server implementation
                         if task_id is None and event.id and '-' in event.id:
                              potential_tid = event.id.split('-')[0]
                              if potential_tid.startswith("task_"):
                                  task_id = potential_tid
                                  print(f"Received task ID from server: {task_id}")

                         msg_data = json.loads(event.data)
                         print(f"\nSSE Received ({event.event}): [{msg_data.get('agent_id')}] {msg_data.get('content')} (at {msg_data.get('timestamp')})\n[{AGENT_ID}] Enter message (or 'quit'): ", end='')
                    except json.JSONDecodeError:
                         print(f"\nReceived non-JSON SSE data: {event.data}\n[{AGENT_ID}] Enter message (or 'quit'): ", end='')
                    except Exception as e:
                         print(f"\nError processing SSE data: {e}\n[{AGENT_ID}] Enter message (or 'quit'): ", end='')

        except requests.exceptions.ChunkedEncodingError:
            print("\nSSE stream connection broken (ChunkedEncodingError).")
        except requests.exceptions.RequestException as e:
             print(f"\nError in SSE stream: {e}")
        except Exception as e:
            print(f"\nAn unexpected error occurred in SSE processor: {e}")
        finally:
            print("\nSSE stream processing finished.")
            # Signal main loop to exit maybe? Or just let it end.

    def run_client_interface():
        print(f"Starting A2A client as {AGENT_ID} targeting {server_base_url}")
        # Optional: Fetch and print Agent Card
        try:
            card_resp = requests.get(f"{server_base_url}/.well-known/agent.json")
            card_resp.raise_for_status()
            print("--- Agent Card ---")
            print(json.dumps(card_resp.json(), indent=2))
            print("------------------")
        except Exception as e:
            print(f"Could not fetch Agent Card: {e}")

        while True:
            try:
                 content = input(f"[{AGENT_ID}] Enter message (or 'quit'): ")
                 if content.lower() == 'quit':
                     # TODO: Send cancellation signal if needed by protocol
                     print("Exiting...")
                     break

                 if not send_message(content):
                     # Break if sending fails significantly (e.g., can't establish SSE)
                     if task_id is None: # Break if initial connection failed
                          break
                     # Decide whether to break on subsequent errors or allow retries

                 time.sleep(0.1) # Prevent tight loop on input error

            except EOFError:
                print("\nInput stream closed. Exiting.")
                break
            except KeyboardInterrupt:
                 print("\nCtrl+C detected. Exiting.")
                 break

        print("Client shutting down.")
        session.close()


    if __name__ == '__main__':
        if len(sys.argv) > 1:
            server_base_url = sys.argv[1]

        # Generate a client-side task ID (optional, server might assign)
        # task_id = f"task_{''.join(random.choices(string.ascii_lowercase + string.digits, k=8))}"

        run_client_interface()

**Dockerfile (Python A2A Server - FastAPI)**

    # Dockerfile.python-a2a-server
    FROM python:3.11-slim

    WORKDIR /app

    COPY ./python_a2a/requirements.txt /app/
    RUN pip install --no-cache-dir -r requirements.txt

    COPY ./python_a2a/src /app/src

    EXPOSE 8000

    CMD ["uvicorn", "src.server_a2a:app", "--host", "0.0.0.0", "--port", "8000"]

**Dockerfile (Python A2A Client)**

    # Dockerfile.python-a2a-client
    FROM python:3.11-slim

    WORKDIR /app

    COPY ./python_a2a/requirements.txt /app/
    RUN pip install --no-cache-dir -r requirements.txt

    COPY ./python_a2a/src /app/src

    # Default command runs client connecting to 'a2a-py-server' hostname
    CMD ["python", "src/client_a2a.py", "http://a2a-py-server:8000"]

**requirements.txt (for Python A2A)**

    fastapi>=0.110.0,<0.111.0
    uvicorn[standard]>=0.28.0,<0.29.0
    pydantic>=2.0.0,<3.0.0
    requests>=2.31.0,<3.0.0
    requests-sse>=0.5.1,<1.0.0 # SSE Client library
    # Add sse-starlette if needed for server, or other SSE libraries

**docker-compose.yml (Python-Python A2A)**

    version: '3.8'
    services:
      a2a-py-server:
        build:
          context: .
          dockerfile: Dockerfile.python-a2a-server
        ports:
          - "8000:8000" # Expose HTTP port
        networks:
          - agentnet-a2a

      a2a-py-client-1:
        build:
          context: .
          dockerfile: Dockerfile.python-a2a-client
        depends_on:
          - a2a-py-server
        networks:
          - agentnet-a2a
        stdin_open: true
        tty: true

      a2a-py-client-2:
        build:
          context: .
          dockerfile: Dockerfile.python-a2a-client
        depends_on:
          - a2a-py-server
        networks:
          - agentnet-a2a
        stdin_open: true
        tty: true

    networks:
      agentnet-a2a:
        driver: bridge

**To Run (Python-Python A2A):**

1.  Organize files into `python_a2a/src`, etc.
2.  Run `docker-compose up --build`.
3.  Attach to the client containers (`docker attach <container_id>`) to interact. Sending the first message from a client initiates the SSE connection. Subsequent messages are sent via separate POST requests. Messages sent by one client should be broadcast via SSE to the other client.

---

### 2d. Python Server (A2A) to Java Client (Micronaut A2A)

We use the Python A2A server above and create a Micronaut HTTP/SSE client.

**Micronaut Project Setup:**

* Create a new Micronaut application (`java-a2a-client`) similar to the gRPC client, but you don't need the `grpc` or `reactive-grpc` features. Include `reactor` and `http-client`.

**build.gradle.kts (Micronaut A2A Client)**

    plugins {
         // ... application, java/kotlin, etc.
         alias(libs.plugins.micronaut.application)
    }
    // ... repositories, versions

    micronaut {
        version.set(libs.versions.micronaut.platform)
        runtime("netty")
        testRuntime("junit5")
         // ... processing, aot
    }

    dependencies {
        implementation("jakarta.annotation:jakarta.annotation-api")
        implementation(libs.micronaut.http.client) // Or "io.micronaut:micronaut-http-client"
        implementation(libs.micronaut.reactor)    // Or "io.micronaut.reactor:micronaut-reactor"

        runtimeOnly("ch.qos.logback:logback-classic")
        // No gRPC or SSE-specific library needed here, Micronaut client handles it
    }

    // ... java config, docker config

**application.yml (Micronaut A2A Client)**

Configure the base URL for the Micronaut HTTP client.

    micronaut:
      application:
        name: javaA2aClient
      http:
        services:
          a2aPyServer: # Arbitrary ID used in @Client
            url: http://a2a-py-server:8000 # Connect to Python A2A server hostname

**Java A2A Declarative Client Interface (`src/main/java/com/example/A2aPythonServerClient.java`)**

    package com.example;

    import io.micronaut.http.MediaType;
    import io.micronaut.http.annotation.Body;
    import io.micronaut.http.annotation.Get;
    import io.micronaut.http.annotation.Header;
    import io.micronaut.http.annotation.Post;
    import io.micronaut.http.client.annotation.Client;
    import io.micronaut.http.sse.Event;
    import reactor.core.publisher.Flux; // Use Flux for reactive streaming
    import reactor.core.publisher.Mono;

    import static io.micronaut.http.HttpHeaders.ACCEPT;
    import static io.micronaut.http.HttpHeaders.CONTENT_TYPE;

    // Define representative Request/Response classes (matching server's JSON)
    // Using simple Maps or specific POJOs
    import java.util.Map;

    @Client(id = "a2aPyServer") // Matches ID in application.yml
    @Header(name = CONTENT_TYPE, value = MediaType.APPLICATION_JSON)
    public interface A2aPythonServerClient {

        @Get("/.well-known/agent.json")
        @Header(name = ACCEPT, value = MediaType.APPLICATION_JSON)
        Mono<Map<String, Object>> getAgentCard(); // Get Agent Card as a Map

        // Initial request that returns SSE stream
        @Post("/tasks/sendSubscribe")
        @Header(name = ACCEPT, value = MediaType.TEXT_EVENT_STREAM) // Expect SSE stream
        Flux<Event<String>> sendAndSubscribe(@Body Map<String, Object> taskRequest); // Send JSON, receive SSE Events with String data

        // Subsequent request that returns JSON ack
        @Post("/tasks/sendSubscribe")
        @Header(name = ACCEPT, value = MediaType.APPLICATION_JSON) // Expect JSON ack
        Mono<Map<String, Object>> sendMessage(@Body Map<String, Object> taskRequest); // Send JSON, receive JSON ack
    }

*(Note: `Event<String>` assumes the SSE data is plain text or JSON string. If it's JSON representing `AgentMessage`, use `Event<Map<String, Object>>` or `Event<AgentMessageDto>` with a corresponding DTO class annotated with `@Serdeable`)*

**Java Client Runner (`src/main/java/com/example/ClientRunnerA2a.java`)**

    package com.example;

    import io.micronaut.context.annotation.Context;
    import io.micronaut.http.sse.Event;
    import jakarta.annotation.PostConstruct;
    import jakarta.inject.Inject;
    import jakarta.inject.Singleton;
    import reactor.core.Disposable;
    import reactor.core.publisher.Flux;
    import reactor.core.publisher.Mono;
    import reactor.core.scheduler.Schedulers;

    import java.io.BufferedReader;
    import java.io.InputStreamReader;
    import java.time.Instant;
    import java.util.List;
    import java.util.Map;
    import java.util.Random;
    import java.util.concurrent.atomic.AtomicReference;

    @Singleton
    @Context
    public class ClientRunnerA2a {

        @Inject
        private A2aPythonServerClient apiClient; // Inject the declarative client

        private final String agentId = "java-a2a-client-" + new Random().nextInt(1000);
        private final AtomicReference<String> taskIdRef = new AtomicReference<>(null);
        private Disposable sseSubscription;

        @PostConstruct
        public void initialize() {
            System.out.println("Starting A2A communication as agent: " + agentId);
            // Fetch Agent Card first (optional)
            apiClient.getAgentCard()
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(card -> System.out.println("--- Agent Card ---\n" + card + "\n------------------"))
                    .doOnError(e -> System.err.println("Failed to fetch Agent Card: " + e.getMessage()))
                    .subscribe(); // Fire and forget

            // Start console reader thread
            Thread inputThread = new Thread(this::readConsoleInput, "console-input-thread");
            inputThread.setDaemon(true);
            inputThread.start();
        }

         private void sendMessageAndHandleResponse(String content) {
            Map<String, Object> messagePart = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("type", "TextPart", "text", content))
            );
            Map<String, Object> params = Map.of(
                    "taskId", taskIdRef.get(), // Will be null initially
                    "message", messagePart,
                    "agent_id", agentId
            );
            Map<String, Object> requestBody = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tasks/sendSubscribe",
                    "params", params,
                    "id", "req-" + System.currentTimeMillis()
            );

            if (taskIdRef.get() == null) {
                // First message: Expect SSE stream
                System.out.println("Sending initial message, subscribing to SSE...");
                Flux<Event<String>> sseFlux = apiClient.sendAndSubscribe(requestBody);

                if (sseSubscription != null && !sseSubscription.isDisposed()) {
                    sseSubscription.dispose(); // Dispose previous subscription if any
                }

                sseSubscription = sseFlux
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                this::handleSseEvent,
                                this::handleSseError,
                                this::handleSseCompletion
                        );
            } else {
                // Subsequent messages: Expect JSON ack
                 System.out.println("Sending subsequent message...");
                apiClient.sendMessage(requestBody)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                ack -> System.out.println("Received ACK: " + ack),
                                error -> System.err.println("Error sending message: " + error.getMessage())
                        );
            }
        }


        private void handleSseEvent(Event<String> event) {
             // Try to parse Task ID from first event ID (example logic)
             if (taskIdRef.get() == null && event.getId() != null && event.getId().contains("-")) {
                 String potentialTaskId = event.getId().split("-")[0];
                 if (potentialTaskId.startsWith("task_")) { // Assuming server uses this prefix
                     taskIdRef.set(potentialTaskId);
                     System.out.println("\nTask ID received: " + taskIdRef.get());
                 }
             }
            // Process event data (assuming JSON string)
            System.out.printf("\nSSE Received (%s): %s\n[%s] Enter message (or 'quit'): ",
                    event.getName(), // Event type
                    event.getData(),   // Event data (raw string)
                    agentId);
        }

        private void handleSseError(Throwable error) {
            System.err.println("\nError in SSE stream: " + error.getMessage());
            taskIdRef.set(null); // Reset task ID on error
        }

         private void handleSseCompletion() {
             System.out.println("\nSSE stream completed by server.");
             taskIdRef.set(null); // Reset task ID on completion
         }


        private void readConsoleInput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                 System.out.print("[" + agentId + "] Enter message (or 'quit'): ");
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("quit".equalsIgnoreCase(line.trim())) {
                        System.out.println("Exiting...");
                        // TODO: Send cancellation request if protocol defines one
                        if (sseSubscription != null && !sseSubscription.isDisposed()) {
                            sseSubscription.dispose();
                        }
                        break;
                    }
                    sendMessageAndHandleResponse(line);
                     System.out.print("[" + agentId + "] Enter message (or 'quit'): "); // Prompt after send
                }
            } catch (Exception e) {
                System.err.println("Error reading console input: " + e.getMessage());
            } finally {
                System.out.println("Input reading finished.");
                 if (sseSubscription != null && !sseSubscription.isDisposed()) {
                     sseSubscription.dispose();
                 }
            }
        }
    }

**Main Application Class (`src/main/java/com/example/Application.java`)**

(Same as the Java gRPC Client Application class, just runs Micronaut).

**docker-compose.yml (Python A2A Server, Java A2A Client)**

    version: '3.8'
    services:
      a2a-py-server: # Python A2A Server
        build:
          context: .
          dockerfile: Dockerfile.python-a2a-server
        ports:
          - "8000:8000"
        networks:
          - agentnet-a2a

      java-a2a-client: # Micronaut Java A2A Client
        build:
          context: ./java-a2a-client # Path to client project
          dockerfile: Dockerfile
        depends_on:
          - a2a-py-server
        networks:
          - agentnet-a2a
        stdin_open: true
        tty: true

    networks:
      agentnet-a2a:
        driver: bridge

**To Run (Python Server A2A, Java Client A2A):**

1.  Build the Micronaut client: `cd java-a2a-client && ./gradlew dockerBuild`.
2.  Run `docker-compose up --build`.
3.  Attach to `java-a2a-client` to interact.

---

### 2e. Java-to-Java (Micronaut A2A)

Implement both server and client using Micronaut's HTTP and SSE features.

**Micronaut A2A Server Project Setup:**

* Create a Micronaut project (`java-a2a-server`) with `reactor`, `http-server-netty`.

**build.gradle.kts (Micronaut A2A Server)**

    plugins {
         // ... application, java/kotlin, etc.
         alias(libs.plugins.micronaut.application)
    }
    // ... repositories, versions

    micronaut {
        version.set(libs.versions.micronaut.platform)
        runtime("netty")
        testRuntime("junit5")
         // ... processing, aot
    }

    dependencies {
        implementation("jakarta.annotation:jakarta.annotation-api")
        implementation(libs.micronaut.http.server.netty) // Or "io.micronaut:micronaut-http-server-netty"
        implementation(libs.micronaut.reactor)          // Or "io.micronaut.reactor:micronaut-reactor"
        // For SSE Event class
        implementation(libs.micronaut.http.sse)         // Or "io.micronaut:micronaut-http-sse"

        runtimeOnly("ch.qos.logback:logback-classic")
    }

    // ... java config, docker config

**Java A2A Server Implementation (`src/main/java/com/example/A2aController.java`)**

    package com.example;

    import io.micronaut.http.MediaType;
    import io.micronaut.http.annotation.Body;
    import io.micronaut.http.annotation.Controller;
    import io.micronaut.http.annotation.Get;
    import io.micronaut.http.annotation.Post;
    import io.micronaut.http.sse.Event;
    import io.micronaut.scheduling.TaskExecutors;
    import io.micronaut.scheduling.annotation.ExecuteOn;
    import jakarta.inject.Singleton;
    import reactor.core.publisher.Flux;
    import reactor.core.publisher.Sinks;

    import java.time.Instant;
    import java.util.List;
    import java.util.Map;
    import java.util.Random;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.atomic.AtomicLong;

    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    // Define DTOs matching the JSON structure
    record TextPart(String type, String text) {}
    record MessagePart(String role, List<TextPart> parts) {}
    record TaskParams(String taskId, MessagePart message, String agent_id) {}
    record JsonRpcRequest(String jsonrpc, String method, TaskParams params, String id) {}

    // Simplified AgentMessage DTO for broadcasting
    record AgentMessageDto(String agent_id, String content, long timestamp) {}


    @Controller // Base path /
    @Singleton
    public class A2aController {

        private static final Logger LOG = LoggerFactory.getLogger(A2aController.class);
        private final AtomicLong eventIdCounter = new AtomicLong(0);

        // Simple broadcast mechanism
        private final Sinks.Many<AgentMessageDto> broadcastSink = Sinks.many().multicast().onBackpressureBuffer();
        private final Flux<AgentMessageDto> broadcastFlux = broadcastSink.asFlux().publish().autoConnect(); // Keep alive

        // Track tasks (simple example)
        private final ConcurrentHashMap<String, String> taskStates = new ConcurrentHashMap<>();


        @Post(uri = "/tasks/sendSubscribe", consumes = MediaType.APPLICATION_JSON, produces = {MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON})
        @ExecuteOn(TaskExecutors.IO) // Offload blocking operations if any
        public Flux<?> handleTaskSendSubscribe(@Body JsonRpcRequest request) {

            String taskId = request.params().taskId();
            if (taskId == null || taskId.isBlank()) {
                taskId = "task_" + new Random().nextInt(100000); // Assign task ID if missing
            }
            final String finalTaskId = taskId; // For lambda capture

            String messageText = request.params().message().parts().stream()
                                      .filter(p -> "TextPart".equals(p.type()))
                                      .map(TextPart::text)
                                      .findFirst().orElse("");
            String senderAgentId = request.params().agent_id() != null ? request.params().agent_id() : "unknown-a2a-java";

            LOG.info("Received for task {}: [{}] {}", finalTaskId, senderAgentId, messageText);

            AgentMessageDto currentMessage = new AgentMessageDto(
                    senderAgentId,
                    messageText,
                    Instant.now().getEpochSecond()
            );

            // Broadcast to all listeners
            broadcastSink.tryEmitNext(currentMessage);

            if (!taskStates.containsKey(finalTaskId)) {
                // New task - return SSE stream
                taskStates.put(finalTaskId, "working");
                LOG.info("New task {}, starting SSE stream.", finalTaskId);

                // Map broadcast messages to SSE Events
                return broadcastFlux
                        .map(msgDto -> Event.of(msgDto) // Micronaut converts msgDto to JSON
                                            .id(String.valueOf(eventIdCounter.incrementAndGet()))
                                            .name("TaskArtifactUpdateEvent")) // Example event name
                        .doOnCancel(() -> {
                            LOG.info("SSE Stream cancelled for task: {}", finalTaskId);
                            taskStates.remove(finalTaskId);
                        })
                        .doOnTerminate(() -> { // Includes complete and error
                            LOG.info("SSE Stream terminated for task: {}", finalTaskId);
                            taskStates.remove(finalTaskId);
                        });

            } else {
                // Existing task - return simple JSON ack
                LOG.info("Existing task {}, sending ACK.", finalTaskId);
                Map<String, Object> ack = Map.of(
                        "jsonrpc", "2.0",
                        "result", Map.of("taskId", finalTaskId, "status", "message_received"),
                        "id", request.id() != null ? request.id() : "ack-" + System.currentTimeMillis()
                );
                // Need to return Mono/Flux for framework to handle JSON correctly when multiple produces types exist
                return Flux.just(ack);
            }
        }

        @Get("/.well-known/agent.json")
        public Map<String, Object> getAgentCard() {
             // In real app, get base_url dynamically
            String baseUrl = "http://java-a2a-server:8080"; // Use service name
            return Map.of(
                "name", "SimpleChatAgent-Java-A2A",
                "description", "A basic agent using A2A-style communication (Micronaut)",
                "url", baseUrl,
                "capabilities", Map.of("streaming", true),
                "skills", List.of(
                    Map.of(
                        "id", "chat",
                        "name", "Simple Chat",
                        "description", "Engage in basic text chat"
                    )
                ),
                 "authentication", Map.of("type", "none")
            );
        }
    }

**Micronaut A2A Client:**

* Use the same `java-a2a-client` project as before.
* Update its `application.yml` to point the `a2aPyServer` (or rename it) client ID to the Java A2A server's service name (`java-a2a-server:8080`).

**docker-compose.yml (Java-Java A2A)**

    version: '3.8'
    services:
      java-a2a-server: # Micronaut Java A2A Server
        build:
          context: ./java-a2a-server
          dockerfile: Dockerfile
        ports:
          - "8080:8080" # Expose HTTP port
        networks:
          - agentnet-a2a

      java-a2a-client: # Micronaut Java A2A Client
        build:
          context: ./java-a2a-client
          dockerfile: Dockerfile
        environment:
          # Point client to the java server
          MICRONAUT_HTTP_SERVICES_A2APYSERVER_URL: http://java-a2a-server:8080 # Or rename client id 'a2aPyServer'
        depends_on:
          - java-a2a-server
        networks:
          - agentnet-a2a
        stdin_open: true
        tty: true

    networks:
      agentnet-a2a:
        driver: bridge

**To Run (Java-Java A2A):**

1.  Build both Micronaut projects: `./gradlew dockerBuild`.
2.  Run `docker-compose up --build`.
3.  Attach to `java-a2a-client` to interact.

---

### 2f. Java Server (Micronaut A2A) to Python Client (A2A)

Use the Java A2A server and the Python A2A client.

**docker-compose.yml (Java Server A2A, Python Client A2A)**

    version: '3.8'
    services:
      java-a2a-server: # Micronaut Java A2A Server
        build:
          context: ./java-a2a-server
          dockerfile: Dockerfile
        ports:
          - "8080:8080"
        networks:
          - agentnet-a2a

      a2a-py-client: # Python A2A Client
        build:
          context: .
          dockerfile: Dockerfile.python-a2a-client
        # Modify command to point to Java server
        command: ["python", "src/client_a2a.py", "http://java-a2a-server:8080"]
        depends_on:
          - java-a2a-server
        networks:
          - agentnet-a2a
        stdin_open: true
        tty: true

    networks:
      agentnet-a2a:
        driver: bridge

**To Run (Java Server A2A, Python Client A2A):**

1.  Build the Java server: `cd java-a2a-server && ./gradlew dockerBuild`.
2.  Run `docker-compose up --build`.
3.  Attach to `a2a-py-client` to interact.

---

### 2g. A2A Spec: Future Considerations and When It's a Good Choice

**Strengths:**

* **Interoperability:** Built on ubiquitous web standards (HTTP, JSON, SSE), making integration easier across diverse organizations, languages, and existing web infrastructure.
* **Discoverability:** The Agent Card provides a standard mechanism for agents to advertise capabilities.
* **Web/Browser Friendly:** Easier to integrate with web frontends and intermediate web proxies/gateways compared to raw gRPC.
* **Flexibility:** JSON offers more flexibility (and less rigidity) than Protobuf, which can be advantageous in rapidly evolving systems. Supports various communication patterns (sync, SSE, push).
* **Industry Momentum:** Backed by Google and a growing list of partners, aiming to become an open standard.

**Challenges:**

* **Performance:** Generally lower performance than optimized gRPC/Protobuf due to text-based JSON serialization and potentially less efficient HTTP/1.1 usage (though HTTP/2 is possible).
* **Streaming Complexity:** SSE is server-to-client push. Implementing true bidirectional-like flow requires combining SSE with separate client-to-server HTTP requests, potentially adding complexity compared to gRPC's native bidirectional streams.
* **Schema Enforcement:** Relies on JSON Schema (if used) for validation, which is less integrated into the core protocol than Protobuf is with gRPC.
* **Maturity:** As a newer specification, the tooling, best practices, and library support are still evolving compared to the mature gRPC ecosystem.

**When is the A2A specification style a good choice?**

* When interoperability with external partners or diverse internal systems is the primary goal.
* When leveraging existing web infrastructure (load balancers, API gateways) is important.
* When direct browser communication is needed without complex proxies.
* For applications where the slight performance overhead compared to gRPC is acceptable.
* When participating in the emerging open standard ecosystem for agent communication is desired.

---

## Conclusion: Choosing Your Path

Both gRPC and the A2A protocol specification offer valid ways to build agent-to-agent communication, but they cater to different priorities.

* **Choose gRPC** if your primary concerns are **raw performance, efficient bidirectional streaming between services you control, and strong type safety**, especially in a backend-heavy, polyglot microservices environment.
* **Choose the A2A specification style** when **interoperability, web standards compliance, discoverability, and ease of integration** with existing HTTP infrastructure are paramount, particularly when dealing with external agents or requiring browser integration.

As we've seen, the core *logic* of sending and receiving messages can be implemented in both paradigms. Migrating or supporting both might even be feasible. The key is understanding the trade-offs and selecting the approach that best aligns with your specific system requirements and collaboration goals. The world of AI agents is dynamic, and having flexible, well-understood communication protocols will be essential for building the next generation of intelligent, collaborative applications.
