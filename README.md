
# Rhizomatic

Rhizomatic is a runtime built on the Java Platform Module System. It provides an extensibility model, service injection framework based on Guice, REST endpoint publishing using
Jersey JAX-RS, and a web application server.

## Creating a Service
Services are implemented as simple Java classes using `@Service`. Their dependencies are injected using Guice and therefore adhere to Guice IoC semantics:

```java
@Service
public class ClientService {
    
    @Inject
    MessageService service;
    
    //...
}

``` 
Classes marked with ```@Service``` will be registered when the runtime is started.

## REST Endpoints
REST endpoints are services decorated with JAX-RS annotations. The runtime will discover and enable these services at startup:
```java
  
@Path("messages")
@Consumes("application/json")
@Produces("application/json")
public class TestResource {
  
     @Inject
     protected MessageStore messageStore;
  
     @GET
     @Path("recent")
     public List<Message> messages() {
         return messageStore.getRecentMessages(); 
     }
}
``` 
Base URIs for resource services can be defined using the ```o.rhizomatic.api.annotations.EndpopintPath``` annotation on a module definition:

```java
@EndpointPath("api")
open module module.a {
    requires javax.inject;
    requires java.ws.rs;
    requires io.rhizomatic.api;
   
}

```
If multiple modules use the same endpoint path, their resource services will be concantenated under the same path. 

## Remote Communications and Streaming
Applications may often use messaging systems such as [NATS](nats.io) and [Kafka](https://kafka.apache.org) or streaming libraries for communications. Instead of providing
abstractions on their native APIs, Rhizomatic encourages writing injectable services that encapsulate their use. For example:

```java
@Service
public class Producer {
    Connection natsConnection;

    @Init
    public void init() {
        // initialize and create a NATS connection
        natsConnection = Nats.connect();
    }

    public TestChannel() {

    }

    public void send(String message) {
        natsConnection.publish("test", message.getBytes());
    }
}
```
Encapsulation centralizes the complexity of configuring communications systems and allows them to be substituted during testing and other alternative deployment scenarios.

## Configuring and Deploying a Modular System

Applications are organized as a set of Java modules. When developing an application, simply organize your code as Java modules.    

Application modules are configured into a system and deployed. Unlike legacy runtimes that deploy archives to a server or are deployed as an "uber" jar, Rhizomatic systems are 
assembled from a layout. A layout can be a set of modules located in a Docker container or a an IDE project on a filesystem.
       
### The Boot Module 

A boot module is responsible for configuring a system from a layout. The boot module provides a Java module service that implements ```io.rhizomatic.api.SystemDefinition```:

```java
module bootstrap.dev {
    requires io.rhizomatic.api;

    provides SystemDefinition with DevSystemDefinition;
}
```  

The ```SystemDefinition``` implementation returns a set of ```RzLayer``` definitions that describes the JPMS layers and contained modules (including their location) to run.
Different boot modules can be loaded based on the runtime environment, e.g. "production" or "development". The Rhizomatic API contains a number of DSL classes for dynamically
defining layers and modules. For example, a layer can be deployed by defining a set of module inclusions from a filesystem directory such as an IDE project root.    
 
### The Assembly Plugin

A system image can be created using the Rhizomatic Gradle assembly plugin. The plugin will create an image from the required Rhzimatic libraries, application modules, and 
transitive third-party dependencies:

```groovy
apply plugin: 'io.rhizomatic.assembly'

def rzVersion = '1.0-SNAPSHOT'

rhizomaticAssembly {
    bootstrapModule = "bootstrap"
    bootstrapName = "bootstrap"
    appGroup = "io.massiv.sample"
}

dependencies {
    compile group: 'io.rhizomatic', name: 'rhizomatic-kernel', version: rzVersion
    compile group: 'io.rhizomatic', name: 'rhizomatic-inject', version: rzVersion
    compile group: 'io.rhizomatic', name: 'rhizomatic-web', version: rzVersion

    compile project(":app-module")
    compile project(":bootstrap")
}

```
Using the plugin, different runtime images can be created using various module combinations.

## Web Applications

A Rhizomatic system may also deploy one or more web applications. Web applications and their content locations are defined by the boot module ```SystemDefinition```. 

Note web applications do not need to be packaged as WAR files since many web applications do not make use of Java-based UI frameworks. For example, an Angular or React 
application can invoke REST resource services provided by another module.  

## Testing

Rhizomatic can be embedded in a test fixture. In a Gradle build file, set the test dependencies to include the required Rhizomatic dependencies (for example, if REST resources 
are not needed, do not include the Rhizomatic web extension):

```groovy
testCompile group: 'io.rhizomatic', name: 'rhizomatic-kernel', version: rzVersion
testCompile group: 'io.rhizomatic', name: 'rhizomatic-inject', version: rzVersion
testCompile group: 'io.rhizomatic', name: 'rhizomatic-web', version: rzVersion

``` 

In a test fixture, configure and start the system, passing in services, mocks, and suppporting classes:

```java
public class SomeTest{
    
    @Test
    public void testHello() {
        InjectionModule.install();
        WebModule.install();

        Rhizomatic.Builder builder = Rhizomatic.Builder.newInstance();
        Rhizomatic rhizomatic = builder.moduleMode(false).silent(true).services(HelloImpl.class, TestResource.class).build();

        // ....
        
        rhizomatic.start();
        rhizomatic.shutdown();
    }
}

```

## Monitoring and Logging
  
Rhizomatic does not provide a logging implementation. Instead it uses a monitoring interface, ```io.rhizomatic.api.Monitor```, that can be implemented by a module:

```java
module bootstrap.dev {
    requires io.rhizomatic.api;

    provides Monitor with CustomMonitor;
}

``` 
To enable the monitor implementation, the module must be laoded as a library (not an application module) since monitor messages are emitted during application boot.