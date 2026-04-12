![Build project](https://github.com/netty/netty-incubator-codec-http3/workflows/Build%20project/badge.svg)

# netty-incubator-codec-http3
Experimental HTTP3 codec on top of our own [QUIC codec](https://github.com/netty/netty-incubator-codec-quic).

## How Can I use it ?

For some example usage please checkout our
[server example](https://github.com/netty/netty-incubator-codec-http3/blob/main/src/test/java/io/netty/incubator/codec/http3/example/Http3ServerExample.java) and
[client example](https://github.com/netty/netty-incubator-codec-http3/blob/main/src/test/java/io/netty/incubator/codec/http3/example/Http3ClientExample.java).

Publish as:
``` BASH
 BASE=https://secfotech.jfrog.io/artifactory/sophon-power-custom-3rd-party-local                                                                                                                                                                   
  REPO=/Users/alesnovak/.m2/repository/io/netty/incubator/netty-incubator-codec-http3/0.0.32.wtransport-SNAPSHOT
  PATH_PREFIX=io/netty/incubator/netty-incubator-codec-http3/0.0.32.wtransport-SNAPSHOT                                                                                                                                                             
  USER=$JFROG_SECFOTECH_USER                                                                                                                                                                                                                        
  TOKEN=$JFROG_SECFOTECH_TOKEN                                                                                                                                                                                                                      
                                                                                                                                                                                                                                                    
  for FILE in \                                             
    netty-incubator-codec-http3-0.0.32.wtransport-SNAPSHOT.pom \                                                                                                                                                                                    
    netty-incubator-codec-http3-0.0.32.wtransport-SNAPSHOT.jar \
    netty-incubator-codec-http3-0.0.32.wtransport-SNAPSHOT-sources.jar \                                                                                                                                                                            
    netty-incubator-codec-http3-0.0.32.wtransport-SNAPSHOT-test-sources.jar
  do                                                                                                                                                                                                                                                
    echo "Uploading $FILE..."                               
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \                                                                                                                                                                                            
      -u "$USER:$TOKEN" \                                                                                                                                                                                                                           
      -T "$REPO/$FILE" \
      "$BASE/$PATH_PREFIX/$FILE")                                                                                                                                                                                                                   
    echo "  → HTTP $HTTP_CODE"                              
  done
  ```