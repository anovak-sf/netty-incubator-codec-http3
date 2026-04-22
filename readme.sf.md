Publish as:
``` BASH
 mvn compile test verify install
 BASE=https://secfotech.jfrog.io/artifactory/sophon-power-custom-3rd-party-local
 REPO=/Users/alesnovak/.m2/repository/io/netty/incubator/netty-incubator-codec-http3/0.0.31.wtransport-SNAPSHOT
 PATH_PREFIX=io/netty/incubator/netty-incubator-codec-http3/0.0.31.wtransport-SNAPSHOT
 USER=$JFROG_SECFOTECH_USER
 TOKEN=$JFROG_SECFOTECH_TOKEN                                                               
FILES=(
    netty-incubator-codec-http3-0.0.31.wtransport-SNAPSHOT.pom
    netty-incubator-codec-http3-0.0.31.wtransport-SNAPSHOT.jar
    netty-incubator-codec-http3-0.0.31.wtransport-SNAPSHOT-sources.jar
 )   

 for FILE in "${FILES[@]}"; do                             
    echo "Uploading $FILE..."                                                                                  
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "$USER:$TOKEN" -T "$REPO/$FILE" "$BASE/$PATH_PREFIX/$FILE")
    echo "  → HTTP $HTTP_CODE"                                                                         
  done
  ```
