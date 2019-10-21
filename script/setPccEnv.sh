sudo su

#!/bin/bash -v

cd /var/vcap/jobs/gemfire-server/config

cat > mycache.xml << EOF
<?xml version="1.0" encoding="UTF-8"?>
<cache xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://geode.apache.org/schema/cache" xsi:schemaLocation="http://geode.apache.org/schema/cache http://geode.apache.org/schema/cache/cache-1.0.xsd" version="1.0" lock-lease="120" lock-timeout="60" search-timeout="300" is-server="false" copy-on-read="false">
<serialization-registration>
 <instantiator id="27315">
 <class-name>org.apache.geode.modules.session.internal.filter.GemfireHttpSession</class-name>
 </instantiator>
</serialization-registration>
</cache>
EOF

echo 'cache-xml-file=/var/vcap/jobs/gemfire-server/config/mycache.xml' >> gemfire.properties

cat gemfire.properties

sed 's/32768/65536/g' gemfire.properties > gemfire.properties.new

cp gemfire.properties.new gemfire.properties

cat gemfire.properties

rm -rf /tmp/AppServer

cd /
APP=`find . | grep AppServer`
mkdir /tmp/AppServer
unzip $APP -d /tmp/AppServer

cp /tmp/AppServer/lib/geode-modules-session-internal-9.7.2.jar /var/vcap/packages/gemfire/pivotal-gemfire/lib/
monit restart gemfire-server
sleep 20

monit summary

tail /var/vcap/sys/log/gemfire-server/gemfire/server.log

exit

exit
