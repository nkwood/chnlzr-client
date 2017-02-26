# chnlzr-client
Example [chnlzr-server](https://github.com/radiowitness/chnlzr-server) client implementation.

## Create chnlzr.properties
Copy `example-chnlzr.properties` to `chnlzr.properties` and modify as you see fit.

## Build
```
$ mvn package
```

## Run
```
$ ./run-client.sh chnlzr://localhost:7070 600000 12500 48000
```

## License
Copyright 2016 An Honest Effort LLC
Licensed under the GPLv3: http://www.gnu.org/licenses/gpl-3.0.html
