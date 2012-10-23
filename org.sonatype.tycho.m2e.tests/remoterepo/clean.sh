for pattern in '*.sha1' '*.md5' _maven.repositories m2e-lastUpdated.properties; do 
  find . -name $pattern -exec rm -f {} \;
done
