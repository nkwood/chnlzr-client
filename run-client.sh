#!/bin/bash
java -cp "target/chnlzr-client-0.2.0.jar" -Dorg.slf4j.simpleLogger.defaultLogLevel=debug org.anhonesteffort.chnlzr.ChnlzrClient "$@"
