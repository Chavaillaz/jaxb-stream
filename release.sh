# Same as -> export newVersion=x.x.x
echo "Enter version number (x.x.x): "
read newVersion
export GPG_TTY=$(tty)
mvn versions:set -DnewVersion=${newVersion} -DgenerateBackupPoms=false
# mvn clean deploy -Prelease -> Done by Github actions
git add .
git commit -m "Release ${newVersion}"
git tag ${newVersion}
git push --tags
git reset HEAD~1
git checkout .