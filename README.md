# PCC Session Clustering between Heterogeneous App Servers
This is a sample application to show session caching between heterogeneous app servers with Pivotal Cloud Cache (PCC).

## Test Environment
1. PCC version: 1.7.x
2. Tested App Server
   - Tomcat
   - JBoss
   - Jeus (App Server by local vendor)

## Reference
https://docs.pivotal.io/p-cloud-cache/1-9/session-caching.html

## Prerequisite
### Create PCC instance
Create PCC instance to use session replication using `$ cf create-service p-cloudcache <PLAN_NAME> <SERVICE_INSTANCE_NAME> -t session-replication`.
For example: \
```
$ cf create-service p-cloudcache extra-small pcc-session-cache -t session-replication
```
For more information, refer this url https://docs.pivotal.io/p-cloud-cache/1-9/create-instance.html.

### Prepare jboss buildpack
https://github.com/cloudfoundry-community/jboss-buildpack

## PCC configuration
Every app server has their own session format, so it can be occurred errors to store session data of heterogeneous app servers. It is necessary to match the format. This is the process to match session data format.
Unfortunately, PCC doesn’t support to change configuration for gemfire properties. So we should change the configuration manually after creating PCC instances.

Option 1. Change configuration for each VM \
In the PCC VM list, we’ll change some configuration of gemfire servers. It can be identified by the name with `server/xxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.
For each server, execute below script.

You should ensure log messages to check configuration well. If you see below messages in `gemfire-server.stdout.log` in `/var/vcap/sys/log/gemfire-server` directory, it’s OK.
```
Instantiator registered with id 27315 class
```

Option 2. Replicate PCC tile manually \
TODO

## Deploy application
Now, it’s time to deploy application. Let's check gemfire configuration in your application.
It's no necessary for tomcat application because it's configured automatically during pushing app with java buildpack.

### Configure GemFire session libraries
#### Add GemFire session libraries in lib directory
Check the following jar files if there's located in jboss directory.
- `jboss/src/main/webapp/WEB-INF/lib/geode-modules-session-internal-9.7.2.jar`
- `jboss/src/main/webapp/WEB-INF/lib/pcc-client-auth-0.0.1.jar`

#### Add GemFire session libraries dependencies in pom.xml file
```
    <dependency>
        <groupId>io.pivotal.gemfire</groupId>
        <artifactId>geode-modules-session-internal</artifactId>
        <version>9.7.2</version>
    </dependency>
    <dependency>
        <groupId>io.pivotal.gemfire</groupId>
        <artifactId>geode-core</artifactId>
        <version>9.7.2</version>
    </dependency>

    <repository>
        <id>gemfire-release-repo</id>
        <name>Pivotal GemFire Release Repository</name>
        <url>https://commercial-repo.pivotal.io/data3/gemfire-release-repo/gemfire</url>
    </repository>
```

#### Add custom Session Filter class file
Check if there's located in the following location.
`jboss/src/main/java/org/apache/geode/modules/session/filter/SessionCachingFilter.java`

### Bind PCC service to application

#### Create service key of PCC instance
It's necessary to connect to PCC instance. Please execute following command to create service key using `cf csk <SERVICE_INSTANCE_NAME> <KEY_NAME>`. Or you can create it in Apps Manager.

For example,
```
$ cf csk pcc-session-cache my-service-key
```

After creating it, execute following command to check the locator IP addresses using `cf service-key <SERVICE_INSTANCE_NAME> <KEY_NAME>`.\

For example,
```
$ cf service-key pcc-session-cache my-service-key
```

#### Check cache-client.xml file
Change locator IPs in `jboss/src/main/webapp/WEB-INF/cache-client.xml`. After changing this configuration file, you should re-build source files with `mvn package` command.
Please refer following configuration.
```
<?xml version='1.0' encoding='UTF-8'?>
<client-cache xmlns='http://geode.apache.org/schema/cache' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='http://geode.apache.org/schema/cachehttp://geode.apache.org/schema/cache/cache-1.0.xsd' version='1.0'>
    <pool name='sessions' subscription-enabled='true' socket-buffer-size='65536'>
        <locator host='192.168.3.11' port='55221'/>
    </pool>
    <function-service>
        <function>
            <class-name>org.apache.geode.modules.util.CreateRegionFunction</class-name>
        </function>
        <function>
            <class-name>org.apache.geode.modules.util.TouchPartitionedRegionEntriesFunction</class-name>
        </function>
        <function>
            <class-name>org.apache.geode.modules.util.TouchReplicatedRegionEntriesFunction</class-name>
        </function>
        <function>
            <class-name>org.apache.geode.modules.util.RegionSizeFunction</class-name>
        </function>
    </function-service>
</client-cache>
```

### Deploy applications
Let's deploy  2 applications located in `jboss` and `tomcat` directory.

### Route domain
Let's map route with same domain for above 2 applications. For example, 
```
https://pivotal-session-test.<YOUR-DOMAIN>
```

## Test
Connect the url `https://pivotal-session-test.<YOUR-DOMAIN>` in web browser. Refresh this pace several times and ensure session data is sharing.

