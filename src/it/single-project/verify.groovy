File patch = new File(basedir, "target/site/rewrite/rewrite.patch")
assert patch.isFile()

File cycloneDxBom = new File(basedir, "target/single-project-0.1.0-SNAPSHOT-cyclonedx.xml")
assert cycloneDxBom.isFile()
