import unittest
from source_guard import violation
from version import parse,advance
class SafetyTests(unittest.TestCase):
    def test_private_paths(self):
        for path in ["local.properties","x/.env","x/key.dpapi","x/build/a.txt","capture-private.png","x/release.apk","backup.bundle"]:
            self.assertIsNotNone(violation(path,b"normal"))
    def test_credential_content(self):
        self.assertIsNotNone(violation("source.txt",("gh"+"p_"+"A"*40).encode()))
        self.assertIsNotNone(violation("source.txt",b"-----BEGIN "+b"OPENSSH PRIVATE KEY-----"))
        self.assertIsNone(violation("android-app/gradle/wrapper/gradle-wrapper.jar",b"wrapper"))
    def test_symlink(self):
        self.assertIsNotNone(violation("source",b"outside","120000"))
    def test_version_policy(self):
        self.assertEqual(("1.7.1",4),advance("1.7",3,"patch"))
        self.assertEqual(("1.8",5),advance("1.7.2",4,"minor"))
        self.assertEqual(("2.0",5),advance("1.7",4,"major"))
        self.assertEqual(("1.7",5),advance("1.7",4,"build"))
    def test_bad_versions(self):
        for text in ["versionName=1.07\nversionCode=3","versionName=1.7\nversionCode=0","versionName=1.7\nversionCode=3\nversionCode=4"]:
            with self.assertRaises(ValueError):parse(text)
if __name__=="__main__":unittest.main()
