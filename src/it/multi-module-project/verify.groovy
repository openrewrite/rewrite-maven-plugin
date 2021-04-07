File aCycloneDxBom = new File(basedir, "a/target/a-0.1.0-SNAPSHOT-cyclonedx.xml")
assert aCycloneDxBom.isFile()

File bCycloneDxBom = new File(basedir, "b/target/b-0.1.0-SNAPSHOT-cyclonedx.xml")
assert bCycloneDxBom.isFile()
