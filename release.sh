# Run before:
# export newVersion=1.0.0
export GPG_TTY=$(tty)
mvn versions:set -DnewVersion=${newVersion} -DgenerateBackupPoms=false
mvn clean deploy -Prelease
git add pom.xml
git commit -m "Release ${newVersion}"
git tag ${newVersion}
git push --tags
git reset HEAD~1
git checkout .