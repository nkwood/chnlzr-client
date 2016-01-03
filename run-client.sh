#!/bin/bash
java -cp "target/chnlzr-client-0.1.1.jar" -Dorg.slf4j.simpleLogger.defaultLogLevel=debug org.anhonesteffort.chnlzr.ChnlzrClient "$@"
