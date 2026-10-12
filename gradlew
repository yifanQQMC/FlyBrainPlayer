#!/bin/sh
#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
#
#   Gradle start up script for UN*X
#
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
APP_HOME=$( cd -P "$( dirname "$0" )" >/dev/null 2>&1 && pwd )
if [ -z "$APP_HOME" ] ; then
    echo "Failed to get APP_HOME" >&2
    exit 1
fi

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

# Find java
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executable
        JAVA_EXE="$JAVA_HOME/jre/sh/java"
    elif [ -x "$JAVA_HOME/bin/java" ] ; then
        JAVA_EXE="$JAVA_HOME/bin/java"
    else
        JAVA_EXE="$JAVA_HOME/jre/bin/java"
    fi
    if [ ! -x "$JAVA_EXE" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        echo "Please set the JAVA_HOME variable in your environment to match the" >&2
        echo "location of your Java installation." >&2
        exit 1
    fi
else
    JAVA_EXE="java"
    which java >/dev/null 2>&1 || ( echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2 && exit 1 )
fi

# Execute Gradle
exec "$JAVA_EXE" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"