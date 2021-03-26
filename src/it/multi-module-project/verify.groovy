File aPatch = new File(basedir, "a/target/site/rewrite/rewrite.patch")
assert aPatch.isFile()
File aCycloneDxBom = new File(basedir, "a/target/a-0.1.0-SNAPSHOT-cyclonedx.xml")
assert aCycloneDxBom.isFile()

File bPatch = new File(basedir, "b/target/site/rewrite/rewrite.patch")
assert bPatch.isFile()
File bCycloneDxBom = new File(basedir, "b/target/b-0.1.0-SNAPSHOT-cyclonedx.xml")
assert bCycloneDxBom.isFile()
