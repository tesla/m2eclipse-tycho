for pattern in '*.sha1' '*.md5' _remote.repositories _maven.repositories m2e-lastUpdated.properties; do 
  find . -name $pattern -exec rm -f {} \;
done
