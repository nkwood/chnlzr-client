#!/bin/bash
java -cp "target/chnlzr-client-1.0.jar" -Dio.netty.leakDetection.level=advanced -Dorg.slf4j.simpleLogger.defaultLogLevel=debug org.anhonesteffort.chnlzr.ChnlzrClient "$@"
