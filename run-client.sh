#!/bin/bash
java -cp "target/chnlzr-client-0.3.3.jar" -Dorg.slf4j.simpleLogger.defaultLogLevel=debug org.anhonesteffort.chnlzr.ChnlzrClient "$@"
