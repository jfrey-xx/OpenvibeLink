#!/bin/bash

mvn install:install-file -Dfile=net.jar -DgroupId=processing -DartifactId=net -Dversion=2.2.1 -Dpackaging=jar

mvn install:install-file -Dfile=core.jar -DgroupId=processing -DartifactId=core -Dversion=2.2.1 -Dpackaging=jar
