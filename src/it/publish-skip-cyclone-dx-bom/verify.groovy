File astJar = new File(basedir, "target/publish-skip-cyclone-dx-bom-0.1.0-SNAPSHOT-ast.jar")
assert astJar.isFile()

File cycloneDxBom = new File(basedir, "target/publish-skip-cyclone-dx-bom-0.1.0-SNAPSHOT-cyclonedx.xml")
assert !cycloneDxBom.isFile() && !cycloneDxBom.exists()

