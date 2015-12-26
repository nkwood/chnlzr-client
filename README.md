# chnlzr-client

example client for chnlzr-server and chnlbrkr.

## Create chnlzr.properties
Copy `example-chnlzr.properties` to `chnlzr.properties` and modify as you see fit.

## Build
```
$ mvn package
```

## Run
```
$ ./run-client.sh brkr://localhost:9090 600000 12500 48000
$ ./run-client.sh chnlzr://localhost:8080 600000 12500 48000
```

## License

Copyright 2015 An Honest Effort LLC

Licensed under the GPLv3: http://www.gnu.org/licenses/gpl-3.0.html
