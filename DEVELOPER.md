# Developing scala-effect-mcp

## Release

* Make sure your GPG key is loaded (eg using `gpg --detach-sign --armor --use-agent --output - README.md`)
* Update `version.sbt` to the new release version (non-snapshot)
* In sbt run
  * `clean`
  * `test` and see if everything compliles ok
  * `publishSigned` to create the artifacts
  * `sonaUpload` to upload to maven central portal
* Visit <https://central.sonatype.com/publishing> to check an publish it
* Push the changes
* Create a release in github <https://github.com/linkyard/scala-effect-mcp/releases/new>
  * Name: The version, e.g. `1.0.0`
  * Release Notes: not all things that changed
