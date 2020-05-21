File aPatch = new File(basedir, "a/target/site/rewrite/rewrite.patch")
assert aPatch.isFile()

File bPatch = new File(basedir, "b/target/site/rewrite/rewrite.patch")
assert bPatch.isFile()